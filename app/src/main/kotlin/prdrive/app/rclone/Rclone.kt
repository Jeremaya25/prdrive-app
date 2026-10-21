package prdrive.app.rclone

import java.io.File
import prdrive.engine.escribirJson
import prdrive.engine.leerJson
import prdrive.lib.rclone.Rclone as Biblioteca

/**
 * Rclone.kt — rclone como biblioteca: el envoltorio del RPC.
 *
 * Todo lo que la app le pide a rclone pasa por aquí, como `{método, JSON}` →
 * JSON, igual que se le hablaría al servidor rc (`rclone rcd`). No hay línea de
 * órdenes: `core/command` no sirve desde la biblioteca (lanzaría un proceso
 * hijo), y la ruta buena es el método de cada cosa (`sync/bisync`,
 * `operations/stat`…). Ver PLAN.md.
 *
 * Los JSON los escribe y los lee el motor ([escribirJson], [leerJson]), con los
 * mismos tipos que el TOML: enteros `Long`, mapas, listas.
 */
object Rclone {

    /** rclone ha contestado con un error: [estado] es el código HTTP del servidor rc. */
    class RcloneError(
        val estado: Long,
        mensaje: String,
        val salida: Map<String, Any?> = emptyMap(),
    ) : Exception(mensaje)

    /**
     * Deja rclone listo, con su `rclone.conf`, el directorio contra el que
     * resuelve las rutas relativas de ese fichero (`keys/…`) y una carpeta
     * temporal en la que se puede escribir. Solo cuenta la primera llamada del
     * proceso: rclone no se reinicia.
     */
    fun iniciar(conf: File, trabajo: File, temporal: File) {
        val error = Biblioteca.iniciar(conf.absolutePath, trabajo.absolutePath, temporal.absolutePath)
        if (error.isNotEmpty()) throw RcloneError(0, error)
    }

    /** La versión de rclone que va dentro. */
    fun version(): String = Biblioteca.version()

    /**
     * Llama a un método rc y devuelve su salida. [RcloneError] si rclone
     * contesta con un error, con el mensaje de rclone dentro.
     */
    fun rpc(metodo: String, parametros: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val resultado = Biblioteca.rpc(metodo, escribirJson(parametros))
        @Suppress("UNCHECKED_CAST")
        val salida = runCatching { leerJson(resultado.salida) as? Map<String, Any?> }.getOrNull()
            ?: mapOf("error" to resultado.salida)
        if (resultado.estado != 200L) {
            throw RcloneError(resultado.estado, salida["error"]?.toString() ?: resultado.salida, salida)
        }
        return salida
    }

    /** Lo que deja un trabajo largo al acabar: lo que devolvió y, si falló, por qué. */
    data class Final(
        val ok: Boolean,
        val error: String,
        val salida: Map<String, Any?>,
    )

    /**
     * Ejecuta un método como trabajo (`_async`) y espera a que acabe, contando
     * el progreso por el camino.
     *
     * Es la forma de pedir una pasada: la llamada síncrona, si el método falla,
     * devuelve solo el error, y el resultado de un trabajo (`job/status`)
     * conserva su salida —en `sync/bisync`, el log de la pasada— también cuando
     * falla. El progreso sale de `core/stats` con el grupo del trabajo
     * (`job/<id>`, fs/accounting/stats_groups.go): datos, no líneas de log.
     */
    fun trabajo(
        metodo: String,
        parametros: Map<String, Any?>,
        cadaMs: Long = 500,
        alProgreso: (Map<String, Any?>) -> Unit = {},
    ): Final {
        val inicio = rpc(metodo, parametros + ("_async" to true))
        val id = (inicio["jobid"] as Number).toLong()
        while (true) {
            val estado = rpc("job/status", mapOf("jobid" to id))
            if (estado["finished"] == true) {
                @Suppress("UNCHECKED_CAST")
                val salida = estado["output"] as? Map<String, Any?> ?: emptyMap()
                return Final(estado["success"] == true, estado["error"]?.toString() ?: "", salida)
            }
            runCatching { rpc("core/stats", mapOf("group" to "job/$id")) }.getOrNull()?.let(alProgreso)
            Thread.sleep(cadaMs)
        }
    }
}
