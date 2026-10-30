package prdrive.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.ui.Modelo
import prdrive.app.ui.Seccion
import prdrive.app.ui.Tema
import prdrive.app.ui.ajustes.Ajustes

/**
 * AjustesTest.kt — La sección Ajustes: que «Buscar actualizaciones» viva aquí
 * (y ya no en Inicio) y que cada permiso diga si está dado o cómo darlo, también
 * después de volver de los ajustes de Android.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class AjustesTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun hay(etiqueta: String) = compose.onAllNodes(hasTestTag(etiqueta)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun buscar_actualizaciones_esta_en_ajustes() {
        Banco.volumen(Banco.pareja("uno"))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(30_000) { hay("pantalla-inicio") }
            assertFalse("sigue en Inicio", hay("buscar-version"))
            compose.onNodeWithTag(Seccion.AJUSTES.etiqueta).performClick()
            compose.waitUntil(10_000) { hay("pantalla-ajustes") }
            assertTrue("no está en Ajustes", hay("buscar-version"))
        }
    }

    /**
     * El permiso de red local se finge: quitarlo de verdad
     * (`revokeRuntimePermission`) mata el proceso de la app, que es también el
     * del test. Lo que se prueba es que la fila lo diga y lo relea al volver.
     */
    @Test
    fun el_permiso_de_red_local_se_ve_dado_o_por_dar() {
        // Antes de Android 17 no hay permiso de red local que enseñar. Un `return` y no
        // `assumeTrue`: el motor de tests de AGP cuenta un test saltado como fallido.
        if (android.os.Build.VERSION.SDK_INT < 37) return
        Banco.volumen(Banco.pareja("uno"))
        var falta = true
        ActivityScenario.launch(ComponentActivity::class.java).use { escena ->
            escena.onActivity { actividad ->
                val modelo = ViewModelProvider(actividad)[Modelo::class.java]
                actividad.setContent { Tema { Ajustes(modelo, faltaRedLocal = { falta }) } }
            }
            compose.waitUntil(10_000) { hay("permiso-red-local") }
            val fila = hasTestTag("permiso-red-local")
            val dice = { texto: String ->
                compose.onAllNodes((fila or hasAnyAncestor(fila)) and hasText(texto, substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue("no ofrece darlo", dice("Dar"))
            falta = false
            escena.moveToState(Lifecycle.State.STARTED)
            escena.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { dice("dado") }
        }
    }
}
