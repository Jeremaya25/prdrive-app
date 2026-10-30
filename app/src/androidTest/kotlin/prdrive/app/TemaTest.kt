package prdrive.app

import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.tema.Colores
import prdrive.app.tema.Tokens
import prdrive.app.ui.Paleta
import prdrive.app.ui.Tema

/**
 * TemaTest.kt — El modo oscuro: que las pantallas sigan al del teléfono, con los
 * colores del sistema de diseño (`tema/Tokens.kt`), y que el fondo de la
 * ventana (`@color/fondo`, de `res/values/fondo.xml` y
 * `res/values-night/fondo.xml`), que es lo primero que se ve al abrir la app,
 * sea el «paper» de las pantallas en los dos modos. Los dos los escribe
 * `herramientas/diseno.py`, pero el de la ventana pasa por los recursos de
 * Android y el de Compose por `Tema.kt`: aquí se ve que no se separan.
 */
@RunWith(AndroidJUnit4::class)
class TemaTest {

    @get:Rule
    val compose = createComposeRule()

    /** La configuración del teléfono, con el modo oscuro puesto o quitado. */
    private fun conModo(base: Configuration, oscuro: Boolean) = Configuration(base).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (oscuro) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }

    private fun fondoDeLaVentana(oscuro: Boolean): Int {
        val contexto = Banco.contexto
        return contexto.createConfigurationContext(conModo(contexto.resources.configuration, oscuro))
            .getColor(R.color.fondo)
    }

    @Test
    fun el_fondo_de_la_ventana_es_el_papel_en_los_dos_modos() {
        assertEquals(Tokens.claro.paper.toArgb(), fondoDeLaVentana(oscuro = false))
        assertEquals(Tokens.oscuro.paper.toArgb(), fondoDeLaVentana(oscuro = true))
    }

    @Test
    fun las_pantallas_siguen_al_modo_oscuro_del_telefono() {
        val vistos = mutableMapOf<Boolean, ColorScheme>()
        val paletas = mutableMapOf<Boolean, Colores>()
        compose.setContent {
            for (oscuro in listOf(false, true)) {
                CompositionLocalProvider(LocalConfiguration provides conModo(LocalConfiguration.current, oscuro)) {
                    Tema {
                        vistos[oscuro] = MaterialTheme.colorScheme
                        paletas[oscuro] = Paleta.actual
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(Tokens.claro.paper, vistos[false]?.background)
        assertEquals(Tokens.oscuro.paper, vistos[true]?.background)
        assertEquals(Tokens.claro, paletas[false])
        assertEquals(Tokens.oscuro, paletas[true])
    }
}
