package prdrive.engine

import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Results.kt — Cómo acabó la última pasada de cada pareja, y qué log lo
 * explica. Espejo de `common/results.py` y de la parte de `sync.py` que decide
 * qué log se guarda.
 *
 * Existe para que un fallo no se quede escondido. El log de una pasada fallida
 * está en `logs/`, pero nadie va a mirar ahí: la app puede pasarse días
 * fallando mientras el usuario cree que el móvil está al día. Con esto la
 * pantalla principal sabe, nada más abrirse, qué parejas fallaron y qué log lo
 * cuenta.
 *
 * **Una pareja SALTADA no se apunta.** No se ha ejecutado, así que su último
 * resultado de verdad sigue siendo el anterior, y de que pide un `--resync` ya
 * avisa su propio chip. Igual que en el escritorio, y por lo mismo: un
 * simulacro tampoco apunta nada, que si no un dry-run bueno taparía un fallo
 * real.
 *
 * **El fichero es el mismo que el del PC** (`state/last_run.json`, con las
 * mismas claves), porque el volumen de la app tiene la distribución de un
 * disco de verdad a propósito: lo que escribe el móvil lo puede leer el
 * programa de escritorio sin traducir nada.
 *
 * Y el log se guarda **solo si la pasada falló** ([disponerDelLog]), que es la
 * política de `dispose_log()`: en el escritorio ahorra ciclos de escritura del
 * disco extraíble, y aquí ahorra los del móvil. La diferencia es de dónde sale
 * el texto — allí rclone escribe un fichero con `--log-file`, aquí el log se
 * recoge en memoria y solo se escribe si hay que conservarlo.
 */

/** Una pareja cuya última pasada falló. */
data class Fallo(
    val pareja: String,
    /** El sello de cuando acabó la pasada. */
    val cuando: String,
    val codigo: Int,
    /** El log, o null si no quedó o ya no está. */
    val log: File?,
)

object Resultados {

    /** `state/last_run.json`. */
    const val FICHERO = "last_run.json"

    /** El formato de fecha de todos estos ficheros. De `store.stamp()`. */
    private val SELLO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Y el del nombre de un log, que va en el nombre del fichero. */
    private val SELLO_FICHERO = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun sello(cuando: LocalDateTime = LocalDateTime.now()): String = SELLO.format(cuando)

    /**
     * Apunta el resultado de una pasada. **Nunca falla**: si el volumen no
     * deja escribir, el aviso se pierde, pero la sincronización no puede
     * caerse por él.
     *
     * El log se guarda por su nombre y no por su ruta entera, igual que en el
     * escritorio: allí porque la letra de unidad cambia de un equipo a otro, y
     * aquí porque `filesDir` cambia al reinstalar la app.
     */
    fun apuntar(
        estadoDir: File,
        nombre: String,
        codigo: Int,
        log: File? = null,
        cuando: LocalDateTime = LocalDateTime.now(),
    ): Boolean {
        val fichero = File(estadoDir, FICHERO)
        val parejas = LinkedHashMap(leerParejas(fichero))
        parejas[nombre] = linkedMapOf<String, Any?>(
            "cuando" to sello(cuando),
            "codigo" to codigo.toLong(),
            "log" to log?.name,
        )
        return escribirJsonEnDisco(fichero, mapOf("parejas" to parejas))
    }

    /**
     * Las parejas cuya última pasada falló, en el orden que se pida.
     *
     * El orden es el del config y no el del fichero: lo que se enseña sigue el
     * orden en el que el usuario ve sus parejas.
     */
    fun fallos(estadoDir: File, logsDir: File, nombres: Iterable<String>): List<Fallo> {
        val parejas = leerParejas(File(estadoDir, FICHERO))
        val salida = ArrayList<Fallo>()
        for (nombre in nombres) {
            @Suppress("UNCHECKED_CAST")
            val dato = parejas[nombre] as? Map<String, Any?> ?: continue
            val codigo = when (val c = dato["codigo"]) {
                is Long -> c.toInt()
                is Int -> c
                is Double -> c.toInt()
                else -> continue
            }
            if (codigo == 0) continue
            val log = (dato["log"] as? String)
                ?.let { File(logsDir, File(it).name) }
                ?.takeIf { runCatching { it.isFile }.getOrDefault(false) }
            salida.add(Fallo(nombre, dato["cuando"]?.toString() ?: "", codigo, log))
        }
        return salida
    }

    /**
     * Guarda el log de una pasada en `logs/` y devuelve dónde quedó.
     *
     * El nombre lleva el sello hasta el segundo, y si ya existe se le añade un
     * número: una pasada y su reintento pueden caer en el mismo segundo
     * (`keep_log` de `sync.py` hace lo mismo).
     */
    fun guardarLog(
        logsDir: File,
        nombre: String,
        texto: String,
        cuando: LocalDateTime = LocalDateTime.now(),
    ): File? {
        return try {
            logsDir.mkdirs()
            val sello = SELLO_FICHERO.format(cuando)
            var destino = File(logsDir, "${nombre}_$sello.log")
            var n = 1
            while (destino.exists()) {
                destino = File(logsDir, "${nombre}_${sello}_$n.log")
                n++
            }
            destino.writeText(texto)
            destino
        } catch (e: IOException) {
            // Que no se pueda guardar el log no puede tumbar la pasada: el
            // texto se le sigue enseñando al usuario en la pantalla.
            null
        }
    }

    /**
     * Descarta el log si la pasada fue bien; si no, lo guarda.
     *
     * `siempre` es el `keep_logs` del config, que en el escritorio también
     * guarda los de las pasadas buenas.
     */
    fun disponerDelLog(
        logsDir: File,
        nombre: String,
        texto: String,
        codigo: Int,
        siempre: Boolean = false,
        cuando: LocalDateTime = LocalDateTime.now(),
    ): File? {
        if (codigo == 0 && !siempre) return null
        if (texto.isBlank()) return null
        return guardarLog(logsDir, nombre, texto, cuando)
    }

    // -----------------------------------------------------------------------

    private fun leerParejas(fichero: File): Map<String, Any?> {
        val texto = runCatching { fichero.readText() }.getOrNull() ?: return emptyMap()
        val raiz = runCatching { leerObjetoJson(texto) }.getOrNull() ?: return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return raiz["parejas"] as? Map<String, Any?> ?: emptyMap()
    }

    /**
     * Escribe el JSON de forma atómica: a un temporal y después un renombre.
     *
     * Es lo que hace `store.write_json`, y por lo mismo: un `last_run.json` a
     * medio escribir no se puede leer, y esto lo escribe justo al acabar una
     * pasada, que es cuando el usuario puede estar cerrando la app.
     */
    private fun escribirJsonEnDisco(fichero: File, datos: Map<String, Any?>): Boolean {
        return try {
            fichero.parentFile?.mkdirs()
            val tmp = File(fichero.parentFile, fichero.name + ".tmp")
            tmp.writeText(escribirJson(datos))
            if (!tmp.renameTo(fichero)) {
                // Windows no renombra sobre un fichero que existe; Android sí,
                // pero si el renombre no sale se borra y se reintenta antes de
                // rendirse — dejar el .tmp y nada más sería perder el aviso.
                fichero.delete()
                if (!tmp.renameTo(fichero)) {
                    tmp.delete()
                    return false
                }
            }
            true
        } catch (e: IOException) {
            false
        }
    }
}
