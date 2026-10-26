package prdrive.app

import android.os.Environment
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.compartidas.Compartir

/**
 * CompartirTest.kt — Lo que la app hace con una carpeta compartida antes de
 * mover una pareja a ella: traducir la carpeta que elige la persona, mirar que
 * las fechas valgan y decir si hay acceso.
 */
@RunWith(AndroidJUnit4::class)
class CompartirTest {

    @get:Rule
    val legible = Banco.Legible()

    private lateinit var carpeta: File

    @Before
    fun dar_el_permiso() {
        // Como en FechasCompartidasTest: por appops, y leyendo el shell hasta el final.
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set prdrive.app MANAGE_EXTERNAL_STORAGE allow")
        java.io.FileInputStream(shell.fileDescriptor).use { it.readBytes() }
        shell.close()
        assertTrue("No se pudo dar MANAGE_EXTERNAL_STORAGE", Environment.isExternalStorageManager())
        carpeta = File(Environment.getExternalStorageDirectory(), "Documents/prdrive-fechas-${System.nanoTime()}")
    }

    @After
    fun borrar_la_carpeta() {
        if (::carpeta.isInitialized) carpeta.deleteRecursively()
    }

    @Test
    fun un_arbol_del_almacenamiento_principal_se_traduce_a_su_ruta() {
        val arbol = DocumentsContract.buildTreeDocumentUri(Compartir.AUTORIDAD, "primary:Documents/vault")
        assertEquals(File(Environment.getExternalStorageDirectory(), "Documents/vault"), Compartir.rutaDeArbol(arbol))
    }

    @Test
    fun un_arbol_de_otro_sitio_no_vale() {
        // Una SD o un USB: su id de volumen no es `primary`.
        assertNull(Compartir.rutaDeArbol(DocumentsContract.buildTreeDocumentUri(Compartir.AUTORIDAD, "1234-5678:vault")))
        // Otro proveedor, aunque el id se parezca al del almacenamiento principal.
        assertNull(
            Compartir.rutaDeArbol(
                DocumentsContract.buildTreeDocumentUri("com.android.providers.downloads.documents", "primary:vault"),
            ),
        )
        // Un documento suelto, no un árbol.
        assertNull(Compartir.rutaDeArbol(DocumentsContract.buildDocumentUri(Compartir.AUTORIDAD, "primary:Documents/vault")))
        // El almacenamiento entero no es una carpeta que mover.
        assertNull(Compartir.rutaDeArbol(DocumentsContract.buildTreeDocumentUri(Compartir.AUTORIDAD, "primary:")))
    }

    @Test
    fun con_permiso_las_fechas_de_una_carpeta_compartida_valen() {
        // Solo para arrancar rclone con un config.
        Banco.volumen(Banco.pareja("notas"))
        assertTrue("No se pudo crear $carpeta", carpeta.mkdirs())
        val temporal = Prdrive.temporal(Banco.contexto)

        val problema = Compartir.probarFechas(carpeta, temporal)

        assertNull(problema, problema)
        // La carpeta queda como estaba: el fichero de prueba subiría con el --resync.
        assertEquals(emptyList<String>(), carpeta.list()?.toList())
        // Y tampoco queda nada del otro lado.
        assertEquals(emptyList<String>(), temporal.listFiles { f -> f.name.startsWith("fechas-") }?.map { it.name })
    }

    @Test
    fun una_carpeta_que_rclone_no_puede_usar_es_un_motivo_y_no_deja_nada() {
        Banco.volumen(Banco.pareja("notas"))
        // Un fichero donde debería haber una carpeta: copiar dentro falla.
        carpeta.parentFile!!.mkdirs()
        carpeta.writeText("no soy una carpeta")
        val temporal = Prdrive.temporal(Banco.contexto)

        val problema = Compartir.probarFechas(carpeta, temporal)

        assertTrue("Debía dar un motivo", !problema.isNullOrBlank())
        assertEquals("no soy una carpeta", carpeta.readText())
        assertEquals(emptyList<String>(), temporal.listFiles { f -> f.name.startsWith("fechas-") }?.map { it.name })
    }

    @Test
    fun sin_permiso_el_acceso_lo_dice() {
        val (_, config) = Banco.volumen(Banco.pareja("notas", "notas"))
        val pareja = config.pairs.single().copy(carpetaExterna = carpeta.absolutePath)

        assertEquals(Prdrive.SIN_PERMISO_COMPARTIDO, Prdrive.accesoCompartido(tienePermiso = { false })(pareja))
        // Con el permiso y una carpeta del almacenamiento principal, no hay nada que decir.
        assertNull(Prdrive.accesoCompartido(tienePermiso = { true })(pareja))
        assertNull(Prdrive.accesoCompartido()(pareja))
    }
}
