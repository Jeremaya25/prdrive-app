package prdrive.app.ui.parejas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import prdrive.app.Estado
import prdrive.app.ui.Cabecera
import prdrive.app.ui.Destino
import prdrive.app.ui.Letra
import prdrive.app.ui.Modelo
import prdrive.app.ui.flecha
import prdrive.app.ui.haceCuanto
import prdrive.app.ui.inicio.estadoDe
import prdrive.app.ui.inicio.iconoDelModo
import prdrive.app.ui.piezas.Chip
import prdrive.app.ui.piezas.Fila
import prdrive.app.ui.piezas.Rotulo
import prdrive.app.ui.piezas.Separador
import prdrive.app.ui.piezas.Tarjeta
import prdrive.app.ui.piezas.Tono
import prdrive.app.ui.rutaCorta

/**
 * Parejas.kt — La sección Parejas: las del teléfono, una fila por pareja con
 * sus dos carpetas, su modo y su estado, que lleva a su ficha; y debajo, las del
 * catálogo que este teléfono no usa, en cursiva y con «no se usa aquí» (no se
 * limitan a ponerse grises). «Añadir…» llega con la fase 5 (#25).
 */
@Composable
fun Parejas(m: Modelo, ir: (Destino) -> Unit) {
    val n = m.filas.size
    val total = n + m.sinUsar.size
    Column(
        Modifier
            .fillMaxSize()
            .testTag("pantalla-parejas")
            .verticalScroll(rememberScrollState()),
    ) {
        Cabecera(
            "Parejas",
            pie = if (m.sinUsar.isEmpty()) {
                if (n == 1) "1 pareja en este teléfono." else "$n parejas en este teléfono."
            } else {
                "$n de $total del catálogo se usan aquí."
            },
        )
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (m.filas.isEmpty()) {
                Text("Este teléfono no sincroniza ninguna pareja todavía.", style = Letra.actual.texto)
            } else {
                Tarjeta {
                    m.filas.forEachIndexed { i, f ->
                        if (i > 0) Separador()
                        val (tono, palabra) = estadoDe(f, m.resultados[f.nombre])
                        Fila(
                            f.nombre,
                            Modifier.testTag("fila-${f.nombre}"),
                            alPulsar = { ir(Destino.Ficha(f.nombre)) },
                            icono = iconoDelModo(f.modo),
                            detalle = "${f.local} ${flecha(f.modo)} ${f.remoto}",
                            detalleMono = true,
                            pista = pistaDe(f),
                            extremo = { Chip(palabra, tono = tono) },
                        )
                    }
                }
            }
            if (m.sinUsar.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Rotulo("Del catálogo, sin usar aquí")
                    Tarjeta {
                        m.sinUsar.forEachIndexed { i, nombre ->
                            if (i > 0) Separador()
                            Fila(
                                nombre,
                                Modifier.testTag("sin-usar-$nombre"),
                                cursiva = true,
                                extremo = { Chip("no se usa aquí", tono = Tono.APAGADO) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** La pista de una fila: dónde vive si está en una carpeta compartida; si no, su modo y su última pasada buena. */
private fun pistaDe(f: Estado.Fila): String {
    f.compartida?.let { return "En ${rutaCorta(it)}, fuera de la app" }
    val cuando = f.ultimaBuena?.let(::haceCuanto)
    return palabraDelModo(f.modo) + (cuando?.let { " · $it" } ?: "")
}

/** El modo en palabras, para una fila: «bisync», «subida», «espejo de bajada · el remoto manda». */
internal fun palabraDelModo(modo: String): String = when (modo) {
    "bisync" -> "bisync"
    "up" -> "subida"
    "down" -> "bajada"
    "up-mirror" -> "espejo de subida · el teléfono manda"
    "down-mirror" -> "espejo de bajada · el remoto manda"
    else -> modo
}
