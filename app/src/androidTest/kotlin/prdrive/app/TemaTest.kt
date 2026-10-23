package prdrive.app

import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.ui.Tema

/**
 * TemaTest.kt — El modo oscuro: que las pantallas sigan al del teléfono, y que
 * el fondo de la ventana (`res/values/tema.xml` y `res/values-night/tema.xml`),
 * que es lo primero que se ve al abrir la app, sea el de las pantallas en los
 * dos modos. Ese fondo está escrito en el XML, y un Material 3 nuevo podría
 * cambiar el de Compose sin avisar.
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
    fun el_fondo_de_la_ventana_es_el_de_las_pantallas_en_los_dos_modos() {
        assertEquals(lightColorScheme().background.toArgb(), fondoDeLaVentana(oscuro = false))
        assertEquals(darkColorScheme().background.toArgb(), fondoDeLaVentana(oscuro = true))
    }

    @Test
    fun las_pantallas_siguen_al_modo_oscuro_del_telefono() {
        val vistos = mutableMapOf<Boolean, ColorScheme>()
        compose.setContent {
            for (oscuro in listOf(false, true)) {
                CompositionLocalProvider(LocalConfiguration provides conModo(LocalConfiguration.current, oscuro)) {
                    Tema { vistos[oscuro] = MaterialTheme.colorScheme }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(lightColorScheme().background, vistos[false]?.background)
        assertEquals(darkColorScheme().background, vistos[true]?.background)
    }
}
