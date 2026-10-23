package prdrive.app.llavero

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import prdrive.app.MainActivity
import prdrive.app.Prdrive
import prdrive.app.R
import prdrive.engine.Conflictos
import prdrive.engine.Store

/**
 * Avisos.kt — Las notificaciones del llavero: solo lo que pide hacer algo
 * fuera de prdrive. Una copia de conflicto nueva (`Agente._copias_del_llavero()`
 * de `agente.py`) y un guardado que llegó cortado. Lo demás lo dice la fila de
 * la principal.
 *
 * Sin el permiso de notificaciones (`POST_NOTIFICATIONS`, que se pide al
 * activar el llavero) no se avisa de nada, y queda la fila.
 */
object Avisos {

    /** El canal de las notificaciones del llavero. */
    const val CANAL = "llavero"

    /**
     * El principio del aviso de una copia de conflicto (`agente.LLAVERO_EN_CONFLICTO`).
     * Escrito aquí, y comprobado contra el vector en `MotorEnAndroidTest`.
     */
    const val LLAVERO_EN_CONFLICTO =
        "Se guardó en dos dispositivos sin sincronizar en medio, y las dos versiones están en el " +
            "dispositivo: no se ha perdido nada. "

    /**
     * Cómo acaba ese aviso en el teléfono, que no tiene «Combinar»: las dos de
     * prdrive hablan de su ventana (`COMBINAR_AQUI`) y de KeePassXC
     * (`COMBINAR_A_MANO`).
     */
    const val COMBINAR_FUERA = "Combínalas en el ordenador con «Abrir llavero», o en tu app de KeePass."

    /** La etiqueta de las notificaciones de una copia (el id sale de su ruta). */
    private const val ETIQUETA_COPIA = "llavero-copia"

    /** La etiqueta y el id de la de un guardado cortado: una sola, la del último. */
    private const val ETIQUETA_CORTADO = "llavero-cortado"

    /** Lo avisado se apunta y se lee de uno en uno: lo llaman la pasada y el proveedor. */
    private val cerrojo = Any()

    /**
     * Tras una pasada del llavero, con sus copias de conflicto: una
     * notificación por cada copia que no se haya avisado ya, una sola vez. Lo
     * avisado se apunta en `noBackupFilesDir` (es del teléfono, no del
     * volumen); cuando no queda ninguna copia se olvida, para que una nueva con
     * el mismo nombre se vuelva a decir.
     *
     * Una copia que no se ha podido avisar (sin permiso, o con las
     * notificaciones de la app apagadas) no se apunta: se dirá cuando se pueda.
     */
    fun trasPasada(contexto: Context, conflictos: List<Conflictos.Conflicto>) {
        val raiz = Prdrive.volumen(contexto).raiz
        val copias = conflictos.flatMap { c -> c.copias.map { it to c } }
            .associateBy({ (copia, _) -> relativa(copia, raiz) }, { it })
        synchronized(cerrojo) {
            val fichero = avisados(contexto)
            if (copias.isEmpty()) {
                fichero.delete()
                return
            }
            val antes = leer(fichero)
            // Las que ya no están se olvidan; las nuevas, se avisan.
            val siguen = antes.filter { it in copias }.toMutableSet()
            val nuevas = copias.keys.filter { it !in antes }.sorted()
            if (nuevas.isEmpty() && siguen.size == antes.size) return
            val gestor = NotificationManagerCompat.from(contexto)
            if (nuevas.isNotEmpty() && puede(gestor)) {
                canal(contexto)
                for (ruta in nuevas) {
                    val (copia, conflicto) = copias.getValue(ruta)
                    avisar(
                        contexto, gestor, ETIQUETA_COPIA, ruta.hashCode(),
                        "El llavero tiene dos versiones de ${conflicto.original.name}",
                        LLAVERO_EN_CONFLICTO + COMBINAR_FUERA,
                        copia.name,
                    )
                    siguen += ruta
                }
            }
            fichero.parentFile?.mkdirs()
            Store.writeJson(fichero, mapOf("copias" to siguen.sorted()))
        }
    }

    /**
     * Un guardado de [fichero] que llegó cortado (`state/llavero-cortado.json`):
     * la base sigue como estaba y lo guardado no ha subido. Una notificación,
     * que sustituye a la de un guardado cortado anterior.
     */
    fun guardadoCortado(contexto: Context, fichero: String) {
        val gestor = NotificationManagerCompat.from(contexto)
        if (!puede(gestor)) return
        canal(contexto)
        avisar(
            contexto, gestor, ETIQUETA_CORTADO, 0,
            "Un guardado del llavero no ha llegado entero",
            "El guardado de $fichero llegó cortado y no se ha puesto: la base sigue como estaba. " +
                "Vuelve a guardar en tu app de KeePass.",
            null,
        )
    }

    /** Un guardado bueno deja atrás el cortado: su notificación, fuera. */
    fun guardadoBueno(contexto: Context) {
        NotificationManagerCompat.from(contexto).cancel(ETIQUETA_CORTADO, 0)
    }

    /** Donde se apunta lo avisado: es del teléfono, no del volumen. */
    private fun avisados(contexto: Context): File = File(File(contexto.noBackupFilesDir, "llavero"), "avisados.json")

    private fun leer(fichero: File): Set<String> =
        (Store.readJson(fichero)["copias"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()

    /** La ruta de la copia dentro del volumen, con barras normales: lo que se apunta. */
    private fun relativa(copia: File, raiz: File): String =
        copia.relativeToOrNull(raiz)?.invariantSeparatorsPath ?: copia.absolutePath

    /** Si se puede notificar: con el permiso dado (desde Android 13) y las notificaciones de la app encendidas. */
    private fun puede(gestor: NotificationManagerCompat): Boolean = gestor.areNotificationsEnabled()

    /** El canal, creado si falta (crearlo otra vez no cambia lo que la persona haya ajustado). */
    private fun canal(contexto: Context) {
        val canal = NotificationChannel(CANAL, "Llavero", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Copias de conflicto del llavero y guardados que no han llegado enteros."
        }
        contexto.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun avisar(
        contexto: Context,
        gestor: NotificationManagerCompat,
        etiqueta: String,
        id: Int,
        titulo: String,
        texto: String,
        detalle: String?,
    ) {
        // Al tocarla, la app, con la fila del llavero.
        val abrir = PendingIntent.getActivity(
            contexto, 0,
            Intent(contexto, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(contexto, CANAL)
            .setSmallIcon(R.drawable.ic_prdrive)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setSubText(detalle)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        try {
            gestor.notify(etiqueta, id, n)
        } catch (_: SecurityException) {
            // El permiso se ha retirado entre mirarlo y usarlo: queda la fila.
        }
    }
}
