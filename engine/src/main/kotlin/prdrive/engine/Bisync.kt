package prdrive.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
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
 *
 * Lo que NO se copia: `migrate_legacy_state()` (un formato de `state/` anterior
 * a que existiera esta app) y `programa_en_listado()` (un diagnóstico de la
 * «Reparación» del escritorio).
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
    // Réplica exacta de cmd/bisync/bilib/canonical.go (rclone v1.75.x):
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
    // v1.75.x), así que aquí tampoco.
    // -----------------------------------------------------------------------

    // El `\s` de canonical.go es el de RE2, que en Go es solo `[\t\n\f\r ]`. Se
    // escribe a mano porque ni el de Java (que añade `\x0b`) ni el de Android
    // (ICU: cualquier espacio Unicode) ni el de Python (también Unicode) son ese.
    // Ojo: aquí el motor se separa de `common/bisync.py` a propósito, y el que
    // manda es rclone: con un espacio de ancho completo (U+3000) en el nombre,
    // prdrive calcularía un prefijo que rclone no escribe.
    private val NO_CANONICAL = Regex("[\\t\\n\\f\\r \\\\/:?*]")

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

    /** `FsPath` del backend local: la ruta acabada en separador (en Android, `/`). */
    fun fsPathLocal(p: String): String = if (p.endsWith("/")) p else "$p/"

    /**
     * `FsPath` para un remote con nombre: `nombre:ruta/`.
     *
     * Casi siempre `Root()` es la ruta tal cual. Dos tipos de remote son la
     * excepción, y por eso se pasan los [tipos] del `rclone.conf`
     * ([tiposDeRemote]): uno `local` tiene por `Root()` la ruta limpia
     * ([raizLocal]), y uno `alias` ni siquiera es él: se escribe su destino
     * ([resolverExtremo]). Si pasa por un alias que no se puede seguir, se deja
     * como viene: es la mejor estimación, y [prefijoComprobable] avisa de que no
     * es de fiar.
     *
     * No se replica la rama de Windows de `FsPath` (el separador `\` y el
     * prefijo `\\?\`): en Android no se da.
     */
    fun fsPathRemote(s: String, tipos: Map<String, TipoRemote>): String =
        resolverExtremo(s, tipos) ?: if (s.endsWith("/")) s else "$s/"

    fun sessionName(path1: String, path2: String): String =
        stripHexString(canonicalPath(path1)) + ".." + stripHexString(canonicalPath(path2))

    /**
     * Nombre base (.path1.lst / .path2.lst) que rclone buscará para esta pareja.
     *
     * El lado local es siempre el remote `combine` ([DEFAULT_DEVICE_REMOTE]),
     * que para rclone es un remote como otro cualquiera: un config sin
     * `device_remote` lo rechaza [parseConfig] antes de llegar aquí, en vez de
     * calcular un nombre que no sería el que rclone busca.
     */
    fun expectedPrefix(pareja: Pareja, tipos: Map<String, TipoRemote>): String =
        sessionName(
            fsPathRemote(pareja.endpoint(pareja.mode.source), tipos),
            fsPathRemote(pareja.endpoint(pareja.mode.dest), tipos),
        )

    /**
     * Si [expectedPrefix] es el nombre que rclone escribirá y no una
     * estimación. No lo es cuando un extremo pasa por un alias que no se puede
     * seguir: comparar con él un baseline que rclone escribió con el destino
     * daría un «no es de esta pareja» falso, y quien diagnostica no debe acusar
     * con él.
     */
    fun prefijoComprobable(pareja: Pareja, tipos: Map<String, TipoRemote>): Boolean =
        listOf(pareja.mode.source, pareja.mode.dest)
            .all { resolverExtremo(pareja.endpoint(it), tipos) != null }

    // -----------------------------------------------------------------------
    // Tipos de remote
    //
    // Lo que ve `FsPath` (cmd/bisync/bilib/canonical.go) cuando el remote no es
    // uno corriente. prdrive lo contrastó con rclone v1.75.1; aquí solo la rama
    // POSIX, porque la app no corre en Windows.
    // -----------------------------------------------------------------------

    /** Cuántos alias encadenados se siguen antes de darlos por un ciclo. */
    const val TOPE_ALIAS = 8

    /**
     * Lo que hace falta saber de un remote del `rclone.conf` para nombrar su
     * extremo: su `type`, si lleva `nounc = true` (solo cuenta en un `local`, y
     * solo en Windows) y su opción `remote` (el destino de un `alias`).
     */
    data class TipoRemote(val tipo: String?, val nounc: Boolean, val destino: String)

    /** `{remote: TipoRemote}` de un `rclone.conf`, leído con [Pairing.parseRcloneConf]. */
    fun tiposDeRemote(rcloneConf: String): Map<String, TipoRemote> =
        Pairing.parseRcloneConf(rcloneConf).mapValues { (_, op) ->
            TipoRemote(op["type"], (op["nounc"] ?: "").lowercase() == "true", op["remote"] ?: "")
        }

    /**
     * El extremo `remote:ruta/` tal como lo escribe `FsPath`, o `null` si pasa
     * por un alias que no se puede seguir: sin `remote`, un ciclo ([TOPE_ALIAS]),
     * un destino que no está en el `rclone.conf` o una cadena de conexión.
     *
     * Un remote `alias` no tiene nombre propio: su `NewFs` devuelve el `Fs` del
     * destino (`cache.Get(ctx, fspath.JoinRootPath(opt.Remote, root))`,
     * backend/alias/alias.go). Un destino que es una ruta suelta es el backend
     * local; uno que es otro remote se nombra con la regla de su tipo. Los demás
     * envoltorios (`crypt`, `union`, `combine`…) devuelven su propio `Fs`, con
     * el nombre de su sección, y no necesitan nada de esto.
     */
    fun resolverExtremo(s: String, tipos: Map<String, TipoRemote>): String? {
        val corte = s.indexOf(':')
        if (corte < 0) return if (s.endsWith("/")) s else "$s/"
        return extremo(s.substring(0, corte), s.substring(corte + 1), tipos, 0)
    }

    private fun extremo(
        nombre: String,
        ruta: String,
        tipos: Map<String, TipoRemote>,
        saltos: Int,
    ): String? {
        val tipo = tipos[nombre] ?: TipoRemote(null, false, "")
        if (tipo.tipo == "alias") return extremoAlias(tipo, ruta, tipos, saltos)
        val raiz = if (tipo.tipo == "local") raizLocal(ruta) else ruta
        val extremo = "$nombre:$raiz"
        return if (extremo.endsWith("/")) extremo else "$extremo/"
    }

    /** Sigue un alias hasta su destino, con la raíz `ruta` que le llega a su `NewFs`. */
    private fun extremoAlias(
        tipo: TipoRemote,
        ruta: String,
        tipos: Map<String, TipoRemote>,
        saltos: Int,
    ): String? {
        if (tipo.destino.isEmpty() || saltos >= TOPE_ALIAS) return null
        val (destino, base) = partirRemote(tipo.destino) ?: return null
        val raiz = unirRaiz(base, ruta, destino.isNotEmpty())
        if (destino.isEmpty()) return fsPathLocal(raizLocal(raiz))
        if (destino !in tipos) return null
        return extremo(destino, raiz, tipos, saltos + 1)
    }

    /**
     * Réplica reducida de `fspath.Parse` (fs/fspath/path.go) para la opción
     * `remote` de un alias: `(nombre, ruta)`, con el nombre vacío si es una ruta
     * local; `null` si no hay nada o es una cadena de conexión (`:backend:ruta`,
     * o con parámetros `nombre,clave=valor:ruta`).
     */
    private fun partirRemote(texto: String): kotlin.Pair<String, String>? {
        if (texto.isEmpty() || texto.startsWith(":")) return null
        if (':' !in texto) return "" to texto
        for ((i, letra) in texto.withIndex()) {
            if (letra == '/' || letra == '\\') return "" to texto   // un `:` tras una barra es de la ruta
            if (letra == ',') return null
            if (letra == ':') return texto.substring(0, i) to texto.substring(i + 1)
        }
        return null
    }

    /** `posixpath.normpath`: la ruta sin `.`, sin `..` que se puedan quitar ni barras de más. */
    private fun normpath(ruta: String): String {
        if (ruta.isEmpty()) return "."
        val barras = when {
            !ruta.startsWith("/") -> 0
            ruta.startsWith("//") && !ruta.startsWith("///") -> 2
            else -> 1
        }
        val tramos = ArrayList<String>()
        for (tramo in ruta.split("/")) {
            if (tramo.isEmpty() || tramo == ".") continue
            if (tramo != ".." || (barras == 0 && tramos.isEmpty()) ||
                (tramos.isNotEmpty() && tramos.last() == "..")
            ) {
                tramos.add(tramo)
            } else if (tramos.isNotEmpty()) {
                tramos.removeAt(tramos.size - 1)
            }
        }
        return ("/".repeat(barras) + tramos.joinToString("/")).ifEmpty { "." }
    }

    /** `path.Clean` de Go: [normpath] sin guardar las dos barras de POSIX. */
    private fun limpiar(ruta: String): String {
        val limpia = normpath(ruta)
        return if (limpia.startsWith("//")) limpia.substring(1) else limpia
    }

    /** `path.Join` de Go: ignora lo vacío y limpia el resultado. */
    private fun unir(vararg partes: String): String {
        val llenas = partes.filter { it.isNotEmpty() }
        return if (llenas.isEmpty()) "" else limpiar(llenas.joinToString("/"))
    }

    /**
     * `fspath.JoinRootPath` (fs/fspath/path.go) con el remote ya separado de su
     * ruta: la raíz que llega al alias se hace absoluta antes de unirla
     * (`makeAbsolute`), para que no pueda salirse de él con `..`.
     */
    private fun unirRaiz(base: String, ruta: String, conNombre: Boolean): String {
        if (ruta.isEmpty()) return base
        val absoluta = limpiar("/$ruta")
        val relativa = if (ruta.startsWith("/")) absoluta else absoluta.substring(1)
        // Las rutas de red de Windows guardan sus dos barras; se conserva por fidelidad.
        if (!conNombre && base.startsWith("//")) return "/" + unir(base, relativa)
        val unida = unir(base, relativa)
        return if (conNombre && unida == ".") "" else unida
    }

    /**
     * El `Root()` del backend local de rclone (backend/local/local.go) en
     * Android, donde `encoder.OS` es `encoder.Base` (lib/encoder/os_other.go).
     *
     * rclone lee la ruta como si viniera en su codificación estándar:
     * `cleanRootPath` pasa cada tramo por `FromStandardName`
     * (`Base.Encode(Standard.Decode(tramo))`) y luego la limpia
     * (`filepath.Clean`: `a//b` y `a/./b` son `a/b`), y `Root()` la devuelve con
     * `ToStandardPath` (`Standard.Encode(Base.Decode(..))`). Como `Base.Decode`
     * deshace `Base.Encode`, cada tramo acaba siendo
     * `Standard.Encode(Standard.Decode(tramo))`.
     *
     * prdrive solo replica los tramos `.` y `..` (`．`/`．．`). Aquí también lo
     * demás: un carácter de control sale como su símbolo (`\u000b` es `␋`), y
     * una `‛` suelta sale doble. Lo dice el oráculo de rclone
     * (rclone/go/oraculo), y el spike lo vio en un dispositivo: rclone escribe
     * el prefijo así, y el que manda es él.
     */
    fun raizLocal(ruta: String): String {
        var limpia = normpath(ruta.ifEmpty { "." })
        if (limpia.startsWith("//")) limpia = limpia.substring(1)
        return limpia.split("/").joinToString("/") { codificarEstandar(decodificarEstandar(it)) }
    }

    /** `encoder.QuoteRune`: lo que va delante de un símbolo que ya estaba en el nombre. */
    private const val COMILLA = '‛'

    /** `encoder.symbolOffset`: el símbolo de NUL, y los de control van detrás. */
    private const val SIMBOLO = '␀'

    /**
     * `Standard.Encode` de un tramo: `EncodeZero | EncodeSlash | EncodeCtl |
     * EncodeDel | EncodeDot`. Un símbolo que ya estaba en el nombre se protege
     * con [COMILLA], para que la codificación se pueda deshacer.
     */
    private fun codificarEstandar(tramo: String): String {
        when (tramo) {
            "" -> return ""
            "." -> return "．"
            ".." -> return "．．"
            "．" -> return "$COMILLA．"
            "．．" -> return "$COMILLA．$COMILLA．"
        }
        val salida = StringBuilder()
        for (c in tramo) {
            when {
                c.code == 0 -> salida.append(SIMBOLO)
                c == SIMBOLO || c == COMILLA -> salida.append(COMILLA).append(c)
                c == '/' -> salida.append('／')
                c == '／' -> salida.append(COMILLA).append(c)
                c.code == 0x7f -> salida.append('␡')
                c == '␡' -> salida.append(COMILLA).append(c)
                c.code in 1..0x1f -> salida.append((SIMBOLO.code + c.code).toChar())
                c.code in SIMBOLO.code + 1..SIMBOLO.code + 0x1f -> salida.append(COMILLA).append(c)
                else -> salida.append(c)
            }
        }
        return salida.toString()
    }

    /**
     * `Standard.Decode` de un tramo (`MultiEncoder.Decode`,
     * lib/encoder/encoder.go): lo contrario de [codificarEstandar]. Un símbolo
     * vuelve a ser su carácter, salvo que lleve [COMILLA] delante, que entonces
     * se queda como está y la comilla se va. Una comilla delante de cualquier
     * otra cosa se queda, igual que una al final.
     */
    private fun decodificarEstandar(tramo: String): String {
        when (tramo) {
            "．" -> return "."
            "．．" -> return ".."
            "$COMILLA．" -> return "．"
            "$COMILLA．$COMILLA．" -> return "．．"
        }
        val salida = StringBuilder()
        var comillaPendiente = false
        for (c in tramo) {
            val tras = comillaPendiente
            comillaPendiente = false
            when {
                c == SIMBOLO -> salida.append(if (tras) c else '\u0000')
                c == COMILLA -> if (tras) salida.append(c) else comillaPendiente = true
                c == '／' -> salida.append(if (tras) c else '/')
                c == '␡' -> salida.append(if (tras) c else '\u007f')
                c.code in SIMBOLO.code + 1..SIMBOLO.code + 0x1f ->
                    salida.append(if (tras) c else (c.code - SIMBOLO.code).toChar())
                else -> {
                    if (tras) salida.append(COMILLA)
                    salida.append(c)
                }
            }
        }
        if (comillaPendiente) salida.append(COMILLA)
        return salida.toString()
    }

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

    /**
     * El contenido del fichero de filtros de la pareja. Las reglas van en orden
     * de prioridad: gana la primera que casa.
     */
    fun filtersContent(pareja: Pareja): String {
        val lineas = ArrayList<String>()
        lineas.add(FILTERS_HEADER)
        if (pareja.versions) {
            // No es filtrado: es la condición de rclone para que `.prversions/`
            // viva dentro de la pareja. Sin ella `--backup-dir1` solapa con
            // Path1 y la pasada muere con «destination and parameter to
            // --backup-dir mustn't overlap». Va PRIMERA: detrás de un `+ **/*.md`
            // no excluiría nada.
            lineas.add("- $VERSIONS_DIR/**")
        }
        // Las del código van antes que las del TOML: las del llavero son toda su
        // lista, y la de `.prdrive/` tiene que ganar a cualquier `+` de la pareja.
        lineas.addAll(pareja.reglas)
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
     * para no invalidar el md5 sin motivo. Cuando cambia se escribe en un
     * temporal y se renombra encima: un fichero a medias tendría otro md5 y se
     * leería como «los filtros han cambiado».
     */
    fun filtersFileFor(pareja: Pareja, filtersDir: File): File? {
        if (!pareja.wantsFiltersFile) return null
        val contenido = filtersContent(pareja)
        filtersDir.mkdirs()
        val fichero = File(filtersDir, "${pareja.name}.txt")
        if (!fichero.exists() || fichero.readText() != contenido) {
            val temporal = File.createTempFile("${pareja.name}.txt.", ".tmp", filtersDir)
            try {
                temporal.writeText(contenido)
                if (!temporal.renameTo(fichero)) fichero.writeText(contenido)
            } finally {
                temporal.delete()
            }
        }
        return fichero
    }

    /** Compara el fichero de filtros con el .md5 que dejó el último resync. */
    fun filtersState(ffile: File?): EstadoFiltros {
        if (ffile == null) return EstadoFiltros("ok", "sin fichero de filtros")
        val digest = md5(ffile.readBytes())
        val hashFile = File(ffile.path + ".md5")
        if (!hashFile.exists()) return EstadoFiltros("new", "${ffile.name} sin hash previo")
        if (stripPy(hashFile.readText()) != digest) {
            return EstadoFiltros(
                "changed",
                "${ffile.name} ha cambiado desde el último resync",
            )
        }
        return EstadoFiltros("ok", "${ffile.name} sin cambios")
    }

    private fun md5(datos: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(datos)
            .joinToString("") { hex(it.toInt() and 0xff, 2) }

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
        val nombres = (workdir.list() ?: emptyArray()).sortedWith(ORDEN_PY)

        // Los .lst-err no los limpia nadie (rclone solo renombra .lst -> .lst-err
        // al abortar; cmd/bisync/operations.go): si después hay un juego de
        // listados válido, el baseline es bueno y esos son residuo.
        //
        // Contra rclone v1.75.1: ante un error crítico `Bisync()` renombra los
        // dos listados a .lst-err («the prior listings are renamed to .lst-err
        // to lock out further runs»), y una interrupción que no se cierra bien
        // hace lo mismo con `markFailed()` (cmd/bisync/lockfile.go). Con
        // --recover la pasada siguiente vuelve a los .lst-old y rehace los .lst,
        // y los -err se quedan como estaban. Se explican, nunca se borran: lo que
        // hay en el workdir de bisync no es cosa nuestra.
        val path1 = nombres.filter { it.endsWith(PATH1_SUFFIX) }
        val path2 = nombres.filter { it.endsWith(PATH2_SUFFIX) }
        val errores = nombres.filter { it.endsWith(ERR_SUFFIX) }
        val residuo = if (errores.isEmpty()) {
            ""
        } else {
            " (+${errores.size} $ERR_SUFFIX: el baseline que rclone apartó al " +
                "abortar una pasada; ya no lo usa y se puede borrar a mano)"
        }

        if (path1.isNotEmpty() && path2.isNotEmpty()) {
            val pre1 = path1.map { it.removeSuffix(PATH1_SUFFIX) }.toSet()
            val pre2 = path2.map { it.removeSuffix(PATH2_SUFFIX) }.toSet()
            val comun = pre1 intersect pre2
            if (comun.size != 1 || pre1.size != 1 || pre2.size != 1) {
                return EstadoPareja(
                    "broken",
                    "varios juegos de listados: ${reprPy((pre1 + pre2).sortedWith(ORDEN_PY))}",
                    null,
                )
            }
            val prefijo = comun.first()
            return EstadoPareja("ok", "baseline '$prefijo'$residuo", prefijo)
        }

        if (errores.isNotEmpty()) {
            return EstadoPareja(
                "broken",
                "${errores.size} listado(s) $ERR_SUFFIX: rclone aparta así el baseline " +
                    "cuando una pasada aborta con un error crítico, para bloquear las " +
                    "siguientes",
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
     * Si un listado de bisync no trae ni un fichero ni una carpeta.
     *
     * Es `fileList.empty()` de rclone (`cmd/bisync/listing.go`, igual en v1.75.1
     * y v1.75.2) sobre lo que `loadListing()` deja en la lista: no cuentan las
     * líneas en blanco ni las que empiezan por `#` (la cabecera), y sí cuenta
     * cualquier otra. Una línea que rclone descartaría por rota cuenta aquí como
     * contenido, a propósito: decir «vacío» de más rehace una referencia que sí
     * tenía algo que proteger. Un listado que no se puede leer no está vacío.
     */
    private fun sinEntradas(lst: File): Boolean = try {
        lst.useLines(Charsets.UTF_8) { lineas -> lineas.none { it.isNotEmpty() && !it.startsWith("#") } }
    } catch (_: IOException) {
        false
    }

    /**
     * Si la pasada va a rehacer sola el baseline de la pareja, por estar vacío.
     *
     * Un baseline vacío es lo que deja un `--resync` sobre dos lados vacíos, y
     * rclone aborta con él toda pasada que no sea otro `--resync`
     * (`checkListing()`, «Empty prior Path1 listing», código 7) porque no sabe
     * distinguir una carpeta vacía de una sin montar. Con la referencia vacía no
     * queda nada que proteger, y la pasada la rehace. Tiene que cumplirse todo:
     *
     * - El baseline es de ESTA pareja: su prefijo es el esperado y ese prefijo
     *   se puede comprobar ([prefijoComprobable]). Rehacer contra unos extremos
     *   que han cambiado es decisión de una persona.
     * - La carpeta local existe. Sin ella, con baseline, la pasada aborta y no
     *   crea nada: un lado vacío se lee como «han borrado todo».
     * - Los DOS listados están vacíos. Si solo lo está uno, un lado se ha
     *   vaciado desde la última pasada buena y rclone debe pararla.
     *
     * A diferencia del escritorio, las rutas se pasan: el motor no sabe dónde
     * está el volumen.
     */
    fun referenciaVacia(
        pareja: Pareja,
        estado: EstadoPareja,
        workdir: File,
        carpetaLocal: File,
        tipos: Map<String, TipoRemote>,
    ): Boolean {
        if (!pareja.isBisync || !estado.hasBaseline) return false
        val prefijo = estado.prefix ?: return false
        if (!prefijoComprobable(pareja, tipos) || prefijo != expectedPrefix(pareja, tipos)) {
            return false
        }
        if (!carpetaLocal.exists()) return false
        return listOf(PATH1_SUFFIX, PATH2_SUFFIX).all { sinEntradas(File(workdir, prefijo + it)) }
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

    private val FORMATO_SELLO = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    /** El sello de un baseline apartado (`%Y%m%d_%H%M%S`), de ahora o de otra fecha. */
    fun selloApartado(cuando: java.time.LocalDateTime = java.time.LocalDateTime.now()): String =
        cuando.format(FORMATO_SELLO)

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
    fun shelveBaseline(stateDir: File, nombre: String, sello: String = selloApartado()): File? {
        val workdir = File(stateDir, nombre)
        if (!workdir.isDirectory) return null
        var destino = File(stateDir, "$nombre.old-$sello")
        var n = 1
        while (destino.exists()) {
            destino = File(stateDir, "$nombre.old-${sello}_$n")
            n++
        }
        // Como `Path.rename()`: si no se puede, es un error y no «no había baseline».
        if (!workdir.renameTo(destino)) throw IOException("No se pudo apartar $workdir a $destino")
        return destino
    }

    /**
     * Aparta los listados de una pareja como rclone aparta los de una pasada
     * que no ha podido cerrar bien: cada `*.path1.lst` y `*.path2.lst` del
     * [workdir] pasa a `<nombre>-err` ([ERR_SUFFIX]), borrando antes el `-err`
     * que hubiera (`markFailed`, cmd/bisync/lockfile.go). Sin ellos la pareja
     * queda `broken` ([pairState]) y la pasada siguiente pide `--resync`, que
     * no borra nada.
     *
     * A diferencia de `markFailed`, que no mira si lo ha conseguido, un listado
     * que no se puede apartar es un [IOException]: quien los aparta antes de
     * cambiar un extremo no puede seguir creyendo que lo ha hecho. Antes de
     * lanzarlo se prueba con los demás, porque con uno solo apartado la pareja
     * ya queda `broken`.
     *
     * @return los listados ya apartados (los `-err`), en orden; ninguno si no
     *   había workdir o no tenía listados.
     */
    fun apartarListados(workdir: File): List<File> {
        if (!workdir.exists()) return emptyList()
        val listados = workdir.listFiles { f -> f.name.endsWith(PATH1_SUFFIX) || f.name.endsWith(PATH2_SUFFIX) }
            ?: throw IOException("No se puede leer $workdir")
        val apartados = ArrayList<File>()
        var error: IOException? = null
        for (lst in listados.filter { it.isFile }.sortedBy { it.name }) {
            val err = File(lst.path + "-err")
            try {
                Files.deleteIfExists(err.toPath())
                Files.move(lst.toPath(), err.toPath())
                apartados.add(err)
            } catch (e: IOException) {
                if (error == null) error = e else error.addSuppressed(e)
            }
        }
        error?.let { throw it }
        return apartados
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
        fun mover(de: File, a: File) {
            if (de.renameTo(a)) {
                movimientos.add(de to a)
                return
            }
            // Media pareja renombrada es peor que ninguna: lo ya movido vuelve
            // a su sitio antes de contar el fallo. (prdrive deja lo movido.)
            for ((antes, ahora) in movimientos.asReversed()) ahora.renameTo(antes)
            throw IOException("No se pudo mover $de a $a")
        }
        val origen = File(stateDir, viejo)
        val destino = File(stateDir, nuevo)
        if (origen.isDirectory && !destino.exists()) mover(origen, destino)
        for (sufijo in listOf(".txt", ".txt.md5")) {
            val fViejo = File(filtersDir, "$viejo$sufijo")
            val fNuevo = File(filtersDir, "$nuevo$sufijo")
            if (fViejo.exists() && !fNuevo.exists()) mover(fViejo, fNuevo)
        }
        return movimientos
    }
}
