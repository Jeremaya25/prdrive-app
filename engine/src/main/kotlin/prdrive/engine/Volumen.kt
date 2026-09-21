package prdrive.engine

import java.io.File

/**
 * Volumen.kt — El volumen virtual: dónde va cada cosa y qué se escribe en él.
 *
 * Espejo de la parte de `install/deploy.py` que un dispositivo necesita
 * (`device_config`, `write_device_remote`, `make_local_dirs`), sin nada de lo
 * que allí es provisionar un disco: aquí no hay rclone que copiar, ni
 * runtimes, ni lanzadores, ni fichero de control.
 *
 * **La distribución es la de un disco de verdad a propósito.** `.prdrive/` con
 * `rclone.conf`, `keys/`, `state/`, `filters/` y `logs/`: así los ficheros de
 * estado que escribe el móvil los lee el programa de escritorio sin traducir
 * nada, y al revés.
 *
 * Esto vive en `engine/` —no en `app/`, como decía el plan— porque es
 * aritmética de rutas y escritura de ficheros: `java.io.File` y nada más. La
 * app solo aporta **dónde** está la raíz (`filesDir`), que es el único dato
 * que el motor no puede saber.
 *
 * ## El `rclone.conf` de la app es derivado, no guardado
 *
 * Y esta es la diferencia de fondo con el escritorio. Allí el `rclone.conf`
 * lleva rutas **relativas** (`key_file = keys/id_ed25519`) y eso es lo que
 * hace que el disco funcione con cualquier letra de unidad, porque `sync.py`
 * ejecuta rclone con `cwd = APP_DIR`. Aquí no hay proceso al que fijarle un
 * cwd: rclone es una biblioteca dentro de la app, y el directorio de trabajo
 * del proceso no es nuestro para cambiarlo.
 *
 * Así que el `rclone.conf` de la app lleva rutas **absolutas**… y por eso hay
 * que reescribirlo cuando la raíz cambia — que es lo que pasa al reinstalar la
 * app o al moverla de perfil de usuario. No es una complicación añadida: el
 * `upstreams` del remote `combine` ya es absoluto ([Config.seccionCombine]) y
 * ya había que reescribirlo, así que la regla es una sola y vale para las dos
 * cosas: **el `rclone.conf` se genera en cada arranque** a partir de la carga
 * del QR y de las parejas. rclone lo relee solo, porque `Storage._check()`
 * compara la fecha y el tamaño en cada lectura (`fs/config/config.go`).
 *
 * Lo que **no** se regenera es la clave privada: eso llega una vez, con el QR.
 */
class Volumen(
    /**
     * La carpeta que hace de disco. La app pasa `File(filesDir, "volumen")`;
     * los tests, un directorio temporal.
     */
    val raiz: File,
) {

    /** `.prdrive/`: el programa y su estado, como en un disco de verdad. */
    val prdrive: File get() = File(raiz, APP_SUBDIR)

    val rcloneConf: File get() = File(prdrive, "rclone.conf")
    val syncConfig: File get() = File(prdrive, "sync_config.toml")
    val keys: File get() = File(prdrive, "keys")
    val state: File get() = File(prdrive, "state")
    val filters: File get() = File(prdrive, "filters")
    val logs: File get() = File(prdrive, "logs")

    /** El workdir de una pareja: `state/<pareja>/`, uno por pareja. */
    fun workdir(pareja: Pareja): File = File(state, pareja.workdir)

    /** La carpeta local de una pareja, resuelta contra la raíz del volumen. */
    fun carpetaLocal(pareja: Pareja): File =
        if (pareja.tramosLocales.isEmpty()) raiz else File(raiz, pareja.tramosLocales.joinToString("/"))

    /** Crea la distribución. Idempotente: se puede llamar en cada arranque. */
    fun crear() {
        for (d in listOf(raiz, prdrive, keys, state, filters, logs)) {
            if (!d.isDirectory && !d.mkdirs()) {
                throw ConfigError("No se puede crear la carpeta del volumen: $d")
            }
        }
    }

    /**
     * Las carpetas locales de las parejas.
     *
     * Solo las que faltan, y **nunca** la de una pareja que ya tiene baseline:
     * eso lo decide quien ejecuta la pasada, porque una carpeta local vacía
     * donde había un baseline se lee como «han borrado todo» y es el caso que
     * `sync.py` aborta a propósito (`_bisync_preflight`).
     */
    fun crearCarpetasDeParejas(config: Config) {
        for (pareja in config.pairs) {
            val carpeta = carpetaLocal(pareja)
            if (!carpeta.isDirectory && !carpeta.mkdirs()) {
                throw ConfigError("[${pareja.name}] no se puede crear '$carpeta'.")
            }
        }
    }

    /**
     * Guarda la clave privada y los known_hosts del QR. Esto sí se escribe una
     * sola vez, cuando se empareja.
     *
     * La clave se guarda con permisos de solo-el-dueño donde el sistema lo
     * admita. En Android sobra —el almacenamiento privado de la app ya lo es—
     * pero el motor no sabe dónde corre, y un volumen de pruebas en un PC sí
     * lo necesita.
     */
    fun escribirClaves(carga: Pairing.Carga) {
        crear()
        carga.privateKey?.let { clave ->
            val fichero = File(keys, carga.keyName)
            fichero.writeBytes(clave)
            fichero.setReadable(false, false)
            fichero.setReadable(true, true)
            fichero.setWritable(false, false)
            fichero.setWritable(true, true)
        }
        if (carga.knownHosts.isNotBlank()) {
            File(keys, "known_hosts").writeText(carga.knownHosts)
        }
    }

    /**
     * Escribe el `rclone.conf`: el remote del QR más la sección `[disp]` de
     * las parejas. Se llama en cada arranque; ver la nota de arriba.
     *
     * [config] puede ser null mientras todavía no hay parejas elegidas (el
     * primer arranque comprueba la conexión y lee el catálogo antes de que
     * exista ninguna).
     */
    fun escribirRcloneConf(carga: Pairing.Carga, config: Config? = null) {
        crear()
        val seccion = config?.seccionCombine(raiz.absolutePath) ?: ""
        rcloneConf.writeText(
            Pairing.rcloneConf(carga, seccion, dirDeClaves = keys.absolutePath),
        )
    }

    /**
     * Escribe el `sync_config.toml`.
     *
     * Por `dumpsChecked`, que relee lo que ha generado y se niega a escribir si
     * el dict no se reproduce: este fichero es editable a mano y lo lee el
     * programa de escritorio.
     */
    fun escribirConfig(raw: Map<String, Any?>, cabecera: String = CABECERA) {
        crear()
        syncConfig.writeText(dumpsChecked(raw, cabecera))
    }

    /** El `sync_config.toml` que hay, ya resuelto. Null si no hay ninguno. */
    fun configActual(): Config? {
        val texto = runCatching { syncConfig.readText() }.getOrNull() ?: return null
        return parseConfig(loadRaw(texto))
    }

    companion object {
        /**
         * El nombre de la carpeta del programa. Es el mismo que en el disco
         * (`deploy.APP_SUBDIR`), y el punto de delante lo esconde igual.
         */
        const val APP_SUBDIR = ".prdrive"

        /** La cabecera del `sync_config.toml`, que `Toml` conserva al reescribir. */
        val CABECERA = "# Config de prdrive para este móvil. Se puede editar a mano.\n" +
            "# Las parejas salen del catálogo del remoto; aquí está lo que sincroniza\n" +
            "# ESTE dispositivo.\n"

        /**
         * El dict crudo del config de este dispositivo. Espejo de
         * `deploy.device_config()`.
         *
         * **Crudo y no [Config]** porque las [Pareja] del modelo llegan con los
         * `[defaults]` ya fundidos: volcarlas duplicaría los defaults dentro de
         * cada pareja.
         *
         * `device_remote` se mete en los `[defaults]` de ESTE dispositivo y no
         * en el catálogo: cambiarlo en el catálogo movería el prefijo de los
         * listados de todos los dispositivos instalados a la vez. Aquí, además,
         * no es un valor por defecto sino un requisito ([parseConfig] lo
         * exige).
         */
        fun configDeDispositivo(
            catalogo: Catalogo,
            elegidas: List<String>,
            catalogPath: String = "",
        ): Map<String, Any?> {
            val porNombre = catalogo.parejasPorNombre
            val faltan = elegidas.filter { it !in porNombre }
            if (faltan.isNotEmpty()) {
                throw ConfigError("El catálogo no tiene estas parejas: ${faltan.joinToString(", ")}.")
            }
            if (elegidas.isEmpty()) {
                throw ConfigError(
                    "Hay que elegir al menos una pareja: un sync_config.toml sin ninguna " +
                        "no se puede sincronizar.",
                )
            }
            val raw = LinkedHashMap<String, Any?>()
            val defaults = LinkedHashMap<String, Any?>(catalogo.defaults)
            defaults.putIfAbsent("device_remote", DEFAULT_DEVICE_REMOTE)
            if (catalogPath.isNotEmpty()) defaults["catalog_path"] = catalogPath
            raw["defaults"] = defaults
            seccionDaemon(catalogo, elegidas)?.let { raw["daemon"] = it }
            raw["pair"] = elegidas.map { LinkedHashMap(porNombre.getValue(it)) }
            // Red final: lo que se va a escribir tiene que poder leerse.
            parseConfig(raw)
            return raw
        }

        /**
         * El `[daemon]` del catálogo, recortado a lo que existe en este
         * dispositivo.
         *
         * El servicio periódico está fuera del paso 1, pero la tabla viaja en
         * el catálogo y tirarla al escribir el config sería perder lo que el
         * usuario configuró en el PC. Lo que no se puede dejar pasar es una
         * lista de parejas que nombre una que este móvil no tiene.
         */
        private fun seccionDaemon(catalogo: Catalogo, elegidas: List<String>): Map<String, Any?>? {
            val daemon = tablaDe(catalogo.raw, "daemon") ?: return null
            if (daemon.isEmpty()) return null
            val salida = LinkedHashMap<String, Any?>(daemon)
            val parejas = daemon["pairs"]
            if (parejas is Collection<*>) {
                val recortadas = parejas.map { it.toString() }.filter { it in elegidas }
                if (recortadas.isEmpty()) salida.remove("pairs") else salida["pairs"] = recortadas
            }
            return salida
        }
    }
}
