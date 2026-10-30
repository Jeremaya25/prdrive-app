package prdrive.app.ui.llavero

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import prdrive.app.Estado
import prdrive.app.llavero.Avisos
import prdrive.app.ui.Cabecera
import prdrive.app.ui.Letra
import prdrive.app.ui.Modelo
import prdrive.app.ui.Paleta
import prdrive.app.ui.TextosLlavero
import prdrive.app.ui.piezas.Aviso
import prdrive.app.ui.piezas.Boton
import prdrive.app.ui.piezas.Chip
import prdrive.app.ui.piezas.Glifo
import prdrive.app.ui.piezas.Icono
import prdrive.app.ui.piezas.Peso
import prdrive.app.ui.piezas.Rotulo
import prdrive.app.ui.piezas.Separador
import prdrive.app.ui.piezas.Tarjeta
import prdrive.app.ui.piezas.Tono
import prdrive.app.ui.piezas.TonoAviso
import prdrive.engine.LLAVERO_LLAVE
import prdrive.engine.LLAVERO_LOCAL
import prdrive.engine.Volumen

/**
 * SeccionLlavero.kt — La sección Llavero: cómo está (y, si va en ámbar, por
 * qué), «Abrir llavero», cómo funciona en el teléfono y quitarlo; o, si el
 * remoto lo tiene y este teléfono no, traerlo. Los tres estados del lienzo.
 *
 * [conRed] y [conPermiso] vienen de `Secciones`, que no se va mientras Android
 * pregunta: los diálogos de traerlo sí pueden irse, y con ellos la respuesta.
 */
@Composable
fun SeccionLlavero(m: Modelo, conRed: (() -> Unit) -> Unit, conPermiso: (() -> Unit) -> Unit) {
    var traer by remember { mutableStateOf(false) }
    var quitar by remember { mutableStateOf(false) }
    val f = m.llavero
    val libre = m.ocupado == null
    Column(
        Modifier
            .fillMaxSize()
            .testTag("pantalla-llavero")
            .verticalScroll(rememberScrollState()),
    ) {
        Cabecera(
            "Llavero",
            pie = f?.base?.takeIf { it.isNotEmpty() }?.let { "$it · KeePass" },
            pieMono = true,
        )
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when {
                f == null -> Text(TextosLlavero.SIN_LLAVERO_REMOTO, style = Letra.actual.texto, color = Paleta.actual.ink2)
                !f.activo -> SinTraer(f, libre) { traer = true }
                else -> {
                    Activo(m, f, libre, conRed)
                    if (f.ambar) PorQueEnAmbar(f)
                    ComoFunciona(f)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Boton(
                            "Quitar el llavero de este teléfono",
                            { quitar = true },
                            Modifier.testTag("quitar-llavero"),
                            peso = Peso.PELIGRO,
                            icono = Icono.TRASH,
                            ancho = true,
                            activo = libre,
                        )
                        Text(
                            "Deja de sincronizarse aquí. La carpeta $LLAVERO_LOCAL/ se queda, con la base; el remoto " +
                                "y los demás dispositivos siguen con él.",
                            style = Letra.actual.pista,
                        )
                    }
                }
            }
        }
    }
    if (traer && f != null) ConfirmarTraer(m, f, conRed, conPermiso) { traer = false }
    if (quitar && f != null) ConfirmarQuitar(m, f) { quitar = false }
}

/** La tarjeta de estado de un llavero activo: su chip, su línea y «Abrir llavero». */
@Composable
private fun Activo(m: Modelo, f: Estado.FilaLlavero, libre: Boolean, conRed: (() -> Unit) -> Unit) {
    val (tono, palabra) = when {
        f.fallo != null -> Tono.PELIGRO to "falló"
        f.conflictos.isNotEmpty() -> Tono.AVISO to "un guardado aparte"
        f.cortado != null -> Tono.AVISO to "un guardado cortado"
        f.faltaLlave -> Tono.AVISO to "falta la llave"
        f.pendiente -> Tono.AVISO to "cambios sin subir"
        !f.hayBase -> Tono.APAGADO to "sin traer"
        else -> Tono.OK to "al día"
    }
    TarjetaDeEstado {
        Chip(palabra, tono = tono)
        Text(TextosLlavero.linea(f), style = Letra.actual.texto, color = Paleta.actual.ink2, modifier = Modifier.testTag("llavero-estado"))
        Boton(
            "Abrir llavero",
            { conRed(m::abrirLlavero) },
            Modifier.testTag("abrir-llavero"),
            peso = Peso.PRINCIPAL,
            icono = Icono.CANDADO_ABIERTO,
            ancho = true,
            ocupado = m.ocupado == TextosLlavero.TRAYENDO,
            activo = libre,
        )
        Text(
            "Se abre en tu app de KeePass. Lo que guardes sube solo, unos 20 s después, con conexión.",
            style = Letra.actual.pista,
        )
    }
}

/** Sin el llavero en el teléfono, pero en el remoto: qué es, y traerlo. */
@Composable
private fun SinTraer(f: Estado.FilaLlavero, libre: Boolean, traer: () -> Unit) {
    TarjetaDeEstado {
        Chip("no está en este teléfono", tono = Tono.APAGADO)
        Text(
            "${f.base}: el remoto tiene un llavero, y este teléfono aún no lo trae. ${TextosLlavero.QUE_ES}",
            style = Letra.actual.texto,
            color = Paleta.actual.ink2,
            modifier = Modifier.testTag("llavero-estado"),
        )
        Boton(
            "Traer el llavero…",
            traer,
            Modifier.testTag("traer-llavero"),
            peso = Peso.PRINCIPAL,
            icono = Icono.DOWN,
            ancho = true,
            activo = libre,
        )
    }
}

/** La tarjeta de arriba de la sección, como la de Inicio. */
@Composable
private fun TarjetaDeEstado(contenido: @Composable () -> Unit) {
    val c = Paleta.actual
    val forma = RoundedCornerShape(4.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(forma)
            .background(c.surface)
            .border(1.dp, c.line, forma)
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { contenido() }
}

/** Por qué va en ámbar: cada motivo, en su aviso. */
@Composable
private fun PorQueEnAmbar(f: Estado.FilaLlavero) {
    Column(Modifier.testTag("llavero-ambar"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (f.fallo != null) {
            Aviso("La última pasada falló", tono = TonoAviso.PELIGRO, cuerpo = f.explicacion)
        }
        if (f.faltaLlave) Aviso("Falta el fichero llave", tono = TonoAviso.AVISO, cuerpo = TextosLlavero.FALTA_LLAVE)
        f.cortado?.let { Aviso("Un guardado no llegó entero", tono = TonoAviso.AVISO, cuerpo = it) }
        if (f.conflictos.isNotEmpty()) {
            Aviso(
                "Hay un guardado aparte",
                tono = TonoAviso.AVISO,
                cuerpo = Avisos.LLAVERO_EN_CONFLICTO + Avisos.COMBINAR_FUERA,
            )
            CopiasDeConflicto(f)
        }
    }
}

/** Las copias de conflicto de la base, por su nombre. */
@Composable
private fun CopiasDeConflicto(f: Estado.FilaLlavero) {
    val copias = f.conflictos.flatMap { it.copias }
    if (copias.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Rotulo("Copias de conflicto · ${copias.size}")
        Tarjeta {
            copias.forEachIndexed { i, copia ->
                if (i > 0) Separador()
                Text(
                    copia.name,
                    style = Letra.actual.mono,
                    color = Paleta.actual.ink2,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("copia-${copia.name}"),
                )
            }
        }
    }
}

/** Cómo funciona el llavero en el teléfono: cuándo sube, cuándo baja, y la llave. */
@Composable
private fun ComoFunciona(f: Estado.FilaLlavero) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Rotulo("Cómo funciona")
        Tarjeta {
            Paso(Icono.UP, "Sube al guardar", "Cada guardado espera unos 20 s y se sube. Si guardas otra vez, vuelve a esperar.")
            Separador()
            Paso(
                Icono.DOWN,
                "Trae lo último al abrir",
                "«Abrir llavero» trae antes lo del remoto. Abrirla desde los recientes de tu app de KeePass enseña la de la " +
                    "última pasada.",
            )
            if (f.llaveInterna) {
                Separador()
                Paso(
                    Icono.LLAVE,
                    "Fichero llave",
                    "$LLAVERO_LOCAL/$LLAVERO_LLAVE, de solo lectura mientras el llavero esté activo.",
                )
            }
        }
    }
}

@Composable
private fun Paso(icono: Icono, titulo: String, texto: String) {
    val c = Paleta.actual
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)).background(c.sunken),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides c.ink2) { Glifo(icono) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titulo, style = Letra.actual.nombre)
            Text(texto, style = Letra.actual.pista, color = c.ink2)
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
                f.avisos.forEach { Text(it, color = Paleta.actual.warnInk) }
                if (f.llaveInterna) {
                    Text(
                        "Va sin contraseña: elige su fichero llave. Se copia dentro de la app, a " +
                            "$LLAVERO_LOCAL/$LLAVERO_LLAVE.",
                    )
                    Boton(
                        if (llave == null) "Elegir el fichero llave" else "Fichero llave elegido",
                        { elegir.launch(arrayOf("*/*")) },
                        Modifier.testTag("elegir-llave"),
                        icono = Icono.LLAVE,
                    )
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
                f.avisos.forEach { Text(it, color = Paleta.actual.warnInk) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    cerrar()
                    m.quitarLlavero()
                },
                modifier = Modifier.testTag("confirmar-quitar"),
            ) { Text("Quitar", color = Paleta.actual.danger) }
        },
        dismissButton = { TextButton(onClick = cerrar) { Text("Cancelar") } },
    )
}
