package prdrive.app.documentos

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import prdrive.app.Prdrive
import prdrive.app.R
import prdrive.engine.CARPETA_PROGRAMA

/**
 * VolumenProvider.kt — El volumen, como una ubicación más de la app de Archivos.
 *
 * Publica la raíz del volumen («prdrive») en la app de Archivos del sistema y en
 * los diálogos de abrir y guardar de otras apps, sin pedir ningún permiso: es
 * almacenamiento privado de la app, y las fechas de modificación se conservan
 * —que es justo lo que hace poco fiable a bisync en tarjetas SD y discos USB—.
 *
 * **`.prdrive/` no se ve ni se alcanza**: lleva la clave privada y el
 * `rclone.conf`. Cada id de documento es una ruta relativa al volumen, y se
 * comprueba, ya resuelta, que cae dentro del volumen y fuera de la carpeta del
 * programa antes de tocar nada.
 */
class VolumenProvider : DocumentsProvider() {

    companion object {
        const val AUTORIDAD = "prdrive.app.documentos"
        const val ID_RAIZ = "volumen"

        private val COLUMNAS_RAIZ = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_ICON,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val COLUMNAS_DOCUMENTO = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
        )
    }

    private val raiz: File get() = Prdrive.volumen(context!!).raiz

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        raiz.mkdirs()
        return MatrixCursor(projection ?: COLUMNAS_RAIZ).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, ID_RAIZ)
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
                add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
                add(Root.COLUMN_ICON, R.drawable.ic_prdrive)
                add(Root.COLUMN_DOCUMENT_ID, ID_RAIZ)
                add(Root.COLUMN_AVAILABLE_BYTES, raiz.usableSpace)
            }
        }
    }

    /**
     * El fichero de un id: `volumen` es la raíz y `volumen/a/b` es `a/b` dentro.
     * Resuelto (sin `..` ni enlaces), tiene que caer dentro del volumen y fuera
     * de `.prdrive/`; si no, para quien pregunta no existe.
     */
    private fun fichero(id: String): File {
        val base = raiz.canonicalFile
        val relativa = when {
            id == ID_RAIZ -> ""
            id.startsWith("$ID_RAIZ/") -> id.substring(ID_RAIZ.length + 1)
            else -> throw FileNotFoundException("No es de este volumen: $id")
        }
        val f = File(base, relativa).canonicalFile
        val programa = File(base, CARPETA_PROGRAMA).canonicalFile
        val dentro = f == base || f.toPath().startsWith(base.toPath())
        val enPrograma = f == programa || f.toPath().startsWith(programa.toPath())
        if (!dentro || enPrograma) throw FileNotFoundException("No es de este volumen: $id")
        return f
    }

    private fun id(f: File): String {
        val base = raiz.canonicalFile
        val c = f.canonicalFile
        return if (c == base) ID_RAIZ else "$ID_RAIZ/" + base.toPath().relativize(c.toPath()).toString().replace(File.separatorChar, '/')
    }

    /** Si es lo que se esconde: la carpeta del programa, en la raíz. */
    private fun oculto(f: File): Boolean = f.canonicalFile == File(raiz, CARPETA_PROGRAMA).canonicalFile

    private fun mime(f: File): String {
        if (f.isDirectory) return Document.MIME_TYPE_DIR
        val ext = f.name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun fila(cursor: MatrixCursor, f: File) {
        var flags = 0
        if (f.isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (f.canonicalFile != raiz.canonicalFile) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id(f))
            add(Document.COLUMN_DISPLAY_NAME, if (f.canonicalFile == raiz.canonicalFile) context!!.getString(R.string.app_name) else f.name)
            add(Document.COLUMN_MIME_TYPE, mime(f))
            add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (f.isFile) f.length() else null)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: COLUMNAS_DOCUMENTO).apply { fila(this, fichero(documentId)) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val padre = fichero(parentDocumentId)
        return MatrixCursor(projection ?: COLUMNAS_DOCUMENTO).apply {
            setNotificationUri(context!!.contentResolver, DocumentsContract.buildChildDocumentsUri(AUTORIDAD, parentDocumentId))
            (padre.listFiles() ?: emptyArray()).filter { !oculto(it) }.sortedBy { it.name.lowercase() }.forEach { fila(this, it) }
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = fichero(documentId)
        if (f.isDirectory) throw FileNotFoundException("Es una carpeta: $documentId")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val padre = fichero(parentDocumentId)
        if (displayName.isEmpty() || '/' in displayName || displayName == "." || displayName == "..") {
            throw FileNotFoundException("Nombre no válido: $displayName")
        }
        var destino = File(padre, displayName)
        var n = 1
        while (destino.exists()) {
            val punto = displayName.lastIndexOf('.')
            val (base, ext) = if (punto > 0) displayName.substring(0, punto) to displayName.substring(punto) else displayName to ""
            destino = File(padre, "$base ($n)$ext")
            n++
        }
        val id = id(destino)
        fichero(id) // que no caiga en `.prdrive/` (un «.prdrive» creado en la raíz)
        val ok = if (mimeType == Document.MIME_TYPE_DIR) destino.mkdir() else destino.createNewFile()
        if (!ok) throw FileNotFoundException("No se pudo crear $displayName")
        avisar(parentDocumentId)
        return id
    }

    override fun deleteDocument(documentId: String) {
        val f = fichero(documentId)
        if (f.canonicalFile == raiz.canonicalFile) throw FileNotFoundException("La raíz no se borra")
        if (!f.deleteRecursively()) throw FileNotFoundException("No se pudo borrar $documentId")
        avisar(id(f.parentFile!!))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val f = fichero(documentId)
        if (displayName.isEmpty() || '/' in displayName || displayName == "." || displayName == "..") {
            throw FileNotFoundException("Nombre no válido: $displayName")
        }
        val destino = File(f.parentFile, displayName)
        val nuevoId = id(destino)
        fichero(nuevoId)
        if (destino.exists() || !f.renameTo(destino)) throw FileNotFoundException("No se pudo renombrar a $displayName")
        avisar(id(f.parentFile!!))
        return nuevoId
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        val padre = fichero(parentDocumentId)
        val hijo = fichero(documentId)
        hijo != padre && hijo.toPath().startsWith(padre.toPath())
    } catch (_: FileNotFoundException) {
        false
    }

    override fun getDocumentType(documentId: String): String = mime(fichero(documentId))

    private fun avisar(padre: String) {
        context!!.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTORIDAD, padre), null)
    }
}
