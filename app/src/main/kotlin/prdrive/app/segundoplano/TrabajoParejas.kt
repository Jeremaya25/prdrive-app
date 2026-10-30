package prdrive.app.segundoplano

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import prdrive.app.Actualizador

/**
 * TrabajoParejas.kt — Una vuelta de «Sincronizar sola» ([SegundoPlano.pasarTodo]),
 * cuando WorkManager la lanza.
 *
 * Como [prdrive.app.llavero.TrabajoLlavero]: avisa al [Actualizador] (con una
 * pasada en marcha no se instala la versión nueva), y una pasada en marcha no se
 * corta nunca: `withContext` y no `runInterruptible`, porque cortar el hilo
 * pararía rclone a mitad de una transferencia y una escritura del motor sobre
 * un canal interrumpido fallaría. Si WorkManager lo para (la wifi que se va),
 * la pasada sigue hasta el final y se apunta como cualquier otra.
 *
 * No se reintenta lo que falla: la vuelta siguiente llega sola, y una pareja que
 * falla lo dice en su fila.
 */
class TrabajoParejas(contexto: Context, params: WorkerParameters) : CoroutineWorker(contexto, params) {

    override suspend fun doWork(): Result {
        if (!Actualizador.empezarTrabajo()) return Result.retry()
        try {
            withContext(Dispatchers.IO) { SegundoPlano.pasarTodo(applicationContext) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Lo que no es una pasada fallida (rclone que no arranca, un config
            // que no se lee): en la vuelta siguiente.
        } finally {
            Actualizador.acabarTrabajo(applicationContext)
        }
        return Result.success()
    }
}
