package prdrive.engine

import java.io.File
import java.security.MessageDigest

/**
 * Bisync.kt — Todo lo que replica el comportamiento interno de rclone bisync.
 *
 * Espejo de `common/bisync.py` de prdrive (versión citada en
 * `engine/src/test/resources/vectores.json`), y como el original, cada apartado
 * cita el fichero de rclone cuyo comportamiento imita. **Conserva esas citas.**
 * Si se toca algo de aquí, es contra esas fuentes contra lo que hay que
 * contrastarlo, no contra lo que parezca razonable.
 *
 * Aquí vive la parte incómoda, y vive junta a propósito: bisync guarda su
 * baseline en ficheros cuyo nombre deduce de los dos extremos, y no perdona que
 * ese nombre cambie. Saber calcular ANTES de ejecutar el nombre que rclone va a
 * buscar es lo que permite decidir si el baseline que hay en disco sirve para
 * esta pareja o hay que apartarlo y rehacerlo.
 *
 * **Lo que NO hay, igual que en el escritorio: renombrado automático de los
 * listados.** Renombrar es decirle a bisync que el listado del destino ANTERIOR
 * describe el NUEVO, y no hay forma de distinguir el caso benigno del maligno.
 * Desde que el lado local va como remote `combine` el nombre no depende de
 * dónde esté montado el volumen, así que la herida está cerrada y la venda
 * sobra.
 */
object Bisync {

    const val PATH1_SUFFIX = ".path1.lst"
    const val PATH2_SUFFIX = ".path2.lst"
    const val ERR_SUFFIX = ".lst-err"

    /** El mensaje con el que rclone se queja de que no encuentra el baseline. */
    const val MISSING_LISTINGS = "cannot find prior Path1 or Path2 listings"

    // -----------------------------------------------------------------------
    // Nombre de sesión
    //
    // Réplica exacta de cmd/bisync/bilib/canonical.go (rclone v1.75.1):
    //
    //   FsPath(f)        -> "remote:ruta/" para todo lo que no es el backend
    //                       local; para él, la ruta con el separador del SO
    //   CanonicalPath(s) -> quita / y \ de los extremos y sustituye
    //                       [\s\\/:?*] por _
    //   SessionName      -> StripHexString(CanonicalPath(path1)) + ".." +
    //                       StripHexString(CanonicalPath(path2))
    //
    // El {hexstring} lo añade rclone al nombre del Fs cuando su configuración
    // viene de flags, del entorno o de una connection string —que es justo el
    // caso del escritorio, que define `disp` con variables de entorno—, y
    // `StripHexString` lo quita. `BasePath` devuelve el nombre YA limpio, así
    // que el fichero en disco es el limpio. La app define `disp` en el
    // rclone.conf, así que ahí no hay hexstring que quitar y el nombre sale
    // idéntico: es lo que hace que un mismo par de extremos tenga el mismo
    // nombre de listado en el móvil y en el PC.
    //
    // rclone NO recorta este nombre por longitud (no hay maxBaseNameLen en
    // v1.75.1), así que aquí tampoco.
    // -----------------------------------------------------------------------

    private val NO_CANONICAL = Regex("[\\s\\\\/:?*]")

    fun canonicalPath(remote: String): String =
        NO_CANONICAL.replace(remote.trim('\\', '/'), "_")

    fun stripHexString(path: String): String {
        val abre = path.indexOf('{')
        val cierra = path.indexOf('}')
        return if (abre >= 0 && cierra > abre) {
            path.substring(0, abre) + path.substring(cierra + 1)
        } else {
            path
        }
    }

    /**
     * `FsPath` para cualquier remote: `nombre:ruta/`.
     *
     * No se replica la rama del backend `local` de `FsPath` —la que usa el
     * separador del sistema y quita el prefijo `\\?\` de Windows— porque en la
     * app **nunca** se da: el lado local es siempre el remote `combine`
     * ([DEFAULT_DEVICE_REMOTE]), que para rclone es un remote como otro
     * cualquiera. Un config sin `device_remote` lo rechaza [parseConfig] antes
     * de llegar aquí, en vez de calcular un nombre que no sería el que rclone
     * busca.
     */
    fun fsPathRemote(s: String): String = if (s.endsWith("/")) s else "$s/"

    fun sessionName(path1: String, path2: String): String =
        stripHexString(canonicalPath(path1)) + ".." + stripHexString(canonicalPath(path2))

    /** Nombre base (.path1.lst / .path2.lst) que rclone buscará para esta pareja. */
    fun expectedPrefix(pareja: Pareja): String = sessionName(
        fsPathRemote(pareja.endpoint(pareja.mode.source)),
        fsPathRemote(pareja.endpoint(pareja.mode.dest)),
    )

    // -----------------------------------------------------------------------
    // Filtros
    //
    // bisync guarda el md5 del fichero de filtros JUNTO AL PROPIO FICHERO
    // (filtersFile + ".md5", ver cmd/bisync/cmd.go: applyFilters) y solo lo
    // escribe durante un --resync. Si el fichero cambia sin resync, aborta con
    // error crítico. Aquí se compara el md5 ANTES de ejecutar y se trata como
    // «hace falta resync», que es una conversación y no un log rojo.
    // -----------------------------------------------------------------------

    const val FILTERS_HEADER =
        "# Generado por sync.py desde sync_config.toml. No editar a mano:\n" +
            "# se regenera en cada ejecución. Cambiar los patrones exige --resync."

    /** `ok` | `new` | `changed`, y por qué. */
    data class EstadoFiltros(val status: String, val detail: String) {
        val needsResync: Boolean get() = status != "ok"
    }

    fun filtersContent(pareja: Pareja): String {
        val lineas = ArrayList<String>()
        lineas.add(FILTERS_HEADER)
        pareja.includes.forEach { lineas.add("+ $it") }
        pareja.excludes.forEach { lineas.add("- $it") }
        if (pareja.includes.isNotEmpty()) {
            // Igual que --include: si hay reglas '+', todo lo demás queda fuera.
            lineas.add("- **")
        }
        return lineas.joinToString("\n") + "\n"
    }

    /**
     * Genera (si hace falta) `filters/<pareja>.txt` y devuelve su ruta.
     *
     * El contenido es determinista: si no cambia, no se reescribe el fichero,
     * para no invalidar el md5 sin motivo.
     */
    fun filtersFileFor(pareja: Pareja, filtersDir: File): File? {
        if (!pareja.wantsFiltersFile) return null
        val contenido = filtersContent(pareja)
        filtersDir.mkdirs()
        val fichero = File(filtersDir, "${pareja.name}.txt")
        if (!fichero.exists() || fichero.readText() != contenido) {
            fichero.writeText(contenido)
        }
        return fichero
    }

    /** Compara el fichero de filtros con el .md5 que dejó el último resync. */
    fun filtersState(ffile: File?): EstadoFiltros {
        if (ffile == null) return EstadoFiltros("ok", "sin fichero de filtros")
        val digest = md5(ffile.readBytes())
        val hashFile = File(ffile.path + ".md5")
        if (!hashFile.exists()) return EstadoFiltros("new", "${ffile.name} sin hash previo")
        if (hashFile.readText().trim() != digest) {
            return EstadoFiltros(
                "changed",
                "${ffile.name} ha cambiado desde el último resync",
            )
        }
        return EstadoFiltros("ok", "${ffile.name} sin cambios")
    }

    private fun md5(datos: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(datos)
            .joinToString("") { "%02x".format(it) }

    // -----------------------------------------------------------------------
    // Estado del baseline
    // -----------------------------------------------------------------------

    /** `fresh` | `ok` | `broken`, y el prefijo cuando es `ok`. */
    data class EstadoPareja(val status: String, val detail: String, val prefix: String?) {
        val hasBaseline: Boolean get() = status == "ok"
    }

    /** El estado real del baseline, mirando los .lst que hay en el workdir. */
    fun pairState(workdir: File): EstadoPareja {
        if (!workdir.exists()) {
            return EstadoPareja("fresh", "sin workdir (nunca sincronizada)", null)
        }
        val nombres = (workdir.list() ?: emptyArray()).sorted()

        // OJO al orden: los .lst-err NO los limpia nadie (rclone solo renombra
        // .lst -> .lst-err al abortar; ver cmd/bisync/operations.go). Si después
        // hay un juego de listados válido, el baseline es bueno y esos son
        // residuo.
        val path1 = nombres.filter { it.endsWith(PATH1_SUFFIX) }
        val path2 = nombres.filter { it.endsWith(PATH2_SUFFIX) }
        val errores = nombres.filter { it.endsWith(ERR_SUFFIX) }
        val residuo = if (errores.isEmpty()) "" else " (+${errores.size} $ERR_SUFFIX residuales)"

        if (path1.isNotEmpty() && path2.isNotEmpty()) {
            val pre1 = path1.map { it.removeSuffix(PATH1_SUFFIX) }.toSet()
            val pre2 = path2.map { it.removeSuffix(PATH2_SUFFIX) }.toSet()
            val comun = pre1 intersect pre2
            if (comun.size != 1 || pre1.size != 1 || pre2.size != 1) {
                return EstadoPareja(
                    "broken",
                    "varios juegos de listados: ${(pre1 + pre2).sorted()}",
                    null,
                )
            }
            val prefijo = comun.first()
            return EstadoPareja("ok", "baseline '$prefijo'$residuo", prefijo)
        }

        if (errores.isNotEmpty()) {
            return EstadoPareja(
                "broken",
                "${errores.size} listado(s) marcados $ERR_SUFFIX por un fallo crítico previo",
                null,
            )
        }
        if (path1.isNotEmpty() || path2.isNotEmpty()) {
            return EstadoPareja("broken", "falta uno de los dos listados (.path1/.path2)", null)
        }
        return EstadoPareja("fresh", "sin listados previos", null)
    }

    /**
     * Cuándo fue la última pasada buena, o `null` si no hay forma de saberlo.
     *
     * No existe un registro de pasadas y no hace falta inventarlo: bisync
     * reescribe sus dos listados justo al terminar bien (ver
     * cmd/bisync/operations.go), así que la fecha del más nuevo ES la de la
     * última sincronización correcta. Fuera de bisync no queda rastro —un `copy`
     * no deja estado—, y esas parejas se quedan sin hora antes que enseñar una
     * inventada.
     */
    fun lastRun(pareja: Pareja, workdir: File): Long? {
        if (!pareja.isBisync) return null
        val marcas = (workdir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(PATH1_SUFFIX) || it.name.endsWith(PATH2_SUFFIX) }
            .map { it.lastModified() }
        return marcas.maxOrNull()
    }

    /**
     * Por qué esta pareja necesita `--resync`. Lista vacía = no lo necesita.
     *
     * El estado se puede pasar ya calculado: quien acaba de mirarlo no tiene por
     * qué volver a recorrer el workdir.
     */
    fun resyncReasons(
        pareja: Pareja,
        estado: EstadoPareja,
        filtros: EstadoFiltros,
    ): List<String> {
        if (!pareja.isBisync) return emptyList()
        val razones = ArrayList<String>()
        if (!estado.hasBaseline) razones.add("estado ${estado.status}: ${estado.detail}")
        if (filtros.needsResync) razones.add("filtros ${filtros.status}: ${filtros.detail}")
        return razones
    }

    // -----------------------------------------------------------------------
    // Apartar el baseline
    // -----------------------------------------------------------------------

    /**
     * Aparta el baseline de una pareja: `state/<n>/` -> `state/<n>.old-<sello>/`.
     *
     * Es lo que hay que hacer cuando cambia un EXTREMO de la pareja (local,
     * remote, remote_path o mode). Reaprovechar los listados con el prefijo
     * nuevo le estaría diciendo a bisync que el listado del destino VIEJO
     * describe el destino NUEVO, y todo lo que no esté en el nuevo se leería
     * como borrado y se propagaría al otro lado. Apartándolo, la pareja queda
     * `fresh` y exige un `--resync` explícito, que es una conversación.
     *
     * Se renombra en vez de borrar por si hay que volver atrás; el directorio
     * apartado queda inerte, porque lo que recorre `state/` solo mira su primer
     * nivel.
     *
     * Devuelve dónde ha quedado, o `null` si no había baseline que apartar.
     */
    fun shelveBaseline(stateDir: File, nombre: String, sello: String): File? {
        val workdir = File(stateDir, nombre)
        if (!workdir.isDirectory) return null
        var destino = File(stateDir, "$nombre.old-$sello")
        var n = 1
        while (destino.exists()) {
            destino = File(stateDir, "$nombre.old-${sello}_$n")
            n++
        }
        return if (workdir.renameTo(destino)) destino else null
    }

    /**
     * Mueve el estado de una pareja cuando solo le cambia el nombre.
     *
     * El prefijo de los listados NO depende del nombre (ver [expectedPrefix]:
     * sale de los extremos), así que renombrar no invalida el baseline. Lo que
     * sí cuelga del nombre son las rutas: `state/<nombre>/` y
     * `filters/<nombre>.txt`, con su `.md5` al lado. Se mueven juntos para que
     * el hash que guarda bisync siga cuadrando.
     */
    fun renamePairState(
        stateDir: File,
        filtersDir: File,
        viejo: String,
        nuevo: String,
    ): List<kotlin.Pair<File, File>> {
        val movimientos = ArrayList<kotlin.Pair<File, File>>()
        val origen = File(stateDir, viejo)
        val destino = File(stateDir, nuevo)
        if (origen.isDirectory && !destino.exists() && origen.renameTo(destino)) {
            movimientos.add(origen to destino)
        }
        for (sufijo in listOf(".txt", ".txt.md5")) {
            val fViejo = File(filtersDir, "$viejo$sufijo")
            val fNuevo = File(filtersDir, "$nuevo$sufijo")
            if (fViejo.exists() && !fNuevo.exists() && fViejo.renameTo(fNuevo)) {
                movimientos.add(fViejo to fNuevo)
            }
        }
        return movimientos
    }
}
