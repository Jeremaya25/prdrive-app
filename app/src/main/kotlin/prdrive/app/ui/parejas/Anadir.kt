package prdrive.app.ui.parejas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import prdrive.app.ui.Cabecera
import prdrive.app.ui.Letra
import prdrive.app.ui.Modelo
import prdrive.app.ui.Paleta
import prdrive.app.ui.inicio.iconoDelModo
import prdrive.app.ui.piezas.Aviso
import prdrive.app.ui.piezas.Boton
import prdrive.app.ui.piezas.Chip
import prdrive.app.ui.piezas.Fila
import prdrive.app.ui.piezas.Icono
import prdrive.app.ui.piezas.Peso
import prdrive.app.ui.piezas.Progreso
import prdrive.app.ui.piezas.Rotulo
import prdrive.app.ui.piezas.Separador
import prdrive.app.ui.piezas.Tarjeta
import prdrive.app.ui.piezas.Tono
import prdrive.app.ui.piezas.TonoAviso
import prdrive.engine.EditarParejas
import prdrive.engine.MODES

/**
 * Anadir.kt — Añadir a este teléfono una pareja del catálogo: cómo se
 * sincroniza, la carpeta del teléfono donde va (la elige la persona, como pide
 * prdrive#196; el `local` del catálogo solo es la sugerencia), cómo está esa
 * carpeta, lo que se avisa y qué va a pasar. Nada se escribe hasta «Añadir»;
 * con la pareja ya en el teléfono, se va a su ficha.
 *
 * Lo que va a pasar lo piensa el modelo cada vez que cambia la carpeta
 * ([Modelo.pensarAnadir], con la copia del catálogo y sin red), y vuelve a
 * pensarlo cuando acaba un trabajo: un «Añadir» que se ha parado porque el
 * catálogo había cambiado ya ha dejado su copia nueva. Lo escrito se guarda con
 * la actividad: es el nombre de una carpeta, nada que no pueda acabar en disco.
 * [conRed] es el de `Secciones`: añadir lee el catálogo del remoto.
 */
@Composable
fun AnadirPareja(
    m: Modelo,
    nombre: String,
    volver: () -> Unit,
    aSuFicha: () -> Unit,
    conRed: (() -> Unit) -> Unit,
) {
    val c = Paleta.actual
    val pareja = m.sinUsar.firstOrNull { it.nombre == nombre }
    var escrita by rememberSaveable { mutableStateOf<String?>(null) }
    val carpeta = escrita ?: pareja?.sugerencia ?: nombre
    val libre = m.ocupado == null
    // Un poco después de dejar de escribir, y otra vez al acabar un trabajo.
    LaunchedEffect(carpeta, libre, pareja != null) {
        if (!libre || pareja == null) return@LaunchedEffect
        delay(PAUSA_AL_ESCRIBIR_MS)
        m.pensarAnadir(nombre, carpeta)
    }
    LaunchedEffect(m.recienAnadida) {
        if (m.recienAnadida == nombre) {
            m.vista(nombre)
            aSuFicha()
        }
    }
    val pensado = m.planDeAnadir?.takeIf { it.nombre == nombre && it.con == carpeta }
    val plan = pensado?.plan
    Column(
        Modifier
            .fillMaxSize()
            .testTag("pantalla-anadir-$nombre")
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Cabecera("Añadir «$nombre»", volver = volver, etiquetaVolver = "Parejas")
        if (pareja == null) {
            val texto = when {
                !m.filasLeidas -> null
                m.filas.any { it.nombre == nombre } -> "«$nombre» ya está en este teléfono."
                else -> "«$nombre» ya no está en la copia del catálogo. Vuelve a leerlo desde Parejas."
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (texto == null) Progreso(null, texto = "Leyendo cómo está…") else Text(texto, style = Letra.actual.texto)
            }
            return@Column
        }
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "Del catálogo del remoto. La carpeta del teléfono la eliges tú: los demás dispositivos pueden " +
                    "tenerla en otra.",
                style = Letra.actual.texto,
                color = c.ink2,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Rotulo("Cómo se sincroniza")
                val (titulo, cuerpo) = comoSeSincroniza(pareja.modo)
                Tarjeta {
                    Fila(titulo, icono = iconoDelModo(pareja.modo), detalle = cuerpo)
                    Separador()
                    Fila("En el remoto", detalle = pareja.remoto, detalleMono = true)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Rotulo("Carpeta en el teléfono")
                OutlinedTextField(
                    value = carpeta,
                    onValueChange = { escrita = it },
                    label = { Text("Carpeta") },
                    supportingText = {
                        Text("Dentro de la app, con su ruta desde la raíz (por ejemplo Fotos/2026). Se ve en la app de Archivos.")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("carpeta-$nombre"),
                )
                plan?.let { EstadoDeLaCarpeta(it, pareja.modo) }
            }
            when {
                pensado == null -> Progreso(null, texto = "Mirando la carpeta…")
                pensado.error != null -> Aviso(
                    "Así no se puede añadir",
                    Modifier.testTag("anadir-no-$nombre"),
                    tono = TonoAviso.PELIGRO,
                    cuerpo = pensado.error,
                )
                plan != null -> {
                    for (aviso in plan.todosLosAvisos) {
                        val partes = EditarParejas.avisoEspejo(pareja.modo)
                            ?.takeIf { aviso == EditarParejas.mirrorWarning(pareja.modo) }
                        Aviso(
                            partes?.first ?: aviso,
                            Modifier.testTag("aviso-anadir-$nombre"),
                            tono = TonoAviso.AVISO,
                            cuerpo = partes?.second,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Rotulo("Qué va a pasar")
                        plan.todasLasConsecuencias.forEachIndexed { i, frase ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${i + 1}.", style = Letra.actual.nombre, color = c.ink3)
                                Text(frase, style = Letra.actual.texto, color = c.ink)
                            }
                        }
                    }
                }
            }
            Boton(
                if (plan?.resyncYa == true) "Añadir y sincronizar" else "Añadir «$nombre»",
                { conRed { m.anadir(nombre, carpeta) } },
                Modifier.testTag("anadir-ya-$nombre"),
                peso = Peso.PRINCIPAL,
                icono = Icono.PLUS,
                ancho = true,
                activo = libre && plan != null,
            )
        }
    }
}

/** Cuánto se espera tras la última tecla antes de volver a mirar la carpeta. */
private const val PAUSA_AL_ESCRIBIR_MS = 250L

/**
 * Cómo está la carpeta elegida, en un chip: nueva, vacía o con cuántos ficheros.
 * La de un espejo de subida no la crea nadie: un origen vacío vaciaría el remoto.
 */
@Composable
private fun EstadoDeLaCarpeta(plan: EditarParejas.PlanAnadir, modo: String) {
    val carpeta = plan.carpeta
    val (palabra, tono) = when {
        !carpeta.existe && MODES[modo]?.borraEnRemoto == true -> "nueva: créala tú, con lo que quieres subir" to Tono.AVISO
        !carpeta.existe -> "nueva: la crea la primera pasada" to Tono.NEUTRO
        carpeta.vacia -> "ya existe, vacía" to Tono.NEUTRO
        else -> "ya tiene ${EditarParejas.cuantos(carpeta)}" to Tono.AVISO
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(palabra, Modifier.testTag("estado-carpeta"), tono = tono)
    }
}
