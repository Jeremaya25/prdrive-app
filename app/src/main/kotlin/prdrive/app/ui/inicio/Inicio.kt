package prdrive.app.ui.inicio

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import prdrive.app.Actualizador
import prdrive.app.Estado
import prdrive.app.segundoplano.SegundoPlano
import prdrive.app.ui.Destino
import prdrive.app.ui.Letra
import prdrive.app.ui.Modelo
import prdrive.app.ui.Paleta
import prdrive.app.ui.Seccion
import prdrive.app.ui.TextosLlavero
import prdrive.app.ui.conRedLocal
import prdrive.app.ui.flecha
import prdrive.app.ui.haceCuanto
import prdrive.app.ui.rutaCorta
import prdrive.app.ui.piezas.Aviso
import prdrive.app.ui.piezas.Boton
import prdrive.app.ui.piezas.Chip
import prdrive.app.ui.piezas.Fila
import prdrive.app.ui.piezas.FilaDeAviso
import prdrive.app.ui.piezas.Glifo
import prdrive.app.ui.piezas.Icono
import prdrive.app.ui.piezas.Insignia
import prdrive.app.ui.piezas.LineaDeEstado
import prdrive.app.ui.piezas.Marca
import prdrive.app.ui.piezas.Peso
import prdrive.app.ui.piezas.Progreso
import prdrive.app.ui.piezas.Rotulo
import prdrive.app.ui.piezas.Separador
import prdrive.app.ui.piezas.Tamano
import prdrive.app.ui.piezas.Tarjeta
import prdrive.app.ui.piezas.Tono
import prdrive.app.ui.piezas.TonoAviso
import prdrive.engine.LLAVERO
import prdrive.engine.MODES

/**
 * Inicio.kt — Lo primero que se ve: cómo está todo en una frase («Todo al día»,
 * «Hay 3 cosas que revisar» o lo que se está haciendo), la acción del día
 * («Sincronizar ahora»), lo que hay que revisar (cada cosa lleva a su arreglo),
 * el llavero y las parejas. Los tres Inicio del lienzo del rediseño.
 *
 * Solo dibuja y llama al [Modelo]. Lo que va arriba de toda sección (el error,
 * el permiso de red local) lo pone `Secciones`.
 */
@Composable
fun Inicio(m: Modelo, ir: (Destino) -> Unit, aSeccion: (Seccion) -> Unit) {
    val conRed = conRedLocal()
    val cosas = remember(m.filas, m.llavero, m.resultados) { revisar(m.filas, m.llavero, m.resultados) }
    val libre = m.ocupado == null
    Column(
        Modifier
            .fillMaxSize()
            .testTag("pantalla-inicio")
            .verticalScroll(rememberScrollState()),
    ) {
        CabeceraDeInicio(m, cosas)
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            EstadoGeneral(m, cosas) { conRed { m.sincronizar() } }
            m.enCurso?.takeIf { it.orden.size > 1 }?.let { EstaPasada(it) }
            if (m.sola.activo && m.ocupado == null) Sola(m)
            if (cosas.isNotEmpty()) {
                Tarjeta {
                    cosas.forEachIndexed { i, cosa ->
                        if (i > 0) Separador()
                        FilaDeAviso(
                            cosa.titulo,
                            cosa.cuerpo,
                            cosa.tono,
                            alPulsar = { cosa.destino?.let(ir) ?: cosa.seccion?.let(aSeccion) },
                            modifier = Modifier.testTag("revisar-$i"),
                        )
                    }
                }
            }
            m.llavero?.let { f ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Rotulo("Llavero")
                    LineaDeEstado(
                        Icono.LLAVE,
                        TextosLlavero.linea(f),
                        Modifier.testTag("fila-llavero"),
                        accion = when {
                            f.activo -> "Abrir llavero"
                            f.traer -> "Traer…"
                            else -> null
                        },
                        alPulsar = { if (f.activo) conRed(m::abrirLlavero) else aSeccion(Seccion.LLAVERO) },
                        aviso = f.ambar,
                        etiquetaAccion = if (f.activo) "abrir-llavero" else "ir-llavero",
                        activa = libre,
                    )
                }
            }
            VersionNueva(libre)
            if (m.filas.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Rotulo("Parejas", Modifier.weight(1f))
                        Boton("Ver todas", { aSeccion(Seccion.PAREJAS) }, peso = Peso.SILENCIOSO, tamano = Tamano.NORMAL)
                    }
                    Tarjeta {
                        m.filas.forEachIndexed { i, f ->
                            if (i > 0) Separador()
                            FilaDePareja(f, m, ir)
                        }
                    }
                }
            }
        }
    }
}

/** La marca con su estado, «Este teléfono» y el remoto. */
@Composable
private fun CabeceraDeInicio(m: Modelo, cosas: List<Revisar>) {
    val insignia = when {
        m.ocupado != null -> Insignia.SINCRONIZANDO
        cosas.isNotEmpty() -> Insignia.AVISO
        else -> Insignia.NINGUNA
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Marca(40.dp, insignia = insignia)
        Column(Modifier.weight(1f)) {
            Text("Este teléfono", style = Letra.actual.barra, color = Paleta.actual.ink)
            m.remoto?.let { Text(it, style = Letra.actual.mono, color = Paleta.actual.ink3) }
        }
    }
}

/**
 * Cómo está todo, en una frase, con su chip, y «Sincronizar ahora». Con algo en
 * marcha, el título es lo que se hace (con la etiqueta `ocupado`) y debajo va su
 * progreso; el botón sigue ahí, ocupado: un segundo toque no hace nada.
 */
@Composable
private fun EstadoGeneral(m: Modelo, cosas: List<Revisar>, sincronizar: () -> Unit) {
    val c = Paleta.actual
    val letra = Letra.actual
    val contexto = LocalContext.current
    val forma = RoundedCornerShape(4.dp)
    val ocupado = m.ocupado
    Column(
        Modifier
            .fillMaxWidth()
            .clip(forma)
            .background(c.surface)
            .border(1.dp, c.line, forma)
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val n = cosas.size
        when {
            ocupado != null -> {
                Chip("en marcha", tono = Tono.EN_MARCHA)
                val curso = m.enCurso
                val titulo = if (curso != null && curso.orden.size > 1 && ocupado != Modelo.PARANDO) {
                    "Sincronizando ${minOf(curso.hechas.size + 1, curso.orden.size)} de ${curso.orden.size}"
                } else {
                    ocupado
                }
                Text(
                    titulo,
                    style = letra.titulo,
                    color = c.ink,
                    modifier = Modifier.testTag("ocupado").semantics { heading() },
                )
                val p = m.progreso
                val tamano = { b: Long -> Formatter.formatShortFileSize(contexto, b) }
                Progreso(
                    p?.fraccion,
                    texto = p?.let {
                        "${it.pareja}: ${it.ficheros} de ${it.totalFicheros} ficheros · " +
                            "${tamano(it.bytes)} de ${tamano(it.totalBytes)} · ${tamano(it.velocidad.toLong())}/s"
                    },
                )
            }
            n > 0 -> {
                Chip(if (n == 1) "1 cosa" else "$n cosas", tono = Tono.AVISO)
                Text(
                    if (n == 1) "Hay 1 cosa que revisar" else "Hay $n cosas que revisar",
                    style = letra.titulo,
                    color = c.ink,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "Lo demás está al día. Estas no se arreglan solas: cada una te lleva a su arreglo.",
                    style = letra.texto,
                    color = c.ink2,
                )
            }
            else -> {
                Chip("al día", tono = Tono.OK)
                Text("Todo al día", style = letra.titulo, color = c.ink, modifier = Modifier.semantics { heading() })
                val ultima = (m.filas.mapNotNull { it.ultimaBuena } + listOfNotNull(m.llavero?.ultimaBuena)).maxOrNull()
                Text(
                    if (ultima != null) "Última pasada ${haceCuanto(ultima)}." else "Todavía sin ninguna pasada.",
                    style = letra.texto,
                    color = c.ink2,
                )
            }
        }
        if (m.sePuedeParar || ocupado == Modelo.PARANDO) {
            // En marcha, la acción es pararla: sincronizar otra vez no haría nada.
            Boton(
                "Parar la pasada",
                m::parar,
                Modifier.testTag("parar"),
                peso = Peso.CONTORNO,
                icono = Icono.PAUSA,
                ancho = true,
                ocupado = ocupado == Modelo.PARANDO,
            )
            Text(
                "Parar espera a que rclone suelte la pareja. Si se queda a medias, la próxima te pedirá rehacer la " +
                    "referencia, que no borra nada.",
                style = letra.pista,
            )
        } else {
            Boton(
                "Sincronizar ahora",
                sincronizar,
                Modifier.testTag("sincronizar"),
                peso = Peso.PRINCIPAL,
                icono = Icono.SYNC,
                ancho = true,
                ocupado = ocupado != null,
            )
        }
    }
}

/** «Sola cada hora, con wifi. La próxima, en 55 min.»: cuándo vuelve a sincronizar sin abrir la app. */
@Composable
private fun Sola(m: Modelo) {
    val a = m.sola
    val condiciones = listOfNotNull(if (a.soloWifi) "con wifi" else null, if (a.soloCargando) "mientras carga" else null)
    val proxima = m.proximaSola?.let { (it - System.currentTimeMillis()) / 60_000 }?.takeIf { it >= 0 }?.let {
        when {
            it < 1 -> " La próxima, en un momento."
            it < 60 -> " La próxima, en $it min."
            else -> " La próxima, en ${it / 60} h."
        }
    } ?: ""
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("proxima"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides Paleta.actual.ink3) { Glifo(Icono.CLOCK) }
        Text(
            "Sola ${SegundoPlano.cadaCuanto(m.intervaloSola)}" +
                (if (condiciones.isEmpty()) "" else ", " + condiciones.joinToString(" y ")) + "." + proxima,
            style = Letra.actual.pista,
        )
    }
}

/** «Esta pasada»: las parejas de la pasada en marcha, por orden, cada una con cómo va. */
@Composable
private fun EstaPasada(curso: Modelo.EnCurso) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Rotulo("Esta pasada")
        Tarjeta {
            curso.orden.forEachIndexed { i, nombre ->
                if (i > 0) Separador()
                val hecha = curso.hechas[nombre]
                val (tono, palabra) = when {
                    hecha?.ok == true -> Tono.OK to "hecha"
                    hecha?.saltada == true -> Tono.NEUTRO to "saltada"
                    hecha != null -> Tono.PELIGRO to "falló"
                    nombre == curso.ahora -> Tono.EN_MARCHA to "en marcha"
                    nombre == LLAVERO -> Tono.APAGADO to "al final"
                    else -> Tono.APAGADO to "en espera"
                }
                Fila(
                    if (nombre == LLAVERO) "El llavero" else nombre,
                    Modifier.testTag("en-curso-$nombre"),
                    icono = if (nombre == LLAVERO) Icono.LLAVE else Icono.PAREJAS,
                    extremo = { Chip(palabra, tono = tono) },
                )
            }
        }
    }
}

/** Una pareja en la lista de Inicio: su modo, sus dos carpetas y su estado; lleva a su ficha. */
@Composable
private fun FilaDePareja(f: Estado.Fila, m: Modelo, ir: (Destino) -> Unit) {
    val (tono, palabra) = estadoDe(f, m.resultados[f.nombre])
    val local = f.compartida?.let(::rutaCorta) ?: f.local
    Fila(
        f.nombre,
        Modifier.testTag("fila-${f.nombre}"),
        alPulsar = { ir(Destino.Ficha(f.nombre)) },
        icono = iconoDelModo(f.modo),
        detalle = "$local ${flecha(f.modo)} ${f.remoto}",
        detalleMono = true,
        extremo = { Chip(palabra, tono = tono) },
    )
}

/** El glifo del modo: los dos sentidos, de subida o de bajada. */
internal fun iconoDelModo(modo: String): Icono {
    val m = MODES[modo] ?: return Icono.BOTH
    return when {
        m.isBisync -> Icono.BOTH
        m.origenLocal -> Icono.UP
        else -> Icono.DOWN
    }
}

/**
 * La versión nueva de la app, si la hay: cómo va ([Actualizador.fase]) y lo que
 * se puede hacer con ella. Sola, se baja al abrir y se instala al salir; los
 * botones son para no esperar, o para cuando Android pide que se confirme.
 */
@Composable
private fun VersionNueva(libre: Boolean) {
    val rel = Actualizador.nueva ?: return
    val contexto = LocalContext.current
    val c = Paleta.actual
    // El permiso de instalar apps se da en los ajustes: al volver, puede haber cambiado. Y un intento
    // sin preguntar que no sale cambia «se instalará sola» por el botón.
    var vueltas by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        vueltas++
        onPauseOrDispose {}
    }
    val sola = remember(vueltas, Actualizador.fallosSinPreguntar) { Actualizador.seInstalaSola(contexto) }
    val faltaPermiso = remember(vueltas) { Actualizador.faltaPermiso(contexto) }
    val tamano = { n: Long -> Formatter.formatShortFileSize(contexto, n) }
    val pista = Letra.actual.pista
    Aviso(
        "Versión nueva: ${rel.tag}",
        Modifier.testTag("version-nueva"),
        tono = TonoAviso.INFO,
        extra = {
            when (Actualizador.fase) {
                Actualizador.Fase.DESCARGANDO -> {
                    val total = rel.apk?.tamano ?: 0L
                    val hecho = Actualizador.bajado
                    Progreso(
                        if (total > 0) (hecho.toFloat() / total).coerceIn(0f, 1f) else null,
                        texto = "Bajándola: ${tamano(hecho)} de ${tamano(total)}",
                    )
                }
                Actualizador.Fase.LISTA -> {
                    Text(
                        if (sola) "Lista. Se instalará sola cuando salgas de la app." else "Lista para instalar.",
                        style = Letra.actual.texto,
                        color = c.ink2,
                        modifier = Modifier.testTag("version-lista"),
                    )
                    Text("Al instalarse, la app se cierra: vuelve a abrirla después.", style = pista)
                    if (faltaPermiso) Text("Para que se instale sola, sin preguntar, permite que prdrive instale apps.", style = pista)
                }
                Actualizador.Fase.INSTALANDO -> Text("Instalando… la app se cerrará en un momento.", style = Letra.actual.texto, color = c.ink2)
                Actualizador.Fase.CONFIRMAR -> Text("Android pide que confirmes la instalación.", style = Letra.actual.texto, color = c.ink2)
                Actualizador.Fase.QUIETA, Actualizador.Fase.BUSCANDO ->
                    Actualizador.espera?.let { Text(it, style = pista) }
            }
            Actualizador.aviso?.let { Text(it, style = pista) }
        },
    ) {
        when (Actualizador.fase) {
            Actualizador.Fase.LISTA, Actualizador.Fase.CONFIRMAR -> {
                Boton(
                    if (Actualizador.fase == Actualizador.Fase.LISTA) "Instalar ahora" else "Instalar",
                    { Actualizador.instalarAhora(contexto) },
                    Modifier.testTag("instalar-version"),
                    peso = Peso.PRINCIPAL,
                    tamano = Tamano.PEQUENO,
                    activo = libre,
                )
                if (faltaPermiso && Actualizador.fase == Actualizador.Fase.LISTA) {
                    Boton(
                        "Permitir",
                        {
                            contexto.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${contexto.packageName}")),
                            )
                        },
                        tamano = Tamano.PEQUENO,
                    )
                }
            }
            Actualizador.Fase.QUIETA, Actualizador.Fase.BUSCANDO -> rel.apk?.let { apk ->
                Boton("Descargar (${tamano(apk.tamano)})", { Actualizador.descargarAhora(contexto) }, tamano = Tamano.PEQUENO)
            }
            else -> Unit
        }
        Boton("Novedades", { abrir(contexto, rel.url) }, peso = Peso.SILENCIOSO, tamano = Tamano.PEQUENO)
    }
}

/** Abre una página en el navegador. */
private fun abrir(contexto: android.content.Context, url: String) {
    try {
        contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: android.content.ActivityNotFoundException) {
        // Un teléfono sin navegador: no hay a dónde ir.
    }
}
