package prdrive.engine

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

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

/**
 * Lo de dentro de la clase `\s` de una regex de Python (la de [espacioPy]),
 * para escribir `[…]` o `[^…]` en una de Java o de ICU, que no la tienen igual.
 */
internal val CLASE_ESPACIO_PY: String by lazy {
    (0..0xffff).filter { espacioPy(it.toChar()) }.joinToString("") { "\\x{${it.toString(16)}}" }
}

/** Lo que parte líneas en `str.splitlines()`, además de `\r\n` junto. */
private const val SALTOS_PY = "\n\r\u000b\u000c\u001c\u001d\u001e\u0085  "

/**
 * `str.splitlines()` de Python: corta en más sitios que `lines()` de Kotlin
 * (también `\u000b`, `\u000c`, `\u001c`-`\u001e`, `\u0085`, ` ` y ` `)
 * y no da una línea vacía al final si el texto acaba en salto.
 */
fun splitlinesPy(texto: String): List<String> {
    val salida = ArrayList<String>()
    var inicio = 0
    var i = 0
    while (i < texto.length) {
        val c = texto[i]
        if (c in SALTOS_PY) {
            salida.add(texto.substring(inicio, i))
            if (c == '\r' && i + 1 < texto.length && texto[i + 1] == '\n') i++
            inicio = i + 1
        }
        i++
    }
    if (inicio < texto.length) salida.add(texto.substring(inicio))
    return salida
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
 * entero, con espacios alrededor y `_` entre cifras. Sin tope, como en Python
 * (salvo el de cifras de un texto, `sys.int_info.default_max_str_digits`).
 */
fun enteroPy(valor: Any?): BigInteger? = when (valor) {
    is Boolean -> if (valor) BigInteger.ONE else BigInteger.ZERO
    is BigInteger -> valor
    is Long -> BigInteger.valueOf(valor)
    is Int -> BigInteger.valueOf(valor.toLong())
    is Double -> if (valor.isNaN() || valor.isInfinite()) null else BigDecimal(valor).toBigInteger()
    // Python admite las cifras de cualquier alfabeto (`\p{Nd}`), y BigInteger también.
    is String -> stripPy(valor).takeIf { ENTERO_PY.matches(it) }?.replace("_", "")
        ?.takeIf { it.trimStart('+', '-').length <= 4300 }?.let { BigInteger(it) }
    else -> null
}

private val ENTERO_PY = Regex("[+-]?\\p{Nd}+(_\\p{Nd}+)*")

/** [enteroPy] cuando hace falta un `Long`: `null` también si no cabe. */
fun intPy(valor: Any?): Long? = enteroPy(valor)?.takeIf { it.bitLength() < 64 }?.toLong()

/**
 * Cómo ordena Python dos cadenas: por punto de código. El `compareTo` de
 * Kotlin compara unidades UTF-16, y un carácter fuera del plano básico (un
 * emoji: dos unidades que empiezan por U+D8xx) quedaría delante de los de
 * ancho completo (U+FFxx), que son justo los que pone rclone en los nombres.
 */
fun compararPy(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a.codePointAt(i)
        val cb = b.codePointAt(j)
        if (ca != cb) return ca.compareTo(cb)
        i += Character.charCount(ca)
        j += Character.charCount(cb)
    }
    return (i < a.length).compareTo(j < b.length)
}

/** `sorted()` de Python para cadenas, como `Comparator`. */
val ORDEN_PY: Comparator<String> = Comparator(::compararPy)

/**
 * `fnmatch.fnmatchcase(nombre, patron)`: `*`, `?`, `[…]` y `[!…]`, distinguiendo
 * mayúsculas. Un `[` sin cerrar es un corchete literal, como en Python.
 */
fun fnmatchcase(nombre: String, patron: String): Boolean = traducirFnmatch(patron).matches(nombre)

private val fnmatchCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

/**
 * `fnmatch._translate` de Python 3.14, sobre puntos de código. Lo que sale es
 * una regex de Java, no de Python: los corchetes no se copian como texto (en
 * Java `&&` es una intersección y un `[` abre otra clase), sino que se
 * reconstruyen con cada carácter escrito como `\x{…}`. Los `*` seguidos de
 * Python acaban en grupos atómicos (`(?>.*?…)`), que para casar el nombre
 * entero dan lo mismo que `.*`.
 */
private fun traducirFnmatch(patron: String): Regex = fnmatchCache.getOrPut(patron) {
    val pat = patron.codePoints().toArray()
    val n = pat.size
    val salida = StringBuilder()
    var i = 0
    while (i < n) {
        val c = pat[i++]
        when (c) {
            '*'.code -> {
                salida.append(".*")
                while (i < n && pat[i] == '*'.code) i++
            }
            '?'.code -> salida.append('.')
            '['.code -> {
                var j = i
                if (j < n && pat[j] == '!'.code) j++
                if (j < n && pat[j] == ']'.code) j++
                while (j < n && pat[j] != ']'.code) j++
                if (j >= n) {
                    salida.append("\\[")
                } else {
                    salida.append(claseFnmatch(pat, i, j))
                    i = j + 1
                }
            }
            else -> salida.append(literalRegex(c))
        }
    }
    Regex(salida.toString(), RegexOption.DOT_MATCHES_ALL)
}

/**
 * Lo de dentro de unos corchetes (`pat[i:j]`), como lo trocea Python: los
 * guiones que hacen rango parten el texto en trozos, y entre el último
 * carácter de un trozo y el primero del siguiente hay un rango. Un rango al
 * revés (`z-a`) se quita, y un guion al final es un guion.
 */
private fun claseFnmatch(pat: IntArray, desde: Int, hasta: Int): String {
    val guion = '-'.code
    val trozos = ArrayList<MutableList<Int>>()
    if ((desde until hasta).none { pat[it] == guion }) {
        trozos.add(pat.copyOfRange(desde, hasta).toMutableList())
    } else {
        var i = desde
        var k = if (pat[i] == '!'.code) i + 2 else i + 1
        while (true) {
            k = (k until hasta).firstOrNull { pat[it] == guion } ?: break
            trozos.add(pat.copyOfRange(i, k).toMutableList())
            i = k + 1
            k += 3
        }
        val ultimo = pat.copyOfRange(i, hasta).toMutableList()
        if (ultimo.isNotEmpty()) trozos.add(ultimo) else trozos.last().add(guion)
        // Los rangos vacíos fuera: en una regex no son válidos.
        for (t in trozos.size - 1 downTo 1) {
            if (trozos[t - 1].last() > trozos[t][0]) {
                trozos[t - 1] = (trozos[t - 1].dropLast(1) + trozos[t].drop(1)).toMutableList()
                trozos.removeAt(t)
            }
        }
    }
    if (trozos.all { it.isEmpty() }) return "(?!)" // vacío: no casa nada
    val negada = trozos[0].firstOrNull() == '!'.code
    if (negada) trozos[0].removeAt(0)
    if (negada && trozos.all { it.isEmpty() }) return "." // `[!]` vacío: cualquier carácter
    val clase = StringBuilder(if (negada) "[^" else "[")
    trozos.forEachIndexed { t, trozo ->
        if (t > 0) clase.append('-')
        trozo.forEach { clase.append(literalClase(it)) }
    }
    return clase.append(']').toString()
}

/** Un carácter literal fuera de corchetes. */
private fun literalRegex(cp: Int): String = Regex.escape(String(Character.toChars(cp)))

/** Un carácter literal dentro de corchetes, sin nada que Java pueda leer como sintaxis. */
private fun literalClase(cp: Int): String =
    if (cp < 0x80 && Character.isLetterOrDigit(cp)) cp.toChar().toString() else "\\x{${cp.toString(16)}}"

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
 *
 * Con cada número de cifras se prueban los dos vecinos, no solo el redondeado:
 * en una potencia de dos el intervalo de abajo es la mitad de estrecho, y el
 * más cercano puede quedarse fuera mientras el otro vuelve bien (2^-24 es
 * `5.960464477539063e-08`, no `5.9604644775390625e-08`).
 */
fun floatPy(valor: Double): String {
    if (valor.isNaN()) return "nan"
    if (valor.isInfinite()) return if (valor > 0) "inf" else "-inf"
    if (valor == 0.0) return if (1.0 / valor < 0) "-0.0" else "0.0"
    val exacto = BigDecimal(valor)
    var decimal = exacto
    for (cifras in 1..17) {
        val candidatos = listOf(RoundingMode.HALF_EVEN, RoundingMode.FLOOR, RoundingMode.CEILING)
            .map { exacto.round(MathContext(cifras, it)) }
            .filter { it.toDouble() == valor }
        if (candidatos.isNotEmpty()) {
            // El más cercano; a igual distancia, el de HALF_EVEN, que va primero.
            decimal = candidatos.minWith(compareBy { (it - exacto).abs() })
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
