package prdrive.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import prdrive.app.Estado
import prdrive.app.Prdrive
import prdrive.app.rclone.Rclone
import prdrive.engine.MODES
import prdrive.engine.Pasada

/**
 * Pantallas.kt — Las dos pantallas del paso 1 (PLAN.md): el primer arranque y
 * la principal. Solo dibujan y llaman al [Modelo]; lo que deciden lo deciden el
 * motor y el instalador.
 *
 * Los `testTag` son los asideros de la prueba de punta a punta
 * (`PuntaAPuntaTest`), que maneja estas mismas pantallas.
 */
@Composable
fun PrdriveApp(modelo: Modelo) {
    MaterialTheme {
        if (modelo.sembrado) Principal(modelo) else PrimerArranque(modelo)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Marco(titulo: String, contenido: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(titulo) }) }) { margen ->
        Column(
            modifier = Modifier.padding(margen).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { contenido() }
    }
}

@Composable
private fun Ocupado(que: String?) {
    if (que == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(que, modifier = Modifier.testTag("ocupado"))
    }
}

@Composable
private fun Error(texto: String?) {
    if (texto == null) return
    Text(texto, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error"))
}

@Composable
private fun LineaDeProgreso(p: Modelo.Progreso?) {
    if (p == null) return
    val contexto = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val fraccion = p.fraccion
        if (fraccion != null) {
            LinearProgressIndicator(progress = { fraccion }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        val tamano = { n: Long -> Formatter.formatShortFileSize(contexto, n) }
        Text(
            "${p.pareja}: ${p.ficheros} de ${p.totalFicheros} ficheros · " +
                "${tamano(p.bytes)} de ${tamano(p.totalBytes)} · ${tamano(p.velocidad.toLong())}/s",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Lineas(lineas: List<String>, modifier: Modifier = Modifier) {
    if (lineas.isEmpty()) return
    SelectionContainer(modifier = modifier) {
        Text(
            lineas.joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Una acción que puede necesitar la red local: antes de hacerla se pide el
 * permiso, si falta ([Prdrive.faltaRedLocal]). Se haga con permiso o sin él:
 * un remoto en internet no lo necesita, y si era uno de casa, el error que
 * salga ya lo explica. [alResponder] se llama cuando la persona contesta.
 */
@Composable
private fun conRedLocal(alResponder: () -> Unit = {}): (() -> Unit) -> Unit {
    val contexto = LocalContext.current
    var pendiente by remember { mutableStateOf<(() -> Unit)?>(null) }
    val lanzador = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        alResponder()
        val accion = pendiente
        pendiente = null
        accion?.invoke()
    }
    return { accion ->
        when {
            // Con la pregunta en pantalla, otro toque no la repite: Android
            // contestaría a la segunda en seguida, como cancelada, y la acción
            // iría sin permiso.
            pendiente != null -> Unit
            Prdrive.faltaRedLocal(contexto) -> {
                pendiente = accion
                lanzador.launch(Prdrive.PERMISO_RED_LOCAL)
            }
            else -> accion()
        }
    }
}

/** Lo que se ve mientras falta el permiso de red local, con cómo darlo. */
@Composable
private fun AvisoRedLocal(pedir: () -> Unit) {
    val contexto = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Prdrive.SIN_RED_LOCAL, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = pedir) { Text("Dar permiso") }
                TextButton(onClick = {
                    // Si ya se negó dos veces, Android no vuelve a preguntar: queda los ajustes.
                    contexto.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", contexto.packageName, null)),
                    )
                }) { Text("Abrir ajustes") }
            }
        }
    }
}

/**
 * Hace cuánto, en español: la app no tiene otro idioma, y el
 * `DateUtils` de Android lo diría en el del teléfono, en mitad de una frase.
 */
private fun haceCuanto(ms: Long, ahora: Long = System.currentTimeMillis()): String {
    val minutos = (ahora - ms) / 60_000
    return when {
        minutos < 1 -> "hace un momento"
        minutos < 60 -> "hace $minutos min"
        minutos < 24 * 60 -> "hace ${minutos / 60} h"
        minutos < 48 * 60 -> "ayer"
        else -> "el " + DateTimeFormatter.ofPattern("d/M/yyyy").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    }
}

/** Hacia dónde va lo que se copia en ese modo: los dos sentidos, de bajada o de subida. */
private fun flecha(modo: String): String {
    val m = MODES[modo] ?: return "↔"
    return when {
        m.isBisync -> "↔"
        m.origenLocal -> "→"
        else -> "←"
    }
}

// --- primer arranque -----------------------------------------------------------

@Composable
private fun PrimerArranque(m: Modelo) {
    Marco("Emparejar este teléfono") {
        when (m.paso) {
            Modelo.Paso.CODIGO -> PasoCodigo(m)
            Modelo.Paso.PAREJAS -> PasoParejas(m)
            Modelo.Paso.INICIANDO -> PasoIniciando(m)
        }
    }
}

@Composable
private fun PasoCodigo(m: Modelo) {
    val contexto = LocalContext.current
    // `remember` y no `rememberSaveable`: el texto lleva la clave privada, y el
    // estado guardado de la actividad puede acabar en disco fuera del volumen.
    var texto by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "En el ordenador, prdrive enseña un código de emparejamiento con la conexión al " +
                "remoto. Escanéalo, o pega aquí su texto.",
        )
        Button(
            onClick = { Escaner.escanear(contexto, m::leerCodigo, m::avisar) },
            enabled = m.ocupado == null,
            modifier = Modifier.testTag("escanear"),
        ) { Text("Escanear el código") }
        // Pequeño a propósito: lo pegado lleva la clave, y no tiene por qué
        // quedarse a la vista. Una vez leído, se vacía.
        // Como una contraseña: sin autocorrección ni aprendizaje del teclado
        // (que se quedaría con la clave) y sin enseñar lo pegado.
        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it },
            label = { Text("Texto del código") },
            maxLines = 3,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag("carga"),
        )
        OutlinedButton(
            onClick = {
                m.leerCodigo(texto)
                // Se lea o no, fuera de la pantalla: lleva la clave.
                texto = ""
            },
            enabled = texto.isNotBlank() && m.ocupado == null,
            modifier = Modifier.testTag("usarTexto"),
        ) { Text("Usar este texto") }
        val c = m.carga
        if (c != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Conexión: ${c.describe()}", fontWeight = FontWeight.SemiBold)
                    Text("Catálogo: ${c.catalogPath}", style = MaterialTheme.typography.bodySmall)
                    if (c.necesitaClave) {
                        Text(
                            "Lleva la clave del remoto: se guarda solo dentro de la app, en una carpeta " +
                                "que no se sincroniza ni se ve desde Archivos.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            val conRed = conRedLocal()
            Button(
                onClick = { conRed(m::conectar) },
                enabled = m.ocupado == null,
                modifier = Modifier.testTag("conectar"),
            ) { Text("Conectar y leer el catálogo") }
        }
        Ocupado(m.ocupado)
        Error(m.error)
    }
}

@Composable
private fun ColumnScope.PasoParejas(m: Modelo) {
    val cat = m.catalogo ?: return
    val parejas = prdrive.engine.Catalogo.pairsByName(cat)
    Text("Elige qué sincroniza este teléfono. Cada pareja es una carpeta suya y una del remoto.")
    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
        items(cat.names) { nombre ->
            val p = parejas[nombre].orEmpty()
            val modo = (p["mode"] ?: "bisync").toString()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = nombre in m.elegidas,
                    onCheckedChange = { m.elegir(nombre, it) },
                    modifier = Modifier.testTag("elegir-$nombre"),
                )
                Column {
                    Text(nombre, fontWeight = FontWeight.SemiBold)
                    Text("$modo · ${p["local"] ?: ""} ${flecha(modo)} ${p["remote_path"] ?: ""}", style = MaterialTheme.typography.bodySmall)
                    if (modo.endsWith("-mirror")) {
                        Text(
                            "Espejo: un lado manda y el otro se iguala a él, borrados incluidos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
    Button(
        onClick = m::instalar,
        enabled = m.elegidas.isNotEmpty() && m.ocupado == null,
        modifier = Modifier.testTag("instalar"),
    ) { Text("Instalar ${m.elegidas.size} pareja(s)") }
    Ocupado(m.ocupado)
    Error(m.error)
}

@Composable
private fun ColumnScope.PasoIniciando(m: Modelo) {
    Text(
        "Primera pasada de cada pareja bisync (--resync): junta lo que hay en los dos lados " +
            "sin borrar nada.",
    )
    m.instalado?.notas?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    val espejos = m.instalado?.espejos.orEmpty()
    if (espejos.isNotEmpty()) {
        // El aviso del instalador (`_paso_inicializar`), con lo que aquí se hace
        // en vez de `--dry-run`, que el teléfono no tiene.
        Text(
            "No se inicializan aquí: ${espejos.joinToString(", ")}. Son espejos: borran en el " +
                "otro lado lo que no esté en el origen, y lanzarlos con las carpetas locales " +
                "recién creadas propagaría ese vacío. Por eso la carpeta de un espejo de subida " +
                "no se crea: hasta que la crees tú con lo que quieres subir, sus pasadas se paran " +
                "en vez de vaciar el remoto.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.testTag("aviso-espejos"),
        )
    }
    LineaDeProgreso(m.progreso)
    Ocupado(m.ocupado)
    Error(m.error)
    for ((nombre, r) in m.resultados) Resumen(nombre, r)
    // Solo cuando no queda nada en marcha: con el modelo ocupado, el toque se perdería.
    if (m.inicioTerminado && m.ocupado == null) {
        Button(onClick = m::empezar, modifier = Modifier.testTag("empezar")) { Text("Ir a la pantalla principal") }
    }
    HorizontalDivider()
    Lineas(m.lineas, Modifier.weight(1f).verticalScroll(rememberScrollState()))
}

@Composable
private fun Resumen(nombre: String, r: Pasada.Resultado) {
    val (texto, color) = when {
        r.ok -> "$nombre: OK" to MaterialTheme.colorScheme.primary
        r.saltada -> "$nombre: saltada" to MaterialTheme.colorScheme.tertiary
        else -> "$nombre: FALLÓ" to MaterialTheme.colorScheme.error
    }
    Column(modifier = Modifier.testTag("resultado-$nombre")) {
        Text(texto, color = color, fontWeight = FontWeight.SemiBold)
        r.explicacion?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

// --- principal -------------------------------------------------------------------

@Composable
private fun Principal(m: Modelo) {
    val contexto = LocalContext.current
    var log by remember { mutableStateOf<Estado.Fila?>(null) }
    var faltaRed by remember { mutableStateOf(Prdrive.faltaRedLocal(contexto)) }
    // Al volver de los ajustes de la app, el permiso puede haber cambiado.
    LifecycleResumeEffect(Unit) {
        faltaRed = Prdrive.faltaRedLocal(contexto)
        onPauseOrDispose {}
    }
    val conRed = conRedLocal { faltaRed = Prdrive.faltaRedLocal(contexto) }
    Marco("prdrive") {
        Button(
            onClick = { conRed(m::sincronizar) },
            enabled = m.ocupado == null,
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("sincronizar"),
        ) { Text("Sincronizar", style = MaterialTheme.typography.titleMedium) }
        if (faltaRed) AvisoRedLocal { conRed {} }
        LineaDeProgreso(m.progreso)
        Ocupado(m.ocupado)
        Error(m.error)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Sin clave por nombre: dos parejas que se llamen igual no pueden
            // tumbar la pantalla (Compose no admite claves repetidas).
            items(m.filas) { f ->
                FilaDePareja(f, m.resultados[f.nombre], m.ocupado == null, { log = f }, { conRed { m.aprobarResync(f.nombre) } })
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text("rclone ${Rclone.version()}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    log?.let { f -> DialogoLog(f) { log = null } }
}

@Composable
private fun FilaDePareja(
    f: Estado.Fila,
    ultimo: Pasada.Resultado?,
    libre: Boolean,
    verLog: () -> Unit,
    aprobar: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("fila-${f.nombre}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(f.nombre, style = MaterialTheme.typography.titleMedium)
            Text("${f.modo} · ${f.local}", style = MaterialTheme.typography.bodySmall)
            val cuando = f.ultimaBuena?.let(::haceCuanto)
            Text(if (cuando != null) "Al día: $cuando" else "Sin ninguna pasada buena todavía")
            val fallo = f.fallo
            if (fallo != null) {
                Text("Falló el ${fallo.cuando}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                f.explicacion?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (fallo.log != null) TextButton(onClick = verLog) { Text("Ver el log") }
            }
            if (f.pideResync.isNotEmpty() && ultimo?.ok != true) {
                Text(
                    "Pide --resync: ${f.pideResync.joinToString("; ")}",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = aprobar, enabled = libre, modifier = Modifier.testTag("resync-${f.nombre}")) {
                    Text("Rehacer la referencia (--resync)")
                }
            }
            if (f.conflictos.isNotEmpty()) {
                Text(
                    "${f.conflictos.size} fichero(s) en conflicto: cambiaron en los dos lados y hay dos versiones.",
                    color = MaterialTheme.colorScheme.error,
                )
                f.conflictos.take(Pasada.CONFLICTS_SHOWN).forEach {
                    Text("· ${it.relativa}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DialogoLog(f: Estado.Fila, cerrar: () -> Unit) {
    // Leído fuera del hilo de la pantalla: un log puede ser grande.
    val texto by produceState(emptyList<String>(), f) {
        value = withContext(Dispatchers.IO) { f.fallo?.log?.let(Estado::leer)?.let { Pasada.cola(it, 60) } ?: emptyList() }
    }
    AlertDialog(
        onDismissRequest = cerrar,
        confirmButton = { TextButton(onClick = cerrar) { Text("Cerrar") } },
        title = { Text("Log de ${f.nombre}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                f.explicacion?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                Lineas(texto)
            }
        },
    )
}
