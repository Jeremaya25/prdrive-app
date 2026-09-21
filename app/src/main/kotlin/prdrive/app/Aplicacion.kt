package prdrive.app

import android.app.Application
import prdrive.engine.Rclone
import prdrive.engine.Sincronizador
import prdrive.engine.Volumen
import java.io.File

/**
 * Aplicacion.kt — Lo único que la app sabe y el motor no: dónde está el
 * volumen.
 *
 * `filesDir` es el almacenamiento privado de la app: Android lo cifra, no
 * necesita ningún permiso, y **cambia** si la app se reinstala o se mueve de
 * perfil de usuario. De ahí la regla que explica `Volumen`: el `rclone.conf`
 * se regenera en cada arranque, porque lleva rutas absolutas dentro.
 */
class Aplicacion : Application() {

    /** La carpeta que hace de disco. */
    val volumen: Volumen by lazy { Volumen(File(filesDir, "volumen")) }

    /** El canal con rclone, arrancado en el orden que hay que arrancarlo. */
    val rclone: Rclone by lazy {
        volumen.crear()
        RcloneAar.arrancar(volumen.rcloneConf)
    }

    val sincronizador: Sincronizador by lazy { Sincronizador(rclone, volumen) }
}
