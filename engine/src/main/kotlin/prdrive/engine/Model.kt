package prdrive.engine

/**
 * Model.kt — El `sync_config.toml` convertido en objetos ya resueltos.
 *
 * Espejo de `common/model.py` de prdrive (versión citada en
 * `engine/src/test/resources/vectores.json`). El TOML se lee UNA vez y se
 * convierte en [Config] con sus [Pareja], y cada pareja con su [Modo]. A partir
 * de ahí nadie vuelve a preguntar por claves del TOML ni repite
 * `.get(clave, por_defecto)`, y `defaults` deja de viajar por todas las firmas.
 *
 * **Añadir un flag de rclone sigue siendo cosa del TOML, no de este fichero.**
 *
 * Lo que NO se copia de `model.py`: todo lo que habla del equipo donde corre
 * —`machine_arch()`, `arch_dir()`, `rclone_binary()`, `BIN_DIR`—. Aquí rclone no
 * es un binario que haya que encontrar: es una biblioteca dentro de la app.
 *
 * Y una diferencia que **no** es una omisión: donde el escritorio define el
 * remote `combine` con variables de entorno (`Config.pen_environment`), la app
 * no tiene un proceso hijo al que pasárselas, así que escribe una sección
 * `[disp]` en su `rclone.conf` con el MISMO `upstreams` ([upstreamsDeCombine]).
 * Las cadenas de los extremos salen idénticas, y con ellas el nombre de los
 * listados de bisync — que es lo único que no puede cambiar.
 */

/** La configuración es inválida. */
class ConfigError(mensaje: String) : Exception(mensaje)

const val DEFAULT_REMOTE = "remote"
const val DEFAULT_MODE = "bisync"

/**
 * El nombre del remote `combine` con el que el lado local deja de ser una ruta
 * absoluta. En la app se da por hecho: sin él, el nombre de los listados de
 * bisync llevaría dentro la ruta del volumen virtual, y una reinstalación de la
 * app rompería todos los baselines.
 */
const val DEFAULT_DEVICE_REMOTE = "disp"

/**
 * El upstream de la RAÍZ del volumen, para la pareja cuyo `local` es ".".
 * Tiene que ser un nombre y no ".": rclone limpia la ruta antes de buscar el
 * upstream, así que `disp:.` se convierte en el upstream "" y falla con
 * «combine for remote "": directory not found». Sale en el prefijo de los
 * listados de bisync: cambiarlo invalida esos baselines.
 */
const val RAIZ_UPSTREAM = "raiz"

/**
 * Flags que lleva toda ejecución, sea del modo que sea.
 *
 * `stats` / `stats-one-line` viven aquí en el escritorio porque de ahí saca
 * `sync.py` el progreso, leyendo el log. **En la app no hace falta leer ningún
 * log para eso**: `core/stats` da el progreso como datos. Se conservan igual,
 * en la capa base y no en el código, para que una pareja los pueda cambiar como
 * cualquier otro flag y para que los flags fundidos coincidan con los del
 * escritorio, que es lo que comprueban los vectores.
 */
val BASE_FLAGS: Map<String, Any?> = linkedMapOf(
    "verbose" to true,
    "create-empty-src-dirs" to true,
    "stats" to "2s",
    "stats-one-line" to true,
)

/**
 * Qué subcomando de rclone es cada modo, en qué sentido va y con qué flags.
 *
 * [source] / [dest] son los nombres de los extremos ("local" o "remote"), no
 * las rutas: quién es origen y quién destino es justo lo que distingue `up` de
 * `down`.
 */
data class Modo(
    val name: String,
    val verb: String,
    val source: String,
    val dest: String,
    val flags: Map<String, Any?> = emptyMap(),
) {
    val isBisync: Boolean get() = verb == "bisync"
}

/**
 * Los modos, con sus flags.
 *
 * `conflict-suffix` es un sufijo POR LADO y eso no es cosmética: con el de
 * fábrica ("conflict") y `--conflict-loser num`, rclone llama al perdedor
 * `.conflictN` con el primer número libre (`cmd/bisync/resolve.go`: `resolve`,
 * `numerate`), y ese número es un ORDEN, no un lado. Con dos sufijos distintos
 * el nombre dice de qué lado viene, y sigue numerado, así que un segundo
 * conflicto en el mismo fichero no pisa la copia del primero.
 *
 * **`max-delete` no significa lo mismo en los dos sitios**, y hay que saberlo:
 * en un `bisync` rclone lee el `--max-delete` global y lo reinterpreta como un
 * **porcentaje** (`cmd/bisync/cmd.go`, `applyContext`: se acota a 0..100 y
 * después pone `ci.MaxDelete = -1` para que `fs/operations` no lo aplique
 * además como cuenta; `cmd/bisync/deltas.go`, `excessDeletes`, compara
 * `borrados/listados_antes` contra ese porcentaje). En los modos `*-mirror`,
 * que son `sync`, el mismo número ES una cuenta de ficheros. Así que 25 en
 * bisync es «no más del 25 % de lo que había» y 50 en espejo es «no más de 50
 * ficheros». En el RPC el parámetro se llama `maxDelete` y **exige** estar
 * entre 0 y 100: ver [Bisync].
 */
val MODES: Map<String, Modo> = listOf(
    Modo(
        "bisync", "bisync", "local", "remote",
        linkedMapOf(
            "conflict-resolve" to "newer",
            "conflict-suffix" to "conflicto-dispositivo,conflicto-remoto",
            "max-delete" to 25L,
            "resilient" to true,
            "recover" to true,
            "max-lock" to "2m",
        ),
    ),
    Modo("up", "copy", "local", "remote"),
    Modo("down", "copy", "remote", "local"),
    Modo("up-mirror", "sync", "local", "remote", linkedMapOf("max-delete" to 50L)),
    Modo("down-mirror", "sync", "remote", "local", linkedMapOf("max-delete" to 50L)),
).associateBy { it.name }

/**
 * `{nombre: valor}` -> argumentos de rclone.
 *
 * ```
 * clave = true          -> --clave
 * clave = false / null  -> (se omite)
 * clave = 4 / "texto"   -> --clave 4 / --clave texto
 * clave = ["a", "b"]    -> --clave a --clave b
 * ```
 *
 * En la app no se lanza ninguna línea de órdenes, así que esto **no** es lo que
 * ejecuta una pareja: eso lo hace [Bisync] traduciendo los flags a parámetros
 * del RPC. Sigue aquí por dos razones que no son de adorno: es lo que se le
 * enseña al usuario en el editor de flags —tiene que ver en qué se convierte lo
 * que escribe, en la sintaxis de rclone y no en una inventada— y es la única
 * forma de comprobar contra el escritorio que las capas se funden igual.
 */
fun flagsToArgs(flags: Map<String, Any?>): List<String> {
    val args = ArrayList<String>()
    for ((key, value) in flags) {
        val flag = "--" + key.replace("_", "-")
        when {
            value == true -> args.add(flag)
            value == false || value == null -> continue
            value is Collection<*> -> value.forEach { args.add(flag); args.add(texto(it)) }
            else -> { args.add(flag); args.add(texto(value)) }
        }
    }
    return args
}

/**
 * Un valor de flag como lo escribiría `str()` de Python, que es con lo que se
 * comparan los vectores. Solo importa para los enteros leídos del TOML, que
 * llegan como `Long`: sin esto, un `4L` saldría igual pero un `4` escrito como
 * `Int` desde código también, así que se unifica en un sitio.
 */
private fun texto(valor: Any?): String = when (valor) {
    is Boolean -> if (valor) "True" else "False"    // no llega aquí; por si acaso
    else -> valor.toString()
}

// ---------------------------------------------------------------------------
// Pareja
// ---------------------------------------------------------------------------

/**
 * Una `[[pair]]` del TOML con todas sus capas ya fusionadas.
 *
 * [flags] y los patrones de filtrado llegan aquí resueltos; nadie aguas abajo
 * necesita saber que existían unos `[defaults]`.
 *
 * A diferencia del escritorio no hay `local_abs`: la ruta absoluta del volumen
 * virtual la sabe la app (es su `filesDir`), no el motor, y meterla aquí ataría
 * el motor a Android y lo haría imposible de probar sin dispositivo. El motor
 * habla de rutas **relativas a la raíz del volumen**, que es lo que dice el
 * TOML, y quien necesite la absoluta la resuelve ([Volumen]).
 */
data class Pareja(
    val name: String,
    val mode: Modo,
    /** Relativa al volumen, con `/` y sin barras sueltas. */
    val local: String,
    val remotePath: String,
    val remoteName: String,
    val includes: List<String>,
    val excludes: List<String>,
    val flags: Map<String, Any?>,
    val extraFlags: List<String>,
    val useFiltersFile: Boolean,
    val deviceRemote: String?,
) {
    val isBisync: Boolean get() = mode.isBisync

    /** La ruta local partida, sin los tramos que no dicen nada ("." y ""). */
    val tramosLocales: List<String>
        get() = local.replace("\\", "/").split("/").filter { it != "" && it != "." }

    /**
     * Primer tramo de la ruta local: lo que se declara como upstream del remote
     * `combine`. Una pareja que sincroniza la RAÍZ (`local = "."`) no tiene
     * primer tramo, y no vale dejarlo en ".": ver [RAIZ_UPSTREAM].
     */
    val topLevelDir: String
        get() = tramosLocales.firstOrNull() ?: RAIZ_UPSTREAM

    /** La ruta de la pareja vista desde dentro del remote `combine`. */
    val rutaEnCombine: String
        get() = (listOf(topLevelDir) + tramosLocales.drop(1)).joinToString("/")

    /**
     * Con `device_remote` el lado local es un remote propio, y entonces su
     * nombre ya no depende de dónde esté montado el volumen.
     *
     * Sin él no hay extremo local que dar: en el escritorio sería la ruta
     * absoluta del dispositivo, y aquí esa ruta no la conoce el motor. Se exige
     * en [parseConfig], así que esto no llega a lanzarse por un config leído.
     */
    val localEndpoint: String
        get() = deviceRemote?.let { "$it:$rutaEnCombine" }
            ?: throw ConfigError(
                "[$name] sin 'device_remote' no hay extremo local que nombrar: " +
                    "en la app el lado local es siempre un remote 'combine'.",
            )

    val remoteEndpoint: String get() = "$remoteName:$remotePath"

    fun endpoint(kind: String): String =
        if (kind == "local") localEndpoint else remoteEndpoint

    val source: String get() = endpoint(mode.source)
    val dest: String get() = endpoint(mode.dest)

    /** Workdir de bisync: uno por pareja, para que sus listados no se mezclen. */
    val workdir: String get() = name

    /** `--filters-file` es exclusivo de bisync; en el resto van include/exclude. */
    val wantsFiltersFile: Boolean get() = isBisync && useFiltersFile
}

// ---------------------------------------------------------------------------
// Configuración completa
// ---------------------------------------------------------------------------

data class Config(
    val pairs: List<Pareja>,
    val daemon: Map<String, Any?>,
    val keepLogs: Boolean,
    val deviceRemote: String?,
) {
    val names: List<String> get() = pairs.map { it.name }

    /**
     * Las parejas pedidas, en el orden del TOML. Aborta si alguna no existe: un
     * nombre mal escrito no puede acabar en «pues no sincronizo eso».
     */
    fun select(wanted: Collection<String>): List<Pareja> {
        val queridas = wanted.toSet()
        val elegidas = pairs.filter { it.name in queridas }
        val faltan = queridas - elegidas.map { it.name }.toSet()
        if (faltan.isNotEmpty()) {
            throw ConfigError(
                "No existen estas parejas en el config: ${faltan.sorted().joinToString(", ")}",
            )
        }
        return elegidas
    }

    /**
     * El `upstreams` del remote `combine`, calculado con TODAS las parejas.
     *
     * Con todas y no solo con las seleccionadas para que el remote sea idéntico
     * sincronices lo que sincronices: si cambiara, cambiaría el nombre de los
     * listados de bisync de las que sí entran.
     *
     * En el escritorio esto viaja en `RCLONE_CONFIG_DISP_UPSTREAMS`; aquí se
     * escribe en el `rclone.conf` de la app. El texto es el mismo.
     */
    fun upstreamsDeCombine(raizDelVolumen: String): String {
        if (deviceRemote == null) return ""
        val tops = LinkedHashMap<String, String>()
        for (pareja in pairs) {
            val nombre = pareja.topLevelDir
            val ruta = unir(raizDelVolumen, pareja.tramosLocales.take(1))
            // Dos parejas que pidan el mismo nombre para carpetas distintas solo
            // puede pasar con la raíz: `local = "."` la declara como
            // RAIZ_UPSTREAM y otra pareja tiene una carpeta que se llama justo
            // así. Sería un upstream apuntando a donde no es, callando.
            val previa = tops.putIfAbsent(nombre, ruta)
            if (previa != null && previa != ruta) {
                throw ConfigError(
                    "Dos parejas declaran el upstream '$nombre' apuntando a carpetas " +
                        "distintas ($previa y $ruta). Renombra la carpeta '$nombre' de " +
                        "la raíz del volumen.",
                )
            }
        }
        return tops.keys.sorted().joinToString(" ") { upstream(it, tops.getValue(it)) }
    }

    /**
     * La sección `[disp]` del `rclone.conf`, que es la forma que toma aquí
     * `Config.pen_environment()` del escritorio.
     */
    fun seccionCombine(raizDelVolumen: String): String {
        if (deviceRemote == null) return ""
        return "[$deviceRemote]\ntype = combine\nupstreams = " +
            "${upstreamsDeCombine(raizDelVolumen)}\n"
    }
}

private fun unir(raiz: String, tramos: List<String>): String {
    val base = raiz.trimEnd('/')
    return if (tramos.isEmpty()) base.ifEmpty { "/" } else base + "/" + tramos.joinToString("/")
}

/**
 * Un tramo del `upstreams` del remote `combine`, tal y como rclone lo lee.
 *
 * rclone parsea `upstreams` como `fs.SpaceSepList` (`fs/types.go`), que es un
 * CSV con el espacio de separador: un campo solo va entrecomillado si **empieza**
 * por comilla, y una comilla dentro de un campo que no empezaba por comilla es
 * un error de sintaxis. Por eso las comillas envuelven el PAR ENTERO
 * `nombre=ruta` y no la ruta: entrecomillar solo la ruta daba `.="F:\"`, que
 * rclone rechaza con «bare " in non-quoted-field» y tumbaba TODAS las parejas
 * del dispositivo. Entre comillas caben tanto la barra final de la raíz de una
 * unidad como los espacios de la ruta, que es para lo que hacían falta. Una
 * comilla dentro de la ruta se dobla, como manda el CSV.
 */
fun upstream(nombre: String, ruta: String): String =
    "\"$nombre=${ruta.replace("\"", "\"\"")}\""

/**
 * Funde las capas de configuración de una pareja. El orden de los flags va de
 * menos a más prioridad: base < modo < `[defaults.flags]` < `[pair.flags]`.
 */
fun construirPareja(raw: Map<String, Any?>, defaults: Map<String, Any?>): Pareja {
    val name = raw["name"]?.toString()?.takeIf { it.isNotEmpty() }
        ?: throw ConfigError("Hay una [[pair]] sin 'name' en el config.")
    for (obligatoria in listOf("local", "remote_path")) {
        if (obligatoria !in raw) {
            throw ConfigError("[$name] falta '$obligatoria' en el config.")
        }
    }

    val modeName = raw["mode"]?.toString() ?: DEFAULT_MODE
    val mode = MODES[modeName] ?: throw ConfigError(
        "[$name] modo inválido: '$modeName'. Válidos: ${MODES.keys.sorted()}",
    )

    val flags = LinkedHashMap<String, Any?>(BASE_FLAGS)
    flags.putAll(mode.flags)
    tablaDe(defaults, "flags")?.let { flags.putAll(it) }
    tablaDe(raw, "flags")?.let { flags.putAll(it) }

    return Pareja(
        name = name,
        mode = mode,
        local = raw.getValue("local").toString().replace("\\", "/").trim('/'),
        remotePath = raw.getValue("remote_path").toString(),
        remoteName = (raw["remote"] ?: defaults["remote"] ?: DEFAULT_REMOTE).toString(),
        includes = comoLista(defaults["include"]) + comoLista(raw["include"]),
        excludes = comoLista(defaults["exclude"]) + comoLista(raw["exclude"]),
        flags = flags,
        extraFlags = comoLista(defaults["extra_flags"]) + comoLista(raw["extra_flags"]),
        useFiltersFile = (raw["use_filters_file"] ?: defaults["use_filters_file"] ?: true) == true,
        deviceRemote = nombreDeviceRemote(defaults),
    )
}

private fun comoLista(valor: Any?): List<String> = when (valor) {
    null -> emptyList()
    is String -> if (valor.isEmpty()) emptyList() else listOf(valor)
    is Collection<*> -> valor.map { it.toString() }
    else -> listOf(valor.toString())
}

private val NOMBRE_DEVICE_REMOTE = Regex("[A-Za-z0-9_]+")

/**
 * El nombre del remote del volumen.
 *
 * En el escritorio viaja en variables `RCLONE_CONFIG_<NOMBRE>_*`, que no admiten
 * cualquier cosa. Aquí va a una sección del `rclone.conf`, donde cabría más,
 * pero la regla se conserva: el nombre entra en el prefijo de los listados de
 * bisync, y un dispositivo de escritorio y el móvil tienen que poder llamarlo
 * igual.
 */
fun nombreDeviceRemote(defaults: Map<String, Any?>): String? {
    val name = defaults["device_remote"]?.toString()?.takeIf { it.isNotEmpty() } ?: return null
    if (!NOMBRE_DEVICE_REMOTE.matches(name)) {
        throw ConfigError(
            "'device_remote' debe ser alfanumérico sin guiones (en el escritorio va " +
                "en una variable RCLONE_CONFIG_<NOMBRE>_*): '$name'",
        )
    }
    return name
}

/**
 * El mapa crudo del TOML, resuelto.
 *
 * Una diferencia deliberada con el escritorio: aquí `device_remote` es
 * **obligatorio**. Allí es el valor de fábrica que el instalador mete en los
 * `[defaults]` de cada dispositivo nuevo, y un config antiguo sin él sigue
 * funcionando con rutas absolutas. Aquí no hay ruta absoluta que valga —el
 * volumen vive en `filesDir`, que cambia si se reinstala la app—, así que un
 * config sin `device_remote` no es un caso legado: es un config que rompería
 * los baselines la primera vez que Android mueva la carpeta.
 */
fun parseConfig(data: Map<String, Any?>): Config {
    val defaults = tablaDe(data, "defaults") ?: emptyMap()
    val rawPairs = parejasDe(data)
    if (rawPairs.isEmpty()) {
        throw ConfigError("El config no tiene ninguna [[pair]] definida.")
    }
    val deviceRemote = nombreDeviceRemote(defaults)
        ?: throw ConfigError(
            "Los [defaults] del config no traen 'device_remote'. En la app el lado " +
                "local va siempre como un remote 'combine' (lo normal es " +
                "'$DEFAULT_DEVICE_REMOTE'): sin él, el nombre de los listados de " +
                "bisync llevaría dentro la ruta de la carpeta de la app y cambiaría " +
                "al reinstalarla.",
        )
    return Config(
        pairs = rawPairs.map { construirPareja(it, defaults) },
        daemon = tablaDe(data, "daemon") ?: emptyMap(),
        keepLogs = defaults["keep_logs"] == true,
        deviceRemote = deviceRemote,
    )
}
