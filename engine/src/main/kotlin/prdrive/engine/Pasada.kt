package prdrive.engine

import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Pasada.kt — La pasada de una pareja: lo que hace `sync.py`, contra rclone como
 * biblioteca.
 *
 * Espejo de `sync.py` de prdrive (`_pasar_pareja`, `_bisync_preflight`,
 * `KNOWN_ERRORS`, `explain_failure`, `keep_log`, `podar_logs`) y de lo que toma
 * de `common/frenos.py` y `common/moderacion.py`. **No reimplementa
 * sincronizar**: monta la orden ([Orden]), la traduce a una llamada rc
 * ([llamadaDe]) y cuenta cómo ha ido.
 *
 * Tres diferencias con el escritorio, todas por no haber proceso hijo:
 * - No hay código de salida: rclone como biblioteca devuelve un error, no un
 *   número. Una pasada que falla es [FALLO] (el «error sin clasificar» de
 *   rclone); las que aborta la propia pasada siguen siendo [ABORTO].
 * - No hay `--log-file`: el log es la salida que captura rclone de la llamada
 *   (`output` de `sync/bisync`, o lo que capture quien ejecuta).
 * - No hay `--config`: rclone ya está apuntado al `rclone.conf` del volumen.
 *
 * Lo que NO hay del escritorio: el llavero (la app no lo sincroniza todavía),
 * el parte de la flota, el diario de pasadas, la medida de si la pasada movió
 * algo y `--force`, que es de una persona desde «Reparación».
 */
object Pasada {

    /** Pareja no ejecutada (ni OK ni fallo): pide resync y nadie lo ha aprobado. */
    const val SALTADA = -1

    /** El «error sin clasificar» de rclone: lo que vale una pasada que ha fallado. */
    const val FALLO = 1

    /** Lo que vale una pasada que la propia pasada aborta antes de llamar a rclone. */
    const val ABORTO = 2

    /** Líneas de log que se enseñan cuando algo falla. */
    const val LOG_TAIL_LINES = 15

    /** Logs de pasadas fallidas que se conservan en `logs/` por pareja. */
    const val LOGS_POR_PAREJA = 20

    /** Ficheros en conflicto que se nombran en la salida. */
    const val CONFLICTS_SHOWN = 5

    // -----------------------------------------------------------------------
    // Lo que se dice cuando falla (sync.py: KNOWN_ERRORS; common/frenos.py;
    // common/moderacion.py)
    // -----------------------------------------------------------------------

    /**
     * Lo que dice rclone cuando no llega al remoto (`moderacion.ERRORES_DE_RED`).
     * Todas son de CONEXIÓN: un remoto que contesta con un error propio no es un
     * problema de red.
     */
    val ERRORES_DE_RED: List<String> = listOf(
        "no such host",
        "temporary failure in name resolution",
        "dial tcp",
        "connection refused",
        "actively refused it",
        "no route to host",
        "network is unreachable",
        "i/o timeout",
        "context deadline exceeded",
        "tls handshake timeout",
        "couldn't connect ssh",
        "did not properly respond after a period of time",
    )

    const val EXPLICACION_RED =
        "No se ha podido llegar al remoto: sin red, el servidor apagado o un nombre " +
            "que no se resuelve. No es un fallo de la pareja; cuando vuelva la conexión, " +
            "la siguiente pasada seguirá donde lo dejó."

    /** Si lo que ha escrito una pasada fallida dice que no llegó al remoto. */
    fun esDeRed(texto: String): Boolean {
        val bajo = texto.lowercase()
        return ERRORES_DE_RED.any { it in bajo }
    }

    const val COMO_FORZAR =
        "fuerza UNA pasada desde «Reparación» o con `python sync.py <pareja> --force`"

    const val EXPLICACION_BORRADOS =
        "Se han borrado más ficheros de un lado de los que permite el freno de bisync, " +
            "que es un porcentaje del listado anterior: no se ha propagado nada. Comprueba " +
            "que la ruta local NO esté vacía ni desmontada; si es lo que querías, $COMO_FORZAR."

    const val EXPLICACION_CAMBIADOS =
        "Han cambiado todos los ficheros de un lado desde la última pasada (pasa con un " +
            "cambio de hora o al tocar toda la carpeta) y bisync se ha parado para no copiarlo " +
            "todo de nuevo. Si es lo esperado, $COMO_FORZAR."

    const val EXPLICACION_ESPEJO =
        "Un espejo solo puede borrar hasta el tope de su freno (--max-delete, una cuenta " +
            "de ficheros) y ha llegado a él: rclone ha parado el resto. Lo ya borrado no vuelve. " +
            "Comprueba que la ruta de origen NO esté vacía ni desmontada; si es lo que querías, " +
            "$COMO_FORZAR."

    // Las líneas de rclone, con las cifras en ASCII: en Android `\d` es Unicode.
    private val BORRADOS =
        Regex("Safety abort: too many deletes \\(>([0-9]+)%, ([0-9]+) of ([0-9]+)\\) on (Path[12])\\b")
    private val CAMBIADOS = Regex("Safety abort: all files were changed on (Path[12])\\b")
    private const val FIN_BORRADOS = "Failed to bisync: too many deletes"
    private const val FIN_CAMBIADOS = "Failed to bisync: all files were changed"
    private const val ESPEJO = "--max-delete threshold reached"

    /** El log es el de un aborto de bisync por demasiados borrados. */
    fun esBorradosBisync(texto: String): Boolean = BORRADOS.containsMatchIn(texto) || FIN_BORRADOS in texto

    /** El log es el de un aborto de bisync porque cambiaron todos los ficheros. */
    fun esCambiadosBisync(texto: String): Boolean = CAMBIADOS.containsMatchIn(texto) || FIN_CAMBIADOS in texto

    /** El log es el de un `sync` que paró en su `--max-delete`. */
    fun esEspejo(texto: String): Boolean = ESPEJO in texto

    /** Una aguja del log y su explicación. */
    class Error(val aguja: String?, val explicacion: String, private val casa: (String) -> Boolean) {
        fun casaCon(texto: String): Boolean = casa(texto)
    }

    private fun texto(aguja: String, explicacion: String) = Error(aguja, explicacion) { aguja in it }

    /**
     * `KNOWN_ERRORS`: agujas de lo que dice rclone al fallar y su explicación;
     * gana la primera que casa. Las de arranque (flags) van al final: un volcado
     * de ayuda con esos nombres no puede explicar un fallo ajeno.
     *
     * Ojo, de prdrive: la aguja `must run --resync` no casa con el aborto de
     * bisync, que rclone escribe «Must run --resync» con mayúscula
     * (cmd/bisync/operations.go); se conserva como está porque la especificación
     * manda, y está apuntado en PLAN.md.
     */
    val KNOWN_ERRORS: List<Error> = listOf(
        texto(
            Bisync.MISSING_LISTINGS,
            "No hay baseline: primera vez, listados en otro sitio (¿cambió la ruta?) o " +
                "un fallo crítico previo los invalidó. Solución: --resync.",
        ),
        texto(
            "filters file has changed",
            "Han cambiado los filtros. Solución: --resync (bisync no puede saber qué " +
                "ficheros excluidos existían antes).",
        ),
        texto("filters file md5 hash not found", "Primer uso de este fichero de filtros. Solución: --resync."),
        texto("must run --resync", "bisync ha invalidado el baseline y exige rehacerlo. Solución: --resync."),
        Error(null, EXPLICACION_BORRADOS, ::esBorradosBisync),
        Error(null, EXPLICACION_CAMBIADOS, ::esCambiadosBisync),
        Error(null, EXPLICACION_ESPEJO, ::esEspejo),
        texto("Access is denied", "Fichero bloqueado por otro proceso (Obsidian, KeePass, antivirus)."),
        texto(
            "prior lock file found",
            "Hay un lock de otra ejecución. Si no hay ninguna corriendo, borra el .lck " +
                "del workdir de la pareja.",
        ),
        texto(
            "couldn't parse known_hosts_file",
            "rclone no puede leer el fichero de known_hosts indicado en rclone.conf " +
                "(no existe o está mal formado). Las rutas relativas de rclone.conf se " +
                "resuelven contra .prdrive/; comprueba que keys/known_hosts existe ahí.",
        ),
        Error(null, EXPLICACION_RED, ::esDeRed),
        texto(
            "Failed to create file system",
            "rclone no ha podido montar uno de los dos extremos. Suele ser una ruta o " +
                "credencial mal resuelta en rclone.conf (revisa key_file y " +
                "known_hosts_file), o el remoto inalcanzable.",
        ),
        texto(
            "unknown flag",
            "rclone no conoce uno de los flags. Está escrito en sync_config.toml (o en " +
                "el editor de flags de la ventana de parejas): rclone lo rechaza antes de " +
                "empezar, por eso no hay más log que este.",
        ),
        texto(
            "invalid argument",
            "rclone rechaza el VALOR de un flag: no es de los que admite (por ejemplo " +
                "conflict-resolve = \"new\" cuando lo válido es \"newer\"). El mensaje de " +
                "arriba enumera los buenos. Se corrige en sync_config.toml.",
        ),
    )

    /** La explicación de un fallo, o `null` si ninguna aguja casa (`explain_failure`). */
    fun explicar(texto: String): String? = KNOWN_ERRORS.firstOrNull { it.casaCon(texto) }?.explicacion

    /** Las últimas líneas del log, sin las de progreso (`print_log_tail`). */
    fun cola(texto: String, lineas: Int = LOG_TAIL_LINES): List<String> =
        texto.lines().filter { it.isNotEmpty() && !esProgreso(it) }.takeLast(lineas)

    // Las líneas de `--stats-one-line` («Transferred: 1 KiB / 1 KiB, 100%…»).
    private fun esProgreso(linea: String): Boolean = "Transferred:" in linea && "ETA" in linea

    // -----------------------------------------------------------------------
    // Los logs de las pasadas fallidas (sync.py: keep_log, podar_logs)
    // -----------------------------------------------------------------------

    private val SELLO_LOG = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    /**
     * Guarda el log de una pasada en `logs/<pareja>_<sello>[_N].log` y poda los
     * más viejos de esa pareja. Devuelve dónde ha quedado, o `null` si no se ha
     * podido escribir (que no es un error de la pasada).
     */
    fun guardarLog(logDir: File, nombre: String, texto: String, ahora: LocalDateTime = LocalDateTime.now()): File? {
        return try {
            logDir.mkdirs()
            val sello = ahora.format(SELLO_LOG)
            var final = File(logDir, "${nombre}_$sello.log")
            var n = 1
            while (final.exists()) {
                final = File(logDir, "${nombre}_${sello}_$n.log")
                n++
            }
            if (!Store.writeText(final, texto)) return null
            podarLogs(logDir, nombre, final)
            final
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Deja en `logs/` solo los [LOGS_POR_PAREJA] más nuevos de una pareja: los
     * del sello más alto del nombre, no los de fecha de modificación más
     * reciente. [conservar] no se borra nunca y queda fuera del ranking (con el
     * reloj atrasado su sello sería más viejo que los de antes).
     */
    fun podarLogs(logDir: File, nombre: String, conservar: File? = null) {
        // El `\d` de Python es Unicode y su `$` admite un `\n` final: se escribe
        // como en `sync.py` (`podar_logs`), y el orden es el de su tupla.
        val patron = Regex("${Regex.escape(nombre)}_(\\p{Nd}{8})_(\\p{Nd}{6})(?:_(\\p{Nd}+))?\\.log\\n?")
        val sellados = (logDir.listFiles() ?: return).mapNotNull { f ->
            val m = patron.matchEntire(f.name) ?: return@mapNotNull null
            if (conservar != null && f == conservar) return@mapNotNull null
            val n = m.groupValues[3].ifEmpty { "0" }.toBigInteger()
            Sellado(m.groupValues[1], m.groupValues[2], n, f)
        }.sortedWith(
            compareBy<Sellado, String>(ORDEN_PY) { it.fecha }
                .thenBy(ORDEN_PY) { it.hora }
                .thenBy { it.n }
                .reversed(),
        )
        val quedan = LOGS_POR_PAREJA - (if (conservar != null) 1 else 0)
        sellados.drop(quedan).forEach { it.log.delete() }
    }

    private data class Sellado(val fecha: String, val hora: String, val n: java.math.BigInteger, val log: File)

    // -----------------------------------------------------------------------
    // De la orden a una llamada rc
    // -----------------------------------------------------------------------

    /** Una llamada a rclone: el método rc y sus parámetros. */
    data class Llamada(val metodo: String, val parametros: Map<String, Any?>)

    /**
     * Lo que rclone sabe de sus opciones globales (`options/info`): por bloque
     * (`main`, `filter`), el nombre de cada una y si es booleana. Es lo que dice
     * si un flag existe y si se lleva el argumento siguiente, como hace la línea
     * de órdenes de rclone.
     */
    data class Opciones(val main: Map<String, Boolean>, val filtro: Map<String, Boolean>) {
        companion object {
            /** De la salida de `options/info`: `{bloque: [{Name, Type, ...}]}`. */
            fun deOptionsInfo(info: Map<String, Any?>): Opciones {
                fun bloque(nombre: String): Map<String, Boolean> =
                    (info[nombre] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().associate {
                        it["Name"].toString() to (it["Type"] == "bool")
                    }
                return Opciones(bloque("main"), bloque("filter"))
            }
        }
    }

    /**
     * Los flags de `rclone bisync` que en el RPC son parámetros de `sync/bisync`
     * (cmd/bisync/rc.go), con su nombre allí.
     */
    val PARAMETROS_BISYNC: Map<String, String> = linkedMapOf(
        "resync" to "resync", "dry-run" to "dryRun", "max-delete" to "maxDelete",
        "filters-file" to "filtersFile", "workdir" to "workdir", "check-access" to "checkAccess",
        "check-filename" to "checkFilename", "force" to "force",
        "create-empty-src-dirs" to "createEmptySrcDirs", "remove-empty-dirs" to "removeEmptyDirs",
        "no-cleanup" to "noCleanup", "ignore-listing-checksum" to "ignoreListingChecksum",
        "resilient" to "resilient", "recover" to "recover", "max-lock" to "maxLock",
        "check-sync" to "checkSync", "resync-mode" to "resyncMode",
        "conflict-resolve" to "conflictResolve", "conflict-loser" to "conflictLoser",
        "conflict-suffix" to "conflictSuffix", "compare" to "compare", "no-slow-hash" to "noSlowHash",
        "slow-hash-sync-only" to "slowHashSyncOnly", "download-hash" to "downloadHash",
        "backup-dir1" to "backupDir1", "backup-dir2" to "backupDir2",
    )

    /** Los de bisync que no son booleanos: se llevan el argumento siguiente. */
    private val BISYNC_CON_VALOR = setOf(
        "max-delete", "filters-file", "workdir", "check-filename", "max-lock", "check-sync",
        "resync-mode", "conflict-resolve", "conflict-loser", "conflict-suffix", "compare",
        "backup-dir1", "backup-dir2",
    )

    /**
     * Los que pone la pasada del escritorio y aquí no significan nada: rclone ya
     * lee el `rclone.conf` del volumen, su salida la captura la llamada, el
     * nivel de log se pone una vez para todas (`options/set`, INFO: `--verbose`)
     * y `--stats` es de la línea de órdenes (cmd/cmd.go, no una opción global):
     * con la biblioteca el progreso se pide a `core/stats`.
     */
    private val SIN_EQUIVALENTE = setOf("config", "log-file", "verbose", "v", "stats")

    /** La orden no se puede pasar a rclone: lo que diría su línea de órdenes. */
    class OrdenInvalida(mensaje: String) : Exception(mensaje)

    /**
     * Traduce la orden de una pareja ([Orden.tramos]) a la llamada rc que hace
     * lo mismo.
     *
     * Los argumentos se leen como los lee rclone: `--x=v`, `--x v` o `--x` si la
     * opción es booleana, y gana el último. Lo que es de bisync va como sus
     * parámetros; las opciones globales, como parámetros planos con su nombre de
     * opción (`modify_window`) y el texto del flag, que rclone interpreta con el
     * mismo analizador que la línea de órdenes (`fs/rc/context.go`,
     * `ParseOptions`); `--include`/`--exclude`, en `_filter`.
     *
     * @throws OrdenInvalida con el mensaje de rclone («unknown flag: --x»), para
     *   que [explicar] diga lo mismo que en el escritorio.
     */
    fun llamadaDe(tramos: List<Orden.Tramo>, opciones: Opciones): Llamada {
        val cabeza = tramos.first().args
        val verbo = cabeza[1]
        val args = tramos.drop(1).flatMap { it.args }
        val bisync = verbo == "bisync"
        val parametros = LinkedHashMap<String, Any?>()
        val incluir = ArrayList<String>()
        val excluir = ArrayList<String>()
        when (verbo) {
            "bisync" -> { parametros["path1"] = cabeza[2]; parametros["path2"] = cabeza[3] }
            "copy", "sync" -> { parametros["srcFs"] = cabeza[2]; parametros["dstFs"] = cabeza[3] }
            else -> throw OrdenInvalida("unknown command \"$verbo\" for \"rclone\"")
        }
        var i = 0
        while (i < args.size) {
            val arg = args[i++]
            if (!arg.startsWith("--")) throw OrdenInvalida("unknown command \"$arg\" for \"rclone $verbo\"")
            val igual = arg.indexOf('=')
            val nombre = if (igual < 0) arg.substring(2) else arg.substring(2, igual)
            val opcion = nombre.replace("-", "_")
            val esBooleano = when {
                bisync && nombre in PARAMETROS_BISYNC -> nombre !in BISYNC_CON_VALOR
                nombre == "create-empty-src-dirs" -> true
                nombre in SIN_EQUIVALENTE -> nombre == "verbose" || nombre == "v"
                nombre == "include" || nombre == "exclude" -> false
                opcion in opciones.main -> opciones.main.getValue(opcion)
                opcion in opciones.filtro -> opciones.filtro.getValue(opcion)
                else -> throw OrdenInvalida("unknown flag: --$nombre")
            }
            val valor: String = when {
                igual >= 0 -> arg.substring(igual + 1)
                esBooleano -> "true"
                i < args.size -> args[i++]
                else -> throw OrdenInvalida("flag needs an argument: --$nombre")
            }
            when {
                bisync && nombre in PARAMETROS_BISYNC -> {
                    val clave = PARAMETROS_BISYNC.getValue(nombre)
                    parametros[clave] = when {
                        nombre == "max-delete" -> intPy(valor)
                            ?: throw OrdenInvalida("invalid argument \"$valor\" for \"--max-delete\" flag")
                        esBooleano -> valorBooleano(nombre, valor)
                        else -> valor
                    }
                }
                nombre == "create-empty-src-dirs" -> parametros["createEmptySrcDirs"] = valorBooleano(nombre, valor)
                nombre in SIN_EQUIVALENTE -> Unit
                nombre == "include" -> incluir.add(valor)
                nombre == "exclude" -> excluir.add(valor)
                else -> parametros[opcion] = valor
            }
        }
        if (incluir.isNotEmpty() || excluir.isNotEmpty()) {
            parametros["_filter"] = linkedMapOf<String, Any?>().apply {
                if (incluir.isNotEmpty()) put("IncludeRule", incluir)
                if (excluir.isNotEmpty()) put("ExcludeRule", excluir)
            }
        }
        val metodo = when (verbo) {
            "bisync" -> "sync/bisync"
            "copy" -> "sync/copy"
            else -> "sync/sync"
        }
        return Llamada(metodo, parametros)
    }

    private fun valorBooleano(nombre: String, valor: String): Boolean = when (valor.lowercase()) {
        "true", "1", "t" -> true
        "false", "0", "f" -> false
        else -> throw OrdenInvalida("invalid argument \"$valor\" for \"--$nombre\" flag")
    }

    // -----------------------------------------------------------------------
    // La pasada de una pareja (sync.py: _pasar_pareja, _bisync_preflight)
    // -----------------------------------------------------------------------

    /** Lo que deja una llamada larga al acabar: si fue bien, por qué no, y su log. */
    data class Final(val ok: Boolean, val error: String, val log: String, val salida: Map<String, Any?> = emptyMap())

    /** Quien habla con rclone. En la app es la biblioteca; en los tests, uno de mentira. */
    interface Rclone {
        /** Ejecuta una llamada larga, contando el progreso, y devuelve cómo acabó. */
        fun ejecutar(llamada: Llamada, alProgreso: (Map<String, Any?>) -> Unit): Final
    }

    /**
     * Lo que no cambia de una pareja a otra en una misma ejecución
     * (`RunContext`). [sello] es el sufijo de las versiones, uno por ejecución
     * para que lo apartado en ella comparta marca.
     */
    data class Contexto(
        val simulacro: Boolean = false,
        val forzarResync: Boolean = false,
        val resyncAprobado: Boolean = false,
        val keepLogs: Boolean = false,
        val sello: String = selloVersiones(),
    )

    /** El sufijo de las versiones de una pasada: `~%Y%m%d-%H%M%S`. */
    fun selloVersiones(cuando: LocalDateTime = LocalDateTime.now()): String =
        cuando.format(DateTimeFormatter.ofPattern("'~'yyyyMMdd-HHmmss"))

    /** Cómo acabó una pareja: su código, lo que se dice de ella, el log si se guardó y sus conflictos. */
    data class Resultado(
        val pareja: String,
        val codigo: Int,
        val lineas: List<String>,
        val log: File? = null,
        val explicacion: String? = null,
        val conflictos: List<Conflictos.Conflicto> = emptyList(),
        val resync: Boolean = false,
    ) {
        val ok: Boolean get() = codigo == 0
        val saltada: Boolean get() = codigo == SALTADA
    }

    /**
     * Las comprobaciones previas de una pareja bisync. Devuelve si hace falta
     * resync, si es porque la referencia está vacía, y un código con el que
     * abortar (o `null` para seguir).
     */
    private fun preflight(
        volumen: Volumen,
        pareja: Pareja,
        ctx: Contexto,
        tipos: Map<String, Bisync.TipoRemote>,
        decir: (String) -> Unit,
    ): Triple<Boolean, Boolean, Int?> {
        val workdir = volumen.workdir(pareja)
        val estado = Bisync.pairState(workdir)
        decir("  estado: ${estado.status} — ${estado.detail}")
        val razones = Bisync.resyncReasons(
            pareja, estado, Bisync.filtersState(Bisync.filtersFileFor(pareja, volumen.filtros)),
        )
        // Con los dos listados vacíos no queda nada que proteger, y rclone se
        // niega a pasar con ellos: se rehacen, sin pedirlo.
        val carpeta = pareja.carpetaEn(volumen.raiz)
        val vacia = Bisync.referenciaVacia(pareja, estado, workdir, carpeta, tipos)
        val needResync = ctx.forzarResync || razones.isNotEmpty() || vacia
        if (vacia) {
            razones.forEach { decir("  requiere --resync -> $it") }
            decir(
                "  la referencia está vacía: se rehace con --resync, sin pedirlo (no hay " +
                    "nada que proteger, y un --resync no borra nada).",
            )
        } else if (needResync && !ctx.resyncAprobado) {
            razones.forEach { decir("  requiere --resync -> $it") }
            decir("[${pareja.name}] Saltada: requiere --resync y no está aprobado.")
            return Triple(needResync, vacia, SALTADA)
        }
        // Con baseline y sin carpeta local, crearla vacía haría que bisync viese
        // «han borrado todo».
        if (estado.hasBaseline && !carpeta.exists()) {
            decir(
                "[${pareja.name}] ERROR: existe baseline pero la ruta local " +
                    "'${pareja.local}' no existe. Se aborta (no se crea vacía a propósito).",
            )
            return Triple(needResync, vacia, ABORTO)
        }
        return Triple(needResync, vacia, null)
    }

    /**
     * Hace la pasada de una pareja (`_pasar_pareja()`), apunta el resultado y
     * los conflictos, y guarda el log si falla.
     *
     * @param opciones las de `options/info`, para traducir la orden.
     * @param decir por dónde va contando lo que pasa, línea a línea.
     */
    fun pasar(
        volumen: Volumen,
        pareja: Pareja,
        rclone: Rclone,
        opciones: Opciones,
        ctx: Contexto = Contexto(),
        alProgreso: (Map<String, Any?>) -> Unit = {},
        decir: (String) -> Unit = {},
        reloj: () -> String = Store::stamp,
    ): Resultado {
        val lineas = ArrayList<String>()
        val cuenta: (String) -> Unit = { lineas.add(it); decir(it) }
        val tag = if (ctx.simulacro) " [DRY-RUN]" else ""
        cuenta("=== ${pareja.name} (${pareja.mode.name})$tag ===")

        fun acabar(codigo: Int, log: File? = null, explicacion: String? = null, resync: Boolean = false,
                   conflictos: List<Conflictos.Conflicto> = emptyList()): Resultado {
            if (!ctx.simulacro && codigo != SALTADA) Resultados.apuntar(volumen.state, pareja.name, codigo, log, reloj)
            return Resultado(pareja.name, codigo, lineas, log, explicacion, conflictos, resync)
        }

        if (pareja.llavero) {
            cuenta("[${pareja.name}] El llavero no se sincroniza en el teléfono todavía.")
            return Resultado(pareja.name, SALTADA, lineas)
        }
        try {
            volumen.crearDistribucion()
        } catch (e: IOException) {
            cuenta("[${pareja.name}] ERROR: ${e.message}")
            return Resultado(pareja.name, ABORTO, lineas)
        }
        // Lo que el texto del config no delata (un enlace) a `.prdrive/`, con la clave.
        volumen.problemaContencion(pareja)?.let {
            cuenta("[${pareja.name}] ERROR: $it Se aborta.")
            return acabar(ABORTO)
        }

        val tipos = Bisync.tiposDeRemote(volumen.leerRcloneConf())
        var needResync = ctx.forzarResync
        if (pareja.isBisync) {
            val (resync, _, aborto) = preflight(volumen, pareja, ctx, tipos, cuenta)
            needResync = resync
            if (aborto != null) return acabar(aborto)
        }

        val carpeta = pareja.carpetaEn(volumen.raiz)
        if (!carpeta.exists()) {
            if (pareja.mode.borraEnRemoto) {
                cuenta(
                    "[${pareja.name}] ERROR: la ruta local '${pareja.local}' no existe y es el " +
                        "origen de un espejo de subida. Se aborta (no se crea vacía a propósito: " +
                        "un origen vacío vaciaría el remoto).",
                )
                return acabar(ABORTO)
            }
            cuenta("[${pareja.name}] La ruta local '${pareja.local}' no existe. Creándola...")
            carpeta.mkdirs()
        }

        val ffile = Bisync.filtersFileFor(pareja, volumen.filtros)
        val tramos = Orden.tramos(
            pareja, binario = "rclone", filtros = ffile?.absolutePath,
            config = volumen.rcloneConf.absolutePath, logfile = "",
            workdir = volumen.workdir(pareja).absolutePath,
            simulacro = ctx.simulacro, resync = needResync, sello = ctx.sello,
        )
        val final = try {
            rclone.ejecutar(llamadaDe(tramos, opciones), alProgreso)
        } catch (e: OrdenInvalida) {
            Final(false, e.message ?: "", "Error: ${e.message}")
        }
        val codigo = if (final.ok) 0 else FALLO
        val texto = if (final.ok || final.error.isEmpty() || final.error in final.log) {
            final.log
        } else {
            final.log + (if (final.log.isEmpty() || final.log.endsWith("\n")) "" else "\n") + final.error + "\n"
        }

        val log = if (codigo != 0 || ctx.keepLogs) guardarLog(volumen.logs, pareja.name, texto) else null
        var explicacion: String? = null
        if (codigo == 0) {
            cuenta("[${pareja.name}] OK." + (log?.let { " Log: ${it.name}" } ?: ""))
        } else {
            cuenta("[${pareja.name}] FALLÓ. " + (log?.let { "Log: ${it.name}" } ?: ""))
            explicacion = explicar(texto)
            explicacion?.let { cuenta("  >> $it") }
        }
        val resultado = acabar(codigo, log, explicacion, needResync)
        if (!pareja.isBisync || ctx.simulacro) return resultado
        // Tras cada pasada, buena o mala: rclone renombra al perdedor al detectar
        // el conflicto, y un fallo posterior no lo quita.
        val conflictos = try {
            Conflictos.actualizarPareja(pareja, volumen.raiz, volumen.state, reloj)
        } catch (_: IOException) {
            emptyList()
        }
        if (conflictos.isNotEmpty()) {
            cuenta(
                "[${pareja.name}] AVISO: ${conflictos.size} fichero(s) en conflicto: cambiaron " +
                    "en los dos lados y hay dos versiones.",
            )
            conflictos.take(CONFLICTS_SHOWN).forEach { cuenta("  conflicto: ${it.relativa}") }
            if (conflictos.size > CONFLICTS_SHOWN) cuenta("  … y ${conflictos.size - CONFLICTS_SHOWN} más")
        }
        return resultado.copy(lineas = lineas, conflictos = conflictos)
    }
}
