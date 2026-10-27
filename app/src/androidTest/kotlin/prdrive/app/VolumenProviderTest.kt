package prdrive.app

import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
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

    /**
     * Los enlaces los trae rclone (un `.rclonelink` del remoto en una pareja con
     * `links`), y apuntan adonde quiera quien los escribió.
     */
    @Test
    fun un_enlace_no_lleva_a_la_carpeta_del_programa_y_borrarlo_no_toca_lo_que_senala() {
        val notas = "${VolumenProvider.ID_RAIZ}/sync-data/notas"
        Files.createSymbolicLink(File(raiz, "sync-data/notas/llaves").toPath(), File(raiz, "$CARPETA_PROGRAMA/keys").toPath())
        Files.createSymbolicLink(File(raiz, "sync-data/notas/fuera").toPath(), File(raiz, "..").toPath())
        // Ni se lista ni se abre lo que hay detrás.
        assertEquals(listOf("a.txt"), hijos(notas))
        for (id in listOf("$notas/llaves/id_ed25519", "$notas/fuera/volumen/$CARPETA_PROGRAMA/keys/id_ed25519")) {
            try {
                proveedor.openDocument(id, "r", null).close()
                fail("se ha podido abrir $id")
            } catch (_: FileNotFoundException) {
                // lo esperado
            }
        }
        // Borrar la carpeta que lo contiene se lleva el enlace, no la clave.
        proveedor.deleteDocument(notas)
        assertFalse(File(raiz, "sync-data/notas").exists())
        assertEquals("secreto", File(raiz, "$CARPETA_PROGRAMA/keys/id_ed25519").readText())

        // Un enlace a otra carpeta del volumen sí se ve; borrarlo o renombrarlo
        // es borrar o renombrar el enlace.
        File(raiz, "sync-data/docs").mkdirs()
        File(raiz, "sync-data/docs/b.txt").writeText("b")
        Files.createSymbolicLink(File(raiz, "sync-data/atajo").toPath(), File(raiz, "sync-data/docs").toPath())
        assertEquals(listOf("atajo", "docs"), hijos("${VolumenProvider.ID_RAIZ}/sync-data"))
        val renombrado = proveedor.renameDocument("${VolumenProvider.ID_RAIZ}/sync-data/atajo", "otro")
        assertTrue(Files.isSymbolicLink(File(raiz, "sync-data/otro").toPath()))
        assertTrue(File(raiz, "sync-data/docs/b.txt").isFile)
        proveedor.deleteDocument(renombrado)
        assertFalse(Files.exists(File(raiz, "sync-data/otro").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("b", File(raiz, "sync-data/docs/b.txt").readText())
    }

    /**
     * Por un enlace de en medio, una ruta de dentro del volumen puede volver a
     * su raíz: `x` apunta a la carpeta de la app, y `…/x/volumen` es el volumen
     * entero. Una app con permiso sobre una carpeta puede pedir ese id.
     */
    @Test
    fun por_un_enlace_de_en_medio_no_se_vuelve_a_la_raiz() {
        val sync = "${VolumenProvider.ID_RAIZ}/sync-data"
        Files.createSymbolicLink(File(raiz, "sync-data/x").toPath(), raiz.absoluteFile.parentFile!!.toPath())
        Files.createSymbolicLink(File(raiz, "sync-data/raiz").toPath(), raiz.absoluteFile.toPath())
        for (id in listOf("$sync/x/volumen", "$sync/raiz", "$sync/x/volumen/sync-data", "$sync/raiz/sync-data/notas")) {
            try {
                proveedor.deleteDocument(id)
                fail("se ha podido borrar $id")
            } catch (_: FileNotFoundException) {
                // lo esperado
            }
            assertFalse("$id es hijo", proveedor.isChildDocument(sync, id))
        }
        try {
            proveedor.openDocument("$sync/x/volumen/$CARPETA_PROGRAMA/keys/id_ed25519", "r", null).close()
            fail("se ha podido abrir la clave")
        } catch (_: FileNotFoundException) {
            // lo esperado
        }
        assertEquals("secreto", File(raiz, "$CARPETA_PROGRAMA/keys/id_ed25519").readText())
        assertEquals("hola", File(raiz, "sync-data/notas/a.txt").readText())
        assertEquals(listOf("notas"), hijos(sync))
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

    /**
     * Una pareja con entrada en `compartidas.json` (en cualquier estado) ya no
     * vive en el volumen: su copia privada no se toca desde la app de Archivos,
     * o la próxima pasada encontraría cambios que nadie hizo en la carpeta buena.
     */
    private fun compartir(estado: prdrive.engine.Compartidas.Estado = prdrive.engine.Compartidas.Estado.LISTA) {
        val v = Prdrive.volumen(Banco.contexto)
        v.escribirConfig(
            mapOf(
                "defaults" to mapOf("device_remote" to "disp", "remote" to "nas"),
                "pair" to listOf(
                    mapOf("name" to "vault", "mode" to "bisync", "local" to "vault", "remote_path" to "x/vault"),
                    mapOf("name" to "notas", "mode" to "bisync", "local" to "notas", "remote_path" to "x/notas"),
                ),
            ),
            "",
        )
        File(raiz, "vault/dentro").mkdirs()
        File(raiz, "vault/a.txt").writeText("privada")
        File(raiz, "notas").mkdirs()
        prdrive.engine.Compartidas.escribir(
            v.compartidas,
            mapOf("vault" to prdrive.engine.Compartidas.Entrada("/storage/emulated/0/Documents/vault", estado)),
        )
    }

    private fun seNiega(que: String, accion: () -> Unit) {
        try {
            accion()
            fail("se ha podido: $que")
        } catch (_: FileNotFoundException) {
            // lo esperado
        }
    }

    @Test
    fun una_pareja_compartida_no_deja_escribir_ni_crear_su_carpeta_privada() {
        for (estado in prdrive.engine.Compartidas.Estado.entries) {
            compartir(estado)
            val vault = "${VolumenProvider.ID_RAIZ}/vault"
            seNiega("escribir un fichero de dentro ($estado)") { proveedor.openDocument("$vault/a.txt", "w", null).close() }
            seNiega("escribir con rw ($estado)") { proveedor.openDocument("$vault/a.txt", "rw", null).close() }
            seNiega("crear un fichero dentro ($estado)") { proveedor.createDocument(vault, "text/plain", "nuevo.txt") }
            seNiega("crear una carpeta en lo de dentro ($estado)") {
                proveedor.createDocument("$vault/dentro", Document.MIME_TYPE_DIR, "x")
            }
            seNiega("borrar un fichero de dentro ($estado)") { proveedor.deleteDocument("$vault/a.txt") }
            seNiega("borrar la carpeta ($estado)") { proveedor.deleteDocument(vault) }
            seNiega("renombrar un fichero de dentro ($estado)") { proveedor.renameDocument("$vault/a.txt", "b.txt") }
            seNiega("renombrar la carpeta ($estado)") { proveedor.renameDocument(vault, "otra") }
            // Ni crear otra con su nombre, ni renombrar una a su nombre.
            File(raiz, "vault").deleteRecursively()
            seNiega("crear vault en la raíz ($estado)") {
                proveedor.createDocument(VolumenProvider.ID_RAIZ, Document.MIME_TYPE_DIR, "vault")
            }
            seNiega("renombrar otra carpeta a vault ($estado)") {
                proveedor.renameDocument("${VolumenProvider.ID_RAIZ}/notas", "vault")
            }
            assertFalse(File(raiz, "vault").exists())
            assertTrue(File(raiz, "notas").isDirectory)
        }
    }

    @Test
    fun una_pareja_compartida_deja_leer_su_carpeta_privada() {
        compartir()
        val vault = "${VolumenProvider.ID_RAIZ}/vault"
        assertEquals(listOf("a.txt", "dentro"), hijos(vault))
        proveedor.openDocument("$vault/a.txt", "r", null).use { pfd ->
            assertEquals("privada", java.io.FileInputStream(pfd.fileDescriptor).readBytes().decodeToString())
        }
        // Y las demás parejas siguen escribiéndose.
        val id = proveedor.createDocument("${VolumenProvider.ID_RAIZ}/notas", "text/plain", "n.txt")
        proveedor.deleteDocument(id)
    }
}
