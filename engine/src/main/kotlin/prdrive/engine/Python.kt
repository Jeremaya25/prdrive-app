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
                    cp < 0x100 -> "\\x%02x".format(cp)
                    cp < 0x10000 -> "\\u%04x".format(cp)
                    else -> "\\U%08x".format(cp)
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
 * `repr()` de un float: los dígitos mínimos que lo identifican (los mismos que
 * da Java), en notación fija entre 1e-4 y 1e16 y científica fuera, con el
 * exponente a dos cifras y con signo (`1e+20`, `1.5e-07`).
 */
fun floatPy(valor: Double): String {
    if (valor.isNaN()) return "nan"
    if (valor.isInfinite()) return if (valor > 0) "inf" else "-inf"
    if (valor == 0.0) return if (1.0 / valor < 0) "-0.0" else "0.0"
    val decimal = BigDecimal(valor.toString()).stripTrailingZeros()
    val exponente = decimal.precision() - decimal.scale() - 1
    if (exponente in -4..15) {
        val plano = decimal.toPlainString()
        return if ('.' in plano) plano else "$plano.0"
    }
    val digitos = decimal.unscaledValue().abs().toString()
    val mantisa = if (digitos.length == 1) digitos else digitos[0] + "." + digitos.substring(1)
    val signo = if (valor < 0) "-" else ""
    val exp = (if (exponente < 0) "-" else "+") + "%02d".format(kotlin.math.abs(exponente))
    return "${signo}${mantisa}e$exp"
}
