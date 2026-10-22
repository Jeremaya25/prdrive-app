package prdrive.engine

import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Duration
import java.time.LocalDateTime

/**
 * Actualizacion.kt — Si hay una versión nueva de la app en GitHub.
 *
 * Espejo de `common/update.py` de prdrive en lo que mira y compara: la última
 * release, la caché de [CACHE_HORAS], la comparación de versiones y lo que se le
 * dice a la persona. Lo que trae y pone la versión nueva no se parece: el
 * escritorio baja el zip del código y lo despliega él mismo; aquí se baja el APK
 * firmado de la release y lo instala Android, que solo lo pone encima si lleva
 * la misma firma. Eso es Android y vive en la app (`Actualizador.kt`); aquí
 * queda lo que se prueba sin él.
 *
 * Las tres reglas de `update.py` valen igual:
 * - Mirar nunca es un error: [check] devuelve `(release, motivo)` y no lanza.
 * - La pantalla no espera a la red: [pending] lee solo la caché.
 * - La red entra por un solo sitio, el [Fetch] que se pasa, y los tests lo
 *   sustituyen entero.
 *
 * El repositorio es el de la app, no el de prdrive: [REPO] es la única
 * constante distinta, y los vectores se sacan de `update.py` con ese
 * repositorio puesto (`herramientas/vectores_actualizacion.py`).
 *
 * Lo que `update.py` no tiene es el APK ([Apk]): el escritorio baja el código de
 * un tag de `codeload`, y el teléfono, lo que `release.yml` cuelga de la
 * release.
 */
object Actualizacion {

    /** El repositorio de la app, de donde sale este mismo programa. No es un ajuste. */
    const val REPO = "Jeremaya25/prdrive-app"

    /** La API de GitHub con la última release (ni borradores ni prereleases). */
    const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    /** La página de releases, para «Ver la página». */
    const val PAGINA = "https://github.com/$REPO/releases"

    /** Sin `User-Agent`, la API contesta 403; con uno propio, se ve quién pregunta. */
    const val USER_AGENT = "prdrive-update (+https://github.com/$REPO)"

    /** Segundos: la pantalla no puede esperar más. */
    const val TIMEOUT_API = 8

    /** Segundos sin recibir nada en una descarga (`TIMEOUT_ZIP`): ahí se espera por ancho de banda. */
    const val TIMEOUT_DESCARGA = 60

    /** Horas entre consultas a GitHub. */
    const val CACHE_HORAS = 24

    /** Caracteres de las notas de la release que se guardan. */
    const val NOTAS_MAX = 4000

    /**
     * El APK firmado que `release.yml` cuelga de la release.
     *
     * @property sha256 La suma que GitHub calcula de cada fichero (`digest`), en
     *   hexadecimal, o `null` si no la da. Sin ella no se pierde nada esencial:
     *   Android comprueba la firma del APK entero antes de instalarlo.
     */
    data class Apk(val nombre: String, val url: String, val tamano: Long, val sha256: String?)

    /**
     * Una release de GitHub, con lo poco que hace falta de ella: los campos de
     * `update.Release`, y el [Apk] si la release lo trae.
     *
     * @property tag Tal como lo publica GitHub, p. ej. `v0.2.0`.
     * @property version La versión sin la `v`, que es lo que se compara.
     */
    data class Release(
        val tag: String,
        val version: String,
        val name: String,
        val url: String,
        val published: String,
        val notes: String = "",
        val apk: Apk? = null,
    )

    /**
     * Lo que GitHub ha contestado no es una release (el `ValueError` de
     * `_parse_release`).
     */
    class RespuestaRara(mensaje: String) : Exception(mensaje)

    /** `update.fetch`: la única puerta a la red. Lanza `IOException` si no llega. */
    fun interface Fetch {
        @Throws(IOException::class)
        fun fetch(url: String, timeout: Int): ByteArray
    }

    /**
     * `parse_version`: `v0.0.10` es `[0, 0, 10]`.
     *
     * Tolerante porque compara, no valida: lo que no es un número cuenta como 0,
     * y un tag raro da «no hay nada nuevo», nunca una excepción. Una cifra es la
     * de cualquier alfabeto (`١٢`, `１２`), como el `int()` de Python. Python
     * también da por cifra un `²`, que luego `int()` no sabe leer y se cae con
     * `ValueError`; aquí ese carácter corta el número, como cualquier letra.
     */
    fun parseVersion(texto: String): List<BigInteger> =
        stripPy(texto).trimStart('v', 'V').split(".").map { trozo ->
            var n = BigInteger.ZERO
            var i = 0
            while (i < trozo.length) {
                val cp = trozo.codePointAt(i)
                if (!Character.isDigit(cp)) break
                n = n.multiply(BigInteger.TEN).add(BigInteger.valueOf(Character.digit(cp, 10).toLong()))
                i += Character.charCount(cp)
            }
            n
        }

    /**
     * `is_newer`: si [nueva] es posterior a [actual]. Se comparan números y no
     * cadenas (0.0.10 va después de 0.0.9), con ceros donde falta una parte (1.2
     * es 1.2.0). Sin versión actual, cualquier cosa es más nueva.
     *
     * Un sufijo no cuenta (1.2.0-rc1 es 1.2.0), igual que en el `versionCode`
     * (`app/build.gradle.kts`): de una rc a su versión final no se actualiza
     * sola, y Android tampoco la vería más nueva.
     */
    fun isNewer(nueva: String, actual: String): Boolean {
        if (nueva.isEmpty()) return false
        if (actual.isEmpty()) return true
        val a = parseVersion(nueva)
        val b = parseVersion(actual)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { BigInteger.ZERO }
            val y = b.getOrElse(i) { BigInteger.ZERO }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * `pagina_segura`: [url] si es una página de este repositorio en GitHub, y si
     * no [PAGINA]. La URL llega de la API o de la caché, y se abre con un toque:
     * solo se da por buena la de aquí.
     */
    fun paginaSegura(url: String): String = if (url.startsWith("https://github.com/$REPO/")) url else PAGINA

    /** El nombre del APK de una versión, el que le pone `release.yml`. */
    fun nombreApk(version: String): String = "prdrive-$version.apk"

    /**
     * De dónde se baja el APK de una release: el `browser_download_url` que da
     * GitHub, y ningún otro. Como [paginaSegura], la URL de la respuesta no se
     * da por buena: tiene que ser esta, letra a letra, o no hay APK.
     */
    fun urlApk(tag: String, nombre: String): String = "https://github.com/$REPO/releases/download/$tag/$nombre"

    /** `a or b` de Python. */
    private fun o(a: Any?, b: Any?): Any? = if (verdad(a)) a else b

    /**
     * `_parse_release`: la respuesta de la API, en una [Release].
     *
     * `update.py` llama a `crudo.get()` sin mirar qué es, y con algo que no es un
     * objeto se cae con un `AttributeError` que `check()` no recoge. Aquí es una
     * [RespuestaRara] más.
     *
     * @throws RespuestaRara Si no es un objeto o no trae `tag_name`.
     */
    fun parseRelease(crudo: Any?): Release {
        val m = crudo as? Map<*, *> ?: throw RespuestaRara("la respuesta de GitHub no es un objeto")
        val tag = stripPy(strPy(o(m["tag_name"], "")))
        if (tag.isEmpty()) throw RespuestaRara("la respuesta de GitHub no trae tag_name")
        val notas = stripPy(strPy(o(m["body"], "")))
        val version = tag.trimStart('v', 'V')
        return Release(
            tag = tag,
            version = version,
            name = stripPy(strPy(o(m["name"], tag))).ifEmpty { tag },
            url = paginaSegura(strPy(o(m["html_url"], ""))),
            published = strPy(o(m["published_at"], "")),
            notes = cortarPy(notas, NOTAS_MAX),
            apk = apkDe(m["assets"], tag, version),
        )
    }

    /** `sha256:<64 cifras hexadecimales>`, que es como da GitHub el `digest`. */
    private val DIGEST = Regex("sha256:([0-9a-f]{64})")

    /**
     * El APK de la release entre sus ficheros: el que se llama como lo llama
     * `release.yml` y se baja de donde se bajan los de este repositorio. Si no
     * está, o algo no cuadra, no hay APK: la release se enseña, pero no se baja.
     */
    private fun apkDe(assets: Any?, tag: String, version: String): Apk? {
        val nombre = nombreApk(version)
        val activo = (assets as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            .firstOrNull { it["name"] == nombre } ?: return null
        val url = urlApk(tag, nombre)
        if (activo["browser_download_url"] != url) return null
        val tamano = (activo["size"] as? Long)?.takeIf { it > 0 } ?: return null
        val sha = (activo["digest"] as? String)?.let { DIGEST.matchEntire(it)?.groupValues?.get(1) }
        return Apk(nombre, url, tamano, sha)
    }

    /** El [Apk] guardado en la caché, con las mismas reglas que el de la API. */
    private fun apkDeCache(valor: Any?, tag: String, version: String): Apk? {
        val m = valor as? Map<*, *> ?: return null
        val nombre = nombreApk(version)
        if (m["nombre"] != nombre || m["url"] != urlApk(tag, nombre)) return null
        val tamano = (m["tamano"] as? Long)?.takeIf { it > 0 } ?: return null
        val sha = (m["sha256"] as? String)?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
        return Apk(nombre, urlApk(tag, nombre), tamano, sha)
    }

    /**
     * `_leer_cache`: la release guardada y cuántos segundos hace que se miró
     * (`null` si no se sabe). Leer nunca es un error: lo raro es «no hay nada».
     *
     * @param ahora El reloj, para los tests.
     */
    fun leerCache(cache: File, ahora: LocalDateTime = LocalDateTime.now()): Pair<Release?, Double?> {
        val data = Store.readJson(cache)
        val tag = strPy(o(data["tag"], ""))
        if (tag.isEmpty()) return null to null
        val version = stripPy(strPy(o(data["version"], "")))
        val rel = Release(
            tag = tag,
            version = version,
            name = strPy(o(data["name"], tag)),
            url = paginaSegura(strPy(o(data["url"], ""))),
            published = strPy(o(data["published"], "")),
            notes = strPy(o(data["notes"], "")),
            apk = apkDeCache(data["apk"], tag, version),
        )
        val visto = if (data.containsKey("checked")) Store.leerSello(strPy(data["checked"])) else null
        val edad = visto?.let { Duration.between(it, ahora).toMillis() / 1000.0 }
        return rel to edad
    }

    /**
     * `_escribir_cache`: guarda la release, con la hora de ahora. Si no se
     * puede, no pasa nada: la caché es una comodidad. El [Apk] va detrás de los
     * campos del escritorio, y sin él el fichero sale igual que el suyo.
     */
    fun escribirCache(rel: Release, cache: File, reloj: () -> String = Store::stamp): Boolean {
        val carpeta = cache.parentFile
        if (carpeta != null && !carpeta.isDirectory && !carpeta.mkdirs()) return false
        val datos = linkedMapOf<String, Any?>(
            "checked" to reloj(),
            "tag" to rel.tag,
            "version" to rel.version,
            "name" to rel.name,
            "url" to rel.url,
            "published" to rel.published,
            "notes" to rel.notes,
        )
        rel.apk?.let {
            datos["apk"] = linkedMapOf("nombre" to it.nombre, "url" to it.url, "tamano" to it.tamano, "sha256" to it.sha256)
        }
        return Store.writeJson(cache, datos)
    }

    /** Lo que `json.loads(bytes)` lee de una respuesta en UTF-8: lo que no lo es, no se lee. */
    private fun utf8(datos: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(datos)).toString()

    /**
     * `check`: la última release y, si algo no ha ido bien, qué decirle a la
     * persona. Nunca lanza. Con una comprobación de hace menos de
     * [CACHE_HORAS] ni siquiera se toca la red.
     *
     * @param force El «buscar ahora»: no mira la caché.
     */
    fun check(
        cache: File,
        fetch: Fetch,
        force: Boolean = false,
        ahora: LocalDateTime = LocalDateTime.now(),
        reloj: () -> String = Store::stamp,
    ): Pair<Release?, String?> {
        val (copia, edad) = leerCache(cache, ahora)
        if (!force && copia != null && edad != null && edad >= 0 && edad < CACHE_HORAS * 3600) {
            return copia to null
        }
        val rel = try {
            parseRelease(leerJson(utf8(fetch.fetch(API_LATEST, TIMEOUT_API))))
        } catch (e: IOException) {
            return sinRespuesta(copia, e)
        } catch (e: JsonError) {
            return sinRespuesta(copia, e)
        } catch (e: RespuestaRara) {
            return sinRespuesta(copia, e)
        }
        escribirCache(rel, cache, reloj)
        return rel to null
    }

    private fun sinRespuesta(copia: Release?, e: Exception): Pair<Release?, String> {
        val motivo = "No he podido preguntarle a GitHub si hay versión nueva: ${e.message ?: e}"
        return if (copia != null) copia to "$motivo\nSe enseña lo último que se supo." else null to motivo
    }

    /**
     * `pending`: la release si es más nueva que [instalada]. Solo caché, jamás
     * red: es lo que se pregunta al pintar.
     */
    fun pending(cache: File, instalada: String): Release? =
        leerCache(cache).first?.takeIf { isNewer(it.version, instalada) }

    /**
     * `veredicto`: en una línea, lo que contesta un «buscar actualizaciones».
     * Un fallo manda aunque haya una release en la caché: quien pulsa «buscar»
     * quiere saber si acaba de mirar de verdad.
     */
    fun veredicto(rel: Release?, motivo: String?, actual: String): String = when {
        !motivo.isNullOrEmpty() -> splitlinesPy(motivo).first()
        rel == null -> "No sé qué versión es la última."
        isNewer(rel.version, actual) -> "Hay una versión nueva: ${rel.tag}"
        else -> "Ya tienes la última versión (${actual.ifEmpty { rel.version }})"
    }
}
