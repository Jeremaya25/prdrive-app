package prdrive.app.ui.piezas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import prdrive.app.tema.Colores
import prdrive.app.tema.Tokens
import prdrive.app.ui.Letra
import prdrive.app.ui.Paleta

/**
 * Estados.kt — Cómo se dice el estado: el chip, el aviso, la línea de estado,
 * el progreso y el registro de una pasada (`.pd-chip`, `.pd-notice`,
 * `.pd-statusline`, `.pd-bar` y `.pd-log` de `components/bundle.css`).
 *
 * El estado nunca se dice solo con el color: el chip lleva su palabra y un disco
 * con glifo, y el aviso, su título. El color vive en el marcador (el disco, la
 * baldosa); el texto va siempre en tinta.
 */
enum class Tono { OK, EN_MARCHA, AVISO, PELIGRO, APAGADO, NEUTRO }

/** El tono de un [Aviso]. */
enum class TonoAviso { AVISO, PELIGRO, INFO, OK }

/**
 * La etiqueta de estado o de modo: una píldora con un disco de color y su
 * glifo delante, y la palabra en tinta. [Tono.APAGADO] es hueca, con el borde de
 * trazos («no se usa aquí»); [Tono.NEUTRO] no lleva disco, solo [icono] (un
 * modo, un dato). Sin [icono], el disco lleva el glifo de su tono.
 */
@Composable
fun Chip(texto: String, modifier: Modifier = Modifier, tono: Tono = Tono.NEUTRO, icono: Icono? = null) {
    val c = Paleta.actual
    val apagado = tono == Tono.APAGADO
    val forma = CircleShape
    val conDisco = tono != Tono.NEUTRO
    val borde = if (apagado) {
        // El borde de trazos de `.pd-muted`: no hay `border` de trazos en Compose.
        Modifier.drawBehind {
            val r = size.height / 2
            drawRoundRect(
                c.border,
                cornerRadius = CornerRadius(r),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
            )
        }
    } else {
        Modifier.border(1.dp, c.line, forma)
    }
    Row(
        modifier = modifier
            .heightIn(min = 24.dp)
            .clip(forma)
            .background(if (apagado) Color.Transparent else c.sunken)
            .then(borde)
            .padding(start = if (conDisco) 2.dp else 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (conDisco) {
            val (fondo, glifo) = discoDe(c, tono)
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (apagado) Color.Transparent else fondo)
                    .then(if (apagado) Modifier.border(1.dp, c.ink3, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides glifo) {
                    Glifo(glifoDelDisco(tono, icono), tamano = 12.dp)
                }
            }
        } else if (icono != null) {
            CompositionLocalProvider(LocalContentColor provides c.ink2) { Glifo(icono, tamano = 12.dp) }
        }
        Text(
            texto,
            color = if (apagado) c.ink3 else c.ink,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** El fondo del disco y el color de su glifo. */
private fun discoDe(c: Colores, tono: Tono): Pair<Color, Color> = when (tono) {
    Tono.OK -> c.ok to c.onOk
    Tono.EN_MARCHA -> c.accent to c.onAccent
    Tono.AVISO -> c.warn to c.onWarn
    Tono.PELIGRO -> c.danger to c.onDanger
    Tono.APAGADO -> Color.Transparent to c.ink3
    Tono.NEUTRO -> c.ink2 to c.surface
}

/** El glifo del disco: el de [icono] si lo hay (con `WARN` cambiado por `ALERT`), o el de su tono. */
private fun glifoDelDisco(tono: Tono, icono: Icono?): Icono {
    if (icono != null) return if (icono == Icono.WARN) Icono.ALERT else icono
    return when (tono) {
        Tono.OK -> Icono.OK
        Tono.EN_MARCHA -> Icono.SYNC
        Tono.AVISO -> Icono.ALERT
        Tono.PELIGRO -> Icono.CLOSE
        Tono.APAGADO, Tono.NEUTRO -> Icono.CLOCK
    }
}

/**
 * Una franja que hay que leer: un borrado, un fallo, una aclaración. Una
 * baldosa de 32 dp del color del tono con su glifo, el título en tinta y el
 * cuerpo debajo, sobre el fondo suave del tono. Uno de [TonoAviso.PELIGRO] se
 * anuncia en cuanto sale (`liveRegion` asertiva): es un fallo. [extra] va entre
 * el cuerpo y las [acciones] (una barra de progreso, una línea con su etiqueta).
 */
@Composable
fun Aviso(
    titulo: String,
    modifier: Modifier = Modifier,
    tono: TonoAviso = TonoAviso.AVISO,
    cuerpo: String? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
    acciones: @Composable RowScope.() -> Unit = {},
) {
    val c = Paleta.actual
    val (fondo, linea) = when (tono) {
        TonoAviso.AVISO -> c.warnSoft to c.warnLine
        TonoAviso.PELIGRO -> c.dangerSoft to c.dangerLine
        TonoAviso.INFO -> c.accentSoft to c.accentLine
        TonoAviso.OK -> c.okSoft to c.okLine
    }
    val forma = RoundedCornerShape(Tokens.radioMd)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(forma)
            .background(fondo)
            .border(1.dp, linea, forma)
            .semantics(mergeDescendants = true) {
                if (tono == TonoAviso.PELIGRO) liveRegion = LiveRegionMode.Assertive
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Baldosa(tono)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.heightIn(min = 32.dp), contentAlignment = Alignment.CenterStart) {
                Text(titulo, style = Letra.actual.nombre, color = c.ink)
            }
            if (cuerpo != null) Text(cuerpo, style = Letra.actual.texto, color = c.ink2)
            extra()
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = acciones,
            )
        }
    }
}

/** La baldosa de 32 dp de un aviso, del color de su tono, con su glifo de 18 dp. */
@Composable
internal fun Baldosa(tono: TonoAviso) {
    val c = Paleta.actual
    val (fondo, glifo, icono) = when (tono) {
        TonoAviso.AVISO -> Triple(c.warn, c.onWarn, Icono.ALERT)
        TonoAviso.PELIGRO -> Triple(c.danger, c.onDanger, Icono.CLOSE)
        TonoAviso.INFO -> Triple(c.accent, c.onAccent, Icono.DOCTOR)
        TonoAviso.OK -> Triple(c.ok, c.onOk, Icono.OK)
    }
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(Tokens.radioMd)).background(fondo),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides glifo) { Glifo(icono, tamano = 18.dp) }
    }
}

/**
 * Una línea de una frase con su icono y, a la derecha, su acción: la del
 * llavero en Inicio. Con [aviso], en ámbar, y el icono va en un disco.
 * [etiquetaAccion] es el `testTag` del botón de la acción.
 */
@Composable
fun LineaDeEstado(
    icono: Icono,
    texto: String,
    modifier: Modifier = Modifier,
    accion: String? = null,
    alPulsar: () -> Unit = {},
    aviso: Boolean = false,
    etiquetaAccion: String? = null,
    activa: Boolean = true,
) {
    val c = Paleta.actual
    val forma = RoundedCornerShape(Tokens.radioMd)
    // Con la letra muy grande, la acción al lado dejaría el texto en una columna
    // estrecha, una palabra por línea: baja debajo, a la derecha.
    val apilada = accion != null && LocalDensity.current.fontScale >= LETRA_GRANDE
    val boton: @Composable () -> Unit = {
        if (accion != null) {
            Boton(
                accion,
                alPulsar,
                modifier = if (etiquetaAccion != null) Modifier.testTag(etiquetaAccion) else Modifier,
                peso = Peso.SILENCIOSO,
                tamano = Tamano.NORMAL,
                sobreAmbar = aviso,
                activo = activa,
            )
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(forma)
            .background(if (aviso) c.warnSoft else c.surface)
            .border(1.dp, if (aviso) c.warnLine else c.line, forma)
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.End,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (aviso) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(c.warn), contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalContentColor provides c.onWarn) {
                        Glifo(if (icono == Icono.WARN) Icono.ALERT else icono, tamano = 12.dp)
                    }
                }
            } else {
                CompositionLocalProvider(LocalContentColor provides c.ink3) { Glifo(icono) }
            }
            Text(texto, style = Letra.actual.texto, color = if (aviso) c.ink else c.ink2, modifier = Modifier.weight(1f))
            if (!apilada) boton()
        }
        if (apilada) boton()
    }
}

/** La escala de letra del sistema a partir de la cual una acción baja debajo de su texto. */
private const val LETRA_GRANDE = 1.5f

/**
 * La barra de progreso de 6 dp sobre `sunken`, con [texto] en monoespaciada
 * debajo (los números de la pasada). Sin [fraccion], una barra sin fin: es una
 * espera de red, o rclone aún no sabe cuánto hay.
 */
@Composable
fun Progreso(fraccion: Float?, modifier: Modifier = Modifier, texto: String? = null) {
    val c = Paleta.actual
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val forma = RoundedCornerShape(Tokens.radioSm)
        val barra = Modifier.fillMaxWidth().height(6.dp).clip(forma).border(1.dp, c.line, forma)
        if (fraccion != null) {
            LinearProgressIndicator(
                progress = { fraccion },
                modifier = barra,
                color = c.accent,
                trackColor = c.sunken,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        } else {
            LinearProgressIndicator(modifier = barra, color = c.accent, trackColor = c.sunken, gapSize = 0.dp)
        }
        if (texto != null) Text(texto, style = Letra.actual.mono, color = c.ink2)
    }
}

/** Cómo se colorea una línea del registro, por lo que dice. */
enum class TonoLinea { CABECERA, ORDEN, BIEN, AVISO, ERROR, NORMAL }

/**
 * El tono de una línea del log de una pasada: las cabeceras de `Pasada`
 * («=== vault (bisync) ==="), la orden que se lanza («ejecutando: …»), cómo ha
 * acabado («[vault] OK.», «[vault] FALLÓ …») y los niveles de rclone
 * («ERROR :», «NOTICE:»).
 */
fun tonoDeLinea(linea: String): TonoLinea {
    val l = linea.trim()
    return when {
        l.startsWith("===") && l.endsWith("===") -> TonoLinea.CABECERA
        l.startsWith("ejecutando:") -> TonoLinea.ORDEN
        "ERROR :" in l || "ERROR:" in l || "] FALLÓ" in l -> TonoLinea.ERROR
        "NOTICE:" in l || "WARNING:" in l || "requiere --resync" in l || "] Saltada" in l -> TonoLinea.AVISO
        (l.startsWith("[") && "] OK." in l) || "Bisync successful" in l -> TonoLinea.BIEN
        else -> TonoLinea.NORMAL
    }
}

/**
 * El registro de una pasada, línea a línea, en monoespaciada y coloreado por lo
 * que dice ([tonoDeLinea]). Sin ajuste de línea (un log se lee en columnas): se
 * desplaza en horizontal. Se puede seleccionar y copiar.
 */
@Composable
fun Registro(lineas: List<String>, modifier: Modifier = Modifier) {
    val c = Paleta.actual
    val texto = remember(lineas, c) {
        buildAnnotatedString {
            lineas.forEachIndexed { i, linea ->
                if (i > 0) append("\n")
                val estilo = when (tonoDeLinea(linea)) {
                    TonoLinea.CABECERA -> SpanStyle(fontWeight = FontWeight.Bold)
                    TonoLinea.ORDEN -> SpanStyle(color = c.ink3)
                    TonoLinea.BIEN -> SpanStyle(color = c.ok)
                    TonoLinea.AVISO -> SpanStyle(color = c.warn)
                    TonoLinea.ERROR -> SpanStyle(color = c.danger, fontWeight = FontWeight.Bold)
                    TonoLinea.NORMAL -> null
                }
                if (estilo == null) append(linea) else withStyle(estilo) { append(linea) }
            }
        }
    }
    val forma = RoundedCornerShape(0.dp)
    SelectionContainer(
        modifier
            .fillMaxWidth()
            .background(c.surface, forma)
            .border(1.dp, c.border, forma)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            texto,
            style = Letra.actual.mono.copy(lineHeight = 18.sp),
            color = c.ink,
            softWrap = false,
        )
    }
}
