package prdrive.engine

import java.io.File
import java.time.LocalDateTime

/**
 * Catalog.kt — El catálogo de parejas, que vive en el remoto del usuario.
 *
 * Espejo de `common/catalog.py`, **con la mitad de escribir fuera**: en el
 * paso 1 el catálogo solo se lee. Eso no es una versión recortada por prisa,
 * es lo que dice el plan — el alta y la baja de una pareja ocurren en un
 * dispositivo de escritorio, y aquí un móvil elige de lo que hay. Así que
 * nada de la ceremonia de `push()` (generar y verificar el texto, releer el
 * remoto, dejar el `.bak`): esa función no existe todavía, y el día que
 * exista tendrá que traerse la ceremonia entera.
 *
 * Dos cosas de `catalog.py` que sí se conservan, porque son las que gobiernan
 * el módulo:
 *
 *  - **Leer nunca puede matar la pantalla.** [cargar] intenta el remoto y, si
 *    no hay red, cae a la copia de `state/catalog.toml`. Sin copia devuelve
 *    null y un aviso, y la pantalla se abre igual con lo que el móvil ya
 *    tiene. No lanza.
 *  - **Un catálogo que viene de la copia NO es editable** ([editable]).
 *    Escribir partiendo de una copia local sería escribir a ciegas encima de
 *    lo que otro dispositivo haya hecho mientras tanto.
 *
 * ## Cómo se lee un fichero del remoto sin línea de órdenes
 *
 * En el escritorio esto es `rclone cat nas:/prdrive-catalog/pairs.toml`. Aquí
 * no hay `cat`: `core/command` no sirve desde la biblioteca (ver `PLAN.md`), y
 * el rc no tiene ningún método que devuelva el contenido de un fichero. Lo que
 * sí hay es `operations/copyfile` (`fs/operations/rc.go`), que copia de un
 * `Fs` a otro — y el backend `local` está compilado dentro, así que el destino
 * es un fichero del volumen y después se lee. Sale gratis: ese fichero es
 * justo la copia local que había que guardar.
 *
 * Y aquí `_async` **no** hace falta, al contrario que en una pasada: un error
 * de `operations/copyfile` no trae ninguna salida que se pueda perder, solo su
 * mensaje, y ese sí viaja en el JSON aunque el estado no sea 200.
 */
data class Catalogo(
    /** El dict crudo del TOML. */
    val raw: Map<String, Any?>,
    /** El fichero tal cual: la base contra la que se escribiría. */
    val texto: String,
    /** `remoto` | `copia`. */
    val origen: String,
    /** Cuándo se leyó. */
    val sello: String,
    /** `remote:/ruta/al/pairs.toml`. */
    val extremo: String,
) {
    /** Solo se escribe sobre lo que se acaba de leer del remoto. */
    val editable: Boolean get() = origen == REMOTO

    val defaults: Map<String, Any?> get() = tablaDe(raw, "defaults") ?: emptyMap()

    /** Las parejas crudas del catálogo, en su orden y por su nombre. */
    val parejasPorNombre: Map<String, Map<String, Any?>>
        get() = parejasDe(raw)
            .filter { !it["name"]?.toString().isNullOrEmpty() }
            .associateBy { it.getValue("name").toString() }

    val names: List<String> get() = parejasPorNombre.keys.toList()

    /**
     * El `[remote]` del catálogo: la definición no secreta del remoto de
     * rclone, que el primer dispositivo escribe y los demás heredan.
     *
     * Aquí se usa para una cosa concreta: **el catálogo decide cómo se llama
     * el remoto**. Todo `remote_path` se resuelve contra
     * `[defaults].remote`, así que un móvil que llame al remoto de otra manera
     * fallaría en todas las parejas con «unknown remote». La clave nunca está
     * ahí.
     */
    val remote: Map<String, Any?> get() = tablaDe(raw, "remote") ?: emptyMap()

    /**
     * Si el tipo de backend del catálogo no está en el `.aar`, el aviso que
     * hay que dar — y darlo aquí, al leer el catálogo, en vez de dejar que
     * falle la primera pasada con un «didn't find section».
     *
     * Es la contrapartida de haber elegido los backends curados en vez de
     * `backend/all`: 31 MB menos de app a cambio de esta frase (ver
     * `PLAN.md`).
     */
    fun backendNoSoportado(soportados: Set<String> = BACKENDS): String? {
        val tipo = (remote["type"] ?: defaults["remote_type"])?.toString() ?: return null
        if (tipo.isEmpty() || tipo in soportados) return null
        return "El catálogo define un remoto de tipo '$tipo', y esta app solo lleva " +
            "dentro ${soportados.sorted().joinToString(", ")}. En el móvil no se puede " +
            "usar ese remoto."
    }

    companion object {
        const val REMOTO = "remoto"
        const val COPIA = "copia"

        /**
         * Valor de fábrica, no la ruta de nadie: cada usuario pone la suya.
         * Es el mismo que `catalog.DEFAULT_CATALOG_PATH` y que el que trae la
         * carga del QR ([Pairing.DEFAULT_CATALOG_PATH]).
         */
        const val RUTA_POR_DEFECTO = Pairing.DEFAULT_CATALOG_PATH

        /** `state/catalog.toml`: la última copia buena, para abrir sin red. */
        const val FICHERO_COPIA = "catalog.toml"

        /** Y cuándo y de dónde se leyó, que no cabe en un TOML de parejas. */
        const val FICHERO_META = "catalog.json"

        /**
         * Los backends que lleva el `.aar` (`rclone/gobind/backends_curados.go`).
         * Se repiten aquí porque el motor tiene que poder dar el aviso, y
         * `CatalogTest` comprueba que las dos listas no se separan.
         */
        val BACKENDS = setOf("combine", "crypt", "local", "sftp", "webdav")

        /**
         * Lo que evita que un remoto caído congele la pantalla.
         *
         * El catálogo son unos kilobytes: aquí no se espera por ancho de banda,
         * se espera a un servidor que puede no estar. Con los valores de rclone
         * por defecto (5 min de timeout, 3 reintentos) una wifi mala deja la
         * pantalla colgada varios minutos; con estos, un remoto inalcanzable se
         * resuelve en segundos y se cae a la copia. Son los mismos números que
         * `catalog.NET_FLAGS`, y van **sueltos** en la llamada: ver [Pasada].
         */
        val FLAGS_RED: Map<String, Any?> = linkedMapOf(
            "contimeout" to "10s",
            "timeout" to "20s",
            "retries" to 1L,
            "low_level_retries" to 2L,
        )

        /** Dónde está el catálogo, según los `[defaults]` de este dispositivo. */
        fun extremo(defaults: Map<String, Any?>): String {
            val remote = (defaults["catalog_remote"] ?: defaults["remote"] ?: DEFAULT_REMOTE)
                .toString()
            val ruta = defaults["catalog_path"]?.toString()?.takeIf { it.isNotEmpty() }
                ?: RUTA_POR_DEFECTO
            return "$remote:$ruta"
        }

        /**
         * La llamada que baja el catálogo a un fichero del volumen.
         *
         * `operations/copyfile` quiere el `Fs` y el nombre por separado, así
         * que el extremo se parte por la última barra. El destino es una ruta
         * local, que rclone resuelve con el backend `local` sin necesidad de
         * que haya un remote definido para ella.
         */
        fun peticionDescarga(extremo: String, destino: File): Peticion {
            val dosPuntos = extremo.indexOf(':')
            if (dosPuntos <= 0) {
                throw ConfigError(
                    "El catálogo tiene que estar en un remoto ('remoto:/ruta'), y dice " +
                        "'$extremo'.",
                )
            }
            val remote = extremo.substring(0, dosPuntos)
            val ruta = extremo.substring(dosPuntos + 1)
            if (ruta.isEmpty() || ruta.endsWith('/')) {
                throw ConfigError(
                    "La ruta del catálogo tiene que acabar en el nombre del fichero, y " +
                        "dice '$ruta'. Una ruta que acaba en barra nombra una carpeta.",
                )
            }
            val barra = ruta.lastIndexOf('/')
            val params = LinkedHashMap<String, Any?>(FLAGS_RED)
            // Sin barra el fichero está en la raíz del remoto, y entonces el Fs
            // es el remoto a secas: `nas:` + `pairs.toml`.
            params["srcFs"] = if (barra < 0) "$remote:" else "$remote:${ruta.substring(0, barra)}"
            params["srcRemote"] = if (barra < 0) ruta else ruta.substring(barra + 1)
            params["dstFs"] = destino.parentFile?.absolutePath ?: "."
            params["dstRemote"] = destino.name
            return Peticion("operations/copyfile", params)
        }

        /**
         * Baja el catálogo y lo deja cacheado. Lanza [RcloneError] si no se
         * puede: quien quiera la versión que no lanza es [cargar].
         */
        fun bajar(
            rclone: Rclone,
            defaults: Map<String, Any?>,
            estadoDir: File,
            cuando: LocalDateTime = LocalDateTime.now(),
        ): Catalogo {
            val donde = extremo(defaults)
            val destino = File(estadoDir, FICHERO_COPIA)
            estadoDir.mkdirs()
            val peticion = peticionDescarga(donde, destino)
            val respuesta = rclone.rpc(peticion.metodo, peticion.json)
            if (!respuesta.ok) {
                throw RcloneError(
                    "No pude leer el catálogo $donde: ${Pasada.mensajeDeError(respuesta)}",
                    respuesta.estado,
                )
            }
            val texto = destino.readText()
            val cat = leer(texto, REMOTO, Resultados.sello(cuando), donde)
            escribirMeta(estadoDir, cat)
            return cat
        }

        /** La última copia buena, sin tocar la red. Null si no hay o no sirve. */
        fun cacheado(estadoDir: File, defaults: Map<String, Any?> = emptyMap()): Catalogo? {
            val texto = runCatching { File(estadoDir, FICHERO_COPIA).readText() }.getOrNull()
                ?: return null
            val meta = runCatching {
                leerObjetoJson(File(estadoDir, FICHERO_META).readText())
            }.getOrNull() ?: emptyMap()
            return runCatching {
                leer(
                    texto,
                    COPIA,
                    meta["pulled_at"]?.toString() ?: "fecha desconocida",
                    meta["endpoint"]?.toString() ?: extremo(defaults),
                )
            }.getOrNull()
        }

        /**
         * El catálogo y, si algo no ha ido bien, qué decirle al usuario.
         *
         * **Nunca lanza.** La pantalla de parejas tiene que abrirse igual sin
         * red: con la copia y un aviso, o vacía y con otro aviso.
         */
        fun cargar(
            rclone: Rclone,
            defaults: Map<String, Any?>,
            estadoDir: File,
            cuando: LocalDateTime = LocalDateTime.now(),
        ): Pair<Catalogo?, String?> {
            val motivo = try {
                return bajar(rclone, defaults, estadoDir, cuando) to null
            } catch (e: RcloneError) {
                e.message.orEmpty()
            } catch (e: ConfigError) {
                e.message.orEmpty()
            } catch (e: TomlError) {
                e.message.orEmpty()
            } catch (e: java.io.IOException) {
                e.message.orEmpty()
            }

            val copia = cacheado(estadoDir, defaults)
            if (copia != null) {
                return copia to (
                    "Sin conexión con el catálogo. Se enseña la copia local del " +
                        "${copia.sello}; no se puede editar hasta que vuelva la " +
                        "conexión.\n$motivo"
                    )
            }
            return null to (
                "No hay catálogo ni copia local, así que solo se puede trabajar con las " +
                    "parejas que ya tiene este móvil.\n$motivo"
                )
        }

        /** El texto del catálogo, parseado y envuelto. */
        fun leer(texto: String, origen: String, sello: String, extremo: String): Catalogo {
            val raw = try {
                loadRaw(texto)
            } catch (e: TomlError) {
                throw TomlError("El catálogo $extremo no es TOML válido: ${e.message}")
            }
            return Catalogo(raw, texto, origen, sello, extremo)
        }

        /**
         * En qué claves difieren dos parejas (o dos `[defaults]`), en orden.
         *
         * Es lo que hace que la procedencia de una pareja sea **derivada** y no
         * guardada: se compara la del config con la de la copia del catálogo y
         * sale «catálogo» / «modificada aquí» / «huérfana». **No se añade una
         * clave `from_catalog`**: el TOML es editable a mano y `dumpsChecked`
         * exige que lo escrito se relea igual.
         */
        fun clavesDistintas(a: Map<String, Any?>?, b: Map<String, Any?>?): List<String> {
            val uno = a ?: emptyMap()
            val otro = b ?: emptyMap()
            return (uno.keys + otro.keys).sorted().distinct()
                .filter { normalizar(uno[it]) != normalizar(otro[it]) }
        }

        private fun escribirMeta(estadoDir: File, cat: Catalogo) {
            runCatching {
                File(estadoDir, FICHERO_META).writeText(
                    escribirJson(mapOf("pulled_at" to cat.sello, "endpoint" to cat.extremo)),
                )
            }
        }
    }
}
