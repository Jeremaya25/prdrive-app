package prdrive.app.ui.piezas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import prdrive.app.tema.Colores
import prdrive.app.tema.Tokens
import prdrive.app.ui.Paleta

/**
 * Botones.kt — Los botones del sistema de diseño (`.pd-btn` de
 * `components/bundle.css`): la jerarquía es de peso, no de color.
 *
 * - [Peso.PRINCIPAL]: uno por pantalla, la acción que se viene a hacer.
 * - [Peso.TONAL]: las de cada día.
 * - [Peso.CONTORNO]: lo demás.
 * - [Peso.SILENCIOSO]: solo texto, dentro de una fila o un aviso.
 * - [Peso.PELIGRO] y [Peso.PELIGRO_SOLIDO]: quitar o borrar; el sólido, solo en
 *   la confirmación.
 */
enum class Peso { PRINCIPAL, TONAL, CONTORNO, SILENCIOSO, PELIGRO, PELIGRO_SOLIDO }

/** El alto del dibujo; el toque es siempre de 48 dp como mínimo. */
enum class Tamano(val alto: Dp) { PEQUENO(28.dp), NORMAL(34.dp), GRANDE(42.dp) }

/**
 * Un botón con su texto en infinitivo («Sincronizar ahora»), y con «…» si abre
 * otra pantalla.
 *
 * El dibujo mide [Tamano.alto] y crece con la letra grande del sistema en vez de
 * cortarla; el toque, 48 dp como poco (`minimumInteractiveComponentSize`).
 * [ocupado] cambia el icono por un anillo que gira y deja de responder (su
 * `stateDescription` es «ocupado»): un segundo toque no hace nada, y no lo
 * parece. [activo] a `false` lo apaga del todo, con el gris del sistema.
 * [sobreAmbar] es para los que viven dentro de un aviso ámbar: su borde y su
 * texto son los de ese aviso.
 */
@Composable
fun Boton(
    texto: String,
    alPulsar: () -> Unit,
    modifier: Modifier = Modifier,
    peso: Peso = Peso.CONTORNO,
    tamano: Tamano = Tamano.GRANDE,
    icono: Icono? = null,
    ancho: Boolean = false,
    ocupado: Boolean = false,
    activo: Boolean = true,
    sobreAmbar: Boolean = false,
) {
    val c = Paleta.actual
    val colores = coloresDe(c, peso, activo, sobreAmbar)
    val forma = RoundedCornerShape(Tokens.radioMd)
    val medidas = medidasDe(tamano, peso)
    Row(
        modifier = modifier
            .then(if (ancho) Modifier.fillMaxWidth() else Modifier)
            .minimumInteractiveComponentSize()
            .heightIn(min = tamano.alto)
            .clip(forma)
            .background(colores.fondo)
            .then(if (colores.borde != Color.Transparent) Modifier.border(1.dp, colores.borde, forma) else Modifier)
            .clickable(enabled = activo && !ocupado, role = Role.Button, onClick = alPulsar)
            .semantics { if (ocupado) stateDescription = "ocupado" }
            .padding(medidas.relleno),
        horizontalArrangement = Arrangement.spacedBy(medidas.hueco, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides colores.letra) {
            if (ocupado) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = colores.letra, strokeWidth = 2.dp)
            } else if (icono != null) {
                Glifo(icono)
            }
            Text(texto, style = medidas.letra, color = colores.letra, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Un botón que es solo un glifo (cerrar una hoja, volver): sin texto al lado,
 * así que lleva [descripcion], que es lo que oye quien no lo ve. Se toca en 48 dp.
 */
@Composable
fun BotonDeIcono(icono: Icono, descripcion: String, alPulsar: () -> Unit, modifier: Modifier = Modifier) {
    val c = Paleta.actual
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(Tokens.radioMd))
            .clickable(role = Role.Button, onClickLabel = descripcion, onClick = alPulsar),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides c.ink2) { Glifo(icono, tamano = 20.dp, descripcion = descripcion) }
    }
}

private data class ColoresDeBoton(val fondo: Color, val letra: Color, val borde: Color)

/** Los colores de `bundle.css` para cada peso, y el gris de desactivado. */
private fun coloresDe(c: Colores, peso: Peso, activo: Boolean, sobreAmbar: Boolean): ColoresDeBoton {
    val nada = Color.Transparent
    if (!activo) {
        return when (peso) {
            Peso.SILENCIOSO -> ColoresDeBoton(nada, c.disabled, nada)
            Peso.TONAL -> ColoresDeBoton(c.disabledBg, c.disabled, nada)
            else -> ColoresDeBoton(c.disabledBg, c.disabled, c.line)
        }
    }
    return when (peso) {
        Peso.PRINCIPAL -> ColoresDeBoton(c.accent, c.onAccent, nada)
        Peso.TONAL -> ColoresDeBoton(c.accentSoft, c.accentStrong, nada)
        Peso.CONTORNO -> ColoresDeBoton(c.surface, c.ink, if (sobreAmbar) c.warnLineStrong else c.border)
        Peso.SILENCIOSO -> ColoresDeBoton(nada, if (sobreAmbar) c.warnInk else c.accent, nada)
        Peso.PELIGRO -> ColoresDeBoton(c.surface, c.danger, c.danger)
        Peso.PELIGRO_SOLIDO -> ColoresDeBoton(c.danger, c.onDanger, nada)
    }
}

private data class Medidas(val relleno: PaddingValues, val hueco: Dp, val letra: TextStyle)

/** El relleno, el hueco entre icono y texto y la letra de cada tamaño (`.pd-sm`, `.pd-lg`). */
private fun medidasDe(tamano: Tamano, peso: Peso): Medidas {
    // El silencioso es solo texto: menos relleno y letra de peso 500 (`.pd-quiet`).
    val silencioso = peso == Peso.SILENCIOSO
    val grosor = if (silencioso) FontWeight.Medium else FontWeight.SemiBold
    val (lado, hueco, letra) = when (tamano) {
        Tamano.PEQUENO -> Triple(12.dp, 6.dp, 12.5.sp)
        Tamano.NORMAL -> Triple(if (silencioso) 12.dp else 16.dp, 8.dp, 13.5.sp)
        Tamano.GRANDE -> Triple(if (silencioso) 12.dp else 24.dp, 10.dp, 14.5.sp)
    }
    return Medidas(
        PaddingValues(horizontal = lado, vertical = 4.dp),
        hueco,
        TextStyle(fontSize = letra, lineHeight = letra * 1.3f, fontWeight = grosor),
    )
}
