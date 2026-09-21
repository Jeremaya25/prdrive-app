package prdrive.engine

/**
 * Vectores.kt — Leer `vectores.json`, que es lo que dice prdrive.
 *
 * El acceso a los vectores, con unos ayudantes que evitan repetir el mismo
 * `as` cincuenta veces. El JSON lo lee [leerJson], que vive en el motor
 * porque también hace falta para hablar con el RPC de rclone: **un solo lector
 * de JSON en el proyecto**, por la misma razón por la que en prdrive hay un
 * solo lector de rclone.conf.
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
        return leerObjetoJson(recurso.bufferedReader().use { it.readText() })
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
