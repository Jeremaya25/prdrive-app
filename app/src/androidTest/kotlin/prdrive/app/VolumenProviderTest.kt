package prdrive.app

import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.documentos.VolumenProvider
import prdrive.engine.CARPETA_PROGRAMA

/**
 * VolumenProviderTest.kt — El volumen en la app de Archivos: se ve lo de las
 * parejas, no se ve ni se alcanza `.prdrive/`, y lo que se crea desde fuera
 * acaba en el volumen.
 *
 * Se habla con el proveedor directamente (el test corre en el proceso de la
 * app); la app de Archivos lo hace a través del sistema, con los mismos ids.
 */
@RunWith(AndroidJUnit4::class)
class VolumenProviderTest {

    private lateinit var proveedor: VolumenProvider
    private lateinit var raiz: File

    @Before
    fun preparar() {
        raiz = Prdrive.volumen(Banco.contexto).raiz
        raiz.deleteRecursively()
        File(raiz, "sync-data/notas").mkdirs()
        File(raiz, "sync-data/notas/a.txt").writeText("hola")
        File(raiz, "$CARPETA_PROGRAMA/keys").mkdirs()
        File(raiz, "$CARPETA_PROGRAMA/keys/id_ed25519").writeText("secreto")
        proveedor = VolumenProvider()
        proveedor.attachInfo(Banco.contexto, android.content.pm.ProviderInfo().apply {
            authority = VolumenProvider.AUTORIDAD
            exported = true
            grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        })
    }

    private fun hijos(id: String): List<String> {
        val c = proveedor.queryChildDocuments(id, null, null as String?)
        val nombres = ArrayList<String>()
        while (c.moveToNext()) nombres.add(c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)))
        c.close()
        return nombres
    }

    @Test
    fun hay_una_raiz_que_se_llama_prdrive() {
        val c = proveedor.queryRoots(null)
        assertTrue(c.moveToFirst())
        assertEquals(VolumenProvider.ID_RAIZ, c.getString(c.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_DOCUMENT_ID)))
        c.close()
    }

    @Test
    fun la_carpeta_del_programa_no_se_ve() {
        assertEquals(listOf("sync-data"), hijos(VolumenProvider.ID_RAIZ))
        assertEquals(listOf("a.txt"), hijos("${VolumenProvider.ID_RAIZ}/sync-data/notas"))
    }

    @Test
    fun la_carpeta_del_programa_no_se_alcanza_por_ningun_camino() {
        for (id in listOf(
            "${VolumenProvider.ID_RAIZ}/$CARPETA_PROGRAMA/keys/id_ed25519",
            "${VolumenProvider.ID_RAIZ}/sync-data/../$CARPETA_PROGRAMA/keys/id_ed25519",
            "${VolumenProvider.ID_RAIZ}/../volumen/$CARPETA_PROGRAMA",
            "${VolumenProvider.ID_RAIZ}/../../shared_prefs",
            "otra/cosa",
        )) {
            try {
                proveedor.openDocument(id, "r", null).close()
                fail("se ha podido abrir $id")
            } catch (_: FileNotFoundException) {
                // lo esperado
            }
        }
        // Crear algo llamado como la carpeta del programa no da la carpeta del
        // programa: se crea con el primer nombre libre, que es otra cosa.
        val creado = proveedor.createDocument(VolumenProvider.ID_RAIZ, Document.MIME_TYPE_DIR, CARPETA_PROGRAMA)
        assertTrue(creado != "${VolumenProvider.ID_RAIZ}/$CARPETA_PROGRAMA")
        assertEquals("secreto", File(raiz, "$CARPETA_PROGRAMA/keys/id_ed25519").readText())
    }

    @Test
    fun lo_que_se_crea_desde_fuera_acaba_en_el_volumen() {
        val carpeta = "${VolumenProvider.ID_RAIZ}/sync-data/notas"
        val id = proveedor.createDocument(carpeta, "text/plain", "nuevo.txt")
        proveedor.openDocument(id, "w", null).use { pfd ->
            java.io.FileOutputStream(pfd.fileDescriptor).use { it.write("escrito desde otra app".toByteArray()) }
        }
        assertEquals("escrito desde otra app", File(raiz, "sync-data/notas/nuevo.txt").readText())
        // Un segundo con el mismo nombre no pisa al primero.
        val otro = proveedor.createDocument(carpeta, "text/plain", "nuevo.txt")
        assertTrue(otro.endsWith("nuevo (1).txt"))

        val renombrado = proveedor.renameDocument(id, "renombrado.txt")
        assertTrue(File(raiz, "sync-data/notas/renombrado.txt").isFile)
        proveedor.deleteDocument(renombrado)
        assertFalse(File(raiz, "sync-data/notas/renombrado.txt").exists())
        assertTrue(proveedor.isChildDocument(VolumenProvider.ID_RAIZ, carpeta))
        assertFalse(proveedor.isChildDocument(carpeta, VolumenProvider.ID_RAIZ))
    }
}
