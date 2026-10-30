package prdrive.app.compartidas

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import prdrive.app.Prdrive
import prdrive.app.rclone.Adaptadores
import prdrive.app.rclone.Rclone
import prdrive.engine.Compartidas
import prdrive.engine.Mudanza
import prdrive.engine.Pasada

/**
 * Compartir.kt — Lo que la app pone para que una pareja viva en una carpeta
 * compartida: el permiso, la carpeta que elige la persona, la prueba de que sus
 * fechas valen y el [Mudanza.Entorno] que la pantalla le da a [Mudanza].
 *
 * El motor decide qué vale y en qué orden; aquí solo se habla con Android y con
 * rclone. El diseño, en `docs/superpowers/specs/2026-10-26-carpeta-compartida-design.md`
 * (§2 y §4).
 */
object Compartir {

    /** El proveedor de documentos del almacenamiento del teléfono. */
    const val AUTORIDAD = "com.android.externalstorage.documents"

    /**
     * Lo que se le enseña a la persona antes de mover una pareja: fuera del
     * volumen privado, la carpeta es de todos los que tengan el permiso.
     */
    const val AVISO_EXPOSICION =
        "Cualquier app con acceso a todos los archivos podrá leer y cambiar esta carpeta, y se verá " +
            "al conectar el teléfono a un ordenador. La clave y el resto del volumen siguen privados."

    /** La hora del fichero de prueba: pasada, y con milisegundos que no son cero. */
    private const val HORA_DE_PRUEBA_MS = 1_700_000_000_123L

    /**
     * La ruta de la carpeta que ha elegido la persona (el URI de árbol del
     * selector de carpetas), o `null` si no es una que la app pueda usar.
     *
     * Solo vale el almacenamiento principal del proveedor [AUTORIDAD], que es
     * el único que rclone ve como una ruta (`/storage/emulated/0/…`): el id del
     * árbol es `primary:<ruta>`. Una SD (`1234-5678:…`), otro proveedor
     * (Descargas, una nube), un documento suelto o el almacenamiento entero
     * (`primary:` sin ruta) no valen. Por qué no, lo dice quien llama.
     */
    fun rutaDeArbol(arbol: Uri): File? {
        if (arbol.authority != AUTORIDAD || !DocumentsContract.isTreeUri(arbol)) return null
        val id = DocumentsContract.getTreeDocumentId(arbol)
        if (!id.startsWith("primary:")) return null
        val ruta = id.removePrefix("primary:")
        if (ruta.isEmpty()) return null
        return File(Environment.getExternalStorageDirectory(), ruta)
    }

    /**
     * Si [carpeta] guarda las fechas de modificación como bisync necesita: `null`
     * si sí, o por qué no. Copia un fichero de prueba con una hora conocida
     * (desde [temporal], una carpeta de trabajo de la app), mira la hora que
     * queda, la precisión que dice rclone y la hora otra vez dos segundos
     * después; decide [Compartidas.problemaFechas].
     *
     * Deja la carpeta exactamente como la encontró, ande bien o mal: el fichero
     * de prueba se borra siempre, y la carpeta también si no existía (rclone la
     * crea al copiar). Mudanza solo quita una carpeta que creó si está vacía, y
     * un resto se subiría con el `--resync`. Si ni así se puede borrar, es un
     * motivo más, con el nombre del resto para que la persona lo quite. Un error de rclone no se propaga: es la respuesta (su mensaje).
     */
    fun probarFechas(carpeta: File, temporal: File): String? {
        val nombre = "prdrive-prueba-fechas-${System.nanoTime()}.tmp"
        val origen = File(temporal, "fechas-${System.nanoTime()}")
        val destino = carpeta.absolutePath
        // `copyfile` crea la carpeta si no está: si no estaba, se quita al acabar.
        val existia = carpeta.exists()
        val resultado = try {
            if (!origen.mkdirs()) throw IOException("no se pudo crear $origen")
            val fichero = File(origen, nombre).apply { writeText("prueba de fechas") }
            if (!fichero.setLastModified(HORA_DE_PRUEBA_MS)) throw IOException("no se pudo poner la hora a $fichero")
            Rclone.rpc(
                "operations/copyfile",
                mapOf(
                    "srcFs" to origen.absolutePath, "srcRemote" to nombre,
                    "dstFs" to destino, "dstRemote" to nombre,
                ),
            )
            val original = horaDe(origen.absolutePath, nombre)
            val copiada = horaDe(destino, nombre)
            val nanos = (Rclone.rpc("operations/fsinfo", mapOf("fs" to destino))["Precision"] as? Number)?.toLong()
                ?: throw IOException("rclone no dice qué precisión tiene la carpeta")
            Thread.sleep(2000)
            val releida = horaDe(destino, nombre)
            Compartidas.problemaFechas(original, copiada, releida, Duration.ofNanos(nanos))
        } catch (e: Rclone.RcloneError) {
            "No se han podido probar las fechas de '$destino': ${e.message}"
        } catch (e: IOException) {
            "No se han podido probar las fechas de '$destino': ${e.message}"
        } catch (e: DateTimeParseException) {
            "No se han podido probar las fechas de '$destino': rclone ha dado una hora que no se entiende (${e.message})"
        } finally {
            origen.deleteRecursively()
            borrar(destino, nombre)
            // `File.delete()` no quita una carpeta con algo dentro: solo la vacía.
            if (!existia) carpeta.delete()
        }
        val detalle = resultado?.let { " ($it)" } ?: ""
        if (File(carpeta, nombre).exists()) {
            return "No se ha podido borrar el fichero de prueba '$nombre' de '$destino'. Bórralo a mano " +
                "y vuelve a intentarlo.$detalle"
        }
        if (!existia && carpeta.exists()) {
            return "No se ha podido quitar la carpeta '$destino', que la prueba de fechas ha creado. " +
                "Bórrala a mano y vuelve a intentarlo.$detalle"
        }
        return resultado
    }

    /** La hora de modificación de [remoto] en [fs]: rclone la da con el desfase local, así que se lee como `OffsetDateTime`. */
    private fun horaDe(fs: String, remoto: String): Instant {
        val item = Rclone.rpc("operations/stat", mapOf("fs" to fs, "remote" to remoto))["item"] as? Map<*, *>
            ?: throw IOException("rclone no encuentra '$remoto' en '$fs' después de copiarlo")
        return OffsetDateTime.parse(item["ModTime"].toString()).toInstant()
    }

    /** Borra el fichero de prueba, por rclone y, si no, directamente; sin quejarse. */
    private fun borrar(fs: String, remoto: String) {
        try {
            Rclone.rpc("operations/deletefile", mapOf("fs" to fs, "remote" to remoto))
        } catch (_: Rclone.RcloneError) {
            // Puede no haberse llegado a copiar. Si está, se borra a mano.
        }
        File(fs, remoto).delete()
    }

    /**
     * La pantalla de Android en la que se da a prdrive el permiso de acceso a
     * todos los archivos. Quien llama la lanza y, al volver, mira
     * [Prdrive.permisoCompartido]: no hay resultado que esperar.
     */
    fun pedirPermiso(contexto: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${contexto.packageName}"))
            .apply { if (contexto !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /**
     * Todo lo que [Mudanza] necesita de la app. Rclone tiene que estar iniciado
     * ([Prdrive.iniciarRclone]): las opciones se leen de él. El `recargar` deja
     * que el error de rclone suba: Mudanza lo cuenta como un paso fallido.
     */
    fun entorno(
        contexto: Context,
        keepLogs: Boolean,
        alProgreso: (Map<String, Any?>) -> Unit,
        decir: (String) -> Unit,
        alPaso: (Mudanza.Paso) -> Unit = {},
    ): Mudanza.Entorno = Mudanza.Entorno(
        rclone = Adaptadores.pasada,
        opciones = Adaptadores.opciones,
        ctx = Pasada.Contexto(keepLogs = keepLogs, accesoCompartido = Prdrive.accesoCompartido()),
        probarFechas = { probarFechas(it, Prdrive.temporal(contexto)) },
        recargar = { Prdrive.recargarRclone(contexto) },
        alProgreso = alProgreso,
        decir = decir,
        alPaso = alPaso,
    )
}
