package prdrive.engine

/**
 * Vectores.kt — Leer `vectores.json`, que es lo que dice prdrive.
 *
 * Dos cosas viven aquí, las dos solo para los tests.
 *
 * **Un lector de JSON mínimo.** El proyecto no admite dependencias, ni siquiera
 * de test, y lo único que hay que leer es un fichero que genera
 * `herramientas/vectores.py`: objetos, arrays, cadenas, números, booleanos y
 * null. No es un JSON de propósito general y no tiene que serlo — si se
 * equivoca, los tests fallan a gritos, que es exactamente donde se quiere que
 * falle algo así. Nunca se compila dentro de la app.
 *
 * **El acceso a los vectores**, con [Vectores.cargar] y unos ayudantes que
 * evitan repetir el mismo `as` cincuenta veces.
 *
 * Los tests NO llevan ni un valor esperado escrito a mano: todos salen de ese
 * fichero, generado desde el prdrive de verdad. Es lo único que impide que las
 * constantes copiadas —las capas de flags, el `upstreams`, el nombre de sesión
 * de bisync— se separen de su original sin que nadie se entere.
 */
object Vectores {

    /** Lo que dice el JSON, entero. */
    val raiz: Map<String, Any?> by lazy { cargar() }

    /** La versión de prdrive de la que salieron estos vectores. */
    val prdrive: Map<String, Any?> by lazy { objeto(raiz, "prdrive") }

    private fun cargar(): Map<String, Any?> {
        val recurso = Vectores::class.java.getResourceAsStream("/vectores.json")
            ?: error(
                "No hay vectores.json en los recursos de test. Se genera con:\n" +
                    "    python herramientas/vectores.py <ruta al checkout de prdrive>",
            )
        val texto = recurso.bufferedReader().use { it.readText() }
        @Suppress("UNCHECKED_CAST")
        return Json(texto).leerDocumento() as Map<String, Any?>
    }

    // --- ayudantes de acceso ------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun objeto(donde: Map<String, Any?>, clave: String): Map<String, Any?> =
        donde[clave] as? Map<String, Any?>
            ?: error("vectores.json: '$clave' no es un objeto (o no está)")

    @Suppress("UNCHECKED_CAST")
    fun lista(donde: Map<String, Any?>, clave: String): List<Any?> =
        donde[clave] as? List<Any?>
            ?: error("vectores.json: '$clave' no es una lista (o no está)")

    @Suppress("UNCHECKED_CAST")
    fun objetos(donde: Map<String, Any?>, clave: String): List<Map<String, Any?>> =
        lista(donde, clave).map { it as Map<String, Any?> }

    fun cadenas(donde: Map<String, Any?>, clave: String): List<String> =
        lista(donde, clave).map { it.toString() }

    fun texto(donde: Map<String, Any?>, clave: String): String =
        donde[clave]?.toString() ?: error("vectores.json: falta '$clave'")

    /** Un apartado del JSON, por su nombre de primer nivel. */
    fun seccion(nombre: String): Map<String, Any?> = objeto(raiz, nombre)
}

/**
 * El lector de JSON. Recursivo descendente, sin estado global, sin sorpresas:
 * devuelve `Map`, `List`, `String`, `Long`, `Double`, `Boolean` y `null`.
 *
 * Los enteros salen como `Long` y los que llevan `.` o exponente como `Double`,
 * que es justo lo que devuelve [loadRaw] para el TOML: así un valor del JSON y
 * el mismo valor leído de un TOML se comparan sin convertir nada.
 */
internal class Json(private val texto: String) {
    private var i = 0

    fun leerDocumento(): Any? {
        val valor = leerValor()
        blancos()
        if (i < texto.length) fallo("sobra texto después del documento")
        return valor
    }

    private fun blancos() {
        while (i < texto.length && texto[i].isWhitespace()) i++
    }

    private fun fallo(que: String): Nothing =
        error("vectores.json no se puede leer en la posición $i: $que")

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
                        'b' -> salida.append('')
                        'f' -> salida.append('')
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
