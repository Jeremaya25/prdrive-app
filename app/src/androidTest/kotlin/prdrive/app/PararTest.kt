package prdrive.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.ui.Modelo

/**
 * PararTest.kt — «Parar la pasada», en Inicio mientras se sincroniza: corta la
 * pareja que está pasando (rclone para su trabajo y suelta la pareja), no pasa
 * las que quedaban, y lo cortado no se apunta como una pasada (como un Ctrl+C
 * en el escritorio). Que una bisync cortada no deje una referencia que borre lo
 * prueba `PasadaEnAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class PararTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val legible = Banco.Legible()

    @Before
    fun preparar() {
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(Banco.contexto.packageName, Prdrive.PERMISO_RED_LOCAL)
        }
    }

    private fun hay(etiqueta: String) = compose.onAllNodes(hasTestTag(etiqueta)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun parar_corta_la_pareja_en_marcha_y_no_pasa_las_demas() {
        // «dos» es de bajada y trae 3000 ficheros: dura lo bastante para pararla.
        Banco.volumen(Banco.pareja("dos", "sync-data/dos", "mode" to "down"), Banco.pareja("tres"))
        val origen = File(Banco.remoto, "dos").apply { mkdirs() }
        repeat(3000) { File(origen, "f$it.txt").writeText("$it") }
        ActivityScenario.launch(MainActivity::class.java).use { escena ->
            var modelo: Modelo? = null
            escena.onActivity { modelo = ViewModelProvider(it)[Modelo::class.java] }
            compose.waitUntil(30_000) { hay("fila-dos") && !hay("ocupado") }
            compose.onNodeWithTag("sincronizar").performClick()
            compose.waitUntil(30_000) { modelo!!.progreso?.pareja == "dos" }
            compose.onNodeWithTag("parar").performClick()
            compose.waitUntil(60_000) { !hay("ocupado") }
            val m = checkNotNull(modelo)
            assertNull("se apuntó la pasada cortada", m.resultados["dos"])
            assertNull("pasó una pareja después de parar", m.resultados["tres"])
            assertFalse("dice un error al parar", hay("error"))
            assertTrue("no se puede volver a sincronizar", hay("sincronizar"))
            val copiados = File(Prdrive.volumen(Banco.contexto).raiz, "sync-data/dos").list()?.size ?: 0
            assertTrue("no ha llegado a cortarse: $copiados de 3000", copiados < 3000)
        }
    }
}
