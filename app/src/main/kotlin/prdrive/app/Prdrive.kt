package prdrive.app

import android.content.Context
import java.io.File
import prdrive.app.rclone.Rclone
import prdrive.engine.Volumen

/**
 * Prdrive.kt — Dónde vive cada cosa de la app, en un solo sitio.
 *
 * El volumen es `filesDir/volumen`: almacenamiento privado de la app, que
 * Android cifra y que no necesita ningún permiso. La carpeta temporal de rclone
 * va en `noBackupFilesDir` y no en la caché, que el sistema puede vaciar
 * mientras la app está viva.
 */
object Prdrive {

    fun volumen(contexto: Context): Volumen = Volumen(File(contexto.filesDir, "volumen"))

    fun temporal(contexto: Context): File = File(contexto.noBackupFilesDir, "rclone")

    /**
     * Deja rclone listo para el volumen. Se llama antes de cada cosa que hable
     * con rclone: la primera vez lo arranca, y las demás vuelven a entrar en
     * `.prdrive/` y a crear la carpeta temporal (ver `Iniciar` en el paquete Go).
     */
    fun iniciarRclone(contexto: Context): Volumen {
        val v = volumen(contexto)
        v.programa.mkdirs()
        Rclone.iniciar(v.rcloneConf, v.programa, temporal(contexto))
        return v
    }
}
