package prdrive.engine

/**
 * Orden.kt — La orden de rclone de una pareja, en trozos que dicen quién pone
 * cada uno.
 *
 * Espejo de `common/orden.py` de prdrive. En el escritorio esto es la línea de
 * órdenes que ejecuta `sync.py`; en la app **no se ejecuta ninguna**: [Pasada]
 * traduce estos mismos trozos a una llamada rc (`sync/bisync`, `sync/copy`,
 * `sync/sync`). Se monta igual a propósito, trozo a trozo y comparado con los
 * vectores, para que la pasada de una pareja sea la misma en el teléfono y en el
 * PC: qué se pone, qué se quita y en qué orden gana cada flag.
 *
 * Un trozo ([Tramo]) es un flag con su valor, una regla de filtrado, un
 * argumento extra o el comienzo de la orden, y lleva la capa de la que sale.
 *
 * Diferencia con el original: las rutas que allí son del dispositivo (`config`,
 * `logfile`, el workdir de bisync) se pasan, porque el motor no sabe dónde está
 * el volumen.
 */
object Orden {

    /** Un trozo de la orden de rclone y quién lo pone (`ORIGEN_*`). */
    data class Tramo(val args: List<String>, val origen: String, val nombre: String? = null)

    /**
     * Los trozos de filtrado de la pareja. Con fichero de filtros no se emiten
     * `--include` ni `--exclude`: se duplicarían las reglas y bisync dejaría de
     * poder detectar cambios de filtrado. Las reglas del código no pueden ir
     * delante de las de la pareja: rclone añade siempre los `--include` antes que
     * los `--exclude` (`fs/filter/filter.go`, `NewFilter`).
     */
    fun tramosDeFiltrado(pareja: Pareja, filtros: String?): List<Tramo> {
        if (filtros != null) return listOf(Tramo(listOf("--filters-file", filtros), ORIGEN_PRDRIVE))
        val tramos = ArrayList<Tramo>()
        for (regla in pareja.reglas) {
            val corte = regla.indexOf(' ')
            val signo = if (corte < 0) regla else regla.substring(0, corte)
            val patron = if (corte < 0) "" else regla.substring(corte + 1)
            tramos.add(Tramo(listOf(if (signo == "+") "--include" else "--exclude", patron), ORIGEN_PRDRIVE))
        }
        pareja.includes.forEach { tramos.add(Tramo(listOf("--include", it), ORIGEN_PATRONES)) }
        pareja.excludes.forEach { tramos.add(Tramo(listOf("--exclude", it), ORIGEN_PATRONES)) }
        return tramos
    }

    /** Si ese nombre de flag, tal como está escrito, es el [flag] de rclone. */
    private fun esFlag(nombre: String, flag: String): Boolean = normalizarFlag(nombre) == flag

    /**
     * `extra_flags` sin ese flag ni su valor: vale `--flag V` (en dos
     * argumentos) y `--flag=V`, con cualquier grafía del nombre. Otro flag que
     * empieza igual (`--max-delete-size`) es otro y se queda.
     */
    fun sinFlag(extra: List<String>, flag: String): List<String> {
        val salida = ArrayList<String>()
        var saltarValor = false
        for (arg in extra) {
            if (saltarValor) {
                saltarValor = false
                if (!arg.startsWith("--")) continue
            }
            val igual = arg.indexOf('=')
            val nombre = if (igual < 0) arg else arg.substring(0, igual)
            if (nombre.startsWith("--") && esFlag(nombre.substring(2), flag)) {
                saltarValor = igual < 0
                continue
            }
            salida.add(arg)
        }
        return salida
    }

    /**
     * El `--max-delete` que una pasada normal de la pareja le da a rclone:
     * rclone se queda con el último, el de la cascada y, tras todos los flags,
     * el de `extra_flags`. `null` si ninguna capa lo pone o no es un número.
     */
    fun maxDeleteEfectivo(pareja: Pareja): Long? {
        var valor: Any? = null
        for ((nombre, v) in pareja.flags) if (esFlag(nombre, "max-delete")) valor = v
        val extra = pareja.extraFlags
        for ((i, arg) in extra.withIndex()) {
            val igual = arg.indexOf('=')
            val nombre = if (igual < 0) arg else arg.substring(0, igual)
            if (nombre.startsWith("--") && esFlag(nombre.substring(2), "max-delete")) {
                valor = if (igual >= 0) arg.substring(igual + 1) else extra.getOrNull(i + 1)
            }
        }
        if (valor is Boolean) return null
        return intPy(valor)
    }

    /**
     * Si la pareja tiene un freno de borrados que se pueda saltar
     * (`frenos.tiene_freno()`): bisync y los espejos; un `copy` no borra.
     */
    fun tieneFreno(pareja: Pareja): Boolean = pareja.isBisync || pareja.mode.verb == "sync"

    /** `frenos.se_puede_forzar()`: con freno y que no sea el llavero, que son contraseñas. */
    fun sePuedeForzar(pareja: Pareja): Boolean = tieneFreno(pareja) && !pareja.llavero

    /**
     * Si esta pasada salta de verdad el freno de borrados: hay que pedirlo, la
     * pareja ha de tener un freno que se pueda saltar, y un `--resync` no pasa por
     * el freno (no compara contra el listado anterior).
     */
    fun pasadaForzada(pareja: Pareja, forzar: Boolean, resync: Boolean): Boolean =
        forzar && sePuedeForzar(pareja) && !resync

    /** Si la orden quita el `--max-delete` de un espejo: bisync se salta el suyo con `--force`. */
    private fun saltaElTope(pareja: Pareja, forzar: Boolean, resync: Boolean): Boolean =
        pasadaForzada(pareja, forzar, resync) && !pareja.isBisync

    /**
     * Los flags de una pasada: los de la pareja y los que añade la pasada. Los de
     * la pareja llegan fundidos ([Pareja.flags], con su capa en
     * [Pareja.origenFlags]); aquí se añaden los que dependen de ESTA ejecución.
     *
     * @param workdir el workdir de bisync de la pareja, ya resuelto.
     * @param sello el sufijo de las versiones de esta pasada (`--suffix`).
     * @param resyncMode el `--resync-mode` de esta pasada, por encima del de la
     *   pareja, o `null` para dejar el suyo. Solo cuenta en un `--resync`.
     *   Diferencia del teléfono, sin equivalente en `orden.py`: los `--resync`
     *   de [Mudanza] lo ponen para que en caso de duda gane el remoto.
     */
    fun flagsDeLaPasada(
        pareja: Pareja,
        config: String,
        logfile: String,
        workdir: String,
        simulacro: Boolean = false,
        resync: Boolean = false,
        sello: String = "",
        forzar: Boolean = false,
        resyncMode: String? = null,
    ): List<FlagEfectivo> {
        val flags = LinkedHashMap<String, FlagEfectivo>()
        for ((nombre, valor) in pareja.flags) {
            flags[nombre] = FlagEfectivo(nombre, valor, pareja.origenFlags[nombre] ?: ORIGEN_PAREJA)
        }
        fun pone(nombre: String, valor: Any?) {
            flags[nombre] = FlagEfectivo(nombre, valor, ORIGEN_PRDRIVE)
        }
        pone("config", config)
        pone("log-file", logfile)
        if (simulacro) pone("dry-run", true)
        if (pareja.isBisync) {
            pone("workdir", workdir)
            if (resync) {
                pone("resync", true)
                if (resyncMode != null) {
                    // El de la pasada manda: se quita el de la pareja, escrito
                    // como se escriba, y en [tramos] también el de `extra_flags`.
                    flags.keys.filter { esFlag(it, "resync-mode") }.forEach { flags.remove(it) }
                    pone("resync-mode", resyncMode)
                }
            } else {
                // `--resync-mode` implica `--resync` (`setResyncDefaults()`,
                // cmd/bisync/resync.go): dicho en una pasada normal la convierte
                // en un resync. Se quita escrito como se escriba.
                flags.keys.filter { esFlag(it, "resync-mode") }.forEach { flags.remove(it) }
            }
        }
        // Un resync no pasa por el freno: no tiene nada que saltar.
        if (pasadaForzada(pareja, forzar, resync)) {
            if (pareja.isBisync) {
                pone("force", true)
            } else {
                // rclone se queda con el último `--max-delete`: se quitan todos.
                flags.keys.filter { esFlag(it, "max-delete") }.forEach { flags.remove(it) }
                pone("max-delete", -1L)
            }
        }
        if (pareja.versions) {
            pone("backup-dir1", pareja.versionsPath1)
            pone("backup-dir2", pareja.versionsPath2)
            pone("suffix", sello)
            pone("suffix-keep-extension", true)
            // Con `delete`, el perdedor de un conflicto sale por el backup-dir en
            // vez de viajar al otro lado. Un `[pair.flags]` sigue mandando.
            if (flags.keys.none { esFlag(it, "conflict-loser") }) pone("conflict-loser", "delete")
        }
        return flags.values.toList()
    }

    /**
     * La orden de rclone de la pareja en trozos, cada uno con su origen, en el
     * orden exacto de la línea de órdenes: el subcomando con los dos extremos, el
     * filtrado, los flags (los que se apagan con `false` no salen) y los
     * argumentos extra. [resyncMode], como en [flagsDeLaPasada].
     */
    fun tramos(
        pareja: Pareja,
        binario: String,
        filtros: String?,
        config: String,
        logfile: String,
        workdir: String,
        simulacro: Boolean = false,
        resync: Boolean = false,
        sello: String = "",
        forzar: Boolean = false,
        resyncMode: String? = null,
    ): List<Tramo> {
        val salida = ArrayList<Tramo>()
        salida.add(Tramo(listOf(binario, pareja.mode.verb, pareja.source, pareja.dest), ORIGEN_PRDRIVE))
        salida.addAll(tramosDeFiltrado(pareja, filtros))
        for (flag in flagsDeLaPasada(pareja, config, logfile, workdir, simulacro, resync, sello, forzar, resyncMode)) {
            val args = flagsToArgs(mapOf(flag.nombre to flag.valor))
            if (args.isNotEmpty()) salida.add(Tramo(args, flag.origen, flag.nombre))
        }
        var extra = pareja.extraFlags
        if (saltaElTope(pareja, forzar, resync)) extra = sinFlag(extra, "max-delete")
        // rclone se queda con el último `--resync-mode`, y `extra_flags` va
        // detrás: con el de la pasada puesto, el de la pareja no puede quedar.
        if (pareja.isBisync && (!resync || resyncMode != null)) extra = sinFlag(extra, "resync-mode")
        extra.forEach { salida.add(Tramo(listOf(it), ORIGEN_EXTRA)) }
        return salida
    }

    /** La orden entera, aplanada. */
    fun ordenRclone(tramos: List<Tramo>): List<String> = tramos.flatMap { it.args }
}
