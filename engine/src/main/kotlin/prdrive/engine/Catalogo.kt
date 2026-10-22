package prdrive.engine

import java.io.File

/**
 * Catalogo.kt — El catálogo del remoto del usuario (`remote.toml`, antes
 * `pairs.toml`), en su mitad de LEER.
 *
 * Espejo de `common/catalog.py` de prdrive. `sync_config.toml` dice qué
 * sincroniza ESTE dispositivo; el catálogo (`<remote>:<catalog_path>`) dice qué
 * parejas existen y es el mismo fichero para todos. En el paso 1 la app solo lo
 * lee —para ofrecer las parejas al emparejarse—, así que aquí no está la
 * ceremonia de `push()` ni el renombrado: lo que gobierna borrados en todos los
 * dispositivos no se escribe desde un teléfono hasta que haga falta.
 *
 * Lo que sí está, igual que en el escritorio:
 * - Los dos nombres: se lee el que haya, primero `remote.toml` ([candidatos]),
 *   y nunca se crea el otro.
 * - Leer nunca puede matar la pantalla: [load] intenta el remoto y, sin red,
 *   cae a la copia de `state/catalog.toml`.
 * - Una ruta que es una carpeta se explica, en vez de dejar que el TOML de
 *   varios ficheros concatenados diga algo que no se parece a la causa.
 *
 * **Quién habla con rclone se pasa** ([Ejecutar]): en el escritorio es un
 * proceso hijo y aquí una llamada a la biblioteca. El motor le pide lo mismo
 * que `catalog.py` le pide a la línea de órdenes —`cat` y `lsjson --stat`— y
 * espera lo mismo de vuelta, códigos de salida incluidos; traducirlo a RPC es
 * cosa de quien lo implementa. Así la lógica se prueba contra prdrive con un
 * rclone de mentira.
 */
object Catalogo {

    const val BAK_SUFFIX = ".bak"

    /** Nombre del fichero dentro de la carpeta del catálogo: sale de la ruta de fábrica. */
    val FICHERO: String = DEFAULT_CATALOG_PATH.substringAfterLast('/')

    /** El nombre de antes, que conserva un remoto hasta que se renombra. */
    const val FICHERO_ANTERIOR = "pairs.toml"

    /** Los dos nombres, en el orden en que se buscan: si están los dos, manda el nuevo. */
    val NOMBRES: List<String> = listOf(FICHERO, FICHERO_ANTERIOR)

    /**
     * Códigos con los que rclone dice que una ruta no existe: 3 carpeta, 4
     * fichero. `cat` de un fichero que no está sale con 3 (rclone busca una
     * carpeta con ese nombre); en un remoto de cubetas sale con 0 y vacío.
     */
    val RC_NO_EXISTE: Set<Int> = setOf(3, 4)

    /**
     * Para que un remoto muerto no congele la pantalla: el catálogo son unos
     * kilobytes, y aquí no se espera por ancho de banda sino por un servidor
     * que puede no estar. En la app van como `_config` del RPC.
     */
    val NET_FLAGS: List<String> = listOf(
        "--contimeout", "10s", "--timeout", "20s",
        "--retries", "1", "--low-level-retries", "2",
    )

    /** El `stamp` de una copia local cuyos metadatos no dicen cuándo se leyó. */
    const val SIN_FECHA = "fecha desconocida"

    /** Lo que contesta rclone: el `subprocess.CompletedProcess` del escritorio. */
    data class Resultado(val codigo: Int, val salida: String, val error: String)

    /**
     * Quien le pregunta a rclone: recibe los argumentos de una orden de rclone.
     *
     * Lo que dice rclone va en el [Resultado], con su código. Si no se ha podido
     * ni preguntar, se lanza `IOException`: es el `OSError` o el
     * `SubprocessError` (un timeout) del escritorio. Por si acaso, [load] tampoco
     * se cae con una `RuntimeException`, porque la pantalla que lo usa es la que
     * tiene que abrirse sin red.
     */
    fun interface Ejecutar {
        @Throws(java.io.IOException::class)
        fun ejecutar(args: List<String>): Resultado
    }

    /**
     * El catálogo tal como se ha leído, con de dónde y de cuándo. [endpoint] es
     * el fichero que se leyó de verdad, que con un remoto sin renombrar es su
     * `pairs.toml`.
     */
    data class Catalog(
        val raw: Map<String, Any?>,
        val text: String,
        val source: String,
        val stamp: String,
        val endpoint: String,
    ) {
        /** Solo se podría escribir sobre lo que se acaba de leer del remoto. */
        val editable: Boolean get() = source == "remote"

        /** La tabla `[defaults]` del catálogo. */
        val defaults: Map<String, Any?> get() = tablaDe(raw, "defaults") ?: emptyMap()

        /** Los nombres de las parejas del catálogo, en su orden. */
        val names: List<String>
            get() = parejasDe(raw).filter { verdad(it["name"]) }.map { strPy(it["name"]) }
    }

    /** `state/catalog.toml`: la última copia buena, para abrir sin red. */
    fun cacheToml(stateDir: File): File = File(stateDir, "catalog.toml")

    /** `state/catalog.json`: de dónde y de cuándo es esa copia. */
    fun cacheMeta(stateDir: File): File = File(stateDir, "catalog.json")

    /**
     * Dónde dice este dispositivo que está el catálogo (`remote:ruta`), según
     * sus `[defaults]`. Qué fichero se lee de verdad lo decide [candidatos].
     */
    fun endpoint(rawLocal: Map<String, Any?>? = null): String {
        val defaults = rawLocal?.let { tablaDe(it, "defaults") } ?: emptyMap()
        val remote = listOf(defaults["catalog_remote"], defaults["remote"])
            .firstOrNull { verdad(it) } ?: DEFAULT_REMOTE
        val ruta = defaults["catalog_path"].takeIf { verdad(it) } ?: DEFAULT_CATALOG_PATH
        return "${strPy(remote)}:${strPy(ruta)}"
    }

    /** Separa una ruta o un endpoint en su carpeta, con la barra, y su nombre. */
    fun partir(donde: String): kotlin.Pair<String, String> {
        val corte = maxOf(donde.lastIndexOf('/'), donde.lastIndexOf(':'))
        return donde.substring(0, corte + 1) to donde.substring(corte + 1)
    }

    /**
     * Dónde buscar el catálogo, por orden. Si la ruta nombra uno de los dos
     * nombres, son los dos en esa carpeta, `remote.toml` primero; si no, la ruta
     * tal cual.
     */
    fun candidatos(donde: String): List<String> {
        val (carpeta, nombre) = partir(donde)
        if (nombre !in NOMBRES) return listOf(donde)
        return NOMBRES.map { carpeta + it }
    }

    /**
     * `cat` del catálogo con la regla de los dos nombres: se pasa al siguiente
     * si rclone dice que no existe o si lo trae vacío (un remoto de cubetas). Con
     * cualquier otro fallo, sin red por ejemplo, se para: el siguiente tardaría
     * lo mismo en no llegar. Si ninguno trae nada, vale la primera lectura
     * vacía.
     *
     * @return lo que contestó el `cat` que cuenta y a qué endpoint se le hizo.
     */
    fun leer(ejecutar: Ejecutar, donde: String): kotlin.Pair<Resultado, String> {
        var vacio: kotlin.Pair<Resultado, String>? = null
        var ultimo: kotlin.Pair<Resultado, String>? = null
        for (cual in candidatos(donde)) {
            val res = ejecutar.ejecutar(listOf("cat", cual))
            ultimo = res to cual
            if (res.codigo == 0 && stripPy(res.salida).isNotEmpty()) return res to cual
            if (res.codigo == 0) {
                if (vacio == null) vacio = res to cual
            } else if (res.codigo !in RC_NO_EXISTE) {
                return res to cual
            }
        }
        return vacio ?: ultimo!!
    }

    /** Por qué no se ha podido leer el catálogo, nombrando los dos sitios si hace falta. */
    fun motivoLectura(donde: String, cual: String, res: Resultado, separador: String = ": "): String {
        val detalle = stripPy(res.error)
        val sitios = candidatos(donde)
        if (res.codigo in RC_NO_EXISTE && sitios.size > 1) {
            return "No hay catálogo en ${sitios.joinToString(" ni en ")}$separador$detalle"
        }
        return "No pude leer el catálogo $cual$separador$detalle"
    }

    /**
     * Qué tiene de malo una ruta de catálogo recién tecleada: sin red solo se
     * puede saber si nombra un `.toml`, y basta para el error de verdad, poner
     * la carpeta en vez del fichero.
     */
    fun problemaDeRuta(ruta: String?): String? {
        val limpia = stripPy(ruta ?: "")
        if (limpia.isEmpty() || limpia.lowercase().endsWith(".toml")) return null
        return "La ruta del catálogo tiene que ser la de su fichero .toml, no la de " +
            "la carpeta: «$limpia» no termina en .toml. Si el catálogo está " +
            "dentro, sería «${dentro(limpia)}»."
    }

    /** El fichero de ese nombre dentro de esa carpeta (vale para una ruta y para un endpoint). */
    fun dentro(ruta: String, nombre: String = FICHERO): String =
        if (ruta.isEmpty() || ruta.endsWith(":") || ruta.endsWith("/")) ruta + nombre else "$ruta/$nombre"

    /**
     * `lsjson --stat`: si esa ruta es una carpeta (`true`), un fichero (`false`)
     * o no se sabe (`null`). Un «no se sabe» nunca se convierte en diagnóstico:
     * uno falso es peor que ninguno.
     */
    private fun esCarpeta(ejecutar: Ejecutar, donde: String): Boolean? {
        val res = try {
            ejecutar.ejecutar(listOf("lsjson", "--stat", donde))
        } catch (_: ConfigError) {
            return null
        } catch (_: java.io.IOException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
        if (res.codigo != 0) return null
        val info = try {
            leerJson(res.salida)
        } catch (_: JsonError) {
            return null
        }
        return (info as? Map<*, *>)?.get("IsDir") as? Boolean
    }

    /**
     * Qué decirle al usuario si la ruta del catálogo es una carpeta. `rclone
     * cat` de una carpeta no falla: concatena todos los ficheros de dentro. Se
     * pregunta solo cuando algo huele mal —lo leído no sirve, o la ruta no
     * termina en `.toml`—, para no gastar una ida y vuelta en el camino bueno.
     */
    fun explicarCarpeta(ejecutar: Ejecutar, donde: String, fallo: Boolean = false): String? {
        val ruta = donde.substringAfter(':', "")
        if (!(fallo || problemaDeRuta(ruta) != null)) return null
        if (esCarpeta(ejecutar, donde) != true) return null
        val motivo = "La ruta del catálogo, $donde, es una carpeta y no un fichero: " +
            "tiene que apuntar al fichero del catálogo."
        for (nombre in NOMBRES) {
            if (esCarpeta(ejecutar, dentro(donde, nombre)) == false) {
                return "$motivo Dentro hay un $nombre, así que seguramente es " +
                    "«${dentro(ruta, nombre)}»."
            }
        }
        return "$motivo Por ejemplo «${dentro(ruta)}», si es ahí donde está."
    }

    private fun parse(texto: String, donde: String): Map<String, Any?> = try {
        loadRaw(texto)
    } catch (e: TomlError) {
        throw ConfigError("El catálogo $donde no es TOML válido: ${e.message}")
    }

    /**
     * Un solo hilo a la vez escribe o lee la copia local: son dos ficheros que
     * se escriben uno detrás de otro, y quien leyera entre los dos se llevaría
     * el texto de una lectura con la fecha de la anterior.
     */
    private val cerrojoCopia = Any()

    /** Guarda la copia local; que falle no es un error. */
    private fun escribirCopia(cat: Catalog, stateDir: File) {
        synchronized(cerrojoCopia) {
            if (!stateDir.isDirectory && !stateDir.mkdirs()) return
            if (!Store.writeText(cacheToml(stateDir), cat.text)) return
            val meta = linkedMapOf<String, Any?>("pulled_at" to cat.stamp, "endpoint" to cat.endpoint)
            val anterior = Store.readJson(cacheMeta(stateDir))
            val sobra = strOVacio(anterior["duplicado"])
            if (sobra.isNotEmpty() && partir(sobra).first == partir(cat.endpoint).first) {
                meta["duplicado"] = sobra
                // `anterior.get("duplicado_visto", "")`: un `null` que esté se queda.
                meta["duplicado_visto"] = if ("duplicado_visto" in anterior) anterior["duplicado_visto"] else ""
            }
            Store.writeJson(cacheMeta(stateDir), meta)
        }
    }

    /**
     * Borra de la copia local el aviso de que en la carpeta están los dos
     * nombres: se ha leído `pairs.toml` porque `remote.toml` no está. (Apuntarlo
     * lo hace quien sube o renombra, que en el teléfono todavía nadie.)
     */
    private fun olvidarDuplicado(stateDir: File) {
        synchronized(cerrojoCopia) {
            val meta = LinkedHashMap(Store.readJson(cacheMeta(stateDir)))
            if ("duplicado" !in meta) return
            meta.remove("duplicado")
            meta.remove("duplicado_visto")
            Store.writeJson(cacheMeta(stateDir), meta)
        }
    }

    private fun strOVacio(valor: Any?): String = if (verdad(valor)) strPy(valor) else ""

    /**
     * Lee el catálogo del remoto y lo cachea en [stateDir]. Lo que `cat` trae de
     * una carpeta no se cachea: no es el catálogo.
     *
     * @throws ConfigError si no se puede leer, no es TOML válido o la ruta es una carpeta.
     */
    fun pull(
        ejecutar: Ejecutar,
        rawLocal: Map<String, Any?>?,
        stateDir: File,
        reloj: () -> String = Store::stamp,
    ): Catalog {
        val where = endpoint(rawLocal)
        val (res, donde) = leer(ejecutar, where)
        if (res.codigo != 0) throw ConfigError(motivoLectura(where, donde, res))
        val texto = res.salida
        val raw = try {
            parse(texto, donde)
        } catch (e: ConfigError) {
            throw ConfigError(explicarCarpeta(ejecutar, donde, fallo = true) ?: e.message ?: "")
        }
        explicarCarpeta(ejecutar, donde)?.let { throw ConfigError(it) }
        val cat = Catalog(raw = raw, text = texto, source = "remote", stamp = reloj(), endpoint = donde)
        escribirCopia(cat, stateDir)
        if (donde != candidatos(where)[0]) olvidarDuplicado(stateDir)
        return cat
    }

    /** La última copia buena, sin tocar la red; `null` si no hay o no sirve. */
    fun cached(stateDir: File): Catalog? {
        val (texto, meta) = synchronized(cerrojoCopia) {
            val texto = try {
                cacheToml(stateDir).readText(Charsets.UTF_8)
            } catch (_: java.io.IOException) {
                return null
            }
            texto to Store.readJson(cacheMeta(stateDir))
        }
        val raw = try {
            loadRaw(texto)
        } catch (_: TomlError) {
            return null
        }
        return Catalog(
            raw = raw, text = texto, source = "cache",
            stamp = strOVacio(meta["pulled_at"]).ifEmpty { SIN_FECHA },
            endpoint = strOVacio(meta["endpoint"]).ifEmpty { endpoint() },
        )
    }

    /**
     * El catálogo y, si algo no ha ido bien, qué decirle al usuario. Nunca
     * lanza: sin remoto cae a la copia local (no editable); sin copia devuelve
     * `null`.
     */
    fun load(
        ejecutar: Ejecutar,
        rawLocal: Map<String, Any?>?,
        stateDir: File,
        reloj: () -> String = Store::stamp,
    ): kotlin.Pair<Catalog?, String?> {
        val motivo = try {
            return pull(ejecutar, rawLocal, stateDir, reloj) to null
        } catch (e: ConfigError) {
            stripPy(e.message ?: "")
        } catch (e: java.io.IOException) {
            // El `OSError` del escritorio: quien habla con rclone no ha podido.
            e.message ?: e.toString()
        } catch (e: RuntimeException) {
            // Fuera del contrato de [Ejecutar], pero «nunca lanza» es la promesa.
            e.message ?: e.toString()
        }
        val copia = cached(stateDir)
        if (copia != null) {
            return copia to "Sin conexión con el catálogo. Se enseña la copia local del " +
                "${copia.stamp}; no se puede editar el catálogo hasta que " +
                "vuelva la conexión.\n$motivo"
        }
        return null to "No hay catálogo ni copia local, así que solo se puede trabajar " +
            "con las parejas que ya tiene este dispositivo.\n$motivo"
    }

    /** Las parejas del catálogo por nombre; vacío si no hay catálogo. */
    fun pairsByName(cat: Catalog?): Map<String, Map<String, Any?>> {
        if (cat == null) return emptyMap()
        return parejasDe(cat.raw).filter { verdad(it["name"]) }.associateBy { strPy(it["name"]) }
    }

    /** La pareja del catálogo con ese nombre, o `null`. */
    fun findPair(cat: Catalog?, name: String): Map<String, Any?>? = pairsByName(cat)[name]

    /**
     * Las claves en que difieren dos parejas (o dos `[defaults]`), ordenadas
     * como las ordena Python. Se comparan con su `==` ([igualesPy]).
     */
    fun diffKeys(a: Map<String, Any?>?, b: Map<String, Any?>?): List<String> {
        val uno = a ?: emptyMap()
        val otro = b ?: emptyMap()
        return (uno.keys + otro.keys).filter { !igualesPy(uno[it], otro[it]) }.sortedWith(ORDEN_PY)
    }

    /**
     * El `==` de Python con lo que sale de un TOML: un booleano es un entero
     * (`True == 1`), un entero y un float se comparan por su valor exacto
     * (`2**53 + 1 != 2.0**53`), y las listas y las tablas elemento a elemento
     * (`[1] == [1.0]`). Un NaN no es igual a nada, ni a sí mismo (Python sí
     * daría por iguales dos listas con el MISMO objeto NaN dentro, por
     * identidad; de un TOML no salen así).
     */
    private fun igualesPy(a: Any?, b: Any?): Boolean {
        val na = numeroPy(a)
        val nb = numeroPy(b)
        if (na != null || nb != null) return na != null && nb != null && na.compareTo(nb) == 0
        // Lo que queda de los floats: un infinito es igual al del mismo signo; un NaN, a nada.
        if (a is Double || b is Double) return a is Double && b is Double && a.isInfinite() && a == b
        return when {
            a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { igualesPy(a[it], b[it]) }
            a is Map<*, *> && b is Map<*, *> -> a.keys == b.keys && a.keys.all { igualesPy(a[it], b[it]) }
            else -> a == b
        }
    }

    /** El valor exacto de un número de Python (un booleano también lo es); `null` si no es uno finito. */
    private fun numeroPy(v: Any?): java.math.BigDecimal? = when (v) {
        is Boolean -> if (v) java.math.BigDecimal.ONE else java.math.BigDecimal.ZERO
        is Double -> if (v.isNaN() || v.isInfinite()) null else java.math.BigDecimal(v)
        is Float -> if (v.isNaN() || v.isInfinite()) null else java.math.BigDecimal(v.toDouble())
        is java.math.BigInteger -> java.math.BigDecimal(v)
        is Number -> java.math.BigDecimal.valueOf(v.toLong())
        else -> null
    }
}
