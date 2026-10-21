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

    /**
     * Como [iniciarRclone], tras reescribir el `rclone.conf`: rclone guarda los
     * remotes ya abiertos unos minutos (`fscache`), y sin vaciarla seguiría
     * usando los de antes.
     */
    fun recargarRclone(contexto: Context): Volumen {
        val v = iniciarRclone(contexto)
        Rclone.rpc("fscache/clear")
        return v
    }

    /**
     * El permiso de red local de Android 17 (API 37): sin él, un remoto en la
     * red de casa no contesta. Se escribe como texto porque la constante no
     * existe antes de API 37.
     */
    const val PERMISO_RED_LOCAL = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Si hace falta pedir [PERMISO_RED_LOCAL]: solo desde API 37, y si no está dado. */
    fun faltaRedLocal(contexto: Context): Boolean =
        android.os.Build.VERSION.SDK_INT >= 37 &&
            androidx.core.content.ContextCompat.checkSelfPermission(contexto, PERMISO_RED_LOCAL) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Lo que se le añade a un error de red cuando falta [PERMISO_RED_LOCAL]. */
    const val SIN_RED_LOCAL =
        "Falta el permiso de red local: desde Android 17, sin él la app no puede llegar a un " +
            "servidor de tu red (un NAS en 192.168…, un PC en 10…). Dalo en los ajustes de la app " +
            "o con el botón de la pantalla, y vuelve a intentarlo."
}
