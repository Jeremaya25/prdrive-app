package prdrive.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.ui.Tema
import prdrive.app.ui.piezas.Aviso
import prdrive.app.ui.piezas.Boton
import prdrive.app.ui.piezas.Casilla
import prdrive.app.ui.piezas.Chip
import prdrive.app.ui.piezas.Fila
import prdrive.app.ui.piezas.Hoja
import prdrive.app.ui.piezas.Icono
import prdrive.app.ui.piezas.Insignia
import prdrive.app.ui.piezas.LineaDeEstado
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

    @Test
    fun una_fila_se_toca_entera() {
        var veces = 0
        compose.setContent { Tema { Fila("Documentos", alPulsar = { veces++ }, detalle = "Documentos ↔ docs") } }
        compose.onNodeWithText("Documentos ↔ docs", useUnmergedTree = true).performClick()
        assertEquals(1, veces)
    }

    @Test
    fun una_casilla_se_marca_tocando_su_texto() {
        var marcada by mutableStateOf(false)
        compose.setContent { Tema { Casilla("El llavero", marcada, { marcada = it }, Modifier.testTag("c")) } }
        compose.onNodeWithText("El llavero").performClick()
        assertTrue(marcada)
        compose.onNodeWithTag("c").assertIsOn()
    }

    @Test
    fun una_fila_con_letra_grande_no_esconde_su_estado() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                Tema {
                    Box(Modifier.width(360.dp)) {
                        Fila(
                            "Una pareja con un nombre muy largo, de sesenta caracteres!!",
                            Modifier.testTag("fila"),
                            alPulsar = {},
                            detalle = "Documentos ↔ docs",
                            extremo = { Chip("al día", Modifier.testTag("chip"), tono = Tono.OK) },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("chip", useUnmergedTree = true).assertIsDisplayed()
        val chip = compose.onNodeWithTag("chip", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val fila = compose.onNodeWithTag("fila").getUnclippedBoundsInRoot()
        assertTrue("el chip (${chip.right}) se sale de la fila (${fila.right})", chip.right <= fila.right)
        assertTrue("el chip no tiene ancho", chip.right > chip.left)
    }

    /**
     * Con la letra a 2, una línea de estado con su acción no aprieta el texto en
     * una columna estrecha al lado del botón: la acción baja, y el texto se queda
     * con casi todo el ancho.
     */
    @Test
    fun una_linea_de_estado_con_letra_grande_deja_ancho_al_texto() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                Tema {
                    Box(Modifier.width(360.dp)) {
                        LineaDeEstado(
                            Icono.CANDADO,
                            "personal.kdbx, con cambios que aún no han subido.",
                            Modifier.testTag("linea"),
                            accion = "Instalar ahora",
                            aviso = true,
                            etiquetaAccion = "accion",
                        )
                    }
                }
            }
        }
        val texto = compose.onNodeWithText("personal.kdbx", substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val linea = compose.onNodeWithTag("linea").getUnclippedBoundsInRoot()
        val ancho = texto.right - texto.left
        assertTrue("el texto tiene $ancho de ${linea.right - linea.left}", ancho >= (linea.right - linea.left) * 0.6f)
        compose.onNodeWithTag("accion", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun los_pasos_de_la_mudanza_se_marcan_por_donde_va() {
        compose.setContent {
            Tema { prdrive.app.ui.parejas.PasosDeLaMudanza(prdrive.engine.Mudanza.PASOS_ENTRAR, prdrive.engine.Mudanza.Paso.BAJAR) }
        }
        val dice = { paso: String, texto: String ->
            compose.onAllNodes(hasTestTag("paso-$paso") and hasText(texto, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("las fechas no salen hechas", dice("FECHAS", "hecho"))
        assertTrue("el --resync no sale en marcha", dice("BAJAR", "en marcha"))
        assertTrue("borrar no sale en espera", dice("BORRAR", "en espera"))
    }

    @Test
    fun la_hoja_se_cierra_con_su_boton() {
        var cerrada = false
        compose.setContent { Tema { Hoja("Rehacer la referencia", alCerrar = { cerrada = true }) { Text("Nada se ha escrito todavía.") } } }
        compose.onNodeWithTag("cerrar-hoja").performClick()
        compose.waitForIdle()
        assertTrue(cerrada)
    }
}
