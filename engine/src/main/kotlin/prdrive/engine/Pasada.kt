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
 * Lo que el llavero hace distinto (resincronizarse solo, repetir sus frenos,
 * volver a apuntar una base) está aparte, en [PasadaLlavero].
 *
 * Lo que NO hay del escritorio: el parte de la flota, el diario de pasadas, la
 * medida de si la pasada movió algo y `--force`, que es de una persona desde
 * «Reparación».
 */
object Pasada {

    /** Pareja no ejecutada (ni OK ni fallo): pide resync y nadie lo ha aprobado. */
    const val SALTADA = -1

    /** El «error sin clasificar» de rclone: lo que vale una pasada que ha fallado. */
    const val FALLO = 1

    /** Lo que vale una pasada que la propia pasada aborta antes de llamar a rclone. */
    const val ABORTO = 2

    /**
     * El llavero tiene una base que no está entera y la pasada no corre
     * (`sync.LLAVERO_ROTO`). Es el «error sin clasificar» de rclone: cuenta como
     * fallo, y la próxima pasada lo vuelve a intentar.
     */
    const val LLAVERO_ROTO = 2

    /**
     * El llavero va sin contraseña y el dispositivo no está cifrado: la pasada no
     * corre (`sync.LLAVERO_SIN_CIFRAR`). Sin cifrar, la llave estaría junto a la
     * base. Cuenta como fallo, y la próxima pasada lo vuelve a comprobar.
     */
    const val LLAVERO_SIN_CIFRAR = 3

    /**
     * La pareja vive en una carpeta compartida y nadie ha comprobado que se vea
     * entera (`Contexto.accesoCompartido`). Sin el permiso de «todos los
     * archivos», Android esconde lo que creó otra app, bisync lo vería borrado y
     * lo borraría en el remoto. Falla cerrado, como `Contexto.cifrado`.
     */
    const val SIN_COMPROBAR_ACCESO =
        "La pareja vive en una carpeta compartida del teléfono y nadie ha comprobado que se " +
            "vea entera: sincronizar podría borrar en el remoto lo que Android no deja ver."

    /**
     * El `rclone.conf` no lleva la carpeta compartida de la pareja: un
     * movimiento cortado entre apuntarla y reescribir el `combine`. Sincronizar
     * miraría la carpeta privada del volumen, que está vacía.
     */
    const val COMBINE_VIEJO =
        "El rclone.conf no apunta la pareja a su carpeta compartida: un movimiento se quedó a " +
            "medias. Se arregla con «Terminar de mover» o con «Volver al volumen»."

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

    /**
     * Lo que rclone apunta de cada fichero que una simulación (`--dry-run`) habría
     * borrado (`frenos._SIMULADO`). Con `versions = true` (`--backup-dir1/2`) lo
     * que se borra va al directorio de versiones y rclone lo apunta como
     * `Skipped move into backup dir` en vez de `Skipped delete`
     * (`DeleteFileWithBackupDir()`); lo que solo se sobrescribe es otra cosa
     * (`Skipped move to <nombre>~<marca>` y `Skipped copy`) y no entra. El `.` de
     * Python solo deja fuera el `\n`, y el de Java también el `\r`: se escribe la clase.
     */
    private val SIMULADO =
        Regex("NOTICE: ([^\\n]*?): Skipped (?:delete|move into backup dir) as --dry-run is set")

    /**
     * Los ficheros que una simulación habría borrado (`frenos.borrarian()`), sin
     * repetir y en orden alfabético: rclone los apunta en el orden en que sus
     * hilos llegan a ellos, que cambia de una vez a otra. El orden es el de
     * Python ([ORDEN_PY]), por puntos de código.
     */
    fun borrarian(log: String): List<String> =
        SIMULADO.findAll(log).map { it.groupValues[1] }.toSet().sortedWith(ORDEN_PY)

    /**
     * Lo que se dice en la consola cuando es el llavero el que ha topado con un
     * freno de borrados (`frenos.EXPLICACION_LLAVERO`). Las de [EXPLICACIONES]
     * dicen cómo forzar la pasada, y al llavero no se le fuerza desde fuera.
     */
    const val EXPLICACION_LLAVERO =
        "El freno de borrados del llavero no se salta: sus bases son tus contraseñas. Comprueba " +
            "que no falte ninguna en un lado y, si hay copias de conflicto, usa «Combinar» al abrir " +
            "el llavero."

    /** Qué pasa y qué hacer cuando al llavero lo para «han cambiado todos» (`frenos.SALIDA_LLAVERO_CAMBIADOS`). */
    private const val SALIDA_LLAVERO_CAMBIADOS =
        "prdrive repite solo la pasada una vez con --force cuando es esto lo único que la para y " +
            "forzarla no borraría nada (lo que pisa cada lado queda en .prversions/); si la ves aquí, " +
            "no ha podido hacerlo o no ha bastado. Lo que guardes en el llavero no sube mientras tanto. " +
            "Desde aquí no se fuerza: `python sync.py keychain --resync` rehace la referencia y se " +
            "queda con la base más nueva, y la otra queda en .prversions/."

    /** Lo que se dice cuando es el llavero el que ha topado con «han cambiado todos». */
    const val EXPLICACION_LLAVERO_CAMBIADOS =
        "Han cambiado todos los ficheros de un lado del llavero (suele ser la hora de un dispositivo " +
            "nuevo o de otra versión) y bisync se ha parado. $SALIDA_LLAVERO_CAMBIADOS"

    /**
     * Lo que se le dice del llavero en lugar de [explicacion], la de un freno
     * (`frenos.explicacion_llavero()`). Cada freno tiene la suya; el de borrados
     * es la general.
     */
    fun explicacionLlavero(explicacion: String): String =
        if (explicacion == EXPLICACION_CAMBIADOS) EXPLICACION_LLAVERO_CAMBIADOS else EXPLICACION_LLAVERO

    /** Las explicaciones de los frenos (`frenos.EXPLICACIONES`): las que dicen cómo forzar. */
    val EXPLICACIONES: List<String> = listOf(EXPLICACION_BORRADOS, EXPLICACION_CAMBIADOS, EXPLICACION_ESPEJO)

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

    /**
     * Las últimas líneas del log, sin las de progreso (`print_log_tail`): con
     * `splitlines()`, y las vacías se quedan, como allí.
     */
    fun cola(texto: String, lineas: Int = LOG_TAIL_LINES): List<String> =
        splitlinesPy(texto).filter { !esProgreso(it) }.takeLast(lineas)

    /**
     * Si una línea es una estadística de rclone: `progress._ESTADISTICA` de
     * prdrive, que replica `StatsInfo.String()` (fs/accounting/stats.go). Con el
     * `\d`, el `\w` y el `\S` de Python, que son Unicode, escritos a mano porque
     * ICU no admite `(?U)`.
     */
    fun esProgreso(linea: String): Boolean = ESTADISTICA.containsMatchIn(linea)

    private val ESTADISTICA: Regex by lazy {
        val tamano = "(\\p{Nd}+(?:\\.\\p{Nd}+)?) ?([KMGTPE]?)i?B"
        Regex(
            "(?<![\\p{L}\\p{N}_.])$tamano / $tamano, (\\p{Nd}+%|-), $tamano/s, ETA [^$CLASE_ESPACIO_PY]",
        )
    }

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

    /** Una opción global de rclone: su tipo (`bool`, `stringArray`…) y su letra, si la tiene. */
    data class Opcion(val tipo: String, val corto: String? = null) {
        val booleana: Boolean get() = tipo == "bool"

        /** Se repite y se acumula (`StringArrayVarP`): `--exclude-from a --exclude-from b` son las dos. */
        val lista: Boolean get() = tipo == "stringArray"
    }

    /**
     * Lo que rclone sabe de sus opciones (`options/info`, bloques `main`,
     * `filter` y `log`) y de los backends que lleva (`config/providers`): si un
     * flag existe, si se lleva el argumento siguiente, si se acumula, y su letra.
     * Es lo que usa la línea de órdenes de rclone para leer los argumentos.
     */
    data class Opciones(
        val main: Map<String, Opcion>,
        val filtro: Map<String, Opcion>,
        val log: Map<String, Opcion> = emptyMap(),
        /** Los flags de los backends (`sftp-set-modtime`), sin los guiones del principio. */
        val deBackend: Set<String> = emptySet(),
    ) {
        companion object {
            /**
             * De la salida de `options/info` (`{bloque: [{Name, Type, ShortOpt}]}`) y
             * de la lista de backends: su prefijo de flag y el nombre de sus opciones
             * (lo que da `config/providers`, ver [backendsDeProviders]).
             */
            fun deOptionsInfo(
                info: Map<String, Any?>,
                backends: List<kotlin.Pair<String, List<String>>> = emptyList(),
            ): Opciones {
                fun bloque(nombre: String): Map<String, Opcion> =
                    (info[nombre] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().associate {
                        it["Name"].toString() to Opcion(it["Type"].toString(), (it["ShortOpt"] as? String)?.ifEmpty { null })
                    }
                val deBackend = backends.flatMap { (prefijo, nombres) ->
                    nombres.map { "$prefijo-" + it.replace('_', '-') }
                }.toSet()
                return Opciones(bloque("main"), bloque("filter"), bloque("log"), deBackend)
            }

            /** `config/providers` → `[(prefijo, [opción])]`. */
            fun backendsDeProviders(providers: Map<String, Any?>): List<kotlin.Pair<String, List<String>>> =
                (providers["providers"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { p ->
                    p["Prefix"].toString() to (p["Options"] as? List<*>).orEmpty().map {
                        if (it is Map<*, *>) it["Name"].toString() else it.toString()
                    }
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

    /** La letra de bisync: `-1` es `--resync` (cmd/bisync/cmd.go). */
    private const val CORTO_RESYNC = "1"

    /**
     * El `--max-delete` de un bisync, como lo lee su línea de órdenes
     * (`Options.applyContext()`, cmd/bisync/cmd.go): sin él, o negativo, el de
     * fábrica ([TOPE_BISYNC_DE_FABRICA], el `DefaultMaxDelete` de rclone); y
     * entre 0 y 100. El RPC no hace nada de eso: sin `maxDelete` usa 0 (no deja
     * borrar nada) y fuera de 0..100 se niega (cmd/bisync/rc.go), así que se
     * manda siempre, ya resuelto.
     */
    fun maxDeleteBisync(dado: Long?): Long = when {
        dado == null || dado < 0 -> TOPE_BISYNC_DE_FABRICA.toLong()
        else -> minOf(dado, 100L)
    }

    /** `frenos.TOPE_BISYNC_DE_FABRICA`: el `DefaultMaxDelete` de bisync (un %). */
    const val TOPE_BISYNC_DE_FABRICA = 50

    /**
     * Flags de la línea de órdenes de rclone que no son opciones (cmd/cmd.go y
     * los «not converted into options yet» de fs/config/configflags) y que aquí
     * no significan nada: rclone ya lee el `rclone.conf` del volumen, el log y su
     * nivel los lleva la app (INFO para todas, con `options/set`), el progreso
     * se pide a `core/stats`, y la caché y los temporales los pone la app. Se
     * leen (con su argumento, si lo llevan) y no viajan. Con `true`, se llevan
     * argumento.
     */
    private val SIN_EQUIVALENTE: Map<String, Boolean> = mapOf(
        "config" to true, "verbose" to false, "quiet" to false, "stats" to true,
        "cache-dir" to true, "temp-dir" to true, "cpuprofile" to true, "memprofile" to true,
        "dump-headers" to false, "dump-bodies" to false,
    )

    /** Las letras de los de [SIN_EQUIVALENTE] (configflags: `-v` es una cuenta, `-q` un booleano). */
    private val CORTOS_SIN_EQUIVALENTE = mapOf("v" to "verbose", "q" to "quiet")

    /**
     * Flags de la línea de órdenes que SÍ cambian lo que hace la pasada pero no
     * son opciones de rclone, así que por el RPC no hay cómo pasarlos
     * (fs/config/configflags: «flags we haven't converted into options yet»).
     * Con `true`, se llevan argumento.
     */
    private val SOLO_LINEA_DE_ORDENES: Map<String, Boolean> = mapOf(
        "delete-before" to false, "delete-during" to false, "delete-after" to false,
        "bind" to true, "disable" to true, "header" to true, "header-upload" to true,
        "header-download" to true, "metadata-set" to true,
    )

    /**
     * La orden no se puede pasar a rclone. [message] es lo que diría su línea de
     * órdenes, si lo diría; [explicacion], lo que se le cuenta a la persona
     * cuando el problema es del teléfono y no del config.
     */
    class OrdenInvalida(mensaje: String, val explicacion: String? = null) : Exception(mensaje)

    private fun desdeElTelefono(flag: String, porque: String) = OrdenInvalida(
        "$flag: no se puede pasar desde el teléfono",
        "El config de la pareja lleva $flag, que en el ordenador vale pero desde el teléfono " +
            "no se puede pasar: $porque. Quítalo de la pareja, o llévalo a la conexión del " +
            "remoto en el rclone.conf si es una opción de su backend.",
    )

    /** Cómo se llama un flag para rclone: su opción, el bloque en que está y su tipo. */
    private class Flag(val nombre: String, val opcion: String, val tipo: Opcion, val clase: Clase)

    private enum class Clase { BISYNC, VACIOS, IGNORAR, PRINCIPAL, FILTRO, INCLUIR, EXCLUIR }

    private fun flag(nombre: String, bisync: Boolean, opciones: Opciones): Flag {
        val opcion = nombre.replace('-', '_')
        val bool = Opcion("bool")
        val conValor = Opcion("string")
        return when {
            // La línea de órdenes no normaliza `_` (pflag sin NormalizeFunc):
            // `--max_delete` no existe, aunque su opción se llame así.
            '_' in nombre -> throw OrdenInvalida("unknown flag: --$nombre")
            bisync && nombre in PARAMETROS_BISYNC ->
                Flag(nombre, PARAMETROS_BISYNC.getValue(nombre), if (nombre in BISYNC_CON_VALOR) conValor else bool, Clase.BISYNC)
            nombre == "create-empty-src-dirs" -> Flag(nombre, "createEmptySrcDirs", bool, Clase.VACIOS)
            nombre in SIN_EQUIVALENTE -> Flag(nombre, opcion, if (SIN_EQUIVALENTE.getValue(nombre)) conValor else bool, Clase.IGNORAR)
            opcion in opciones.log -> Flag(nombre, opcion, opciones.log.getValue(opcion), Clase.IGNORAR)
            nombre == "include" -> Flag(nombre, opcion, Opcion("stringArray"), Clase.INCLUIR)
            nombre == "exclude" -> Flag(nombre, opcion, Opcion("stringArray"), Clase.EXCLUIR)
            opcion in opciones.main -> Flag(nombre, opcion, opciones.main.getValue(opcion), Clase.PRINCIPAL)
            opcion in opciones.filtro -> Flag(nombre, opcion, opciones.filtro.getValue(opcion), Clase.FILTRO)
            nombre in SOLO_LINEA_DE_ORDENES ->
                throw desdeElTelefono("--$nombre", "no es una opción de rclone sino de su línea de órdenes")
            nombre in opciones.deBackend ->
                throw desdeElTelefono("--$nombre", "es una opción de un backend, y el teléfono no abre los remotes con flags")
            else -> throw OrdenInvalida("unknown flag: --$nombre")
        }
    }

    /** El flag de una letra (`ShortOpt` en `options/info`), o `null` si no hay. */
    private fun flagCorto(letra: String, bisync: Boolean, opciones: Opciones): String? {
        if (bisync && letra == CORTO_RESYNC) return "resync"
        CORTOS_SIN_EQUIVALENTE[letra]?.let { return it }
        for (bloque in listOf(opciones.main, opciones.filtro, opciones.log)) {
            bloque.entries.firstOrNull { it.value.corto == letra }?.let { return it.key.replace('_', '-') }
        }
        return null
    }

    /**
     * Traduce la orden de una pareja ([Orden.tramos]) a la llamada rc que hace
     * lo mismo.
     *
     * Los argumentos se leen como los lee rclone (pflag): `--x=v`, `--x v` o
     * `--x` si la opción es booleana; letras sueltas o juntas (`-u`, `-uc`,
     * `-f regla`, `-fregla`); gana el último, salvo en las que se acumulan
     * (`stringArray`), que van todas y en su orden, como lista: un texto plano
     * rclone lo partiría por las comas (`configstruct.StringToInterface`). Lo
     * que es de bisync va como sus parámetros; las opciones globales, como
     * parámetros planos con su nombre de opción (`modify_window`) y el texto del
     * flag, que rclone interpreta con el mismo analizador que la línea de
     * órdenes (`fs/rc/context.go`, `ParseOptions`); `--include`/`--exclude`, en
     * `_filter`.
     *
     * @throws OrdenInvalida con el mensaje de rclone («unknown flag: --x»), para
     *   que [explicar] diga lo mismo que en el escritorio; o con su
     *   [OrdenInvalida.explicacion] si es algo que el escritorio sí acepta.
     */
    fun llamadaDe(tramos: List<Orden.Tramo>, opciones: Opciones): Llamada {
        val cabeza = tramos.first().args
        val verbo = cabeza[1]
        val args = tramos.drop(1).flatMap { it.args }
        val bisync = verbo == "bisync"
        val parametros = LinkedHashMap<String, Any?>()
        val listas = LinkedHashMap<String, MutableList<String>>()
        val incluir = ArrayList<String>()
        val excluir = ArrayList<String>()
        var maxDelete: Long? = null
        when (verbo) {
            "bisync" -> { parametros["path1"] = cabeza[2]; parametros["path2"] = cabeza[3] }
            "copy", "sync" -> { parametros["srcFs"] = cabeza[2]; parametros["dstFs"] = cabeza[3] }
            else -> throw OrdenInvalida("unknown command \"$verbo\" for \"rclone\"")
        }

        fun poner(f: Flag, valor: String) {
            when (f.clase) {
                Clase.BISYNC -> parametros[f.opcion] = when {
                    f.nombre == "max-delete" -> {
                        maxDelete = intPy(valor)
                            ?: throw OrdenInvalida("invalid argument \"$valor\" for \"--max-delete\" flag")
                        return
                    }
                    f.tipo.booleana -> valorBooleano(f.nombre, valor)
                    else -> valor
                }
                Clase.VACIOS -> parametros[f.opcion] = valorBooleano(f.nombre, valor)
                Clase.IGNORAR -> Unit
                Clase.INCLUIR -> incluir.add(valor)
                Clase.EXCLUIR -> excluir.add(valor)
                Clase.PRINCIPAL, Clase.FILTRO ->
                    if (f.tipo.lista) listas.getOrPut(f.opcion) { ArrayList() }.add(valor) else parametros[f.opcion] = valor
            }
        }

        var i = 0
        while (i < args.size) {
            val arg = args[i++]
            when {
                arg.startsWith("--") && arg.length > 2 -> {
                    val igual = arg.indexOf('=')
                    val nombre = if (igual < 0) arg.substring(2) else arg.substring(2, igual)
                    val f = flag(nombre, bisync, opciones)
                    val valor: String = when {
                        igual >= 0 -> arg.substring(igual + 1)
                        f.tipo.booleana || f.nombre == "verbose" -> "true"
                        i < args.size -> args[i++]
                        else -> throw OrdenInvalida("flag needs an argument: --$nombre")
                    }
                    poner(f, valor)
                }
                arg.startsWith("-") && arg.length > 1 && arg != "--" -> {
                    // pflag, parseSingleShortArg: letra a letra; la que lleva
                    // valor se queda con el resto (o con el argumento siguiente).
                    var resto = arg.substring(1)
                    while (resto.isNotEmpty()) {
                        val letra = resto.substring(0, resto.offsetByCodePoints(0, 1))
                        resto = resto.substring(letra.length)
                        val nombre = flagCorto(letra, bisync, opciones)
                            ?: throw OrdenInvalida("unknown shorthand flag: '$letra' in $arg")
                        // Una letra es su flag largo, y pasa por lo mismo que él
                        // (`problemaFlag`): `problema_extra` solo mira los de dos
                        // guiones, y `-1` sería un `--resync` en cada pasada.
                        problemaFlag(nombre)?.let {
                            throw OrdenInvalida(
                                "-$letra (--$nombre): no se admite",
                                "La pareja lleva -$letra en 'extra_flags', que para rclone es --$nombre: $it. " +
                                    "Quítalo de 'extra_flags'.",
                            )
                        }
                        val f = flag(nombre, bisync, opciones)
                        val valor: String = when {
                            resto.startsWith("=") -> resto.substring(1).also { resto = "" }
                            f.tipo.booleana || f.nombre == "verbose" -> "true"
                            resto.isNotEmpty() -> resto.also { resto = "" }
                            i < args.size -> args[i++]
                            else -> throw OrdenInvalida("flag needs an argument: '$letra' in $arg")
                        }
                        poner(f, valor)
                    }
                }
                else -> throw OrdenInvalida("unknown command \"$arg\" for \"rclone $verbo\"")
            }
        }
        if (bisync) parametros["maxDelete"] = maxDeleteBisync(maxDelete)
        for ((opcion, valores) in listas) parametros[opcion] = valores
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

        /**
         * Crea la carpeta [destino] (un `operations/mkdir` con `fs = destino` y
         * `remote = ""`; el `rclone mkdir` de `crear_carpeta_remota()`). Si ya
         * está, no hace nada.
         */
        fun mkdir(destino: String): Final
    }

    /**
     * Lo que no cambia de una pareja a otra en una misma ejecución
     * (`RunContext`). [sello] es el sufijo de las versiones, uno por ejecución
     * para que lo apartado en ella comparta marca.
     *
     * @property cifrado Si el dispositivo está cifrado (`cifrada.estado()` en el
     *   escritorio). Sin él, un llavero sin contraseña no se sincroniza
     *   ([LLAVERO_SIN_CIFRAR]). Por defecto no, como prdrive: falla cerrado.
     * @property accesoCompartido Si la app puede ver entera la carpeta compartida de
     *   una pareja: `null` si sí, o por qué no. Solo se pregunta a las parejas que
     *   viven fuera del volumen. Sin él, esas parejas no corren
     *   ([SIN_COMPROBAR_ACCESO]): falla cerrado.
     * @property dormir Cómo se espera antes de mirar otra vez una base que no
     *   está entera ([Llavero.rotas]); se pasa para que los tests no esperen.
     */
    data class Contexto(
        val simulacro: Boolean = false,
        val forzarResync: Boolean = false,
        val resyncAprobado: Boolean = false,
        val keepLogs: Boolean = false,
        val sello: String = selloVersiones(),
        val cifrado: Boolean = false,
        val dormir: (Long) -> Unit = { Thread.sleep(it) },
        val accesoCompartido: ((Pareja) -> String?)? = null,
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
        } else if (needResync && pareja.llavero && razones.isNotEmpty()) {
            // El llavero no espera a que nadie lo apruebe: con `resync-mode =
            // newer` gana la versión más nueva y la otra queda en `.prversions/`
            // (el backup-dir también vale en un --resync). Saltarlo lo dejaría
            // sin sincronizar sin que nadie se enterase.
            razones.forEach { decir("  requiere --resync -> $it") }
            decir(
                "[${pareja.name}] El llavero se resincroniza solo: gana lo más nuevo y lo " +
                    "otro queda en $VERSIONS_DIR.",
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
     * Por qué una pareja que vive (o debería vivir) en una carpeta compartida no
     * puede correr, o `null`. Por orden: la entrada de `compartidas.json` no vale;
     * nadie ha comprobado el acceso; el acceso dice que no; el `rclone.conf` no
     * lleva su carpeta.
     */
    private fun problemaDeCompartida(volumen: Volumen, pareja: Pareja, ctx: Contexto): String? {
        pareja.problemaCompartida?.let { return it }
        if (pareja.carpetaExterna == null) return null
        val acceso = ctx.accesoCompartido ?: return SIN_COMPROBAR_ACCESO
        acceso(pareja)?.let { return it }
        return if (Compartidas.alDia(volumen.leerRcloneConf(), pareja)) null else COMBINE_VIEJO
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
        // Una pareja en una carpeta compartida no corre a ciegas: si no se ve
        // entera, bisync daría por borrado en el remoto lo que no ve.
        problemaDeCompartida(volumen, pareja, ctx)?.let {
            cuenta("[${pareja.name}] ERROR: $it Se aborta.")
            return acabar(ABORTO, explicacion = it)
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
        if (pareja.llavero) {
            PasadaLlavero.antes(pareja, carpeta, ctx, needResync, rclone, cuenta)?.let { return acabar(it) }
        }

        val ffile = Bisync.filtersFileFor(pareja, volumen.filtros)
        var explicacionDeLaOrden: String? = null
        // `build_command()` + `execute()`: la orden de esa pareja, traducida a
        // una llamada rc. El llavero la repite con la pareja cambiada.
        val llamar = PasadaLlavero.Llamar { p, resync, simulacro ->
            val tramos = Orden.tramos(
                p, binario = "rclone", filtros = ffile?.absolutePath,
                config = volumen.rcloneConf.absolutePath, logfile = "",
                workdir = volumen.workdir(p).absolutePath,
                simulacro = simulacro, resync = resync, sello = ctx.sello,
            )
            try {
                rclone.ejecutar(llamadaDe(tramos, opciones), alProgreso)
            } catch (e: OrdenInvalida) {
                explicacionDeLaOrden = e.explicacion
                Final(false, e.message ?: "", "Error: ${e.message}")
            }
        }
        // `dispose_log()`: se tira si fue bien (salvo que se guarden todos).
        val disponer = PasadaLlavero.Disponer { texto, codigo, siempre ->
            if (codigo == 0 && !siempre) null else guardarLog(volumen.logs, pareja.name, texto)
        }
        var final = llamar.llamar(pareja, needResync, ctx.simulacro)
        if (!final.ok && pareja.llavero && !needResync) {
            final = PasadaLlavero.repetirSinFreno(pareja, ctx, final, rclone, llamar, disponer, cuenta)
        }
        val codigo = if (final.ok) 0 else FALLO
        val texto = textoDe(final)

        val log = disponer.disponer(texto, codigo, ctx.keepLogs)
        var explicacion: String? = null
        if (codigo == 0) {
            cuenta("[${pareja.name}] OK." + (log?.let { " Log: ${it.name}" } ?: ""))
        } else {
            cuenta("[${pareja.name}] FALLÓ (código $codigo). " + (log?.let { "Log: ${it.name}" } ?: ""))
            // `print_log_tail()`: lo último del log, sin las líneas de progreso,
            // para que un error que nadie sabe explicar se vea sin abrir el log.
            if (log != null) {
                val ultimas = cola(texto)
                cuenta("--- últimas ${ultimas.size} líneas de ${log.name} ---")
                ultimas.forEach { cuenta("  $it") }
                cuenta("---")
            }
            // Las de un freno dicen cómo forzar la pasada, y al llavero no se le
            // fuerza: para él se dice lo suyo.
            explicacion = explicacionDeLaOrden ?: explicar(texto)?.let {
                if (pareja.llavero && it in EXPLICACIONES) explicacionLlavero(it) else it
            }
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
        if (codigo == 0 && pareja.llavero) {
            PasadaLlavero.reapuntar(
                pareja, carpeta, Llavero.listadoDe(volumen, pareja, tipos), ctx, llamar, disponer, cuenta,
            )
        }
        return resultado.copy(lineas = lineas, conflictos = conflictos)
    }

    /**
     * El log de una llamada: lo que rclone ha escrito y, si falló, su error
     * detrás, salvo que ya esté (es lo que en el escritorio sale por consola y
     * `append_output()` añade al log).
     */
    internal fun textoDe(final: Final): String =
        if (final.ok || final.error.isEmpty() || final.error in final.log) {
            final.log
        } else {
            final.log + (if (final.log.isEmpty() || final.log.endsWith("\n")) "" else "\n") + final.error + "\n"
        }

    /**
     * [pasar] como la llama `run_all()`: un error de E/S a mitad de una pareja
     * (el `OSError` del escritorio: una carpeta que no se puede leer, un log que
     * no se puede guardar) no se lleva las demás. Esa pareja cuenta como
     * fallida, con su línea, y se apunta (salvo en un simulacro).
     */
    fun pasarOFallar(
        volumen: Volumen,
        pareja: Pareja,
        rclone: Rclone,
        opciones: Opciones,
        ctx: Contexto = Contexto(),
        alProgreso: (Map<String, Any?>) -> Unit = {},
        decir: (String) -> Unit = {},
        reloj: () -> String = Store::stamp,
    ): Resultado = try {
        pasar(volumen, pareja, rclone, opciones, ctx, alProgreso, decir, reloj)
    } catch (e: IOException) {
        val linea = "[${pareja.name}] FALLÓ: ${e.message ?: e}"
        decir(linea)
        if (!ctx.simulacro) Resultados.apuntar(volumen.state, pareja.name, FALLO, null, reloj)
        Resultado(pareja.name, FALLO, listOf(linea))
    }
}
