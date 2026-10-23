package prdrive.engine

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Kdbx.kt — Mirar si una base de KeePassXC está entera, sin contraseña y sin
 * descifrar nada.
 *
 * Espejo de `common/kdbx.py` de prdrive. Es la red que impide subir una base a
 * medio guardar: KeePassXC escribe en un temporal y lo renombra al final
 * (`QSaveFile`), pero si la persona contestó «Deshabilitar» a «¿Desactivar
 * almacenajes seguros?» escribe encima del fichero, y una pasada que lo lea en
 * ese momento subiría media base. La otra red es que rclone ya falla un fichero
 * que cambia mientras lo sube.
 *
 * Lo que se puede saber sin la clave sale del formato KDBX
 * (`src/format/KeePass2.h`, `Kdbx4Reader.cpp` y `Kdbx3Reader.cpp` de KeePassXC
 * 2.7.12, y la especificación de KeePass 2.x):
 *
 * - Las dos firmas al principio: `0x9AA2D903` y `0xB54BFB67` (little-endian).
 *   Después, la versión: menor y mayor, dos `uint16`.
 * - La cabecera: campos `id` (1 byte), tamaño y datos, hasta el de `id` 0
 *   (`EndOfHeader`). El tamaño es `uint16` en KDBX 3 y `uint32` en KDBX 4.
 * - KDBX 4, además: tras la cabecera, su SHA-256 (32 bytes), que se comprueba,
 *   y su HMAC (32 bytes), que necesita la clave. Luego los bloques HMAC
 *   (`HmacBlockStream`): 32 bytes de HMAC, un `int32` con el tamaño y los
 *   datos. El último bloque mide 0, y tiene que acabar justo al final del
 *   fichero.
 *
 * En KDBX 3 el cuerpo cifrado no dice dónde acaba sin descifrarlo. Lo que sí se
 * sabe es que con AES o Twofish va en CBC con relleno PKCS#7, así que mide un
 * múltiplo de 16 bytes (campo 2, `CipherID`, `KeePass2::CIPHER_AES256` y
 * `CIPHER_TWOFISH`): un corte al azar se ve 15 veces de cada 16. Con otro
 * cifrado solo se comprueba la cabecera. Un formato que no se conoce no bloquea
 * nada: «no se sabe» (`null`) no es «está rota».
 *
 * Los textos de `motivo` son los de prdrive, letra por letra: los compara el
 * test contra lo que contesta Python.
 */
object Kdbx {

    /**
     * Lo que se sabe de una base sin abrirla.
     *
     * @property entera `true` si todo lo que se puede comprobar cuadra, `false`
     *   si algo no, y `null` si el formato no se conoce.
     * @property version `4.1`, `3.1`…, o vacío si no se lee.
     * @property motivo Qué falla, para decirlo; vacío si nada.
     */
    data class Estado(val entera: Boolean?, val version: String = "", val motivo: String = "")

    /** Primera firma de un fichero de KeePass 2.x (`KeePass2::SIGNATURE_1`). */
    private const val FIRMA_1 = 0x9AA2D903L

    /** Segunda firma de KDBX (`KeePass2::SIGNATURE_2`); la de KeePass 1.x es otra. */
    private const val FIRMA_2 = 0xB54BFB67L

    /** `id` del campo que cierra la cabecera (`EndOfHeader`). */
    private const val FIN_CABECERA = 0

    /** `id` del campo del cifrado (`CipherID`), un UUID de 16 bytes. */
    private const val CIFRADO = 2

    /** AES-256 y Twofish (`KeePass2::CIPHER_AES256`, `CIPHER_TWOFISH`): CBC, bloques de 16. */
    private val CIFRADOS_DE_BLOQUE = listOf(
        "31c1f2e6bf714350be5805216afc5aff",
        "ad68f29f576f4bb9a36ad47af965346c",
    ).map { texto -> ByteArray(16) { texto.substring(2 * it, 2 * it + 2).toInt(16).toByte() } }

    private const val BLOQUE = 16

    /** Bytes del SHA-256 de la cabecera, y también los de su HMAC y los de cada bloque. */
    private const val TAM_SUMA = 32

    /** Cabecera leída: su estado, dónde acaba (o -1 si no se ha podido leer) y su `CipherID`. */
    private class Cabecera(val estado: Estado, val fin: Int, val cifrado: ByteArray = ByteArray(0))

    /** Un entero sin signo little-endian de `bytes` bytes. */
    private fun uint(datos: ByteArray, pos: Int, bytes: Int): Long {
        var n = 0L
        for (i in bytes - 1 downTo 0) n = (n shl 8) or (datos[pos + i].toLong() and 0xFF)
        return n
    }

    /** Lee las firmas y la cabecera. `estado.entera` es `true` si la cabecera llega entera. */
    private fun cabecera(datos: ByteArray): Cabecera {
        if (datos.size < 12) {
            return Cabecera(Estado(false, motivo = "es demasiado corta para ser una base"), -1)
        }
        if (uint(datos, 0, 4) != FIRMA_1 || uint(datos, 4, 4) != FIRMA_2) {
            return Cabecera(Estado(false, motivo = "no empieza como una base de KeePassXC"), -1)
        }
        val menor = uint(datos, 8, 2)
        val mayor = uint(datos, 10, 2)
        val version = "$mayor.$menor"
        if (mayor != 3L && mayor != 4L) {
            return Cabecera(Estado(null, version, "es una versión de KDBX que no se conoce ($version)"), -1)
        }
        val paso = if (mayor == 3L) 2 else 4
        var pos = 12L
        var cifrado = ByteArray(0)
        while (true) {
            if (pos + 1 + paso > datos.size) {
                return Cabecera(Estado(false, version, "la cabecera está cortada"), -1)
            }
            val campo = datos[pos.toInt()].toInt() and 0xFF
            val largo = uint(datos, pos.toInt() + 1, paso)
            pos += 1 + paso
            if (pos + largo > datos.size) {
                return Cabecera(Estado(false, version, "la cabecera está cortada"), -1)
            }
            if (campo == CIFRADO) cifrado = datos.copyOfRange(pos.toInt(), (pos + largo).toInt())
            pos += largo
            if (campo == FIN_CABECERA) return Cabecera(Estado(true, version), pos.toInt(), cifrado)
        }
    }

    /** Comprueba una base entera en memoria (ver el KDoc del objeto). */
    fun mirar(datos: ByteArray): Estado {
        val cab = cabecera(datos)
        val estado = cab.estado
        val fin = cab.fin
        if (fin < 0) return estado
        if (estado.version.startsWith("3.")) {
            val cuerpo = datos.size - fin
            if (CIFRADOS_DE_BLOQUE.any { it.contentEquals(cab.cifrado) } &&
                (cuerpo <= 0 || cuerpo % BLOQUE != 0)
            ) {
                return estado.copy(entera = false, motivo = "está cortada")
            }
            return estado
        }
        if (fin + 2 * TAM_SUMA > datos.size) {
            return estado.copy(entera = false, motivo = "falta la suma de la cabecera")
        }
        val suma = MessageDigest.getInstance("SHA-256").digest(datos.copyOfRange(0, fin))
        if (!suma.contentEquals(datos.copyOfRange(fin, fin + TAM_SUMA))) {
            return estado.copy(entera = false, motivo = "la suma de la cabecera no cuadra")
        }
        var pos = fin + 2 * TAM_SUMA
        while (true) {
            if (pos + TAM_SUMA + 4 > datos.size) {
                return estado.copy(entera = false, motivo = "está cortada")
            }
            // `int32` con signo: un tamaño negativo es un bloque imposible.
            val largo = uint(datos, pos + TAM_SUMA, 4).toInt()
            pos += TAM_SUMA + 4
            if (largo < 0 || pos.toLong() + largo > datos.size) {
                return estado.copy(entera = false, motivo = "está cortada")
            }
            pos += largo
            if (largo == 0) {
                if (pos != datos.size) {
                    return estado.copy(entera = false, motivo = "lleva datos después del final")
                }
                return estado
            }
        }
    }

    /**
     * Comprueba la base de ese fichero.
     *
     * Una base son unos cientos de KB: se lee entera. Lo que no se puede leer
     * cuenta como no entero, porque eso es lo que habría subido.
     */
    fun comprobar(fichero: File): Estado {
        val datos = try {
            fichero.readBytes()
        } catch (e: IOException) {
            return Estado(false, motivo = "no se puede leer (${e.message})")
        }
        return mirar(datos)
    }
}
