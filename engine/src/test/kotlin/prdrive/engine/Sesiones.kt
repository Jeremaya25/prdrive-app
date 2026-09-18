package prdrive.engine

/**
 * Sesiones.kt — Leer `sesiones.json`, que es lo que dice **rclone**.
 *
 * El hermano de [Vectores], y la diferencia importa: `vectores.json` lo genera
 * `herramientas/vectores.py` desde el Python de prdrive, y `sesiones.json` lo
 * genera `rclone/spike` ejecutando rclone como biblioteca. Uno comprueba que
 * la traducción es fiel, el otro que el original acertaba.
 *
 * Reutiliza el lector de JSON de [Vectores]: no hay dependencias, tampoco de
 * test.
 */
object Sesiones {

    /** Lo que dice el JSON, entero. */
    val raiz: Map<String, Any?> by lazy { cargar() }

    /** El `sync_config.toml` con el que el spike ejecutó las parejas. */
    val configToml: String by lazy { texto(raiz, "config_toml") }

    /** El `rclone.conf` que el spike le dio a rclone. */
    val rcloneConf: String by lazy { texto(raiz, "rclone_conf") }

    /** Solo la sección del `device_remote`, que es la que arma el motor. */
    val seccionDisp: String by lazy { texto(raiz, "seccion_disp") }

    /** La raíz del volumen de mentira, que es lo que la app sabría por `filesDir`. */
    val raizVolumen: String by lazy { texto(raiz, "raiz_volumen") }

    /** Una entrada por pareja sincronizada, con lo que rclone devolvió de ella. */
    val casos: List<Map<String, Any?>> by lazy { objetos("casos") }

    /** Los ficheros de filtros y el md5 que bisync escribió junto a cada uno. */
    val filtros: List<Map<String, Any?>> by lazy { objetos("filtros") }

    private fun cargar(): Map<String, Any?> {
        val recurso = Sesiones::class.java.getResourceAsStream("/sesiones.json")
            ?: error(
                "No hay sesiones.json en los recursos de test. Se genera con:\n" +
                    "    cd rclone && go run ./spike -json ../engine/src/test/resources/sesiones.json",
            )
        val texto = recurso.bufferedReader().use { it.readText() }
        @Suppress("UNCHECKED_CAST")
        return Json(texto).leerDocumento() as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun objetos(clave: String): List<Map<String, Any?>> =
        (raiz[clave] as? List<Any?> ?: error("sesiones.json: '$clave' no es una lista (o no está)"))
            .map { it as Map<String, Any?> }

    fun texto(donde: Map<String, Any?>, clave: String): String =
        donde[clave]?.toString() ?: error("sesiones.json: falta '$clave'")
}
