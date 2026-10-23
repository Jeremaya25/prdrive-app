package prdrive.app.rclone

import java.io.File
import prdrive.engine.Catalogo
import prdrive.engine.Pasada
import prdrive.engine.escribirJson

/**
 * Adaptadores.kt — Lo que el motor le pide a rclone, hecho con la biblioteca.
 *
 * El motor habla con rclone por dos interfaces, para poder probarse sin él: la
 * pasada ([Pasada.Rclone]: una llamada larga con su log) y el catálogo
 * ([Catalogo.Ejecutar]: `cat` y `lsjson --stat`, como `catalog.py` se los pide a
 * la línea de órdenes). Aquí se cumplen con el RPC.
 */
object Adaptadores {

    /**
     * Las pasadas, de una en una. La captura del log de rclone es del proceso
     * entero (`bilib.CaptureOutput`, y la nuestra de [Rclone.capturar]): dos
     * pasadas a la vez se robarían el log. Es la misma regla que `sync.py`, que
     * ejecuta las parejas una detrás de otra.
     */
    private val unaPasada = Any()

    /** La pasada de una pareja ([Pasada.Rclone]) contra la biblioteca. */
    val pasada: Pasada.Rclone = object : Pasada.Rclone {
        override fun ejecutar(llamada: Pasada.Llamada, alProgreso: (Map<String, Any?>) -> Unit): Pasada.Final =
            synchronized(unaPasada) {
                // `sync/bisync` devuelve su propio log (`output`); `sync/copy` y
                // `sync/sync` no devuelven nada, así que se captura.
                val bisync = llamada.metodo == "sync/bisync"
                var capturado = ""
                if (!bisync) Rclone.capturar()
                val final = try {
                    Rclone.trabajo(llamada.metodo, llamada.parametros, alProgreso = alProgreso)
                } catch (e: Rclone.RcloneError) {
                    Rclone.Final(false, e.message ?: "", e.salida)
                } finally {
                    if (!bisync) capturado = Rclone.soltarCaptura()
                }
                val log = if (bisync) final.salida["output"]?.toString() ?: "" else capturado
                Pasada.Final(final.ok, final.error, log, final.salida)
            }

        // El `rclone mkdir` de la carpeta del llavero antes de su --resync.
        override fun mkdir(destino: String): Pasada.Final =
            synchronized(unaPasada) {
                try {
                    Rclone.rpc("operations/mkdir", mapOf("fs" to destino, "remote" to ""))
                    Pasada.Final(true, "", "")
                } catch (e: Rclone.RcloneError) {
                    Pasada.Final(false, e.message ?: "", "")
                }
            }
    }

    /**
     * Las opciones de rclone, para traducir las órdenes ([Pasada.llamadaDe]):
     * las globales (`options/info`, todos los bloques) y los flags de los
     * backends que lleva el `.aar` (`config/providers`), que el escritorio
     * acepta y aquí se explican.
     */
    val opciones: Pasada.Opciones by lazy {
        Pasada.Opciones.deOptionsInfo(
            Rclone.rpc("options/info"),
            Pasada.Opciones.backendsDeProviders(Rclone.rpc("config/providers")),
        )
    }

    /**
     * Las opciones de red de `catalog.NET_FLAGS` (`--contimeout 10s --timeout
     * 20s --retries 1 --low-level-retries 2`), como parámetros planos de la
     * llamada: un remoto muerto no puede congelar la pantalla.
     */
    private val red: Map<String, Any?> by lazy {
        val salida = LinkedHashMap<String, Any?>()
        val flags = Catalogo.NET_FLAGS
        var i = 0
        while (i + 1 < flags.size) {
            salida[flags[i].removePrefix("--").replace("-", "_")] = flags[i + 1]
            i += 2
        }
        salida
    }

    /**
     * `cat` y `lsjson --stat` del catálogo ([Catalogo.Ejecutar]) con el RPC, que
     * no tiene `cat`: un fichero se copia a [temporal] y se lee; una carpeta se
     * lista y se concatena, como hace `rclone cat`. Los códigos son los de la
     * línea de órdenes: 0 bien, 3 no existe, 1 cualquier otro fallo (sin red,
     * por ejemplo), que es lo que `catalog.py` distingue.
     *
     * Va con el mismo cerrojo que las pasadas: rclone apunta en su log los
     * errores de cualquier llamada (librclone los escribe en ERROR), y un
     * «no such host» del catálogo en mitad del log de una pasada haría que
     * esta se explicara como un problema de red.
     */
    fun catalogo(temporal: File): Catalogo.Ejecutar = Catalogo.Ejecutar { args ->
        synchronized(unaPasada) {
            try {
                when {
                    args.size == 2 && args[0] == "cat" -> cat(args[1], temporal)
                    args.size == 3 && args[0] == "lsjson" && args[1] == "--stat" -> stat(args[2])
                    else -> Catalogo.Resultado(1, "", "orden no soportada: $args")
                }
            } catch (e: Rclone.RcloneError) {
                val mensaje = e.message ?: ""
                val noExiste = "not found" in mensaje || "doesn't exist" in mensaje
                Catalogo.Resultado(if (noExiste) 3 else 1, "", mensaje)
            }
        }
    }

    private fun partir(donde: String): Pair<String, String> {
        val (carpeta, nombre) = Catalogo.partir(donde)
        return carpeta to nombre
    }

    private fun item(donde: String): Map<*, *>? {
        val (carpeta, nombre) = partir(donde)
        return Rclone.rpc("operations/stat", red + mapOf("fs" to carpeta, "remote" to nombre))["item"] as? Map<*, *>
    }

    private fun stat(donde: String): Catalogo.Resultado {
        val item = item(donde) ?: return Catalogo.Resultado(3, "", "error: object not found")
        return Catalogo.Resultado(0, escribirJson(item), "")
    }

    private fun cat(donde: String, temporal: File): Catalogo.Resultado {
        val item = item(donde) ?: return Catalogo.Resultado(3, "", "error: directory not found")
        if (item["IsDir"] != true) return Catalogo.Resultado(0, bajar(donde, temporal), "")
        // `rclone cat` de una carpeta concatena todos los ficheros de dentro.
        val lista = Rclone.rpc(
            "operations/list",
            red + mapOf("fs" to donde.trimEnd('/') + "/", "remote" to "", "opt" to mapOf("recurse" to true, "filesOnly" to true)),
        )["list"] as? List<*> ?: emptyList<Any?>()
        val texto = StringBuilder()
        for (f in lista.filterIsInstance<Map<*, *>>()) {
            texto.append(bajar(donde.trimEnd('/') + "/" + f["Path"], temporal))
        }
        return Catalogo.Resultado(0, texto.toString(), "")
    }

    private fun bajar(donde: String, temporal: File): String {
        val (carpeta, nombre) = partir(donde)
        temporal.mkdirs()
        val destino = File.createTempFile("catalogo", ".toml", temporal)
        try {
            Rclone.rpc(
                "operations/copyfile",
                red + mapOf(
                    "srcFs" to carpeta, "srcRemote" to nombre,
                    "dstFs" to destino.parentFile!!.absolutePath, "dstRemote" to destino.name,
                ),
            )
            return destino.readText(Charsets.UTF_8)
        } finally {
            destino.delete()
        }
    }
}
