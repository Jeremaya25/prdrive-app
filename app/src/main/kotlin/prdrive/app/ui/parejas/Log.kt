package prdrive.app.ui.parejas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import prdrive.app.Estado
import prdrive.app.ui.Cabecera
import prdrive.app.ui.Letra
import prdrive.app.ui.Modelo
import prdrive.app.ui.piezas.Aviso
import prdrive.app.ui.piezas.Registro
import prdrive.app.ui.piezas.TonoAviso
import prdrive.engine.Pasada

/** Cuántas líneas del final del log se enseñan: lo que explica un fallo está al final. */
private const val LINEAS_DEL_LOG = 200

/**
 * Log.kt — El log de la última pasada que falló de una pareja: lo que se sabe
 * de por qué (la explicación de `KNOWN_ERRORS`) y sus últimas líneas, en
 * monoespaciada y coloreadas por lo que dicen. Se lee fuera del hilo de la
 * pantalla: un log puede ser grande.
 */
@Composable
fun LogDePareja(m: Modelo, nombre: String, volver: () -> Unit) {
    val f = m.filas.firstOrNull { it.nombre == nombre }
    val log = f?.fallo?.log
    val lineas by produceState<List<String>?>(null, log) {
        value = withContext(Dispatchers.IO) { log?.let(Estado::leer)?.let { Pasada.cola(it, LINEAS_DEL_LOG) } ?: emptyList() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .testTag("pantalla-log-$nombre")
            .verticalScroll(rememberScrollState()),
    ) {
        Cabecera(
            "Log de la última pasada",
            volver = volver,
            etiquetaVolver = nombre,
            pie = f?.fallo?.let { "Falló el ${it.cuando}" },
        )
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            f?.explicacion?.let { Aviso(it, tono = TonoAviso.PELIGRO) }
            val texto = lineas
            when {
                texto == null -> Unit
                texto.isEmpty() -> Text("El log ya no está en el teléfono.", style = Letra.actual.texto)
                else -> Registro(texto)
            }
        }
    }
}
