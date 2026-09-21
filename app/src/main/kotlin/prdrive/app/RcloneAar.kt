package prdrive.app

import android.util.Log
import com.prdrive.rclone.prdrive.Prdrive
import prdrive.engine.Rclone
import prdrive.engine.RespuestaRpc
import java.io.File

/**
 * RcloneAar.kt — Las cuatro funciones del canal con rclone, sobre el `.aar`.
 *
 * Esto es TODO lo que la app añade a la ejecución de una pareja: el resto —qué
 * se le manda, en qué orden, qué se apunta— está en `:engine`, que se prueba
 * sin teléfono. Es la traducción literal de lo que expone
 * `rclone/gobind/prdrive.go`, y a propósito no hay nada más aquí: un envoltorio
 * que decidiera algo sería código que no se puede probar.
 *
 * Lo único que este fichero sí decide es **el orden de arranque**, porque
 * equivocarse no da error: ver [arrancar].
 */
class RcloneAar private constructor() : Rclone {

    override fun rpc(metodo: String, entrada: String): RespuestaRpc {
        val res = Prdrive.rcloneRPC(metodo, entrada)
        return RespuestaRpc(res.output, res.status.toInt())
    }

    override fun logReiniciar() = Prdrive.rcloneLogReiniciar()

    override fun logTexto(): String = Prdrive.rcloneLogTexto()

    override fun nivelDeLog(nombre: String) {
        // `RcloneLogNivel` devuelve error si el nombre no es de los de rclone,
        // y eso es un fallo de la app, no del config: el nivel lo calcula
        // `Pasada.nivelDeLog` a partir de los flags.
        runCatching { Prdrive.rcloneLogNivel(nombre) }
            .onFailure { Log.w(TAG, "nivel de log '$nombre' rechazado por rclone", it) }
    }

    companion object {
        private const val TAG = "prdrive.rclone"

        @Volatile
        private var arrancado = false

        /**
         * Arranca rclone y devuelve el canal. Se puede llamar más de una vez:
         * arranca una sola.
         *
         * **El orden es obligatorio y equivocarse NO da error.**
         * `librclone.Initialize()` llama a `configfile.Install()`, que es
         * `config.SetData()`, y esa función se va de vacío si el path está sin
         * fijar («If no config file, use in-memory config»,
         * `fs/config/config.go`). Al revés del orden bueno, rclone arranca con
         * una configuración vacía en memoria y el fallo sale mucho después,
         * diciendo «unknown remote». En Android además no hay HOME donde
         * buscar el sitio por defecto, así que sin la llamada no hay
         * configuración en absoluto.
         *
         * Fijarlo antes no impide reescribir el fichero después: `Storage`
         * compara la fecha y el tamaño en cada lectura y lo recarga solo, que
         * es justo lo que hace falta aquí — el `rclone.conf` de la app se
         * regenera en cada arranque (ver `Volumen`).
         */
        @Synchronized
        fun arrancar(rcloneConf: File): RcloneAar {
            if (!arrancado) {
                rcloneConf.parentFile?.mkdirs()
                if (!rcloneConf.exists()) rcloneConf.writeText("")
                Prdrive.rcloneSetConfigPath(rcloneConf.absolutePath)
                Prdrive.rcloneInitialize()
                val enUso = Prdrive.rcloneConfigPath()
                // La cadena vacía significa «configuración en memoria», o sea
                // que algo se llamó en el orden equivocado.
                check(enUso == rcloneConf.absolutePath) {
                    "rclone no está leyendo '$rcloneConf' sino '$enUso'"
                }
                Log.i(TAG, "rclone arrancado con $enUso")
                arrancado = true
            }
            return RcloneAar()
        }
    }
}
