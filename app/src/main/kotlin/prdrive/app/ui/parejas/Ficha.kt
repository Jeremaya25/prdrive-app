package prdrive.app.ui.parejas

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import prdrive.app.Estado
import prdrive.app.Prdrive
import prdrive.app.ui.Cabecera
import prdrive.app.ui.Destino
import prdrive.app.ui.Letra
import prdrive.app.ui.Modelo
import prdrive.app.ui.Paleta
import prdrive.app.ui.conRedLocal
import prdrive.app.ui.darPermisoCompartido
import prdrive.app.ui.haceCuanto
import prdrive.app.ui.inicio.estadoDe
import prdrive.app.ui.inicio.iconoDelModo
import prdrive.app.ui.piezas.Aviso
import prdrive.app.ui.piezas.Boton
import prdrive.app.ui.piezas.Chip
import prdrive.app.ui.piezas.FilaDeAviso
import prdrive.app.ui.piezas.Glifo
import prdrive.app.ui.piezas.Icono
import prdrive.app.ui.piezas.Peso
import prdrive.app.ui.piezas.Rotulo
import prdrive.app.ui.piezas.Separador
import prdrive.app.ui.piezas.Tamano
import prdrive.app.ui.piezas.Tarjeta
import prdrive.app.ui.piezas.TonoAviso
import prdrive.app.ui.rutaCorta
import prdrive.engine.Pasada

/**
 * Ficha.kt — Una pareja: cómo está, sincronizarla sola, lo que hay que
 * arreglar (rehacer la referencia, sus conflictos, su último fallo), cómo se
 * sincroniza y dónde vive en este teléfono, con lo que se puede hacer con su
 * carpeta (moverla a una carpeta compartida, terminar de moverla, devolverla
 * al volumen). Es «Una pareja · detalle» del lienzo y sus tres variantes.
 *
 * Con un trabajo en marcha, todo lo que cambia algo se apaga: el modelo ya
 * ignora el segundo toque, pero la pantalla no debe parecer que lo va a hacer.
 */
@Composable
fun Ficha(
    m: Modelo,
    nombre: String,
    ir: (Destino) -> Unit,
    volver: () -> Unit,
    etiquetaVolver: String = "Parejas",
    rehacer: (Estado.Fila) -> Unit = {},
) {
    val conRed = conRedLocal()
    val f = m.filas.firstOrNull { it.nombre == nombre }
    val libre = m.ocupado == null
    Column(
        Modifier
            .fillMaxSize()
            .testTag("pantalla-pareja-$nombre")
            .verticalScroll(rememberScrollState()),
    ) {
        Cabecera(nombre, volver = volver, etiquetaVolver = etiquetaVolver)
        if (f == null) {
            Text(
                "Esta pareja ya no está en este teléfono.",
                style = Letra.actual.texto,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return@Column
        }
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val (tono, palabra) = estadoDe(f, m.resultados[f.nombre])
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(palabra, tono = tono)
                    Text(
                        f.ultimaBuena?.let { "Última pasada buena ${haceCuanto(it)}" } ?: "Sin ninguna pasada buena todavía",
                        style = Letra.actual.pista,
                    )
                }
                Boton(
                    "Sincronizar esta pareja",
                    { conRed { m.sincronizar(solo = nombre) } },
                    Modifier.testTag("sincronizar-$nombre"),
                    peso = Peso.PRINCIPAL,
                    icono = Icono.SYNC,
                    ancho = true,
                    activo = libre && !f.entrando,
                )
            }
            Arreglos(m, f, libre, rehacer)
            Bloque("Cómo se sincroniza") { ComoSeSincroniza(f) }
            Bloque("Dónde vive en este teléfono") { DondeVive(m, f, libre, ir, conRed) }
            if (f.fallo?.log != null) {
                Boton(
                    "Ver el log",
                    { ir(Destino.Log(nombre)) },
                    Modifier.testTag("ver-log-$nombre"),
                    icono = Icono.FILE,
                    ancho = true,
                )
            }
        }
    }
}

/** Un bloque de la ficha: su rótulo y, debajo, lo suyo. */
@Composable
private fun Bloque(titulo: String, contenido: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Rotulo(titulo)
        contenido()
    }
}

/**
 * Lo que hay que arreglar, si hay algo: el último fallo, rehacer la referencia
 * (que abre su hoja, [rehacer]) y los ficheros en conflicto.
 */
@Composable
private fun Arreglos(m: Modelo, f: Estado.Fila, libre: Boolean, rehacer: (Estado.Fila) -> Unit) {
    val fallo = f.fallo
    val pideResync = f.pideResync.isNotEmpty() && m.resultados[f.nombre]?.ok != true && !f.entrando
    if (fallo == null && !pideResync && f.conflictos.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (fallo != null) {
            Aviso("Falló el ${fallo.cuando}", tono = TonoAviso.PELIGRO, cuerpo = f.explicacion)
        }
        if (pideResync) {
            Tarjeta {
                FilaDeAviso(
                    "Pide rehacer la referencia",
                    "No borra nada. Revisa antes lo que va a pasar.",
                    TonoAviso.AVISO,
                    alPulsar = { rehacer(f) },
                    modifier = Modifier.testTag("resync-${f.nombre}"),
                    activa = libre,
                )
            }
        }
        if (f.conflictos.isNotEmpty()) {
            val n = f.conflictos.size
            Tarjeta {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (n == 1) "1 fichero en conflicto" else "$n ficheros en conflicto",
                        style = Letra.actual.nombre,
                    )
                    Text(
                        "Cambiaron en los dos lados y hay dos versiones: la de cada lado se queda con su nombre y la " +
                            "otra, al lado, como copia de conflicto.",
                        style = Letra.actual.texto,
                        color = Paleta.actual.ink2,
                    )
                    f.conflictos.take(Pasada.CONFLICTS_SHOWN).forEach {
                        Text(it.relativa, style = Letra.actual.mono, color = Paleta.actual.ink2)
                    }
                    if (n > Pasada.CONFLICTS_SHOWN) {
                        Text("y ${n - Pasada.CONFLICTS_SHOWN} más", style = Letra.actual.pista)
                    }
                }
            }
        }
    }
}

/** El modo en palabras, y las dos carpetas en monoespaciada. */
@Composable
private fun ComoSeSincroniza(f: Estado.Fila) {
    val c = Paleta.actual
    val (titulo, cuerpo) = comoSeSincroniza(f.modo)
    Tarjeta {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Baldosa(iconoDelModo(f.modo), c.sunken, c.ink2)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(titulo, style = Letra.actual.nombre)
                    Text(f.modo, style = Letra.actual.mono, color = c.ink3)
                }
                Text(cuerpo, style = Letra.actual.pista, color = c.ink2)
            }
        }
        Separador()
        Dato("En el teléfono", f.compartida?.let(::rutaCorta) ?: f.local)
        Separador()
        Dato("En el remoto", f.remoto)
    }
}

/** Un dato de la ficha: su nombre y su valor, en monoespaciada. */
@Composable
private fun Dato(nombre: String, valor: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(nombre, style = Letra.actual.pista)
        Text(valor, style = Letra.actual.mono, color = Paleta.actual.ink2)
    }
}

/** La baldosa de 32 dp de un bloque, con su glifo. */
@Composable
private fun Baldosa(icono: Icono, fondo: Color, glifo: Color) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)).background(fondo),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides glifo) { Glifo(icono) }
    }
}

/**
 * Dónde vive la pareja en este teléfono, en una de las variantes del lienzo: en
 * una carpeta compartida (con terminar de moverla o volver al volumen), dentro
 * de la app y movible, o dentro de la app y sin poder moverse, con el porqué.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DondeVive(m: Modelo, f: Estado.Fila, libre: Boolean, ir: (Destino) -> Unit, conRed: (() -> Unit) -> Unit) {
    val c = Paleta.actual
    val contexto = LocalContext.current
    val n = f.nombre
    Tarjeta {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val compartida = f.compartida
            if (compartida != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Baldosa(Icono.CARPETA, c.accentSoft, c.accentStrong)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("En una carpeta compartida", style = Letra.actual.nombre)
                        Text(
                            rutaCorta(compartida),
                            style = Letra.actual.mono,
                            color = c.ink2,
                            modifier = Modifier.testTag("donde-$n"),
                        )
                        Text(
                            "Otras apps, como Obsidian, la abren. Lo demás de prdrive sigue privado.",
                            style = Letra.actual.pista,
                            color = c.ink2,
                        )
                    }
                }
                f.sinAcceso?.let { motivo ->
                    Aviso("No ve su carpeta", tono = TonoAviso.PELIGRO, cuerpo = motivo) {
                        // Solo el permiso se da con un botón: un almacenamiento sin montar, no.
                        if (motivo == Prdrive.SIN_PERMISO_COMPARTIDO && Build.VERSION.SDK_INT >= 30) {
                            Boton(
                                "Dar permiso",
                                { darPermisoCompartido(contexto, m) },
                                Modifier.testTag("permiso-$n"),
                                tamano = Tamano.PEQUENO,
                            )
                        }
                    }
                }
                f.problemaCompartida?.let {
                    Aviso("Ha vuelto a su carpeta del volumen", tono = TonoAviso.AVISO, cuerpo = it)
                }
                // A medio mover solo se ofrece terminar: pasarla con una pasada normal o
                // con un --resync cualquiera la cogería con la carpeta a medio llenar.
                if (f.entrando) {
                    Aviso(
                        "A medio mover",
                        tono = TonoAviso.AVISO,
                        cuerpo = "No se sincroniza con las demás hasta que la termines.",
                    ) {
                        Boton(
                            "Terminar de mover",
                            { conRed { m.terminarDeMover(n) } },
                            Modifier.testTag("terminar-$n"),
                            tamano = Tamano.PEQUENO,
                            sobreAmbar = true,
                            activo = libre,
                        )
                    }
                }
                if (!f.entrando || f.problemaCompartida != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Boton(
                            "Volver al volumen",
                            { conRed { m.volverAlVolumen(n) } },
                            Modifier.testTag("volver-$n"),
                            activo = libre,
                        )
                    }
                    Text(
                        "Al volver, la carpeta compartida se queda como está: bórrala tú cuando ya no la quieras.",
                        style = Letra.actual.pista,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Baldosa(Icono.CARPETA, c.sunken, c.ink2)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Dentro de la app", style = Letra.actual.nombre)
                        Text(
                            if (f.sePuedeCompartir) {
                                "Solo la ven prdrive y la app de Archivos. Para que otra app, como Obsidian, la abra, " +
                                    "muévela a una carpeta compartida."
                            } else {
                                "Solo la ven prdrive y la app de Archivos."
                            },
                            style = Letra.actual.pista,
                            color = c.ink2,
                        )
                    }
                }
                if (f.sePuedeCompartir) {
                    Boton(
                        "Mover a una carpeta compartida…",
                        { ir(Destino.Mover(n)) },
                        Modifier.testTag("mover-$n"),
                        peso = Peso.TONAL,
                        icono = Icono.CARPETA,
                        activo = libre,
                    )
                }
                f.porQueNoSeComparte?.let {
                    Aviso(
                        "No se puede mover a una carpeta compartida",
                        Modifier.testTag("no-compartir-$n"),
                        tono = TonoAviso.AVISO,
                        cuerpo = it,
                    )
                }
            }
        }
    }
}

/** Cómo se sincroniza un modo, en palabras: el título y lo que pasa con lo que cambia y con lo que se borra. */
internal fun comoSeSincroniza(modo: String): Pair<String, String> = when (modo) {
    "bisync" -> "En los dos sentidos" to "Lo que cambias en un lado cambia en el otro, también los borrados."
    "up" -> "De subida" to "Lo que cambia en el teléfono se copia al remoto. No baja nada y no se borra nada."
    "down" -> "De bajada" to "Lo que cambia en el remoto se copia al teléfono. No sube nada y no se borra nada."
    "up-mirror" -> "Espejo de subida" to
        "Espejo: un lado manda y el otro se iguala a él, borrados incluidos. Aquí manda el teléfono."
    "down-mirror" -> "Espejo de bajada" to
        "Espejo: un lado manda y el otro se iguala a él, borrados incluidos. Aquí manda el remoto."
    else -> modo to ""
}
