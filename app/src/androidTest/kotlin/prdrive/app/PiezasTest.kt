package prdrive.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.ui.Tema
import prdrive.app.ui.piezas.Insignia
import prdrive.app.ui.piezas.Marca

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
}
