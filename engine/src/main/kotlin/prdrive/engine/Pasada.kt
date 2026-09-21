package prdrive.engine

/**
 * Pasada.kt — Ejecutar una pareja. Espejo de `sync.py` de prdrive.
 *
 * En el escritorio, `sync.py` monta una línea de órdenes y lanza un proceso.
 * Aquí no hay proceso: rclone es una biblioteca y se le habla por su API rc
 * (`librclone.RPC`). Lo que se traduce, entonces, no es «cómo se escribe el
 * comando» sino **cómo viaja cada flag**, y eso es lo único de este fichero
 * que no se parece a su original.
 *
 * ## Los flags viajan SUELTOS, no dentro de `_config`
 *
 * Esto está comprobado ejecutándolo (`rclone/spike`,
 * `comprobarLosGenericos`), y la respuesta no es la que parecía. `jobs.NewJob`
 * llama a `rc.AddConfig(ctx, in)` y `rc.AddFilter(ctx, in)`, y las dos acaban
 * en `rc.ParseOptions` (`fs/rc/context.go`), que admite los valores por dos
 * caminos que **no** son equivalentes:
 *
 *  - **Sueltos, en el primer nivel de la llamada**: los recoge
 *    `configstruct.SetAny`, y los nombres son las etiquetas `config:"…"` de
 *    `fs.ConfigInfo` y `filter.Options`, o sea snake_case (`dry_run`,
 *    `max_delete`, `include`). **Funciona.**
 *  - **Dentro de `_config` / `_filter`**: los recoge `GetStructMissingOK` →
 *    `rc.Reshape`, que es `json.Marshal` + `json.Unmarshal`. Y esas
 *    estructuras **no llevan etiquetas `json`**, así que lo que casa es el
 *    nombre del CAMPO de Go (`DryRun`, `IncludeRule`), no el de la etiqueta.
 *    `encoding/json` descarta sin decir nada lo que no encuentra.
 *
 * O sea que `{"_config":{"dry_run":true}}` —la forma que parecía la buena, y
 * la que este plan daba por hecha— no pone ningún dry-run: **sincroniza de
 * verdad**. Un «Simular» que sincroniza es el peor fallo que puede tener este
 * proyecto, y no da ni un aviso. Medido: con esa forma se copiaron los 2
 * ficheros; sueltos, 0.
 *
 * ## Y los tipos importan, por lo mismo
 *
 * Los parámetros propios de `sync/bisync` sí se leen uno a uno
 * (`cmd/bisync/rc.go`), pero los cuatro que son enumerados pasan por
 * `setEnum`, que trata «no es una cadena» igual que «no está»: un
 * `check-sync = false` escrito como booleano en el TOML **se ignora**, y solo
 * una cadena con un valor inválido da error. Por eso [TipoRpc] existe y por
 * eso [cuadrar] convierte antes de escribir el JSON, en vez de confiar en que
 * rclone avise.
 *
 * `SesionesTest` comprueba los nombres y los tipos de esta tabla contra la
 * ayuda que rclone registra para `sync/bisync`, y compara el JSON que sale de
 * aquí con el que el spike le pasó a rclone de verdad. Si rclone renombra un
 * parámetro, falla un test en vez de dejar de aplicarse un flag.
 */

/** Lo que devuelve una llamada al rc: la salida en JSON y un estado HTTP. */
data class RespuestaRpc(val salida: String, val estado: Int) {
    val ok: Boolean get() = estado == 200
}

/**
 * El canal con rclone.
 *
 * La app lo implementa sobre el `.aar` de `rclone/gobind` (una línea por
 * función); los tests, con un doble que no ejecuta nada. Es el punto de
 * indirección que en prdrive es `catalog.run()`: **todo lo que toca rclone
 * pasa por aquí**, para que ningún test necesite un rclone.
 */
interface Rclone {
    /** Un método del rc con su entrada en JSON. */
    fun rpc(metodo: String, entrada: String): RespuestaRpc

    /** Vacía el log acumulado (`RcloneLogReiniciar`). */
    fun logReiniciar()

    /** El log acumulado desde la última llamada a [logReiniciar]. */
    fun logTexto(): String

    /**
     * Hasta qué nivel registra rclone, con los nombres de su `--log-level`.
     *
     * Está en el canal y no en los parámetros de la llamada porque en rclone
     * es **global**: ver [Pasada.nivelDeLog] y `rclone/gobind/prdrive.go`.
     */
    fun nivelDeLog(nombre: String)
}

/** El tipo que rclone espera de un parámetro, que no siempre es el del TOML. */
enum class TipoRpc { BOOL, ENTERO, CADENA }

/** Lo que cambia de una pasada a otra; el resto está en la [Pareja]. */
data class Opciones(
    /** Rehacer el baseline. Solo lo mira bisync. */
    val resync: Boolean = false,
    val dryRun: Boolean = false,
    /** `state/<pareja>/`, absoluto: la ruta del volumen la sabe la app. */
    val workdir: String? = null,
    /** El fichero de filtros ya escrito, absoluto. Solo bisync. */
    val filtersFile: String? = null,
    /** El `_group` con el que se le preguntan las estadísticas a rclone. */
    val grupo: String? = null,
)

/** Una llamada al rc, montada y lista. */
data class Peticion(val metodo: String, val params: Map<String, Any?>) {
    val json: String get() = escribirJson(params)
}

/** El estado de un job asíncrono, tal y como lo cuenta `job/status`. */
data class EstadoJob(
    val acabado: Boolean,
    val exito: Boolean,
    val error: String,
    val salida: Map<String, Any?>,
)

/** Cómo acabó una pasada. */
data class Resultado(
    val pareja: String,
    /** 0 si fue bien, [Pasada.SALTADA] si no se ejecutó, y si no el código de fallo. */
    val codigo: Int,
    /** El log de ESTA pasada, que es lo que se le enseña al usuario. */
    val log: String,
    /** Lo que devolvió el método. Para bisync trae `session`, `basePath`… */
    val salida: Map<String, Any?> = emptyMap(),
    /** El error del job, que en bisync es un lacónico «bisync aborted». */
    val error: String = "",
    /** La traducción de [Pasada.KNOWN_ERRORS], si alguna aguja encaja. */
    val explicacion: String? = null,
) {
    val ok: Boolean get() = codigo == 0
    val saltada: Boolean get() = codigo == Pasada.SALTADA

    /** El nombre de sesión que rclone usó de verdad, para comprobar el prefijo. */
    val sesion: String? get() = salida["session"] as? String
}

object Pasada {

    /** Código interno: la pareja no se ejecutó (ni bien ni mal). De `sync.py`. */
    const val SALTADA = -1

    /** Líneas de log que se enseñan cuando algo falla. De `sync.py`. */
    const val LINEAS_DE_COLA = 15

    /** Ficheros en conflicto que se nombran en la salida. De `sync.py`. */
    const val CONFLICTOS_MOSTRADOS = 5

    /** El estado con el que rclone responde a una llamada bien hecha. */
    const val OK_HTTP = 200

    // -----------------------------------------------------------------------
    // Qué método es cada modo
    // -----------------------------------------------------------------------

    /**
     * El método del rc de cada verbo de `MODES`.
     *
     * No hay `sync/bisync` para los modos espejo ni al revés: el verbo de
     * [Modo] ya dice cuál es, y es el mismo reparto que en el escritorio.
     */
    val METODOS: Map<String, String> = linkedMapOf(
        "bisync" to "sync/bisync",
        "copy" to "sync/copy",
        "sync" to "sync/sync",
    )

    fun metodo(pareja: Pareja): String = METODOS[pareja.mode.verb]
        ?: throw ConfigError(
            "[${pareja.name}] el modo '${pareja.mode.name}' usa el verbo " +
                "'${pareja.mode.verb}', que no tiene método en el rc de rclone.",
        )

    // -----------------------------------------------------------------------
    // La tabla de traducción
    // -----------------------------------------------------------------------

    /**
     * Los parámetros propios de `sync/bisync`, por su nombre de flag, con el
     * tipo que rclone espera (`cmd/bisync/rc.go`, `rcBisync`).
     *
     * El nombre del parámetro es el flag en camelCase ([aCamello]), sin
     * excepciones — comprobado contra la ayuda que rclone registra
     * (`SesionesTest`). Los tipos: `GetBool` → [TipoRpc.BOOL], `GetInt64` →
     * [TipoRpc.ENTERO], y todo lo demás —`GetString`, `GetFsDuration` y los
     * cuatro enumerados de `setEnum`— → [TipoRpc.CADENA].
     */
    val PARAMETROS_BISYNC: Map<String, TipoRpc> = linkedMapOf(
        "dry-run" to TipoRpc.BOOL,
        "max-delete" to TipoRpc.ENTERO,
        "resync" to TipoRpc.BOOL,
        "check-access" to TipoRpc.BOOL,
        "force" to TipoRpc.BOOL,
        "create-empty-src-dirs" to TipoRpc.BOOL,
        "remove-empty-dirs" to TipoRpc.BOOL,
        "no-cleanup" to TipoRpc.BOOL,
        "ignore-listing-checksum" to TipoRpc.BOOL,
        "resilient" to TipoRpc.BOOL,
        "recover" to TipoRpc.BOOL,
        "no-slow-hash" to TipoRpc.BOOL,
        "slow-hash-sync-only" to TipoRpc.BOOL,
        "download-hash" to TipoRpc.BOOL,
        "check-filename" to TipoRpc.CADENA,
        "filters-file" to TipoRpc.CADENA,
        "workdir" to TipoRpc.CADENA,
        "backup-dir1" to TipoRpc.CADENA,
        "backup-dir2" to TipoRpc.CADENA,
        "conflict-suffix" to TipoRpc.CADENA,
        "compare" to TipoRpc.CADENA,
        "max-lock" to TipoRpc.CADENA,
        // Los cuatro de `setEnum`: una cadena, o se ignoran sin avisar.
        "check-sync" to TipoRpc.CADENA,
        "resync-mode" to TipoRpc.CADENA,
        "conflict-resolve" to TipoRpc.CADENA,
        "conflict-loser" to TipoRpc.CADENA,
    )

    /**
     * Los parámetros propios de `sync/copy` y `sync/sync` (`fs/sync/rc.go`,
     * `rcSyncCopyMove`). Los demás flags de esos modos van sueltos.
     */
    val PARAMETROS_COPY: Map<String, TipoRpc> = linkedMapOf(
        "create-empty-src-dirs" to TipoRpc.BOOL,
    )

    /**
     * Flags que se quedan por el camino a propósito, con el motivo.
     *
     * No es lo mismo que [DEL_MOTOR]: estos se pueden escribir en el TOML —el
     * escritorio los usa y el catálogo es el mismo fichero para los dos— y
     * aquí simplemente no tienen a dónde ir. Se dice, no se calla: [traducir]
     * los devuelve para que la pantalla los pueda enseñar.
     */
    val IGNORADOS: Map<String, String> = linkedMapOf(
        "verbose" to "el nivel del log es global en la biblioteca: lo pone RcloneLogNivel()",
        "log-level" to "el nivel del log es global en la biblioteca: lo pone RcloneLogNivel()",
        "quiet" to "el nivel del log es global en la biblioteca: lo pone RcloneLogNivel()",
        "log-file" to "aquí el log se recoge en memoria (RcloneLogTexto), sin fichero",
        "log-format" to "el log no se formatea: se recoge tal cual del sumidero",
        "use-json-log" to "el log no se formatea: se recoge tal cual del sumidero",
        "stats" to "el progreso sale de core/stats como datos, no de una línea de texto",
        "stats-one-line" to "el progreso sale de core/stats como datos, no de una línea de texto",
        "stats-one-line-date" to "el progreso sale de core/stats como datos",
        "stats-one-line-date-format" to "el progreso sale de core/stats como datos",
        "progress" to "detrás de esto no hay una terminal que repintar",
        "color" to "se apaga en RcloneInitialize; el _config lo ignora sin quejarse",
        "config" to "lo fija RcloneSetConfigPath ANTES de arrancar la biblioteca",
    )

    /**
     * Los que pone la app en cada pasada, y que por eso un TOML no puede
     * traer. Son las mismas claves que `ui/flags_editor.RESERVED` en el
     * escritorio (`FlagsTest` lo comprueba contra los vectores), con el motivo
     * reescrito donde allí decía «lo pone sync.py».
     */
    val DEL_MOTOR: Map<String, String> = linkedMapOf(
        "config" to "lo pone la app: es el rclone.conf del volumen",
        "log-file" to "aquí no hay fichero de log: el log se recoge en memoria",
        "dry-run" to "es el «Simular» de la app, para que valga en todas las parejas",
        "workdir" to "lo pone la app: state/<pareja>/, y cambiarlo mueve el baseline",
        "resync" to "es el --resync de la app, que además pregunta antes",
        "filters-file" to "sale de los patrones incluir/excluir de la pareja",
        "filter" to "usa los patrones incluir/excluir; mezclarlos rompe el filtrado",
        "filter-from" to "usa los patrones incluir/excluir; mezclarlos rompe el filtrado",
        "include" to "usa los patrones «incluir» de la pareja",
        "exclude" to "usa los patrones «excluir» de la pareja",
    )

    /** `max-lock` -> `maxLock`. Sin excepciones: ver [PARAMETROS_BISYNC]. */
    fun aCamello(flag: String): String {
        val partes = flag.replace("_", "-").split("-")
        return partes.first() + partes.drop(1).joinToString("") { tramo ->
            tramo.replaceFirstChar { it.uppercaseChar() }
        }
    }

    /** `max-delete` -> `max_delete`: el nombre de la etiqueta `config:`. */
    fun aSnake(flag: String): String = flag.replace("-", "_")

    /**
     * El valor con el tipo que rclone espera, o un error que lo explica.
     *
     * Convertir un booleano a `"true"` no es una licencia: es lo que hace
     * rclone con `--check-sync=false` en la línea de órdenes, y lo que el RPC
     * necesita para no ignorarlo. Al revés no: una cadena donde va un booleano
     * se rechaza, porque ahí adivinar sería inventarse la intención.
     */
    fun cuadrar(flag: String, tipo: TipoRpc, valor: Any?): Any = when (tipo) {
        TipoRpc.BOOL -> valor as? Boolean
            ?: throw ConfigError(
                "'$flag' tiene que ser true o false para rclone, y es '$valor'.",
            )
        TipoRpc.ENTERO -> when (valor) {
            is Long -> valor
            is Int -> valor.toLong()
            else -> throw ConfigError(
                "'$flag' tiene que ser un número entero para rclone, y es '$valor'.",
            )
        }
        // Un número o un booleano se escriben como los escribiría rclone.
        TipoRpc.CADENA -> when (valor) {
            is String -> valor
            is Boolean -> if (valor) "true" else "false"
            is Long, is Int -> valor.toString()
            else -> throw ConfigError(
                "'$flag' tiene que ser texto para rclone, y es '$valor'.",
            )
        }
    }

    // -----------------------------------------------------------------------
    // Montar la llamada
    // -----------------------------------------------------------------------

    /** Lo traducido, y lo que se quedó fuera para poder decirlo. */
    data class Traduccion(
        val metodo: String,
        val params: Map<String, Any?>,
        /** Flag -> por qué no viaja. Ver [IGNORADOS]. */
        val ignorados: Map<String, String>,
    )

    /**
     * Los flags fundidos de la pareja, repartidos entre parámetros del método
     * y valores sueltos.
     *
     * Lo que no se sabe traducir **no se calla**: un flag que ni es parámetro
     * ni existe como opción de rclone se iría sin más por el desagüe —rclone
     * no comprueba los sueltos que no conoce—, así que se rechaza aquí. Es la
     * misma decisión que `flags_editor` en el escritorio: fallar al preparar
     * la pasada y no a mitad de faena.
     */
    fun traducir(pareja: Pareja, op: Opciones = Opciones()): Traduccion {
        val metodo = metodo(pareja)
        val esBisync = pareja.isBisync
        val propios = if (esBisync) PARAMETROS_BISYNC else PARAMETROS_COPY
        val params = LinkedHashMap<String, Any?>()
        val ignorados = LinkedHashMap<String, String>()

        for ((flag, valor) in pareja.flags) {
            val nombre = flag.replace("_", "-")
            // `false` y `null` son «quitar el flag», igual que en
            // flagsToArgs. Con un parámetro propio del método sí se manda,
            // porque el valor por defecto de bisync puede no ser `false`.
            if (valor == null || (valor == false && nombre !in propios)) continue

            DEL_MOTOR[nombre]?.let { motivo ->
                throw ConfigError(
                    "[${pareja.name}] el flag '$nombre' no se pone en el config: $motivo",
                )
            }
            val ignorado = IGNORADOS[nombre]
            if (ignorado != null) {
                ignorados[nombre] = ignorado
                continue
            }
            val tipo = propios[nombre]
            if (tipo != null) {
                params[aCamello(nombre)] = cuadrar(nombre, tipo, valor)
                continue
            }
            // Suelto, con el nombre de la etiqueta `config:` de rclone. Una
            // lista viaja como lista: configstruct sabe leerla.
            params[aSnake(nombre)] = when (valor) {
                is Collection<*> -> valor.map { it.toString() }
                else -> valor
            }
        }

        // Los extremos. Para bisync son path1/path2 en el sentido de la
        // pareja; para copy y sync, origen y destino.
        if (esBisync) {
            params["path1"] = pareja.source
            params["path2"] = pareja.dest
        } else {
            params["srcFs"] = pareja.source
            params["dstFs"] = pareja.dest
        }

        // Y lo que pone la app en cada pasada.
        if (op.dryRun) {
            // En bisync es su propio parámetro, porque además de `ci.DryRun`
            // pone `opt.DryRun`, que es lo que mira bisync para no escribir
            // sus listados (`cmd/bisync/rc.go`). En los otros modos va suelto:
            // dentro de `_config` se ignoraría sin decir nada.
            if (esBisync) params["dryRun"] = true else params["dry_run"] = true
        }
        if (esBisync) {
            if (op.resync) params["resync"] = true
            if (op.workdir.isNullOrEmpty()) {
                throw ConfigError(
                    "[${pareja.name}] una pasada de bisync necesita su workdir: sin él " +
                        "rclone usaría su carpeta por defecto y el baseline se perdería.",
                )
            }
            params["workdir"] = op.workdir
            op.filtersFile?.let { params["filtersFile"] = it }
        } else {
            // Sin fichero de filtros, los patrones van como los del filtro
            // global de rclone. Sueltos: `_filter` casa por el nombre del
            // CAMPO de Go, así que `{"_filter":{"include":…}}` se descarta.
            if (pareja.includes.isNotEmpty()) params["include"] = pareja.includes
            if (pareja.excludes.isNotEmpty()) params["exclude"] = pareja.excludes
        }

        return Traduccion(metodo, params, ignorados)
    }

    /**
     * La llamada entera, ya con `_async` y `_group`.
     *
     * **`_async` no es opcional**: `librclone.RPC` descarta el `out` cuando la
     * llamada devuelve error (`writeError`, `librclone/librclone.go`), así que
     * por la vía síncrona una pasada fallida pierde su salida justo cuando
     * hace falta. Con `_async`, `job.finish()` asigna `job.Output` antes de
     * mirar el error (`fs/rc/jobs/job.go`) y `job/status` trae las dos cosas.
     */
    fun peticion(pareja: Pareja, op: Opciones = Opciones()): Peticion {
        val t = traducir(pareja, op)
        val params = LinkedHashMap<String, Any?>(t.params)
        params["_async"] = true
        op.grupo?.let { params["_group"] = it }
        return Peticion(t.metodo, params)
    }

    /** El `_group` de una pareja: es con lo que se le piden sus estadísticas. */
    fun grupoDe(pareja: Pareja): String = "prdrive/${pareja.name}"

    /**
     * Hasta qué nivel registra rclone, con los nombres de su `--log-level`.
     *
     * No es un flag que se pueda mandar en la llamada: es global y hay que
     * ponerlo con `RcloneLogNivel` antes (ver `rclone/gobind/prdrive.go`). El
     * `verbose = true` de las [BASE_FLAGS] de prdrive es INFO, que es lo que
     * hace falta para que el log traiga las líneas que `KNOWN_ERRORS`
     * reconoce.
     */
    fun nivelDeLog(flags: Map<String, Any?>): String {
        (flags["log-level"] ?: flags["log_level"])?.let { return it.toString().uppercase() }
        if (flags["verbose"] == true) return "INFO"
        if (flags["quiet"] == true) return "ERROR"
        return "NOTICE"
    }

    // -----------------------------------------------------------------------
    // Leer lo que contestó
    // -----------------------------------------------------------------------

    /** El jobid de una llamada con `_async`. */
    fun jobid(respuesta: RespuestaRpc): Long {
        if (!respuesta.ok) {
            throw RcloneError(mensajeDeError(respuesta), respuesta.estado)
        }
        val salida = leerObjetoJson(respuesta.salida)
        return (salida["jobid"] as? Long)
            ?: throw RcloneError(
                "la llamada no devolvió jobid, así que no se lanzó con _async: ${respuesta.salida}",
                respuesta.estado,
            )
    }

    /** Lo que dice `job/status`. */
    @Suppress("UNCHECKED_CAST")
    fun estadoJob(respuesta: RespuestaRpc): EstadoJob {
        if (!respuesta.ok) throw RcloneError(mensajeDeError(respuesta), respuesta.estado)
        val salida = leerObjetoJson(respuesta.salida)
        return EstadoJob(
            acabado = salida["finished"] == true,
            exito = salida["success"] == true,
            error = salida["error"]?.toString() ?: "",
            salida = salida["output"] as? Map<String, Any?> ?: emptyMap(),
        )
    }

    /** El `error` que rclone pone en el JSON cuando el estado no es 200. */
    fun mensajeDeError(respuesta: RespuestaRpc): String {
        val texto = runCatching { leerObjetoJson(respuesta.salida)["error"]?.toString() }
            .getOrNull()
        return texto?.takeIf { it.isNotBlank() } ?: respuesta.salida
    }

    // -----------------------------------------------------------------------
    // Traducir el fallo
    // -----------------------------------------------------------------------

    /**
     * Agujas de `sync.py` (`KNOWN_ERRORS`), en su orden, y por las mismas
     * razones: las dos últimas de prdrive son errores de ARRANQUE, y van al
     * final porque si un log trae además una de las de arriba, esa es la que
     * ocurrió de verdad.
     *
     * `PasadaTest` comprueba contra los vectores que esta lista es la de
     * prdrive, aguja por aguja y en orden. Los casos nuevos se añaden **allí**
     * y se regeneran los vectores; los que solo existen aquí van en
     * [KNOWN_ERRORS_RPC].
     */
    val KNOWN_ERRORS_PRDRIVE: List<Pair<String, String>> = listOf(
        Bisync.MISSING_LISTINGS to
            "No hay baseline: primera vez, listados en otro sitio (¿cambió la ruta?) o " +
            "un fallo crítico previo los invalidó. Solución: --resync.",
        "filters file has changed" to
            "Han cambiado los filtros. Solución: --resync (bisync no puede saber qué " +
            "ficheros excluidos existían antes).",
        "filters file md5 hash not found" to
            "Primer uso de este fichero de filtros. Solución: --resync.",
        "must run --resync" to
            "bisync ha invalidado el baseline y exige rehacerlo. Solución: --resync.",
        "--max-delete" to
            "Se han superado los borrados permitidos. Comprueba que la carpeta del " +
            "volumen NO esté vacía antes de forzar nada.",
        "Access is denied" to
            "Fichero bloqueado por otro proceso.",
        "prior lock file found" to
            "Hay un lock de otra ejecución. Si no hay ninguna corriendo, borra el .lck " +
            "del workdir de la pareja.",
        "known_hosts_file" to
            "rclone no encuentra el fichero de known_hosts del rclone.conf. Las rutas " +
            "relativas se resuelven contra la carpeta de la app; comprueba que " +
            "keys/known_hosts sigue ahí.",
        "Failed to create file system" to
            "rclone no ha podido montar uno de los dos extremos. Suele ser una ruta o " +
            "una credencial mal resuelta en rclone.conf (revisa key_file y " +
            "known_hosts_file), o el remoto inalcanzable.",
        "unknown flag" to
            "rclone no conoce uno de los flags del config.",
        "invalid argument" to
            "rclone rechaza el VALOR de un flag del config.",
    )

    /**
     * Las que solo pueden salir por la API rc, y que en el escritorio no
     * existen porque allí rclone es una línea de órdenes.
     *
     * Van **después** de las de prdrive por la misma razón que allí las de
     * arranque van al final: si el log trae un fallo de la sincronización, ese
     * es el que hay que explicar.
     */
    val KNOWN_ERRORS_RPC: List<Pair<String, String>> = listOf(
        "invalid choice" to
            "rclone rechaza el valor de un flag del config porque no es de los que " +
            "admite: el mensaje de arriba enumera los buenos.",
        "didn't find section in config file" to
            "El rclone.conf del volumen no tiene el remote que pide la pareja. Vuelve a " +
            "emparejar el móvil desde el PC.",
        "not supported" to
            "Este método no se puede usar desde la biblioteca. Es un fallo de la app, " +
            "no del config.",
    )

    /** Las dos listas, que es lo que se recorre. */
    val KNOWN_ERRORS: List<Pair<String, String>> = KNOWN_ERRORS_PRDRIVE + KNOWN_ERRORS_RPC

    /**
     * El log traducido a algo accionable, o null si no hay aguja que encaje.
     *
     * Un diagnóstico falso es peor que ninguno —es lo que pasaba en el
     * escritorio cuando rclone volcaba su ayuda de 12 KB detrás del error y
     * `explain_failure` encontraba `--max-delete` dentro de su propia
     * documentación—, así que aquí no se adivina: se recorren las agujas en
     * orden y se devuelve la primera.
     */
    fun explicarFallo(log: String): String? =
        KNOWN_ERRORS.firstOrNull { (aguja, _) -> aguja in log }?.second

    /**
     * Las últimas [LINEAS_DE_COLA] líneas del log, sin las estadísticas.
     *
     * Sin quitarlas, una pasada que se queda pensando antes de fallar llenaba
     * estas líneas de números y el error se quedaba fuera (`print_log_tail`
     * de `sync.py`). Aquí las estadísticas no las escribe rclone en el log
     * —van por `core/stats`—, pero un `stats` puesto a mano en el TOML las
     * traería, así que se filtran igual.
     */
    fun colaDelLog(log: String, lineas: Int = LINEAS_DE_COLA): List<String> =
        log.lines()
            .filter { it.isNotBlank() && Progresos.leer(it) == null }
            .takeLast(lineas)
}

/** rclone no ha podido con la llamada. Lleva el estado HTTP para diagnóstico. */
class RcloneError(mensaje: String, val estado: Int = 0) : Exception(mensaje)
