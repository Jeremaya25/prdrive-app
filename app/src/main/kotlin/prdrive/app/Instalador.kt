package prdrive.app

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import prdrive.app.llavero.Disparos
import prdrive.app.rclone.Adaptadores
import prdrive.engine.Catalogo
import prdrive.engine.Config
import prdrive.engine.ConfigError
import prdrive.engine.DEFAULT_MODE
import prdrive.engine.LLAVERO_LLAVE
import prdrive.engine.LLAVERO_LOCAL
import prdrive.engine.Llavero
import prdrive.engine.MODES
import prdrive.engine.Pairing
import prdrive.engine.Pasada
import prdrive.engine.Store
import prdrive.engine.Volumen
import prdrive.engine.dumpsChecked
import prdrive.engine.headerOf
import prdrive.engine.loadRaw
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
 * Y, ya sembrado, [activarLlavero] y [quitarLlavero]: el teléfono entra en el
 * llavero que el remoto ya tiene («Traer el del remoto» de prdrive; nunca crea
 * uno) y sale de él.
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
     * - El nombre del remote es el del catálogo, y sus opciones las del
     *   `[remote]` del catálogo si viene completo, menos lo que ejecuta algo o
     *   es secreto ([Pairing.alinearConCatalogo]). La clave y los known_hosts
     *   son los del código.
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
            Pasada.Contexto(
                resyncAprobado = true, keepLogs = config.keepLogs, accesoCompartido = Prdrive.accesoCompartido(),
            ),
            alProgreso, decir,
        )
    }

    /**
     * Activa en el teléfono el llavero que ya tiene el remoto (`plan_activar()`
     * con `origen = None`; `hacer()`). No hace la pasada: la lanza quien llama,
     * que es quien la hace con la pantalla delante.
     *
     * En este orden: lo que puede fallar antes de tocar nada (la tabla del
     * remoto, que el config con ella vale y se relee igual, y la llave si hace
     * falta), la carpeta `.keychain/` y la llave, el `rclone.conf` con el
     * `upstreams` del `combine` al día y, el último, el `sync_config.toml`. El
     * `combine` va antes que el config porque un `.keychain` de más en él no
     * hace nada, y un config con `[keychain]` sin él haría fallar cada pasada;
     * y el config va el último, como en [instalar], porque es el que marca lo
     * hecho. Si algo falla antes de él, activar se puede repetir, y lo que se
     * ha creado se deshace ([deshacerAlta]): la llave, si no estaba, la carpeta
     * `.keychain/`, si no estaba, y el `rclone.conf` de antes. Sin el config,
     * nada deja fuera esa carpeta de una pareja de la raíz entera, que subiría
     * la llave.
     *
     * La llave se copia con el content resolver (`content://`, `file://`) a
     * `.keychain/llave.keyx`, de una vez. Su contenido no pasa por ningún
     * mensaje ni por ningún sitio que no sea ese fichero.
     *
     * @param remota La tabla `[keychain]` del catálogo del remoto
     *   (`Catalogo.Catalog.raw["keychain"]`).
     * @param llave El fichero llave que da la persona. Hace falta si la tabla
     *   dice `llave_interna`, y si no se ignora.
     * @return El config con el llavero.
     * @throws ConfigError si el remoto no tiene llavero, la tabla no vale, o
     *   el llavero va sin contraseña y no hay fichero llave (entonces no se ha
     *   escrito nada).
     * @throws IOException si no se puede abrir o copiar la llave, o escribir.
     */
    fun activarLlavero(contexto: Context, remota: Map<String, Any?>, llave: Uri?): Config {
        val v = Prdrive.volumen(contexto)
        val texto = v.config.readText(Charsets.UTF_8)
        val raw = Volumen.conLlavero(loadRaw(texto), remota)
        val config = parseConfig(raw)
        val interna = config.parejaLlavero?.llaveInterna == true
        if (interna && llave == null) {
            throw ConfigError("Este llavero va sin contraseña: hace falta su fichero llave.")
        }
        val cabecera = headerOf(texto)
        dumpsChecked(raw, cabecera)
        // Lo que puede fallar al generarlo, antes de tocar el disco.
        config.seccionCombine(v.raiz.absolutePath)

        val carpeta = File(v.raiz, LLAVERO_LOCAL)
        val antes = Antes(
            carpeta = Files.exists(carpeta.toPath(), LinkOption.NOFOLLOW_LINKS),
            llave = Files.exists(File(carpeta, LLAVERO_LLAVE).toPath(), LinkOption.NOFOLLOW_LINKS),
            rcloneConf = v.leerRcloneConfSiSePuede(),
        )
        try {
            Llavero.prepararCarpeta(v.raiz, hora = Prdrive::hora)
            if (interna) {
                val entrada = try {
                    contexto.contentResolver.openInputStream(llave!!)
                } catch (e: SecurityException) {
                    throw IOException("No tengo permiso para leer el fichero llave.", e)
                } catch (e: java.io.FileNotFoundException) {
                    throw IOException("No he podido abrir el fichero llave.", e)
                } ?: throw IOException("No he podido abrir el fichero llave.")
                entrada.use { Llavero.ponerLlave(it, v.raiz) }
            }
            v.actualizarCombine(config)
            v.escribirConfig(raw, cabecera)
        } catch (e: Throwable) {
            deshacerAlta(v, antes)
            throw e
        }
        Prdrive.recargarRclone(contexto)
        return config
    }

    /**
     * Lo que había antes de activar el llavero, para deshacerlo si no se acaba.
     * [rcloneConf] es `null` si no se pudo leer: entonces no se devuelve.
     */
    private class Antes(val carpeta: Boolean, val llave: Boolean, val rcloneConf: String?)

    /**
     * Deshace un [activarLlavero] que no ha llegado a escribir el config: la
     * llave y la carpeta `.keychain/` que no estaban (una que ya estaba, con
     * sus bases, se queda), y el `rclone.conf` como estaba. Lo que no se puede
     * deshacer no tapa el fallo que lo ha traído aquí.
     */
    private fun deshacerAlta(v: Volumen, antes: Antes) {
        val carpeta = File(v.raiz, LLAVERO_LOCAL)
        try {
            if (!antes.llave) Files.deleteIfExists(File(carpeta, LLAVERO_LLAVE).toPath())
            // Solo lo que ha creado activar: el LEEME y la llave. No se siguen enlaces.
            if (!antes.carpeta && Files.isDirectory(carpeta.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                carpeta.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
                Files.deleteIfExists(carpeta.toPath())
            }
        } catch (e: IOException) {
            Log.w("Instalador", "Deshaciendo la carpeta del llavero", e)
        }
        // Uno que no se pudo leer antes no se devuelve: sería escribir un vacío
        // encima. Tampoco se ha podido cambiar (`actualizarCombine` lo lee primero).
        val conf = antes.rcloneConf
        if (conf == null) {
            Log.w("Instalador", "El rclone.conf de antes no se pudo leer: se deja como está")
        } else if (v.leerRcloneConfSiSePuede() != conf && !Store.writeText(v.rcloneConf, conf)) {
            Log.w("Instalador", "No se ha podido devolver el rclone.conf de antes")
        }
    }

    /**
     * Quita el llavero de este teléfono (`plan_desactivar()`; `hacer()`): el
     * config pierde `[keychain]` y el `combine` su `.keychain`. La carpeta
     * `.keychain/` se queda (con la base y, si la hay, la llave) y el remoto no
     * se toca: los demás dispositivos siguen con él. Lo que avisa prdrive antes
     * de hacerlo sale de [Volumen.avisosBaja], con el config de antes.
     *
     * Aquí el config va primero y el `combine` después: si el segundo falla,
     * queda un `.keychain` de más en el `combine`, que no hace nada.
     *
     * Y la pasada del llavero que hubiera encolada se cancela ([Disparos]). Si
     * ya había empezado a correr, al leer el config no encuentra el llavero y
     * acaba sin hacer nada.
     *
     * Los permisos que se dieron sobre lo de `.keychain/` (el persistible de
     * «Abrir llavero» a la app de KeePass, o el que dio el selector de Archivos)
     * se retiran ([retirarPermisos]): sin llavero, ninguna app tiene por qué
     * seguir pudiendo escribir la base.
     *
     * @return El config sin llavero.
     * @throws ConfigError si el teléfono no lleva el llavero.
     */
    fun quitarLlavero(contexto: Context): Config {
        val v = Prdrive.volumen(contexto)
        val texto = v.config.readText(Charsets.UTF_8)
        val actual = loadRaw(texto)
        if (!actual.containsKey("keychain")) throw ConfigError(Volumen.SIN_LLAVERO)
        val raw = Volumen.sinLlavero(actual)
        val config = parseConfig(raw)
        v.escribirConfig(raw, headerOf(texto))
        retirarPermisos(contexto, File(v.raiz, LLAVERO_LOCAL))
        v.actualizarCombine(config)
        Prdrive.recargarRclone(contexto)
        Disparos.cancelar(contexto)
        return config
    }

    /**
     * Retira los permisos de otras apps sobre cada fichero de [carpeta] (la del
     * llavero): `revokeUriPermission` quita, a todas, los que dio el proveedor
     * de esta app, persistidos incluidos. Va por fichero porque el id de un
     * documento va codificado en la URI, y no hay «subruta» que los abarque.
     * Un fallo solo se apunta: no impide quitar el llavero.
     */
    internal fun retirarPermisos(contexto: Context, carpeta: File) {
        for (f in carpeta.listFiles().orEmpty()) {
            if (!f.isFile) continue
            val uri = android.provider.DocumentsContract.buildDocumentUri(
                prdrive.app.documentos.VolumenProvider.AUTORIDAD,
                "${prdrive.app.documentos.VolumenProvider.ID_RAIZ}/$LLAVERO_LOCAL/${f.name}",
            )
            try {
                contexto.revokeUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: RuntimeException) {
                Log.w("Instalador", "Retirando los permisos de ${f.name}", e)
            }
        }
    }
}
