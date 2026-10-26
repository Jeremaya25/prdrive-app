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
 * —`machine_arch()`, `arch_dir()`, `rclone_binary()`, `BIN_DIR`, las raíces del
 * equipo (`es_equipo()`, `problema_local_equipo()`)—. Aquí rclone no es un
 * binario que haya que encontrar sino una biblioteca dentro de la app, y el
 * volumen es siempre el de la app, que se comporta como un dispositivo: la
 * pareja de la raíz entera es legítima.
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
 * Ruta del catálogo de fábrica; cada usuario pone la suya en
 * `[defaults].catalog_path`. Desde prdrive 0.4 se llama `remote.toml`, y un
 * remoto de antes conserva su `pairs.toml`: ver [Catalogo].
 */
const val DEFAULT_CATALOG_PATH = "/prdrive-catalog/remote.toml"

/**
 * La carpeta del programa dentro del volumen: `filesDir/volumen/.prdrive/`,
 * con el `rclone.conf` y la clave en `keys/`. En el escritorio es
 * `APP_DIR.name`, que en un dispositivo también es `.prdrive`; aquí es fija.
 * De ella salen [REGLA_SIN_PROGRAMA] y lo que [problemaLocal] rechaza.
 */
const val CARPETA_PROGRAMA = ".prdrive"

/**
 * Nombre de la pareja del llavero, que construye el código cuando hay
 * `[keychain]`. Es también su carpeta en `state/` y `filters/`, y queda
 * reservado mientras el llavero esté activo.
 *
 * La app del paso 1 no sincroniza el llavero (no hay KeePassXC en el teléfono),
 * pero lo construye igual: su carpeta entra en el `upstreams` del `combine` y
 * su existencia cambia las reglas de la pareja de la raíz entera.
 */
const val LLAVERO = "keychain"

/** Carpeta del llavero en la raíz del volumen, oculta como `.prdrive/`. */
const val LLAVERO_LOCAL = ".keychain"

/**
 * El fichero llave de un llavero sin contraseña (`llave_interna`), dentro de
 * [LLAVERO_LOCAL]: nombre fijo (`model.LLAVERO_LLAVE`), y nunca sube al remoto.
 */
const val LLAVERO_LLAVE = "llave.keyx"

/** Subcarpeta del llavero dentro de la carpeta del catálogo, en el remoto. */
const val LLAVERO_REMOTO = "keychain"

/** Los filtros de la pareja del llavero, en su orden: gana la primera que casa. */
val LLAVERO_REGLAS: List<String> = listOf("- *.old.kdbx", "+ *.kdbx", "+ LEEME.txt", "- **")

/** Los flags propios de la pareja del llavero, encima de los de bisync. */
val LLAVERO_FLAGS: Map<String, Any?> = linkedMapOf(
    "conflict-loser" to "num",
    "resync-mode" to "newer",
)

/**
 * La regla que reciben las parejas que sincronizan la raíz entera cuando hay
 * llavero: sin ella, la base viajaría por otro camino y con otras reglas.
 */
const val REGLA_SIN_LLAVERO = "- /$LLAVERO_LOCAL/**"

/**
 * La regla que reciben las parejas que sincronizan la raíz entera: deja fuera
 * `.prdrive/`, que lleva la clave privada y el `rclone.conf`. En la app importa
 * igual que en un pendrive —el volumen tiene la misma distribución— y no la
 * quita ningún TOML.
 */
const val REGLA_SIN_PROGRAMA = "- /$CARPETA_PROGRAMA/**"

/**
 * Carpeta de versiones de una pareja, dentro de su propia raíz. rclone rechaza
 * un `--backup-dir` que solape con el destino, así que [Bisync.filtersContent]
 * la excluye con la PRIMERA regla.
 */
const val VERSIONS_DIR = ".prversions"

/**
 * Lo que el sistema deja en la raíz de cualquier volumen, en minúsculas y como
 * patrones de `fnmatch`. No es contenido de nadie: un recorrido que busca
 * conflictos en la pareja de la raíz entera no entra ahí. En el volumen de la
 * app no debería aparecer nada de esto, pero el catálogo es el de todos.
 */
val RUIDO_DEL_SISTEMA: List<String> = listOf(
    "system volume information", "\$recycle.bin", "recycler", "lost+found",
    ".ds_store", ".spotlight-v100", ".fseventsd", ".trashes", "desktop.ini",
    "autorun.inf", ".trash-*",
)

/** La ventana con la que se comparan las horas de modificación, en segundos. */
const val VENTANA_HORA = 2L

/**
 * Flags que lleva toda ejecución, sea del modo que sea.
 *
 * `stats` / `stats-one-line` viven aquí en el escritorio porque de ahí saca
 * `sync.py` el progreso, leyendo el log. **En la app no hace falta leer ningún
 * log para eso**: `core/stats` da el progreso como datos. Se conservan igual,
 * en la capa base y no en el código, para que una pareja los pueda cambiar como
 * cualquier otro flag y para que los flags fundidos coincidan con los del
 * escritorio, que es lo que comprueban los vectores.
 *
 * `modify-window` es la tolerancia con la que rclone da por iguales dos horas
 * (`fs.GetModifyWindow()`, `fs/fs.go`; `timeDiffers()`,
 * `cmd/bisync/compare.go`). El escritorio la pone por exFAT y FAT32, que
 * guardan la hora en saltos de 2 s; el almacenamiento de la app es ext4 o f2fs y
 * no la necesitaría, pero ensancha la tolerancia sin estrecharla y no entra en
 * el prefijo del baseline ni en el fichero de filtros: se conserva para que una
 * pareja se comporte igual en el teléfono y en el PC.
 */
val BASE_FLAGS: Map<String, Any?> = linkedMapOf(
    "verbose" to true,
    "create-empty-src-dirs" to true,
    "stats" to "2s",
    "stats-one-line" to true,
    "modify-window" to "${VENTANA_HORA}s",
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

    /** El lado local es origen: lo que cambia allí hay que subirlo. */
    val origenLocal: Boolean get() = source == "local"

    /**
     * La pasada borra en el remoto lo que falta en la carpeta local: el espejo
     * de subida. Por eso su carpeta local nunca se crea vacía: ante un origen
     * que no existe, rclone se niega con «directory not found» y no borra nada.
     */
    val borraEnRemoto: Boolean get() = verb == "sync" && origenLocal
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
            value is Collection<*> -> value.forEach { args.add(flag); args.add(strPy(it)) }
            else -> { args.add(flag); args.add(strPy(value)) }
        }
    }
    return args
}

// ---------------------------------------------------------------------------
// Lo que el config no puede llevar
//
// El catálogo llega del remoto y lo escriben otros, a mano o desde otra versión.
// prdrive rechaza al leerlo lo que acabaría en la línea de órdenes de rclone, y
// la app lo rechaza igual y con las mismas palabras: aunque aquí no haya línea
// de órdenes, los flags llegan a rclone por el `_config` del RPC, y un config
// que el escritorio rechaza no puede ser bueno en el teléfono.
// ---------------------------------------------------------------------------

/** Flags que pone la pasada por su cuenta, con el motivo de cada uno. */
val FLAGS_RESERVADOS: Map<String, String> = linkedMapOf(
    "config" to "lo pone sync.py: es el rclone.conf del dispositivo",
    "log-file" to "lo pone sync.py: cada pasada escribe en su propio log",
    "dry-run" to "es --dry-run de sync.py, para que valga en todas las parejas",
    "workdir" to "lo pone sync.py: state/<pareja>/, y cambiarlo mueve el baseline",
    "resync" to "es --resync de sync.py, que además pregunta antes",
    "force" to "es --force de sync.py: salta el freno de borrados en UNA pasada y no puede " +
        "quedar fijo. Quita `force` (o `--force` de `extra_flags`) de " +
        "`.prdrive/sync_config.toml` del dispositivo o, si viene del catálogo, del " +
        "`remote.toml` del remoto; ya quitado, para saltar el freno una vez está «Forzar " +
        "sincronización…» en «Reparación» o `sync.py PAREJA --force`",
    "filters-file" to "sale de los patrones incluir/excluir de la pareja",
    "filter" to "usa los patrones incluir/excluir; mezclarlos rompe el filtrado",
    "filter-from" to "usa los patrones incluir/excluir; mezclarlos rompe el filtrado",
    "include" to "usa el cuadro «Incluir»",
    "exclude" to "usa el cuadro «Excluir»",
    "backup-dir" to "el versionado de bisync usa --backup-dir1/2, no este",
    "backup-dir1" to "sale de la casilla «Guardar versiones»: es <pareja>/.prversions",
    "backup-dir2" to "sale de la casilla «Guardar versiones»: es <pareja>/.prversions",
    "suffix" to "lo pone sync.py: la marca de tiempo de ESTA pasada",
    "suffix-keep-extension" to "lo pone sync.py junto con --suffix",
)

private const val MOTIVO_PROGRAMA =
    "lanza un programa en este equipo, y el config viaja con el dispositivo"

/** Flags de rclone que escriben o truncan un fichero cualquiera (`cmd/cmd.go`). */
val FLAGS_ESCRIBEN: Map<String, String> = linkedMapOf(
    "cpuprofile" to "escribe (o trunca) en este equipo el fichero que se le diga, y el " +
        "config viaja con el dispositivo",
    "memprofile" to "escribe (o trunca) en este equipo el fichero que se le diga, y el " +
        "config viaja con el dispositivo",
)

/** Con lo que acaba el rechazo de un flag: el config se arregla a mano. */
const val QUITALO = "Quítalo del config."

/** El nombre con el que rclone verá el flag: `_` es `-` y sin mayúsculas. */
fun normalizarFlag(nombre: String): String = stripPy(nombre).replace("_", "-").lowercase()

/**
 * Por qué un flag de rclone no puede ir en el config, o `null` si vale.
 *
 * No vale lo que pone la pasada ([FLAGS_RESERVADOS]), ni lo que escribe un
 * fichero cualquiera ([FLAGS_ESCRIBEN]), ni lo que hace que rclone ejecute un
 * programa: los que acaban en `-command` o `-ssh`, `metadata-mapper`, `rc` y
 * los `rc-*` (`--password-command` de fs/config, `ssh` de backend/sftp,
 * `bearer_token_command` de backend/webdav, el servidor de fs/rc/rcflags). Se
 * mira lo que hay antes de un `=`: una clave `"sftp-ssh=sh -c id"` llegaría a
 * rclone como `--sftp-ssh=sh -c id`.
 */
fun problemaFlag(nombre: String): String? {
    val clave = stripPy(normalizarFlag(nombre).split("=", limit = 2)[0])
    (FLAGS_RESERVADOS[clave] ?: FLAGS_ESCRIBEN[clave])?.let { return it }
    if (clave.endsWith("-command") || clave.endsWith("-ssh") ||
        clave == "metadata-mapper" || clave == "rc" || clave.startsWith("rc-")
    ) {
        return MOTIVO_PROGRAMA
    }
    return null
}

/**
 * Por qué una lista de argumentos no puede ir en el config: la frase entera,
 * que nombra el primero rechazado. Solo se miran los que empiezan por `--`; un
 * valor suelto (`8M`, `sh -c id`) no es un flag.
 */
fun problemaExtra(args: Iterable<String>): String? {
    for (arg in args) {
        if (!arg.startsWith("--")) continue
        val nombre = arg.substring(2).split("=", limit = 2)[0]
        problemaFlag(nombre)?.let { return "'--$nombre' no se admite: $it. $QUITALO" }
    }
    return null
}

/**
 * Por qué el valor de un flag no puede ir en el config: un booleano no consume
 * el argumento siguiente, así que `checksum = "--sftp-ssh=sh -c id"` llegaría a
 * rclone como dos flags.
 */
fun problemaValorFlag(valor: Any?): String? = when (valor) {
    is String -> problemaExtra(listOf(valor))
    is Collection<*> -> problemaExtra(valor.filterIsInstance<String>())
    else -> null
}

/**
 * Un nombre de remote que no es una cadena de conexión: letras, números,
 * `_ . + @ -` y espacios entre palabras, sin empezar por `-`.
 *
 * El `\w` de Python es «letra o número» de cualquier alfabeto (`str.isalnum()`)
 * más `_`, y se escribe así, con `\p{L}` y `\p{N}`: el `\w` de Java no es ese
 * (con `(?U)` deja pasar marcas combinantes y el ZWJ, y sin él solo es ASCII), y
 * Android no admite `(?U)`.
 */
internal val NOMBRE_REMOTE = Regex("(?!-)[\\p{L}\\p{N}_.+@-]+(?: [\\p{L}\\p{N}_.+@-]+)*")

/**
 * Por qué un `remote` no vale como nombre de remote de rclone, o `null`. Se
 * junta con la ruta (`nombre:ruta`) y va tal cual a rclone, que lo lee como
 * cadena de conexión si lleva opciones y, si es una sola letra, como una unidad
 * de Windows (`fs/fspath/path.go`).
 */
fun problemaRemote(nombre: Any?, clave: String = "remote"): String? {
    if (nombre !is String) return "'$clave' no vale: tiene que ser un texto."
    if (nombre.length == 1 && nombre[0].code < 128 && nombre[0].isLetter()) {
        return "'$clave' no vale (${reprPy(nombre)}): rclone lee un nombre de una sola letra " +
            "como una unidad de Windows ($nombre:), no como un remote. Renombra el " +
            "remote, con dos caracteres o más, en el rclone.conf y aquí."
    }
    if (!NOMBRE_REMOTE.matches(nombre)) {
        return "'$clave' no vale (${reprPy(nombre)}): es el nombre de un remote del " +
            "rclone.conf, con letras, números, espacios entre palabras y " +
            ". _ + @ -, sin empezar por '-' ni llevar ',' ':' '=' o comillas " +
            "(rclone lo leería como opciones de la conexión)."
    }
    return null
}

/** Por qué un nombre de pareja no vale (va a `state/<nombre>/` y a `filters/`). */
fun problemaNombre(name: Any?): String? {
    if (name !is String) return "el nombre tiene que ser un texto"
    if (name.startsWith("-")) return "el nombre no puede empezar por '-' (se leería como una opción)"
    if (name == "." || name == ".." || name.any { it in "/\\:" } || name.any { it.code < 32 }) {
        return "el nombre no puede llevar '/', '\\', ':' ni caracteres de control, " +
            "ni ser '.' o '..' (es el nombre de su carpeta en state/ y filters/)"
    }
    return null
}

/** El `local` como lo entiende el motor: `\` es `/` y sin barras en los extremos. */
fun normalizarLocal(local: Any?): String = strPy(local).replace("\\", "/").trim('/')

private fun tramosDe(texto: String): List<String> =
    texto.replace("\\", "/").split("/").filter { it != "" && it != "." }

private fun empiezaPorUnidad(tramo: String): Boolean =
    tramo.length >= 2 && (tramo[0] in 'A'..'Z' || tramo[0] in 'a'..'z') && tramo[1] == ':'

/**
 * Por qué el `local` de una pareja no vale, o `null`.
 *
 * Es una carpeta de DENTRO del volumen. Con un `..` o una letra de unidad la
 * pareja saldría de él; la carpeta del programa lleva la clave, y la del
 * llavero tiene su propia pareja. Las reglas de los puntos y espacios del final
 * y de los `:` son de Windows, que normaliza el nombre hacia otra carpeta; en
 * Android no harían ese daño, pero el catálogo es el mismo para todos y lo que
 * el escritorio rechaza no puede pasar aquí.
 */
fun problemaLocal(local: Any?, carpetaPrograma: String = CARPETA_PROGRAMA): String? {
    val texto = strPy(local)
    val tramos = tramosDe(texto)
    if (".." in tramos || (tramos.isNotEmpty() && empiezaPorUnidad(tramos[0]))) {
        return "local = \"$texto\" sale del dispositivo (lleva un '..' o una letra " +
            "de unidad): la pareja sincronizaría carpetas del ordenador. Pon una " +
            "carpeta de dentro del dispositivo, con la ruta relativa a su raíz."
    }
    for (tramo in tramos) {
        if (tramo.trim('.', ' ').isEmpty()) {
            return "local = \"$texto\" lleva un tramo de solo puntos y espacios " +
                "(${reprPy(tramo)}): Windows lo recorta y la pareja acabaría en otra carpeta " +
                "del ordenador. Pon una carpeta de dentro del dispositivo, con nombre."
        }
        if (':' in tramo) {
            return "local = \"$texto\" lleva ':' en un tramo (${reprPy(tramo)}): en Windows " +
                "es un flujo de datos (nombre:flujo) de otra carpeta, que puede ser " +
                "la del programa. Quita el ':' del nombre."
        }
    }
    val primero = tramos.firstOrNull()?.trimEnd('.', ' ')?.lowercase() ?: ""
    if (primero == carpetaPrograma.lowercase()) {
        return "local = \"$texto\" cae en «${tramos[0]}»: es la carpeta del " +
            "programa, con su clave. Pon otra carpeta de dentro del dispositivo."
    }
    if (primero == LLAVERO_LOCAL.lowercase()) {
        return "local = \"$texto\" cae en «${tramos[0]}»: es la del llavero, que " +
            "tiene su propia pareja ([keychain]). Pon otra carpeta de dentro del " +
            "dispositivo."
    }
    return null
}

/** Un valor del TOML que `_as_tuple()` no sabe convertir en lista (el `TypeError`). */
private class NoEsLista : Exception()

/**
 * `_as_tuple()`: nada, un texto o una lista, a lista de textos. Un mapa da sus
 * claves, como al iterarlo en Python; cualquier otra cosa verdadera (un número,
 * un booleano) no se puede leer como lista.
 */
private fun comoTupla(valor: Any?): List<String> {
    if (!verdad(valor)) return emptyList()
    return when (valor) {
        is String -> listOf(valor)
        is Map<*, *> -> valor.keys.map { strPy(it) }
        is Collection<*> -> valor.map { strPy(it) }
        else -> throw NoEsLista()
    }
}

/**
 * [comoTupla] para lo que va a una [Pareja]. Donde prdrive dejaría escapar el
 * `TypeError` (un `include = 5`), aquí es un [ConfigError] con lo que pasa: la
 * app no puede caerse por un catálogo mal escrito.
 */
private fun listaDe(donde: String, clave: String, valor: Any?): List<String> = try {
    comoTupla(valor)
} catch (_: NoEsLista) {
    throw ConfigError("$donde $clave tiene que ser una lista de textos.")
}

private fun comprobarCapas(donde: String, tabla: String, flags: Any?, extra: Any?) {
    // prdrive se cae con un `AttributeError` al fundir una `flags` que no es una
    // tabla (`flags = ["--x"]`); aquí se dice, en vez de tirar los flags callando.
    if (verdad(flags) && flags !is Map<*, *>) {
        throw ConfigError("$donde $tabla tiene que ser una tabla de flags (clave = valor).")
    }
    if (flags is Map<*, *>) {
        for ((clave, valor) in flags) {
            problemaFlag(strPy(clave))?.let {
                throw ConfigError("$donde $tabla '$clave' no se admite: $it. $QUITALO")
            }
            problemaValorFlag(valor)?.let {
                throw ConfigError("$donde $tabla '$clave': en su valor, $it")
            }
        }
    }
    // Lo que emite `_as_tuple()` y nada menos: una tabla en línea da sus claves.
    problemaExtra(listaDe(donde, "extra_flags", extra))?.let {
        throw ConfigError("$donde extra_flags: $it")
    }
}

private fun comprobarRemote(donde: String, valor: Any?, clave: String) {
    problemaRemote(valor, clave)?.let {
        throw ConfigError("$donde $it Deja solo el nombre del remote o quita la clave del config.")
    }
}

/**
 * Un `include` o `exclude` con un salto de línea escribiría reglas de más en el
 * fichero de filtros, que rclone lee línea a línea: con un `!` se borrarían las
 * de antes (la de `.prdrive/`, la de `.prversions/`).
 */
private fun comprobarPatrones(donde: String, capa: Map<*, *>) {
    for (clave in listOf("include", "exclude")) {
        val patrones = try {
            comoTupla(capa[clave])
        } catch (_: NoEsLista) {
            continue
        }
        for (patron in patrones) {
            if ('\n' in patron || '\r' in patron) {
                throw ConfigError(
                    "$donde $clave ${reprPy(patron)} lleva un salto de línea: en el fichero " +
                        "de filtros de rclone cada línea es una regla, y con un '!' se " +
                        "borrarían las de antes. Quita el salto de línea del patrón (un " +
                        "patrón por entrada de la lista).",
                )
            }
        }
    }
}

/**
 * Rechaza del config en bruto lo que no puede llegar a rclone. Es
 * `comprobar_seguridad()`: tolera cualquier forma (tablas que no lo son,
 * nombres que no son texto) y no decide nada más; un modo o una clave
 * desconocidos no son asunto suyo.
 */
fun comprobarSeguridad(crudo: Map<String, Any?>) {
    val defaults = crudo["defaults"]
    if (defaults is Map<*, *>) {
        for (clave in listOf("remote", "catalog_remote")) {
            if (defaults.containsKey(clave)) comprobarRemote("[defaults]", defaults[clave], clave)
        }
        comprobarCapas("[defaults]", "[defaults.flags]", defaults["flags"], defaults["extra_flags"])
        comprobarPatrones("[defaults]", defaults)
    }
    val parejas = crudo["pair"] as? Collection<*> ?: return
    for (pareja in parejas) {
        if (pareja !is Map<*, *> || pareja["name"] !is String) continue
        val donde = "[${pareja["name"]}]"
        if (pareja.containsKey("remote")) comprobarRemote(donde, pareja["remote"], "remote")
        comprobarCapas(donde, "[pair.flags]", pareja["flags"], pareja["extra_flags"])
        comprobarPatrones(donde, pareja)
        if (pareja.containsKey("local")) {
            val local = pareja["local"]
            if (local !is String) {
                throw ConfigError(
                    "$donde 'local' tiene que ser un texto, no ${reprPy(local)}: es la " +
                        "carpeta de dentro del dispositivo. Pon la ruta entre comillas, " +
                        "relativa a su raíz.",
                )
            }
            problemaLocal(local)?.let { throw ConfigError("$donde $it") }
        }
    }
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
 * TOML, y quien necesite la absoluta la resuelve.
 *
 * La excepción es una pareja que vive en una carpeta compartida
 * ([carpetaExterna], de `compartidas.json`): su `local` sigue siendo el de
 * siempre (de él salen el nombre de sesión de bisync y los filtros), y solo
 * cambia la carpeta a la que apunta su upstream del `combine`.
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
    /** Guarda versiones de lo que pierde cada lado (`--backup-dir1/2`). Solo bisync. */
    val versions: Boolean = false,
    /** El agente del escritorio la sincroniza al cambiar; la app no lo usa todavía. */
    val watch: Boolean = false,
    /**
     * Reglas de filtrado (`+ patrón` / `- patrón`) que van antes que [includes]
     * y [excludes]. Las pone el código, no el TOML: las del llavero, y en una
     * pareja de la raíz entera [REGLA_SIN_PROGRAMA] y [REGLA_SIN_LLAVERO].
     */
    val reglas: List<String> = emptyList(),
    /** Es la pareja del llavero, la que construye el código. */
    val llavero: Boolean = false,
    val llaveInterna: Boolean = false,
    /**
     * De qué capa (`ORIGEN_*`) sale cada clave de [flags]. Una pareja hecha a
     * mano que no lo diga cuenta sus flags como de la pareja.
     */
    val origenFlags: Map<String, String> = emptyMap(),
    /**
     * La ruta absoluta de la carpeta compartida en la que vive la pareja, o
     * `null` si vive en el volumen. No sale del TOML: la pone
     * [Config.conCompartidas] desde `compartidas.json`, y solo si la entrada
     * vale.
     */
    val carpetaExterna: String? = null,
    /**
     * Por qué la entrada de `compartidas.json` de esta pareja no se ha
     * aplicado, o `null`. Con motivo, la pareja sigue apuntando al volumen y
     * la pasada no debe correr (la carpeta del volumen está vacía y se daría
     * por borrada).
     */
    val problemaCompartida: String? = null,
) {
    val isBisync: Boolean get() = mode.isBisync

    /** Sincroniza la raíz entera del volumen (`local = "."`). */
    val esRaiz: Boolean get() = tramosLocales.isEmpty()

    /** La ruta local partida, sin los tramos que no dicen nada ("." y ""). */
    val tramosLocales: List<String> get() = tramosDe(local)

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

    /**
     * `--backup-dir1`: lo que pierde el lado local. Path1 es [source] y Path2
     * [dest], que es como se le pasan a rclone (en bisync el origen es el local).
     */
    val versionsPath1: String get() = "${source.trimEnd('/')}/$VERSIONS_DIR"

    /** `--backup-dir2`: lo que pierde el lado remoto. */
    val versionsPath2: String get() = "${dest.trimEnd('/')}/$VERSIONS_DIR"

    /**
     * La carpeta local de la pareja dentro de un volumen: el `local_abs` del
     * escritorio, con el volumen dicho por quien lo sabe. La pareja de la raíz
     * es el volumen mismo, y una pareja compartida ([carpetaExterna]) vive en
     * su carpeta compartida, no en el volumen.
     */
    fun carpetaEn(volumen: java.io.File): java.io.File =
        if (carpetaExterna != null) java.io.File(carpetaExterna)
        else if (esRaiz) volumen else java.io.File(volumen, tramosLocales.joinToString("/"))
}

// ---------------------------------------------------------------------------
// Configuración completa
// ---------------------------------------------------------------------------

data class Config(
    val pairs: List<Pareja>,
    val daemon: Map<String, Any?>,
    val keepLogs: Boolean,
    val deviceRemote: String?,
    /** La tabla `[keychain]` en bruto, o `null` sin llavero. */
    val llavero: Map<String, Any?>? = null,
) {
    /**
     * Los nombres de las parejas del TOML, en su orden, sin la del llavero: son
     * las que se eligen. [pairs] sí la lleva.
     */
    val names: List<String> get() = delUsuario.map { it.name }

    /** Las parejas del TOML, sin la del llavero. */
    val delUsuario: List<Pareja> get() = pairs.filter { !it.llavero }

    /** La pareja del llavero, o `null` si el config no lo lleva. */
    val parejaLlavero: Pareja? get() = pairs.firstOrNull { it.llavero }

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
                "No existen estas parejas en el config: ${faltan.sortedWith(ORDEN_PY).joinToString(", ")}",
            )
        }
        return elegidas
    }

    /**
     * Este config con las carpetas compartidas de [entradas] (`compartidas.json`)
     * aplicadas a sus parejas.
     *
     * Idempotente: antes de aplicar nada deja [Pareja.carpetaExterna] y
     * [Pareja.problemaCompartida] a `null` en todas, así que aplicarlo a un
     * config ya aplicado (o con otras entradas) no arrastra lo de antes. Una
     * entrada que vale redirige la pareja; una que no vale la deja donde está
     * y dice por qué en [Pareja.problemaCompartida]. Una entrada sin pareja se
     * ignora.
     *
     * @param raiz la raíz del volumen, para que ninguna ruta lo toque.
     */
    fun conCompartidas(entradas: Map<String, Compartidas.Entrada>, raiz: java.io.File): Config =
        copy(
            pairs = pairs.map { pareja ->
                val limpia = pareja.copy(carpetaExterna = null, problemaCompartida = null)
                val entrada = entradas[pareja.name] ?: return@map limpia
                val motivo = Compartidas.problema(this, pareja.name, entrada.ruta, raiz, entradas - pareja.name)
                if (motivo == null) {
                    limpia.copy(carpetaExterna = entrada.ruta)
                } else {
                    limpia.copy(problemaCompartida = motivo)
                }
            },
        )

    /**
     * El `upstreams` del remote `combine`, calculado con TODAS las parejas
     * (también la del llavero).
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
            val ruta = pareja.carpetaExterna ?: unir(raizDelVolumen, pareja.tramosLocales.take(1))
            // Dos parejas que pidan el mismo nombre para carpetas distintas solo
            // puede pasar con la raíz: `local = "."` la declara como
            // RAIZ_UPSTREAM y otra pareja tiene una carpeta que se llama justo
            // así. Sería un upstream apuntando a donde no es, callando.
            val previa = tops.putIfAbsent(nombre, ruta)
            if (previa != null && previa != ruta) {
                throw ConfigError(
                    "Dos parejas declaran el upstream '$nombre' apuntando a carpetas " +
                        "distintas ($previa y $ruta). Renombra la carpeta '$nombre' de " +
                        "la raíz del dispositivo.",
                )
            }
        }
        return tops.keys.sortedWith(ORDEN_PY).joinToString(" ") { upstream(it, tops.getValue(it)) }
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

/** Capa de [BASE_FLAGS]: lo que lleva toda ejecución. */
const val ORIGEN_BASE = "base"

/** Capa de [Modo.flags]: lo propio del modo de la pareja. */
const val ORIGEN_MODO = "modo"

/** Capa de `[defaults.flags]`: lo común a todas las parejas. */
const val ORIGEN_DEFAULTS = "defaults"

/** Capa de `[pair.flags]`: lo que la pareja pone por su cuenta. */
const val ORIGEN_PAREJA = "pareja"

/** Lo que la pasada añade y no se configura ([Orden]). */
const val ORIGEN_PRDRIVE = "prdrive"

/** Los patrones incluir/excluir de la pareja, que salen como `--include`/`--exclude`. */
const val ORIGEN_PATRONES = "patrones"

/** Los `extra_flags`: argumentos en bruto al final de la orden. */
const val ORIGEN_EXTRA = "extra"

/** Un flag tal como le llegará a rclone y la capa que lo puso. */
data class FlagEfectivo(val nombre: String, val valor: Any?, val origen: String)

/**
 * Funde las capas de flags de una pareja y recuerda de cuál sale cada uno:
 * base < modo < `[defaults.flags]` < `[pair.flags]`, la última que nombra una
 * clave se queda con ella (`flags_efectivos()`). Se funde por la clave tal como
 * está escrita: `max_delete` y `max-delete` son dos claves, y rclone recibe las
 * dos.
 */
fun flagsEfectivos(
    modo: Modo?,
    defaultsFlags: Map<String, Any?>?,
    propios: Map<String, Any?>?,
): List<FlagEfectivo> {
    val efectivos = LinkedHashMap<String, FlagEfectivo>()
    val capas = listOf(
        ORIGEN_BASE to BASE_FLAGS,
        ORIGEN_MODO to (modo?.flags ?: emptyMap()),
        ORIGEN_DEFAULTS to (defaultsFlags ?: emptyMap()),
        ORIGEN_PAREJA to (propios ?: emptyMap()),
    )
    for ((origen, tabla) in capas) {
        for ((nombre, valor) in tabla) efectivos[nombre] = FlagEfectivo(nombre, valor, origen)
    }
    return efectivos.values.toList()
}

/** `raw.get(clave, por_defecto)`: el valor si la clave está, aunque sea falso. */
private fun Map<String, Any?>.leer(clave: String, porDefecto: Any?): Any? =
    if (containsKey(clave)) get(clave) else porDefecto

/**
 * La clave `watch` de una pareja: un booleano, y `true` solo donde el origen es
 * el lado local (en una bajada, un cambio local no es algo que la pasada vaya a
 * enviar).
 */
private fun leerWatch(name: String, raw: Map<String, Any?>, mode: Modo): Boolean {
    val valor = raw.leer("watch", false)
    if (valor !is Boolean) {
        throw ConfigError("[$name] 'watch' tiene que ser true o false, no ${reprPy(valor)}.")
    }
    if (valor && !mode.origenLocal) {
        val validos = MODES.values.filter { it.origenLocal }.joinToString(", ") { it.name }
        throw ConfigError(
            "[$name] 'watch' solo vale donde el origen es el dispositivo " +
                "($validos), y esta pareja es '${mode.name}'. Quita la clave o " +
                "cambia el modo.",
        )
    }
    return valor
}

/**
 * Funde las capas de configuración de una pareja. El orden de los flags va de
 * menos a más prioridad: base < modo < `[defaults.flags]` < `[pair.flags]`.
 */
fun construirPareja(raw: Map<String, Any?>, defaults: Map<String, Any?>): Pareja {
    val nombreCrudo = raw["name"]
    if (!verdad(nombreCrudo)) throw ConfigError("Hay una [[pair]] sin 'name' en el config.")
    problemaNombre(nombreCrudo)?.let { throw ConfigError("[${strPy(nombreCrudo)}] $it") }
    val name = nombreCrudo as String
    for (obligatoria in listOf("local", "remote_path")) {
        if (obligatoria !in raw) {
            throw ConfigError("[$name] falta '$obligatoria' en el config.")
        }
    }

    val modeName = raw.leer("mode", DEFAULT_MODE)
    val mode = (modeName as? String)?.let { MODES[it] } ?: throw ConfigError(
        "[$name] modo inválido: '${strPy(modeName)}'. Válidos: ${reprPy(MODES.keys.sortedWith(ORDEN_PY))}",
    )

    // El versionado se apoya en `--backup-dir1/2` y en apartar al perdedor de
    // un conflicto, que son cosas de bisync: vale más decirlo al leer que dejar
    // la clave sin efecto.
    val versions = verdad(raw.leer("versions", false))
    if (versions && !mode.isBisync) {
        throw ConfigError(
            "[$name] 'versions' solo vale en modo bisync, y esta pareja es " +
                "'${strPy(modeName)}'. Quita la clave o cambia el modo.",
        )
    }

    val watch = leerWatch(name, raw, mode)

    // El que sale de la cadena de valores por defecto, que es el que va a
    // rclone: ninguna rama se salta la comprobación.
    val remote = raw.leer("remote", defaults.leer("remote", DEFAULT_REMOTE))
    comprobarRemote("[$name]", remote, "remote")

    val donde = "[$name]"
    val efectivos = flagsEfectivos(mode, tablaDe(defaults, "flags"), tablaDe(raw, "flags"))
    return Pareja(
        name = name,
        mode = mode,
        local = normalizarLocal(raw.getValue("local")),
        remotePath = strPy(raw.getValue("remote_path")),
        remoteName = remote as String,
        includes = listaDe("[defaults]", "include", defaults["include"]) +
            listaDe(donde, "include", raw["include"]),
        excludes = listaDe("[defaults]", "exclude", defaults["exclude"]) +
            listaDe(donde, "exclude", raw["exclude"]),
        flags = efectivos.associate { it.nombre to it.valor },
        origenFlags = efectivos.associate { it.nombre to it.origen },
        extraFlags = listaDe("[defaults]", "extra_flags", defaults["extra_flags"]) +
            listaDe(donde, "extra_flags", raw["extra_flags"]),
        useFiltersFile = verdad(raw.leer("use_filters_file", defaults.leer("use_filters_file", true))),
        deviceRemote = nombreDeviceRemote(defaults),
        versions = versions,
        watch = watch,
    )
}

/**
 * El remote y la carpeta del catálogo de un dispositivo: `catalog_remote` o el
 * `remote` de las parejas, y la carpeta de `catalog_path` acabada en `/` (o
 * `""` si es la raíz del remote).
 */
fun carpetaDelCatalogo(defaults: Map<String, Any?>): kotlin.Pair<String, String> {
    val remote = listOf(defaults["catalog_remote"], defaults["remote"]).firstOrNull { verdad(it) }
        ?: DEFAULT_REMOTE
    val ruta = strPy(defaults["catalog_path"].takeIf { verdad(it) } ?: DEFAULT_CATALOG_PATH)
    return strPy(remote) to ruta.substring(0, ruta.lastIndexOf('/') + 1)
}

/**
 * La pareja del llavero, a partir de `[keychain]` y los `[defaults]`. El TOML
 * no la puede cambiar: bisync con versiones, en `.keychain/` y en la subcarpeta
 * `keychain/` de la carpeta del catálogo, con sus filtros y sus flags. Ni los
 * flags ni los filtros de `[defaults]` le llegan.
 */
private fun construirLlavero(tabla: Any?, defaults: Map<String, Any?>): Pareja {
    if (tabla !is Map<*, *>) throw ConfigError("[keychain] tiene que ser una tabla.")
    val base = tabla["base"]
    if (base !is String || !base.endsWith(".kdbx") || base == ".kdbx" ||
        base.any { it in "/\\:" } || base.startsWith(".")
    ) {
        // `.kdbx` en minúsculas: los filtros de rclone distinguen, y `+ *.kdbx`
        // no dejaría pasar una `.KDBX`.
        throw ConfigError(
            "[keychain] tiene que decir qué base lleva, con «base = " +
                "\"<nombre>.kdbx\"», un nombre suelto que acabe en .kdbx " +
                "(en minúsculas).",
        )
    }
    val interna = if (tabla.containsKey("llave_interna")) tabla["llave_interna"] else false
    if (interna !is Boolean) throw ConfigError("[keychain] llave_interna tiene que ser true o false.")
    if (interna && !verdad(tabla["fichero_llave"])) {
        throw ConfigError(
            "[keychain] llave_interna = true necesita fichero_llave = true: " +
                "la base se abre solo con su fichero llave.",
        )
    }
    val (remote, carpeta) = carpetaDelCatalogo(defaults)
    val mode = MODES.getValue("bisync")
    val efectivos = flagsEfectivos(mode, null, LLAVERO_FLAGS)
    return Pareja(
        name = LLAVERO, mode = mode, local = LLAVERO_LOCAL,
        remotePath = carpeta + LLAVERO_REMOTO, remoteName = remote,
        includes = emptyList(), excludes = emptyList(),
        flags = efectivos.associate { it.nombre to it.valor },
        origenFlags = efectivos.associate { it.nombre to it.origen },
        extraFlags = emptyList(), useFiltersFile = true,
        deviceRemote = nombreDeviceRemote(defaults), versions = true,
        reglas = LLAVERO_REGLAS, llavero = true, llaveInterna = interna,
    )
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
    val crudo = defaults["device_remote"]
    if (!verdad(crudo)) return null
    val name = strPy(crudo)
    // Un `device_remote = true` hace caer a prdrive (`re.fullmatch` de un no
    // texto); aquí se rechaza con el mensaje de un nombre que no vale.
    if (crudo !is String || !NOMBRE_DEVICE_REMOTE.matches(name)) {
        throw ConfigError(
            "'device_remote' debe ser alfanumérico sin guiones (va en una " +
                "variable de entorno RCLONE_CONFIG_<NOMBRE>_*): '$name'",
        )
    }
    return name
}

/**
 * El mapa crudo del TOML, resuelto.
 *
 * Primero [comprobarSeguridad], como `parse_config()`. Cada pareja que
 * sincroniza la raíz entera recibe [REGLA_SIN_PROGRAMA] delante de sus filtros;
 * con `[keychain]`, la pareja del llavero va la última, su nombre queda
 * reservado y esas parejas reciben además [REGLA_SIN_LLAVERO].
 *
 * Una diferencia deliberada con el escritorio: aquí `device_remote` es
 * **obligatorio**. Allí es el valor de fábrica que el instalador mete en los
 * `[defaults]` de cada dispositivo nuevo, y un config antiguo sin él sigue
 * funcionando con rutas absolutas. Aquí no hay ruta absoluta que valga —el
 * volumen vive en `filesDir`, que cambia si se reinstala la app—, así que un
 * config sin `device_remote` no es un caso legado: es un config que rompería
 * los baselines la primera vez que Android mueva la carpeta. Se comprueba la
 * última, para que todo lo que también rechaza el escritorio se diga con sus
 * palabras.
 */
fun parseConfig(data: Map<String, Any?>): Config {
    comprobarSeguridad(data)
    val defaults = tablaDe(data, "defaults") ?: emptyMap()
    val rawPairs = parejasDe(data)
    if (rawPairs.isEmpty()) {
        throw ConfigError("El config no tiene ninguna [[pair]] definida.")
    }
    if ("watch" in defaults) {
        // Solo se lee de `[[pair]]`: en `[defaults]` no haría nada y parecería
        // que vigila todas las parejas.
        throw ConfigError(
            "[defaults] no admite 'watch': vigilar los cambios locales se pide " +
                "pareja a pareja, con 'watch = true' en cada [[pair]] que lo quiera.",
        )
    }
    var pairs = rawPairs.map { construirPareja(it, defaults) }
    val conLlavero = data.containsKey("keychain")
    if (conLlavero && pairs.any { it.name == LLAVERO }) {
        throw ConfigError(
            "Hay una pareja que se llama '$LLAVERO', que es el nombre de la " +
                "del llavero. Renómbrala o quita [keychain].",
        )
    }
    val reglasRaiz = listOf(REGLA_SIN_PROGRAMA) + if (conLlavero) listOf(REGLA_SIN_LLAVERO) else emptyList()
    pairs = pairs.map { if (it.esRaiz) it.copy(reglas = reglasRaiz + it.reglas) else it }
    if (conLlavero) pairs = pairs + construirLlavero(data["keychain"], defaults)

    val deviceRemote = nombreDeviceRemote(defaults)
        ?: throw ConfigError(
            "Los [defaults] del config no traen 'device_remote'. En la app el lado " +
                "local va siempre como un remote 'combine' (lo normal es " +
                "'$DEFAULT_DEVICE_REMOTE'): sin él, el nombre de los listados de " +
                "bisync llevaría dentro la ruta de la carpeta de la app y cambiaría " +
                "al reinstalarla.",
        )
    return Config(
        pairs = pairs,
        daemon = tablaDe(data, "daemon") ?: emptyMap(),
        keepLogs = verdad(defaults["keep_logs"]),
        deviceRemote = deviceRemote,
        llavero = if (conLlavero) tablaDe(data, "keychain") ?: emptyMap() else null,
    )
}
