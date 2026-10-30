package prdrive.app.ui.parejas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import prdrive.app.ui.Cabecera
import prdrive.app.ui.Letra
import prdrive.app.ui.Modelo
import prdrive.app.ui.Paleta
import prdrive.app.ui.piezas.Aviso
import prdrive.app.ui.piezas.Boton
import prdrive.app.ui.piezas.Glifo
import prdrive.app.ui.piezas.Icono
import prdrive.app.ui.piezas.Peso
import prdrive.app.ui.piezas.Progreso
import prdrive.app.ui.piezas.Rotulo
import prdrive.app.ui.piezas.Tamano
import prdrive.app.ui.piezas.TonoAviso
import prdrive.engine.MODES

/** Cuántas rutas de cada lista se ven antes de «Ver los N restantes». */
private const val VISIBLES = 3

/**
 * Simulacro.kt — «Hacer un simulacro…» de una pareja: la pasada con
 * `--dry-run`, que no escribe nada, leída como lo que va a pasar (cuántos
 * ficheros se bajan, se suben y se borran, y cuáles), con «Sincronizar» al pie.
 * Si borra, el botón dice cuántos y es rojo: un aviso que borra dice qué borra.
 * Es «Simulacro de un espejo» del lienzo, y vale para cualquier modo.
 *
 * [conRed] es el de `Secciones`, como en [Mover]: la pasada vuelve a la ficha y
 * la respuesta de Android a la red local tiene que llegar a alguien.
 */
@Composable
fun Simulacro(m: Modelo, nombre: String, volver: () -> Unit, conRed: (() -> Unit) -> Unit) {
    val c = Paleta.actual
    val r = m.simulacro?.takeIf { it.pareja == nombre }
    LaunchedEffect(nombre) {
        if (m.simulacro?.pareja != nombre && m.ocupado == null) m.simular(nombre)
    }
    Column(Modifier.fillMaxSize().testTag("pantalla-simulacro-$nombre")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Cabecera("Simulacro", volver = volver, etiquetaVolver = nombre)
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    if (r?.resync == true) {
                        "Esto es lo que haría rehacer la referencia de «$nombre». Nada se ha escrito todavía."
                    } else {
                        "Esto es lo que va a pasar en «$nombre». Nada se ha escrito todavía."
                    },
                    style = Letra.actual.texto,
                    color = c.ink2,
                )
                when {
                    r == null -> Progreso(m.progreso?.fraccion, texto = m.ocupado)
                    !r.ok -> Aviso(
                        "El simulacro no ha salido",
                        tono = TonoAviso.PELIGRO,
                        cuerpo = r.explicacion ?: "Mira el log de la pareja para saber por qué.",
                    )
                    else -> Plan(r)
                }
            }
        }
        if (r != null && r.ok) {
            Column(Modifier.fillMaxWidth().background(c.paper)) {
                HorizontalDivider(thickness = 1.dp, color = c.line)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val borra = r.plan.borrados.size
                    Boton(
                        if (borra > 0) "Sincronizar y borrar $borra" else "Sincronizar",
                        { conRed { m.sincronizar(solo = nombre) }; volver() },
                        Modifier.testTag("simulacro-sincronizar"),
                        peso = if (borra > 0) Peso.PELIGRO_SOLIDO else Peso.PRINCIPAL,
                        ancho = true,
                        activo = m.ocupado == null && !r.resync,
                    )
                    if (r.resync) {
                        Text("Para hacerlo de verdad, «Rehacer la referencia» en su ficha.", style = Letra.actual.pista)
                    }
                    Boton("Cancelar", volver, ancho = true)
                }
            }
        }
    }
}

/** Las cifras, el aviso si borra y las dos listas. */
@Composable
private fun Plan(r: Modelo.ResultadoSimulacro) {
    val modo = MODES[r.modo]
    val bisync = modo?.isBisync != false
    val subida = !bisync && MODES.getValue(r.modo).origenLocal
    val plan = r.plan
    if (plan.vacio) {
        Aviso("No hay nada que hacer", tono = TonoAviso.OK, cuerpo = "Los dos lados ya son iguales.")
        return
    }
    val (bajan, suben) = when {
        bisync -> plan.bajarian to plan.subirian
        subida -> 0 to plan.copias.size
        else -> plan.copias.size to 0
    }
    // Dónde se borra: en un espejo, en el lado que se iguala; en bisync, lo dice su cola.
    val dondeBorra = when {
        subida -> "en el remoto"
        !bisync -> "en el teléfono"
        plan.borrariaRemoto > 0 && plan.borrariaTelefono == 0 -> "en el remoto"
        plan.borrariaTelefono > 0 && plan.borrariaRemoto == 0 -> "en el teléfono"
        else -> "en los dos lados"
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Cifra(bajan, "se bajan", Icono.DOWN, Modifier.weight(1f).testTag("simulacro-copias-bajan"))
        Cifra(suben, "se suben", Icono.UP, Modifier.weight(1f).testTag("simulacro-copias-suben"))
        Cifra(plan.borrados.size, "se borran", Icono.TRASH, Modifier.weight(1f).testTag("simulacro-borrados"), peligro = true)
    }
    // Para los tests y los lectores de pantalla: el total de copias, sea hacia donde sea.
    Text(
        "${plan.copias.size} ${if (plan.copias.size == 1) "fichero se copia" else "ficheros se copian"}",
        style = Letra.actual.pista,
        modifier = Modifier.testTag("simulacro-copias"),
    )
    val borra = plan.borrados.size
    if (borra > 0) {
        Aviso(
            "Ojo: borra $borra ${if (borra == 1) "fichero" else "ficheros"} $dondeBorra",
            tono = TonoAviso.AVISO,
            cuerpo = when {
                bisync -> "Se borraron en el otro lado desde la última pasada, y bisync lo lleva a este."
                subida -> "Es un espejo de subida: el remoto se iguala al teléfono, y lo que no está aquí se borra allí."
                else -> "Es un espejo de bajada: el teléfono se iguala al remoto, y lo que no está allí se borra aquí."
            } + (r.tope?.let { " Se para si fueran más (--max-delete $it${if (bisync) " %" else ""})." } ?: ""),
        )
        Lista("Se borran · $borra", plan.borrados, Icono.TRASH, peligro = true, Modifier.testTag("simulacro-lista-borrados"))
    }
    if (plan.copias.isNotEmpty()) {
        Lista(
            "Se copian · ${plan.copias.size}",
            plan.copias,
            if (suben > 0 && bajan == 0) Icono.UP else Icono.DOWN,
            peligro = false,
            Modifier.testTag("simulacro-lista-copias"),
        )
    }
}

/** Una cifra del plan: el número grande y qué es, con su glifo. */
@Composable
private fun Cifra(n: Int, que: String, icono: Icono, modifier: Modifier, peligro: Boolean = false) {
    val c = Paleta.actual
    val forma = RoundedCornerShape(4.dp)
    val roja = peligro && n > 0
    Column(
        modifier
            .clip(forma)
            .background(if (roja) c.dangerSoft else c.surface)
            .border(1.dp, if (roja) c.dangerLine else c.line, forma)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("$n", fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = if (roja) c.danger else c.ink)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompositionLocalProvider(LocalContentColor provides if (roja) c.danger else c.ink2) { Glifo(icono, tamano = 12.dp) }
            Text(que, style = Letra.actual.pista, color = if (roja) c.ink else c.ink2)
        }
    }
}

/** Una lista de rutas: las primeras [VISIBLES], y el resto con «Ver los N restantes». */
@Composable
private fun Lista(titulo: String, rutas: List<String>, icono: Icono, peligro: Boolean, modifier: Modifier) {
    val c = Paleta.actual
    var todas by rememberSaveable(titulo) { mutableStateOf(false) }
    val forma = RoundedCornerShape(4.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Rotulo(titulo)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(forma)
                .background(if (peligro) c.dangerSoft else c.surface)
                .border(1.dp, if (peligro) c.dangerLine else c.line, forma),
        ) {
            val visibles = if (todas) rutas else rutas.take(VISIBLES)
            visibles.forEachIndexed { i, ruta ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = if (peligro) c.dangerLine else c.lineSoft)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val color = if (peligro) c.danger else c.ink2
                    CompositionLocalProvider(LocalContentColor provides color) { Glifo(icono, tamano = 12.dp) }
                    Text(ruta, style = Letra.actual.mono, color = color)
                }
            }
            if (rutas.size > VISIBLES) {
                HorizontalDivider(thickness = 1.dp, color = if (peligro) c.dangerLine else c.lineSoft)
                Boton(
                    if (todas) "Ver menos" else "Ver los ${rutas.size - VISIBLES} restantes",
                    { todas = !todas },
                    Modifier.padding(horizontal = 4.dp),
                    peso = Peso.SILENCIOSO,
                    tamano = Tamano.NORMAL,
                )
            }
        }
    }
}
