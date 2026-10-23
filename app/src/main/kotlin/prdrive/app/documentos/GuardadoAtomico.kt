package prdrive.app.documentos

import android.os.Handler
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import prdrive.engine.Kdbx
import prdrive.engine.LLAVERO_LLAVE
import prdrive.engine.LLAVERO_LOCAL
import prdrive.engine.Store

/**
 * GuardadoAtomico.kt — Las bases del llavero, guardadas enteras o no guardadas.
 *
 * En el escritorio prdrive obliga a KeePassXC a guardar de forma atómica
 * (`UseAtomicSaves`, H-11 en prdrive): escribe a un temporal y lo pone encima
 * de la base de un solo golpe, así que la pasada del llavero nunca ve —ni sube—
 * una base a medio escribir. En el teléfono la app de KeePass (KeePassDX,
 * Keepass2Android) escribe a través de [VolumenProvider], y lo hace como quiere:
 * un guardado cortado (la app muere, se queda sin batería) dejaría la base
 * rota, y la pasada siguiente la subiría. Así que el guardado atómico lo hace
 * el proveedor, y solo para los `.kdbx` que cuelgan directamente de
 * `.keychain/` ([esBase]): la base y sus copias de conflicto.
 *
 * - El cliente recibe un temporal en `.prdrive/tmp/` ([abrir]), nunca la base:
 *   el mismo sistema de ficheros que el volumen (el cambio de nombre es
 *   atómico), fuera de `.keychain/` (ninguna pasada lo ve) y fuera del alcance
 *   del proveedor. Su nombre no acaba en `.kdbx`.
 * - Con `"w"` o `"wt"`, el temporal empieza vacío. El `"w"` de Android no
 *   trunca (`FileUtils.translateModeStringToPosix` no le pone `O_TRUNC`): si se
 *   abriera la base, un guardado más corto dejaría basura al final.
 * - Con `"rw"`, `"rwt"` o `"wa"`, empieza con una copia de la base: el cliente
 *   puede leerla o añadir.
 * - Al cerrar: con error o con el cliente muerto, fuera el temporal; limpio,
 *   `fsync`, [Kdbx.comprobar] y, si no está rota (entera o de formato
 *   desconocido, como en el escritorio), encima de la base con `ATOMIC_MOVE`.
 *   Rota, fuera y se avisa ([AVISO]).
 *
 * Dos escritores a la vez llevan cada uno su temporal: gana el último que
 * cierra, como con dos KeePassXC.
 */
internal object GuardadoAtomico {

    private const val ETIQUETA = "GuardadoAtomico"

    /** El aviso de un guardado que llegó roto, en `state/`. Lo lee la pantalla. */
    const val AVISO = "llavero-cortado.json"

    /** La carpeta de los temporales dentro de `.prdrive/`. */
    const val TEMPORALES = "tmp"

    /** Lo que tiene que tener un temporal huérfano para que se borre al arrancar. */
    val HUERFANO: Duration = Duration.ofDays(1)

    /**
     * Si el id (sus tramos) es una base del llavero: un `.kdbx` justo dentro de
     * `.keychain/`, que no sea una copia de seguridad de KeePassXC (`.old.kdbx`).
     */
    fun esBase(tramos: List<String>): Boolean =
        tramos.size == 2 && tramos[0] == LLAVERO_LOCAL &&
            tramos[1].endsWith(".kdbx") && !tramos[1].endsWith(".old.kdbx")

    /** Si el id es la llave del llavero (`.keychain/llave.keyx`): se ve y no se toca. */
    fun esLlave(tramos: List<String>): Boolean = tramos == listOf(LLAVERO_LOCAL, LLAVERO_LLAVE)

    /**
     * Abre para escribir un temporal en [temporales] en lugar de [base], y lo
     * pone encima de ella al cerrar si llega entero.
     *
     * @param base la entrada de `.keychain/` que se guarda (la ruta léxica: si
     *   es un enlace, se sustituye el enlace, no lo que señala).
     * @param modo el modo de `openDocument`, con una `w`.
     * @param handler el hilo donde se atiende el cierre.
     * @param alPoner lo que se hace tras poner una base nueva.
     * @param alCortar lo que se hace con un guardado que ha llegado roto, con
     *   lo que dice [Kdbx.comprobar].
     */
    fun abrir(
        base: File,
        modo: String,
        temporales: File,
        handler: Handler,
        alPoner: () -> Unit,
        alCortar: (String) -> Unit,
    ): ParcelFileDescriptor {
        if (!temporales.isDirectory && !temporales.mkdirs()) throw IOException("No se ha podido crear $temporales")
        // Único y sin `.kdbx` al final: nada lo toma por una base.
        val tmp = File.createTempFile("guardado-", ".tmp", temporales)
        try {
            if (modo.startsWith("rw") || modo.contains('a')) {
                Files.copy(base.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.parseMode(modo), handler) { error ->
                alCerrar(tmp, base, error, alPoner, alCortar)
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    private fun alCerrar(tmp: File, base: File, error: IOException?, alPoner: () -> Unit, alCortar: (String) -> Unit) {
        if (error != null) {
            // El cliente cerró con error o murió: lo que haya en el temporal no
            // es un guardado acabado.
            Log.i(ETIQUETA, "Guardado de ${base.name} sin acabar: ${error.message}")
            tmp.delete()
            return
        }
        val estado = try {
            // Lo escrito, en el disco antes de mirarlo y de ponerlo: si se va la
            // luz justo después del cambio de nombre, que no quede una base vacía.
            FileChannel.open(tmp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            Kdbx.comprobar(tmp)
        } catch (e: IOException) {
            Kdbx.Estado(false, motivo = "no se ha podido guardar (${e.message})")
        }
        if (estado.entera == false) {
            tmp.delete()
            avisar { alCortar(estado.motivo) }
            return
        }
        try {
            Files.move(tmp.toPath(), base.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            tmp.delete()
            avisar { alCortar("no se ha podido poner en su sitio (${e.message})") }
            return
        }
        sincronizarCarpeta(base.parentFile)
        avisar(alPoner)
    }

    /** Lo de después no tumba el proceso: el listener corre en el hilo del proveedor. */
    private fun avisar(que: () -> Unit) {
        try {
            que()
        } catch (e: RuntimeException) {
            Log.w(ETIQUETA, "Tras un guardado del llavero", e)
        }
    }

    /** El cambio de nombre, en el disco: `fsync` de la carpeta. Si no se puede, se queda en la caché. */
    private fun sincronizarCarpeta(carpeta: File?) {
        if (carpeta == null) return
        try {
            val fd = Os.open(carpeta.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            Log.w(ETIQUETA, "fsync de $carpeta", e)
        }
    }

    /** Apunta en `state/` ([AVISO]) un guardado de [fichero] que llegó roto. */
    fun apuntarCortado(state: File, fichero: String, motivo: String) {
        state.mkdirs()
        Store.writeJson(File(state, AVISO), mapOf("cuando" to Store.stamp(), "fichero" to fichero, "motivo" to motivo))
    }

    /** Quita el aviso: un guardado bueno lo deja atrás. */
    fun quitarCortado(state: File) {
        File(state, AVISO).delete()
    }

    /**
     * Borra los temporales de más de [HUERFANO]: los del proceso que murió con
     * uno abierto. Uno de un guardado en curso se escribe ahora, y es reciente.
     */
    fun limpiar(temporales: File, ahora: Long = System.currentTimeMillis()) {
        for (f in temporales.listFiles() ?: return) {
            if (f.isFile && ahora - f.lastModified() > HUERFANO.toMillis()) f.delete()
        }
    }
}
