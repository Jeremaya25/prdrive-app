package prdrive.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import prdrive.app.rclone.Adaptadores
import prdrive.app.rclone.Rclone
import prdrive.engine.Config
import prdrive.engine.Orden
import prdrive.engine.Pareja
import prdrive.engine.Pasada
import prdrive.engine.Volumen
import prdrive.engine.leerJson

/**
 * Banco.kt — Lo que comparten los tests instrumentados: los vectores del motor,
 * un volumen limpio en el sitio de verdad de la app y un «remoto» que es una
 * carpeta local, para probar contra rclone sin red.
 *
 * Todos usan el volumen de la app (`filesDir/volumen`) porque rclone solo se
 * arranca una vez por proceso, con un `rclone.conf` y un directorio de trabajo,
 * y los tests corren en el proceso de la app.
 */
object Banco {

    val contexto get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("UNCHECKED_CAST")
    val vectores: Map<String, Any?> by lazy {
        val recurso = Banco::class.java.getResourceAsStream("/vectores.json")
            ?: error("vectores.json no está en los recursos del test")
        leerJson(recurso.bufferedReader().use { it.readText() }) as Map<String, Any?>
    }

    /** El «remoto»: una carpeta de la app que rclone ve como un remote `local`. */
    val remoto: File get() = File(contexto.filesDir, "remoto-de-prueba")

    /**
     * Deja el volumen y el remoto vacíos, escribe el config con esas parejas y
     * un `rclone.conf` con `[nas]` de tipo local y la sección del `combine`, y
     * arranca rclone. Vacía la caché de remotos de rclone, que si no recordaría
     * durante cinco minutos los de la prueba anterior.
     *
     * Con [llavero] (el nombre de su base) el config lleva `[keychain]`, y su
     * carpeta en el remoto es `catalogo/keychain` dentro de [remoto].
     */
    fun volumen(vararg parejas: Map<String, Any?>, llavero: String? = null): kotlin.Pair<Volumen, Config> {
        val v = Prdrive.volumen(contexto)
        v.raiz.deleteRecursively()
        remoto.deleteRecursively()
        remoto.mkdirs()
        v.escribirConfig(
            mapOf(
                "defaults" to mapOf("device_remote" to "disp", "remote" to "nas") +
                    if (llavero != null) mapOf("catalog_path" to File(remoto, "catalogo/remote.toml").absolutePath) else emptyMap(),
                "pair" to parejas.toList(),
            ) + if (llavero != null) mapOf("keychain" to mapOf("base" to llavero)) else emptyMap(),
            "# Para los tests.\n",
        )
        val config = v.leerConfig()
        v.rcloneConf.writeText("[nas]\ntype = local\n\n" + config.seccionCombine(v.raiz.absolutePath))
        Prdrive.iniciarRclone(contexto)
        Rclone.rpc("fscache/clear")
        return v to config
    }

    /** Una pareja cuyo remoto es una carpeta de [remoto]. */
    fun pareja(nombre: String, local: String = "sync-data/$nombre", vararg extra: kotlin.Pair<String, Any?>): Map<String, Any?> =
        mapOf("name" to nombre, "local" to local, "remote_path" to File(remoto, nombre).absolutePath) + extra

    /** La llamada rc de una pasada de la pareja, montada por el motor como la monta la pasada. */
    fun llamada(v: Volumen, p: Pareja, resync: Boolean): Pasada.Llamada {
        val filtros = prdrive.engine.Bisync.filtersFileFor(p, v.filtros)
        val tramos = Orden.tramos(
            p, binario = "rclone", filtros = filtros?.absolutePath, config = v.rcloneConf.absolutePath,
            logfile = "", workdir = v.workdir(p).absolutePath, resync = resync, sello = Pasada.selloVersiones(),
        )
        return Pasada.llamadaDe(tramos, Adaptadores.opciones)
    }
}
