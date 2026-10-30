package prdrive.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import prdrive.app.ui.piezas.Fila
import prdrive.app.ui.piezas.Glifo
import prdrive.app.ui.piezas.Icono
import prdrive.app.ui.piezas.Tarjeta

/**
 * Navegacion.kt — Las cuatro secciones de abajo (Inicio, Parejas, Llavero y
 * Ajustes) y la pila de pantallas que se abren encima de una sección (la ficha
 * de una pareja, su log, moverla). Es propia, sin la biblioteca de navegación:
 * son cuatro raíces y tres destinos, y así no entra una dependencia nueva.
 *
 * El botón atrás del sistema saca la pantalla de encima; sin nada encima y
 * fuera de Inicio, vuelve a Inicio; y en Inicio, es de Android: sale de la app.
 * La pila se guarda con la actividad ([Pila.Saver]): al girar el teléfono o
 * cambiar el modo oscuro se vuelve al mismo sitio. Solo son nombres de parejas,
 * nada que no pueda acabar en disco.
 */
enum class Seccion(val titulo: String, val icono: Icono, val etiqueta: String) {
    INICIO("Inicio", Icono.DISPOSITIVO, "seccion-inicio"),
    PAREJAS("Parejas", Icono.PAREJAS, "seccion-parejas"),
    LLAVERO("Llavero", Icono.LLAVE, "seccion-llavero"),
    AJUSTES("Ajustes", Icono.GEAR, "seccion-ajustes"),
}

/** Lo que se abre encima de una sección. */
sealed interface Destino {
    data class Ficha(val pareja: String) : Destino
    data class Log(val pareja: String) : Destino
    data class Mover(val pareja: String) : Destino
}

/** La sección elegida y lo que tiene encima, de abajo arriba. */
class Pila(seccion: Seccion = Seccion.INICIO, encima: List<Destino> = emptyList()) {

    var seccion by mutableStateOf(seccion)
        private set

    private val destinos = mutableStateListOf<Destino>().apply { addAll(encima) }

    /** La pantalla de encima, o `null` si se ve la de la sección. */
    val cima: Destino? get() = destinos.lastOrNull()

    /** Si el botón atrás tiene algo que hacer aquí: si no, es de Android. */
    val puedeVolver: Boolean get() = destinos.isNotEmpty() || seccion != Seccion.INICIO

    fun ir(d: Destino) {
        destinos.add(d)
    }

    /** Otra sección, desde su raíz: lo que hubiera encima se cierra. */
    fun cambiar(s: Seccion) {
        destinos.clear()
        seccion = s
    }

    /** Un paso atrás; `false` si no hay a dónde (Inicio sin nada encima). */
    fun atras(): Boolean {
        if (destinos.isNotEmpty()) {
            destinos.removeAt(destinos.lastIndex)
            return true
        }
        if (seccion != Seccion.INICIO) {
            seccion = Seccion.INICIO
            return true
        }
        return false
    }

    companion object {
        /** `["PAREJAS", "ficha:vault", "log:vault"]`. Un nombre de pareja puede llevar «:»: solo se parte en el primero. */
        val Saver: Saver<Pila, List<String>> = Saver(
            save = { p -> listOf(p.seccion.name) + p.destinos.map(::codificar) },
            restore = { lista ->
                val seccion = Seccion.entries.firstOrNull { it.name == lista.firstOrNull() } ?: Seccion.INICIO
                Pila(seccion, lista.drop(1).mapNotNull(::decodificar))
            },
        )

        private fun codificar(d: Destino): String = when (d) {
            is Destino.Ficha -> "ficha:${d.pareja}"
            is Destino.Log -> "log:${d.pareja}"
            is Destino.Mover -> "mover:${d.pareja}"
        }

        private fun decodificar(texto: String): Destino? {
            val (tipo, pareja) = texto.split(":", limit = 2).takeIf { it.size == 2 } ?: return null
            return when (tipo) {
                "ficha" -> Destino.Ficha(pareja)
                "log" -> Destino.Log(pareja)
                "mover" -> Destino.Mover(pareja)
                else -> null
            }
        }
    }
}

/** La pila de la pantalla, guardada con la actividad. */
@Composable
fun rememberPila(): Pila = rememberSaveable(saver = Pila.Saver) { Pila() }

/**
 * La barra de las cuatro secciones, siempre las mismas. La elegida lleva la
 * pastilla `accent-soft`, el texto `accent-strong` y peso 600, y lo dice en su
 * semántica (`selected`): no solo con el color.
 */
@Composable
fun BarraSecciones(actual: Seccion, alElegir: (Seccion) -> Unit) {
    val c = Paleta.actual
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        HorizontalDivider(thickness = 1.dp, color = c.line)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            for (s in Seccion.entries) {
                val elegida = s == actual
                val color = if (elegida) c.accentStrong else c.ink2
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .selectable(selected = elegida, role = Role.Tab, onClick = { alElegir(s) })
                        .testTag(s.etiqueta),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Box(
                        Modifier
                            .width(56.dp)
                            .height(28.dp)
                            .clip(CircleShape)
                            .background(if (elegida) c.accentSoft else c.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides color) { Glifo(s.icono, tamano = 20.dp) }
                    }
                    Text(
                        s.titulo,
                        color = color,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = if (elegida) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * La cabecera de una pantalla. Sin [volver], la de una raíz: el título grande
 * y, debajo, el [pie] («4 de 6 del catálogo se usan aquí»). Con [volver], la de
 * un detalle, como el lienzo: arriba, el botón de volver de 48 dp con su glifo y
 * [etiquetaVolver] (el nombre de donde se vuelve: «Parejas», «vault»), y debajo
 * el título. [acciones] van a la derecha del título.
 */
@Composable
fun Cabecera(
    titulo: String,
    modifier: Modifier = Modifier,
    volver: (() -> Unit)? = null,
    etiquetaVolver: String? = null,
    pie: String? = null,
    pieMono: Boolean = false,
    acciones: @Composable RowScope.() -> Unit = {},
) {
    val c = Paleta.actual
    val letra = Letra.actual
    Column(modifier.fillMaxWidth()) {
        if (volver != null) {
            Row(
                Modifier
                    .padding(start = 4.dp)
                    .heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button, onClickLabel = "Volver", onClick = volver)
                        .testTag("volver")
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CompositionLocalProvider(LocalContentColor provides c.accent) {
                        Glifo(Icono.BACK, tamano = 20.dp, descripcion = if (etiquetaVolver == null) "Volver" else null)
                    }
                    if (etiquetaVolver != null) {
                        Text(etiquetaVolver, style = letra.nombre, color = c.accent)
                    }
                }
            }
        }
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = if (volver != null) 4.dp else 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(titulo, style = letra.titulo, color = c.ink, modifier = Modifier.semantics { heading() })
                if (pie != null) Text(pie, style = if (pieMono) letra.mono.copy(color = c.ink3) else letra.pista)
            }
            acciones()
        }
    }
}

/**
 * La app ya emparejada: la sección elegida, o lo que tiene encima, y la barra de
 * secciones abajo, fija. Lo que tiene que estar en todas las pantallas (abrir
 * la base del llavero cuando el modelo lo pide, el aviso de antes de abrirla)
 * va aquí y no en una sección: la que lo pidió puede no estar delante.
 */
@Composable
fun Secciones(m: Modelo) {
    val pila = rememberPila()
    BackHandler(enabled = pila.puedeVolver) { pila.atras() }
    // Al volver a la app (de la app de KeePass, de los ajustes), lo que se ve puede haber cambiado.
    LifecycleResumeEffect(Unit) {
        m.alVolver()
        onPauseOrDispose {}
    }
    Scaffold(
        containerColor = Paleta.actual.paper,
        bottomBar = { BarraSecciones(pila.seccion, pila::cambiar) },
    ) { margen ->
        Box(Modifier.padding(margen).fillMaxSize()) {
            when (val d = pila.cima) {
                is Destino.Ficha -> Box(Modifier.fillMaxSize().testTag("pantalla-pareja-${d.pareja}"))
                is Destino.Log -> Box(Modifier.fillMaxSize().testTag("pantalla-log-${d.pareja}"))
                is Destino.Mover -> Box(Modifier.fillMaxSize().testTag("pantalla-mover-${d.pareja}"))
                null -> when (pila.seccion) {
                    Seccion.INICIO -> Box(Modifier.fillMaxSize().testTag("pantalla-inicio")) { Principal(m) }
                    Seccion.PAREJAS -> ListaProvisional(m, pila::ir)
                    Seccion.LLAVERO -> Box(Modifier.fillMaxSize().testTag("pantalla-llavero"))
                    Seccion.AJUSTES -> Box(Modifier.fillMaxSize().testTag("pantalla-ajustes"))
                }
            }
        }
    }
    m.avisoLlavero?.let { AvisoAntesDeAbrir(it, m::cerrarAvisoLlavero) }
    AbrirLaBase(m)
}

/** Hasta que esté la sección Parejas: una fila por pareja, que lleva a su ficha. */
@Composable
private fun ListaProvisional(m: Modelo, ir: (Destino) -> Unit) {
    Column(Modifier.fillMaxSize().testTag("pantalla-parejas").verticalScroll(rememberScrollState())) {
        Cabecera("Parejas")
        Tarjeta(Modifier.padding(horizontal = 16.dp)) {
            for (f in m.filas) {
                Fila(f.nombre, Modifier.testTag("fila-${f.nombre}"), alPulsar = { ir(Destino.Ficha(f.nombre)) })
            }
        }
    }
}
