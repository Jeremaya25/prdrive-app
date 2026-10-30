package prdrive.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.engine.Historial

/**
 * HistorialEnAndroidTest.kt — El diario de pasadas (`state/historial.jsonl`,
 * el mismo que lee el escritorio) en el teléfono: «Sincronizar» lo escribe, y la
 * ficha de la pareja enseña sus últimas pasadas.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class HistorialEnAndroidTest {

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
    fun una_pasada_queda_en_el_diario_y_la_ficha_la_ensena() {
        val (v, _) = Banco.volumen(Banco.pareja("fotos", "sync-data/fotos", "mode" to "down"))
        File(Banco.remoto, "fotos").apply { mkdirs() }.resolve("foto.txt").writeText("una foto\n")
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(30_000) { hay("fila-fotos") && !hay("ocupado") }
            compose.onNodeWithTag("sincronizar").performClick()
            compose.waitUntil(60_000) { Historial.leer(v.state).isNotEmpty() && !hay("ocupado") }
            val apuntada = Historial.leer(v.state).single()
            assertEquals("fotos", apuntada.pareja)
            assertTrue("no acabó bien", apuntada.ok)
            Banco.abrirFicha(compose, "fotos")
            compose.waitUntil(10_000) { hay("pasada-fotos-0") }
            val bien = hasText("bien", substring = true) and hasAnyAncestor(hasTestTag("pasada-fotos-0"))
            assertTrue("la fila no dice que fue bien", compose.onAllNodes(bien).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasTestTag("pasada-fotos-0") and hasText("bien", substring = true)).fetchSemanticsNodes().isNotEmpty())
        }
    }
}
