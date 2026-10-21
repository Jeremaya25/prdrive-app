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
import java.time.format.DateTimeParseException

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
    fun desdeSello(texto: String?): Long? {
        if (texto == null) return null
        return try {
            LocalDateTime.parse(texto, FORMATO).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
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
