package prdrive.app.ui.inicio

import prdrive.app.Estado
import prdrive.app.ui.Destino
import prdrive.app.ui.Seccion
import prdrive.app.ui.TextosLlavero
import prdrive.app.ui.piezas.Tono
import prdrive.app.ui.piezas.TonoAviso
import prdrive.engine.Pasada

/**
 * Revisar.kt — Lo que Inicio cuenta como «cosas que revisar»: una por problema,
 * cada una con su título concreto («vault» falló) y a dónde lleva su arreglo.
 * Primero lo que falló o no se ve; después lo que pide algo.
 *
 * Solo junta lo que ya dice [Estado]: no lee el disco ni la red.
 */
data class Revisar(
    val tono: TonoAviso,
    val titulo: String,
    val cuerpo: String,
    /** La pantalla del arreglo, encima de su sección; `null` si es una sección ([seccion]). */
    val destino: Destino?,
    val seccion: Seccion?,
)

/**
 * Las cosas que revisar de [filas] y del [llavero]: las de `PELIGRO` primero y
 * las de `AVISO` después, y en cada grupo, en el orden de las filas. Un
 * `--resync` aprobado en esta sesión ([resultados] dice que fue bien) no se
 * vuelve a pedir: la fila aún no lo sabe hasta que se relee.
 */
fun revisar(
    filas: List<Estado.Fila>,
    llavero: Estado.FilaLlavero?,
    resultados: Map<String, Pasada.Resultado>,
): List<Revisar> {
    val peligro = mutableListOf<Revisar>()
    val aviso = mutableListOf<Revisar>()
    for (f in filas) {
        val ficha = Destino.Ficha(f.nombre)
        val fallo = f.fallo
        if (fallo != null) {
            peligro += Revisar(
                TonoAviso.PELIGRO, "«${f.nombre}» falló",
                f.explicacion ?: "La última pasada falló el ${fallo.cuando}.", ficha, null,
            )
        }
        f.sinAcceso?.let { peligro += Revisar(TonoAviso.PELIGRO, "«${f.nombre}» no ve su carpeta", it, ficha, null) }
        f.problemaCompartida?.let {
            aviso += Revisar(TonoAviso.AVISO, "«${f.nombre}» ha vuelto a su carpeta del volumen", it, ficha, null)
        }
        if (f.entrando) {
            aviso += Revisar(
                TonoAviso.AVISO, "«${f.nombre}» está a medio mover",
                "Termínalo desde su ficha. Hasta entonces no se sincroniza.", ficha, null,
            )
        }
        if (f.pideResync.isNotEmpty() && !f.entrando && resultados[f.nombre]?.ok != true) {
            aviso += Revisar(
                TonoAviso.AVISO, "«${f.nombre}» pide rehacer la referencia",
                "No borra nada. Revisa antes lo que va a pasar.", ficha, null,
            )
        }
        val n = f.conflictos.size
        if (n > 0) {
            val cuantos = if (n == 1) "1 fichero en conflicto" else "$n ficheros en conflicto"
            aviso += Revisar(TonoAviso.AVISO, "$cuantos en «${f.nombre}»", "Cambiaron en los dos lados.", ficha, null)
        }
    }
    if (llavero != null && llavero.ambar) {
        aviso += Revisar(
            TonoAviso.AVISO, "El llavero tiene algo que mirar", TextosLlavero.linea(llavero), null, Seccion.LLAVERO,
        )
    }
    return peligro + aviso
}

/**
 * El chip de una pareja en una lista: lo peor que tiene, en una palabra. Un
 * `--resync` aprobado en esta sesión ([r] bien) ya no cuenta.
 */
fun estadoDe(f: Estado.Fila, r: Pasada.Resultado?): Pair<Tono, String> = when {
    f.fallo != null -> Tono.PELIGRO to "falló"
    f.sinAcceso != null -> Tono.PELIGRO to "sin acceso"
    f.entrando -> Tono.AVISO to "a medio mover"
    f.conflictos.isNotEmpty() -> Tono.AVISO to "conflictos"
    (f.pideResync.isNotEmpty() && r?.ok != true) || f.problemaCompartida != null -> Tono.AVISO to "revisar"
    f.ultimaBuena != null || r?.ok == true -> Tono.OK to "al día"
    else -> Tono.APAGADO to "sin pasada"
}
