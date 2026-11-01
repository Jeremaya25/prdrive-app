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
 * Lo que el teléfono hace distinto no refleja a prdrive, y va aparte ([planAnadir],
 * [planQuitar], [anadir], [quitar]):
 *
 * - **La carpeta la elige el teléfono.** Es prdrive#196, adelantado: el
 *   catálogo describe el lado remoto y cada dispositivo elige su carpeta del
 *   volumen al usar una pareja. El `local` del catálogo, si lo trae, solo es la
 *   sugerencia ([sugerencia]). El plan es el de `plan_enable()` con la entrada
 *   del catálogo llevando la carpeta elegida, que es lo que hará #196, y un
 *   `state/<pareja>/` de antes se aparta siempre: la pareja empieza de cero con
 *   la carpeta nueva y su `--resync` («Elegir otra carpeta al volver a activar
 *   una pareja con el mismo nombre debe seguir pasando por el apartado de
 *   baseline», #196).
 * - **Lo que se avisa** (#196): un espejo con la carpeta que borraría (la de
 *   subida vacía, la de bajada con ficheros) y otra pareja en la misma carpeta,
 *   dentro o alrededor.
 * - **Lo que no se deja**: una carpeta que rompería una pareja que vive en una
 *   carpeta compartida ([Compartidas]: la redirección es por carpeta de primer
 *   nivel), una con caracteres de control (acabaría en el `rclone.conf`) y una
 *   que no es carpeta.
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
     * El plan de empezar a usar aquí una pareja del catálogo (`plan_enable`):
     * la entrada del catálogo, tal cual, al final de las del dispositivo.
     *
     * @throws ConfigError si el catálogo no la tiene, si ya está en este
     *   dispositivo o si el config resultante no vale.
     */
    fun planEnable(raw: Map<String, Any?>, cat: Catalogo.Catalog?, name: String): EditPlan {
        val entrada = Catalogo.findPair(cat, name)
            ?: throw ConfigError(
                "El catálogo no tiene ninguna pareja llamada '$name'. " +
                    "Créala primero ahí y luego elígela aquí.",
            )
        if (parejasCrudas(raw).any { (it as? Map<*, *>)?.get("name") == name }) {
            throw ConfigError("'$name' ya está en este dispositivo.")
        }

        val nuevo = Volumen.copiaProfunda(raw)
        nuevo["pair"] = parejasCrudas(nuevo) + Volumen.copiaProfunda(entrada)
        val consecuencias = arrayListOf(
            "Se empieza a usar '$name' en este dispositivo, tal y como está en el catálogo.",
            "La ruta local '${strPy(entrada["local"])}' se creará en la primera pasada si " +
                "todavía no existe.",
        )
        if (modoDe(entrada) == "bisync") {
            consecuencias.add(
                "Al ser bisync, la primera pasada pedirá un --resync para fijar la " +
                    "referencia (compara ambos lados; no borra por diferencias).",
            )
        }
        val avisos = listOfNotNull(mirrorWarning(modoDe(entrada)))

        parseConfig(nuevo)
        return EditPlan(nuevo, consecuencias, avisos)
    }

    // -----------------------------------------------------------------------
    // Lo del teléfono: la carpeta elegida (prdrive#196, adelantado)
    // -----------------------------------------------------------------------

    /** Hasta cuántos ficheros se cuentan al mirar una carpeta: más da igual cuántos. */
    const val TOPE_FICHEROS = 1000

    /**
     * Cómo está la carpeta elegida para una pareja, antes de añadirla.
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
     * raíz del volumen es `.`, como la de `ruta_local_relativa()`.
     */
    fun limpiarCarpeta(texto: String): String =
        stripPy(texto).replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            .joinToString("/").ifEmpty { "." }

    /**
     * La carpeta que se ofrece al añadir [nombre]: la del catálogo, si la trae y
     * vale, que es la pista de cómo la tienen los demás dispositivos; si no, una
     * con el nombre de la pareja.
     */
    fun sugerencia(cat: Catalogo.Catalog?, nombre: String): String {
        val local = Catalogo.findPair(cat, nombre)?.get("local") as? String
        if (local != null) {
            val limpia = limpiarCarpeta(local)
            if (problemaTexto(limpia) == null) return limpia
        }
        return limpiarCarpeta(nombre)
    }

    /** Por qué el texto de la carpeta no vale, mirando solo el texto; `null` si vale. */
    fun problemaTexto(local: String): String? {
        if (local.any { it.isISOControl() }) {
            return "La carpeta '${local.filter { !it.isISOControl() }}' lleva algún carácter de control " +
                "(un salto de línea, por ejemplo): acabaría escrita en el rclone.conf. Escríbela sin él."
        }
        return problemaLocal(local)
    }

    /** Cómo está [carpeta] en el disco ([Carpeta]), sin seguir enlaces. */
    fun mirarCarpeta(carpeta: File): Carpeta {
        val camino = carpeta.toPath()
        if (!Files.exists(camino, LinkOption.NOFOLLOW_LINKS)) return Carpeta(existe = false, esCarpeta = false, ficheros = 0)
        if (!Files.isDirectory(camino, LinkOption.NOFOLLOW_LINKS)) return Carpeta(existe = true, esCarpeta = false, ficheros = 0)
        var n = 0
        fun recorrer(d: Path) {
            Files.newDirectoryStream(d).use { entradas ->
                for (p in entradas) {
                    if (n > TOPE_FICHEROS) return
                    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) recorrer(p) else n++
                }
            }
        }
        recorrer(camino)
        return Carpeta(existe = true, esCarpeta = true, ficheros = n)
    }

    /** Cómo se dice una carpeta del volumen: la raíz no se llama «.». */
    private fun dicha(local: String): String = if (local == ".") "el volumen entero" else "'$local'"

    /** Si una ruta de tramos es la otra o cae dentro de ella. */
    private fun dentroDe(a: List<String>, b: List<String>): Boolean = a.size >= b.size && a.subList(0, b.size) == b

    private fun tramos(local: String): List<String> = local.split('/').filter { it.isNotEmpty() && it != "." }

    /**
     * Lo que avisa de las demás parejas del teléfono: la misma carpeta, una
     * dentro de la otra (lo de dentro lo sincronizarían las dos).
     */
    fun avisosDeOtras(config: Config, nombre: String, local: String): List<String> {
        val mia = tramos(local)
        val avisos = ArrayList<String>()
        for (otra in config.delUsuario) {
            if (otra.name == nombre) continue
            val suya = otra.tramosLocales
            when {
                mia == suya -> avisos.add(
                    "La pareja «${otra.name}» ya usa ${dicha(local)}: las dos sincronizarían los mismos " +
                        "ficheros, cada una con su carpeta del remoto.",
                )
                dentroDe(mia, suya) -> avisos.add(
                    "${dicha(local).replaceFirstChar { it.uppercase() }} está dentro de la carpeta de " +
                        "«${otra.name}» (${dicha(otra.local)}): lo de dentro lo sincronizarían las dos.",
                )
                dentroDe(suya, mia) -> avisos.add(
                    "${dicha(local).replaceFirstChar { it.uppercase() }} contiene la carpeta de " +
                        "«${otra.name}» (${dicha(otra.local)}): lo de dentro lo sincronizarían las dos.",
                )
            }
        }
        return avisos
    }

    /** Lo que avisa de un espejo según cómo esté su carpeta: lo que borraría la primera pasada. */
    fun avisoDeLaCarpeta(pareja: Pareja, carpeta: Carpeta): String? = when {
        pareja.mode.borraEnRemoto && carpeta.vacia ->
            "Es un espejo de subida y ${dicha(pareja.local)} " +
                (if (carpeta.existe) "está vacía" else "no existe") +
                ": la pasada borraría en el remoto todo lo que no esté en ella. No se crea sola: pon antes " +
                "en ella lo que quieres subir, y hasta entonces la pasada se parará."
        pareja.mode.name == "down-mirror" && !carpeta.vacia ->
            "Es un espejo de bajada y ${dicha(pareja.local)} ya tiene ${cuantos(carpeta)}: la pasada " +
                "borrará de ella lo que no esté en el remoto."
        else -> null
    }

    /** «1 fichero», «12 ficheros», «más de 1000 ficheros». */
    fun cuantos(carpeta: Carpeta): String = when {
        carpeta.masDe -> "más de $TOPE_FICHEROS ficheros"
        carpeta.ficheros == 1 -> "1 fichero"
        else -> "${carpeta.ficheros} ficheros"
    }

    /**
     * La entrada [nombre] de [cat] con su `local` puesto a [local], en su sitio
     * si lo traía y si no detrás del nombre: lo que guardará `sync_config.toml`.
     */
    fun conCarpeta(cat: Catalogo.Catalog, nombre: String, local: String): Catalogo.Catalog {
        val parejas = parejasCrudas(cat.raw).map { p ->
            val mapa = p as? Map<*, *>
            if (mapa == null || !verdad(mapa["name"]) || strPy(mapa["name"]) != nombre) return@map p
            val nueva = LinkedHashMap<String, Any?>()
            for ((k, v) in mapa) {
                nueva[k.toString()] = if (k == "local") local else v
                if (k == "name" && !mapa.containsKey("local")) nueva["local"] = local
            }
            if (!nueva.containsKey("local")) nueva["local"] = local
            nueva
        }
        val raw = LinkedHashMap(cat.raw)
        raw["pair"] = parejas
        return cat.copy(raw = raw)
    }

    /**
     * En qué ha cambiado la pareja [nombre] del catálogo entre dos lecturas,
     * sin contar `local`, que la elige el teléfono: lo que se enseñó al pensar
     * el plan y lo que hay al ejecutarlo.
     */
    fun cambiosDelCatalogo(antes: Catalogo.Catalog?, ahora: Catalogo.Catalog?, nombre: String): List<String> =
        Catalogo.diffKeys(
            Catalogo.findPair(antes, nombre)?.minus("local"),
            Catalogo.findPair(ahora, nombre)?.minus("local"),
        )

    /**
     * El plan de añadir [nombre] con su carpeta elegida.
     *
     * @property plan el de `plan_enable()` con la carpeta puesta: el config, lo
     *   que dice prdrive y su aviso de espejo.
     * @property local la carpeta, ya limpia ([limpiarCarpeta]).
     * @property carpeta cómo está en el disco.
     * @property apartar si hay un `state/<pareja>/` de antes que se aparta.
     * @property resyncYa si es bisync y su carpeta no tiene nada que pisar: el
     *   `--resync` se hace al añadirla, como al emparejar.
     * @property olvidarCompartida si `compartidas.json` tiene una entrada suelta
     *   con su nombre (de una baja que no la pudo olvidar): se olvida, porque la
     *   carpeta la acaba de elegir la persona, y es una del volumen.
     * @property consecuencias lo que añade el teléfono a las de [plan].
     * @property avisos lo que avisa el teléfono, además de los de [plan].
     */
    data class PlanAnadir(
        val nombre: String,
        val plan: EditPlan,
        val local: String,
        val carpeta: Carpeta,
        val apartar: Boolean,
        val resyncYa: Boolean,
        val consecuencias: List<String>,
        val avisos: List<String>,
        val olvidarCompartida: Boolean = false,
    ) {
        /** Todo lo que pasará, en orden: lo de prdrive y lo del teléfono. */
        val todasLasConsecuencias: List<String> get() = plan.consequences + consecuencias

        /** Todos los avisos: el de prdrive y los del teléfono. */
        val todosLosAvisos: List<String> get() = plan.warnings + avisos
    }

    /**
     * Piensa añadir [nombre] del catálogo con la carpeta [carpetaEscrita], sin
     * tocar nada.
     *
     * @param raw el `sync_config.toml` del volumen, en bruto.
     * @throws ConfigError si no se puede: la carpeta no vale, el catálogo no la
     *   tiene, ya está aquí, o dejaría sin carpeta a una pareja compartida.
     * @throws IOException si no se puede mirar la carpeta.
     */
    fun planAnadir(v: Volumen, raw: Map<String, Any?>, cat: Catalogo.Catalog?, nombre: String, carpetaEscrita: String): PlanAnadir {
        val local = limpiarCarpeta(carpetaEscrita)
        problemaTexto(local)?.let { throw ConfigError(it) }
        val plan = planEnable(raw, cat?.let { conCarpeta(it, nombre, local) }, nombre)

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

        val carpeta = mirarCarpeta(pareja.carpetaEn(v.raiz))
        if (carpeta.existe && !carpeta.esCarpeta) {
            throw ConfigError("${dicha(local).replaceFirstChar { it.uppercase() }} ya existe y no es una carpeta (es un fichero o un enlace). Elige otra.")
        }
        val apartar = File(v.state, nombre).isDirectory
        val resyncYa = pareja.isBisync && carpeta.vacia
        val consecuencias = ArrayList<String>()
        if (apartar) {
            consecuencias.add(
                "Tiene una referencia de antes (state/$nombre/): se apartará a state/$nombre.old-<fecha>/, sin " +
                    "borrarla, y la pareja empezará de cero con esta carpeta.",
            )
        }
        when {
            !pareja.isBisync -> Unit
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
        val avisos = listOfNotNull(avisoDeLaCarpeta(pareja, carpeta)) + avisosDeOtras(antes, nombre, local)
        return PlanAnadir(nombre, plan, local, carpeta, apartar, resyncYa, consecuencias, avisos, nombre in entradas)
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
     * antes de escribirlo, lo apartado vuelve a su sitio y el `rclone.conf` a lo
     * que era: no queda nada a medias. Un upstream de más en el `combine` no
     * haría nada, pero tampoco se deja.
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
        val movidos = apartar(v, if (p.apartar) listOf(p.nombre) else emptyList(), hechos)
        try {
            // Antes que el config: con él escrito, la entrada la redirigiría.
            if (p.nombre in entradas) Compartidas.escribir(v.compartidas, entradas - p.nombre)
            v.actualizarCombine(config, entradas - p.nombre)
            v.escribirConfig(p.plan.raw, cabecera)
        } catch (e: Exception) {
            devolver(movidos)
            if (v.leerRcloneConfSiSePuede() != confAntes) Store.writeText(v.rcloneConf, confAntes)
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
