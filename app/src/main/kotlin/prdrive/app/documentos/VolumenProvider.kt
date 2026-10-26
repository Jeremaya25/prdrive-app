package prdrive.app.documentos

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import prdrive.app.Actualizador
import prdrive.app.Prdrive
import prdrive.app.R
import prdrive.app.llavero.Avisos
import prdrive.app.llavero.Disparos
import prdrive.engine.CARPETA_PROGRAMA
import prdrive.engine.ConfigError
import prdrive.engine.Conflictos
import prdrive.engine.LLAVERO_LLAVE
import prdrive.engine.LLAVERO_LOCAL
import prdrive.engine.TomlError

/**
 * VolumenProvider.kt — El volumen, como una ubicación más de la app de Archivos.
 *
 * Publica la raíz del volumen («prdrive») en la app de Archivos del sistema y en
 * los diálogos de abrir y guardar de otras apps, sin pedir ningún permiso: es
 * almacenamiento privado de la app, y las fechas de modificación se conservan
 * —que es justo lo que hace poco fiable a bisync en tarjetas SD y discos USB—.
 *
 * **`.prdrive/` no se ve ni se alcanza**: lleva la clave privada y el
 * `rclone.conf`. Y en el volumen puede haber enlaces simbólicos: los trae
 * rclone de un `.rclonelink` del remoto en una pareja con `links`, y apuntan
 * adonde diga quien los escribió. De ahí las dos reglas:
 *
 * - Un id es una ruta **léxica** dentro del volumen (sin `.`, `..` ni tramos
 *   vacíos, y nunca `.prdrive` en la raíz), y lo que se toca es esa ruta: borrar
 *   o renombrar un enlace es borrar o renombrar el enlace, no lo que señala.
 * - Lo que hay detrás tiene que caer, ya resuelto, dentro del volumen y fuera de
 *   la carpeta del programa. Lo que no, no existe: ni se lista ni se abre.
 *
 * Borrar una carpeta la recorre sin seguir enlaces (`Files.walkFileTree`), que
 * `File.deleteRecursively()` sí sigue: con un enlace a `.prdrive/` dentro, se
 * llevaría la clave.
 *
 * **El llavero** (`.keychain/`) lo abre una app de KeePass por aquí. Sus bases
 * se guardan de forma atómica ([GuardadoAtomico]): el cliente escribe en un
 * temporal de `.prdrive/tmp/`, que solo se pone encima de la base al cerrar y si
 * llega entera. Guardar una base encola la subida ([Disparos]); leerla no
 * encola nada (lo último del remoto lo trae «Abrir llavero»). Lo que cada app
 * lee de una base se apunta ([Lecturas]): si luego guarda sobre una versión que
 * ya no es la del disco (una pasada trajo otra mientras la tenía abierta), el
 * guardado va a una copia de conflicto y no encima. La llave (`llave.keyx`) se ve, porque la app de KeePass
 * la necesita para abrir la base. Mientras el llavero está activo
 * ([GuardadoAtomico.llaveroActivo]), la llave no se escribe, ni se borra ni se
 * renombra, ni por su ruta ni por un enlace que lleve a ella; `.keychain/` no
 * se borra ni se renombra (se llevaría la llave); y nada se crea ni se renombra
 * como `.keychain` en la raíz o como `llave.keyx` dentro, que se haría pasar por
 * ellas.
 * Sin llavero son ficheros normales: al quitarlo se aconseja borrar la llave.
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

    /** El volumen tal cual (`filesDir/volumen`): las rutas léxicas cuelgan de aquí. */
    private val raiz: File get() = Prdrive.volumen(context!!).raiz.absoluteFile

    /** Donde se atiende el cierre de un guardado del llavero ([GuardadoAtomico]). */
    private lateinit var guardados: Handler

    /** Los temporales de los guardados del llavero: `.prdrive/tmp/`. */
    private val temporales: File get() = File(Prdrive.volumen(context!!).programa, GuardadoAtomico.TEMPORALES)

    /** Si el llavero está activo, leído ahora del config del volumen. */
    private fun llaveroActivo(): Boolean = GuardadoAtomico.llaveroActivo(Prdrive.volumen(context!!).config)

    /**
     * La llave o la carpeta del llavero, con el llavero activo: ni se borran ni
     * se renombran, y nada se crea ni se renombra con su nombre. Si falta
     * `.keychain` (antes de la primera pasada, o borrada a mano), otra carpeta
     * con una llave falsa dentro ocuparía su sitio.
     */
    private fun protegido(t: List<String>): Boolean =
        (GuardadoAtomico.esLlave(t) || GuardadoAtomico.esCarpeta(t)) && llaveroActivo()

    /** Si [f], resuelto, es la llave del llavero: un enlace del volumen puede llevar a ella. */
    private fun llevaALaLlave(f: File): Boolean =
        f.canonicalFile == File(raiz, "$LLAVERO_LOCAL/$LLAVERO_LLAVE").canonicalFile

    override fun onCreate(): Boolean {
        guardados = Handler(HandlerThread("guardado-atomico").apply { start() }.looper)
        // Los de un proceso que murió con un guardado abierto.
        try {
            GuardadoAtomico.limpiar(temporales)
        } catch (e: SecurityException) {
            Log.w("VolumenProvider", "Limpiando $temporales", e)
        }
        return true
    }

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

    private fun noExiste(id: String): Nothing = throw FileNotFoundException("No es de este volumen: $id")

    /** Los tramos de un id, o ninguno para la raíz. */
    private fun tramos(id: String): List<String> {
        val relativa = when {
            id == ID_RAIZ -> return emptyList()
            id.startsWith("$ID_RAIZ/") -> id.substring(ID_RAIZ.length + 1)
            else -> noExiste(id)
        }
        val tramos = relativa.split('/')
        if (tramos.any { it.isEmpty() || it == "." || it == ".." }) noExiste(id)
        if (tramos.first() == CARPETA_PROGRAMA) noExiste(id)
        return tramos
    }

    private fun idDe(tramos: List<String>): String =
        if (tramos.isEmpty()) ID_RAIZ else ID_RAIZ + "/" + tramos.joinToString("/")

    /**
     * La ruta léxica de un id, si lo que hay detrás —resuelto, enlaces incluidos—
     * cae dentro del volumen y fuera de `.prdrive/`. Si no existe, se mira su
     * carpeta: es lo que hace falta para crear algo nuevo.
     */
    private fun fichero(id: String): File {
        val t = tramos(id)
        // Un enlace solo puede ser el último tramo: por uno de en medio, una ruta
        // de dentro del volumen podría volver a su raíz (`x/volumen`, con `x`
        // apuntando a la carpeta de la app) y borrarla entera, `.prdrive/`
        // incluida. La carpeta de un enlace se ve vacía, y es el precio.
        var actual = raiz
        for (tramo in t.dropLast(1)) {
            actual = File(actual, tramo)
            if (Files.isSymbolicLink(actual.toPath())) noExiste(id)
        }
        val f = if (t.isEmpty()) raiz else File(raiz, t.joinToString("/"))
        if (!alcanzable(f, esRaiz = t.isEmpty())) noExiste(id)
        return f
    }

    private fun alcanzable(f: File, esRaiz: Boolean): Boolean {
        val base = raiz.canonicalFile.toPath()
        val programa = File(raiz, CARPETA_PROGRAMA).canonicalFile.toPath()
        // Un enlace roto no tiene destino que resolver: se juzga por su carpeta.
        val existe = Files.exists(f.toPath())
        val resuelta = (if (existe || esRaiz) f else f.parentFile ?: return false).canonicalFile.toPath()
        // Nada que no sea la raíz puede ser la raíz (un enlace a ella): la
        // contiene a ella y a `.prdrive/`.
        if (!esRaiz && existe && resuelta == base) return false
        return resuelta.startsWith(base) && !resuelta.startsWith(programa)
    }

    private fun mime(f: File): String {
        if (f.isDirectory) return Document.MIME_TYPE_DIR
        val ext = f.name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun fila(cursor: MatrixCursor, id: String, f: File) {
        val esRaiz = id == ID_RAIZ
        var flags = if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        if (!esRaiz) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        // Con el llavero activo, la llave es de solo lectura y su carpeta se queda.
        val t = tramos(id)
        if (protegido(t)) {
            flags = flags and (Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME).inv()
            if (GuardadoAtomico.esLlave(t)) flags = flags and Document.FLAG_SUPPORTS_WRITE.inv()
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, if (esRaiz) context!!.getString(R.string.app_name) else f.name)
            add(Document.COLUMN_MIME_TYPE, mime(f))
            add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (f.isFile) f.length() else null)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val f = fichero(documentId)
        if (!Files.exists(f.toPath())) noExiste(documentId)
        return MatrixCursor(projection ?: COLUMNAS_DOCUMENTO).apply { fila(this, documentId, f) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val padre = fichero(parentDocumentId)
        val tramosPadre = tramos(parentDocumentId)
        return MatrixCursor(projection ?: COLUMNAS_DOCUMENTO).apply {
            setNotificationUri(context!!.contentResolver, DocumentsContract.buildChildDocumentsUri(AUTORIDAD, parentDocumentId))
            for (hijo in (padre.listFiles() ?: emptyArray()).sortedBy { it.name.lowercase() }) {
                val id = idDe(tramosPadre + hijo.name)
                // Lo que no se alcanza (la carpeta del programa, un enlace que
                // sale del volumen o entra en ella, uno roto) no se enseña.
                val f = try {
                    fichero(id)
                } catch (_: FileNotFoundException) {
                    continue
                }
                if (Files.exists(f.toPath())) fila(this, id, f)
            }
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val t = tramos(documentId)
        val f = fichero(documentId)
        if (f.isDirectory || !Files.exists(f.toPath())) throw FileNotFoundException("No se puede abrir: $documentId")
        val escribe = 'w' in mode
        // La llave, ni por su ruta ni por un enlace (uno que traiga rclone de un
        // `.rclonelink` en una pareja con `links`).
        if (escribe && (GuardadoAtomico.esLlave(t) || llevaALaLlave(f)) && llaveroActivo()) {
            throw FileNotFoundException("La llave del llavero no se escribe")
        }
        if (escribe && GuardadoAtomico.esBase(t)) return guardarBase(f, mode)
        if (!escribe && GuardadoAtomico.esBase(t)) return leerBase(f)
        // Se abre lo comprobado: el destino ya resuelto, no la ruta del enlace.
        return ParcelFileDescriptor.open(f.canonicalFile, ParcelFileDescriptor.parseMode(mode))
    }

    /**
     * El paquete de la app que llama, o `null` si Android no lo dice. Sin él no
     * se apunta nada, y sus guardados van encima, como antes de [Lecturas].
     */
    private fun quienLlama(): String? = try {
        callingPackage
    } catch (_: SecurityException) {
        null
    }

    /**
     * La lectura de una base del llavero ([f], la entrada de `.keychain/`):
     * se apunta qué versión se le da a la app ([Lecturas.apuntarLectura]).
     *
     * La huella sale del mismo fichero abierto que se le da, con lecturas
     * posicionales (no mueven la posición del descriptor): si una pasada pone
     * otra base justo entonces, la app lee la que se ha apuntado.
     */
    private fun leerBase(f: File): ParcelFileDescriptor {
        val app = quienLlama()
        val state = Prdrive.volumen(context!!).state
        RandomAccessFile(f.canonicalFile, "r").use { fichero ->
            if (app != null) {
                try {
                    Lecturas.apuntarLectura(state, app, f.name, Lecturas.huella(fichero.channel))
                } catch (e: IOException) {
                    Log.w("VolumenProvider", "Apuntando la lectura de ${f.name}", e)
                }
            }
            return ParcelFileDescriptor.dup(fichero.fd)
        }
    }

    /**
     * El guardado de una base del llavero ([f], la entrada de `.keychain/`): un
     * temporal que se pone encima al cerrar, si llega entero. Uno roto se apunta
     * en `state/` y se notifica ([Avisos.guardadoCortado]), y uno bueno quita
     * ese aviso si era de esta base y encola la subida. Uno bueno hecho sobre
     * una versión que ya no es la del disco va a una copia de conflicto
     * ([Lecturas.destino]), que se apunta en `state/conflicts.json` y se
     * notifica la primera vez ([Avisos.guardadoAparte]).
     *
     * Mientras el temporal está abierto cuenta como un trabajo de la
     * autoactualización ([Actualizador.empezarTrabajo]): instalarse cierra el
     * proceso, y con él el cierre que pone la base; la app de KeePass creería
     * guardado lo que no ha llegado. Si ya se está instalando, se niega: la app
     * de KeePass dice que no ha podido guardar, que es la verdad.
     */
    private fun guardarBase(f: File, mode: String): ParcelFileDescriptor {
        val c = context!!
        val state = Prdrive.volumen(c).state
        val app = quienLlama()
        if (!Actualizador.empezarTrabajo()) {
            throw FileNotFoundException("No se puede guardar ${f.name}: se está instalando la versión nueva de prdrive")
        }
        return try {
            GuardadoAtomico.abrir(
                base = f,
                modo = mode,
                temporales = temporales,
                handler = guardados,
                destino = { Lecturas.destino(state, app, f) },
                alPoner = { puesto ->
                    // Un guardado bueno de esta base, aunque haya ido aparte:
                    // lo que se cortó ya está guardado.
                    if (GuardadoAtomico.quitarCortado(state, f.name)) Avisos.guardadoBueno(c)
                    val nueva = puesto != f && app != null && Lecturas.conocida(state, app, f.name)?.aparte != puesto.name
                    if (app != null) Lecturas.apuntarEscritura(state, app, f, puesto)
                    if (puesto != f) apartado(c, f, puesto, nueva)
                    Disparos.alGuardar(c)
                },
                alCortar = { motivo ->
                    GuardadoAtomico.apuntarCortado(state, f.name, motivo)
                    Avisos.guardadoCortado(c, f.name)
                },
                alAcabar = { Actualizador.acabarTrabajo(c) },
            )
        } catch (e: IOException) {
            throw FileNotFoundException("No se puede guardar ${f.name}: ${e.message}")
        }
    }

    /**
     * Tras un guardado que ha ido a la copia [copia] y no encima de [base]: la
     * fila lo enseña ya (`state/conflicts.json`, sin esperar a la pasada, que
     * necesita red) y, si la copia es [nueva], se notifica.
     */
    private fun apartado(c: Context, base: File, copia: File, nueva: Boolean) {
        try {
            val v = Prdrive.volumen(c)
            v.leerConfig().parejaLlavero?.let { Conflictos.actualizarPareja(it, v.raiz, v.state) }
        } catch (e: IOException) {
            Log.w("VolumenProvider", "Apuntando la copia ${copia.name}", e)
        } catch (e: ConfigError) {
            Log.w("VolumenProvider", "Apuntando la copia ${copia.name}", e)
        } catch (e: TomlError) {
            Log.w("VolumenProvider", "Apuntando la copia ${copia.name}", e)
        }
        if (nueva) Avisos.guardadoAparte(c, base.name, copia)
    }

    private fun nombreValido(nombre: String) =
        nombre.isNotEmpty() && '/' !in nombre && '\u0000' !in nombre && nombre != "." && nombre != ".."

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val padre = fichero(parentDocumentId)
        if (!padre.isDirectory) throw FileNotFoundException("No es una carpeta: $parentDocumentId")
        if (!nombreValido(displayName)) throw FileNotFoundException("Nombre no válido: $displayName")
        val tramosPadre = tramos(parentDocumentId)
        val punto = displayName.lastIndexOf('.')
        val (base, ext) = if (punto > 0) displayName.substring(0, punto) to displayName.substring(punto) else displayName to ""
        // Con el llavero activo, nada se crea como su llave o su carpeta: se
        // haría pasar por ellas. Solo el primer nombre puede serlo (los demás
        // llevan « (n)»).
        if (protegido(tramosPadre + displayName)) {
            throw FileNotFoundException("Con el llavero activo, no se crea otra $displayName")
        }
        var nombre = displayName
        var n = 1
        while (true) {
            val id = idDe(tramosPadre + nombre)
            // Un «.prdrive» en la raíz no es la carpeta del programa: se busca otro nombre.
            val libre = try {
                tramos(id)
                !Files.exists(File(padre, nombre).toPath(), LinkOption.NOFOLLOW_LINKS)
            } catch (_: FileNotFoundException) {
                false
            }
            if (libre) {
                val ruta = File(padre, nombre).toPath()
                try {
                    // CREATE_NEW y no `createNewFile()` tras mirar: si aparece un
                    // enlace en medio, falla en vez de crear al otro lado.
                    if (mimeType == Document.MIME_TYPE_DIR) Files.createDirectory(ruta) else Files.createFile(ruta)
                } catch (e: IOException) {
                    throw FileNotFoundException("No se pudo crear $displayName: ${e.message}")
                }
                avisar(parentDocumentId)
                return id
            }
            nombre = "$base ($n)$ext"
            n++
        }
    }

    override fun deleteDocument(documentId: String) {
        val t = tramos(documentId)
        if (t.isEmpty()) throw FileNotFoundException("La raíz no se borra")
        if (protegido(t)) throw FileNotFoundException("Con el llavero activo, $LLAVERO_LOCAL y su llave no se borran")
        val f = fichero(documentId)
        try {
            borrarSinSeguirEnlaces(f.toPath())
        } catch (e: IOException) {
            throw FileNotFoundException("No se pudo borrar $documentId: ${e.message}")
        }
        avisar(idDe(t.dropLast(1)))
    }

    /** Borra un fichero, un enlace (el enlace) o una carpeta entera sin entrar en sus enlaces. */
    private fun borrarSinSeguirEnlaces(ruta: Path) {
        Files.walkFileTree(
            ruta,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val t = tramos(documentId)
        if (t.isEmpty()) throw FileNotFoundException("La raíz no se renombra")
        if (protegido(t)) throw FileNotFoundException("Con el llavero activo, $LLAVERO_LOCAL y su llave no se renombran")
        if (!nombreValido(displayName)) throw FileNotFoundException("Nombre no válido: $displayName")
        val f = fichero(documentId)
        val nuevoId = idDe(t.dropLast(1) + displayName)
        tramos(nuevoId) // ni `.prdrive` en la raíz
        if (protegido(tramos(nuevoId))) {
            throw FileNotFoundException("Con el llavero activo, nada se renombra como $displayName")
        }
        val destino = File(f.parentFile, displayName)
        try {
            // Mueve la entrada misma: si es un enlace, el enlace.
            Files.move(f.toPath(), destino.toPath(), LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            throw FileNotFoundException("No se pudo renombrar a $displayName: ${e.message}")
        }
        avisar(idDe(t.dropLast(1)))
        return nuevoId
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        val padre = tramos(parentDocumentId)
        val hijo = tramos(documentId)
        fichero(parentDocumentId)
        fichero(documentId)
        hijo.size > padre.size && hijo.subList(0, padre.size) == padre
    } catch (_: FileNotFoundException) {
        false
    }

    override fun getDocumentType(documentId: String): String = mime(fichero(documentId))

    private fun avisar(padre: String) {
        context!!.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTORIDAD, padre), null)
    }
}
