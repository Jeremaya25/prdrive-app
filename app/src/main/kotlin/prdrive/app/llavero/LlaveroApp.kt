package prdrive.app.llavero

import android.content.Context
import java.io.IOException
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import prdrive.app.Prdrive
import prdrive.app.rclone.Adaptadores
import prdrive.engine.Bisync
import prdrive.engine.ConfigError
import prdrive.engine.LLAVERO
import prdrive.engine.Llavero
import prdrive.engine.Pareja
import prdrive.engine.Pasada
import prdrive.engine.Resultados
import prdrive.engine.TomlError
import prdrive.engine.Volumen

/**
 * LlaveroApp.kt — La pasada del llavero en la app: la que lanza el trabajo de
 * fondo ([TrabajoLlavero]) y, más adelante, «Abrir llavero».
 *
 * La pasada es la del motor ([Pasada.pasarOFallar]) con la pareja del llavero;
 * aquí está lo que es del teléfono: el volumen, rclone, que Android cuenta como
 * cifrado y que no haya dos a la vez.
 *
 * Todo bloquea: se llama fuera del hilo de la pantalla.
 */
object LlaveroApp {

    /**
     * Cuánto vale una pasada buena para no lanzar otra al abrir la base
     * (`keepassxc.DESDE_LA_ULTIMA`, 120 s).
     */
    val DESDE_LA_ULTIMA: Duration = Duration.ofMinutes(2)

    /**
     * Una pasada del llavero a la vez. El cerrojo de las llamadas a rclone
     * (`unaPasada`, [Adaptadores]) no basta: una pasada del llavero son varias
     * llamadas (el `mkdir` del `--resync`, la repetición sin freno, el
     * reapunte), y dos bisync de la misma pareja intercaladas se encontrarían
     * el lock de la otra puesto. La espera bloquea.
     */
    private val cerrojo = ReentrantLock()

    /**
     * Ejecuta [bloque] sin ninguna pasada del llavero en marcha, y sin que
     * empiece otra mientras. Lo usa [pasar], y lo que cambia el llavero con la
     * app delante (activarlo o quitarlo reescribe el `rclone.conf`) lo envuelve
     * en él. Es reentrante; la espera se corta si se interrumpe el hilo.
     */
    fun <T> enExclusiva(bloque: () -> T): T {
        cerrojo.lockInterruptibly()
        try {
            return bloque()
        } finally {
            cerrojo.unlock()
        }
    }

    /**
     * Pasa la pareja del llavero, apunta cómo ha ido (`state/`) y guarda el log
     * si falla, como cualquier pareja.
     *
     * Al acabar se lo dice a los avisos ([trasPasada]) y, si algo ha quedado sin
     * subir (se guardó durante la pasada, o no ha salido), encola otra
     * ([Disparos.siPendiente]).
     *
     * @return cómo ha ido, o `null` si no hay nada que pasar: el volumen aún no
     *   está sembrado, o el config no lleva el llavero (se ha quitado con un
     *   trabajo encolado).
     */
    fun pasar(
        contexto: Context,
        alProgreso: (Map<String, Any?>) -> Unit = {},
        decir: (String) -> Unit = {},
    ): Pasada.Resultado? {
        val resultado = enExclusiva {
            val v = Prdrive.volumen(contexto)
            if (!v.sembrado) return@enExclusiva null
            val config = v.leerConfig()
            val pareja = config.parejaLlavero ?: return@enExclusiva null
            Prdrive.iniciarRclone(contexto)
            Pasada.pasarOFallar(
                v, pareja, Adaptadores.pasada, Adaptadores.opciones,
                // Android cifra el almacenamiento de la app: el teléfono cuenta
                // como cifrado (el motor, por defecto, no).
                Pasada.Contexto(cifrado = true, keepLogs = config.keepLogs),
                alProgreso, decir,
            )
        } ?: return null
        trasPasada(contexto, resultado)
        Disparos.siPendiente(contexto)
        return resultado
    }

    /**
     * Si hay algo del llavero sin subir ([Llavero.pendiente]). `false` sin
     * volumen, sin llavero o con un config que no se puede leer: ahí no hay
     * pasada que encolar.
     */
    fun pendiente(contexto: Context): Boolean {
        val v = Prdrive.volumen(contexto)
        val pareja = parejaDe(v) ?: return false
        val tipos = Bisync.tiposDeRemote(v.leerRcloneConf())
        return Llavero.pendiente(pareja.carpetaEn(v.raiz), Llavero.listadoDe(v, pareja, tipos))
    }

    /** Si la última pasada buena del llavero es de hace menos de [DESDE_LA_ULTIMA]. */
    fun reciente(contexto: Context): Boolean {
        val ultima = Resultados.ultimasBuenas(Prdrive.volumen(contexto).state, listOf(LLAVERO))[LLAVERO]
            ?: return false
        // Como `ahora - ultima > DESDE_LA_ULTIMA` en prdrive: una marca del
        // futuro (el reloj ha ido hacia atrás) cuenta como reciente.
        return System.currentTimeMillis() - ultima <= DESDE_LA_ULTIMA.toMillis()
    }

    private fun parejaDe(v: Volumen): Pareja? {
        if (!v.sembrado) return null
        return try {
            v.leerConfig().parejaLlavero
        } catch (_: ConfigError) {
            null
        } catch (_: TomlError) {
            null
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Lo que se avisa tras una pasada del llavero (`Avisos.trasPasada`, que
     * trae Task 9: la notificación de un fallo o de un conflicto). Hasta
     * entonces, nada.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun trasPasada(contexto: Context, resultado: Pasada.Resultado) = Unit
}
