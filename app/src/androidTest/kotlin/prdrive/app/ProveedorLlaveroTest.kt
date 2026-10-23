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

        val datos = Banco.otraBase()
        escribir(creada, "w", datos.copyOf(datos.size / 2))
        assertEquals("han quedado bytes rotos en nueva.kdbx", 0L, nueva.length())

        escribir(creada, "w", datos)
        assertArrayEquals(datos, nueva.readBytes())
    }

    @Test
    fun leer_una_base_encola_la_pasada_de_lectura_y_no_la_toca() {
        val pfd = resolver.openFileDescriptor(uriBase, "r")!!
        assertArrayEquals(Banco.base(entera = true), FileInputStream(pfd.fileDescriptor).readBytes())
        pfd.close()
        val trabajo = encolados().singleOrNull()
        assertNotNull("leer no ha encolado la pasada", trabajo)
        assertEquals(0L, trabajo!!.initialDelayMillis)
        assertTrue((temporales.list() ?: emptyArray()).isEmpty())
    }

    @Test
    fun la_llave_se_ve_pero_no_se_escribe_ni_se_borra() {
        val c = resolver.query(DocumentsContract.buildChildDocumentsUri(VolumenProvider.AUTORIDAD, "${VolumenProvider.ID_RAIZ}/$LLAVERO_LOCAL"), null, null, null, null)!!
        var flags: Int? = null
        while (c.moveToNext()) {
            if (c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)) == LLAVERO_LLAVE) {
                flags = c.getInt(c.getColumnIndexOrThrow(Document.COLUMN_FLAGS))
            }
        }
        c.close()
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
}
