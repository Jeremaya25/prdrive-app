package prdrive.app.ui.piezas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import prdrive.app.tema.Colores
import prdrive.app.ui.Paleta

/** La pastilla de estado de la marca, la del icono de la bandeja del escritorio. */
enum class Insignia { NINGUNA, SINCRONIZANDO, AVISO }

/**
 * Marca.kt — La marca de prdrive: dos arcos gruesos de extremos redondos (blanco
 * y ámbar) alrededor de una unidad de una sola pieza, sobre el campo azul y con
 * un filete que lo separa del fondo en oscuro. Es `DriveMark` de
 * `components/bundle.js` del sistema de diseño, con su misma geometría: un
 * lienzo de 64 unidades, escalado a [tamano].
 *
 * Con [insignia], la pastilla se recorta del campo (un círculo de radio 20 que
 * borra, en una capa aparte) en vez de llevar un halo, así que vale sobre
 * cualquier fondo; encima va el disco de radio 16 con su glifo. El estado se
 * dice también en palabras (su `contentDescription`): el color solo no basta.
 */
@Composable
fun Marca(tamano: Dp, modifier: Modifier = Modifier, insignia: Insignia = Insignia.NINGUNA) {
    val c = Paleta.actual
    val pequena = tamano <= 20.dp
    val descripcion = when (insignia) {
        Insignia.NINGUNA -> "prdrive"
        Insignia.SINCRONIZANDO -> "prdrive, sincronizando"
        Insignia.AVISO -> "prdrive, con avisos"
    }
    Canvas(
        modifier
            .size(tamano)
            .graphicsLayer {
                // El recorte de la pastilla borra lo de debajo: en su propia capa,
                // para no borrar también el fondo de la pantalla.
                if (insignia != Insignia.NINGUNA) compositingStrategy = CompositingStrategy.Offscreen
            }
            .semantics {
                contentDescription = descripcion
                role = Role.Image
            },
    ) {
        scale(size.width / LADO, pivot = Offset.Zero) {
            campo(c, pequena)
            if (insignia != Insignia.NINGUNA) pastilla(c, insignia)
        }
    }
}

private const val LADO = 64f

private val CUERPO = PathParser().parsePathString(
    "M29 20h6v6h2.5a1.5 1.5 0 0 1 1.5 1.5v14.5a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2V27.5A1.5 1.5 0 0 1 26.5 26H29z",
).toPath()

private val FLECHA_SUBE = PathParser().parsePathString("M44.5 53V41.5M41 45l3.5-3.5L48 45").toPath()
private val FLECHA_BAJA = PathParser().parsePathString("M49.5 41v11.5M46 49l3.5 3.5L53 49").toPath()

/** El campo, los dos arcos, la unidad y el filete. */
private fun DrawScope.campo(c: Colores, pequena: Boolean) {
    drawRoundRect(c.markBlue, size = Size(LADO, LADO), cornerRadius = CornerRadius(14f))
    val arco = Stroke(width = 7f, cap = StrokeCap.Round)
    val esquina = Offset(32f - 21f, 32f - 21f)
    val caja = Size(42f, 42f)
    drawArc(c.markPaper, startAngle = -78f, sweepAngle = 156f, useCenter = false, topLeft = esquina, size = caja, style = arco)
    drawArc(c.markAmber, startAngle = 102f, sweepAngle = 156f, useCenter = false, topLeft = esquina, size = caja, style = arco)
    if (pequena) {
        // A 20 dp o menos los detalles de la unidad no se ven: un rectángulo liso.
        drawRoundRect(c.markPaper, topLeft = Offset(27f, 21f), size = Size(10f, 22f), cornerRadius = CornerRadius(2f))
    } else {
        drawPath(CUERPO, c.markPaper)
    }
    drawRoundRect(
        c.markEdge,
        topLeft = Offset(0.5f, 0.5f),
        size = Size(63f, 63f),
        cornerRadius = CornerRadius(13.5f),
        style = Stroke(width = 1f),
    )
}

/** El recorte de radio 20 y, encima, el disco de radio 16 con su glifo. */
private fun DrawScope.pastilla(c: Colores, insignia: Insignia) {
    val centro = Offset(47f, 47f)
    // Lo que se dibuje con Clear se borra, sea del color que sea.
    drawCircle(c.markInk, radius = 20f, center = centro, blendMode = BlendMode.Clear)
    when (insignia) {
        Insignia.SINCRONIZANDO -> {
            drawCircle(c.traySync, radius = 16f, center = centro)
            val trazo = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(FLECHA_SUBE, c.markInk, style = trazo)
            drawPath(FLECHA_BAJA, c.markInk, style = trazo)
        }
        Insignia.AVISO -> {
            drawCircle(c.markAmber, radius = 16f, center = centro)
            drawRoundRect(c.markInk, topLeft = Offset(45f, 38f), size = Size(4f, 11.5f), cornerRadius = CornerRadius(2f))
            drawRoundRect(c.markInk, topLeft = Offset(45f, 52f), size = Size(4f, 4f), cornerRadius = CornerRadius(2f))
        }
        Insignia.NINGUNA -> Unit
    }
}
