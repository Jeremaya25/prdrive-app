package prdrive.engine

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Store.kt — Leer y escribir los ficheros pequeños de `state/` sin dejarlos a
 * medias.
 *
 * Espejo de la parte de `common/store.py` de prdrive que necesita la app:
 * `stamp()`, `desde_sello()`, `read_json()`, `write_json()` y `write_text()`.
 * Lo demás de ese módulo (procesos, cerrojos entre programas, el diario, la
 * espera a Windows) es del escritorio.
 *
 * Los ficheros de estado son los mismos que escribe el escritorio y con el
 * mismo formato —el volumen tiene la distribución de un dispositivo—, así que
 * el programa de escritorio puede leer lo que escribe el teléfono.
 */
object Store {

    /** `"%Y-%m-%d %H:%M:%S"`: el formato de las fechas de todos estos ficheros. */
    const val FORMATO_PY = "%Y-%m-%d %H:%M:%S"

    private val FORMATO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** La fecha de ahora, como se escribe en todos estos ficheros. */
    fun stamp(): String = sello(LocalDateTime.now())

    /** Una fecha cualquiera en el formato de [stamp]. */
    fun sello(cuando: LocalDateTime): String = cuando.format(FORMATO)

    /**
     * Los milisegundos de época de un sello de [stamp], en la hora local (como
     * `datetime.strptime(...).timestamp()`), o `null` si no es una fecha suya.
     */
    fun desdeSello(texto: String?): Long? =
        leerSello(texto)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

    /**
     * `datetime.strptime(texto, FORMATO_PY)`, o `null` donde Python lanzaría.
     *
     * strptime es más manso que un `DateTimeFormatter`: cada campo admite una
     * cifra sola (`2024-1-5 3:4:5`), el espacio del formato casa con cualquier
     * tira de espacios de Python, y donde su regex dice `\d` vale una cifra de
     * cualquier alfabeto (el año en cifras de ancho completo, sí; el mes en
     * árabe, no, porque su regex es `1[0-2]|0[1-9]|[1-9]`). Las regex son las
     * de `_strptime.TimeRE`; luego `datetime()` rechaza lo que no es una fecha
     * (un 30 de febrero, el segundo 60, el año 0).
     */
    fun leerSello(texto: String?): LocalDateTime? {
        val m = texto?.let { SELLO_PY.matchEntire(it) } ?: return null
        val (a, mes, dia, h, min, s) = m.destructured
        val ano = a.toInt()
        if (ano < 1) return null
        return try {
            LocalDateTime.of(ano, mes.toInt(), dia.trim().toInt(), h.trim().toInt(), min.toInt(), s.toInt())
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    /** `_strptime.TimeRE().pattern(FORMATO_PY)`, con el `\s` y el `\d` de Python. */
    private val SELLO_PY: Regex by lazy {
        val d = "\\p{Nd}"
        val espacio = (0..0xffff).filter { espacioPy(it.toChar()) }
            .joinToString("", "[", "]+") { "\\x{${it.toString(16)}}" }
        Regex(
            "($d$d$d$d)-(1[0-2]|0[1-9]|[1-9])-(3[0-1]|[1-2]$d|0[1-9]|[1-9]| [1-9])" +
                "$espacio(2[0-3]|[0-1]$d|$d| $d):([0-5]$d|$d):(6[0-1]|[0-5]$d|$d)",
        )
    }

    /**
     * Lee un JSON que es un objeto. Si falta, está a medias o no lo es,
     * devuelve un mapa vacío: quien lo lee es un aviso, y un aviso no puede
     * tumbar nada.
     */
    fun readJson(fichero: File): Map<String, Any?> {
        val datos = try {
            leerJson(fichero.readText(Charsets.UTF_8))
        } catch (_: IOException) {
            return emptyMap()
        } catch (_: JsonError) {
            return emptyMap()
        }
        @Suppress("UNCHECKED_CAST")
        return datos as? Map<String, Any?> ?: emptyMap()
    }

    /** Escribe un JSON de forma atómica. `false` si no se ha podido. */
    fun writeJson(fichero: File, datos: Map<String, Any?>): Boolean =
        writeText(fichero, escribirJson(datos))

    /**
     * Escribe un texto de forma atómica: el fichero nunca queda a medias ni
     * vacío. Como `store.write_text()`, el temporal (`<nombre>.tmp`) se crea de
     * nuevo y en exclusiva —el que hubiera, de un corte o un enlace que alguien
     * dejó ahí, se quita antes— y se renombra encima del destino.
     *
     * @return `false` si no se ha podido escribir.
     */
    fun writeText(fichero: File, texto: String): Boolean {
        val temporal = File(fichero.parentFile, fichero.nameWithoutExtension + ".tmp").toPath()
        return try {
            Files.deleteIfExists(temporal)
            Files.write(
                temporal,
                texto.toByteArray(Charsets.UTF_8),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(
                    temporal, fichero.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporal, fichero.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (_: IOException) {
            false
        }
    }
}
