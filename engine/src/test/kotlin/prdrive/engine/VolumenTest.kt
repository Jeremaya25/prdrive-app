package prdrive.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VolumenTest.kt — Sembrar el volumen como lo siembra el instalador del
 * escritorio: el config del dispositivo, las carpetas y el `rclone.conf`.
 */
class VolumenTest {

    private val paso3 get() = Vectores.seccion("paso3")
    private val temporales = ArrayList<File>()

    private fun temporal(): File =
        Files.createTempDirectory("prdrive-volumen").toFile().also { temporales.add(it) }

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
    }

    @Test
    fun `la carpeta del programa es la de un dispositivo`() {
        assertEquals(Vectores.texto(Vectores.objeto(paso3, "volumen"), "app_subdir"), CARPETA_PROGRAMA)
    }

    @Test
    fun `el config del volumen es el que escribe el instalador`() {
        val volumen = Vectores.objeto(paso3, "volumen")
        val catalogo = Vectores.objeto(volumen, "catalogo")
        for (caso in Vectores.objetos(volumen, "casos")) {
            val nombre = Vectores.texto(caso, "caso")
            val elegidas = Vectores.cadenas(caso, "elegidas")
            val ruta = Vectores.texto(caso, "catalog_path")
            assertEquals(
                normalizar(Vectores.objeto(caso, "config")),
                normalizar(Volumen.deviceConfig(catalogo, elegidas, ruta)),
                "«$nombre»",
            )
            // Y en el mismo orden de claves, que es el que acaba en el TOML.
            assertEquals(
                dumps(Vectores.objeto(caso, "config")),
                dumps(Volumen.deviceConfig(catalogo, elegidas, ruta)),
                "«$nombre»: el TOML",
            )
            assertEquals(Vectores.cadenas(caso, "local_dirs"), Volumen.localDirs(catalogo, elegidas), "«$nombre»")
            assertEquals(Vectores.cadenas(caso, "resync_targets"), Volumen.resyncTargets(catalogo, elegidas))
            assertEquals(Vectores.cadenas(caso, "mirror_pairs"), Volumen.mirrorPairs(catalogo, elegidas))
        }
        val errores = Vectores.objeto(volumen, "errores")
        assertEquals(
            errores["ninguna"],
            assertFailsWith<Volumen.Companion.InstallError> { Volumen.deviceConfig(catalogo, emptyList()) }.message,
        )
        assertEquals(
            errores["una que no existe"],
            assertFailsWith<Volumen.Companion.InstallError> {
                Volumen.deviceConfig(catalogo, listOf("notas", "inventada"))
            }.message,
        )
    }

    @Test
    fun `lo que no cabe en un rclone_conf se rechaza con las palabras del instalador`() {
        val conf = Vectores.objeto(paso3, "rclone_conf")
        for (caso in Vectores.objetos(conf, "lineas")) {
            val clave = Vectores.texto(caso, "clave")
            val valor = Vectores.texto(caso, "valor")
            val obtenido = try {
                Pairing.linea(clave, valor)
            } catch (e: Pairing.PairingError) {
                "error: ${e.message}"
            }
            val esperado = caso["salida"]?.toString() ?: "error: ${caso["error"]}"
            assertEquals(esperado, obtenido, "linea(${reprPy(clave)}, ${reprPy(valor)})")
        }
        for (caso in Vectores.objetos(conf, "nombres")) {
            assertEquals(caso["vale"], Pairing.nombreValido(Vectores.texto(caso, "nombre")), "«${caso["nombre"]}»")
        }
        assertEquals(Vectores.cadenas(conf, "opciones_que_ejecutan").toSet(), Pairing.OPCIONES_QUE_EJECUTAN)
    }

    private fun carga(texto: String) = Pairing.leer(texto)

    @Test
    fun `sembrar el volumen deja la clave en keys y un rclone_conf con rutas relativas`() {
        val pairing = Vectores.seccion("pairing")
        val c = carga(Vectores.texto(Vectores.objeto(pairing, "con_clave"), "texto"))
        val v = Volumen(File(temporal(), "volumen"))
        val raw = mapOf(
            "defaults" to mapOf("device_remote" to "disp", "remote" to c.remoteName),
            "pair" to listOf(mapOf("name" to "notas", "local" to "sync-data/notas", "remote_path" to "/n")),
        )
        v.escribirConfig(raw, "# cabecera\n")
        val config = v.leerConfig()
        v.escribirRemoto(c, config)
        assertTrue(File(v.keys, c.keyName).isFile)
        val conf = Pairing.parseRcloneConf(v.leerRcloneConf())
        assertEquals("keys/${c.keyName}", conf.getValue(c.remoteName)["key_file"])
        assertEquals("combine", conf.getValue("disp")["type"])
        assertTrue(v.sembrado)
    }

    @Test
    fun `una carga con un nombre de clave que sale de keys se rechaza`() {
        val pairing = Vectores.seccion("pairing")
        val c = carga(Vectores.texto(Vectores.objeto(pairing, "con_clave"), "texto"))
        val v = Volumen(File(temporal(), "volumen"))
        val config = parseConfig(
            mapOf(
                "defaults" to mapOf("device_remote" to "disp", "remote" to c.remoteName),
                "pair" to listOf(mapOf("name" to "notas", "local" to "a", "remote_path" to "/n")),
            ),
        )
        assertFailsWith<Pairing.PairingError> { v.escribirRemoto(c.copy(keyName = "../../fuera"), config) }
        assertFalse(File(v.raiz, "fuera").exists())
    }

    @Test
    fun `cambiar las parejas rehace la seccion del combine y deja la del remoto`() {
        val v = Volumen(File(temporal(), "volumen"))
        v.programa.mkdirs()
        v.rcloneConf.writeText("[nas]\ntype = sftp\nhost = h\n\n[disp]\ntype = combine\nupstreams = \"viejo=/x\"\n")
        val config = parseConfig(
            mapOf(
                "defaults" to mapOf("device_remote" to "disp", "remote" to "nas"),
                "pair" to listOf(mapOf("name" to "notas", "local" to "nuevo/notas", "remote_path" to "/n")),
            ),
        )
        v.actualizarCombine(config)
        val conf = Pairing.parseRcloneConf(v.leerRcloneConf())
        assertEquals("h", conf.getValue("nas")["host"])
        assertEquals(config.upstreamsDeCombine(v.raiz.absolutePath), conf.getValue("disp")["upstreams"])
    }

    @Test
    fun `una carpeta local que es un enlace a la del programa se detecta`() {
        val v = Volumen(File(temporal(), "volumen"))
        v.programa.mkdirs()
        val config = parseConfig(
            mapOf(
                "defaults" to mapOf("device_remote" to "disp", "remote" to "nas"),
                "pair" to listOf(mapOf("name" to "notas", "local" to "enlace", "remote_path" to "/n")),
            ),
        )
        assertNull(v.problemaContencion(config.pairs[0]))
        val enlace = File(v.raiz, "enlace").toPath()
        val creado = try {
            Files.createSymbolicLink(enlace, v.programa.toPath())
            true
        } catch (_: Exception) {
            false // Windows sin permiso para enlaces: no hay nada que comprobar aquí
        }
        if (creado) assertTrue(v.problemaContencion(config.pairs[0]) != null)
    }

    // --- activar y quitar el llavero --------------------------------------------

    private val activar get() = Vectores.objeto(Vectores.seccion("llavero"), "activar")

    @Suppress("UNCHECKED_CAST")
    private fun remotaDe(caso: Map<String, Any?>) = caso["remota"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun sinLlaveroDe(caso: Map<String, Any?>) = caso["sin_llavero"] as Map<String, Any?>

    @Test
    fun `activar el llavero añade la tabla del remoto y el config se sigue leyendo`() {
        assertEquals(Vectores.texto(activar, "llave"), LLAVERO_LLAVE)
        val casos = Vectores.objetos(activar, "casos")
        assertTrue(casos.size >= 5)
        for (caso in casos) {
            val nombre = "«${caso["caso"]}»"
            val sin = sinLlaveroDe(caso)
            val remota = remotaDe(caso)
            val con = Volumen.conLlavero(sin, remota)
            assertEquals(remota, con["keychain"], nombre)
            assertTrue(con["keychain"] !== remota, "$nombre: es una copia, no la tabla del catálogo")
            val config = parseConfig(con)
            val pareja = assertNotNull(config.parejaLlavero, nombre)
            assertEquals(remota["llave_interna"] == true, pareja.llaveInterna, nombre)
            assertEquals(remota, config.llavero, nombre)
            // El resto del config no cambia, y el original tampoco.
            assertEquals(normalizar(sin), normalizar(con - "keychain"), nombre)
            assertFalse("keychain" in sin, nombre)
            // Y lo que se escribe se relee igual.
            assertEquals(normalizar(con), normalizar(loadRaw(dumpsChecked(con))), nombre)
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `sin llavero en el remoto no hay nada que traer`() {
        val caso = Vectores.objetos(Vectores.objeto(Vectores.seccion("llavero"), "frenos"), "alta")
            .first { it.containsKey("error") }
        val sin = sinLlaveroDe(Vectores.objetos(activar, "casos").first())
        val error = assertFailsWith<ConfigError> { Volumen.conLlavero(sin, caso["remota"] as Map<String, Any?>?) }
        assertEquals(Vectores.texto(caso, "error"), error.message)
    }

    @Test
    fun `quitar el llavero deja el resto del config igual`() {
        for (caso in Vectores.objetos(activar, "casos")) {
            val nombre = "«${caso["caso"]}»"
            val sin = sinLlaveroDe(caso)
            val con = Volumen.conLlavero(sin, remotaDe(caso))
            val quitado = Volumen.sinLlavero(con)
            assertEquals(normalizar(sin), normalizar(quitado), nombre)
            assertTrue("keychain" in con, "$nombre: el config de partida no se toca")
            val config = parseConfig(quitado)
            assertNull(config.parejaLlavero, nombre)
            assertNull(config.llavero, nombre)
            assertEquals(parseConfig(sin).pairs, config.pairs, nombre)
        }
        // Sin llavero que quitar, queda igual.
        val sin = sinLlaveroDe(Vectores.objetos(activar, "casos").first())
        assertEquals(normalizar(sin), normalizar(Volumen.sinLlavero(sin)))
    }

    @Test
    fun `los avisos de activar y quitar son los de prdrive`() {
        val casos = Vectores.objetos(activar, "casos")
        assertTrue(casos.any { Vectores.cadenas(it, "avisos_alta").isNotEmpty() })
        assertTrue(casos.any { Vectores.cadenas(it, "avisos_baja").size == 2 })
        for (caso in casos) {
            val nombre = "«${caso["caso"]}»"
            val sin = parseConfig(sinLlaveroDe(caso))
            val con = parseConfig(Volumen.conLlavero(sinLlaveroDe(caso), remotaDe(caso)))
            assertEquals(Vectores.cadenas(caso, "avisos_alta"), Volumen.avisosAlta(sin), "$nombre: activar")
            // Con o sin el llavero ya puesto, la pareja de la raíz es la misma.
            assertEquals(Vectores.cadenas(caso, "avisos_alta"), Volumen.avisosAlta(con), "$nombre: activar con él")
            assertEquals(Vectores.cadenas(caso, "avisos_baja"), Volumen.avisosBaja(con), "$nombre: quitar")
        }
        val error = assertFailsWith<ConfigError> { Volumen.avisosBaja(parseConfig(sinLlaveroDe(casos.first()))) }
        assertEquals(Vectores.texto(activar, "baja_sin_llavero"), error.message)
    }
}
