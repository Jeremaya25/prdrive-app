package prdrive.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.ui.Modelo
import prdrive.app.ui.Seccion

/**
 * NavegacionTest.kt — Las cuatro secciones de abajo y la pila de pantallas: que
 * se llegue a cada una, que el botón atrás del sistema vuelva por donde se vino
 * (de una ficha a su lista, de una sección a Inicio, y desde Inicio fuera de la
 * app) y que Android, al recrear la actividad, la deje donde estaba.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class NavegacionTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    /** Espera a que el modelo haya leído las filas: hasta entonces, la lista está vacía. */
    private fun esperarALaFila(nombre: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("fila-$nombre")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun esperarA(etiqueta: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(etiqueta)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun las_cuatro_secciones_se_eligen_desde_abajo() {
        Banco.volumen(Banco.pareja("uno"))
        ActivityScenario.launch(MainActivity::class.java).use {
            for (s in listOf(Seccion.PAREJAS, Seccion.LLAVERO, Seccion.AJUSTES, Seccion.INICIO)) {
                compose.onNodeWithTag(s.etiqueta).performClick()
                val pantalla = "pantalla-" + s.name.lowercase()
                esperarA(pantalla)
                compose.onNodeWithTag(pantalla).assertIsDisplayed()
                compose.onNodeWithTag(s.etiqueta).assertIsSelected()
            }
        }
    }

    @Test
    fun atras_desde_una_ficha_vuelve_a_su_lista_y_desde_una_seccion_a_inicio() {
        Banco.volumen(Banco.pareja("uno"))
        ActivityScenario.launch(MainActivity::class.java).use { escena ->
            compose.onNodeWithTag(Seccion.PAREJAS.etiqueta).performClick()
            esperarALaFila("uno")
            compose.onNodeWithTag("fila-uno").performClick()
            esperarA("pantalla-pareja-uno")

            Espresso.pressBack()
            esperarA("pantalla-parejas")
            compose.onNodeWithTag("pantalla-parejas").assertIsDisplayed()

            Espresso.pressBack()
            esperarA("pantalla-inicio")
            compose.onNodeWithTag("pantalla-inicio").assertIsDisplayed()

            // Desde Inicio, sin nada encima, atrás es de Android: la app se va.
            Espresso.pressBackUnconditionally()
            compose.waitUntil(10_000) { escena.state.isAtMost(Lifecycle.State.CREATED) }
            assertTrue("la app sigue delante (${escena.state})", escena.state.isAtMost(Lifecycle.State.CREATED))
        }
    }

    @Test
    fun recrear_la_actividad_no_pierde_la_ficha() {
        Banco.volumen(Banco.pareja("uno"))
        ActivityScenario.launch(MainActivity::class.java).use { escena ->
            compose.onNodeWithTag(Seccion.PAREJAS.etiqueta).performClick()
            esperarALaFila("uno")
            compose.onNodeWithTag("fila-uno").performClick()
            esperarA("pantalla-pareja-uno")

            escena.recreate()

            esperarA("pantalla-pareja-uno")
            compose.onNodeWithTag("pantalla-pareja-uno").assertIsDisplayed()
            compose.onNodeWithTag(Seccion.PAREJAS.etiqueta).assertIsSelected()
        }
    }

    @Test
    fun con_treinta_parejas_inicio_se_desplaza_y_la_barra_queda() {
        Banco.volumen(*Array(30) { Banco.pareja("p$it") })
        ActivityScenario.launch(MainActivity::class.java).use {
            esperarALaFila("p0")
            compose.onNodeWithTag("fila-p29").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag(Seccion.AJUSTES.etiqueta).assertIsDisplayed()
        }
    }

    /**
     * Con una pasada en marcha, lo que cambia algo se apaga también en la
     * ficha: el modelo ya ignora el segundo toque, pero la pantalla no debe
     * parecer que lo va a hacer. «dos» es de bajada y trae 3000 ficheros, para
     * que la pasada dure lo bastante como para mirar.
     */
    @Test
    fun con_un_trabajo_en_marcha_la_ficha_apaga_sus_botones() {
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(Banco.contexto.packageName, Prdrive.PERMISO_RED_LOCAL)
        }
        Banco.volumen(Banco.pareja("uno"), Banco.pareja("dos", "sync-data/dos", "mode" to "down"))
        val origen = File(Banco.remoto, "dos").apply { mkdirs() }
        repeat(3000) { File(origen, "f$it.txt").writeText("$it") }
        ActivityScenario.launch(MainActivity::class.java).use {
            esperarALaFila("uno")
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("ocupado")).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("sincronizar").performClick()
            compose.onNodeWithTag("fila-uno").performScrollTo().performClick()
            esperarA("pantalla-pareja-uno")
            esperarA("ocupado")
            compose.onNodeWithTag("sincronizar-uno").assertIsNotEnabled()
            compose.waitUntil(120_000) { compose.onAllNodes(hasTestTag("ocupado")).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("sincronizar-uno").assertIsEnabled()
        }
    }

    /**
     * Con la letra del sistema a 2 y un error largo, lo de arriba (el error)
     * no se come la pantalla: la sección sigue teniendo casi todo el alto, y el
     * error se puede cerrar.
     */
    @Test
    fun con_letra_grande_un_error_largo_no_tapa_la_seccion_y_se_cierra() {
        Banco.volumen(Banco.pareja("uno"))
        shell("settings put system font_scale 2.0")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { escena ->
                esperarALaFila("uno")
                compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("ocupado")).fetchSemanticsNodes().isEmpty() }
                val largo = List(4) { "Esto es un error muy largo que rclone podría contar sobre la conexión con el remoto." }
                    .joinToString(" ") + "\n\n" + Prdrive.SIN_RED_LOCAL
                escena.onActivity { ViewModelProvider(it)[Modelo::class.java].avisar(largo) }
                esperarA("error")
                val raiz = compose.onRoot().getUnclippedBoundsInRoot()
                val seccion = compose.onNodeWithTag("pantalla-inicio").getBoundsInRoot()
                assertTrue(
                    "la sección se queda con ${seccion.height} de ${raiz.height}",
                    seccion.height >= raiz.height * 0.35f,
                )
                compose.onNodeWithTag("cerrar-error").performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("error")).fetchSemanticsNodes().isEmpty() }
            }
        } finally {
            shell("settings put system font_scale 1.0")
        }
    }

    /** Una orden del shell, leída hasta el final (si no, puede no haber acabado). */
    private fun shell(orden: String) {
        val salida = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(orden)
        java.io.FileInputStream(salida.fileDescriptor).use { it.readBytes() }
        salida.close()
    }

    private fun Lifecycle.State.isAtMost(otro: Lifecycle.State) = this <= otro
}
