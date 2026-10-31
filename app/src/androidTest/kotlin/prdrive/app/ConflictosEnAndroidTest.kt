package prdrive.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import prdrive.app.rclone.Adaptadores
import prdrive.engine.Conflictos
import prdrive.engine.Pasada

/**
 * ConflictosEnAndroidTest.kt — Resolver un conflicto desde la ficha, contra el
 * rclone de verdad: bisync deja un conflicto, se elige la versión del teléfono,
 * y al aplicar se queda con su nombre, las copias se van y la pasada de después
 * lo lleva al remoto.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ConflictosEnAndroidTest {

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
    fun elegir_la_version_del_telefono_la_deja_en_los_dos_lados_sin_copias() {
        val (v, config) = Banco.volumen(Banco.pareja("notas"))
        val p = config.pairs.single()
        val remoto = File(Banco.remoto, "notas").apply { mkdirs() }
        File(remoto, "plan.md").writeText("uno")
        // Los que no se tocan: si cambiaran todos los de un lado, bisync pararía; y
        // borrar las copias después tiene que quedar por debajo del 25 % del freno.
        repeat(6) { File(remoto, "quieto$it.txt").writeText("quieto") }
        val inicial = Pasada.pasar(v, p, Adaptadores.pasada, Adaptadores.opciones, Pasada.Contexto(resyncAprobado = true))
        assertEquals(0, inicial.codigo)
        File(v.raiz, "sync-data/notas/plan.md").writeText("del teléfono")
        File(remoto, "plan.md").writeText("del remoto, que es más nuevo")
        File(remoto, "plan.md").setLastModified(System.currentTimeMillis() + 60_000)
        val conflicto = Pasada.pasar(v, p, Adaptadores.pasada, Adaptadores.opciones, Pasada.Contexto())
        assertEquals(1, conflicto.conflictos.size)

        ActivityScenario.launch(MainActivity::class.java).use {
            Banco.abrirFicha(compose, "notas")
            compose.waitUntil(30_000) { hay("resolver-notas") && !hay("ocupado") }
            compose.onNodeWithTag("resolver-notas").performScrollTo().performClick()
            compose.waitUntil(10_000) { hay("pantalla-conflictos-notas") }
            compose.onNodeWithTag("version-plan.md-${Conflictos.DISPOSITIVO}").performScrollTo().performClick()
            compose.onNodeWithTag("aplicar-conflictos").performClick()
            compose.waitUntil(10_000) { hay("confirmar-conflictos") }
            compose.onNodeWithTag("confirmar-conflictos").performClick()
            compose.waitUntil(120_000) { hay("pantalla-pareja-notas") && !hay("ocupado") }
        }
        val local = File(v.raiz, "sync-data/notas")
        assertEquals("del teléfono", File(local, "plan.md").readText())
        assertEquals("del teléfono", File(remoto, "plan.md").readText())
        val copias = { d: File -> d.list()!!.filter { it.startsWith("plan.md") && it != "plan.md" } }
        assertTrue("quedan copias en el teléfono: ${copias(local)}", copias(local).isEmpty())
        assertTrue("quedan copias en el remoto: ${copias(remoto)}", copias(remoto).isEmpty())
        assertTrue(Conflictos.cargar(config, v.raiz, v.state)["notas"].orEmpty().isEmpty())
    }
}
