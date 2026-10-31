package prdrive.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.engine.Pairing
import prdrive.engine.dumps

/**
 * EmparejarTest.kt — El botón atrás mientras se empareja: vuelve un paso (de
 * elegir las parejas al código leído, y de ahí a la bienvenida) y no saca de la
 * app a mitad del emparejamiento.
 *
 * El remoto es el de [Banco]: una carpeta que rclone ve como un remote `local`,
 * con el catálogo en `catalogo/remote.toml`.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class EmparejarTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun preparar() {
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(Banco.contexto.packageName, Prdrive.PERMISO_RED_LOCAL)
        }
        Prdrive.volumen(Banco.contexto).raiz.deleteRecursively()
        Banco.remoto.deleteRecursively()
        val pareja = Banco.pareja("notas")
        File(Banco.remoto, "catalogo").mkdirs()
        File(Banco.remoto, "catalogo/remote.toml").writeText(
            dumps(mapOf("defaults" to mapOf("remote" to "nas"), "pair" to listOf(pareja)), "# El catálogo de la prueba.\n"),
        )
        File(pareja["remote_path"].toString()).mkdirs()
    }

    private fun carga(): String =
        "catalog_path = \"${File(Banco.remoto, "catalogo/remote.toml").absolutePath}\"\n" +
            "${Pairing.MARCA} = ${Pairing.FORMATO}\nremote_name = \"nas\"\n[options]\ntype = \"local\"\n"

    private fun esperarA(etiqueta: String, segundos: Long = 30) {
        compose.waitUntil(segundos * 1000) { compose.onAllNodes(hasTestTag(etiqueta)).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Con el teclado abierto para pegar el código, «Usar este texto» se puede
     * llevar a la vista por encima del teclado: lo que se desplaza acaba donde
     * empieza el teclado, no debajo. Si el emulador no enseña el teclado, no hay
     * nada que mirar.
     */
    @Test
    fun con_el_teclado_abierto_usar_este_texto_queda_por_encima() {
        ActivityScenario.launch(MainActivity::class.java).use { escena ->
            compose.onNodeWithText("Pegar el texto del código").performClick()
            compose.onNodeWithTag("carga").performClick()
            var teclado = 0
            var alto = 0
            val hasta = System.currentTimeMillis() + 5_000
            while (teclado == 0 && System.currentTimeMillis() < hasta) {
                escena.onActivity {
                    val raiz = it.window.decorView
                    alto = raiz.height
                    teclado = ViewCompat.getRootWindowInsets(raiz)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                }
                compose.waitForIdle()
            }
            if (teclado == 0) return
            compose.waitForIdle()
            val boton = compose.onNodeWithTag("usarTexto").performScrollTo().fetchSemanticsNode().boundsInWindow
            assertTrue("«Usar este texto» acaba en ${boton.bottom} y el teclado empieza en ${alto - teclado}", boton.bottom <= alto - teclado)
        }
    }

    @Test
    fun atras_vuelve_un_paso_al_emparejar_y_no_sale_de_la_app() {
        ActivityScenario.launch(MainActivity::class.java).use { escena ->
            compose.onNodeWithText("Pegar el texto del código").performClick()
            compose.onNodeWithTag("carga").performTextInput(carga())
            compose.onNodeWithTag("usarTexto").performScrollTo().performClick()
            esperarA("conectar")
            compose.onNodeWithTag("conectar").performClick()
            esperarA("instalar", 60)
            // El paso cambia un momento antes de que acabe de conectar; mientras, atrás no hace nada.
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("ocupado")).fetchSemanticsNodes().isEmpty() }

            Espresso.pressBack()
            esperarA("conectar")
            assertEquals(Lifecycle.State.RESUMED, escena.state)

            Espresso.pressBack()
            esperarA("escanear")
            assertEquals(Lifecycle.State.RESUMED, escena.state)
        }
    }
}
