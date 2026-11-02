package prdrive.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * EditarParejas.kt — Añadir a este teléfono una pareja del catálogo y quitarla.
 *
 * Espejo de la parte de `ui/pair_editor.py` de prdrive que decide cuáles de las
 * parejas del catálogo se usan en este dispositivo: `plan_enable()` («Usar
 * aquí»), `plan_remove()` («Quitar…») y su `EditPlan`, que es lo que se enseña
 * antes de confirmar. Como allí, pensar un cambio no toca nada: el plan dice
 * qué va a pasar, y solo si la persona dice que sí se ejecuta, primero el disco
 * y luego el config (`EditPlan.execute()`).
 *
 * `plan_enable()` es el de prdrive#202 (#196, todavía sin juntar): la carpeta la
 * elige cada dispositivo al usar la pareja, el catálogo ya no la lleva, y sus
 * valores esperados salen de esa rama (`vectores_196.json`). `plan_remove()` no
 * cambia allí, y los suyos salen de prdrive 0.7.7 (`vectores.json`).
 *
 * Lo que el teléfono hace distinto no refleja a prdrive, y va aparte ([planAnadir],
 * [planQuitar], [anadir], [quitar]):
 *
 * - **Lo que no se deja**: una carpeta que rompería una pareja que vive en una
 *   carpeta compartida ([Compartidas]: la redirección es por carpeta de primer
 *   nivel), una con caracteres de control (acabaría en el `rclone.conf`), una
 *   que no es carpeta, y dos espejos que prdrive solo avisa
 *   ([problemaDelEspejo]).
 * - **El `rclone.conf`**: el `combine` lleva un upstream por carpeta de primer
 *   nivel, así que añadir y quitar lo reescriben, como el config.
 * - **Quitar una pareja compartida** olvida su entrada de `compartidas.json`
 *   (la carpeta compartida no se toca) y aparta siempre su referencia. Una a
 *   medio mover no se quita.
 *
 * El motor no sabe de Android: quien recarga rclone tras reescribir el
 * `rclone.conf` lo pasa ([anadir], [quitar]), y el cerrojo y las pasadas de
 * fondo los pone la app.
 */
object EditarParejas {

    // -----------------------------------------------------------------------
    // Espejo de ui/pair_editor.py
    // -----------------------------------------------------------------------

    /**
     * El resultado de pensar un cambio, antes de ejecutarlo (`EditPlan`).
     *
     * @property raw el config resultante, en bruto.
     * @property consequences una línea por consecuencia: se enseña antes de confirmar.
     * @property warnings lo que conviene saber antes de confirmar.
     * @property shelve las parejas cuyo baseline apartar.
     * @property borrarFiltros los ficheros de filtros que sobran.
     */
    data class EditPlan(
        val raw: Map<String, Any?>,
        val consequences: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val shelve: List<String> = emptyList(),
        val borrarFiltros: List<File> = emptyList(),
    )

    /** Los modos que BORRAN en el destino lo que no está en el origen (`MIRROR_MODES`). */
    val MIRROR_MODES: List<String> = listOf("up-mirror", "down-mirror")

    /** El modo de una pareja en bruto, o el de fábrica (`_mode_of`). */
    private fun modoDe(pareja: Map<*, *>): Any? = if (pareja.containsKey("mode")) pareja["mode"] else DEFAULT_MODE

    /** El aviso de un modo espejo partido en título y cuerpo, o `null` (`aviso_espejo`). */
    fun avisoEspejo(mode: Any?): kotlin.Pair<String, String>? {
        if (mode !is String || mode !in MIRROR_MODES) return null
        val destino = if (MODES.getValue(mode).dest == "remote") "el remoto" else "el dispositivo"
        return "Modo '$mode': es un espejo" to
            "BORRA en $destino lo que no esté en el origen. Pruébalo antes con --dry-run."
    }

    /** El aviso de un modo espejo en una línea, o `null` (`mirror_warning`). */
    fun mirrorWarning(mode: Any?): String? = avisoEspejo(mode)?.let { (titulo, cuerpo) -> "$titulo, $cuerpo" }

    /** Las parejas del config en bruto, tal cual (`raw.get("pair", [])`). */
    private fun parejasCrudas(raw: Map<String, Any?>): List<Any?> = (raw["pair"] as? List<*>).orEmpty()

    /**
     * Un `pair` que no es una lista de tablas (`[[pair]]`). prdrive se cae ahí
     * con un `AttributeError` (llama a `.get()` de cada una, o `.append()` de una
     * tabla); el motor no puede caerse con un config mal escrito, así que lo
     * dice con un [ConfigError].
     */
    private fun comprobarParejas(raw: Map<String, Any?>) {
        val parejas = raw["pair"] ?: return
        if (parejas !is List<*> || parejas.any { it !is Map<*, *> }) {
            throw ConfigError(
                "El config tiene un 'pair' que no es una lista de tablas [[pair]]: así no se sabe " +
                    "qué parejas lleva. Arréglalo a mano antes de añadir o quitar ninguna.",
            )
        }
    }

    /**
     * La posición de la pareja con ese nombre (`pair_index`).
     *
     * @throws ConfigError si no hay ninguna.
     */
    fun pairIndex(raw: Map<String, Any?>, name: String): Int {
        parejasCrudas(raw).forEachIndexed { i, p -> if ((p as? Map<*, *>)?.get("name") == name) return i }
        throw ConfigError("No hay ninguna pareja llamada '$name'.")
    }

    /**
     * Lo que hay en disco atado a esa pareja: su workdir y su fichero de filtros
     * con su `.md5` (`bisync.pair_state_paths`), solo lo que existe.
     */
    fun pairStatePaths(name: String, stateDir: File, filtersDir: File): List<File> =
        listOf(File(stateDir, name), File(filtersDir, "$name.txt"), File(filtersDir, "$name.txt.md5"))
            .filter { it.exists() }

    /**
     * El plan de quitar una pareja de este dispositivo (`plan_remove`); no toca nada.
     *
     * @param cleanState si se aparta también su baseline y se borran sus filtros generados.
     * @throws ConfigError si no está, si es la última (el config se quedaría sin
     *   ninguna) o si el config resultante no vale.
     */
    fun planRemove(
        raw: Map<String, Any?>,
        name: String,
        cleanState: Boolean,
        stateDir: File,
        filtersDir: File,
    ): EditPlan {
        comprobarParejas(raw)
        val i = pairIndex(raw, name)
        val nuevo = Volumen.copiaProfunda(raw)
        val parejas = parejasCrudas(nuevo).toMutableList()
        parejas.removeAt(i)
        nuevo["pair"] = parejas
        if (parejas.isEmpty()) {
            throw ConfigError(
                "No se puede quitar la última pareja: el config se quedaría " +
                    "sin ninguna y sync.py no arrancaría.",
            )
        }

        // Si [daemon].pairs la nombraba, se cae también: el servicio intentaría
        // sincronizar una pareja que ya no existe y fallaría en cada ciclo.
        //
        // Solo si es una lista. Diferencia deliberada: con un texto, el `in` de
        // Python es de subcadena y la lista que deja es la de sus letras
        // (`pairs = "notas"` sale `["n", "o", "t", "a", "s"]`), que dejaría a
        // «Sincronizar sola» sin ninguna pareja. Aquí no se toca.
        @Suppress("UNCHECKED_CAST")
        val daemon = nuevo["daemon"] as? MutableMap<String, Any?>
        val nombradas = daemon?.get("pairs") as? List<*>
        if (daemon != null && nombradas != null && name in nombradas) {
            daemon["pairs"] = nombradas.filter { it != name }
        }

        val consecuencias = arrayListOf(
            "Se quita '$name' de este dispositivo. Sus datos NO se tocan, " +
                "ni aquí ni en el remoto: solo deja de sincronizarse. Si " +
                "está en el catálogo, sigue estándolo.",
        )
        var shelve = emptyList<String>()
        var borrarFiltros = emptyList<File>()
        val huerfanos = pairStatePaths(name, stateDir, filtersDir)
        if (huerfanos.isNotEmpty() && cleanState) {
            if (File(stateDir, name).isDirectory) {
                shelve = listOf(name)
                consecuencias.add("Su baseline se apartará a state/$name.old-<fecha>/; no se borra.")
            }
            borrarFiltros = huerfanos.filter { it.parentFile == filtersDir }
            if (borrarFiltros.isNotEmpty()) {
                consecuencias.add("Se borrarán sus filtros generados, que son datos derivados del TOML.")
            }
        } else if (huerfanos.isNotEmpty()) {
            consecuencias.add("Quedará sin usar: " + huerfanos.joinToString(", ") { it.name })
        }

        parseConfig(nuevo)
        return EditPlan(nuevo, consecuencias, shelve = shelve, borrarFiltros = borrarFiltros)
    }

    /**
     * Qué pasa con el baseline que dejó esta pareja otra vez (`_baseline_sobrante`):
     * con una pareja que se quitó sin limpiar su estado y se vuelve a usar, su
     * `state/<pareja>/` sigue ahí. Si su prefijo es el que espera la pareja con
     * la carpeta de ahora (la misma que antes), se reutiliza y no hay
     * `--resync`. Si es de otra carpeta, o está roto, reaprovecharlo haría que
     * bisync leyera como borrado lo que no estaba en la de antes: se aparta y la
     * pareja pide su `--resync`. Nunca se renombra.
     *
     * El prefijo es el del teléfono ([Bisync.expectedPrefix], el `combine` del
     * `rclone.conf`, sin el `{hash}` que el escritorio lleva en el entorno).
     *
     * @return si queda un baseline que la primera pasada va a usar, y lo que hay
     *   que apartar.
     */
    private fun baselineSobrante(
        consecuencias: MutableList<String>,
        config: Config,
        name: String,
        stateDir: File,
        tipos: Map<String, Bisync.TipoRemote>,
    ): kotlin.Pair<Boolean, List<String>> {
        val pareja = config.pairs.firstOrNull { it.name == name }
        if (pareja == null || !pareja.isBisync || !File(stateDir, name).isDirectory) return false to emptyList()
        val estado = Bisync.pairState(File(stateDir, pareja.workdir))
        if (estado.status == "broken" ||
            (!estado.prefix.isNullOrEmpty() && estado.prefix != Bisync.expectedPrefix(pareja, tipos))
        ) {
            consecuencias.add(
                "Quedó una referencia de otra vez en state/$name/ que no es de esta carpeta: " +
                    "se apartará a state/$name.old-<fecha>/ y la pareja pedirá un --resync. Es a " +
                    "propósito: reaprovecharla haría que bisync leyera como borrado lo que no " +
                    "estaba en la carpeta de antes.",
            )
            return false to listOf(name)
        }
        if (!estado.hasBaseline) return false to emptyList()
        consecuencias.add(
            "Quedó una referencia de otra vez en state/$name/, de esta misma carpeta, y se " +
                "reutiliza: la primera pasada no hace --resync. Lo que se haya borrado desde " +
                "entonces en un lado se borrará en el otro (con el freno de --max-delete).",
        )
        return true to emptyList()
    }

    /**
     * El plan de empezar a usar aquí una pareja del catálogo, en la carpeta
     * [local] (`plan_enable` de prdrive#202). La carpeta es de este dispositivo y
     * el catálogo no la lleva: la entrada que se escribe es la del catálogo con
     * ella, completa, como todas. Lo que se avisa de la carpeta (un espejo en la
     * equivocada, otra pareja en la misma) es [Carpetas], lo mismo que al
     * emparejar.
     *
     * @param local la carpeta elegida, ya relativa a la raíz del volumen.
     * @param raiz la raíz del volumen (el `DEVICE_ROOT` de prdrive): si la
     *   carpeta existe y qué tiene.
     * @param stateDir `state/`, por el baseline que dejara una vez anterior.
     * @param tipos los remotes del `rclone.conf`, para el prefijo de ese baseline.
     * @throws ConfigError si el catálogo no la tiene, ya está aquí, falta la
     *   carpeta o el config resultante no vale.
     */
    fun planEnable(
        raw: Map<String, Any?>,
        cat: Catalogo.Catalog?,
        name: String,
        local: String?,
        raiz: File,
        stateDir: File,
        tipos: Map<String, Bisync.TipoRemote> = emptyMap(),
    ): EditPlan {
        val entrada = Catalogo.findPair(cat, name)
            ?: throw ConfigError(
                "El catálogo no tiene ninguna pareja llamada '$name'. " +
                    "Créala primero ahí y luego elígela aquí.",
            )
        // Después del catálogo, que es lo primero que mira prdrive (y donde aún no se cae).
        comprobarParejas(raw)
        if (parejasCrudas(raw).any { (it as? Map<*, *>)?.get("name") == name }) {
            throw ConfigError("'$name' ya está en este dispositivo.")
        }
        val carpeta = stripPy(if (verdad(local)) local!! else "")
        if (carpeta.isEmpty()) throw ConfigError("Falta la carpeta de '$name' en este dispositivo.")

        val nueva = LinkedHashMap<String, Any?>()
        nueva["name"] = name
        nueva["local"] = carpeta
        for ((k, v) in Volumen.copiaProfunda(entrada)) {
            if (k != "name" && k !in Catalogo.DEL_DISPOSITIVO) nueva[k] = v
        }
        val mode = modoDe(nueva)
        val nuevo = Volumen.copiaProfunda(raw)
        nuevo["pair"] = parejasCrudas(nuevo) + nueva
        val config = parseConfig(nuevo)

        val consecuencias = arrayListOf(
            "Se empieza a usar '$name' en este dispositivo, como está en el catálogo, con " +
                "la carpeta '$carpeta'.",
        )
        val avisos = ArrayList<String>()
        val (reutiliza, shelve) = baselineSobrante(consecuencias, config, name, stateDir, tipos)
        val relativa = normalizarLocal(carpeta)
        val existe = (if (relativa.isEmpty()) raiz else File(raiz, relativa)).exists()
        if (reutiliza && !existe) {
            avisos.add(
                "La carpeta '$carpeta' no existe y '$name' tiene referencia: la pasada abortará " +
                    "sin crearla (vacía se leería como «se ha borrado todo»). Ponla con lo que " +
                    "tenía, o quita la pareja limpiando su estado y vuelve a usarla.",
            )
        } else if (mode != "up-mirror" && !existe) {
            consecuencias.add("La carpeta '$carpeta' se creará en la primera pasada.")
        }
        if (mode == "bisync" && !reutiliza) {
            consecuencias.add(
                "Al ser bisync, la primera pasada pedirá un --resync para fijar la " +
                    "referencia (compara ambos lados; no borra por diferencias).",
            )
        }
        mirrorWarning(mode)?.let { avisos.add(it) }
        val otras = LinkedHashMap<String, String>()
        for (p in parejasCrudas(raw)) {
            val m = p as? Map<*, *> ?: continue
            if (verdad(m["name"]) && "local" in m) otras[strPy(m["name"])] = strPy(m["local"])
        }
        avisos += Carpetas.avisos(name, strPy(mode), carpeta, raiz, otras)
        return EditPlan(nuevo, consecuencias, avisos, shelve = shelve)
    }

    // -----------------------------------------------------------------------
    // Lo del teléfono: la carpeta escrita, lo que no se deja y añadir
    // -----------------------------------------------------------------------

    /** Hasta cuántos ficheros se cuentan al mirar una carpeta: más da igual cuántos. */
    const val TOPE_FICHEROS = 1000

    /**
     * Cómo está la carpeta elegida para una pareja, antes de añadirla: para
     * decirlo, para saber si su `--resync` puede ir ya y para [problemaDelEspejo].
     *
     * @property existe si hay algo con ese nombre.
     * @property esCarpeta si es una carpeta (sin seguir enlaces).
     * @property ficheros cuántos ficheros hay dentro, contando las subcarpetas,
     *   hasta [TOPE_FICHEROS] más uno: [masDe] dice si se ha llegado al tope.
     */
    data class Carpeta(val existe: Boolean, val esCarpeta: Boolean, val ficheros: Int) {
        /** No hay nada que pisar: no existe, o no tiene ningún fichero. */
        val vacia: Boolean get() = !existe || (esCarpeta && ficheros == 0)

        /** Si hay más de [TOPE_FICHEROS]: no se han contado todos. */
        val masDe: Boolean get() = ficheros > TOPE_FICHEROS
    }

    /**
     * La carpeta escrita a mano, como la guarda el config: con `/`, sin barras
     * repetidas ni en los extremos, sin tramos `.` y sin espacios alrededor. La
     * raíz del volumen (`.` o `/` escritos) es `.`, como la de
     * `ruta_local_relativa()`; en blanco se queda en blanco, que es «Falta la
     * carpeta» ([Volumen.problemaCarpeta]) y no la raíz.
     */
    fun limpiarCarpeta(texto: String): String {
        val limpio = stripPy(texto)
        if (limpio.isEmpty()) return ""
        return limpio.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            .joinToString("/").ifEmpty { "." }
    }

    /**
     * Por qué el texto de la carpeta no vale, mirando solo el texto; `null` si
     * vale: un carácter de control (acabaría en el `rclone.conf`), y lo de
     * `problema_carpeta()` del instalador ([Volumen.problemaCarpeta]).
     */
    fun problemaTexto(local: String): String? {
        if (local.any { it.isISOControl() }) {
            return "La carpeta '${local.filter { !it.isISOControl() }}' lleva algún carácter de control " +
                "(un salto de línea, por ejemplo): acabaría escrita en el rclone.conf. Escríbela sin él."
        }
        return Volumen.problemaCarpeta(local)
    }

    /**
     * Lo que la pasada de una pareja de la raíz entera nunca lleva: la carpeta
     * del programa ([REGLA_SIN_PROGRAMA]) y la del llavero ([REGLA_SIN_LLAVERO]).
     * Mirar la raíz sin ellas es mirar lo que de verdad sincronizaría.
     */
    private val FUERA_DE_LA_RAIZ = setOf(CARPETA_PROGRAMA, LLAVERO_LOCAL)

    /**
     * Cómo está [carpeta] en el disco ([Carpeta]), sin seguir enlaces. Con
     * [raiz], es la del volumen entero, y no se cuenta lo que su pasada deja
     * fuera ([FUERA_DE_LA_RAIZ]): la raíz siempre tiene `.prdrive/`, y sin esto
     * un espejo de subida en ella parecería tener algo que subir.
     */
    fun mirarCarpeta(carpeta: File, raiz: Boolean = false): Carpeta {
        val camino = carpeta.toPath()
        if (!Files.exists(camino, LinkOption.NOFOLLOW_LINKS)) return Carpeta(existe = false, esCarpeta = false, ficheros = 0)
        if (!Files.isDirectory(camino, LinkOption.NOFOLLOW_LINKS)) return Carpeta(existe = true, esCarpeta = false, ficheros = 0)
        var n = 0
        fun recorrer(d: Path, primerNivel: Boolean) {
            Files.newDirectoryStream(d).use { entradas ->
                for (p in entradas) {
                    if (n > TOPE_FICHEROS) return
                    if (primerNivel && raiz && p.fileName.toString() in FUERA_DE_LA_RAIZ) continue
                    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) recorrer(p, false) else n++
                }
            }
        }
        recorrer(camino, true)
        return Carpeta(existe = true, esCarpeta = true, ficheros = n)
    }

    /** Cómo se dice una carpeta del volumen: la raíz no se llama «.». */
    private fun dicha(local: String): String = if (local == ".") "el volumen entero" else "'$local'"

    /**
     * Por qué un espejo no se puede añadir así, o `null`: lo que su primera
     * pasada borraría en el remoto sin que nadie lo haya pedido.
     *
     * - **Un espejo de subida con su carpeta ya creada y vacía** (sin ningún
     *   fichero, aunque tenga subcarpetas; en la raíz entera, sin contar
     *   `.prdrive/` ni `.keychain/`, que su pasada deja fuera). La pasada solo se
     *   para si el origen no existe (`sync.py`, y [Pasada] igual); vacío,
     *   `rclone sync` borra en el remoto todo lo que no está en él, con
     *   `max-delete` de único freno.
     * - **Un espejo de bajada que se solapa con otra pareja** (la misma carpeta,
     *   dentro o alrededor, como [Carpetas.solapadas]), o cualquier pareja que se
     *   solape con uno. El espejo borra de su carpeta lo que no está en su
     *   remoto, también los ficheros de la otra, y la bisync de la otra lleva
     *   esos borrados a su remoto.
     *
     * prdrive#202 (`common/carpetas.py`) solo los avisa; el teléfono no los deja.
     */
    fun problemaDelEspejo(config: Config, pareja: Pareja, carpeta: Carpeta): String? {
        if (pareja.mode.borraEnRemoto && carpeta.existe && carpeta.vacia) {
            return "Es un espejo de subida y ${dicha(pareja.local)} está vacía: la primera pasada borraría en " +
                "el remoto todo lo que no esté en ella. Pon antes en ella lo que quieres subir, o elige una " +
                "carpeta que no exista todavía (la pasada se parará hasta que la crees)."
        }
        for (otra in config.delUsuario) {
            if (otra.name == pareja.name) continue
            if (Carpetas.solapadas(mapOf(otra.name to otra.local), pareja.local).isEmpty()) continue
            val espejo = when {
                pareja.mode.name == "down-mirror" -> pareja
                otra.mode.name == "down-mirror" -> otra
                else -> continue
            }
            val la = if (espejo === pareja) otra else pareja
            return "«${espejo.name}» es un espejo de bajada y su carpeta se solapa con la de «${la.name}» " +
                "(${dicha(pareja.local)} y ${dicha(otra.local)}): el espejo borraría los ficheros de «${la.name}» " +
                "que no estén en su remoto, y la pasada de «${la.name}» los borraría también del suyo. Elige " +
                "una carpeta aparte."
        }
        return null
    }

    /** «1 fichero», «12 ficheros», «más de 1000 ficheros». */
    fun cuantos(carpeta: Carpeta): String = when {
        carpeta.masDe -> "más de $TOPE_FICHEROS ficheros"
        carpeta.ficheros == 1 -> "1 fichero"
        else -> "${carpeta.ficheros} ficheros"
    }

    /**
     * En qué ha cambiado la pareja [nombre] del catálogo entre dos lecturas: lo
     * que se enseñó al pensar el plan y lo que hay al ejecutarlo. Sin `local`,
     * que es de cada dispositivo ([Catalogo.diffKeys]).
     */
    fun cambiosDelCatalogo(antes: Catalogo.Catalog?, ahora: Catalogo.Catalog?, nombre: String): List<String> =
        Catalogo.diffKeys(Catalogo.findPair(antes, nombre), Catalogo.findPair(ahora, nombre))

    /**
     * El plan de añadir [nombre] con su carpeta elegida.
     *
     * @property plan el de `plan_enable()`: el config, lo que se aparta, lo que
     *   dice prdrive y sus avisos ([Carpetas] y el de espejo).
     * @property local la carpeta, ya limpia ([limpiarCarpeta]).
     * @property carpeta cómo está en el disco.
     * @property reutiliza si la pareja vuelve con una referencia de antes de esta
     *   misma carpeta, que su primera pasada usa (sin `--resync`).
     * @property resyncYa si es bisync, empieza de cero y su carpeta no tiene nada
     *   que pisar: el `--resync` se hace al añadirla, como al emparejar.
     * @property consecuencias lo que añade el teléfono a las de [plan].
     * @property olvidarCompartida si `compartidas.json` tiene una entrada suelta
     *   con su nombre (de una baja que no la pudo olvidar): se olvida, porque la
     *   carpeta la acaba de elegir la persona, y es una del volumen.
     */
    data class PlanAnadir(
        val nombre: String,
        val plan: EditPlan,
        val local: String,
        val carpeta: Carpeta,
        val reutiliza: Boolean,
        val resyncYa: Boolean,
        val consecuencias: List<String>,
        val olvidarCompartida: Boolean = false,
    ) {
        /** Todo lo que pasará, en orden: lo de prdrive y lo del teléfono. */
        val todasLasConsecuencias: List<String> get() = plan.consequences + consecuencias

        /** Los avisos, los de prdrive. */
        val todosLosAvisos: List<String> get() = plan.warnings
    }

    /**
     * Piensa añadir [nombre] del catálogo con la carpeta [carpetaEscrita], sin
     * tocar nada.
     *
     * @param raw el `sync_config.toml` del volumen, en bruto.
     * @throws ConfigError si no se puede: la carpeta no vale, el catálogo no la
     *   tiene, ya está aquí, dejaría sin carpeta a una pareja compartida, o es un
     *   espejo que borraría ([problemaDelEspejo]).
     * @throws IOException si no se puede mirar la carpeta.
     */
    fun planAnadir(v: Volumen, raw: Map<String, Any?>, cat: Catalogo.Catalog?, nombre: String, carpetaEscrita: String): PlanAnadir {
        val local = limpiarCarpeta(carpetaEscrita)
        problemaTexto(local)?.let { throw ConfigError(it) }
        val tipos = Bisync.tiposDeRemote(v.leerRcloneConf())
        val plan = planEnable(raw, cat, nombre, local, v.raiz, v.state, tipos)

        val entradas = Compartidas.leer(v.compartidas)
        val antes = parseConfig(raw).conCompartidas(entradas, v.raiz)
        val despues = parseConfig(plan.raw).conCompartidas(entradas - nombre, v.raiz)
        val pareja = despues.pairs.first { it.name == nombre }
        // Una pareja compartida se redirige por su carpeta de primer nivel: otra
        // dentro de ella, o una de la raíz entera, la dejaría sin redirección, y
        // sus pasadas pararían (la carpeta privada está vacía).
        for (compartida in despues.pairs) {
            val motivo = compartida.problemaCompartida ?: continue
            if (antes.pairs.firstOrNull { it.name == compartida.name }?.problemaCompartida != null) continue
            throw ConfigError(
                "Con ${dicha(local)}, «${compartida.name}», que vive en una carpeta compartida, dejaría de " +
                    "poder vivir ahí: $motivo Elige otra carpeta.",
            )
        }
        v.problemaContencion(pareja)?.let { throw ConfigError(it) }
        // El `combine` con ella: dos parejas que pidan el mismo upstream para
        // carpetas distintas se rechazan aquí, antes de enseñar nada.
        despues.seccionCombine(v.raiz.absolutePath)

        val carpeta = mirarCarpeta(pareja.carpetaEn(v.raiz), raiz = pareja.esRaiz && pareja.carpetaExterna == null)
        if (carpeta.existe && !carpeta.esCarpeta) {
            throw ConfigError("${dicha(local).replaceFirstChar { it.uppercase() }} ya existe y no es una carpeta (es un fichero o un enlace). Elige otra.")
        }
        problemaDelEspejo(despues, pareja, carpeta)?.let { throw ConfigError(it) }
        // La que `plan_enable()` reutiliza: bisync, con `state/` y sin nada que apartar.
        val reutiliza = pareja.isBisync && plan.shelve.isEmpty() && File(v.state, nombre).isDirectory &&
            Bisync.pairState(v.workdir(pareja)).hasBaseline
        val resyncYa = pareja.isBisync && !reutiliza && carpeta.vacia
        val consecuencias = ArrayList<String>()
        when {
            !pareja.isBisync || reutiliza -> Unit
            resyncYa -> consecuencias.add(
                "Como ${dicha(local)} " + (if (carpeta.existe) "está vacía" else "no existe todavía") +
                    ", ese --resync se hace ya, al añadirla: con un lado vacío no hay nada que pisar, y baja lo " +
                    "del remoto.",
            )
            else -> consecuencias.add(
                "${dicha(local).replaceFirstChar { it.uppercase() }} ya tiene ${cuantos(carpeta)}, así que ese " +
                    "--resync no se hace solo: lo pedirá su ficha («Rehacer la referencia»), que dice antes lo " +
                    "que va a pasar. Junta los dos lados y no borra nada.",
            )
        }
        return PlanAnadir(nombre, plan, local, carpeta, reutiliza, resyncYa, consecuencias, nombre in entradas)
    }

    /**
     * Por qué no se puede sembrar el volumen con estas carpetas, o `null`: lo que
     * no deja seguir al emparejar. Por pareja, la carpeta escrita
     * ([problemaTexto]: `problema_carpeta()` del instalador y los caracteres de
     * control); y con todas, los espejos que borrarían ([problemaDelEspejo]), en
     * un volumen que aún no tiene nada.
     */
    fun problemaAlSembrar(raiz: File, catalogo: Map<String, Any?>, elegidas: List<String>, locales: Map<String, String>): String? {
        for (nombre in elegidas) {
            problemaTexto(stripPy(locales[nombre].orEmpty()))?.let { return "«$nombre»: $it" }
        }
        val config = parseConfig(Volumen.deviceConfig(catalogo, elegidas, "", locales))
        for (pareja in config.delUsuario) {
            problemaDelEspejo(config, pareja, mirarCarpeta(pareja.carpetaEn(raiz), raiz = pareja.esRaiz))?.let { return it }
        }
        return null
    }

    /**
     * El plan de quitar [nombre] del teléfono.
     *
     * @property plan el de `plan_remove()`.
     * @property compartida su entrada de `compartidas.json`, si vive en una
     *   carpeta compartida: se olvida, y la carpeta no se toca.
     * @property limpiar si se aparta su referencia y se borran sus filtros (con
     *   [compartida], siempre).
     * @property consecuencias lo que añade el teléfono a las de [plan].
     */
    data class PlanQuitar(
        val nombre: String,
        val plan: EditPlan,
        val compartida: Compartidas.Entrada?,
        val limpiar: Boolean,
        val consecuencias: List<String>,
    ) {
        val todasLasConsecuencias: List<String> get() = plan.consequences + consecuencias
    }

    /** Lo que se dice de una pareja a medio mover, que no se quita. */
    fun aMedioMover(nombre: String): String =
        "«$nombre» está a medio mover a una carpeta compartida: termina de moverla (o devuélvela al volumen) " +
            "antes de quitarla."

    /**
     * Piensa quitar [nombre] del teléfono, sin tocar nada.
     *
     * @param limpiar lo que se marca en la hoja; una pareja compartida aparta su
     *   referencia siempre: si vuelve, será contra otra carpeta con el mismo
     *   nombre de sesión.
     * @throws ConfigError si no se puede: no está, es la última, está a medio
     *   mover, o `compartidas.json` no se lee.
     */
    fun planQuitar(v: Volumen, raw: Map<String, Any?>, nombre: String, limpiar: Boolean): PlanQuitar {
        val entrada = Compartidas.leer(v.compartidas)[nombre]
        if (entrada?.estado == Compartidas.Estado.ENTRANDO) throw ConfigError(aMedioMover(nombre))
        val conLimpieza = limpiar || entrada != null
        val plan = planRemove(raw, nombre, conLimpieza, v.state, v.filtros)
        val consecuencias = ArrayList<String>()
        if (entrada != null) {
            consecuencias.add(
                "Vive en una carpeta compartida (${entrada.ruta}): esa carpeta se queda como está, con sus " +
                    "ficheros. Bórrala tú cuando ya no la quieras.",
            )
            consecuencias.add(
                "Su referencia se aparta siempre: si vuelves a añadirla, será con otra carpeta y tendrá que " +
                    "empezar de cero.",
            )
        }
        return PlanQuitar(nombre, plan, entrada, conLimpieza, consecuencias)
    }

    // -----------------------------------------------------------------------
    // Ejecutar, en el orden de EditPlan.execute()
    // -----------------------------------------------------------------------

    /**
     * Aparta los baselines de [nombres] y devuelve lo movido (de dónde, adónde),
     * para poder devolverlo. Si uno no se puede, devuelve lo ya movido y lanza.
     */
    private fun apartar(v: Volumen, nombres: List<String>, hechos: MutableList<String>): List<kotlin.Pair<File, File>> {
        val movidos = ArrayList<kotlin.Pair<File, File>>()
        try {
            for (nombre in nombres) {
                val destino = Bisync.shelveBaseline(v.state, nombre) ?: continue
                movidos.add(File(v.state, nombre) to destino)
                hechos.add("Referencia apartada en state/${destino.name}/")
            }
        } catch (e: IOException) {
            devolver(movidos)
            throw e
        }
        return movidos
    }

    /** Devuelve a su sitio lo apartado, del último al primero; lo que no se puede, se queda. */
    private fun devolver(movidos: List<kotlin.Pair<File, File>>) {
        for ((origen, destino) in movidos.asReversed()) destino.renameTo(origen)
    }

    /**
     * Añade la pareja de [p] al teléfono: aparta su `state/` de antes, reescribe
     * el `combine` del `rclone.conf` y, el último, el `sync_config.toml`; luego
     * [recargar] (rclone guarda los remotes abiertos y seguiría con el `combine`
     * de antes).
     *
     * El config va el último por lo mismo que en `EditPlan.execute()`: la
     * combinación peligrosa es «config nuevo con baseline viejo». Si algo falla
     * antes de escribirlo, lo apartado vuelve a su sitio, y el `rclone.conf` y
     * `compartidas.json` a lo que eran. Lo que no se pueda devolver se queda: un
     * baseline apartado de más pide un `--resync`, y un upstream o una entrada
     * sin pareja no hacen nada.
     *
     * Sin la copia `sync_config.toml.bak` que deja `config_file.save()`: el
     * config del teléfono se escribe siempre con [Volumen.escribirConfig], que
     * no la hace, y el que vale es el de [p].
     *
     * @param cabecera la del `sync_config.toml` de ahora ([headerOf]), que se conserva.
     * @return lo hecho, para contarlo.
     */
    fun anadir(v: Volumen, p: PlanAnadir, cabecera: String, recargar: () -> Unit): List<String> {
        // Lo que puede fallar al generarlo, antes de tocar el disco.
        dumpsChecked(p.plan.raw, cabecera)
        val config = parseConfig(p.plan.raw)
        val entradas = Compartidas.leer(v.compartidas)
        val confAntes = v.leerRcloneConfSiSePuede() ?: throw IOException("No he podido leer ${v.rcloneConf}")
        val hechos = ArrayList<String>()
        val movidos = apartar(v, p.plan.shelve, hechos)
        try {
            // Antes que el config: con él escrito, la entrada la redirigiría.
            if (p.nombre in entradas) Compartidas.escribir(v.compartidas, entradas - p.nombre)
            v.actualizarCombine(config, entradas - p.nombre)
            v.escribirConfig(p.plan.raw, cabecera)
        } catch (e: Exception) {
            devolver(movidos)
            // Devolverlos puede fallar también: lo que quede no hace nada (arriba).
            if (v.leerRcloneConfSiSePuede() != confAntes) Store.writeText(v.rcloneConf, confAntes)
            if (p.nombre in entradas) {
                try {
                    Compartidas.escribir(v.compartidas, entradas)
                } catch (_: IOException) {
                    // Una entrada sin pareja no redirige nada.
                }
            }
            throw e
        }
        hechos.add("«${p.nombre}» añadida, en ${dicha(p.local)}")
        recargar()
        return hechos
    }

    /**
     * Quita la pareja de [p] del teléfono, en el orden de `EditPlan.execute()`:
     * aparta su referencia si se pidió, escribe el `sync_config.toml` sin ella y,
     * ya escrito, olvida su carpeta compartida, rehace el `combine` y borra sus
     * filtros generados (datos derivados del TOML: si algo hubiera fallado
     * antes, seguirían haciendo falta). Luego [recargar].
     *
     * Lo de después del config ya no deshace la baja: si falla, se cuenta en lo
     * hecho. Una entrada de `compartidas.json` sin pareja no redirige nada, y un
     * upstream de más en el `combine` tampoco.
     *
     * @return lo hecho, para contarlo.
     */
    fun quitar(v: Volumen, p: PlanQuitar, cabecera: String, recargar: () -> Unit): List<String> {
        dumpsChecked(p.plan.raw, cabecera)
        val config = parseConfig(p.plan.raw)
        val entradas = Compartidas.leer(v.compartidas)
        val hechos = ArrayList<String>()
        val movidos = apartar(v, p.plan.shelve, hechos)
        try {
            v.escribirConfig(p.plan.raw, cabecera)
        } catch (e: Exception) {
            devolver(movidos)
            throw e
        }
        hechos.add("«${p.nombre}» quitada de este teléfono")
        val quedan = entradas - p.nombre
        if (p.nombre in entradas) {
            try {
                Compartidas.escribir(v.compartidas, quedan)
                hechos.add("Olvidada su carpeta compartida (${p.compartida?.ruta ?: entradas.getValue(p.nombre).ruta})")
            } catch (e: IOException) {
                hechos.add("No he podido olvidar su carpeta compartida (${e.message}): no hace nada sin la pareja")
            }
        }
        try {
            v.actualizarCombine(config, quedan)
        } catch (e: IOException) {
            hechos.add("No he podido rehacer el rclone.conf (${e.message}): le sobra su carpeta, que no hace nada")
        }
        for (f in p.plan.borrarFiltros) {
            try {
                Files.deleteIfExists(f.toPath())
                hechos.add("Borrado filters/${f.name}")
            } catch (_: IOException) {
                // Como `unlink(missing_ok=True)` dentro de un `except OSError: pass`.
            }
        }
        recargar()
        return hechos
    }
}
