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
 * `operations/stat`…). Ver AGENTS.md, «Arquitectura».
 *
 * Los JSON los escribe y los lee el motor ([escribirJson], [leerJson]), con los
 * mismos tipos que el TOML: enteros `Long`, mapas, listas.
 */
object Rclone {

    /** La primera espera de [trabajo]; después se dobla hasta su `cadaMs`. */
    private const val PRIMERA_ESPERA_MS = 10L

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
     * Si el hilo se interrumpe, se para el trabajo (`job/stop`), se espera a
     * que rclone lo dé por acabado —que no quede una pasada huérfana con el
     * lock de bisync puesto, ni otra que empiece encima— y se lanza
     * [Cancelada], con la interrupción repuesta: una pasada
     * cancelada no es una pasada fallida, como un Ctrl+C en el escritorio. Si se
     * sale por cualquier otro error antes de que acabe, igual: parar y esperar.
     * rclone olvida un trabajo acabado a los 60 s (fs/rc/jobs/job.go,
     * `JobExpireDuration`): si para entonces nadie lo ha mirado, eso es lo que
     * se dice.
     *
     * Se mira enseguida y cada vez más espaciado, hasta [cadaMs]: una pasada
     * sin cambios acaba en unos milisegundos, y esperar medio segundo antes de
     * la primera mirada era lo que más tardaba (en la app y en los tests).
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
        var esperaMs = minOf(PRIMERA_ESPERA_MS, cadaMs)
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
                    return finalDe(estado)
                }
                try {
                    Thread.sleep(esperaMs)
                    esperaMs = minOf(esperaMs * 2, cadaMs)
                } catch (_: InterruptedException) {
                    val (parada, final) = pararYEsperar(id)
                    acabado = true
                    Thread.currentThread().interrupt()
                    throw Cancelada(parada, final)
                }
            }
        } finally {
            if (!acabado) pararYEsperar(id)
        }
    }

    /**
     * Un trabajo cancelado. [parada] dice si hubo que pararlo con `job/stop`
     * (si no, ya había acabado solo), y [final] es lo que rclone dijo al acabar,
     * o `null` si no llegó a decirlo.
     *
     * Con [parada], un [final] bueno no es de fiar: `sync` se traga el
     * `context.Canceled` de un `job/stop` si en ese momento no había una
     * transferencia en vuelo (fs/sync/sync.go, `processError`: lo da por un
     * corte suyo, porque su `inCtx` cuelga del contexto que se ha cancelado), y
     * devuelve que ha ido bien sin haber copiado lo que le quedaba.
     */
    class Cancelada(val parada: Boolean, val final: Final?) :
        java.util.concurrent.CancellationException("La pasada se ha cancelado.")

    @Suppress("UNCHECKED_CAST")
    private fun finalDe(estado: Map<String, Any?>) = Final(
        estado["success"] == true,
        estado["error"]?.toString() ?: "",
        estado["output"] as? Map<String, Any?> ?: emptyMap(),
    )

    /**
     * Para un trabajo y espera a que rclone lo dé por acabado (o lo haya
     * olvidado), sin dejarse interrumpir mientras: lo que se espera es
     * precisamente que suelte lo que tiene. Si tarda más de [maxMs], se deja.
     * Una interrupción que llegue entretanto se repone al salir.
     *
     * Devuelve si ha hecho falta pararlo (si ya había acabado, no se para) y lo
     * que dijo al acabar, si se sabe.
     */
    private fun pararYEsperar(id: Long, maxMs: Long = 30_000): Pair<Boolean, Final?> {
        val antes = runCatching { rpc("job/status", mapOf("jobid" to id)) }.getOrNull()
        if (antes?.get("finished") == true) return false to finalDe(antes)
        runCatching { rpc("job/stop", mapOf("jobid" to id)) }
        var interrumpido = false
        var final: Final? = null
        val hasta = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < hasta) {
            val estado = runCatching { rpc("job/status", mapOf("jobid" to id)) }.getOrNull() ?: break
            if (estado["finished"] == true) {
                final = finalDe(estado)
                break
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                interrumpido = true
            }
        }
        if (interrumpido) Thread.currentThread().interrupt()
        return true to final
    }

    /** Empieza a guardar el log de rclone (para las llamadas que no lo devuelven). */
    fun capturar() = Biblioteca.capturar()

    /** Deja de guardar el log y devuelve lo guardado. */
    fun soltarCaptura(): String = Biblioteca.soltarCaptura()
}
