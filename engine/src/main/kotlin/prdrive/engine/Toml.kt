package prdrive.engine

/**
 * Toml.kt — Leer y escribir el TOML del proyecto.
 *
 * Espejo de `common/config_file.py` de prdrive (ver la versión citada en
 * `engine/src/test/resources/vectores.json`). El original existe porque
 * `tomllib` solo lee y prdrive no admite dependencias; aquí la razón es la
 * misma más una: lo que se escriba tiene que ser **byte a byte** lo que el
 * programa de escritorio luego relee, porque el `sync_config.toml` se edita a
 * mano y el `remote.toml` del catálogo lo comparten todos los dispositivos.
 *
 * **No es un TOML completo y no pretende serlo.** Cubre el esquema que el
 * proyecto usa de verdad: escalares, arrays de cadenas, `[tabla]`,
 * `[[array de tablas]]` y una subtabla `flags`. Lo que no entiende lo **rechaza
 * en voz alta** ([TomlError]) en vez de ignorarlo: `tomllib` acepta más que
 * esto, así que un catálogo escrito a mano con un TOML legítimo que aquí no
 * entre tiene que dar un mensaje, nunca una pareja a medio leer.
 *
 * Se trabaja siempre con el mapa **crudo**, nunca con [Config]: sus [Pareja]
 * llegan con los `[defaults]` ya fundidos, así que volcarlas duplicaría los
 * defaults dentro de cada pareja.
 */

/** El TOML no se puede leer, o no se puede escribir lo que se pide. */
class TomlError(mensaje: String) : Exception(mensaje)

/**
 * Orden con el que se escriben las claves de una pareja: primero lo que la
 * identifica, luego lo que la matiza. El resto va detrás, alfabético.
 */
val PAIR_KEY_ORDER = listOf("name", "local", "remote_path", "remote", "mode")

/** Las tablas que [dumps] escribe en su sitio; cualquier otra va al final, tal cual. */
val TABLAS_CONOCIDAS = listOf("remote", "defaults", "daemon", "pair")

private val BARE_KEY = Regex("[A-Za-z0-9_-]+")

// ---------------------------------------------------------------------------
// Lectura
// ---------------------------------------------------------------------------

/**
 * El TOML tal cual, sin resolver capas: mapas, listas, `String`, `Long`,
 * `Double` y `Boolean`. Los enteros llegan como `Long` **siempre**, que es lo
 * que hace comparables dos mapas leídos por caminos distintos.
 */
fun loadRaw(texto: String): Map<String, Any?> {
    val raiz = LinkedHashMap<String, Any?>()
    // Dónde caen los pares `clave = valor` que vengan ahora. Cambia con cada
    // cabecera, y con `[[pair]]` apunta al último elemento del array.
    var actual: MutableMap<String, Any?> = raiz
    var ultimoArray: MutableMap<String, Any?>? = null

    val lineas = Lineas(texto)
    while (lineas.hay()) {
        val linea = lineas.siguiente().trim()
        if (linea.isEmpty() || linea.startsWith("#")) continue

        if (linea.startsWith("[[")) {
            val nombre = cabecera(linea, "[[", "]]", lineas.numero)
            val lista = arrayDeTablas(raiz, nombre, lineas.numero)
            val nueva = LinkedHashMap<String, Any?>()
            lista.add(nueva)
            actual = nueva
            ultimoArray = nueva
            continue
        }
        if (linea.startsWith("[")) {
            val nombre = cabecera(linea, "[", "]", lineas.numero)
            actual = tabla(raiz, ultimoArray, nombre, lineas.numero)
            continue
        }

        val corte = linea.indexOf('=')
        if (corte < 0) throw TomlError("Línea ${lineas.numero}: falta el '=' en «$linea».")
        val clave = leerClave(linea.substring(0, corte).trim(), lineas.numero)
        val valor = leerValor(linea.substring(corte + 1).trim(), lineas)
        if (actual.containsKey(clave)) {
            throw TomlError("Línea ${lineas.numero}: '$clave' está definida dos veces.")
        }
        actual[clave] = valor
    }
    return raiz
}

/** El bloque de comentarios del principio de un TOML, tal cual. */
fun headerOf(texto: String): String {
    val cabecera = ArrayList<String>()
    for (linea in texto.lines()) {
        if (linea.isNotBlank() && !linea.trimStart().startsWith("#")) break
        cabecera.add(linea)
    }
    if (cabecera.none { it.isNotBlank() }) return ""
    return cabecera.joinToString("\n").trimEnd() + "\n"
}

/**
 * Un lector de líneas que además sabe seguir leyendo: un array puede ocupar
 * varias, que es justo como los escribe [dumps].
 */
private class Lineas(texto: String) {
    private val todas = texto.lines()
    private var i = 0
    var numero = 0
        private set

    fun hay() = i < todas.size

    fun siguiente(): String {
        numero = i + 1
        return todas[i++]
    }
}

private fun cabecera(linea: String, abre: String, cierra: String, numero: Int): String {
    if (!linea.endsWith(cierra)) {
        throw TomlError("Línea $numero: la cabecera «$linea» no se cierra con '$cierra'.")
    }
    val nombre = linea.substring(abre.length, linea.length - cierra.length).trim()
    if (nombre.isEmpty()) throw TomlError("Línea $numero: cabecera vacía.")
    return nombre
}

@Suppress("UNCHECKED_CAST")
private fun arrayDeTablas(
    raiz: MutableMap<String, Any?>,
    nombre: String,
    numero: Int,
): MutableList<MutableMap<String, Any?>> {
    if (nombre.contains('.')) {
        throw TomlError("Línea $numero: «[[$nombre]]» anidado no está soportado.")
    }
    val actual = raiz[nombre]
    if (actual == null) {
        val lista = ArrayList<MutableMap<String, Any?>>()
        raiz[nombre] = lista
        return lista
    }
    if (actual !is MutableList<*>) {
        throw TomlError("Línea $numero: '$nombre' ya existe y no es un array de tablas.")
    }
    return actual as MutableList<MutableMap<String, Any?>>
}

@Suppress("UNCHECKED_CAST")
private fun tabla(
    raiz: MutableMap<String, Any?>,
    ultimoArray: MutableMap<String, Any?>?,
    nombre: String,
    numero: Int,
): MutableMap<String, Any?> {
    val tramos = nombre.split('.').map { leerClave(it.trim(), numero) }
    // `[pair.flags]` se engancha a la ÚLTIMA `[[pair]]` leída: es la semántica
    // de TOML y es por lo que el serializador escribe esa subtabla pegada a su
    // pareja y nunca al final del fichero.
    val enPareja = tramos.size > 1 && tramos[0] == "pair"
    var donde: MutableMap<String, Any?> = if (enPareja) {
        ultimoArray ?: throw TomlError(
            "Línea $numero: «[$nombre]» sin ninguna [[pair]] delante.",
        )
    } else {
        raiz
    }
    val camino = if (enPareja) tramos.drop(1) else tramos
    for ((indice, tramo) in camino.withIndex()) {
        val hijo = donde[tramo]
        if (hijo == null) {
            val nueva = LinkedHashMap<String, Any?>()
            donde[tramo] = nueva
            donde = nueva
        } else if (hijo is MutableMap<*, *>) {
            donde = hijo as MutableMap<String, Any?>
        } else {
            throw TomlError(
                "Línea $numero: '${camino.take(indice + 1).joinToString(".")}' " +
                    "ya existe y no es una tabla.",
            )
        }
    }
    return donde
}

private fun leerClave(texto: String, numero: Int): String {
    if (texto.isEmpty()) throw TomlError("Línea $numero: falta el nombre de la clave.")
    if (texto.startsWith("\"")) return leerCadena(texto, numero)
    if (!BARE_KEY.matches(texto)) {
        throw TomlError("Línea $numero: '$texto' no es un nombre de clave que se entienda aquí.")
    }
    return texto
}

private fun leerValor(texto: String, lineas: Lineas): Any? {
    val limpio = sinComentario(texto)
    if (limpio.isEmpty()) throw TomlError("Línea ${lineas.numero}: falta el valor.")
    if (limpio.startsWith("[")) return leerArray(limpio, lineas)
    return leerEscalar(limpio, lineas.numero)
}

/**
 * Quita el comentario del final de una línea de valor, respetando las comillas:
 * un `#` dentro de una cadena es parte de la cadena.
 */
private fun sinComentario(texto: String): String {
    var dentro = false
    var escapando = false
    for ((i, c) in texto.withIndex()) {
        when {
            escapando -> escapando = false
            c == '\\' && dentro -> escapando = true
            c == '"' -> dentro = !dentro
            c == '#' && !dentro -> return texto.substring(0, i).trim()
        }
    }
    return texto.trim()
}

private fun leerArray(primera: String, lineas: Lineas): List<Any?> {
    val cuerpo = StringBuilder(primera)
    // Un array puede seguir en las líneas de abajo, que es como lo escribe
    // `dumps` en cuanto tiene más de un elemento.
    while (!cerrado(cuerpo)) {
        if (!lineas.hay()) throw TomlError("Línea ${lineas.numero}: el array no se cierra.")
        cuerpo.append('\n').append(sinComentario(lineas.siguiente()))
    }
    val dentro = cuerpo.substring(cuerpo.indexOf("[") + 1, cuerpo.lastIndexOf("]"))
    return partirArray(dentro, lineas.numero).map { leerEscalar(it, lineas.numero) }
}

private fun cerrado(texto: CharSequence): Boolean {
    var nivel = 0
    var dentro = false
    var escapando = false
    for (c in texto) {
        when {
            escapando -> escapando = false
            c == '\\' && dentro -> escapando = true
            c == '"' -> dentro = !dentro
            dentro -> {}
            c == '[' -> nivel++
            c == ']' -> nivel--
        }
    }
    return nivel == 0
}

/** Parte por las comas de primer nivel, sin cortar dentro de una cadena. */
private fun partirArray(texto: String, numero: Int): List<String> {
    val partes = ArrayList<String>()
    val actual = StringBuilder()
    var dentro = false
    var escapando = false
    for (c in texto) {
        when {
            escapando -> {
                actual.append(c)
                escapando = false
            }
            c == '\\' && dentro -> {
                actual.append(c)
                escapando = true
            }
            c == '"' -> {
                actual.append(c)
                dentro = !dentro
            }
            c == ',' && !dentro -> {
                partes.add(actual.toString())
                actual.clear()
            }
            else -> actual.append(c)
        }
    }
    partes.add(actual.toString())
    if (dentro) throw TomlError("Línea $numero: una cadena del array no se cierra.")
    // La coma final de `dumps` deja un hueco vacío detrás: no es un elemento.
    return partes.map { it.trim() }.filter { it.isNotEmpty() }
}

private fun leerEscalar(texto: String, numero: Int): Any {
    if (texto.startsWith("\"")) return leerCadena(texto, numero)
    if (texto.startsWith("'")) {
        // Cadena literal: sin escapes, tal cual entre comillas simples.
        if (texto.length < 2 || !texto.endsWith("'")) {
            throw TomlError("Línea $numero: la cadena literal «$texto» no se cierra.")
        }
        val dentro = texto.substring(1, texto.length - 1)
        dentro.firstOrNull { prohibido(it) }?.let { caracterDeControl(it, numero) }
        return dentro
    }
    if (texto == "true") return true
    if (texto == "false") return false
    // Lo que escribe `repr()` de Python para un float que no es un número.
    when (texto) {
        "inf", "+inf" -> return Double.POSITIVE_INFINITY
        "-inf" -> return Double.NEGATIVE_INFINITY
        "nan", "+nan", "-nan" -> return Double.NaN
    }
    // Cifras ASCII y nada más, como TOML: en Android `\d` admite las de
    // cualquier alfabeto, así que se escriben a mano.
    val sinGuiones = texto.replace("_", "")
    if (Regex("[+-]?[0-9]+").matches(sinGuiones)) {
        return sinGuiones.toLongOrNull()
            ?: throw TomlError("Línea $numero: el entero «$texto» no cabe.")
    }
    if (Regex("[+-]?[0-9]+\\.[0-9]+([eE][+-]?[0-9]+)?").matches(sinGuiones) ||
        Regex("[+-]?[0-9]+[eE][+-]?[0-9]+").matches(sinGuiones)
    ) {
        return sinGuiones.toDouble()
    }
    throw TomlError("Línea $numero: «$texto» no es un valor que se entienda aquí.")
}

/**
 * Lo que `tomllib` no deja escribir tal cual dentro de una cadena: los
 * caracteres de control, salvo el tabulador. Aceptarlos aquí haría que el
 * teléfono escribiera un fichero que el escritorio no puede leer.
 */
private fun prohibido(c: Char): Boolean = (c.code < 0x20 && c != '\t') || c.code == 0x7f

private fun caracterDeControl(c: Char, numero: Int): Nothing =
    throw TomlError("Línea $numero: carácter de control U+${hex(c.code, 4).uppercase()} en una cadena.")

private fun leerCadena(texto: String, numero: Int): String {
    if (texto.length < 2 || !texto.endsWith("\"")) {
        throw TomlError("Línea $numero: la cadena «$texto» no se cierra.")
    }
    val dentro = texto.substring(1, texto.length - 1)
    val salida = StringBuilder()
    var i = 0
    while (i < dentro.length) {
        val c = dentro[i]
        if (c != '\\') {
            if (c == '"') throw TomlError("Línea $numero: comilla sin escapar en «$texto».")
            if (prohibido(c)) caracterDeControl(c, numero)
            salida.append(c)
            i++
            continue
        }
        i++
        if (i >= dentro.length) throw TomlError("Línea $numero: escape a medias en «$texto».")
        // Los escapes de TOML 1.0, los mismos que acepta `tomllib`.
        when (val e = dentro[i]) {
            '"' -> salida.append('"')
            '\\' -> salida.append('\\')
            'n' -> salida.append('\n')
            'r' -> salida.append('\r')
            't' -> salida.append('\t')
            'b' -> salida.append('\b')
            'f' -> salida.append('\u000c')
            'u', 'U' -> {
                val largo = if (e == 'u') 4 else 8
                val cifras = if (i + 1 + largo <= dentro.length) dentro.substring(i + 1, i + 1 + largo) else ""
                if (cifras.length != largo || !cifras.all { it in "0123456789abcdefABCDEF" }) {
                    throw TomlError("Línea $numero: escape «\\$e$cifras» mal formado en «$texto».")
                }
                val punto = cifras.toLong(16)
                if (punto > 0x10FFFF || punto in 0xD800..0xDFFF) {
                    throw TomlError("Línea $numero: «\\$e$cifras» no es un carácter Unicode.")
                }
                salida.appendCodePoint(punto.toInt())
                i += largo
            }
            else -> throw TomlError("Línea $numero: escape «\\$e» no soportado.")
        }
        i++
    }
    return salida.toString()
}

// ---------------------------------------------------------------------------
// Escritura
// ---------------------------------------------------------------------------

private fun clave(nombre: String) = if (BARE_KEY.matches(nombre)) nombre else cadena(nombre)

private fun cadena(valor: Any?): String {
    val escapado = valor.toString()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return "\"$escapado\""
}

private fun escalar(valor: Any?): String = when (valor) {
    is Boolean -> if (valor) "true" else "false"
    is Long, is Int, is Short, is Byte -> valor.toString()
    // `repr()` de Python, que es lo que escribe `_scalar()`: «4.0» y «1e+20».
    is Double -> floatPy(valor)
    is Float -> floatPy(valor.toDouble())
    else -> cadena(valor)
}

private fun array(valores: Collection<*>): String {
    val items = valores.toList()
    if (items.isEmpty()) return "[]"
    if (items.size == 1) return "[${escalar(items[0])}]"
    return "[\n" + items.joinToString("") { "    ${escalar(it)},\n" } + "]"
}

/** Las claves escalares y de array de una tabla, sin su cabecera. */
private fun cuerpoDeTabla(
    tabla: Map<String, Any?>,
    orden: List<String> = emptyList(),
): List<String> {
    val claves = ArrayList(orden)
    // `sorted()` de Kotlin compara por unidad de código UTF-16 y `sorted()` de
    // Python por punto de código: para las claves de este esquema —ASCII— es el
    // mismo orden, y es lo que hace cuadrar los vectores.
    claves += tabla.keys.filter { it !in claves && it != "flags" }.sorted()
    val lineas = ArrayList<String>()
    for (k in claves) {
        if (!tabla.containsKey(k)) continue
        when (val valor = tabla[k]) {
            is Map<*, *> -> continue          // las subtablas van aparte
            is Collection<*> -> lineas.add("${clave(k)} = ${array(valor)}")
            else -> lineas.add("${clave(k)} = ${escalar(valor)}")
        }
    }
    return lineas
}

/**
 * Una tabla suelta como texto TOML, una clave por línea.
 *
 * Existe para el editor de flags: lo que se le enseña al usuario tiene que ser
 * exactamente lo que este fichero escribiría, o el formulario diría una cosa y
 * el TOML acabaría con otra.
 */
fun dumpsTable(tabla: Map<String, Any?>): String = cuerpoDeTabla(tabla).joinToString("\n")

/** El mapa crudo como texto TOML. */
fun dumps(raw: Map<String, Any?>, head: String = ""): String {
    val out = ArrayList<String>()
    if (head.isNotEmpty()) out.add(head.trimEnd() + "\n")

    // `[remote]` solo aparece en el catálogo: es cómo se define el remote en el
    // rclone.conf de cada dispositivo. Arriba es seguro; la que no puede
    // moverse es `[pair.flags]`, que se engancha a la ÚLTIMA `[[pair]]`.
    tablaDe(raw, "remote")?.let {
        out.add("[remote]")
        out += cuerpoDeTabla(it, listOf("name", "type", "host", "port", "user"))
        out.add("")
    }

    tablaDe(raw, "defaults")?.let { defaults ->
        out.add("[defaults]")
        out += cuerpoDeTabla(
            defaults,
            listOf("remote", "device_remote", "catalog_path", "keep_logs"),
        )
        out.add("")
        tablaDe(defaults, "flags")?.let {
            out.add("[defaults.flags]")
            out += cuerpoDeTabla(it)
            out.add("")
        }
    }

    tablaDe(raw, "daemon")?.let {
        out.add("[daemon]")
        out += cuerpoDeTabla(it, listOf("pairs", "interval_minutes"))
        out.add("")
    }

    for (pareja in parejasDe(raw)) {
        out.add("[[pair]]")
        out += cuerpoDeTabla(pareja, PAIR_KEY_ORDER)
        out.add("")
        tablaDe(pareja, "flags")?.let {
            // OJO: `[pair.flags]` se engancha a la ÚLTIMA `[[pair]]` escrita.
            // Por eso va aquí, pegada a la suya, y nunca al final del fichero.
            out.add("[pair.flags]")
            out += cuerpoDeTabla(it)
            out.add("")
        }
    }

    // Una tabla que este código no conoce (la de una versión más nueva, o el
    // `[keychain]`) se escribe tal cual al final, en orden alfabético: el modelo
    // la ignora al leer, y perderla al reescribir haría que [dumpsChecked] se
    // negara a escribir el fichero entero. Lo que no se sepa escribir (una
    // subtabla) lo sigue parando él.
    for (nombre in raw.keys.filter { it !in TABLAS_CONOCIDAS && raw[it] is Map<*, *> }.sorted()) {
        @Suppress("UNCHECKED_CAST")
        val tabla = raw[nombre] as Map<String, Any?>
        out.add("[${clave(nombre)}]")
        out += cuerpoDeTabla(tabla)
        out.add("")
    }

    return out.joinToString("\n").trimEnd() + "\n"
}

/**
 * El TOML generado, ya releído y comprobado. [TomlError] si algo no cuadra.
 *
 * Es la mitad de `save()` que no depende de escribir en disco, y por eso vive
 * aparte: el catálogo del remoto pasa por aquí antes de subirse, y allí un
 * fichero que no se relee igual es todavía peor, porque gobierna borrados en
 * TODOS los dispositivos y no solo en este.
 */
fun dumpsChecked(raw: Map<String, Any?>, head: String = ""): String {
    parseConfig(raw)                       // ¿tiene sentido lo que se pide?

    val texto = dumps(raw, head)

    val releido = try {
        loadRaw(texto)
    } catch (e: TomlError) {
        throw TomlError("El config generado no es TOML válido (${e.message}). No se ha escrito.")
    }
    if (normalizar(releido) != normalizar(raw)) {
        throw TomlError(
            "El config generado no reproduce lo que se pidió. No se ha escrito.\n" +
                "Es un fallo del serializador, no de tu configuración.",
        )
    }
    return texto
}

/**
 * Deja un mapa crudo en la forma en la que lo devuelve [loadRaw], para poder
 * compararlos: enteros a `Long` y colecciones a `List`. Sin esto, un 25 escrito
 * como `Int` y el mismo 25 releído como `Long` no serían iguales y
 * [dumpsChecked] se negaría a escribir un config perfectamente bueno.
 */
fun normalizar(valor: Any?): Any? = when (valor) {
    is Map<*, *> -> valor.entries.associate { (k, v) -> k.toString() to normalizar(v) }
    is Collection<*> -> valor.map { normalizar(it) }
    is Int, is Short, is Byte -> (valor as Number).toLong()
    is Float -> valor.toDouble()
    else -> valor
}

@Suppress("UNCHECKED_CAST")
internal fun tablaDe(raw: Map<String, Any?>, nombre: String): Map<String, Any?>? {
    val valor = raw[nombre] as? Map<String, Any?> ?: return null
    return valor.ifEmpty { null }
}

@Suppress("UNCHECKED_CAST")
internal fun parejasDe(raw: Map<String, Any?>): List<Map<String, Any?>> =
    (raw["pair"] as? Collection<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
