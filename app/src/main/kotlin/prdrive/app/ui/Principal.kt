package prdrive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import prdrive.engine.Config
import prdrive.engine.Fallo
import prdrive.engine.Pareja

/**
 * Principal.kt — Un «Sincronizar» grande, una fila por pareja, y el log cuando
 * algo falla.
 *
 * Deliberadamente escueta, como la del escritorio: lo que se hace una vez en la
 * vida de un dispositivo no va aquí. Lo único que esta pantalla decide es
 * **preguntar antes de un `--resync`**, y eso no es una cortesía: rehacer un
 * baseline es la operación que decide qué se borra, así que se pregunta una vez
 * para todas las parejas y no a mitad de faena.
 */
@Composable
fun Principal(
    config: Config,
    estado: prdrive.app.Modelo.Estado,
    lineaDeProgreso: String?,
    alSincronizar: (List<Pareja>?, Boolean, Boolean) -> Unit,
    alVerLog: (Fallo) -> Unit,
    alDescartarAviso: () -> Unit,
) {
    var preguntando by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("prdrive", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${config.pairs.size} pareja(s)",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        estado.aviso?.let { Aviso(it, alDescartarAviso) }

        if (estado.fallos.isNotEmpty()) {
            Aviso(
                "La última pasada falló en: ${estado.fallos.joinToString(", ") { it.pareja }}. " +
                    "Toca la pareja para ver su log.",
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (estado.pidenResync.isNotEmpty()) {
                        preguntando = true
                    } else {
                        alSincronizar(null, false, false)
                    }
                },
                enabled = !estado.ocupado,
            ) { Text("Sincronizar") }
            TextButton(
                onClick = { alSincronizar(null, true, false) },
                enabled = !estado.ocupado,
            ) { Text("Simular") }
        }

        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(config.pairs, key = { it.name }) { pareja ->
                FilaDePareja(
                    pareja = pareja,
                    corriendo = estado.corriendo == pareja.name,
                    fallo = estado.fallos.firstOrNull { it.pareja == pareja.name },
                    conflictos = estado.conflictos[pareja.name] ?: 0,
                    pideResync = pareja.name in estado.pidenResync,
                    alVerLog = alVerLog,
                )
            }
        }

        if (estado.salida.isNotEmpty()) {
            Salida(
                estado.salida,
                lineaDeProgreso,
                Modifier.fillMaxWidth().height(220.dp),
            )
        }
    }

    if (preguntando) {
        PreguntarResync(
            estado.pidenResync,
            alCancelar = { preguntando = false },
            alAprobar = {
                preguntando = false
                alSincronizar(null, false, true)
            },
        )
    }
}

@Composable
private fun FilaDePareja(
    pareja: Pareja,
    corriendo: Boolean,
    fallo: Fallo?,
    conflictos: Int,
    pideResync: Boolean,
    alVerLog: (Fallo) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(pareja.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "  ${pareja.mode.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "${pareja.local}  ⇄  ${pareja.remoteEndpoint}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            when {
                corriendo -> Text("sincronizando…", color = MaterialTheme.colorScheme.primary)
                fallo != null -> TextButton(onClick = { alVerLog(fallo) }) {
                    Text("Falló el ${fallo.cuando} — ver el log", color = ROJO)
                }
                pideResync -> Text("necesita --resync", color = AMBAR)
            }
            if (conflictos > 0) {
                Text("$conflictos fichero(s) en conflicto", color = AMBAR)
            }
        }
    }
}

/**
 * La pregunta del `--resync`, una sola vez y con los motivos delante.
 *
 * No es un «¿seguro?»: cada línea dice por qué esa pareja lo necesita, que es
 * lo que permite darse cuenta de que algo va mal —una ruta que cambió, unos
 * filtros que se movieron— antes de rehacer un baseline.
 */
@Composable
private fun PreguntarResync(
    razones: Map<String, List<String>>,
    alCancelar: () -> Unit,
    alAprobar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = alCancelar,
        title = { Text("Hay parejas que piden --resync") },
        text = {
            Column {
                Text(
                    "Rehacer el baseline compara los dos lados desde cero y decide qué " +
                        "se copia. Comprueba que la carpeta del móvil NO esté vacía antes " +
                        "de aprobarlo.",
                    style = MaterialTheme.typography.bodySmall,
                )
                for ((pareja, motivos) in razones) {
                    Text(pareja, style = MaterialTheme.typography.titleSmall)
                    for (motivo in motivos) {
                        Text("  · $motivo", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = alAprobar) { Text("Rehacer y sincronizar") } },
        dismissButton = { TextButton(onClick = alCancelar) { Text("Cancelar") } },
    )
}
