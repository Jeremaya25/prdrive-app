package prdrive.engine

/**
 * Oraculo.kt — Leer `oraculo_rclone.json`, que es lo que dice rclone.
 *
 * Lo genera `rclone/go/oraculo` llamando al código del rclone que fija
 * `rclone/go/go.mod`, el mismo que va en el `.aar`:
 *
 *     cd rclone/go && go run ./oraculo ../../engine/src/test/resources/oraculo_rclone.json
 *
 * Es el `vectores.json` de lo que el motor toma de rclone y no de prdrive.
 */
object Oraculo {

    val raiz: Map<String, Any?> by lazy {
        val recurso = Oraculo::class.java.getResourceAsStream("/oraculo_rclone.json")
            ?: error("No hay oraculo_rclone.json en los recursos de test (ver la cabecera de Oraculo.kt).")
        @Suppress("UNCHECKED_CAST")
        leerJson(recurso.bufferedReader().use { it.readText() }) as Map<String, Any?>
    }

    /** Las opciones de rclone como las ve la app: `options/info` y los backends del `.aar`. */
    val opciones: Pasada.Opciones by lazy {
        val backends = Vectores.objetos(raiz, "backends").map { b ->
            b["Prefix"].toString() to Vectores.cadenas(b, "Options")
        }
        Pasada.Opciones.deOptionsInfo(Vectores.objeto(raiz, "options_info"), backends)
    }
}
