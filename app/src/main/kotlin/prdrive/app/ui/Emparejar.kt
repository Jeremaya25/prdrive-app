package prdrive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import prdrive.app.Modelo

/**
 * Emparejar.kt — El primer arranque: la conexión y las parejas.
 *
 * El orden es de carga: no se puede leer el catálogo antes de saber cuál es el
 * remoto, ni elegir parejas antes de leer el catálogo. Es el mismo orden que
 * los ocho pasos del instalador del PC, recortado a lo que un móvil necesita.
 *
 * Y la última pasada no se lanza sola: el primer `--resync` decide qué se
 * copia a dónde, así que lo pide el usuario desde la pantalla principal.
 */
@Composable
fun Emparejar(
    estado: Modelo.Estado,
    alEscanear: () -> Unit,
    alPegar: (String) -> Unit,
    alLeerCatalogo: () -> Unit,
    alElegir: (List<String>) -> Unit,
    alDescartarAviso: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("prdrive", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Sincroniza una carpeta de este móvil con tu remoto, con el mismo " +
                "catálogo de parejas que tus dispositivos de escritorio.",
            style = MaterialTheme.typography.bodyMedium,
        )

        estado.aviso?.let { Aviso(it, alDescartarAviso) }

        if (!estado.emparejado) {
            PasoConexion(alEscanear, alPegar)
            return@Column
        }

        val carga = estado.carga!!
        Text("1. Conexión", style = MaterialTheme.typography.titleMedium)
        Etiqueta("Remoto:", carga.describe())

        Text("2. Parejas del catálogo", style = MaterialTheme.typography.titleMedium)
        val cat = estado.catalogo
        if (cat == null) {
            Text(
                "El catálogo vive en el remoto y dice qué parejas existen. Aquí solo " +
                    "se elige cuáles usa este móvil: crearlas y borrarlas se hace desde " +
                    "el PC.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = alLeerCatalogo) { Text("Leer el catálogo") }
            return@Column
        }

        if (!cat.editable) {
            Aviso("Esto es la copia local del ${cat.sello}: no hay conexión con el remoto.")
        }
        ElegirParejas(cat.names, alElegir)
    }
}

@Composable
private fun PasoConexion(alEscanear: () -> Unit, alPegar: (String) -> Unit) {
    var texto by remember { mutableStateOf("") }
    Text("1. Conexión", style = MaterialTheme.typography.titleMedium)
    Text(
        "En un dispositivo de prdrive ya instalado: Doctor → «Emparejar un móvil…». " +
            "Ese código lleva la clave privada del remoto dentro, así que enséñalo solo " +
            "donde nadie más pueda fotografiarlo.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = alEscanear) { Text("Escanear el código") }
    Text(
        "El escáner lo ejecuta Google Play Services en su propio proceso, así que esta " +
            "app no pide permiso de cámara. Si no está disponible, pega el texto:",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = texto,
        onValueChange = { texto = it },
        label = { Text("Texto del código") },
        modifier = Modifier.fillMaxWidth().height(160.dp),
    )
    TextButton(
        onClick = { alPegar(texto) },
        enabled = texto.isNotBlank(),
    ) { Text("Usar este texto") }
}

@Composable
private fun ElegirParejas(nombres: List<String>, alElegir: (List<String>) -> Unit) {
    // Todas marcadas al empezar, que es lo que hace el instalador del PC: lo
    // normal es querer todo el catálogo, y quitar es más fácil que poner.
    val marcadas = remember(nombres) { mutableStateOf(nombres.toSet()) }
    if (nombres.isEmpty()) {
        Aviso("El catálogo no tiene ninguna pareja. Créalas desde un PC.")
        return
    }
    for (nombre in nombres) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = nombre in marcadas.value,
                onCheckedChange = { puesta ->
                    marcadas.value = if (puesta) {
                        marcadas.value + nombre
                    } else {
                        marcadas.value - nombre
                    }
                },
            )
            Text(nombre)
        }
    }
    Spacer(Modifier.height(4.dp))
    Button(
        onClick = { alElegir(nombres.filter { it in marcadas.value }) },
        enabled = marcadas.value.isNotEmpty(),
    ) { Text("Crear las carpetas y guardar") }
}

@Composable
fun Cargando() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { CircularProgressIndicator() }
}
