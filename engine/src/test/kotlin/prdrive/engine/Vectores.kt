package prdrive.engine

/**
 * Vectores.kt — Leer `vectores.json`, que es lo que dice prdrive.
 *
 * El acceso a los vectores, con [Vectores.cargar] y unos ayudantes que evitan
 * repetir el mismo `as` cincuenta veces. El JSON lo lee [leerJson], el mismo
 * lector con el que el motor lee lo que hay en `state/` (antes vivía aquí,
 * solo para los tests; ahora lo necesita el motor y se prueba también contra
 * el `json` de Python).
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
        return leerJson(texto) as Map<String, Any?>
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
