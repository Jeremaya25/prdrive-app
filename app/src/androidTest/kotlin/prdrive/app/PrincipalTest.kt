package prdrive.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PrincipalTest.kt — La pantalla principal al abrir la app con el volumen ya
 * sembrado, sin red: las parejas son carpetas locales ([Banco]).
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class PrincipalTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    /**
     * Al abrir, el modelo lee las filas fuera del hilo de la pantalla. Si el
     * modelo nace dentro de la primera composición, sus estados nacen en la
     * instantánea de esa composición, y al aplicarse esta pisa lo que el otro
     * hilo haya escrito entretanto: la principal salía sin ninguna pareja y sin
     * error. Pasaba sobre todo en frío, con la primera composición lenta; aquí
     * rclone ya está arrancado y las filas se leen en un momento, que es lo que
     * hace falta para que la lectura acabe antes que la composición. Cada vuelta
     * es una actividad nueva, con su modelo nuevo.
     */
    @Test
    fun al_abrir_con_el_volumen_sembrado_se_ven_las_parejas() {
        Banco.volumen(Banco.pareja("uno"), Banco.pareja("dos"))
        repeat(VUELTAS) { vuelta ->
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("ocupado")).fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTagOrNull("error")?.let { error("vuelta $vuelta: la pantalla dice un error") }
                for (pareja in listOf("uno", "dos")) {
                    assertTrue(
                        "vuelta $vuelta: no se ve la pareja $pareja",
                        compose.onAllNodes(hasTestTag("fila-$pareja")).fetchSemanticsNodes().isNotEmpty(),
                    )
                }
            }
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithTagOrNull(tag: String) =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()

    private companion object {
        const val VUELTAS = 5
    }
}
