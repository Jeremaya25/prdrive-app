package prdrive.app.ui.piezas

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import prdrive.app.R

/**
 * Iconos.kt — Los 30 glifos del sistema de diseño: trazo de 1,5 sobre la
 * rejilla de 16, con los nombres de `ui/icons.py` de prdrive. Los vectores
 * (`res/drawable/pd_*.xml`) los escribe `herramientas/diseno.py` desde
 * `app/diseno/iconos.json`; aquí solo se nombran.
 *
 * Nunca emoji ni caracteres sueltos en su lugar: salen en color y a un tamaño
 * que cambia en cada teléfono. Dentro de un disco, una baldosa o un botón
 * pequeño va [ALERT] (solo el «!»), no [WARN]: el triángulo no se lee a 12 dp.
 */
enum class Icono(@param:DrawableRes val res: Int) {
    SYNC(R.drawable.pd_sync),
    DISPOSITIVO(R.drawable.pd_dispositivo),
    NAS(R.drawable.pd_nas),
    BOTH(R.drawable.pd_both),
    UP(R.drawable.pd_up),
    DOWN(R.drawable.pd_down),
    OK(R.drawable.pd_ok),
    WARN(R.drawable.pd_warn),
    ALERT(R.drawable.pd_alert),
    CLOCK(R.drawable.pd_clock),
    GEAR(R.drawable.pd_gear),
    PAREJAS(R.drawable.pd_parejas),
    ARRANQUE(R.drawable.pd_arranque),
    DOCTOR(R.drawable.pd_doctor),
    FLAG(R.drawable.pd_flag),
    EYE(R.drawable.pd_eye),
    RELOAD(R.drawable.pd_reload),
    EDIT(R.drawable.pd_edit),
    TRASH(R.drawable.pd_trash),
    PLUS(R.drawable.pd_plus),
    CLOSE(R.drawable.pd_close),
    BACK(R.drawable.pd_back),
    FILE(R.drawable.pd_file),
    EXPULSAR(R.drawable.pd_expulsar),
    CARPETA(R.drawable.pd_carpeta),
    PAUSA(R.drawable.pd_pausa),
    PLAY(R.drawable.pd_play),
    CANDADO(R.drawable.pd_candado),
    CANDADO_ABIERTO(R.drawable.pd_candado_abierto),
    LLAVE(R.drawable.pd_llave),
}

/**
 * Un glifo del color de la letra de donde se dibuja (`LocalContentColor`). Va a
 * 16 dp, a 12 dentro de un chip y a 20 en la barra de secciones. Solo, sin texto
 * al lado, lleva [descripcion]; con texto, no: lo diría dos veces.
 */
@Composable
fun Glifo(icono: Icono, modifier: Modifier = Modifier, tamano: Dp = 16.dp, descripcion: String? = null) {
    Icon(
        painter = painterResource(icono.res),
        contentDescription = descripcion,
        modifier = modifier.size(tamano),
        tint = LocalContentColor.current,
    )
}
