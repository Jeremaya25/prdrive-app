package prdrive.engine

/**
 * Json.kt — El JSON con el que se le habla al RPC de rclone.
 *
 * Escrito a mano por la misma razón que [loadRaw] lee el TOML a mano y que
 * `ui/qr.py` codifica sus propios QR en prdrive: aquí no entran dependencias.
 * Y el trabajo es pequeño porque el JSON que hace falta es pequeño — objetos,
 * arrays, cadenas, números, booleanos y null—, que es justo lo que se cruza
 * con `librclone.RPC`.
 *
 * **Lo que escribe tiene que salir igual que lo que escribe Go.** No es un
 * capricho: `rclone/spike` apunta en `sesiones.json` la cadena exacta que
 * rclone aceptó en cada pasada, y `SesionesTest` la compara con la que genera
 * [Pasada]. Ahí está la garantía de que los nombres y los tipos de los
 * parámetros son los que rclone acepta de verdad y no los que parecían. Para
 * que esa comparación signifique algo hay tres reglas, las tres copiadas de
 * `encoding/json`:
 *
 *  - **Las claves de un objeto salen ordenadas**, como las de un `map` de Go.
 *  - Se escapa lo que el JSON obliga y nada más (`"`, `\` y los caracteres de
 *    control), que es lo que hace Go con `SetEscapeHTML(false)` — el spike
 *    escribe así a propósito. Sin eso, un `&` en una ruta saldría `&` en
 *    un lado y crudo en el otro.
 *  - Un número entero se escribe sin parte decimal, también cuando llega como
 *    `Double` con valor entero: Go escribe `4`, no `4.0`.
 *
 * Los números salen como `Long` o `Double` al leer, igual que [loadRaw] hace
 * con el TOML, para que un valor del config y el mismo valor de una respuesta
 * se comparen sin convertir nada.
 */

/** El JSON no se puede leer, o no se puede escribir lo que se le ha dado. */
class JsonError(mensaje: String) : Exception(mensaje)

/**
 * El texto JSON de un valor, compacto y con las claves ordenadas.
 *
 * Admite `Map<*, *>` (las claves se escriben con `toString()`), `Collection`,
 * `Array`, `String`, `Boolean`, los números y `null`. Cualquier otra cosa es
 * un [JsonError] y no una cadena rara: lo que se le manda a rclone no puede
 * quedar en manos de un `toString()` de casualidad.
 */
fun escribirJson(valor: Any?): String {
    val b = StringBuilder()
    escribirValor(b, valor)
    return b.toString()
}

private fun escribirValor(b: StringBuilder, valor: Any?) {
    when (valor) {
        null -> b.append("null")
        is Boolean -> b.append(if (valor) "true" else "false")
        is String -> escribirCadena(b, valor)
        is Map<*, *> -> escribirObjeto(b, valor)
        is Collection<*> -> escribirArray(b, valor)
        is Array<*> -> escribirArray(b, valor.asList())
        is Byte, is Short, is Int, is Long -> b.append(valor.toString())
        is Float, is Double -> b.append(numero((valor as Number).toDouble()))
        else -> throw JsonError(
            "no sé escribir un ${valor::class.simpleName} en JSON: '$valor'",
        )
    }
}

private fun escribirObjeto(b: StringBuilder, mapa: Map<*, *>) {
    // Ordenadas, como las escribe Go: es lo que hace comparable el JSON del
    // motor con el que apuntó el spike.
    val pares = mapa.entries
        .map { (clave, valor) ->
            (clave?.toString() ?: throw JsonError("un objeto JSON con clave nula")) to valor
        }
        .sortedBy { it.first }
    for (j in 1 until pares.size) {
        if (pares[j].first == pares[j - 1].first) {
            throw JsonError("dos claves iguales en el mismo objeto JSON: '${pares[j].first}'")
        }
    }
    b.append('{')
    pares.forEachIndexed { j, (clave, valor) ->
        if (j > 0) b.append(',')
        escribirCadena(b, clave)
        b.append(':')
        escribirValor(b, valor)
    }
    b.append('}')
}

private fun escribirArray(b: StringBuilder, valores: Collection<*>) {
    b.append('[')
    var primera = true
    for (v in valores) {
        if (!primera) b.append(',')
        primera = false
        escribirValor(b, v)
    }
    b.append(']')
}

private const val HEX = "0123456789abcdef"

private fun escribirCadena(b: StringBuilder, texto: String) {
    b.append('"')
    for (c in texto) {
        when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            // Go escribe los demás de control como \u00xx en minúsculas, y no
            // usa \b ni \f. Y U+2028/U+2029 siempre, porque rompen JavaScript.
            c < ' ' || c == ' ' || c == ' ' -> {
                b.append("\\u")
                b.append(HEX[(c.code shr 12) and 0xF])
                b.append(HEX[(c.code shr 8) and 0xF])
                b.append(HEX[(c.code shr 4) and 0xF])
                b.append(HEX[c.code and 0xF])
            }
            else -> b.append(c)
        }
    }
    b.append('"')
}

/** Un número como lo escribe Go: sin `.0` si el valor es entero. */
private fun numero(d: Double): String {
    if (d.isNaN() || d.isInfinite()) throw JsonError("un número que no es finito no cabe en JSON: $d")
    return if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
}

/**
 * El documento JSON leído: `Map`, `List`, `String`, `Long`, `Double`,
 * `Boolean` o `null`.
 */
fun leerJson(texto: String): Any? = LectorJson(texto).documento()

/** Una respuesta del RPC, que siempre es un objeto. */
@Suppress("UNCHECKED_CAST")
fun leerObjetoJson(texto: String): Map<String, Any?> =
    leerJson(texto) as? Map<String, Any?>
        ?: throw JsonError("esperaba un objeto JSON y no lo es: ${texto.take(120)}")

/**
 * El lector: recursivo descendente, sin estado global, sin sorpresas. No es un
 * JSON de propósito general —no admite escapes raros ni números fuera de lo
 * que rclone escribe— y no tiene que serlo: si se equivoca, lo dice con la
 * posición en la mano.
 */
private class LectorJson(private val texto: String) {
    private var i = 0

    fun documento(): Any? {
        val valor = leerValor()
        blancos()
        if (i < texto.length) fallo("sobra texto después del documento")
        return valor
    }

    private fun blancos() {
        while (i < texto.length && texto[i].isWhitespace()) i++
    }

    private fun fallo(que: String): Nothing =
        throw JsonError("JSON ilegible en la posición $i: $que")

    private fun leerValor(): Any? {
        blancos()
        if (i >= texto.length) fallo("se acaba antes de tiempo")
        return when (val c = texto[i]) {
            '{' -> leerObjeto()
            '[' -> leerLista()
            '"' -> leerCadena()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) leerNumero() else fallo("no esperaba '$c'")
        }
    }

    private fun literal(cual: String, valor: Any?): Any? {
        if (!texto.startsWith(cual, i)) fallo("esperaba '$cual'")
        i += cual.length
        return valor
    }

    private fun leerObjeto(): Map<String, Any?> {
        i++                                     // '{'
        val salida = LinkedHashMap<String, Any?>()
        blancos()
        if (i < texto.length && texto[i] == '}') { i++; return salida }
        while (true) {
            blancos()
            if (i >= texto.length || texto[i] != '"') fallo("esperaba una clave")
            val clave = leerCadena()
            blancos()
            if (i >= texto.length || texto[i] != ':') fallo("esperaba ':'")
            i++
            salida[clave] = leerValor()
            blancos()
            if (i >= texto.length) fallo("el objeto no se cierra")
            when (texto[i]) {
                ',' -> i++
                '}' -> { i++; return salida }
                else -> fallo("esperaba ',' o '}'")
            }
        }
    }

    private fun leerLista(): List<Any?> {
        i++                                     // '['
        val salida = ArrayList<Any?>()
        blancos()
        if (i < texto.length && texto[i] == ']') { i++; return salida }
        while (true) {
            salida.add(leerValor())
            blancos()
            if (i >= texto.length) fallo("la lista no se cierra")
            when (texto[i]) {
                ',' -> i++
                ']' -> { i++; return salida }
                else -> fallo("esperaba ',' o ']'")
            }
        }
    }

    private fun leerCadena(): String {
        i++                                     // '"'
        val salida = StringBuilder()
        while (true) {
            if (i >= texto.length) fallo("la cadena no se cierra")
            when (val c = texto[i]) {
                '"' -> { i++; return salida.toString() }
                '\\' -> {
                    i++
                    if (i >= texto.length) fallo("escape a medias")
                    when (val e = texto[i]) {
                        '"' -> salida.append('"')
                        '\\' -> salida.append('\\')
                        '/' -> salida.append('/')
                        'b' -> salida.append('\b')
                        'f' -> salida.append('\u000C')
                        'n' -> salida.append('\n')
                        'r' -> salida.append('\r')
                        't' -> salida.append('\t')
                        'u' -> {
                            if (i + 5 > texto.length) fallo("escape \\u a medias")
                            salida.append(texto.substring(i + 1, i + 5).toInt(16).toChar())
                            i += 4
                        }
                        else -> fallo("escape '\\$e' no soportado")
                    }
                    i++
                }
                else -> { salida.append(c); i++ }
            }
        }
    }

    private fun leerNumero(): Any {
        val inicio = i
        if (texto[i] == '-') i++
        while (i < texto.length && texto[i].isDigit()) i++
        var decimal = false
        if (i < texto.length && texto[i] == '.') {
            decimal = true
            i++
            while (i < texto.length && texto[i].isDigit()) i++
        }
        if (i < texto.length && (texto[i] == 'e' || texto[i] == 'E')) {
            decimal = true
            i++
            if (i < texto.length && (texto[i] == '+' || texto[i] == '-')) i++
            while (i < texto.length && texto[i].isDigit()) i++
        }
        val crudo = texto.substring(inicio, i)
        return if (decimal) crudo.toDouble() else crudo.toLong()
    }
}
