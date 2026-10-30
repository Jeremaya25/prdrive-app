package prdrive.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.ui.Modelo
import prdrive.engine.Resultados

/**
 * SimulacroEnAndroidTest.kt — «Hacer un simulacro…» desde la ficha, contra el
 * rclone de verdad: un espejo de bajada dice cuántos ficheros bajaría y cuáles
 * borraría en el teléfono, sin escribir nada ni apuntar ninguna pasada.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class SimulacroEnAndroidTest {

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

    private fun dice(etiqueta: String, texto: String) =
        compose.onAllNodes((hasTestTag(etiqueta) or hasAnyAncestor(hasTestTag(etiqueta))) and hasText(texto, substring = true))
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun el_simulacro_de_un_espejo_dice_lo_que_bajaria_y_borraria_sin_tocar_nada() {
        val (v, _) = Banco.volumen(Banco.pareja("musica", "sync-data/musica", "mode" to "down-mirror"))
        File(Banco.remoto, "musica").apply { mkdirs() }.also {
            File(it, "uno.flac").writeText("uno")
            File(it, "dos.flac").writeText("dos")
        }
        val sobra = File(v.raiz, "sync-data/musica/demo.mp3").apply { parentFile!!.mkdirs(); writeText("demo") }
        ActivityScenario.launch(MainActivity::class.java).use { escena ->
            var modelo: Modelo? = null
            escena.onActivity { modelo = ViewModelProvider(it)[Modelo::class.java] }
            Banco.abrirFicha(compose, "musica")
            compose.onNodeWithTag("simulacro-musica").performScrollTo().performClick()
            compose.waitUntil(60_000) { dice("simulacro-copias", "2") }
            assertTrue("no dice lo que borraría", dice("simulacro-borrados", "1"))
            assertTrue("no nombra el que borraría", dice("simulacro-lista-borrados", "demo.mp3"))
            assertTrue("el botón no dice cuántos borra", dice("simulacro-sincronizar", "borrar 1"))
            assertTrue("el simulacro ha borrado", sobra.isFile)
            assertTrue("el simulacro ha bajado", !File(v.raiz, "sync-data/musica/uno.flac").exists())
            assertNull("el simulacro se ha apuntado como pasada", Resultados.ultimasBuenas(v.state, listOf("musica"))["musica"])
            assertNull(checkNotNull(modelo).resultados["musica"])
        }
    }
}
