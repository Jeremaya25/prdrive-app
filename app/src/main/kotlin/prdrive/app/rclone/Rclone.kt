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
     *
     * El nivel de log se pone aquí, global, en INFO: es el `--verbose` de
     * `BASE_FLAGS`, y tiene que ser el global porque `fs.Infof` no mira el de la
     * llamada (fs/log.go, `LogLevelPrintf`). Sin él, una pasada buena no deja
     * nada en su log.
     */
    fun iniciar(conf: File, trabajo: File, temporal: File) {
        trabajo.mkdirs()
        val error = Biblioteca.iniciar(conf.absolutePath, trabajo.absolutePath, temporal.absolutePath)
        if (error.isNotEmpty()) throw RcloneError(0, error)
        rpc("options/set", mapOf("main" to mapOf("LogLevel" to "INFO")))
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
        if (resultado.estado != 200L) {
            throw RcloneError(resultado.estado, salida?.get("error")?.toString() ?: resultado.salida, salida.orEmpty())
        }
        // Un «bien» que no se entiende no es un bien: se diría que la llamada
        // fue y lo que se leyera de ella sería inventado.
        return salida ?: throw RcloneError(resultado.estado, "rclone ha contestado algo que no se entiende: ${resultado.salida}")
    }

    /** Lo que deja un trabajo largo al acabar: lo que devolvió y, si falló, por qué. */
    data class Final(
        val ok: Boolean,
        val error: String,
        val salida: Map<String, Any?>,
    )

    /**
     * Ejecuta un método como trabajo (`_async`) y espera a que acabe, contando
     * el progreso por el camino. Bloquea: se llama desde un hilo de fondo.
     *
     * Es la forma de pedir una pasada: la llamada síncrona, si el método falla,
     * devuelve solo el error, y el resultado de un trabajo (`job/status`)
     * conserva su salida —en `sync/bisync`, el log de la pasada— también cuando
     * falla. El progreso sale de `core/stats` con el grupo del trabajo
     * (`job/<id>`, fs/accounting/stats_groups.go): datos, no líneas de log.
     *
     * Si el hilo se interrumpe, se para el trabajo (`job/stop`) y se sigue
     * esperando a que rclone lo dé por acabado, para que no quede una pasada
     * huérfana con el lock de bisync puesto. Lo mismo si se sale del bucle por
     * cualquier otra cosa antes de que acabe. rclone olvida un trabajo acabado a
     * los 60 s (fs/rc/jobs/job.go, `JobExpireDuration`): si para entonces nadie
     * lo ha mirado, eso es lo que se dice.
     *
     * Bloquea: nunca desde el hilo principal.
     */
    fun trabajo(
        metodo: String,
        parametros: Map<String, Any?>,
        cadaMs: Long = 500,
        alProgreso: (Map<String, Any?>) -> Unit = {},
    ): Final {
        val inicio = rpc(metodo, parametros + ("_async" to true))
        val id = (inicio["jobid"] as? Number)?.toLong()
            ?: throw RcloneError(0, "rclone no ha devuelto el número del trabajo: $inicio")
        var acabado = false
        var parado = false
        try {
            while (true) {
                val estado = try {
                    rpc("job/status", mapOf("jobid" to id))
                } catch (e: RcloneError) {
                    if ("job not found" in (e.message ?: "")) {
                        acabado = true
                        throw RcloneError(e.estado, "rclone ya no recuerda la pasada (más de un minuto sin mirarla)")
                    }
                    throw e
                }
                val stats = runCatching { rpc("core/stats", mapOf("group" to "job/$id")) }.getOrNull()
                // Que el progreso falle no es que falle la pasada.
                stats?.let { runCatching { alProgreso(it) } }
                if (estado["finished"] == true) {
                    acabado = true
                    @Suppress("UNCHECKED_CAST")
                    val salida = estado["output"] as? Map<String, Any?> ?: emptyMap()
                    return Final(estado["success"] == true, estado["error"]?.toString() ?: "", salida)
                }
                try {
                    Thread.sleep(cadaMs)
                } catch (_: InterruptedException) {
                    if (!parado) {
                        runCatching { rpc("job/stop", mapOf("jobid" to id)) }
                        parado = true
                    }
                }
            }
        } finally {
            if (!acabado && !parado) runCatching { rpc("job/stop", mapOf("jobid" to id)) }
        }
    }

    /** Empieza a guardar el log de rclone (para las llamadas que no lo devuelven). */
    fun capturar() = Biblioteca.capturar()

    /** Deja de guardar el log y devuelve lo guardado. */
    fun soltarCaptura(): String = Biblioteca.soltarCaptura()
}
