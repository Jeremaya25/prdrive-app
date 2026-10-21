package prdrive.engine

/**
 * Json.kt — Leer y escribir JSON como lo hace el módulo `json` de Python.
 *
 * No refleja un fichero de prdrive sino la biblioteca estándar que usa:
 * `store.write_json()` escribe todo lo de `state/` con
 * `json.dumps(datos, ensure_ascii=False, indent=1)`, y el escritorio tiene que
 * poder leer lo que escribe el teléfono y al revés. [escribirJson] reproduce ese
 * formato byte a byte (lo comprueban los vectores) y [leerJson] lee lo que
 * escribe cualquiera de los dos.
 *
 * Escrito a mano por la misma razón que el TOML: el proyecto no admite
 * dependencias. Cubre objetos, arrays, cadenas, números, booleanos y null, y
 * devuelve `Map`, `List`, `String`, `Long`, `Double`, `Boolean` y `null` —los
 * mismos tipos que [loadRaw]—, así que un valor del JSON y el mismo leído de un
 * TOML se comparan sin convertir nada. La excepción es un entero que no cabe
 * en un `Long`, que sale como `BigInteger`: Python lo lee, y si aquí no se
 * leyera, todo el fichero que lo lleva contaría como vacío.
 */

/** El texto no es JSON, o no uno que se entienda aquí. */
class JsonError(mensaje: String) : Exception(mensaje)

/** Lee un documento JSON entero. [JsonError] si no lo es. */
fun leerJson(texto: String): Any? = LectorJson(texto).leerDocumento()

/** Un entero con el tipo con el que lo devolvería [leerJson]: `Long` si cabe. */
fun comoJson(n: java.math.BigInteger): Number = if (n.bitLength() < 64) n.toLong() else n

/**
 * `json.dumps(valor, ensure_ascii=False, indent=1)`: un nivel de sangría por
 * espacio, `": "` entre clave y valor, y sin escapar nada que no sea una
 * comilla, una barra invertida o un carácter de control.
 */
fun escribirJson(valor: Any?): String = StringBuilder().also { escribir(it, valor, 0) }.toString()

private fun escribir(salida: StringBuilder, valor: Any?, nivel: Int) {
    when (valor) {
        null -> salida.append("null")
        is Boolean -> salida.append(if (valor) "true" else "false")
        is Double -> salida.append(numeroJson(valor))
        is Float -> salida.append(numeroJson(valor.toDouble()))
        is java.math.BigInteger -> salida.append(valor.toString())
        is Number -> salida.append(valor.toLong())
        is String -> cadenaJson(salida, valor)
        is Map<*, *> -> {
            if (valor.isEmpty()) {
                salida.append("{}")
                return
            }
            salida.append("{")
            var primero = true
            for ((clave, dentro) in valor) {
                salida.append(if (primero) "\n" else ",\n")
                primero = false
                salida.append(" ".repeat(nivel + 1))
                cadenaJson(salida, clave.toString())
                salida.append(": ")
                escribir(salida, dentro, nivel + 1)
            }
            salida.append("\n").append(" ".repeat(nivel)).append("}")
        }
        is Collection<*> -> {
            if (valor.isEmpty()) {
                salida.append("[]")
                return
            }
            salida.append("[")
            var primero = true
            for (dentro in valor) {
                salida.append(if (primero) "\n" else ",\n")
                primero = false
                salida.append(" ".repeat(nivel + 1))
                escribir(salida, dentro, nivel + 1)
            }
            salida.append("\n").append(" ".repeat(nivel)).append("]")
        }
        else -> cadenaJson(salida, valor.toString())
    }
}

/** `float.__repr__`, salvo lo que JSON no tiene: Python escribe `NaN` e `Infinity`. */
private fun numeroJson(valor: Double): String = when {
    valor.isNaN() -> "NaN"
    valor == Double.POSITIVE_INFINITY -> "Infinity"
    valor == Double.NEGATIVE_INFINITY -> "-Infinity"
    else -> floatPy(valor)
}

private fun cadenaJson(salida: StringBuilder, texto: String) {
    salida.append('"')
    for (c in texto) {
        when (c) {
            '"' -> salida.append("\\\"")
            '\\' -> salida.append("\\\\")
            '\n' -> salida.append("\\n")
            '\r' -> salida.append("\\r")
            '\t' -> salida.append("\\t")
            '\b' -> salida.append("\\b")
            '\u000c' -> salida.append("\\f")
            else -> if (c.code < 0x20) salida.append("\\u").append(hex(c.code, 4)) else salida.append(c)
        }
    }
    salida.append('"')
}

/**
 * El lector. Recursivo descendente, sin estado global, sin sorpresas: los
 * enteros salen como `Long` (o `BigInteger`) y los que llevan `.` o exponente
 * como `Double`. Lo que acepta es lo que acepta `json.loads`.
 */
private class LectorJson(private val texto: String) {
    private var i = 0

    fun leerDocumento(): Any? {
        val valor = leerValor()
        blancos()
        if (i < texto.length) fallo("sobra texto después del documento")
        return valor
    }

    private fun blancos() {
        while (i < texto.length && texto[i] in " \t\n\r") i++
    }

    // Como `json.loads`: los números son ASCII (`isDigit()` de Kotlin admitiría
    // las cifras de cualquier alfabeto).
    private fun cifra(c: Char): Boolean = c in '0'..'9'

    private fun fallo(que: String): Nothing = throw JsonError("JSON inválido en la posición $i: $que")

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
            // Lo que escribe (y lee) el `json` de Python para los floats que no son números.
            'N' -> literal("NaN", Double.NaN)
            'I' -> literal("Infinity", Double.POSITIVE_INFINITY)
            else -> when {
                texto.startsWith("-Infinity", i) -> literal("-Infinity", Double.NEGATIVE_INFINITY)
                c == '-' || cifra(c) -> leerNumero()
                else -> fallo("no esperaba '$c'")
            }
        }
    }

    private fun literal(cual: String, valor: Any?): Any? {
        if (!texto.startsWith(cual, i)) fallo("esperaba '$cual'")
        i += cual.length
        return valor
    }

    private fun leerObjeto(): Map<String, Any?> {
        i++ // '{'
        val salida = LinkedHashMap<String, Any?>()
        blancos()
        if (i < texto.length && texto[i] == '}') {
            i++
            return salida
        }
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
                '}' -> {
                    i++
                    return salida
                }
                else -> fallo("esperaba ',' o '}'")
            }
        }
    }

    private fun leerLista(): List<Any?> {
        i++ // '['
        val salida = ArrayList<Any?>()
        blancos()
        if (i < texto.length && texto[i] == ']') {
            i++
            return salida
        }
        while (true) {
            salida.add(leerValor())
            blancos()
            if (i >= texto.length) fallo("la lista no se cierra")
            when (texto[i]) {
                ',' -> i++
                ']' -> {
                    i++
                    return salida
                }
                else -> fallo("esperaba ',' o ']'")
            }
        }
    }

    private fun leerCadena(): String {
        i++ // '"'
        val salida = StringBuilder()
        while (true) {
            if (i >= texto.length) fallo("la cadena no se cierra")
            when (val c = texto[i]) {
                '"' -> {
                    i++
                    return salida.toString()
                }
                '\\' -> {
                    i++
                    if (i >= texto.length) fallo("escape a medias")
                    when (val e = texto[i]) {
                        '"' -> salida.append('"')
                        '\\' -> salida.append('\\')
                        '/' -> salida.append('/')
                        'b' -> salida.append('\b')
                        'f' -> salida.append('\u000c')
                        'n' -> salida.append('\n')
                        'r' -> salida.append('\r')
                        't' -> salida.append('\t')
                        'u' -> {
                            if (i + 5 > texto.length) fallo("escape \\u a medias")
                            val cifras = texto.substring(i + 1, i + 5)
                            if (!cifras.all { it in "0123456789abcdefABCDEF" }) fallo("escape \\u$cifras")
                            salida.append(cifras.toInt(16).toChar())
                            i += 4
                        }
                        else -> fallo("escape '\\$e' no soportado")
                    }
                    i++
                }
                else -> {
                    // `strict=True`, que es lo de `json.loads`: un carácter de
                    // control sin escapar no es JSON.
                    if (c.code < 0x20) fallo("carácter de control U+${hex(c.code, 4).uppercase()} en una cadena")
                    salida.append(c)
                    i++
                }
            }
        }
    }

    /** Cuántas cifras seguidas hay desde [desde]. */
    private fun cifrasDesde(desde: Int): Int {
        var j = desde
        while (j < texto.length && cifra(texto[j])) j++
        return j - desde
    }

    /**
     * El `NUMBER_RE` de json/scanner.py:
     * `-?(?:0|[1-9][0-9]*)(\.[0-9]+)?([eE][-+]?[0-9]+)?`. Lo que no casa se
     * queda sin leer, y entonces sobra: `01`, `1.` o `1e` no son números.
     */
    private fun leerNumero(): Any {
        val inicio = i
        if (texto[i] == '-') i++
        if (i >= texto.length || !cifra(texto[i])) fallo("número a medias")
        i += if (texto[i] == '0') 1 else cifrasDesde(i)
        var decimal = false
        if (i + 1 < texto.length && texto[i] == '.' && cifra(texto[i + 1])) {
            decimal = true
            i += 1 + cifrasDesde(i + 1)
        }
        if (i < texto.length && (texto[i] == 'e' || texto[i] == 'E')) {
            val signo = if (i + 1 < texto.length && (texto[i + 1] == '+' || texto[i + 1] == '-')) 1 else 0
            val exponente = cifrasDesde(i + 1 + signo)
            if (exponente > 0) {
                decimal = true
                i += 1 + signo + exponente
            }
        }
        val crudo = texto.substring(inicio, i)
        if (decimal) return crudo.toDouble()
        // Python no tiene tope para los enteros: los que no caben en un Long
        // salen como BigInteger. Sí lo tiene para leerlos de un texto
        // (sys.int_info.default_max_str_digits): pasado ese, `ValueError`.
        if (crudo.trimStart('-').length > MAX_CIFRAS_PY) fallo("entero de más de $MAX_CIFRAS_PY cifras")
        return crudo.toLongOrNull() ?: java.math.BigInteger(crudo)
    }
}

/** `sys.int_info.default_max_str_digits`: Python no lee enteros más largos. */
private const val MAX_CIFRAS_PY = 4300
