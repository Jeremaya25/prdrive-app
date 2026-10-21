package prdrive.app

import android.content.Context
import prdrive.app.rclone.Adaptadores
import prdrive.engine.Catalogo
import prdrive.engine.Config
import prdrive.engine.ConfigError
import prdrive.engine.DEFAULT_MODE
import prdrive.engine.MODES
import prdrive.engine.Pairing
import prdrive.engine.Pasada
import prdrive.engine.Volumen
import prdrive.engine.dumpsChecked
import prdrive.engine.headerOf
import prdrive.engine.parseConfig
import prdrive.engine.strPy

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
     * Lo que deja [instalar]: el config, lo que se ha ajustado para decirlo
     * (`align_with_catalog`) y los espejos, que no se inicializan.
     */
    data class Instalado(val config: Config, val notas: List<String>, val espejos: List<String>)

    /**
     * Siembra el volumen con las parejas elegidas.
     *
     * El orden es el del instalador (`write_device_remote` antes que
     * `write_device_config`), y por la misma razón: el config es lo que marca un
     * volumen como sembrado ([Volumen.sembrado]), así que va el último. Antes de
     * escribir nada se comprueba todo lo que puede fallar —el `combine` de las
     * parejas, el texto del config—: o se escribe entero, o no se escribe, y
     * entonces el primer arranque se puede repetir.
     *
     * - El nombre del remote es el del catálogo ([Pairing.alinearConCatalogo]).
     * - La ruta del catálogo es la del código, siempre, como hace el instalador
     *   con la de su perfil: es de donde se ha leído.
     * - La carpeta de un espejo de subida NO se crea, a diferencia de
     *   `make_local_dirs()`: aquí «Sincronizar» pasa todas las parejas en
     *   seguida, sin `--dry-run`, y un origen vacío vaciaría el remoto. Sin
     *   carpeta, la pasada se para con su explicación hasta que la persona la
     *   cree con lo que quiere subir.
     */
    fun instalar(contexto: Context, carga: Pairing.Carga, catalogo: Catalogo.Catalog, elegidas: List<String>): Instalado {
        val v = Prdrive.volumen(contexto)
        val repetidas = catalogo.names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (repetidas.isNotEmpty()) {
            throw ConfigError(
                "El catálogo tiene más de una pareja con el mismo nombre (${repetidas.joinToString(", ")}): " +
                    "así no se puede saber cuál es cuál. Corrígelo en el catálogo y vuelve a empezar.",
            )
        }
        val (alineada, notas) = Pairing.alinearConCatalogo(carga, catalogo.raw)
        val raw = Volumen.deviceConfig(catalogo.raw, elegidas, carga.catalogPath)
        val config = parseConfig(raw)
        val cabecera = Volumen.deviceHeader(catalogo.endpoint, headerOf(catalogo.text))
        // Lo que puede fallar, antes de tocar el disco.
        Pairing.rcloneConf(alineada, config.seccionCombine(v.raiz.absolutePath))
        dumpsChecked(raw, cabecera)

        v.escribirRemoto(alineada, config)
        val subida = elegidas.filter { MODES[modoDe(catalogo, it)]?.borraEnRemoto == true }.toSet()
        v.crearCarpetas(catalogo.raw, elegidas.filter { it !in subida })
        v.escribirConfig(raw, cabecera)
        Prdrive.recargarRclone(contexto)
        return Instalado(config, notas, Volumen.mirrorPairs(catalogo.raw, elegidas))
    }

    private fun modoDe(catalogo: Catalogo.Catalog, nombre: String): String =
        strPy(Catalogo.findPair(catalogo, nombre)?.get("mode") ?: DEFAULT_MODE)

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
        return Pasada.pasarOFallar(
            v, pareja, Adaptadores.pasada, Adaptadores.opciones,
            Pasada.Contexto(resyncAprobado = true, keepLogs = config.keepLogs), alProgreso, decir,
        )
    }
}
