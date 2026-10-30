package prdrive.app

import androidx.compose.ui.test.ExperimentalTestApi
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

    @Test
    fun atras_vuelve_un_paso_al_emparejar_y_no_sale_de_la_app() {
        ActivityScenario.launch(MainActivity::class.java).use { escena ->
            compose.onNodeWithText("Pegar el texto del código").performClick()
            compose.onNodeWithTag("carga").performTextInput(carga())
            compose.onNodeWithTag("usarTexto").performScrollTo().performClick()
            esperarA("conectar")
            compose.onNodeWithTag("conectar").performClick()
            esperarA("instalar", 60)

            Espresso.pressBack()
            esperarA("conectar")
            assertEquals(Lifecycle.State.RESUMED, escena.state)

            Espresso.pressBack()
            esperarA("escanear")
            assertEquals(Lifecycle.State.RESUMED, escena.state)
        }
    }
}
