package prdrive.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import prdrive.app.Estado
import prdrive.app.llavero.Avisos
import prdrive.engine.LLAVERO_LLAVE
import prdrive.engine.LLAVERO_LOCAL
import prdrive.engine.Store
import prdrive.engine.Volumen
import prdrive.engine.parseConfig

/**
 * PantallaLlavero.kt — El llavero en las pantallas: su fila en la principal
 * («Abrir llavero», «Traer el llavero», quitarlo), el aviso de antes de abrir
 * y la casilla «El llavero» al emparejar. Como el resto de `ui/`, solo dibuja y
 * llama al [Modelo].
 *
 * La fila es la línea de `llavero_editor.linea()` de prdrive; lo que no hay en
 * el teléfono (KeePassXC, «Combinar», crear un llavero) no sale.
 */
internal object TextosLlavero {

    const val TRAYENDO = "Trayendo lo último del llavero…"

    const val SIN_BASE =
        "El llavero todavía no tiene base: ni en el teléfono ni en el remoto. Se crea en el " +
            "ordenador, en «Ajustes → Llavero»."

    /** La base no está y la pasada que la iba a traer ha fallado (`sin_traer_base()`). */
    fun sinTraerBase(codigo: Int): String =
        "La base del llavero no está en el teléfono y no se ha podido traer del remoto (código " +
            "$codigo). Vuelve a intentarlo con conexión."

    /** La pasada de antes de abrir ha fallado, y se abre igual (`sin_traer()`). */
    fun sinTraer(codigo: Int): String =
        "No se ha podido traer lo último del remoto (código $codigo): se ha abierto la base del " +
            "teléfono. Lo que guardes subirá cuando haya conexión."

    const val FALTA_LLAVE =
        "Este llavero va sin contraseña y falta su fichero llave en el teléfono " +
            "($LLAVERO_LOCAL/$LLAVERO_LLAVE): tu app de KeePass te lo pedirá para abrir la base. " +
            "Para volver a tenerlo aquí, quita el llavero y tráelo otra vez con su fichero llave."

    const val SIN_APP =
        "No hay ninguna app en el teléfono que abra una base de KeePass. Instala una, como " +
            "KeePassDX o Keepass2Android, y vuelve a pulsar «Abrir llavero»."

    const val SIN_LLAVERO_REMOTO =
        "El catálogo del remoto no tiene llavero: no hay nada que traer. Se activa en el " +
            "ordenador, en «Ajustes → Llavero»."

    /** Lo que se dice de él, para la casilla y la confirmación de traerlo. */
    const val QUE_ES =
        "Una base de KeePass (contraseñas y passkeys) que se sincroniza sola con el remoto. Se " +
            "abre con «Abrir llavero», en tu app de KeePass."

    /**
     * Los tipos con que se pide abrir la base, en orden. No hay uno oficial para
     * `.kdbx`: `application/x-kdbx` lo declaran las apps de KeePass (KeePassDX
     * 4.5.6, visto en el emulador), así que el selector sale solo con ellas; el
     * genérico queda de reserva para una que solo declare ese, y con él Android
     * mezcla en el selector cualquier app que abra ficheros (Google, Wallet…).
     */
    val TIPOS = listOf("application/x-kdbx", "application/octet-stream")
}

/** El ámbar de la fila: el mismo en claro y en oscuro, con su texto. */
private val AMBAR = Color(0xFFFFE082)
private val SOBRE_AMBAR = Color(0xFF3B2F00)

/**
 * Una acción que activa el llavero: antes se piden las notificaciones (desde
 * Android 13), que es lo que avisa de una copia de conflicto o de un guardado
 * cortado. Se haga con permiso o sin él: sin él, queda la fila.
 */
@Composable
internal fun conAvisos(): (() -> Unit) -> Unit {
    val contexto = LocalContext.current
    var pendiente by remember { mutableStateOf<(() -> Unit)?>(null) }
    val lanzador = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val accion = pendiente
        pendiente = null
        accion?.invoke()
    }
    return { accion ->
        when {
            pendiente != null -> Unit
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED -> {
                pendiente = accion
                lanzador.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> accion()
        }
    }
}

/**
 * La fila del llavero en la principal. Sin llavero activo, ofrece traerlo;
 * con él, dice cómo está (en ámbar si hay algo que mirar) y lo abre.
 *
 * [conRed] y [conPermiso] ([conAvisos]) vienen de la pantalla, que no se va
 * mientras Android pregunta: la fila y sus diálogos sí pueden irse, y con
 * ellos la respuesta.
 */
@Composable
internal fun FilaDelLlavero(
    m: Modelo,
    f: Estado.FilaLlavero,
    conRed: (() -> Unit) -> Unit,
    conPermiso: (() -> Unit) -> Unit,
) {
    val contexto = LocalContext.current
    val libre = m.ocupado == null
    var traer by remember { mutableStateOf(false) }
    var quitar by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val colores = if (f.ambar) {
        CardDefaults.cardColors(containerColor = AMBAR, contentColor = SOBRE_AMBAR)
    } else {
        CardDefaults.cardColors()
    }
    Card(colors = colores, modifier = Modifier.fillMaxWidth().testTag("fila-llavero")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Llavero", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (f.activo) {
                    Box {
                        TextButton(
                            onClick = { menu = true },
                            enabled = libre,
                            modifier = Modifier.testTag("menu-llavero").semantics { contentDescription = "Más del llavero" },
                        ) { Text("⋮", style = MaterialTheme.typography.titleMedium) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Quitar el llavero de este teléfono") },
                                onClick = {
                                    menu = false
                                    quitar = true
                                },
                                modifier = Modifier.testTag("quitar-llavero"),
                            )
                        }
                    }
                }
            }
            if (!f.activo) {
                Text("${f.base}: el remoto tiene un llavero, y este teléfono aún no lo trae.")
                Text(TextosLlavero.QUE_ES, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { traer = true }, enabled = libre, modifier = Modifier.testTag("traer-llavero")) {
                    Text("Traer el llavero")
                }
            } else {
                EstadoDelLlavero(f)
                Button(
                    onClick = { conRed(m::abrirLlavero) },
                    enabled = libre,
                    modifier = Modifier.testTag("abrir-llavero"),
                ) { Text("Abrir llavero") }
            }
        }
    }
    if (traer) ConfirmarTraer(m, f, conRed, conPermiso) { traer = false }
    if (quitar) ConfirmarQuitar(m, f) { quitar = false }
}

/**
 * Abre la base que pide el modelo ([Modelo.porAbrir]) con la app de KeePass,
 * con el contexto de ahora, y se lo dice: es de una sola vez. Va en la
 * pantalla y no en la fila, que puede no estar compuesta (fuera de la vista).
 */
@Composable
internal fun AbrirLaBase(m: Modelo) {
    val contexto = LocalContext.current
    val uri = m.porAbrir ?: return
    LaunchedEffect(uri) {
        m.abierta()
        for (tipo in TextosLlavero.TIPOS) {
            try {
                contexto.startActivity(intentDeAbrir(uri, tipo))
                return@LaunchedEffect
            } catch (_: ActivityNotFoundException) {
                // Ninguna app para este tipo: el siguiente.
            }
        }
        m.avisar(TextosLlavero.SIN_APP)
    }
}

/**
 * El `ACTION_VIEW` de la base, con permiso de leerla y escribirla.
 *
 * - **Persistible** (`FLAG_GRANT_PERSISTABLE_URI_PERMISSION`): la app de KeePass
 *   se queda el permiso y puede volver a abrir la base desde su lista de
 *   recientes. Sin él, KeePassDX lo intenta («No persistable permission grants»)
 *   y Android le niega el proveedor la vez siguiente.
 * - **En su propia tarea** (`FLAG_ACTIVITY_NEW_TASK`): si no, la app de KeePass
 *   se apila encima de prdrive, y volver a prdrive la enseña a ella.
 */
internal fun intentDeAbrir(uri: Uri, tipo: String): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, tipo)
        .addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK,
        )

/** Cómo está el llavero: la línea de siempre y, si los hay, los motivos del ámbar. */
@Composable
private fun EstadoDelLlavero(f: Estado.FilaLlavero) {
    val fallo = f.fallo
    if (fallo != null) {
        val cuando = Store.desdeSello(fallo.cuando)?.let { " (${haceCuanto(it)})" } ?: ""
        Text(
            "${f.base}: la última pasada falló$cuando. Se vuelve a intentar al abrirlo y en cada guardado.",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("llavero-estado"),
        )
    } else {
        val linea = when {
            !f.hayBase -> "${f.base} todavía no está en el teléfono: «Abrir llavero» la trae del remoto."
            f.pendiente -> "${f.base}, con cambios que aún no han subido."
            else -> "${f.base}, al día" + (f.ultimaBuena?.let { " · última pasada ${haceCuanto(it)}." } ?: ".")
        }
        Text(linea, modifier = Modifier.testTag("llavero-estado"))
    }
    if (!f.ambar) return
    Column(modifier = Modifier.testTag("llavero-ambar"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (fallo != null) f.explicacion?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (f.faltaLlave) Text(TextosLlavero.FALTA_LLAVE, style = MaterialTheme.typography.bodySmall)
        f.cortado?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (f.conflictos.isNotEmpty()) {
            Text(Avisos.LLAVERO_EN_CONFLICTO + Avisos.COMBINAR_FUERA, style = MaterialTheme.typography.bodySmall)
            f.conflictos.flatMap { it.copias }.forEach {
                Text("· ${it.name}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * La confirmación de «Traer el llavero», con lo que avisa prdrive
 * ([Volumen.avisosAlta]) y, si va sin contraseña, el selector de su fichero
 * llave: sin él no se trae.
 */
@Composable
private fun ConfirmarTraer(
    m: Modelo,
    f: Estado.FilaLlavero,
    conRed: (() -> Unit) -> Unit,
    conPermiso: (() -> Unit) -> Unit,
    cerrar: () -> Unit,
) {
    var llave by remember { mutableStateOf<Uri?>(null) }
    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) llave = uri
    }
    AlertDialog(
        onDismissRequest = cerrar,
        title = { Text("Traer el llavero") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${f.base} se sincronizará en este teléfono, en $LLAVERO_LOCAL/. ${TextosLlavero.QUE_ES}")
                f.avisos.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                if (f.llaveInterna) {
                    Text(
                        "Va sin contraseña: elige su fichero llave. Se copia dentro de la app, a " +
                            "$LLAVERO_LOCAL/$LLAVERO_LLAVE.",
                    )
                    OutlinedButton(onClick = { elegir.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("elegir-llave")) {
                        Text(if (llave == null) "Elegir el fichero llave" else "Fichero llave elegido")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    cerrar()
                    val elegida = llave
                    conPermiso { conRed { m.traerLlavero(elegida) } }
                },
                enabled = !f.llaveInterna || llave != null,
                modifier = Modifier.testTag("confirmar-traer"),
            ) { Text("Traer") }
        },
        dismissButton = { TextButton(onClick = cerrar) { Text("Cancelar") } },
    )
}

/** La confirmación de quitarlo, con lo que avisa prdrive ([Volumen.avisosBaja]). */
@Composable
private fun ConfirmarQuitar(m: Modelo, f: Estado.FilaLlavero, cerrar: () -> Unit) {
    AlertDialog(
        onDismissRequest = cerrar,
        title = { Text("Quitar el llavero de este teléfono") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Dejará de sincronizarse aquí. La carpeta $LLAVERO_LOCAL/ se queda, con la base, y el " +
                        "remoto y los demás dispositivos siguen con él.",
                )
                f.avisos.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    cerrar()
                    m.quitarLlavero()
                },
                modifier = Modifier.testTag("confirmar-quitar"),
            ) { Text("Quitar") }
        },
        dismissButton = { TextButton(onClick = cerrar) { Text("Cancelar") } },
    )
}

/** Lo que «Abrir llavero» dice antes de abrir; al cerrarlo, se abre. */
@Composable
internal fun AvisoAntesDeAbrir(texto: String, cerrar: () -> Unit) {
    AlertDialog(
        onDismissRequest = cerrar,
        title = { Text("Antes de abrir el llavero") },
        text = { Text(texto, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = cerrar, modifier = Modifier.testTag("cerrar-aviso-llavero")) { Text("Abrir") }
        },
        modifier = Modifier.testTag("aviso-llavero"),
    )
}

/**
 * La casilla «El llavero» al emparejar, si el catálogo trae `[keychain]`:
 * desmarcada, como el paso opcional del instalador. Sin contraseña, marcarla
 * pide su fichero llave, y sin él no se marca.
 */
@Composable
internal fun CasillaDelLlavero(m: Modelo) {
    val cat = m.catalogo ?: return
    val tabla = cat.raw["keychain"] as? Map<*, *> ?: return
    val interna = tabla["llave_interna"] == true
    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) m.elegirLlavero(true, uri)
    }
    // Lo que avisa prdrive con las parejas elegidas: una de la raíz entera.
    val avisos = remember(cat, m.elegidas) {
        try {
            Volumen.avisosAlta(parseConfig(Volumen.deviceConfig(cat.raw, cat.names.filter { it in m.elegidas })))
        } catch (_: Exception) {
            // Sin parejas elegidas, o un catálogo que no vale: lo dirá «Instalar».
            emptyList()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = m.conLlavero,
            onCheckedChange = { si -> if (si && interna) elegir.launch(arrayOf("*/*")) else m.elegirLlavero(si) },
            modifier = Modifier.testTag("elegir-llavero"),
        )
        Column {
            Text("El llavero", fontWeight = FontWeight.SemiBold)
            Text("${tabla["base"] ?: ""}: ${TextosLlavero.QUE_ES}", style = MaterialTheme.typography.bodySmall)
            if (interna) {
                Text(
                    "Va sin contraseña: al marcarlo, elige su fichero llave, que se copia dentro de la app.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (m.conLlavero) {
                avisos.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}
