package prdrive.app.llavero

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import prdrive.app.Actualizador
import prdrive.engine.Pasada

/**
 * TrabajoLlavero.kt — La pasada del llavero en segundo plano, con WorkManager:
 * la que encolan [Disparos] al guardar la base, al leerla y si algo quedó sin
 * subir.
 *
 * Qué se reintenta, como en `Vigilancia` (`common/llavero.py`): una pasada que
 * falla, sí (sin red, un remoto que no contesta), con la espera de [Disparos].
 * Una base rota ([Pasada.LLAVERO_ROTO]) y un freno que no se ha podido repetir,
 * no: reintentar no los arregla, y esperan al siguiente guardado o a «Abrir
 * llavero».
 *
 * La red local (API 37): el trabajo no puede pedir el permiso. Sin él, la
 * pasada falla con la explicación de siempre y se reintenta; la fila lo enseña.
 */
class TrabajoLlavero(contexto: Context, params: WorkerParameters) : CoroutineWorker(contexto, params) {

    /**
     * Pasa el llavero ([LlaveroApp.pasar]) y dice a WorkManager si repetir.
     *
     * Avisa al [Actualizador] (`empezarTrabajo`/`acabarTrabajo`): con una pasada
     * en marcha no se instala la versión nueva, y con ella instalándose no
     * empieza ninguna (se reintenta). `acabarTrabajo` va al final, con la
     * pasada ya apuntada: con la app fuera de la vista puede instalar la
     * versión nueva y cerrar el proceso antes de que WorkManager apunte este
     * resultado, y entonces WorkManager repite el trabajo, que no pierde nada.
     *
     * Una pasada en marcha no se corta nunca, como en el escritorio (la
     * vigilancia de `common/llavero.py` deja acabar la pasada y recoge después
     * lo guardado durante ella). Si WorkManager para el trabajo (un guardado
     * que lo sustituye, [Disparos.cancelar], la red que se va), la pasada sigue
     * hasta el final, se apunta como cualquier otra y suelta el lock de bisync;
     * su resultado ya no cuenta para WorkManager, pero `acabarTrabajo` se llama
     * igual. Por eso va con `withContext` y no con `runInterruptible`: cortar el
     * hilo pararía rclone a mitad de una transferencia, y una escritura del
     * motor sobre un canal interrumpido fallaría (`ClosedByInterruptException`).
     */
    override suspend fun doWork(): Result {
        if (!Actualizador.empezarTrabajo()) return Result.retry()
        try {
            val resultado = try {
                withContext(Dispatchers.IO) { LlaveroApp.pasar(applicationContext) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Lo que no es una pasada fallida (rclone que no arranca, un
                // config que no se lee) tampoco es para siempre.
                return Result.retry()
            }
            return if (resultado == null || !reintentar(resultado)) Result.success() else Result.retry()
        } finally {
            Actualizador.acabarTrabajo(applicationContext)
        }
    }

    private companion object {

        /** Si una pasada que ha acabado así se vuelve a intentar sola. */
        fun reintentar(r: Pasada.Resultado): Boolean {
            if (r.ok || r.codigo == Pasada.LLAVERO_ROTO) return false
            // El freno que la pasada no ha podido quitar: su log es el que la paró.
            val log = try {
                r.log?.readText(Charsets.UTF_8) ?: ""
            } catch (_: IOException) {
                ""
            }
            return !(Pasada.esBorradosBisync(log) || Pasada.esCambiadosBisync(log))
        }
    }
}
