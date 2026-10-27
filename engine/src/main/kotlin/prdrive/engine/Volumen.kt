package prdrive.engine

import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Volumen.kt — El «disco» de la app: una carpeta con la distribución de un
 * dispositivo de prdrive, y lo que hace el instalador para sembrarlo.
 *
 * Espejo de la parte de `install/deploy.py` de prdrive que no es copiar el
 * programa: `device_config()`, `_daemon_section()`, `device_header()`,
 * `local_dirs()`, `make_local_dirs()`, `resync_targets()`, `mirror_pairs()` y
 * `write_device_remote()`. La distribución es la misma a propósito: los
 * ficheros de estado siguen siendo legibles por el programa de escritorio.
 *
 * ```
 * <raiz>/                 las carpetas de las parejas
 * <raiz>/.prdrive/        sync_config.toml, rclone.conf, keys/, state/, filters/, logs/
 * ```
 *
 * No toda pareja cuelga de la raíz: una pareja puede vivir en una carpeta
 * compartida del teléfono ([Compartidas], `.prdrive/compartidas.json`), y
 * entonces solo el upstream de su `combine` apunta fuera del volumen.
 *
 * El motor no sabe dónde está [raiz]: en la app es `filesDir/volumen`, y la
 * pasa quien la sabe.
 */
class Volumen(val raiz: File) {

    /** `.prdrive/`: la carpeta del programa, con la clave y el `rclone.conf`. */
    val programa: File get() = File(raiz, CARPETA_PROGRAMA)
    val config: File get() = File(programa, "sync_config.toml")
    val rcloneConf: File get() = File(programa, "rclone.conf")
    val keys: File get() = File(programa, "keys")
    val state: File get() = File(programa, "state")
    val filtros: File get() = File(programa, "filters")
    val logs: File get() = File(programa, "logs")

    /** `compartidas.json`: qué parejas viven en una carpeta compartida ([Compartidas]). */
    val compartidas: File get() = File(programa, Compartidas.FICHERO)

    /** El workdir de bisync de una pareja: `state/<pareja>/`. */
    fun workdir(pareja: Pareja): File = File(state, pareja.workdir)

    /**
     * Crea la distribución de `.prdrive/` (`state/`, `filters/`, `logs/`). En el
     * escritorio la deja el instalador; aquí la crea quien siembra el volumen y,
     * por si acaso, cada pasada: sin `state/`, `last_run.json` no se escribe y
     * un fallo se quedaría escondido.
     */
    fun crearDistribucion() {
        for (carpeta in listOf(programa, state, filtros, logs)) {
            if (!carpeta.isDirectory && !carpeta.mkdirs()) throw IOException("No he podido crear $carpeta")
        }
    }

    /** Si el volumen ya está sembrado: tiene config y `rclone.conf`. */
    val sembrado: Boolean get() = config.isFile && rcloneConf.isFile

    /**
     * El `sync_config.toml` del volumen, resuelto, con las carpetas compartidas
     * de `compartidas.json` puestas ([Config.conCompartidas]). Un
     * `compartidas.json` que no se lee es un [ConfigError], no «ninguna»: ver
     * [Compartidas.leer].
     */
    fun leerConfig(): Config =
        parseConfig(loadRaw(config.readText(Charsets.UTF_8))).conCompartidas(Compartidas.leer(compartidas), raiz)

    /**
     * El `rclone.conf` del volumen, o vacío si no hay. Para mirarlo (los tipos
     * de los remotes); lo que lo reescribe usa [leerRcloneConfSiSePuede]: aquí
     * no se distingue «vacío» de «no se ha podido leer».
     */
    fun leerRcloneConf(): String = leerRcloneConfSiSePuede() ?: ""

    /**
     * El `rclone.conf` del volumen tal cual, o `null` si no se ha podido leer
     * (no está, o la lectura falla). Lo que no se ha leído no se vuelve a
     * escribir: escribir un vacío en su lugar borraría el remoto y la clave.
     */
    fun leerRcloneConfSiSePuede(): String? = try {
        rcloneConf.readText(Charsets.UTF_8)
    } catch (_: IOException) {
        null
    }

    /**
     * Si la carpeta local de la pareja, ya resuelta, es la del programa o cae
     * dentro (`model.problema_contencion()`): lo que el texto no delata —un
     * enlace— puede llevar un `local` inocente a `.prdrive/`, donde está la
     * clave, y un espejo de bajada la borraría. La pareja de la raíz entera la
     * contiene pero no cae dentro: lleva [REGLA_SIN_PROGRAMA].
     */
    fun problemaContencion(pareja: Pareja): String? {
        val prog = programa.canonicalFile
        for (ruta in listOf(pareja.carpetaEn(raiz), File(raiz, pareja.topLevelDir).takeIf { !pareja.esRaiz })) {
            val resuelta = ruta?.canonicalFile ?: continue
            if (resuelta == prog || resuelta.toPath().startsWith(prog.toPath())) {
                return "la carpeta local de la pareja resuelve a '$resuelta', que es la carpeta " +
                    "del programa ('$prog') o cae dentro de ella: lleva la clave del " +
                    "dispositivo y no se sincroniza. Puede ser un enlace. Pon en 'local' " +
                    "otra carpeta de dentro del dispositivo."
            }
        }
        return null
    }

    /**
     * Escribe el `rclone.conf` del volumen y la clave, con rutas relativas
     * (`write_device_remote()`): `key_file = keys/…` se resuelve contra
     * `.prdrive/`, que es el directorio de trabajo con el que corre rclone. La
     * sección `[disp]` del remote `combine` va detrás, calculada con todas las
     * parejas del config. El conf se genera antes de escribir nada, para que uno
     * rechazado no deje la clave a medias.
     *
     * Sin [config], solo el remote: es lo que hace falta para leer el catálogo,
     * y el config sale de él. El instalador lo vuelve a escribir, con el
     * `combine`, en cuanto hay parejas elegidas.
     *
     * El `combine` lleva las carpetas compartidas de `compartidas.json` (un
     * fichero roto es un [ConfigError] antes de escribir nada).
     *
     * @return lo escrito.
     */
    fun escribirRemoto(carga: Pairing.Carga, config: Config?): List<File> {
        val seccion = config?.conCompartidas(Compartidas.leer(compartidas), raiz)?.seccionCombine(raiz.absolutePath)
        val texto = Pairing.rcloneConf(carga, seccion ?: "")
        val escrito = ArrayList<File>()
        val clave = carga.privateKey
        if (clave != null) {
            keys.mkdirs()
            val destino = File(keys, Pairing.nombreDeClave(carga.keyName))
            destino.writeBytes(clave)
            // Solo para la app: como el `chmod 0o600` del escritorio.
            destino.setReadable(false, false)
            destino.setReadable(true, true)
            destino.setWritable(false, false)
            destino.setWritable(true, true)
            escrito.add(destino)
            if (carga.knownHosts.isNotBlank()) {
                val kh = File(keys, "known_hosts")
                kh.writeText(carga.knownHosts)
                escrito.add(kh)
            }
        }
        programa.mkdirs()
        if (!Store.writeText(rcloneConf, texto)) throw IOException("No he podido escribir $rcloneConf")
        escrito.add(rcloneConf)
        return escrito
    }

    /**
     * Reescribe la sección `[disp]` del `rclone.conf` cuando cambian las parejas
     * del config, dejando la del remoto como estaba.
     *
     * El [config] no tiene por qué traer las carpetas compartidas puestas:
     * siempre se aplican [entradas] (por defecto las del fichero) antes de
     * escribir, con lo que reescribir el `combine` con un config recién leído
     * del TOML no devuelve las parejas compartidas al volumen. Quien acaba de
     * cambiar las entradas y aún no las ha escrito las pasa aquí.
     *
     * @throws IOException si no se puede leer (o no está) o escribir: uno que
     *   no se ha leído se deja como está.
     * @throws ConfigError si [entradas] se lee del fichero y está roto.
     */
    fun actualizarCombine(
        config: Config,
        entradas: Map<String, Compartidas.Entrada> = Compartidas.leer(compartidas),
    ) {
        // Uno que no se puede leer no se rehace solo con el `combine`: se iría el remoto.
        val actual = leerRcloneConfSiSePuede() ?: throw IOException("No he podido leer $rcloneConf")
        val nombre = config.deviceRemote ?: return
        val sinCombine = quitarSeccion(actual, nombre).trimEnd('\n')
        val seccion = config.conCompartidas(entradas, raiz).seccionCombine(raiz.absolutePath)
        val texto = (if (sinCombine.isEmpty()) "" else sinCombine + "\n\n") + seccion
        if (!Store.writeText(rcloneConf, texto)) throw IOException("No he podido escribir $rcloneConf")
    }

    private fun quitarSeccion(texto: String, nombre: String): String {
        val salida = ArrayList<String>()
        var dentro = false
        for (linea in texto.lines()) {
            val limpia = linea.trim()
            if (limpia.startsWith("[") && limpia.endsWith("]")) {
                dentro = limpia.substring(1, limpia.length - 1).trim() == nombre
            }
            if (!dentro) salida.add(linea)
        }
        return salida.joinToString("\n")
    }

    /**
     * Escribe el `sync_config.toml` del volumen (`write_device_config()`), ya
     * comprobado: [dumpsChecked] se niega antes que escribir algo que no se
     * relee igual.
     */
    fun escribirConfig(raw: Map<String, Any?>, cabecera: String) {
        val texto = dumpsChecked(raw, cabecera)
        crearDistribucion()
        if (!Store.writeText(config, texto)) throw IOException("No he podido escribir $config")
    }

    /** Crea las carpetas locales de las parejas elegidas y devuelve las que se han creado ahora. */
    fun crearCarpetas(catalogo: Map<String, Any?>, elegidas: List<String>): List<File> {
        val creadas = ArrayList<File>()
        for (rel in localDirs(catalogo, elegidas)) {
            val destino = File(raiz, rel)
            if (destino.isDirectory) continue
            if (!destino.mkdirs()) throw IOException("No he podido crear $destino")
            creadas.add(destino)
        }
        return creadas
    }

    companion object {

        /** Lo que dice `plan_desactivar()` si el dispositivo no lleva el llavero. */
        const val SIN_LLAVERO = "Este dispositivo no lleva el llavero."

        /** El instalador no puede seguir: el mensaje es para la persona. */
        class InstallError(mensaje: String) : Exception(mensaje)

        private fun parejaDe(catalogo: Map<String, Any?>, nombre: String): Map<String, Any?>? =
            parejasDe(catalogo).firstOrNull { it["name"] == nombre }

        /**
         * El config crudo de ESTE volumen (`device_config()`): los `[defaults]`
         * del catálogo con `device_remote` (desde el primer día: sin él el
         * nombre de los listados llevaría la ruta de la app), su `[daemon]`
         * recortado y solo las parejas elegidas. Se comprueba con [parseConfig].
         */
        fun deviceConfig(catalogo: Map<String, Any?>, elegidas: List<String>, catalogPath: String = ""): Map<String, Any?> {
            val faltan = elegidas.filter { parejaDe(catalogo, it) == null }
            if (faltan.isNotEmpty()) {
                throw InstallError("El catálogo no tiene estas parejas: ${faltan.joinToString(", ")}.")
            }
            if (elegidas.isEmpty()) {
                throw InstallError(
                    "Hay que elegir al menos una pareja: un sync_config.toml sin ninguna " +
                        "no lo acepta sync.py.",
                )
            }
            val raw = LinkedHashMap<String, Any?>()
            val defaults = LinkedHashMap<String, Any?>(tablaDe(catalogo, "defaults") ?: emptyMap())
            if (!defaults.containsKey("device_remote")) defaults["device_remote"] = DEFAULT_DEVICE_REMOTE
            if (catalogPath.isNotEmpty()) defaults["catalog_path"] = catalogPath
            raw["defaults"] = defaults
            daemonSection(catalogo, elegidas).takeIf { it.isNotEmpty() }?.let { raw["daemon"] = it }
            raw["pair"] = elegidas.map { LinkedHashMap(parejaDe(catalogo, it)!!) }
            parseConfig(raw)
            return raw
        }

        /** El `[daemon]` del catálogo recortado a las parejas de este volumen. */
        fun daemonSection(catalogo: Map<String, Any?>, elegidas: List<String>): Map<String, Any?> {
            val daemon = tablaDe(catalogo, "daemon") ?: return emptyMap()
            val salida = LinkedHashMap(daemon)
            if ("pairs" in salida) {
                val quedan = (salida["pairs"] as? List<*>).orEmpty().filter { it in elegidas }
                if (quedan.isNotEmpty()) salida["pairs"] = quedan else salida.remove("pairs")
            }
            return salida
        }

        /** La cabecera del config generado: de dónde sale y cuándo, y la del catálogo. */
        fun deviceHeader(endpoint: String, cabeceraCatalogo: String, ahora: LocalDateTime = LocalDateTime.now()): String {
            val donde = endpoint.ifEmpty { "el catálogo del remoto" }
            val propia = "# Generado por prdrive-app el " +
                ahora.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + ".\n" +
                "# Es el config de ESTE dispositivo: el catálogo global vive en\n" +
                "# $donde.\n"
            return propia + if (cabeceraCatalogo.isNotEmpty()) "#\n$cabeceraCatalogo" else ""
        }

        /**
         * El config con el llavero del remoto puesto (`ui/llavero_editor.py`,
         * `plan_activar()` con `origen = None`: «Traer el del remoto»): una
         * copia del config con `[keychain]` igual a la tabla del catálogo, que
         * [Llavero.decidirAlta] decide. El teléfono nunca crea un llavero, solo
         * entra en el que ya hay. Se comprueba con [parseConfig] (`model.parse_config()`
         * antes de confirmar): un `[keychain]` que no vale se rechaza ahora, no
         * al primer arranque.
         *
         * @throws ConfigError si el remoto no tiene llavero, o la tabla no vale.
         */
        fun conLlavero(rawActual: Map<String, Any?>, remota: Map<String, Any?>?): Map<String, Any?> {
            val tabla = Llavero.decidirAlta(remota)
            val nuevo = copiaProfunda(rawActual)
            nuevo["keychain"] = tabla
            parseConfig(nuevo)
            return nuevo
        }

        /**
         * El config sin `[keychain]` (`plan_desactivar()`): lo demás, igual. La
         * carpeta del llavero y el remoto no se tocan.
         */
        fun sinLlavero(rawActual: Map<String, Any?>): Map<String, Any?> {
            val nuevo = copiaProfunda(rawActual)
            nuevo.remove("keychain")
            return nuevo
        }

        /** `copy.deepcopy(dict(raw))`: los mapas y las listas se copian, lo demás es inmutable. */
        private fun copiaProfunda(raw: Map<String, Any?>): LinkedHashMap<String, Any?> {
            fun copia(valor: Any?): Any? = when (valor) {
                is Map<*, *> -> LinkedHashMap<Any?, Any?>().also { m -> valor.forEach { (k, v) -> m[k] = copia(v) } }
                is List<*> -> valor.map { copia(it) }
                else -> valor
            }
            val salida = LinkedHashMap<String, Any?>()
            raw.forEach { (k, v) -> salida[k] = copia(v) }
            return salida
        }

        /** Los nombres de las parejas bisync de la raíz entera, ya entre comillas angulares. */
        private fun raices(config: Config): String =
            config.delUsuario.filter { it.esRaiz && it.isBisync }.joinToString(", ") { "«${it.name}»" }

        /**
         * Lo que avisa `plan_activar()` de prdrive y aplica en el teléfono: la
         * pareja de la raíz entera, que desde ahora deja fuera `.keychain/` y
         * por eso pedirá un `--resync`. (Los avisos de KeePassXC y del formato
         * de la base son del escritorio.) Sirve el config de antes o de después
         * de poner el llavero: solo mira las parejas del usuario.
         */
        fun avisosAlta(config: Config): List<String> {
            val raices = raices(config)
            if (raices.isEmpty()) return emptyList()
            return listOf(
                "$raices sincroniza la raíz entera: desde ahora deja fuera $LLAVERO_LOCAL/, " +
                    "y por eso pedirá un --resync.",
            )
        }

        /**
         * Lo que avisa `plan_desactivar()` y aplica en el teléfono, en su orden:
         * con `llave_interna`, que el fichero llave se queda; y la pareja de la
         * raíz entera, que sin llavero llevará `.keychain/` y pedirá un
         * `--resync` (y, con llave interna, subiría también la llave).
         *
         * @param config El del dispositivo, con su llavero.
         * @throws ConfigError si el dispositivo no lleva el llavero.
         */
        fun avisosBaja(config: Config): List<String> {
            if (config.llavero == null) throw ConfigError(SIN_LLAVERO)
            val interna = config.parejaLlavero?.llaveInterna == true
            val avisos = ArrayList<String>()
            if (interna) {
                avisos.add(
                    "El fichero llave ($LLAVERO_LOCAL/$LLAVERO_LLAVE) se queda en el dispositivo: sin " +
                        "él la base no se abre. Bórralo tú si ya no lo quieres aquí.",
                )
            }
            val raices = raices(config)
            if (raices.isNotEmpty()) {
                avisos.add(
                    "$raices sincroniza la raíz entera: sin llavero, $LLAVERO_LOCAL/ viajará con ella, " +
                        "y pedirá un --resync." +
                        if (interna) " Con ella subiría también el fichero llave: quítala de ahí antes." else "",
                )
            }
            return avisos
        }

        /** Las carpetas que necesitan las parejas elegidas; la de la raíz no cuenta. */
        fun localDirs(catalogo: Map<String, Any?>, elegidas: List<String>): List<String> =
            elegidas.mapNotNull { nombre ->
                val local = strPy(parejaDe(catalogo, nombre)?.get("local") ?: "").replace("\\", "/").trim('/')
                local.takeIf { it.isNotEmpty() && it != "." }
            }

        /** Las elegidas que hay que inicializar: solo las bisync (un espejo propagaría el vacío). */
        fun resyncTargets(catalogo: Map<String, Any?>, elegidas: List<String>): List<String> =
            elegidas.filter { (parejaDe(catalogo, it)?.get("mode") ?: DEFAULT_MODE) == "bisync" }

        /** Las elegidas que son espejo, para poder avisar de ellas. */
        fun mirrorPairs(catalogo: Map<String, Any?>, elegidas: List<String>): List<String> =
            elegidas.filter { (parejaDe(catalogo, it)?.get("mode") ?: "") in listOf("up-mirror", "down-mirror") }
    }
}
