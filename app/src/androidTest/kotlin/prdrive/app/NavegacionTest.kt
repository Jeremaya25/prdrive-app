package prdrive.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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

    private fun Lifecycle.State.isAtMost(otro: Lifecycle.State) = this <= otro
}
