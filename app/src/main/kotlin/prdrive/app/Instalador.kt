package prdrive.app

import android.content.Context
import prdrive.app.rclone.Adaptadores
import prdrive.engine.Catalogo
import prdrive.engine.Config
import prdrive.engine.DEFAULT_CATALOG_PATH
import prdrive.engine.Pairing
import prdrive.engine.Pasada
import prdrive.engine.Volumen
import prdrive.engine.headerOf

/**
 * Instalador.kt — El primer arranque, sin pantallas: lo que hace `install/` de
 * prdrive para sembrar un dispositivo, en el orden en que lo pide la pantalla.
 *
 * 1. [conectar]: con la carga del QR, el `rclone.conf` con solo el remote, y la
 *    clave. Es lo justo para leer el catálogo.
 * 2. [leerCatalogo]: el catálogo del remoto. Si no se lee, la conexión no va,
 *    y el motivo es el que se enseña.
 * 3. [instalar]: con las parejas elegidas, el `sync_config.toml`, el
 *    `rclone.conf` entero (con el `combine` de esas parejas) y sus carpetas.
 * 4. [inicializar]: el `--resync` inicial de las bisync, como
 *    `deploy.resync_targets()`: un espejo no se inicializa, porque propagaría
 *    el vacío.
 *
 * Todo bloquea: se llama fuera del hilo de la pantalla.
 */
object Instalador {

    /** Escribe la conexión del QR en el volumen y deja rclone listo para usarla. */
    fun conectar(contexto: Context, carga: Pairing.Carga): Volumen {
        if (!carga.configurado) {
            throw Pairing.PairingError("El código no trae una conexión completa: le falta el remote o su tipo.")
        }
        val v = Prdrive.volumen(contexto)
        v.crearDistribucion()
        v.escribirRemoto(carga, null)
        return Prdrive.recargarRclone(contexto)
    }

    /**
     * El `sync_config.toml` que tendría el dispositivo antes de tenerlo: solo
     * lo que dice dónde está el catálogo, que es lo que trae el QR.
     */
    fun rawLocal(carga: Pairing.Carga): Map<String, Any?> =
        mapOf("defaults" to mapOf("remote" to carga.remoteName, "catalog_path" to carga.catalogPath))

    /** Lee el catálogo del remoto (y deja su copia en `state/`). */
    fun leerCatalogo(contexto: Context, carga: Pairing.Carga): Catalogo.Catalog =
        Catalogo.pull(
            Adaptadores.catalogo(Prdrive.temporal(contexto)),
            rawLocal(carga),
            Prdrive.volumen(contexto).state,
        )

    /**
     * Siembra el volumen con las parejas elegidas y devuelve su config. La ruta
     * del catálogo solo se escribe si no es la de fábrica, como el instalador.
     */
    fun instalar(contexto: Context, carga: Pairing.Carga, catalogo: Catalogo.Catalog, elegidas: List<String>): Config {
        val v = Prdrive.volumen(contexto)
        val ruta = carga.catalogPath.takeIf { it != DEFAULT_CATALOG_PATH } ?: ""
        val raw = Volumen.deviceConfig(catalogo.raw, elegidas, ruta)
        v.escribirConfig(raw, Volumen.deviceHeader(catalogo.endpoint, headerOf(catalogo.text)))
        val config = v.leerConfig()
        v.escribirRemoto(carga, config)
        v.crearCarpetas(catalogo.raw, elegidas)
        Prdrive.recargarRclone(contexto)
        return config
    }

    /** Las parejas que hay que inicializar con `--resync`, en el orden del config. */
    fun aInicializar(config: Config, catalogo: Catalogo.Catalog): List<String> {
        val bisync = Volumen.resyncTargets(catalogo.raw, config.names).toSet()
        return config.names.filter { it in bisync }
    }

    /** El `--resync` inicial de una pareja, ya aprobado: para eso se ha elegido. */
    fun inicializar(
        contexto: Context,
        config: Config,
        nombre: String,
        alProgreso: (Map<String, Any?>) -> Unit,
        decir: (String) -> Unit,
    ): Pasada.Resultado {
        val v = Prdrive.volumen(contexto)
        val pareja = config.pairs.first { it.name == nombre }
        return Pasada.pasar(
            v, pareja, Adaptadores.pasada, Adaptadores.opciones,
            Pasada.Contexto(resyncAprobado = true), alProgreso, decir,
        )
    }
}
