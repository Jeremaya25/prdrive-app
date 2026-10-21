package prdrive.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
}
