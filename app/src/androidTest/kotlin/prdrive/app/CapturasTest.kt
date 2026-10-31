package prdrive.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.ui.Seccion

/**
 * CapturasTest.kt — Las pantallas principales, una captura de cada una en
 * `/data/local/tmp/prdrive-cap-<n>.png`, para mirarlas contra el lienzo del
 * rediseño en claro, en oscuro y con la letra grande. No comprueba nada: solo
 * corre con el argumento `capturas` (si no, acaba sin hacer nada, que AGP cuenta
 * un test saltado como fallido).
 *
 *     adb shell cmd uimode night yes; adb shell settings put system font_scale 1.3
 *     ./gradlew :app:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.CapturasTest \
 *         -Pandroid.testInstrumentationRunnerArguments.capturas=oscuro
 *     adb pull /data/local/tmp/prdrive-cap-oscuro-1-inicio.png
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class CapturasTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val prefijo: String? = InstrumentationRegistry.getArguments().getString("capturas")

    private fun captura(nombre: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/prdrive-cap-$prefijo-$nombre.png")
            .let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } }
    }

    private fun esperarA(etiqueta: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(etiqueta)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun las_pantallas_principales() {
        if (prefijo.isNullOrBlank()) return
        Banco.volumen(
            Banco.pareja("notas"),
            Banco.pareja("vault", local = "vault"),
            Banco.pareja("fotos", "sync-data/fotos", "mode" to "down-mirror"),
            llavero = "personal.kdbx",
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            esperarA("fila-notas")
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("ocupado")).fetchSemanticsNodes().isEmpty() }
            captura("1-inicio")
            compose.onNodeWithTag(Seccion.PAREJAS.etiqueta).performClick()
            esperarA("pantalla-parejas")
            captura("2-parejas")
            compose.onNodeWithTag("fila-vault").performClick()
            esperarA("pantalla-pareja-vault")
            captura("3-ficha")
            compose.onNodeWithTag("resync-vault").performScrollTo().performClick()
            esperarA("confirmar-resync")
            Thread.sleep(800)
            captura("4-hoja")
            Espresso.pressBack()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("confirmar-resync")).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("mover-vault").performScrollTo().performClick()
            esperarA("pantalla-mover-vault")
            captura("5-mover")
            compose.onNodeWithTag(Seccion.LLAVERO.etiqueta).performClick()
            esperarA("pantalla-llavero")
            captura("6-llavero")
            compose.onNodeWithTag(Seccion.AJUSTES.etiqueta).performClick()
            esperarA("pantalla-ajustes")
            captura("7-ajustes")
        }
    }
}
