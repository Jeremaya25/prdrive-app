package prdrive.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
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
import prdrive.app.ui.Modelo
import prdrive.engine.dumps

/**
 * ParejasEnAndroidTest.kt — Añadir una pareja del catálogo y quitarla, desde la
 * sección Parejas y la ficha, contra el rclone de verdad y un catálogo en el
 * «remoto» de [Banco] (una carpeta local): volver a leer el catálogo la saca en
 * «sin usar», añadirla con otra carpeta la baja allí (`--resync` de una carpeta
 * nueva) y lleva a su ficha, y quitarla la saca del config sin tocar sus
 * ficheros. La última pareja no se quita.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ParejasEnAndroidTest {

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

    private fun encendido(etiqueta: String) =
        compose.onAllNodes(hasTestTag(etiqueta) and isEnabled()).fetchSemanticsNodes().isNotEmpty()

    /** El catálogo del remoto, con estas parejas, donde lo busca el config de [Banco.volumen]. */
    private fun catalogo(vararg parejas: Map<String, Any?>) {
        File(Banco.remoto, "catalogo/remote.toml").apply { parentFile!!.mkdirs() }
            .writeText(dumps(mapOf("defaults" to mapOf("remote" to "nas"), "pair" to parejas.toList())))
    }

    private fun modelo(escena: ActivityScenario<MainActivity>): Modelo {
        var modelo: Modelo? = null
        escena.onActivity { modelo = ViewModelProvider(it)[Modelo::class.java] }
        return checkNotNull(modelo)
    }

    @Test
    fun anadir_una_pareja_del_catalogo_en_otra_carpeta_la_baja_alli_y_lleva_a_su_ficha() {
        val (v, _) = Banco.volumen(Banco.pareja("notas"), catalogo = true)
        catalogo(Banco.pareja("notas"), Banco.pareja("fotos"))
        File(Banco.remoto, "fotos").apply { mkdirs() }.also { File(it, "a.jpg").writeText("una foto") }
        ActivityScenario.launch(MainActivity::class.java).use { escena ->
            val m = modelo(escena)
            compose.onNodeWithTag("seccion-parejas").performClick()
            compose.waitUntil(30_000) { hay("fila-notas") }
            // La copia del catálogo no la tiene todavía: hay que leerlo otra vez.
            compose.onNodeWithTag("releer-catalogo").performScrollTo().performClick()
            compose.waitUntil(60_000) { hay("sin-usar-fotos") }
            compose.onNodeWithTag("sin-usar-fotos").performScrollTo().performClick()
            compose.waitUntil(10_000) { hay("pantalla-anadir-fotos") }
            compose.onNodeWithTag("carpeta-fotos").performTextReplacement("Fotos")
            compose.waitUntil(30_000) { encendido("anadir-ya-fotos") }
            compose.onNodeWithTag("anadir-ya-fotos").performScrollTo().performClick()
            compose.waitUntil(30_000) { hay("pantalla-pareja-fotos") }
            compose.waitUntil(120_000) { m.ocupado == null }
            assertEquals(null, m.error)
            assertEquals(listOf("notas", "fotos"), v.leerConfig().names)
            assertEquals("Fotos", v.leerConfig().pairs.first { it.name == "fotos" }.local)
            assertTrue("no ha bajado lo del remoto", File(v.raiz, "Fotos/a.jpg").isFile)
            assertTrue("la pasada no ha ido bien", m.resultados["fotos"]?.ok == true)
            assertTrue("el combine no la lleva", v.leerRcloneConf().contains(v.leerConfig().seccionCombine(v.raiz.absolutePath)))
        }
    }

    @Test
    fun quitar_una_pareja_la_saca_del_config_sin_tocar_sus_ficheros_y_vuelve_a_parejas() {
        val (v, _) = Banco.volumen(Banco.pareja("notas"), Banco.pareja("fotos"))
        val foto = File(v.raiz, "sync-data/fotos/a.jpg").apply { parentFile!!.mkdirs(); writeText("una foto") }
        ActivityScenario.launch(MainActivity::class.java).use { escena ->
            val m = modelo(escena)
            Banco.abrirFicha(compose, "fotos")
            compose.onNodeWithTag("quitar-pareja-fotos").performScrollTo().performClick()
            compose.waitUntil(10_000) { encendido("confirmar-quitar") }
            compose.onNodeWithTag("confirmar-quitar").performScrollTo().performClick()
            compose.waitUntil(30_000) { hay("pantalla-parejas") && m.ocupado == null }
            assertEquals(null, m.error)
            assertEquals(listOf("notas"), v.leerConfig().names)
            assertTrue("ha tocado sus ficheros", foto.isFile)
            assertTrue("sigue en la lista", !hay("fila-fotos"))
        }
    }

    @Test
    fun la_ultima_pareja_no_se_quita() {
        val (v, _) = Banco.volumen(Banco.pareja("notas"))
        ActivityScenario.launch(MainActivity::class.java).use {
            Banco.abrirFicha(compose, "notas")
            compose.onNodeWithTag("quitar-pareja-notas").performScrollTo().performClick()
            compose.waitUntil(10_000) { hay("quitar-no-notas") }
            assertTrue(
                "se puede confirmar",
                compose.onAllNodes(hasTestTag("confirmar-quitar") and isNotEnabled()).fetchSemanticsNodes().isNotEmpty(),
            )
            assertEquals(listOf("notas"), v.leerConfig().names)
        }
    }
}
