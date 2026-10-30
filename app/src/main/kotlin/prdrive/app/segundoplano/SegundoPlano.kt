package prdrive.app.segundoplano

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import prdrive.app.Prdrive
import prdrive.app.llavero.LlaveroApp
import prdrive.app.rclone.Adaptadores
import prdrive.engine.Compartidas
import prdrive.engine.Config
import prdrive.engine.Pasada

/**
 * SegundoPlano.kt — «Sincronizar sola»: las parejas y el llavero cada cierto
 * tiempo, sin abrir la app, con un trabajo periódico de WorkManager
 * ([TrabajoParejas]). Es lo que en el escritorio hace el servicio (`agente.py`)
 * con el `[daemon]` del config: cada cuánto (`interval_minutes`) y qué parejas
 * (`pairs`; sin ella, todas).
 *
 * Si va o no, y con qué condiciones (solo con wifi, solo mientras carga), es del
 * teléfono, no del config: se guarda en sus preferencias. El momento exacto lo
 * elige Android para ahorrar batería; «Sincronizar ahora» va siempre al momento.
 */
object SegundoPlano {

    /** El nombre del trabajo: uno solo, que se sustituye al cambiar los ajustes. */
    const val NOMBRE = "parejas"

    /** WorkManager no repite un trabajo periódico más a menudo que esto. */
    const val MINIMO_MIN = 15L

    /** El de fábrica del servicio del escritorio, si el config no dice otro. */
    const val POR_DEFECTO_MIN = 60L

    private const val PREFERENCIAS = "segundo-plano"

    /** Si va, y con qué condiciones. */
    data class Ajustes(val activo: Boolean = false, val soloWifi: Boolean = true, val soloCargando: Boolean = false)

    fun leer(c: Context): Ajustes {
        val p = c.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE)
        return Ajustes(p.getBoolean("activo", false), p.getBoolean("soloWifi", true), p.getBoolean("soloCargando", false))
    }

    /** Guarda [a] y programa (o quita) el trabajo con ellos, cada [intervaloMin] minutos. */
    fun guardar(c: Context, a: Ajustes, intervaloMin: Long) {
        c.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE).edit()
            .putBoolean("activo", a.activo)
            .putBoolean("soloWifi", a.soloWifi)
            .putBoolean("soloCargando", a.soloCargando)
            .apply()
        programar(c, a, intervaloMin, ExistingPeriodicWorkPolicy.UPDATE)
    }

    /**
     * Al abrir la app: si va, que el trabajo esté (sin moverlo si ya está). Una
     * reinstalación borra los trabajos de WorkManager, y no las preferencias si
     * vienen de una copia.
     */
    fun asegurar(c: Context) {
        val a = leer(c)
        if (!a.activo) return
        val minutos = try {
            intervalo(Prdrive.volumen(c).leerConfig())
        } catch (_: Exception) {
            POR_DEFECTO_MIN
        }
        programar(c, a, minutos, ExistingPeriodicWorkPolicy.KEEP)
    }

    private fun programar(c: Context, a: Ajustes, intervaloMin: Long, politica: ExistingPeriodicWorkPolicy) {
        val wm = WorkManager.getInstance(c)
        if (!a.activo) {
            wm.cancelUniqueWork(NOMBRE)
            return
        }
        val condiciones = Constraints.Builder()
            .setRequiredNetworkType(if (a.soloWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(a.soloCargando)
            .build()
        val peticion = PeriodicWorkRequestBuilder<TrabajoParejas>(maxOf(intervaloMin, MINIMO_MIN), TimeUnit.MINUTES)
            .setConstraints(condiciones)
            .build()
        wm.enqueueUniquePeriodicWork(NOMBRE, politica, peticion)
    }

    /** Cada cuántos minutos, según el `[daemon]` del config: `interval_minutes`, o 60; nunca menos de 15. */
    fun intervalo(config: Config): Long {
        val minutos = (config.daemon["interval_minutes"] as? Number)?.toLong() ?: POR_DEFECTO_MIN
        return maxOf(minutos, MINIMO_MIN)
    }

    /** «cada hora», «cada 30 min», «cada 2 horas», «cada 1 h 30 min». */
    fun cadaCuanto(minutos: Long): String {
        val h = minutos / 60
        val m = minutos % 60
        return when {
            h == 0L -> "cada $m min"
            m == 0L && h == 1L -> "cada hora"
            m == 0L -> "cada $h horas"
            else -> "cada $h h $m min"
        }
    }

    /** Cuándo tocará la próxima, en milisegundos de época, si está programada. */
    fun proxima(c: Context): Long? =
        WorkManager.getInstance(c).getWorkInfosForUniqueWork(NOMBRE).get()
            .firstOrNull { !it.state.isFinished }
            ?.nextScheduleTimeMillis
            ?.takeIf { it in 1 until Long.MAX_VALUE }

    /**
     * Una vuelta: las parejas del `[daemon]` (o todas), una detrás de otra como
     * «Sincronizar», y el llavero el último. Una pareja a medio mover no se pasa
     * (solo la pasa «Terminar de mover»), y a una bisync que pide rehacer la
     * referencia no se le hace: falla como en «Sincronizar», porque eso lo
     * aprueba alguien que ha leído por qué. Bloquea.
     */
    fun pasarTodo(c: Context) {
        val v = Prdrive.iniciarRclone(c)
        if (!v.sembrado) return
        val config = v.leerConfig()
        val ctx = Pasada.Contexto(keepLogs = config.keepLogs, accesoCompartido = Prdrive.accesoCompartido())
        val entradas = Compartidas.leer(v.compartidas)
        val elegidas = (config.daemon["pairs"] as? List<*>)?.map { it.toString() }?.takeIf { it.isNotEmpty() }
        for (p in config.delUsuario) {
            if (elegidas != null && p.name !in elegidas) continue
            if (entradas[p.name]?.estado == Compartidas.Estado.ENTRANDO) continue
            Pasada.pasarOFallar(v, p, Adaptadores.pasada, Adaptadores.opciones, ctx)
        }
        if (config.parejaLlavero != null) LlaveroApp.pasar(c)
    }
}
