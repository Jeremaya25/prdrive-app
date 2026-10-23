package prdrive.app.llavero

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration

/**
 * Disparos.kt — Cuándo se encola una pasada del llavero ([TrabajoLlavero]).
 *
 * Es el `Vigilancia` del escritorio (`common/llavero.py`, con la
 * `PoliticaCambios` de `common/planificador.py`) hecho con WorkManager: siempre
 * con el mismo nombre ([NOMBRE]), así que nunca hay dos trabajos del llavero, y
 * con red (`CONNECTED`; en el escritorio, una red de pago no retiene al
 * llavero).
 *
 * Una pasada que falla se reintenta con espera exponencial desde [SEPARACION].
 * El escritorio la topa en 30 min (`llavero.TOPE_REINTENTO`); WorkManager no
 * deja poner un tope, y el suyo es de 5 h. Diferencia aceptada: un llavero sin
 * red espera más entre intentos, y cada guardado o lectura lo vuelve a lanzar.
 */
object Disparos {

    /** El nombre único del trabajo del llavero. */
    const val NOMBRE = "llavero"

    /** Lo que se espera tras un guardado por si llega otro (`PoliticaCambios.calma`, 20 s). */
    val CALMA: Duration = Duration.ofSeconds(20)

    /** La primera espera tras una pasada fallida (`PoliticaCambios.separacion`, 120 s). */
    val SEPARACION: Duration = Duration.ofMinutes(2)

    /**
     * Al guardar una base: sustituye al trabajo que hubiera, con [CALMA] por
     * delante, así que cada guardado aplaza la subida. Si había una pasada en
     * marcha, no se corta ([TrabajoLlavero.doWork]): acaba y se apunta, y el
     * trabajo nuevo espera a que suelte el cerrojo ([LlaveroApp.enExclusiva]) y
     * sube lo guardado durante ella.
     */
    fun alGuardar(c: Context) = encolar(c, ExistingWorkPolicy.REPLACE, CALMA)

    /**
     * Al leer una base por el proveedor: una pasada ya, que vale para la
     * próxima vez que se abra. Nada si la última buena es reciente
     * ([LlaveroApp.reciente]): es la que acaba de hacer «Abrir llavero». Si ya
     * hay un trabajo, se queda ese.
     */
    fun alLeer(c: Context) {
        if (LlaveroApp.reciente(c)) return
        encolar(c, ExistingWorkPolicy.KEEP, Duration.ZERO)
    }

    /**
     * Al arrancar la app y al acabar una pasada: una ya, si hay algo sin subir
     * ([LlaveroApp.pendiente]): un guardado hecho durante una pasada, o uno cuyo
     * trabajo se perdió. Si ya hay un trabajo, se queda ese.
     *
     * Al acabar la pasada de un trabajo, ese trabajo aún cuenta como en marcha
     * y `KEEP` se lo queda: no se encola otro. Es lo que evita que una base
     * rota, que sigue pendiente, se vuelva a pasar en bucle. Lo guardado
     * durante esa pasada ya encoló el suyo ([alGuardar]), que sigue esperando
     * (y `KEEP` lo conserva). Desde una pasada de la pantalla, o desde la de
     * un trabajo que ya ha sido sustituido, sí encola si no hay ninguno.
     */
    fun siPendiente(c: Context) {
        if (LlaveroApp.pendiente(c)) encolar(c, ExistingWorkPolicy.KEEP, Duration.ZERO)
    }

    /** Al quitar el llavero: el trabajo que hubiera, fuera. */
    fun cancelar(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork(NOMBRE)
    }

    private fun encolar(c: Context, politica: ExistingWorkPolicy, retraso: Duration) {
        WorkManager.getInstance(c).enqueueUniqueWork(NOMBRE, politica, peticion(retraso))
    }

    private fun peticion(retraso: Duration): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<TrabajoLlavero>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SEPARACION)
            .setInitialDelay(retraso)
            .build()
}
