package prdrive.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import prdrive.app.Prdrive
import prdrive.app.compartidas.Compartir
import prdrive.app.ui.piezas.Aviso
import prdrive.app.ui.piezas.Boton
import prdrive.app.ui.piezas.Peso
import prdrive.app.ui.piezas.Tamano
import prdrive.app.ui.piezas.TonoAviso

/**
 * Permisos.kt — Lo que piden las acciones que necesitan un permiso: la red
 * local (desde API 37) antes de hablar con el remoto, y el acceso a todos los
 * archivos antes de mover una pareja a una carpeta compartida.
 */

/**
 * Una acción que puede necesitar la red local: antes de hacerla se pide el
 * permiso, si falta ([Prdrive.faltaRedLocal]). Se haga con permiso o sin él:
 * un remoto en internet no lo necesita, y si era uno de casa, el error que
 * salga ya lo explica. [alResponder] se llama cuando la persona contesta.
 */
@Composable
internal fun conRedLocal(alResponder: () -> Unit = {}): (() -> Unit) -> Unit {
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
internal fun AvisoRedLocal(pedir: () -> Unit, modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    Aviso(
        "Falta el permiso de red local",
        modifier,
        tono = TonoAviso.AVISO,
        cuerpo = "Sin él, la app no llega a un servidor de tu casa (un NAS en 192.168…, un PC en 10…). " +
            "Si tu remoto está en internet, no hace falta.",
    ) {
        Boton("Dar permiso", pedir, peso = Peso.CONTORNO, tamano = Tamano.PEQUENO, sobreAmbar = true)
        // Si ya se negó dos veces, Android no vuelve a preguntar: quedan los ajustes.
        Boton(
            "Abrir ajustes",
            { abrirAjustesDeLaApp(contexto) },
            peso = Peso.SILENCIOSO,
            tamano = Tamano.PEQUENO,
            sobreAmbar = true,
        )
    }
}

/** Los ajustes de la app en Android, donde se dan los permisos que ya no se preguntan. */
internal fun abrirAjustesDeLaApp(contexto: Context) {
    contexto.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", contexto.packageName, null)),
    )
}

/**
 * Abre la pantalla de Android en la que se da el permiso de acceso a todos los
 * archivos. No hay resultado que esperar: al volver a la app se vuelve a mirar.
 * En un teléfono que no tenga esa pantalla, se dice dónde está el ajuste.
 */
internal fun darPermisoCompartido(contexto: Context, m: Modelo) {
    try {
        contexto.startActivity(Compartir.pedirPermiso(contexto))
    } catch (_: ActivityNotFoundException) {
        m.avisar("No encuentro la pantalla del permiso. Búscalo en los ajustes de la app prdrive: «Acceso a todos los archivos».")
    }
}
