package prdrive.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import prdrive.app.Actualizador
import prdrive.app.Estado
import prdrive.app.Prdrive
import prdrive.app.compartidas.Compartir
import prdrive.app.rclone.Rclone
import prdrive.engine.MODES
import prdrive.engine.Pasada

/**
 * Pantallas.kt — Las dos pantallas: el primer arranque y
 * la principal. Solo dibujan y llaman al [Modelo]; lo que deciden lo deciden el
 * motor y el instalador. Lo del llavero está en `PantallaLlavero.kt`.
 *
 * Los `testTag` son los asideros de la prueba de punta a punta
 * (`PuntaAPuntaTest`), que maneja estas mismas pantallas.
 */
@Composable
fun PrdriveApp(modelo: Modelo) {
    Tema {
        if (modelo.sembrado) Secciones(modelo) else PrimerArranque(modelo)
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
 * Hace cuánto, en español: la app no tiene otro idioma, y el
 * `DateUtils` de Android lo diría en el del teléfono, en mitad de una frase.
 */
internal fun haceCuanto(ms: Long, ahora: Long = System.currentTimeMillis()): String {
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
internal fun flecha(modo: String): String {
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
    // Lo que el catálogo cambia de la conexión del código: antes de instalar,
    // que es cuando todavía se puede no seguir.
    for (nota in m.notasDelCatalogo) {
        Text(
            nota,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.testTag("nota-catalogo"),
        )
    }
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
    CasillaDelLlavero(m)
    // Con el llavero, las notificaciones se piden antes: avisan de lo suyo.
    val conPermiso = conAvisos()
    Button(
        onClick = { if (m.conLlavero) conPermiso(m::instalar) else m.instalar() },
        enabled = m.elegidas.isNotEmpty() && m.ocupado == null,
        modifier = Modifier.testTag("instalar"),
    ) { Text("Instalar ${m.elegidas.size} pareja(s)" + if (m.conLlavero) " y el llavero" else "") }
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

/**
 * Hasta que esté la ficha de una pareja: su tarjeta de antes, con lo que se
 * puede hacer con ella (el log, rehacer la referencia, moverla, terminar de
 * moverla y devolverla al volumen).
 */
@Composable
internal fun FichaProvisional(m: Modelo, nombre: String) {
    val contexto = LocalContext.current
    var log by remember { mutableStateOf<Estado.Fila?>(null) }
    val conRed = conRedLocal()
    // La pareja que se va a mover (con el diálogo abierto o el selector de
    // carpetas delante), y si ya está el permiso de acceso a todos los archivos,
    // que se da en los ajustes y se mira al volver. Guardado: es solo un nombre.
    var moviendo by rememberSaveable { mutableStateOf<String?>(null) }
    var permisoCompartido by remember { mutableStateOf(Prdrive.permisoCompartido()) }
    LifecycleResumeEffect(Unit) {
        permisoCompartido = Prdrive.permisoCompartido()
        onPauseOrDispose {}
    }
    val elegirCarpeta = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { arbol ->
        val pareja = moviendo
        moviendo = null
        if (arbol != null && pareja != null) m.moverACompartida(pareja, arbol)
    }
    val f = m.filas.firstOrNull { it.nombre == nombre }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        if (f != null) {
            FilaDePareja(
                f, m.resultados[f.nombre], m.ocupado == null,
                verLog = { log = f },
                aprobar = { conRed { m.aprobarResync(f.nombre) } },
                mover = { moviendo = f.nombre },
                terminar = { conRed { m.terminarDeMover(f.nombre) } },
                volver = { conRed { m.volverAlVolumen(f.nombre) } },
                darPermiso = { darPermisoCompartido(contexto, m) },
            )
        }
    }
    log?.let { fila -> DialogoLog(fila) { log = null } }
    moviendo?.let { pareja ->
        DialogoMover(
            nombre = pareja,
            conPermiso = permisoCompartido,
            darPermiso = { darPermisoCompartido(contexto, m) },
            // Como «Sincronizar»: la mudanza pasa bisync contra el remoto, y sin el
            // permiso de red local (API 37) una dirección privada se cuelga. Se
            // pide antes del selector, para que su resultado ya pueda mover.
            elegir = {
                conRed {
                    elegirCarpeta.launch(DocumentsContract.buildDocumentUri(Compartir.AUTORIDAD, "primary:Documents"))
                }
            },
            cerrar = { moviendo = null },
        )
    }
}

/** Las versiones, y buscar la nueva sin esperar al día siguiente. */
@Composable
private fun Pie() {
    val contexto = LocalContext.current
    val version = remember { Actualizador.versionInstalada(contexto) }
    val ocupado = Actualizador.fase in setOf(
        Actualizador.Fase.BUSCANDO, Actualizador.Fase.DESCARGANDO, Actualizador.Fase.INSTALANDO,
    )
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "prdrive $version · rclone ${Rclone.version()}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { Actualizador.buscarAhora(contexto) },
                enabled = !ocupado,
                modifier = Modifier.testTag("buscar-version"),
            ) { Text("Buscar actualizaciones") }
        }
        // Con una versión nueva, el aviso va en su tarjeta.
        if (Actualizador.nueva == null) {
            Actualizador.aviso?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("aviso-version"))
            }
        }
    }
}

@Composable
private fun FilaDePareja(
    f: Estado.Fila,
    ultimo: Pasada.Resultado?,
    libre: Boolean,
    verLog: () -> Unit,
    aprobar: () -> Unit,
    mover: () -> Unit,
    terminar: () -> Unit,
    volver: () -> Unit,
    darPermiso: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("fila-${f.nombre}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(f.nombre, style = MaterialTheme.typography.titleMedium)
            Text("${f.modo} · ${f.local}", style = MaterialTheme.typography.bodySmall)
            f.compartida?.let {
                Text(
                    "En ${rutaCorta(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("donde-${f.nombre}"),
                )
            }
            val cuando = f.ultimaBuena?.let(::haceCuanto)
            Text(if (cuando != null) "Al día: $cuando" else "Sin ninguna pasada buena todavía")
            val fallo = f.fallo
            if (fallo != null) {
                Text("Falló el ${fallo.cuando}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                f.explicacion?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (fallo.log != null) TextButton(onClick = verLog) { Text("Ver el log") }
            }
            f.sinAcceso?.let { motivo ->
                Text(motivo, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                // Solo el permiso se da con un botón: una tarjeta sin montar, no.
                if (motivo == Prdrive.SIN_PERMISO_COMPARTIDO && Build.VERSION.SDK_INT >= 30) {
                    OutlinedButton(onClick = darPermiso, modifier = Modifier.testTag("permiso-${f.nombre}")) {
                        Text("Dar permiso")
                    }
                }
            }
            f.problemaCompartida?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            // A medio mover solo se ofrece terminar: pasarla con una pasada normal o
            // con un --resync cualquiera la cogería con la carpeta a medio llenar.
            if (f.entrando) {
                Text(
                    "A medio mover: no se sincroniza con las demás hasta que la termines.",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = terminar, enabled = libre, modifier = Modifier.testTag("terminar-${f.nombre}")) {
                    Text("Terminar de mover")
                }
            }
            if (f.compartida != null && (!f.entrando || f.problemaCompartida != null)) {
                OutlinedButton(onClick = volver, enabled = libre, modifier = Modifier.testTag("volver-${f.nombre}")) {
                    Text("Volver al volumen")
                }
                Text(
                    "La carpeta compartida se queda como está: bórrala tú cuando ya no la quieras.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (f.sePuedeCompartir) {
                OutlinedButton(onClick = mover, enabled = libre, modifier = Modifier.testTag("mover-${f.nombre}")) {
                    Text("Mover a una carpeta compartida…")
                }
            }
            if (f.pideResync.isNotEmpty() && ultimo?.ok != true && !f.entrando) {
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

/** La ruta de una carpeta compartida como la ve la persona: desde la raíz del almacenamiento (`Documents/vault`). */
internal fun rutaCorta(ruta: String): String =
    File(ruta).relativeToOrNull(Environment.getExternalStorageDirectory())?.path?.takeIf { it.isNotEmpty() } ?: ruta

/**
 * Antes de mover una pareja: qué cambia (la carpeta deja de ser privada) y, si
 * falta, el permiso; con él, elegir la carpeta. [elegir] abre el selector de
 * carpetas del sistema.
 */
@Composable
private fun DialogoMover(
    nombre: String,
    conPermiso: Boolean,
    darPermiso: () -> Unit,
    elegir: () -> Unit,
    cerrar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = cerrar,
        confirmButton = {
            if (conPermiso) {
                TextButton(onClick = elegir, modifier = Modifier.testTag("elegir-carpeta")) { Text("Elegir la carpeta") }
            } else {
                TextButton(onClick = darPermiso, modifier = Modifier.testTag("dar-permiso")) { Text("Dar permiso") }
            }
        },
        dismissButton = { TextButton(onClick = cerrar) { Text("Cancelar") } },
        title = { Text("Mover $nombre a una carpeta compartida") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Compartir.AVISO_EXPOSICION)
                Text(
                    "Elige una carpeta vacía, o créala nueva: lo de la pareja se lleva allí y la copia del " +
                        "volumen se borra al acabar.",
                )
                if (!conPermiso) Text(Prdrive.SIN_PERMISO_COMPARTIDO, color = MaterialTheme.colorScheme.error)
            }
        },
    )
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
