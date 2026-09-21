package prdrive.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Piezas.kt — Los trozos que se repiten en las dos pantallas.
 *
 * El bloque en ámbar de [Aviso] es el mismo recurso que en prdrive: un aviso no
 * es un `Toast` que se va, es un bloque que se queda hasta que se lee. Y la
 * salida de una pasada se pinta en monoespaciada y coloreada por contenido,
 * igual que la ventana de salida del escritorio.
 */
@Composable
fun Aviso(texto: String, alDescartar: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AMBAR_FONDO, RoundedCornerShape(4.dp))
            .padding(12.dp),
    ) {
        Text(texto, color = AMBAR, style = MaterialTheme.typography.bodyMedium)
        if (alDescartar != null) {
            TextButton(onClick = alDescartar) { Text("Entendido", color = AMBAR) }
        }
    }
}

/**
 * El diario de una pasada.
 *
 * El color sale del vocabulario que ya imprime el motor —`=== pareja ===`,
 * `[pareja] OK.`, `AVISO:`, `>>`—, que es exactamente lo que hace `tk._tono`
 * en prdrive. Si el motor cambia una palabra, una línea se queda sin color y
 * no pasa nada más.
 */
@Composable
fun Salida(lineas: List<String>, progreso: String?, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        for (linea in lineas) {
            Text(
                linea,
                color = tono(linea),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        // La línea de progreso va aparte y se REESCRIBE, no se apila: con una
        // lectura cada medio segundo, apilarlas llenaría la pantalla.
        if (progreso != null) {
            Text(progreso, color = AMBAR, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun tono(linea: String): Color = when {
    linea.startsWith("===") -> MaterialTheme.colorScheme.primary
    "FALLÓ" in linea || linea.startsWith("  >>") -> ROJO
    "AVISO" in linea || "requiere --resync" in linea -> AMBAR
    linea.endsWith("OK.") -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
fun Etiqueta(texto: String, valor: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(texto, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Text(valor, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}
