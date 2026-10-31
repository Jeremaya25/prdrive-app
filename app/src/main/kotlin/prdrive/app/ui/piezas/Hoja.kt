package prdrive.app.ui.piezas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import prdrive.app.ui.Letra
import prdrive.app.ui.Paleta

/**
 * Hoja.kt — La hoja inferior: confirmar algo sin cambiar de pantalla («Rehacer
 * la referencia»). Es el `ModalBottomSheet` de Material con los colores del
 * sistema: `surface`, el velo de `mark-ink` a 0,55 y 12 dp de esquina arriba. Es
 * lo único que flota, y lo único con sombra.
 *
 * Se abre entera (`skipPartiallyExpanded`): a medias, el botón de confirmar
 * quedaría debajo del borde. Se cierra con su botón (etiqueta `cerrar-hoja`),
 * arrastrándola, tocando el velo o con el botón atrás del sistema; en todos los
 * casos llama a [alCerrar].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Hoja(titulo: String, alCerrar: () -> Unit, contenido: @Composable ColumnScope.() -> Unit) {
    val c = Paleta.actual
    val estado = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val alcance = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = alCerrar,
        sheetState = estado,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        containerColor = c.surface,
        contentColor = c.ink,
        scrimColor = c.markInk.copy(alpha = 0.55f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                titulo,
                style = Letra.actual.barra,
                color = c.ink,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            BotonDeIcono(
                Icono.CLOSE,
                "Cerrar",
                alPulsar = { alcance.launch { estado.hide() }.invokeOnCompletion { alCerrar() } },
                modifier = Modifier.testTag("cerrar-hoja"),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = contenido,
        )
    }
}
