package prdrive.app

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.documentos.GuardadoAtomico
import prdrive.app.documentos.VolumenProvider
import prdrive.app.llavero.Disparos
import prdrive.engine.Kdbx
import prdrive.engine.LLAVERO_LLAVE
import prdrive.engine.LLAVERO_LOCAL
import prdrive.engine.Store
import prdrive.engine.Volumen

/**
 * ProveedorLlaveroTest.kt — El guardado atómico de las bases del llavero en
 * [VolumenProvider] (`GuardadoAtomico`): una app de KeePass que escribe la base
 * por el proveedor o la deja entera o no la toca, y la llave se ve pero no se
 * escribe.
 *
 * Se habla con el proveedor de verdad, por `ContentResolver`, como lo haría una
 * app de KeePass (el test corre en el proceso de la app, así que pasa el
 * permiso). El cambio de la base llega al cerrar, en el hilo del proveedor: por
 * eso se espera a que `.prdrive/tmp/` se quede vacío, y a lo que venga después
 * (la subida encolada, el aviso).
 */
@RunWith(AndroidJUnit4::class)
class ProveedorLlaveroTest {

    private val contexto get() = Banco.contexto
    private val resolver get() = contexto.contentResolver

    private lateinit var v: Volumen

    /** La carpeta del llavero en el volumen (`.keychain/`). */
    private val llavero: File get() = File(v.raiz, LLAVERO_LOCAL)
    private val base: File get() = File(llavero, "personal.kdbx")
    private val temporales: File get() = File(v.programa, "tmp")
    private val aviso: File get() = File(v.state, "llavero-cortado.json")

    @Before
    fun preparar() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            contexto,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        v = Banco.volumen(Banco.pareja("notas"), llavero = "personal.kdbx").first
        llavero.mkdirs()
        base.writeBytes(Banco.base(entera = true))
        File(llavero, LLAVERO_LLAVE).writeBytes(byteArrayOf(1, 2, 3, 4))
    }

    private fun uri(relativa: String): Uri =
        DocumentsContract.buildDocumentUri(VolumenProvider.AUTORIDAD, "${VolumenProvider.ID_RAIZ}/$relativa")

    private val uriBase: Uri get() = uri("$LLAVERO_LOCAL/personal.kdbx")

    /** Los trabajos del llavero que aún no han acabado. */
    private fun encolados(): List<WorkInfo> =
        WorkManager.getInstance(contexto).getWorkInfosForUniqueWork(Disparos.NOMBRE).get()
            .filter { !it.state.isFinished }

    private fun esperar(que: String, maxMs: Long = 10_000, hecho: () -> Boolean) {
        val hasta = System.currentTimeMillis() + maxMs
        while (!hecho()) {
            if (System.currentTimeMillis() > hasta) fail(que)
            Thread.sleep(20)
        }
    }

    /** Lo que deja el proveedor al cerrar: ni un temporal en `.prdrive/tmp/`. */
    private fun esperarAlCierre() =
        esperar("el temporal sigue en ${temporales.list()?.toList()}") { (temporales.list() ?: emptyArray()).isEmpty() }

    /**
     * Escribe [datos] en [uri] con [modo], desde el principio y cortando ahí, y
     * cierra: con [error], como un cliente que se cae.
     */
    private fun escribir(uri: Uri, modo: String, datos: ByteArray, error: String? = null) {
        val pfd = resolver.openFileDescriptor(uri, modo) ?: error("sin descriptor")
        FileOutputStream(pfd.fileDescriptor).channel.let { canal ->
            canal.position(0)
            canal.write(java.nio.ByteBuffer.wrap(datos))
            // `"rw"` no trunca, y `"wa"` añade: una app de KeePass que guarda
            // con `"rw"` corta ella al final de lo suyo.
            if (!modo.contains('a')) canal.truncate(datos.size.toLong())
        }
        if (error != null) pfd.closeWithError(error) else pfd.close()
        esperarAlCierre()
    }

    @Test
    fun escribir_una_base_entera_la_cambia_y_encola_la_subida() {
        for (modo in listOf("wt", "w", "rw")) {
            preparar()
            assertTrue(encolados().isEmpty())
            val nueva = Banco.otraBase()
            escribir(uriBase, modo, nueva)
            assertArrayEquals("con \"$modo\"", nueva, base.readBytes())
            esperar("con \"$modo\" no se ha encolado la subida") { encolados().isNotEmpty() }
            assertEquals(Disparos.CALMA.toMillis(), encolados().single().initialDelayMillis)
        }
    }

    @Test
    fun con_rw_se_escribe_sobre_una_copia_de_la_base() {
        val pfd = resolver.openFileDescriptor(uriBase, "rw")!!
        val leido = FileInputStream(pfd.fileDescriptor).readBytes()
        pfd.close()
        esperarAlCierre()
        assertArrayEquals(Banco.base(entera = true), leido)
        assertArrayEquals(Banco.base(entera = true), base.readBytes())
    }

    @Test
    fun una_escritura_cortada_o_con_error_deja_la_base_como_estaba() {
        val antes = base.readBytes()
        val nueva = Banco.otraBase()

        // El cliente se cae (o cierra con error) tras escribirla entera: ni se
        // pone ni se avisa, porque no era un guardado acabado.
        escribir(uriBase, "wt", nueva, error = "se ha caído")
        assertArrayEquals(antes, base.readBytes())
        assertFalse("se ha avisado de un cierre con error", aviso.exists())
        assertTrue(encolados().isEmpty())

        // Medio fichero cerrado limpio: no se pone, y queda el aviso con lo que
        // dice `Kdbx.comprobar` de esos bytes.
        val medio = nueva.copyOf(nueva.size / 2)
        escribir(uriBase, "wt", medio)
        assertArrayEquals(antes, base.readBytes())
        esperar("no se ha avisado del guardado cortado") { aviso.exists() }
        assertTrue(encolados().isEmpty())
        val roto = File(contexto.cacheDir, "medio.kdbx").apply { writeBytes(medio) }
        val esperado = Kdbx.comprobar(roto)
        roto.delete()
        assertEquals(false, esperado.entera)
        val apuntado = Store.readJson(aviso)
        assertEquals("personal.kdbx", apuntado["fichero"])
        assertEquals(esperado.motivo, apuntado["motivo"])
        assertTrue(apuntado["cuando"].toString().isNotEmpty())

        // Un guardado bueno lo quita.
        escribir(uriBase, "wt", nueva)
        assertArrayEquals(nueva, base.readBytes())
        // El aviso se quita antes de encolar la subida.
        esperar("no se ha encolado la subida") { encolados().isNotEmpty() }
        assertFalse("el aviso sigue tras un guardado bueno", aviso.exists())
    }

    @Test
    fun una_base_nueva_en_el_llavero_tambien_se_guarda_entera_o_no_se_pone() {
        val creada = DocumentsContract.createDocument(
            resolver, uri(LLAVERO_LOCAL), "application/octet-stream", "nueva.kdbx",
        ) ?: error("no se ha creado")
        val nueva = File(llavero, "nueva.kdbx")
        assertTrue(nueva.isFile)

        // El primer guardado, cortado: ni bytes rotos ni el marcador vacío de
        // `createDocument`, que `Llavero.rotas` contaría como base rota y
        // pararía el llavero entero.
        val datos = Banco.otraBase()
        escribir(creada, "w", datos.copyOf(datos.size / 2))
        esperar("no se ha avisado del guardado cortado") { aviso.exists() }
        assertFalse("ha quedado nueva.kdbx tras un primer guardado cortado", nueva.exists())

        val otraVez = DocumentsContract.createDocument(
            resolver, uri(LLAVERO_LOCAL), "application/octet-stream", "nueva.kdbx",
        ) ?: error("no se ha creado")
        escribir(otraVez, "w", datos)
        assertArrayEquals(datos, nueva.readBytes())
    }

    @Test
    fun abrir_y_cerrar_sin_escribir_no_es_un_guardado() {
        // Una hora de antes, en segundos enteros: que se note si se toca.
        val hora = (System.currentTimeMillis() / 1000 - 3600) * 1000
        base.setLastModified(hora)
        val antes = base.readBytes()
        for (modo in listOf("rw", "wa", "w", "wt")) {
            resolver.openFileDescriptor(uriBase, modo)!!.close()
            esperarAlCierre()
            assertArrayEquals("con \"$modo\"", antes, base.readBytes())
            assertEquals("con \"$modo\" ha cambiado la hora de la base", hora, base.lastModified())
            assertFalse("con \"$modo\" se ha avisado de un guardado cortado", aviso.exists())
        }
        assertTrue("se ha encolado una subida sin guardar nada", encolados().isEmpty())
    }

    @Test
    fun un_descriptor_soltado_es_un_guardado_cortado() {
        val antes = base.readBytes()
        val pfd = resolver.openFileDescriptor(uriBase, "wt")!!
        FileOutputStream(pfd.fileDescriptor).write(Banco.otraBase())
        // El cliente se queda el descriptor (`detachFd`): el proveedor ya no
        // sabe cuándo acaba, y no puede ponerlo.
        ParcelFileDescriptor.adoptFd(pfd.detachFd()).close()
        esperar("no se ha avisado del descriptor soltado") { aviso.exists() }
        esperarAlCierre()
        assertArrayEquals(antes, base.readBytes())
        assertEquals(GuardadoAtomico.MOTIVO_SOLTADO, Store.readJson(aviso)["motivo"])
        assertTrue(encolados().isEmpty())
    }

    @Test
    fun leer_una_base_no_encola_ninguna_pasada_ni_la_toca() {
        // Lo último del remoto solo lo trae «Abrir llavero»: una pasada al leer
        // podría cambiar la base debajo de la app de KeePass ([Disparos]).
        val pfd = resolver.openFileDescriptor(uriBase, "r")!!
        assertArrayEquals(Banco.base(entera = true), FileInputStream(pfd.fileDescriptor).readBytes())
        pfd.close()
        assertTrue("leer ha encolado una pasada", encolados().isEmpty())
        assertTrue((temporales.list() ?: emptyArray()).isEmpty())
    }

    /** Los flags de [nombre] en el listado de la carpeta [padre] (relativa al volumen; vacía, la raíz). */
    private fun flagsDe(padre: String, nombre: String): Int? {
        val id = if (padre.isEmpty()) VolumenProvider.ID_RAIZ else "${VolumenProvider.ID_RAIZ}/$padre"
        val c = resolver.query(DocumentsContract.buildChildDocumentsUri(VolumenProvider.AUTORIDAD, id), null, null, null, null)!!
        var flags: Int? = null
        while (c.moveToNext()) {
            if (c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)) == nombre) {
                flags = c.getInt(c.getColumnIndexOrThrow(Document.COLUMN_FLAGS))
            }
        }
        c.close()
        return flags
    }

    /** Que [accion] falle con `FileNotFoundException`, que es como el proveedor se niega. */
    private fun seNiega(que: String, accion: () -> Unit) {
        try {
            accion()
            fail("se ha podido $que")
        } catch (_: FileNotFoundException) {
            // lo esperado
        }
    }

    @Test
    fun la_llave_se_ve_pero_no_se_escribe_ni_se_borra() {
        val flags = flagsDe(LLAVERO_LOCAL, LLAVERO_LLAVE)
        assertNotNull("la llave no está en el listado", flags)
        for (flag in listOf(Document.FLAG_SUPPORTS_WRITE, Document.FLAG_SUPPORTS_DELETE, Document.FLAG_SUPPORTS_RENAME)) {
            assertEquals("la llave lleva el flag $flag", 0, flags!! and flag)
        }

        val llave = uri("$LLAVERO_LOCAL/$LLAVERO_LLAVE")
        // Se lee: la app de KeePass la necesita para abrir la base.
        resolver.openFileDescriptor(llave, "r")!!.use { pfd ->
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), FileInputStream(pfd.fileDescriptor).readBytes())
        }
        for (modo in listOf("w", "wt", "rw", "wa")) {
            try {
                resolver.openFileDescriptor(llave, modo)?.close()
                fail("se ha podido abrir la llave con \"$modo\"")
            } catch (_: FileNotFoundException) {
                // lo esperado
            }
        }
        try {
            DocumentsContract.deleteDocument(resolver, llave)
            fail("se ha podido borrar la llave")
        } catch (_: FileNotFoundException) {
            // lo esperado
        }
        try {
            DocumentsContract.renameDocument(resolver, llave, "otra.keyx")
            fail("se ha podido renombrar la llave")
        } catch (_: FileNotFoundException) {
            // lo esperado
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(llavero, LLAVERO_LLAVE).readBytes())
    }

    @Test
    fun fuera_del_llavero_todo_sigue_como_antes() {
        val fuera = File(v.raiz, "sync-data/notas/otra.kdbx").apply {
            parentFile!!.mkdirs()
            writeBytes(Banco.base(entera = true))
        }
        // Con la escritura a medias, lo escrito ya está en el fichero: no hay temporal.
        val pfd = resolver.openFileDescriptor(uri("sync-data/notas/otra.kdbx"), "wt")!!
        val medio = Banco.otraBase().copyOf(10)
        FileOutputStream(pfd.fileDescriptor).write(medio)
        assertArrayEquals(medio, fuera.readBytes())
        assertTrue((temporales.list() ?: emptyArray()).isEmpty())
        pfd.close()
        assertArrayEquals(medio, fuera.readBytes())
        assertTrue(encolados().isEmpty())
        assertFalse(aviso.exists())
    }

    @Test
    fun los_temporales_huerfanos_de_mas_de_un_dia_se_borran() {
        temporales.mkdirs()
        val ahora = System.currentTimeMillis()
        val viejo = File(temporales, "guardado-viejo.tmp").apply { writeText("x") }
        viejo.setLastModified(ahora - GuardadoAtomico.HUERFANO.toMillis() - 60_000)
        val reciente = File(temporales, "guardado-reciente.tmp").apply { writeText("x") }
        reciente.setLastModified(ahora - GuardadoAtomico.HUERFANO.toMillis() + 60_000)
        GuardadoAtomico.limpiar(temporales, ahora)
        assertFalse("el huérfano sigue ahí", viejo.exists())
        assertTrue("se ha borrado uno reciente", reciente.exists())
    }

    @Test
    fun con_el_llavero_activo_ni_su_carpeta_se_borra_ni_nada_se_hace_pasar_por_la_llave() {
        val carpeta = uri(LLAVERO_LOCAL)
        val flags = flagsDe("", LLAVERO_LOCAL)
        assertNotNull(".keychain no está en el listado", flags)
        assertEquals("se puede borrar .keychain", 0, flags!! and Document.FLAG_SUPPORTS_DELETE)
        assertEquals("se puede renombrar .keychain", 0, flags and Document.FLAG_SUPPORTS_RENAME)
        assertTrue("no se puede crear en .keychain", (flags and Document.FLAG_DIR_SUPPORTS_CREATE) != 0)

        seNiega("borrar .keychain") { DocumentsContract.deleteDocument(resolver, carpeta) }
        seNiega("renombrar .keychain") { DocumentsContract.renameDocument(resolver, carpeta, "otra") }
        seNiega("crear otra llave") {
            DocumentsContract.createDocument(resolver, carpeta, "application/octet-stream", LLAVERO_LLAVE)
        }
        val otro = File(llavero, "otro.keyx").apply { writeBytes(byteArrayOf(9, 9)) }
        seNiega("renombrar otro fichero a la llave") {
            DocumentsContract.renameDocument(resolver, uri("$LLAVERO_LOCAL/otro.keyx"), LLAVERO_LLAVE)
        }
        assertTrue(otro.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(llavero, LLAVERO_LLAVE).readBytes())

        // Un config que no se puede leer cuenta como llavero activo.
        v.config.writeText("[[[ esto no es TOML")
        seNiega("borrar .keychain con un config que no se lee") { DocumentsContract.deleteDocument(resolver, carpeta) }
        assertTrue(File(llavero, LLAVERO_LLAVE).exists())
    }

    @Test
    fun sin_el_llavero_la_llave_es_un_fichero_mas() {
        // Tras quitar el llavero, `Volumen.avisosBaja` dice «bórralo tú».
        v = Banco.volumen(Banco.pareja("notas")).first
        llavero.mkdirs()
        File(llavero, LLAVERO_LLAVE).writeBytes(byteArrayOf(1, 2, 3, 4))
        val flags = flagsDe(LLAVERO_LOCAL, LLAVERO_LLAVE)
        assertNotNull(flags)
        for (flag in listOf(Document.FLAG_SUPPORTS_WRITE, Document.FLAG_SUPPORTS_DELETE, Document.FLAG_SUPPORTS_RENAME)) {
            assertTrue("sin llavero, a la llave le falta el flag $flag", (flags!! and flag) != 0)
        }
        val carpeta = flagsDe("", LLAVERO_LOCAL)!!
        assertTrue((carpeta and Document.FLAG_SUPPORTS_DELETE) != 0)

        DocumentsContract.deleteDocument(resolver, uri("$LLAVERO_LOCAL/$LLAVERO_LLAVE"))
        assertFalse(File(llavero, LLAVERO_LLAVE).exists())
        DocumentsContract.deleteDocument(resolver, uri(LLAVERO_LOCAL))
        assertFalse(llavero.exists())
    }

    @Test
    fun con_el_llavero_activo_no_se_hace_otra_carpeta_del_llavero() {
        // Sin `.keychain` (antes de la primera pasada, o borrada a mano): nada
        // puede ocupar su sitio con una llave falsa dentro.
        llavero.deleteRecursively()
        val raizUri = DocumentsContract.buildDocumentUri(VolumenProvider.AUTORIDAD, VolumenProvider.ID_RAIZ)
        seNiega("crear .keychain") {
            DocumentsContract.createDocument(resolver, raizUri, Document.MIME_TYPE_DIR, LLAVERO_LOCAL)
        }
        val otra = File(v.raiz, "otra").apply { mkdirs() }
        File(otra, LLAVERO_LLAVE).writeBytes(byteArrayOf(6, 6, 6))
        seNiega("renombrar otra carpeta a .keychain") {
            DocumentsContract.renameDocument(resolver, uri("otra"), LLAVERO_LOCAL)
        }
        assertFalse("ha aparecido otra .keychain", llavero.exists())
        assertTrue(otra.isDirectory)
    }

    @Test
    fun una_base_nueva_abierta_y_cerrada_sin_escribir_no_se_queda_vacia() {
        val creada = DocumentsContract.createDocument(
            resolver, uri(LLAVERO_LOCAL), "application/octet-stream", "nueva.kdbx",
        ) ?: error("no se ha creado")
        val nueva = File(llavero, "nueva.kdbx")
        assertTrue(nueva.isFile)
        // Una app de KeePass que crea el fichero y se cierra sin guardar: el
        // marcador vacío pararía el llavero entero (`Llavero.rotas`).
        resolver.openFileDescriptor(creada, "w")!!.close()
        esperar("ha quedado nueva.kdbx vacía") { !nueva.exists() }
        esperarAlCierre()
        assertFalse("se ha avisado de un guardado cortado", aviso.exists())
        assertTrue(encolados().isEmpty())
        assertArrayEquals(Banco.base(entera = true), base.readBytes())
    }

    /**
     * Abre [uri] para escribir con [modo] y comprueba que, con el descriptor
     * abierto, la autoactualización tiene un trabajo más en marcha; [cerrar]
     * lo cierra como lo cerraría el cliente, y al acabar el cierre vuelve a los
     * de antes.
     */
    private fun conTrabajoMientrasEstaAbierto(uri: Uri, modo: String, cerrar: (ParcelFileDescriptor) -> Unit) {
        val antes = Actualizador.trabajosEnMarcha()
        val pfd = resolver.openFileDescriptor(uri, modo) ?: error("sin descriptor")
        assertEquals("con \"$modo\" abierto", antes + 1, Actualizador.trabajosEnMarcha())
        cerrar(pfd)
        esperar("con \"$modo\", el trabajo del guardado sigue en marcha tras cerrar") {
            Actualizador.trabajosEnMarcha() == antes
        }
        esperarAlCierre()
    }

    @Test
    fun con_un_guardado_abierto_la_actualizacion_no_se_instala_y_se_suelta_al_cerrar() {
        // Uno bueno.
        conTrabajoMientrasEstaAbierto(uriBase, "wt") { pfd ->
            FileOutputStream(pfd.fileDescriptor).write(Banco.otraBase())
            pfd.close()
        }
        assertArrayEquals(Banco.otraBase(), base.readBytes())
        // Uno sin escribir nada (igual que la base, y vacío).
        conTrabajoMientrasEstaAbierto(uriBase, "rw") { it.close() }
        conTrabajoMientrasEstaAbierto(uriBase, "w") { it.close() }
        // Uno cortado.
        conTrabajoMientrasEstaAbierto(uriBase, "wt") { pfd ->
            FileOutputStream(pfd.fileDescriptor).write(Banco.base(entera = true).let { it.copyOf(it.size / 2) })
            pfd.close()
        }
        esperar("no se ha avisado del guardado cortado") { aviso.exists() }
        // Uno que se cierra con error.
        conTrabajoMientrasEstaAbierto(uriBase, "wt") { it.closeWithError("se ha caído") }
        // Uno cuyo descriptor se queda el cliente.
        conTrabajoMientrasEstaAbierto(uriBase, "wt") { ParcelFileDescriptor.adoptFd(it.detachFd()).close() }
        assertArrayEquals(Banco.otraBase(), base.readBytes())

        // Uno que no llega a abrirse (no se puede crear `.prdrive/tmp/`): no deja el trabajo puesto.
        val antes = Actualizador.trabajosEnMarcha()
        temporales.deleteRecursively()
        temporales.writeText("no es una carpeta")
        try {
            seNiega("abrir para escribir sin carpeta de temporales") { resolver.openFileDescriptor(uriBase, "wt")?.close() }
        } finally {
            temporales.delete()
        }
        assertEquals(antes, Actualizador.trabajosEnMarcha())
    }

    @Test
    fun con_la_actualizacion_instalandose_no_se_abre_un_guardado() {
        assertTrue("hay trabajos en marcha", Actualizador.reservar())
        try {
            seNiega("abrir para escribir mientras se instala la versión nueva") {
                resolver.openFileDescriptor(uriBase, "wt")?.close()
            }
            // Leer, sí.
            resolver.openFileDescriptor(uriBase, "r")!!.close()
        } finally {
            Actualizador.soltar()
        }
        assertTrue((temporales.list() ?: emptyArray()).isEmpty())
        assertArrayEquals(Banco.base(entera = true), base.readBytes())
    }

    @Test
    fun un_guardado_bueno_de_otra_base_no_quita_el_aviso_de_esta() {
        // El de personal.kdbx, cortado.
        escribir(uriBase, "wt", Banco.otraBase().let { it.copyOf(it.size / 2) })
        esperar("no se ha avisado del guardado cortado") { aviso.exists() }

        // Uno bueno de otra base del llavero (una copia de conflicto) no lo tapa.
        val copia = File(llavero, "personal (conflicto).kdbx").apply { writeBytes(Banco.base(entera = true)) }
        escribir(uri("$LLAVERO_LOCAL/${copia.name}"), "wt", Banco.otraBase())
        assertArrayEquals(Banco.otraBase(), copia.readBytes())
        esperar("no se ha encolado la subida") { encolados().isNotEmpty() }
        assertTrue("un guardado de otra base ha quitado el aviso", aviso.exists())
        assertEquals("personal.kdbx", Store.readJson(aviso)["fichero"])

        // Uno bueno de la misma, sí.
        escribir(uriBase, "wt", Banco.otraBase())
        esperar("el aviso sigue tras un guardado bueno de su base") { !aviso.exists() }
    }

    @Test
    fun un_enlace_a_la_llave_no_deja_escribirla() {
        val enlace = File(v.raiz, "sync-data/notas/llave.lnk")
        enlace.parentFile!!.mkdirs()
        // Como el que traería rclone de un `.rclonelink` en una pareja con `links`.
        java.nio.file.Files.createSymbolicLink(enlace.toPath(), File(llavero, LLAVERO_LLAVE).toPath())
        val id = uri("sync-data/notas/llave.lnk")
        resolver.openFileDescriptor(id, "r")!!.use { pfd ->
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), FileInputStream(pfd.fileDescriptor).readBytes())
        }
        for (modo in listOf("w", "wt", "rw", "wa")) {
            seNiega("escribir la llave por un enlace con \"$modo\"") { resolver.openFileDescriptor(id, modo)?.close() }
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(llavero, LLAVERO_LLAVE).readBytes())
    }
}
