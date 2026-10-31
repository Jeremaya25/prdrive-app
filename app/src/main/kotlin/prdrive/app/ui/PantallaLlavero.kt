package prdrive.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import prdrive.app.Estado
import prdrive.app.ui.piezas.Casilla
import prdrive.engine.LLAVERO_LLAVE
import prdrive.engine.LLAVERO_LOCAL
import prdrive.engine.Store
import prdrive.engine.Volumen
import prdrive.engine.parseConfig

/**
 * PantallaLlavero.kt — Lo del llavero que usan varias pantallas: sus textos
 * ([TextosLlavero], con la línea que dice cómo está), pedir las notificaciones
 * antes de activarlo, abrir la base con la app de KeePass, el aviso de antes de
 * abrir y la casilla «El llavero» al emparejar. La sección está en
 * `llavero/SeccionLlavero.kt`. Como el resto de `ui/`, solo dibuja y llama al
 * [Modelo].
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
     * El tipo con que se pide abrir la base. No hay uno oficial para `.kdbx`:
     * `application/x-kdbx` lo declaran las apps de KeePass (KeePassDX 4.5.6,
     * visto en el emulador), así que abre una de ellas sin más.
     */
    const val TIPO_KEEPASS = "application/x-kdbx"

    /**
     * La reserva, para una app de KeePass que solo declare el genérico. Con él,
     * Android mezcla cualquier app que abra ficheros (Google, Wallet…): va por
     * un selector y sin permiso que dure.
     */
    const val TIPO_GENERICO = "application/octet-stream"

    /**
     * Cómo le llega a KeePassDX el fichero llave en un `ACTION_VIEW`: un
     * `ClipData` con esta etiqueta y una sola URI (`MainCredentialActivity`,
     * `getUriFromIntent`, y `UriUtil.getUri` de KeePassDX 4.5.6). Sin él, el
     * campo «Archivo de clave» sale vacío la primera vez y solo lo rellena su
     * historial, si ya se abrió esa base con esa llave.
     */
    const val ETIQUETA_LLAVE_KEEPASSDX = "keyFile"

    /**
     * Los paquetes de KeePassDX (sus sabores `libre` y `free`, en su
     * `app/build.gradle.kts`), declarados en `<queries>` del manifiesto. La
     * llave solo se le pasa a uno de ellos.
     */
    val PAQUETES_KEEPASSDX = setOf("com.kunzisoft.keepass.libre", "com.kunzisoft.keepass.free")

    /** El título del selector de la reserva. */
    const val ABRIR_CON = "Abrir el llavero con tu app de KeePass"

    /**
     * Cómo está el llavero, en una frase: la línea de `llavero_editor.linea()`
     * de prdrive, la que sale en Inicio y en su sección.
     */
    fun linea(f: Estado.FilaLlavero): String {
        if (!f.activo) return "${f.base}: el remoto tiene un llavero, y este teléfono aún no lo trae."
        val fallo = f.fallo
        if (fallo != null) {
            val cuando = Store.desdeSello(fallo.cuando)?.let { " (${haceCuanto(it)})" } ?: ""
            return "${f.base}: la última pasada falló$cuando. Se vuelve a intentar al abrirlo y en cada guardado."
        }
        return when {
            !f.hayBase -> "${f.base} todavía no está en el teléfono: «Abrir llavero» la trae del remoto."
            f.pendiente -> "${f.base}, con cambios que aún no han subido."
            else -> "${f.base}, al día" + (f.ultimaBuena?.let { " · última pasada ${haceCuanto(it)}." } ?: ".")
        }
    }
}

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
                Preguntas.apuntar(contexto, Manifest.permission.POST_NOTIFICATIONS)
                lanzador.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> accion()
        }
    }
}

/**
 * Abre la base que pide el modelo ([Modelo.porAbrir]) con la app de KeePass,
 * con el contexto de ahora, y se lo dice: es de una sola vez. Va en la
 * pantalla y no en la fila, que puede no estar compuesta (fuera de la vista).
 */
@Composable
internal fun AbrirLaBase(m: Modelo) {
    val contexto = LocalContext.current
    val apertura = m.porAbrir ?: return
    LaunchedEffect(apertura) {
        m.abierta()
        val keepass = intentDeAbrir(apertura.base, TextosLlavero.TIPO_KEEPASS, persistible = true)
        val llave = apertura.llave
        val paquete = llave?.let {
            contexto.packageManager.resolveActivity(keepass, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }
        try {
            contexto.startActivity(conLaLlave(keepass, llave, paquete))
            return@LaunchedEffect
        } catch (_: ActivityNotFoundException) {
            // Ninguna app de KeePass declara su tipo: la reserva, sin la llave.
        }
        val generico = intentDeAbrir(apertura.base, TextosLlavero.TIPO_GENERICO, persistible = false)
        if (contexto.packageManager.queryIntentActivities(generico, 0).isEmpty()) {
            m.avisar(TextosLlavero.SIN_APP)
            return@LaunchedEffect
        }
        // Con el genérico lo abre casi cualquier app: que la elija la persona,
        // siempre, y que el permiso dure esa vez.
        contexto.startActivity(
            Intent.createChooser(generico, TextosLlavero.ABRIR_CON).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * El `ACTION_VIEW` de la base, con permiso de leerla y escribirla, y en su
 * propia tarea (`FLAG_ACTIVITY_NEW_TASK`: si no, la app de KeePass se apila
 * encima de prdrive, y volver a prdrive la enseña a ella).
 *
 * [persistible] (`FLAG_GRANT_PERSISTABLE_URI_PERMISSION`) deja a la app quedarse
 * el permiso y volver a abrir la base desde sus recientes; sin él, KeePassDX lo
 * intenta («No persistable permission grants») y Android le niega el proveedor
 * la vez siguiente. Es un permiso de escritura que dura, dado con un `Intent`
 * implícito, así que va solo con el tipo de KeePass, que declaran las apps de
 * KeePass; con el genérico, no, y por un selector. Al quitar el llavero se
 * retira ([prdrive.app.Instalador.quitarLlavero]).
 */
internal fun intentDeAbrir(uri: Uri, tipo: String, persistible: Boolean): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, tipo)
        .addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_NEW_TASK or
                (if (persistible) Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION else 0),
        )

/**
 * [intent] con el fichero llave ([llave]) de un llavero sin contraseña, como lo
 * lee KeePassDX ([TextosLlavero.ETIQUETA_LLAVE_KEEPASSDX]), si quien lo abriría
 * ([paquete], lo que resuelve Android) es KeePassDX. Si no, [intent] tal cual.
 *
 * La llave y la base abren el llavero sin nada más, así que la llave no va en
 * un `Intent` implícito: va dirigido a ese paquete (`setPackage`), y solo
 * cuando Android lo abriría con él de todos modos (con un selector delante, o
 * con otra app, se queda fuera y se elige a mano en la app de KeePass). Las
 * marcas de [intent] valen también para la URI del `ClipData`, escritura
 * incluida: [prdrive.app.documentos.VolumenProvider] no deja escribir la llave
 * con el llavero activo, y al quitarlo se retira el permiso
 * ([prdrive.app.Instalador.retirarPermisos]).
 */
internal fun conLaLlave(intent: Intent, llave: Uri?, paquete: String?): Intent {
    if (llave == null || paquete !in TextosLlavero.PAQUETES_KEEPASSDX) return intent
    return Intent(intent).setPackage(paquete).apply {
        clipData = ClipData.newRawUri(TextosLlavero.ETIQUETA_LLAVE_KEEPASSDX, llave)
    }
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
    Casilla(
        "El llavero",
        m.conLlavero,
        { si -> if (si && interna) elegir.launch(arrayOf("*/*")) else m.elegirLlavero(si) },
        Modifier.testTag("elegir-llavero").padding(horizontal = 16.dp),
        pista = "${tabla["base"] ?: ""}: ${TextosLlavero.QUE_ES}",
        activa = m.ocupado == null,
    ) {
        if (interna) {
            Text(
                "Va sin contraseña: al marcarlo, elige su fichero llave, que se copia dentro de la app.",
                style = Letra.actual.pista,
            )
        }
        if (m.conLlavero) {
            avisos.forEach { Text(it, style = Letra.actual.pista, color = Paleta.actual.warnInk) }
        }
    }
}
