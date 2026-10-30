package prdrive.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.ui.Tema
import prdrive.app.ui.piezas.Aviso
import prdrive.app.ui.piezas.Boton
import prdrive.app.ui.piezas.Chip
import prdrive.app.ui.piezas.Insignia
import prdrive.app.ui.piezas.Marca
import prdrive.app.ui.piezas.Peso
import prdrive.app.ui.piezas.Tamano
import prdrive.app.ui.piezas.Tono
import prdrive.app.ui.piezas.TonoAviso
import prdrive.app.ui.piezas.TonoLinea
import prdrive.app.ui.piezas.tonoDeLinea

/**
 * PiezasTest.kt — Las piezas comunes de las pantallas (`ui/piezas/`), solas:
 * que digan lo que enseñan a quien no ve el color, que se toquen en 48 dp y que
 * no se escondan con la letra grande del sistema.
 */
@RunWith(AndroidJUnit4::class)
class PiezasTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun la_marca_dice_su_estado() {
        var insignia by mutableStateOf(Insignia.AVISO)
        compose.setContent { Tema { Marca(40.dp, insignia = insignia) } }
        compose.onNodeWithContentDescription("prdrive, con avisos").assertExists()
        insignia = Insignia.NINGUNA
        compose.onNodeWithContentDescription("prdrive").assertExists()
    }

    @Test
    fun todo_boton_se_toca_en_48_dp() {
        compose.setContent {
            Tema {
                Column {
                    for (t in Tamano.entries) {
                        Boton("Sincronizar", {}, Modifier.testTag("b-$t"), peso = Peso.PRINCIPAL, tamano = t)
                    }
                }
            }
        }
        for (t in Tamano.entries) compose.onNodeWithTag("b-$t").assertTouchHeightIsEqualTo(48.dp)
    }

    @Test
    fun un_boton_ocupado_no_vuelve_a_disparar() {
        var veces = 0
        compose.setContent { Tema { Boton("Sincronizar", { veces++ }, Modifier.testTag("b"), ocupado = true) } }
        compose.onNodeWithTag("b").performClick()
        compose.onNodeWithTag("b").performClick()
        assertEquals(0, veces)
        compose.onNodeWithTag("b").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "ocupado"))
    }

    @Test
    fun un_boton_desactivado_lo_dice() {
        compose.setContent { Tema { Boton("Sincronizar", {}, Modifier.testTag("b"), activo = false) } }
        compose.onNodeWithTag("b").assertIsNotEnabled()
    }

    @Test
    fun un_chip_se_lee_por_su_palabra() {
        compose.setContent { Tema { Chip("al día", tono = Tono.OK) } }
        compose.onNodeWithText("al día").assertExists()
        // El disco con su glifo no dice nada: lo dice la palabra.
        val conAlgoQueLeer = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) or
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)
        compose.onAllNodes(conAlgoQueLeer, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun un_aviso_de_peligro_se_anuncia() {
        compose.setContent { Tema { Aviso("«vault» falló", Modifier.testTag("a"), tono = TonoAviso.PELIGRO) } }
        compose.onNodeWithTag("a").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    @Test
    fun las_lineas_del_log_se_colorean_por_lo_que_dicen() {
        assertEquals(TonoLinea.CABECERA, tonoDeLinea("=== vault ==="))
        assertEquals(TonoLinea.ORDEN, tonoDeLinea("  ejecutando: rclone bisync …"))
        assertEquals(TonoLinea.BIEN, tonoDeLinea("[vault] OK."))
        assertEquals(TonoLinea.ERROR, tonoDeLinea("2026/10/30 12:00:00 ERROR : vault: fallo al copiar"))
        assertEquals(TonoLinea.AVISO, tonoDeLinea("2026/10/30 12:00:00 NOTICE: vault: algo que mirar"))
        assertEquals(TonoLinea.NORMAL, tonoDeLinea("Applying changes"))
    }
}
