package prdrive.app.ui.piezas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import prdrive.app.tema.Tokens
import prdrive.app.ui.Letra
import prdrive.app.ui.Paleta

/**
 * Listas.kt — Lo que se pone en fila: la tarjeta que las agrupa, el rótulo de
 * una sección, la fila que lleva a su detalle, la fila de un aviso, la casilla y
 * la opción de tarjeta (`.pd-surface`, `.pd-label`, `.pd-check` y `.pd-radio`
 * de `components/bundle.css`, con las medidas del teléfono del lienzo).
 *
 * Todo lo que se pulsa se pulsa entero (la fila, no su flecha) y mide 48 dp o
 * más; las filas, 56. Nada lleva sombra: se separa por borde.
 */
@Composable
fun Tarjeta(modifier: Modifier = Modifier, contenido: @Composable ColumnScope.() -> Unit) {
    val c = Paleta.actual
    val forma = RoundedCornerShape(Tokens.radioMd)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(forma)
            .background(c.surface)
            .border(1.dp, c.line, forma),
        content = contenido,
    )
}

/** La línea entre dos filas de una tarjeta. */
@Composable
fun Separador() {
    HorizontalDivider(thickness = 1.dp, color = Paleta.actual.lineSoft)
}

/** El rótulo de una sección: en mayúsculas por estilo, no por escrito, y marcado como encabezado. */
@Composable
fun Rotulo(texto: String, modifier: Modifier = Modifier) {
    Text(
        texto.uppercase(),
        style = Letra.actual.rotulo,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * Una fila de una lista: de 56 dp como poco, con la baldosa de su [icono] sobre
 * `sunken`, el [titulo], el [detalle] (en monoespaciada si es una ruta) y la
 * [pista] debajo, y a la derecha su [extremo] (un chip, un botón). Con
 * [alPulsar], se toca entera y lleva el chevrón. [cursiva] es la de una pareja
 * que este teléfono no usa.
 *
 * Es un solo nodo para la accesibilidad (`mergeDescendants`): se lee de una vez.
 * Su `testTag` lo pone quien la usa.
 */
@Composable
fun Fila(
    titulo: String,
    modifier: Modifier = Modifier,
    alPulsar: (() -> Unit)? = null,
    icono: Icono? = null,
    detalle: String? = null,
    detalleMono: Boolean = false,
    pista: String? = null,
    cursiva: Boolean = false,
    extremo: @Composable (() -> Unit)? = null,
) {
    val c = Paleta.actual
    val letra = Letra.actual
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (alPulsar != null) Modifier.clickable(onClick = alPulsar) else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icono != null) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(Tokens.radioMd)).background(c.sunken),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides c.ink2) { Glifo(icono) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                titulo,
                style = if (cursiva) letra.texto.copy(fontStyle = FontStyle.Italic) else letra.nombre,
                color = if (cursiva) c.ink3 else c.ink,
            )
            if (detalle != null) {
                Text(
                    detalle,
                    style = if (detalleMono) letra.mono else letra.pista,
                    color = if (cursiva) c.ink3 else c.ink2,
                    fontStyle = if (cursiva) FontStyle.Italic else null,
                )
            }
            if (pista != null) Text(pista, style = letra.pista)
        }
        extremo?.invoke()
        if (alPulsar != null) Chevron()
    }
}

/**
 * La flecha de «lleva a otra pantalla». No es uno de los 30 glifos del sistema:
 * el lienzo la dibuja suelta, con el mismo trazo, y aquí igual.
 */
@Composable
private fun Chevron() {
    val color = Paleta.actual.ink3
    Canvas(Modifier.size(16.dp)) {
        val u = size.width / 16f
        val trazo = Stroke(width = 1.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path().apply {
            moveTo(6f * u, 3.5f * u)
            lineTo(10.5f * u, 8f * u)
            lineTo(6f * u, 12.5f * u)
        }
        drawPath(p, color, style = trazo)
    }
}

/**
 * La fila de algo que revisar: la baldosa del tono, el [titulo] («vault» falló)
 * y el [cuerpo] con lo que pasa, y al tocarla, su arreglo.
 */
@Composable
fun FilaDeAviso(titulo: String, cuerpo: String, tono: TonoAviso, alPulsar: () -> Unit, modifier: Modifier = Modifier) {
    val c = Paleta.actual
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clickable(onClick = alPulsar)
            .semantics(mergeDescendants = true) {}
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Baldosa(tono)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titulo, style = Letra.actual.nombre, color = c.ink)
            Text(cuerpo, style = Letra.actual.pista, color = c.ink2)
        }
        Chevron()
    }
}

/**
 * Una casilla de marcar: la fila entera se toca (`toggleable`), y la caja de
 * 18 dp lleva el glifo `OK` cuando está marcada.
 */
@Composable
fun Casilla(
    texto: String,
    marcada: Boolean,
    alCambiar: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    pista: String? = null,
    activa: Boolean = true,
) {
    val c = Paleta.actual
    val forma = RoundedCornerShape(Tokens.radioSm)
    val (fondo, borde) = when {
        !activa && marcada -> c.disabled to c.disabled
        !activa -> c.disabledBg to c.line
        marcada -> c.accent to c.accent
        else -> c.surface to c.border
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = marcada, enabled = activa, role = Role.Checkbox, onValueChange = alCambiar)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).clip(forma).background(fondo).border(1.dp, borde, forma),
            contentAlignment = Alignment.Center,
        ) {
            if (marcada) {
                CompositionLocalProvider(LocalContentColor provides if (activa) c.onAccent else c.disabledBg) {
                    Glifo(Icono.OK, tamano = 14.dp)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(texto, style = Letra.actual.nombre, color = if (activa) c.ink else c.disabled)
            if (pista != null) Text(pista, style = Letra.actual.pista)
        }
    }
}

/**
 * Una opción de tarjeta que se elige entera (`selectable`): elegida, toma
 * `accent-soft` y un borde `accent` de 2 dp.
 */
@Composable
fun Opcion(titulo: String, elegida: Boolean, alElegir: () -> Unit, modifier: Modifier = Modifier, pista: String? = null) {
    val c = Paleta.actual
    val forma = RoundedCornerShape(Tokens.radioMd)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(forma)
            .background(if (elegida) c.accentSoft else c.surface)
            .border(if (elegida) 2.dp else 1.dp, if (elegida) c.accent else c.border, forma)
            .selectable(selected = elegida, role = Role.RadioButton, onClick = alElegir)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(16.dp).clip(CircleShape).background(c.surface)
                .border(1.dp, if (elegida) c.accent else c.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (elegida) Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titulo, style = Letra.actual.nombre, color = c.ink, fontWeight = FontWeight.SemiBold)
            if (pista != null) Text(pista, style = Letra.actual.pista)
        }
    }
}
