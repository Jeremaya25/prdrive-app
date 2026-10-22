package prdrive.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ActualizacionTest.kt — Si hay versión nueva, dicho como lo dice
 * `common/update.py` con el repositorio de la app
 * (`herramientas/vectores_actualizacion.py`), y el APK de la release, leído
 * como lo publica `release.yml`.
 */
class ActualizacionTest {

    private val v = Vectores.seccion("actualizacion")
    private val ahora = Store.leerSello(Vectores.texto(v, "ahora"))!!
    private val sello = Vectores.texto(v, "sello")

    private val temporales = ArrayList<File>()

    private fun temporal(): File =
        Files.createTempDirectory("prdrive-actualizacion").toFile().also { temporales.add(it) }

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
    }

    /** La respuesta de GitHub de verdad, la misma que leyó Python. */
    private val respuesta: Map<*, *> by lazy {
        val recurso = javaClass.getResourceAsStream("/release_github.json") ?: error("falta release_github.json")
        leerJson(recurso.bufferedReader().use { it.readText() }) as Map<*, *>
    }

    /** Una [Actualizacion.Release] como la escribe el generador: sus seis campos, sin el APK. */
    private fun comoVector(rel: Actualizacion.Release?): Map<String, Any?>? = rel?.let {
        linkedMapOf(
            "tag" to it.tag, "version" to it.version, "name" to it.name, "url" to it.url,
            "published" to it.published, "notes" to it.notes,
        )
    }

    @Test
    fun `las constantes son las de update_py con el repositorio de la app`() {
        val c = Vectores.objeto(v, "constantes")
        assertEquals(c["REPO"], Actualizacion.REPO)
        assertEquals(c["API_LATEST"], Actualizacion.API_LATEST)
        assertEquals(c["PAGINA"], Actualizacion.PAGINA)
        assertEquals(c["USER_AGENT"], Actualizacion.USER_AGENT)
        assertEquals(c["TIMEOUT_API"], Actualizacion.TIMEOUT_API.toLong())
        assertEquals(c["TIMEOUT_ZIP"], Actualizacion.TIMEOUT_DESCARGA.toLong())
        assertEquals(c["CACHE_HORAS"], Actualizacion.CACHE_HORAS.toLong())
        assertEquals(c["NOTAS_MAX"], Actualizacion.NOTAS_MAX.toLong())
    }

    @Test
    fun `una version se lee como parse_version y donde Python se cae aqui no`() {
        for (caso in Vectores.objetos(v, "parse_version")) {
            val texto = Vectores.texto(caso, "texto")
            val leida = Actualizacion.parseVersion(texto).map { it.toString() }
            if (caso["rompe"] == null) {
                assertEquals(caso["tupla"], leida, "parse_version(${reprPy(texto)})")
            }
        }
    }

    @Test
    fun `una version es mas nueva cuando lo dice is_newer`() {
        for (caso in Vectores.objetos(v, "is_newer")) {
            val nueva = Vectores.texto(caso, "nueva")
            val actual = Vectores.texto(caso, "actual")
            val es = Actualizacion.isNewer(nueva, actual)
            if (caso["rompe"] == null) assertEquals(caso["es"], es, "is_newer(${reprPy(nueva)}, ${reprPy(actual)})")
        }
    }

    @Test
    fun `solo se abre una pagina de este repositorio`() {
        for (caso in Vectores.objetos(v, "pagina_segura")) {
            assertEquals(caso["segura"], Actualizacion.paginaSegura(Vectores.texto(caso, "url")), "url ${caso["url"]}")
        }
    }

    @Test
    fun `la respuesta de GitHub se lee como _parse_release`() {
        for (caso in Vectores.objetos(v, "parse_release")) {
            val crudo = caso["crudo"]
            val error = caso["error"] as String?
            when {
                caso["rompe"] != null ->
                    // Python se cae con algo que no es un objeto; aquí es una respuesta rara.
                    assertFailsWith<Actualizacion.RespuestaRara>("con ${reprPy(crudo)}") { Actualizacion.parseRelease(crudo) }
                error != null -> {
                    val e = assertFailsWith<Actualizacion.RespuestaRara> { Actualizacion.parseRelease(crudo) }
                    assertEquals(error, e.message)
                }
                else -> assertEquals(caso["release"], comoVector(Actualizacion.parseRelease(crudo)), "con ${reprPy(crudo).take(80)}")
            }
        }
    }

    @Test
    fun `la cache se escribe letra a letra como la del escritorio`() {
        val escrita = Vectores.objeto(v, "cache_escrita")
        val cache = File(temporal(), "update.json")
        val rel = Actualizacion.parseRelease(respuesta)
        assertEquals(escrita["release"], comoVector(rel))
        // Sin el APK, que es lo que el escritorio no tiene, el fichero es el mismo.
        assertTrue(Actualizacion.escribirCache(rel.copy(apk = null), cache) { sello })
        assertEquals(Vectores.texto(escrita, "texto"), cache.readText())
        // Con él, lo mismo y el APK detrás; y se vuelve a leer entero.
        assertTrue(Actualizacion.escribirCache(rel, cache) { sello })
        val datos = leerJson(cache.readText()) as Map<*, *>
        assertEquals(leerJson(Vectores.texto(escrita, "texto")), datos - "apk")
        assertEquals(rel, Actualizacion.leerCache(cache, ahora).first)
    }

    @Test
    fun `la cache se lee como _leer_cache y pending solo mira la cache`() {
        val cache = File(temporal(), "update.json")
        for (caso in Vectores.objetos(v, "cache_leida")) {
            val texto = Vectores.texto(caso, "texto")
            cache.writeText(texto)
            val (rel, edad) = Actualizacion.leerCache(cache, ahora)
            assertEquals(caso["release"], comoVector(rel), "leyendo $texto")
            assertEquals((caso["edad"] as Number?)?.toDouble(), edad, "la edad de $texto")
            for ((instalada, pendiente) in Vectores.objeto(caso, "pending")) {
                assertEquals(
                    (pendiente as Map<*, *>?)?.get("tag"),
                    Actualizacion.pending(cache, instalada)?.tag,
                    "pending con $instalada instalada y $texto",
                )
            }
        }
    }

    @Test
    fun `check sale a la red cuando lo hace update_py y dice lo mismo`() {
        for (caso in Vectores.objetos(v, "check")) {
            val nombre = Vectores.texto(caso, "nombre")
            val cache = File(temporal(), "update.json")
            (caso["cache"] as String?)?.let { cache.writeText(it) }
            val llamadas = ArrayList<String>()
            val fetch = Actualizacion.Fetch { url, timeout ->
                llamadas.add(url)
                assertEquals(Actualizacion.TIMEOUT_API, timeout)
                (caso["error"] as String?)?.let { throw IOException(it) }
                Vectores.texto(caso, "respuesta").toByteArray()
            }
            val (rel, motivo) = Actualizacion.check(cache, fetch, caso["force"] == true, ahora) { sello }
            assertEquals(caso["llamadas"], llamadas, nombre)
            assertEquals(caso["release"], comoVector(rel), nombre)
            assertEquals(caso["motivo"], motivo, nombre)
            val despues = caso["cache_despues"] as String?
            if (despues == null) {
                assertTrue(!cache.exists(), nombre)
            } else {
                assertEquals(leerJson(despues), (leerJson(cache.readText()) as Map<*, *>) - "apk", nombre)
            }
        }
    }

    @Test
    fun `check no se cae con lo que no es JSON ni con lo que no es UTF-8`() {
        for (respuesta in listOf("no es json".toByteArray(), byteArrayOf(0xff.toByte(), 0x7b), "[]".toByteArray())) {
            val (rel, motivo) = Actualizacion.check(File(temporal(), "update.json"), { _, _ -> respuesta }, ahora = ahora)
            assertNull(rel)
            assertTrue(motivo!!.startsWith("No he podido preguntarle a GitHub si hay versión nueva: "), motivo)
        }
    }

    @Test
    fun `el veredicto se dice como en el escritorio`() {
        for (caso in Vectores.objetos(v, "veredicto")) {
            val r = caso["release"] as Map<*, *>?
            val rel = r?.let {
                Actualizacion.Release(
                    it["tag"] as String, it["version"] as String, it["name"] as String,
                    it["url"] as String, it["published"] as String, it["notes"] as String,
                )
            }
            assertEquals(
                caso["dice"],
                Actualizacion.veredicto(rel, caso["motivo"] as String?, Vectores.texto(caso, "actual")),
                "veredicto de $caso",
            )
        }
    }

    // --- el APK, que el escritorio no tiene ------------------------------------

    /** `release.yml`, que es quien le pone nombre al APK y escribe las notas. */
    private val release: String by lazy { File("../.github/workflows/release.yml").readText() }

    @Test
    fun `el APK se busca con el nombre que le pone release_yml`() {
        val plantilla = Regex("""firmado="\${'$'}RUNNER_TEMP/([^"]+)"""").find(release)?.groupValues?.get(1)
        assertNotNull(plantilla, "release.yml ya no dice cómo se llama el APK firmado")
        for (version in listOf("0.1.0", "1.2.0-rc1")) {
            assertEquals(plantilla.replace("\$VERSION", version), Actualizacion.nombreApk(version))
        }
    }

    @Test
    fun `el APK de la release de verdad sale con su URL su tamano y su suma`() {
        val activo = (respuesta["assets"] as List<*>).single() as Map<*, *>
        val apk = assertNotNull(Actualizacion.parseRelease(respuesta).apk)
        assertEquals(activo["name"], apk.nombre)
        assertEquals(activo["browser_download_url"], apk.url)
        assertEquals(activo["size"], apk.tamano)
        assertEquals(activo["digest"], "sha256:${apk.sha256}")
    }

    /** La respuesta de verdad, con el APK tocado. */
    private fun conApk(cambio: (MutableMap<Any?, Any?>) -> Unit): Map<*, *> {
        val activo = LinkedHashMap((respuesta["assets"] as List<*>).single() as Map<*, *>).also(cambio)
        return LinkedHashMap(respuesta).also { it["assets"] = listOf(activo) }
    }

    @Test
    fun `un APK que no es el de este repositorio no se baja`() {
        val otros = listOf<(MutableMap<Any?, Any?>) -> Unit>(
            { it["browser_download_url"] = "https://github.com/otro/prdrive-app/releases/download/v0.1.0/prdrive-0.1.0.apk" },
            { it["browser_download_url"] = "http://github.com/Jeremaya25/prdrive-app/releases/download/v0.1.0/prdrive-0.1.0.apk" },
            { it["browser_download_url"] = (it["browser_download_url"] as String) + "?x" },
            { it["name"] = "prdrive-0.1.1.apk" },
            { it["size"] = 0L },
            { it.remove("size") },
        )
        for (cambio in otros) assertNull(Actualizacion.parseRelease(conApk(cambio)).apk)
        assertNull(Actualizacion.parseRelease(LinkedHashMap(respuesta).also { it.remove("assets") }).apk)
    }

    @Test
    fun `sin la suma de GitHub el APK sale sin suma`() {
        for (cambio in listOf<(MutableMap<Any?, Any?>) -> Unit>(
            { it.remove("digest") },
            { it["digest"] = "sha512:abc" },
            { it["digest"] = "sha256:XYZ" },
        )) {
            val apk = assertNotNull(Actualizacion.parseRelease(conApk(cambio)).apk)
            assertNull(apk.sha256)
        }
    }

    @Test
    fun `un APK de la cache que no cuadra con su release no cuenta`() {
        val cache = File(temporal(), "update.json")
        val rel = Actualizacion.parseRelease(respuesta)
        Actualizacion.escribirCache(rel, cache) { sello }
        val datos = LinkedHashMap(leerJson(cache.readText()) as Map<*, *>)
        datos["version"] = "9.9.9"
        cache.writeText(escribirJson(datos))
        assertNull(Actualizacion.leerCache(cache, ahora).first?.apk)
    }
}
