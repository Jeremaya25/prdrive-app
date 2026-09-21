package prdrive.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import prdrive.engine.Volumen
import java.io.File
import java.io.FileNotFoundException

/**
 * Archivos.kt — El volumen, publicado en la app de Archivos del sistema.
 *
 * Es lo que hace que esto sea un «disco» y no una caja negra: la raíz sale como
 * una ubicación «prdrive» en la app de Archivos y en los diálogos de
 * abrir/guardar de otras apps, así que se puede meter un fichero ahí desde
 * cualquier sitio y se sincroniza en la pasada siguiente. Sin un permiso, y sin
 * darle a esta app acceso a nada de las otras.
 *
 * Dos decisiones que no son de adorno:
 *
 *  - **`.prdrive/` no se enseña.** Ahí están el `rclone.conf` con la clave, los
 *    listados de bisync y el estado. Un usuario que borre un `.lst` desde la
 *    app de Archivos deja la pareja pidiendo `--resync`; uno que borre
 *    `keys/` la deja sin conexión. Y nada de eso avisaría.
 *  - **Las fechas de modificación funcionan**, porque esto es almacenamiento
 *    normal de la app. Es justo lo que hace poco fiable a bisync en tarjetas
 *    SD y discos USB, y la razón por la que el volumen es una carpeta privada
 *    en vez de la SD.
 */
class Archivos : DocumentsProvider() {

    private val volumen: Volumen get() = (context!!.applicationContext as Aplicacion).volumen

    override fun onCreate(): Boolean = true

    // -----------------------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: COLUMNAS_RAIZ)
        volumen.crear()
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, RAIZ)
            add(Root.COLUMN_DOCUMENT_ID, idDe(volumen.raiz))
            add(Root.COLUMN_TITLE, "prdrive")
            add(Root.COLUMN_SUMMARY, "El volumen que se sincroniza")
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or
                    Root.FLAG_LOCAL_ONLY,
            )
            add(Root.COLUMN_AVAILABLE_BYTES, volumen.raiz.usableSpace)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: COLUMNAS_DOC)
        fila(cursor, ficheroDe(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: COLUMNAS_DOC)
        val padre = ficheroDe(parentDocumentId)
        for (hijo in padre.listFiles().orEmpty().sortedBy { it.name }) {
            if (oculto(hijo)) continue
            fila(cursor, hijo)
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = ParcelFileDescriptor.open(
        ficheroDe(documentId),
        ParcelFileDescriptor.parseMode(mode),
    )

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val padre = ficheroDe(parentDocumentId)
        val nuevo = File(padre, displayName)
        if (mimeType == Document.MIME_TYPE_DIR) {
            if (!nuevo.mkdir()) throw FileNotFoundException("No se puede crear '$displayName'")
        } else if (!nuevo.createNewFile()) {
            throw FileNotFoundException("No se puede crear '$displayName'")
        }
        return idDe(nuevo)
    }

    override fun deleteDocument(documentId: String) {
        val fichero = ficheroDe(documentId)
        if (!fichero.deleteRecursively()) {
            throw FileNotFoundException("No se puede borrar '${fichero.name}'")
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith(parentDocumentId)

    // -----------------------------------------------------------------------

    private fun fila(cursor: MatrixCursor, fichero: File) {
        val esDir = fichero.isDirectory
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, idDe(fichero))
            add(Document.COLUMN_DISPLAY_NAME, if (fichero == volumen.raiz) "prdrive" else fichero.name)
            add(Document.COLUMN_SIZE, fichero.length())
            add(Document.COLUMN_LAST_MODIFIED, fichero.lastModified())
            add(Document.COLUMN_MIME_TYPE, if (esDir) Document.MIME_TYPE_DIR else tipo(fichero))
            add(
                Document.COLUMN_FLAGS,
                (if (esDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE) or
                    Document.FLAG_SUPPORTS_DELETE,
            )
        }
    }

    /**
     * El id de un documento es su ruta relativa a la raíz del volumen.
     *
     * Relativa y no absoluta porque `filesDir` cambia al reinstalar la app: un
     * id absoluto guardado por otra app (un «documento reciente») apuntaría a
     * una carpeta que ya no existe.
     */
    private fun idDe(fichero: File): String {
        val raiz = volumen.raiz.absolutePath
        val ruta = fichero.absolutePath
        return if (ruta == raiz) RAIZ_DOC else RAIZ_DOC + ruta.removePrefix("$raiz/")
    }

    private fun ficheroDe(documentId: String): File {
        val relativa = documentId.removePrefix(RAIZ_DOC)
        val fichero = if (relativa.isEmpty()) volumen.raiz else File(volumen.raiz, relativa)
        // Nada fuera del volumen, y nada de `.prdrive/`: un id lo construye
        // quien quiera, así que se comprueba aquí y no se confía en él.
        val dentro = fichero.canonicalPath.startsWith(volumen.raiz.canonicalPath)
        if (!dentro || oculto(fichero)) {
            throw FileNotFoundException("'$documentId' no está en el volumen")
        }
        return fichero
    }

    private fun oculto(fichero: File): Boolean =
        fichero.name == Volumen.APP_SUBDIR ||
            fichero.canonicalPath.startsWith(volumen.prdrive.canonicalPath)

    private fun tipo(fichero: File): String {
        val ext = fichero.name.substringAfterLast('.', "")
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext.lowercase())
            ?: "application/octet-stream"
    }

    private companion object {
        const val RAIZ = "prdrive"

        /** El prefijo de todos los ids, para que la raíz tenga uno propio. */
        const val RAIZ_DOC = "volumen/"

        val COLUMNAS_RAIZ = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES,
        )
        val COLUMNAS_DOC = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS,
        )
    }
}
