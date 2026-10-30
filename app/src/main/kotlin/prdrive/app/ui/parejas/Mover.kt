package prdrive.app.ui.parejas

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import prdrive.app.Prdrive
import prdrive.app.compartidas.Compartir
import prdrive.app.ui.Cabecera
import prdrive.app.ui.Letra
import prdrive.app.ui.Modelo
import prdrive.app.ui.Paleta
import prdrive.app.ui.conRedLocal
import prdrive.app.ui.darPermisoCompartido
import prdrive.app.ui.piezas.Aviso
import prdrive.app.ui.piezas.Boton
import prdrive.app.ui.piezas.Chip
import prdrive.app.ui.piezas.Peso
import prdrive.app.ui.piezas.Rotulo
import prdrive.app.ui.piezas.Separador
import prdrive.app.ui.piezas.Tamano
import prdrive.app.ui.piezas.Tarjeta
import prdrive.app.ui.piezas.Tono
import prdrive.app.ui.piezas.TonoAviso
import prdrive.app.ui.rutaCorta

/**
 * Mover.kt — Mover una pareja a una carpeta compartida, para que otra app (como
 * Obsidian) la abra: lo que cambia (la carpeta deja de ser privada), lo que hace
 * falta antes (el permiso, una carpeta vacía; las fechas se prueban al empezar)
 * y qué va a pasar. Nada se mueve hasta «Mover «X»», que vuelve a la ficha, donde
 * se ve cómo va.
 *
 * La carpeta elegida es solo una URI de árbol (`rememberSaveable`): sobrevive a
 * que Android recree la actividad al volver del selector, y no lleva nada que
 * no pueda acabar en disco. [tienePermiso] es [Prdrive.permisoCompartido]; un
 * test pasa otro, porque quitar ese permiso con `appops` mata el proceso.
 */
@Composable
fun Mover(m: Modelo, nombre: String, volver: () -> Unit, tienePermiso: () -> Boolean = Prdrive::permisoCompartido) {
    val contexto = LocalContext.current
    val c = Paleta.actual
    val conRed = conRedLocal()
    // El permiso se da en los ajustes de Android: al volver, se mira otra vez.
    var permiso by remember { mutableStateOf(tienePermiso()) }
    LifecycleResumeEffect(Unit) {
        permiso = tienePermiso()
        onPauseOrDispose {}
    }
    var arbol by rememberSaveable { mutableStateOf<Uri?>(null) }
    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { if (it != null) arbol = it }
    val carpeta = arbol?.let(Compartir::rutaDeArbol)
    Column(
        Modifier
            .fillMaxSize()
            .testTag("pantalla-mover-$nombre")
            .verticalScroll(rememberScrollState()),
    ) {
        Cabecera("Mover a una carpeta compartida", volver = volver, etiquetaVolver = nombre)
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "Para que otra app, como Obsidian, abra «$nombre». La clave, el llavero y las demás parejas siguen privados.",
                style = Letra.actual.texto,
                color = c.ink2,
            )
            Aviso("Ojo: fuera de la app deja de ser privada", tono = TonoAviso.AVISO, cuerpo = Compartir.AVISO_EXPOSICION)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Rotulo("Antes de mover")
                Tarjeta {
                    Paso("Acceso a todos los archivos") {
                        if (permiso) {
                            Chip("dado", tono = Tono.OK)
                        } else {
                            Text(Prdrive.SIN_PERMISO_COMPARTIDO, style = Letra.actual.pista, color = c.ink2)
                            Boton(
                                "Dar permiso",
                                { darPermisoCompartido(contexto, m) },
                                Modifier.testTag("dar-permiso"),
                                tamano = Tamano.NORMAL,
                            )
                        }
                    }
                    Separador()
                    Paso("Una carpeta vacía") {
                        Text(
                            "Vacía o nueva: lo de la pareja se lleva allí y la copia de la app se borra al acabar.",
                            style = Letra.actual.pista,
                            color = c.ink2,
                        )
                        when {
                            carpeta != null -> Text(rutaCorta(carpeta.path), style = Letra.actual.mono, color = c.ink2)
                            arbol != null -> Text(Modelo.SIN_RUTA_COMPARTIDA, style = Letra.actual.pista, color = c.danger)
                        }
                        Boton(
                            if (arbol == null) "Elegir la carpeta" else "Elegir otra",
                            { elegir.launch(DocumentsContract.buildDocumentUri(Compartir.AUTORIDAD, "primary:Documents")) },
                            Modifier.testTag("elegir-carpeta"),
                            tamano = Tamano.NORMAL,
                            activo = permiso,
                        )
                    }
                    Separador()
                    Paso("Las fechas se conservan") {
                        Text(
                            "Las fechas se comprueban al empezar: si no se conservan, no se mueve nada.",
                            style = Letra.actual.pista,
                            color = c.ink2,
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Rotulo("Qué va a pasar")
                val destino = carpeta?.let { rutaCorta(it.path) } ?: "la carpeta"
                listOf(
                    "Una pasada normal: el remoto se queda con lo último.",
                    "Se baja todo a $destino. Si hay duda, gana el remoto.",
                    "Lo que solo tenía la app se lleva allí, y la copia de la app se borra.",
                    "Si se corta, «Terminar de mover» sigue donde se quedó, sin borrar nada en el remoto.",
                ).forEachIndexed { i, frase ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${i + 1}.", style = Letra.actual.nombre, color = c.ink3)
                        Text(frase, style = Letra.actual.texto, color = c.ink)
                    }
                }
            }
            Boton(
                "Mover «$nombre»",
                {
                    val elegido = arbol ?: return@Boton
                    conRed { m.moverACompartida(nombre, elegido) }
                    volver()
                },
                Modifier.testTag("mover-ya"),
                peso = Peso.PRINCIPAL,
                ancho = true,
                activo = permiso && carpeta != null && m.ocupado == null,
            )
        }
    }
}

/** Uno de los pasos de «Antes de mover»: su título y lo suyo debajo. */
@Composable
private fun Paso(titulo: String, contenido: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(titulo, style = Letra.actual.nombre)
        contenido()
    }
}
