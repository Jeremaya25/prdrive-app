package prdrive.app.ui.parejas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import prdrive.app.Estado
import prdrive.app.ui.Letra
import prdrive.app.ui.Modelo
import prdrive.app.ui.Paleta
import prdrive.app.ui.piezas.Aviso
import prdrive.app.ui.piezas.Boton
import prdrive.app.ui.piezas.Glifo
import prdrive.app.ui.piezas.Hoja
import prdrive.app.ui.piezas.Icono
import prdrive.app.ui.piezas.Peso
import prdrive.app.ui.piezas.Rotulo
import prdrive.app.ui.piezas.TonoAviso

/**
 * RehacerReferencia.kt — La hoja que confirma rehacer la referencia de una
 * pareja (`--resync`): qué va a pasar antes de que pase, y por qué lo pide.
 * Nada se escribe hasta «Rehacer la referencia».
 *
 * [conRed] es el de la ficha, que sigue ahí cuando la hoja se cierra: si
 * Android pregunta por la red local, la respuesta tiene que tener a quién volver.
 */
@Composable
fun RehacerReferencia(m: Modelo, f: Estado.Fila, conRed: (() -> Unit) -> Unit, alCerrar: () -> Unit) {
    val c = Paleta.actual
    Hoja("Rehacer la referencia de «${f.nombre}»", alCerrar) {
        Aviso("Esto es lo que va a pasar. Nada se ha escrito todavía.", tono = TonoAviso.INFO)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Linea(Icono.BOTH, "Lo que solo está en un lado se copia al otro.")
            Linea(Icono.OK, "No se borra nada, en ningún lado.")
            Linea(Icono.SYNC, "Después, las pasadas vuelven a ser normales.")
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Rotulo("Por qué lo pide")
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(c.sunken)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                f.pideResync.forEach { Text(it, style = Letra.actual.texto, color = c.ink2) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Boton(
                "Rehacer la referencia",
                {
                    alCerrar()
                    conRed { m.aprobarResync(f.nombre) }
                },
                Modifier.testTag("confirmar-resync"),
                peso = Peso.PRINCIPAL,
                ancho = true,
                activo = m.ocupado == null,
            )
            Boton("Ahora no", alCerrar, peso = Peso.SILENCIOSO, ancho = true)
        }
    }
}

/** Una línea de lo que va a pasar, con su glifo. */
@Composable
private fun Linea(icono: Icono, texto: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CompositionLocalProvider(LocalContentColor provides Paleta.actual.ok) { Glifo(icono) }
        Text(texto, style = Letra.actual.texto, color = Paleta.actual.ink)
    }
}
