package prdrive.engine

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Progreso.kt — Cómo va una pasada. Espejo de `common/progress.py`, con el
 * canal cambiado y el texto igual.
 *
 * En el escritorio no hay otra vía que el log: `sync.py` lanza rclone con
 * `--stats 2s --stats-one-line` y va leyendo su fichero de log mientras la
 * pareja corre, con un lector que descarta lo que no encaje entero porque una
 * línea cortada a media escritura es lo normal.
 *
 * Aquí el canal es mejor: `core/stats` con `{"group": "<grupo>"}` devuelve las
 * mismas cifras **como datos** (`fs/accounting/stats_groups.go`), así que no
 * hay nada que parsear ni línea que pueda llegar a medias. Es [deStats].
 *
 * Lo que **no** cambia es lo que lee el usuario: [Progreso.texto] escribe la
 * misma frase que en el PC, con los mismos redondeos y la coma decimal del
 * español. Los vectores la comparan caso por caso con la de prdrive, porque
 * «2,1 MB de 3,4 MB · 61 % · 1,1 MB/s» es de esas cosas que se copian a ojo y
 * salen distintas.
 *
 * Y se conserva el lector de líneas ([leer]) por una razón concreta: la cola
 * del log que se enseña cuando algo falla tiene que ir sin estadísticas
 * (`print_log_tail` de `sync.py`), y un `stats` escrito a mano en el TOML las
 * traería.
 */

/**
 * Lo que abre la línea de progreso en la salida.
 *
 * La pantalla la busca para reescribirla en su sitio en vez de apilar una por
 * lectura, así que va en una constante que importan los dos: si la pantalla
 * dejara de reconocerla no se quedaría sin color, se llenaría de cientos de
 * líneas. Es la misma decisión —y la misma cadena— que `progress.ETIQUETA`.
 */
const val ETIQUETA_PROGRESO = "progreso:"

/** Una lectura del progreso de una pasada. */
data class Progreso(
    /** Octetos transferidos. */
    val hecho: Long,
    /** Los que rclone sabe que tiene que mover, hasta ahora. */
    val total: Long,
    /** El de rclone; null cuando él no lo sabe. */
    val porcentaje: Int?,
    /** Octetos por segundo. */
    val velocidad: Double,
) {
    /**
     * «2,1 MB de 3,4 MB · 61 % · 1,1 MB/s». Sin porcentaje si rclone no lo
     * da: con el total a cero no hay porcentaje que valga.
     */
    fun texto(): String {
        val partes = mutableListOf("${Progresos.tamano(hecho)} de ${Progresos.tamano(total)}")
        porcentaje?.let { partes.add("$it %") }
        partes.add("${Progresos.tamano(velocidad)}/s")
        return partes.joinToString(" · ")
    }
}

object Progresos {

    /** Los múltiplos con los que rclone escribe un tamaño. */
    private val MULTIPLOS = mapOf(
        "" to 1.0, "K" to 1024.0, "M" to 1048576.0, "G" to 1073741824.0,
        "T" to 1099511627776.0, "P" to 1125899906842624.0, "E" to 1152921504606846976.0,
    )

    /**
     * `fs/accounting/stats.go`, `StatsInfo.String()`:
     * `"%s%13s / %s, %s, %s, ETA %s%s"` —lo transferido, el total, el
     * porcentaje (o «-»), la velocidad y el tiempo que falta—. Se busca, no se
     * ancla: delante va la fecha del log. Exigir la ETA es exigir que la
     * velocidad esté entera, así que la cuenta de ficheros
     * («Transferred: 0 / 3, 0%») y la línea de cada fichero no encajan.
     */
    private val TAMANO = """(\d+(?:\.\d+)?) ?([KMGTPE]?)i?B"""
    private val ESTADISTICA = Regex(
        """(?<![\w.])$TAMANO / $TAMANO, (\d+%|-), $TAMANO/s, ETA \S""",
    )

    /** Una línea del log -> su progreso, o null si no es una estadística entera. */
    fun leer(linea: String): Progreso? {
        val m = ESTADISTICA.find(linea) ?: return null
        val (hecho, preHecho, total, preTotal, pct, vel, preVel) = m.destructured
        return Progreso(
            hecho = Math.round(octetos(hecho, preHecho)),
            total = Math.round(octetos(total, preTotal)),
            porcentaje = if (pct == "-") null else pct.dropLast(1).toInt(),
            velocidad = octetos(vel, preVel),
        )
    }

    /**
     * La lectura más reciente de un trozo de log.
     *
     * Cada estadística cuenta desde el principio de la pasada, así que la
     * última es la verdad aunque diga menos que las de antes o llegue detrás
     * de un error.
     */
    fun ultimo(texto: String): Progreso? =
        texto.lines().asReversed().firstNotNullOfOrNull { leer(it) }

    /**
     * Lo que devuelve `core/stats`, que es de donde sale el progreso en la app.
     *
     * Devuelve null mientras no haya nada que contar: al principio de una
     * pasada rclone no sabe todavía cuánto hay que mover, y una línea
     * «0 B de 0 B» no informa de nada. Es el equivalente de que en el
     * escritorio no encaje ninguna línea todavía.
     *
     * El porcentaje se calcula como el suyo —`percent()` en
     * `fs/accounting/stats.go`, que redondea con `+0.5` y devuelve «-» si el
     * total no es positivo—, para que el móvil y el PC no digan cifras
     * distintas de la misma pasada.
     */
    fun deStats(stats: Map<String, Any?>): Progreso? {
        val hecho = entero(stats["bytes"])
        val total = entero(stats["totalBytes"])
        if (hecho <= 0L && total <= 0L) return null
        return Progreso(
            hecho = hecho,
            total = total,
            porcentaje = if (hecho < 0L || total <= 0L) {
                null
            } else {
                Math.floor(hecho.toDouble() * 100.0 / total.toDouble() + 0.5).toInt()
            },
            velocidad = decimal(stats["speed"]),
        )
    }

    /** La línea que se enseña, con su etiqueta. */
    fun linea(progreso: Progreso): String = "  $ETIQUETA_PROGRESO ${progreso.texto()}"

    /**
     * «1,1 MB»: como enseña los tamaños el resto de la interfaz.
     *
     * El redondeo es el de Python (mitad al par sobre el valor binario exacto,
     * que es lo que hace su `f"{v:.1f}"`), no el de `String.format`, que
     * redondea siempre hacia arriba: con eso, un tamaño de los que caen justo
     * en la mitad saldría distinto en el móvil y en el PC.
     */
    fun tamano(octetos: Number): String {
        var valor = octetos.toDouble()
        var unidad = "B"
        for (u in listOf("B", "KB", "MB", "GB", "TB")) {
            unidad = u
            if (valor < 1024 || u == "TB") break
            valor /= 1024
        }
        val decimales = if (unidad == "B") 0 else 1
        val texto = BigDecimal(valor).setScale(decimales, RoundingMode.HALF_EVEN).toPlainString()
        return "${texto.replace('.', ',')} $unidad"
    }

    private fun octetos(numero: String, prefijo: String): Double =
        numero.toDouble() * (MULTIPLOS[prefijo] ?: 1.0)

    private fun entero(valor: Any?): Long = when (valor) {
        is Long -> valor
        is Int -> valor.toLong()
        is Double -> Math.round(valor)
        else -> 0L
    }

    private fun decimal(valor: Any?): Double = when (valor) {
        is Number -> valor.toDouble()
        else -> 0.0
    }
}
