package prdrive.app.ui.parejas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import prdrive.app.Estado
import prdrive.app.ui.Letra
import prdrive.app.ui.Modelo
import prdrive.app.ui.Paleta
import prdrive.app.ui.piezas.Aviso
import prdrive.app.ui.piezas.Boton
import prdrive.app.ui.piezas.Casilla
import prdrive.app.ui.piezas.Hoja
import prdrive.app.ui.piezas.Icono
import prdrive.app.ui.piezas.Peso
import prdrive.app.ui.piezas.Progreso
import prdrive.app.ui.piezas.TonoAviso

/**
 * Quitar.kt — La hoja que confirma quitar una pareja de este teléfono: el
 * «Quitar…» del escritorio (`preguntar_limpieza()` y el plan de `plan_remove()`).
 * Los datos no se tocan, ni aquí ni en el remoto, y la pareja sigue en el
 * catálogo; la casilla aparta también su referencia y borra sus filtros
 * generados, desmarcada como allí. Una pareja en una carpeta compartida la
 * aparta siempre, y la casilla lo dice. Nada se escribe hasta «Quitar de este
 * teléfono»; lo que no se puede (la última pareja, una a medio mover) lo dice
 * el plan, y el botón no se enciende.
 */
@Composable
fun QuitarPareja(m: Modelo, f: Estado.Fila, alCerrar: () -> Unit) {
    val c = Paleta.actual
    var limpiar by rememberSaveable { mutableStateOf(false) }
    val compartida = f.compartida != null
    LaunchedEffect(limpiar) { m.pensarQuitar(f.nombre, limpiar) }
    val pensado = m.planDeQuitar?.takeIf { it.nombre == f.nombre && it.con == limpiar.toString() }
    Hoja("Quitar «${f.nombre}» de este teléfono", alCerrar) {
        Aviso("Esto es lo que va a pasar. Nada se ha escrito todavía.", tono = TonoAviso.INFO)
        Text(
            "Los datos NO se tocan y la pareja sigue en el catálogo: solo deja de sincronizarse aquí.",
            style = Letra.actual.texto,
            color = c.ink2,
        )
        Casilla(
            "Apartar también su referencia y borrar sus filtros generados",
            marcada = limpiar || compartida,
            alCambiar = { limpiar = it },
            modifier = Modifier.testTag("limpiar-${f.nombre}"),
            pista = if (compartida) {
                "Vive en una carpeta compartida: su referencia se aparta siempre."
            } else {
                "La referencia se aparta en state/<pareja>.old-<fecha>/; no se borra."
            },
            activa = !compartida,
        )
        when {
            pensado == null -> Progreso(null, texto = "Mirando lo que tiene…")
            pensado.error != null -> Aviso(
                "No se puede quitar",
                Modifier.testTag("quitar-no-${f.nombre}"),
                tono = TonoAviso.PELIGRO,
                cuerpo = pensado.error,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pensado.plan?.todasLasConsecuencias?.forEachIndexed { i, frase ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${i + 1}.", style = Letra.actual.nombre, color = c.ink3)
                        Text(frase, style = Letra.actual.texto, color = c.ink)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Boton(
                "Quitar de este teléfono",
                {
                    alCerrar()
                    m.quitar(f.nombre, limpiar || compartida)
                },
                Modifier.testTag("confirmar-quitar"),
                peso = Peso.PELIGRO_SOLIDO,
                icono = Icono.TRASH,
                ancho = true,
                activo = m.ocupado == null && pensado?.plan != null,
            )
            Boton("Ahora no", alCerrar, peso = Peso.SILENCIOSO, ancho = true)
        }
    }
}
