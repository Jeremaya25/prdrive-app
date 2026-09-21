package prdrive.engine

/**
 * Sesiones.kt — Leer `sesiones.json`, que es lo que dice **rclone**.
 *
 * El hermano de [Vectores], y la diferencia importa: `vectores.json` lo genera
 * `herramientas/vectores.py` desde el Python de prdrive, y `sesiones.json` lo
 * genera `rclone/spike` ejecutando rclone como biblioteca. Uno comprueba que
 * la traducción es fiel, el otro que el original acertaba.
 *
 * El JSON lo lee [leerJson], el mismo del motor.
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

    /**
     * Los parámetros que rclone **declara** para `sync/bisync`, con su tipo,
     * sacados de la ayuda que registra con el método. Es contra esto contra lo
     * que se comprueba la tabla de [Pasada], no contra lo que leyó quien la
     * escribió.
     */
    val parametrosBisync: List<Map<String, Any?>> by lazy { objetos("parametros_bisync") }

    /**
     * Los nombres que rclone acepta sueltos en el primer nivel de una llamada:
     * las etiquetas `config:` de `fs.ConfigInfo` y `filter.Options`. Un flag
     * suelto que no esté aquí lo descarta rclone sin decir nada.
     */
    val opcionesSueltas: List<String> by lazy {
        @Suppress("UNCHECKED_CAST")
        (raiz["opciones_sueltas"] as? List<Any?>
            ?: error("sesiones.json: falta 'opciones_sueltas'")).map { it.toString() }
    }

    private fun cargar(): Map<String, Any?> {
        val recurso = Sesiones::class.java.getResourceAsStream("/sesiones.json")
            ?: error(
                "No hay sesiones.json en los recursos de test. Se genera con:\n" +
                    "    cd rclone && go run ./spike -json ../engine/src/test/resources/sesiones.json",
            )
        return leerObjetoJson(recurso.bufferedReader().use { it.readText() })
    }

    @Suppress("UNCHECKED_CAST")
    private fun objetos(clave: String): List<Map<String, Any?>> =
        (raiz[clave] as? List<Any?> ?: error("sesiones.json: '$clave' no es una lista (o no está)"))
            .map { it as Map<String, Any?> }

    fun texto(donde: Map<String, Any?>, clave: String): String =
        donde[clave]?.toString() ?: error("sesiones.json: falta '$clave'")
}
