package prdrive.engine

import java.math.BigDecimal

/**
 * Python.kt — Lo poco de la semántica de Python que hace falta para decir lo
 * mismo que prdrive.
 *
 * No refleja un fichero de prdrive sino el lenguaje en el que está escrito: sus
 * mensajes de error llevan `{valor!r}` dentro, sus condiciones preguntan «¿es
 * verdadero?» con las reglas de `bool()`, y su `_as_tuple()` convierte cada
 * elemento con `str()`. Los tests comparan esos mensajes y esos valores letra a
 * letra contra los vectores, así que las tres cosas se reproducen aquí, en un
 * solo sitio, en vez de aproximarlas en cada mensaje.
 *
 * Cubre los tipos que salen de un TOML (y de los vectores): `null`, `Boolean`,
 * `Long`/`Int`, `Double`, `String`, listas y mapas. Nada más.
 */

/** `bool(valor)` de Python: falso lo vacío, el cero, `None` y `False`. */
fun verdad(valor: Any?): Boolean = when (valor) {
    null -> false
    is Boolean -> valor
    is Number -> valor.toDouble() != 0.0
    is CharSequence -> valor.isNotEmpty()
    is Collection<*> -> valor.isNotEmpty()
    is Map<*, *> -> valor.isNotEmpty()
    else -> true
}

/**
 * Un número en hexadecimal con ceros delante. Sin `String.format`, que usa las
 * cifras del idioma del teléfono (en árabe saldrían `٠١`).
 */
internal fun hex(n: Int, cifras: Int): String = n.toString(16).padStart(cifras, '0')

/**
 * Lo que `str.isspace()` de Python da por espacio: más que `Char.isWhitespace`
 * de Kotlin, que no cuenta el NEL (U+0085).
 */
fun espacioPy(c: Char): Boolean =
    c in "\t\n\u000b\u000c\r\u001c\u001d\u001e\u001f \u0085      　" ||
        c in ' '..' '

/** `str.strip()` de Python. */
fun stripPy(texto: String): String = texto.trim { espacioPy(it) }

/** `str(valor)` de Python. */
fun strPy(valor: Any?): String = when (valor) {
    null -> "None"
    is Boolean -> if (valor) "True" else "False"
    is Double -> floatPy(valor)
    is Float -> floatPy(valor.toDouble())
    is String -> valor
    is Collection<*>, is Map<*, *> -> reprPy(valor)
    else -> valor.toString()
}

/** `repr(valor)` de Python. */
fun reprPy(valor: Any?): String = when (valor) {
    is String -> reprCadena(valor)
    is Collection<*> -> valor.joinToString(", ", "[", "]") { reprPy(it) }
    is Map<*, *> -> valor.entries.joinToString(", ", "{", "}") {
        "${reprPy(it.key)}: ${reprPy(it.value)}"
    }
    else -> strPy(valor)
}

/**
 * `repr()` de una cadena: comillas simples salvo que lleve una simple y ninguna
 * doble, y escapado lo que `str.isprintable()` no da por imprimible.
 */
private fun reprCadena(texto: String): String {
    val comilla = if ('\'' in texto && '"' !in texto) '"' else '\''
    val salida = StringBuilder().append(comilla)
    var i = 0
    while (i < texto.length) {
        val cp = texto.codePointAt(i)
        i += Character.charCount(cp)
        when {
            cp == '\\'.code -> salida.append("\\\\")
            cp == comilla.code -> salida.append('\\').append(comilla)
            cp == '\t'.code -> salida.append("\\t")
            cp == '\n'.code -> salida.append("\\n")
            cp == '\r'.code -> salida.append("\\r")
            !imprimible(cp) -> salida.append(
                when {
                    cp < 0x100 -> "\\x" + hex(cp, 2)
                    cp < 0x10000 -> "\\u" + hex(cp, 4)
                    else -> "\\U" + hex(cp, 8)
                },
            )
            else -> salida.appendCodePoint(cp)
        }
    }
    return salida.append(comilla).toString()
}

/**
 * `str.isprintable()` para un carácter: no lo es nada de las categorías
 * «Other» (control, formato, sustitutos, uso privado, sin asignar) ni de las
 * «Separator» salvo el espacio.
 */
private fun imprimible(cp: Int): Boolean {
    if (cp == ' '.code) return true
    return when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
        Character.UNASSIGNED, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
        -> false
        else -> true
    }
}

/**
 * `int(valor)` de Python, o `null` donde Python lanzaría (`TypeError`,
 * `ValueError`): un booleano es 0 o 1, un float se trunca y un texto se lee
 * entero, con espacios alrededor y `_` entre cifras.
 */
fun intPy(valor: Any?): Long? = when (valor) {
    is Boolean -> if (valor) 1L else 0L
    is Long -> valor
    is Int -> valor.toLong()
    is Double -> if (valor.isNaN() || valor.isInfinite()) null else valor.toLong()
    // Python admite las cifras de cualquier alfabeto (`\p{Nd}`), y `toLongOrNull` también.
    is String -> stripPy(valor).takeIf { Regex("[+-]?\\p{Nd}+(_\\p{Nd}+)*").matches(it) }
        ?.replace("_", "")?.toLongOrNull()
    else -> null
}

/**
 * `fnmatch.fnmatchcase(nombre, patron)`: `*`, `?`, `[…]` y `[!…]`, distinguiendo
 * mayúsculas. Un `[` sin cerrar es un corchete literal, como en Python.
 */
fun fnmatchcase(nombre: String, patron: String): Boolean = traducirFnmatch(patron).matches(nombre)

private val fnmatchCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

private fun traducirFnmatch(patron: String): Regex = fnmatchCache.getOrPut(patron) {
    val salida = StringBuilder()
    var i = 0
    while (i < patron.length) {
        val c = patron[i++]
        when (c) {
            '*' -> salida.append(".*")
            '?' -> salida.append('.')
            '[' -> {
                var j = i
                if (j < patron.length && patron[j] == '!') j++
                if (j < patron.length && patron[j] == ']') j++
                while (j < patron.length && patron[j] != ']') j++
                if (j >= patron.length) {
                    salida.append("\\[")
                } else {
                    var dentro = patron.substring(i, j).replace("\\", "\\\\")
                    i = j + 1
                    dentro = when {
                        dentro.startsWith("!") -> "^" + dentro.substring(1)
                        dentro.startsWith("^") -> "\\" + dentro
                        else -> dentro
                    }
                    salida.append('[').append(dentro.replace("[", "\\[")).append(']')
                }
            }
            else -> salida.append(Regex.escape(c.toString()))
        }
    }
    Regex(salida.toString(), RegexOption.DOT_MATCHES_ALL)
}

/**
 * `repr()` de un float: los dígitos mínimos que lo identifican, en notación
 * fija entre 1e-4 y 1e16 y científica fuera, con el exponente a dos cifras y con
 * signo (`1e+20`, `1.5e-07`).
 *
 * Los dígitos no se le piden a `Double.toString()`, que no da los mínimos en
 * todos los casos (`4.9E-324` donde Python dice `5e-324`) y en Android no es la
 * misma implementación que en la JVM: se redondea el valor exacto a 1, 2, 3…
 * cifras y se queda con el primero que vuelve a ser el mismo double, que es la
 * respuesta del algoritmo de Python (el más corto, y el más cercano entre los
 * cortos).
 */
fun floatPy(valor: Double): String {
    if (valor.isNaN()) return "nan"
    if (valor.isInfinite()) return if (valor > 0) "inf" else "-inf"
    if (valor == 0.0) return if (1.0 / valor < 0) "-0.0" else "0.0"
    val exacto = BigDecimal(valor)
    var decimal = exacto
    for (cifras in 1..17) {
        val redondeado = exacto.round(java.math.MathContext(cifras, java.math.RoundingMode.HALF_EVEN))
        if (redondeado.toDouble() == valor) {
            decimal = redondeado
            break
        }
    }
    decimal = decimal.stripTrailingZeros()
    val exponente = decimal.precision() - decimal.scale() - 1
    if (exponente in -4..15) {
        val plano = decimal.toPlainString()
        return if ('.' in plano) plano else "$plano.0"
    }
    val digitos = decimal.unscaledValue().abs().toString()
    val mantisa = if (digitos.length == 1) digitos else digitos[0] + "." + digitos.substring(1)
    val signo = if (valor < 0) "-" else ""
    val exp = (if (exponente < 0) "-" else "+") + kotlin.math.abs(exponente).toString().padStart(2, '0')
    return "${signo}${mantisa}e$exp"
}
