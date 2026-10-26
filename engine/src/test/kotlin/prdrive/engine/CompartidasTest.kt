package prdrive.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CompartidasTest.kt — El fichero de las parejas que viven fuera del volumen y
 * las reglas de qué parejas y qué rutas pueden hacerlo.
 */
class CompartidasTest {

    private val temporales = ArrayList<File>()

    private fun temporal(): File =
        Files.createTempDirectory("prdrive-compartidas").toFile().also { temporales.add(it) }

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
    }

    private fun pareja(nombre: String, local: String, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        mapOf("name" to nombre, "mode" to "bisync", "local" to local, "remote_path" to "x/$nombre") + extra

    private fun config(vararg parejas: Map<String, Any?>, extra: Map<String, Any?> = emptyMap()): Config =
        parseConfig(
            mapOf(
                "defaults" to mapOf("device_remote" to "disp", "remote" to "nas"),
                "pair" to parejas.toList(),
            ) + extra,
        )

    @Test
    fun `lo escrito se lee igual`() {
        val fichero = File(temporal(), Compartidas.FICHERO)
        val entradas = linkedMapOf(
            "vault" to Compartidas.Entrada("/storage/emulated/0/Documents/vault", Compartidas.Estado.LISTA),
            "fotos" to Compartidas.Entrada("/storage/emulated/0/DCIM/fotos", Compartidas.Estado.ENTRANDO),
        )
        Compartidas.escribir(fichero, entradas)
        assertEquals(entradas, Compartidas.leer(fichero))
        // Y en el orden de la brief: cada entrada con `ruta` antes que `estado`, y en su orden.
        val texto = fichero.readText(Charsets.UTF_8)
        val posiciones = listOf("\"vault\"", "\"ruta\"", "\"estado\"", "\"lista\"", "\"fotos\"", "\"entrando\"")
            .map { texto.indexOf(it) }
        assertEquals(posiciones.sorted(), posiciones, texto)
        assertTrue(posiciones.all { it >= 0 }, texto)
    }

    @Test
    fun `sin fichero no hay ninguna`() {
        assertEquals(emptyMap(), Compartidas.leer(File(temporal(), Compartidas.FICHERO)))
    }

    @Test
    fun `un fichero roto no se lee como si no hubiera ninguna`() {
        val fichero = File(temporal(), Compartidas.FICHERO)
        for (texto in listOf(
            "no es json",
            "[]",
            """{"vault": {"ruta": 3, "estado": "lista"}}""",
            """{"vault": {"ruta": "/x", "estado": "otra"}}""",
            """{"vault": "/x"}""",
        )) {
            fichero.writeText(texto, Charsets.UTF_8)
            val error = assertFailsWith<ConfigError>(texto) { Compartidas.leer(fichero) }
            assertTrue(Compartidas.FICHERO in error.message.orEmpty(), "«$texto»: ${error.message}")
        }
    }

    @Test
    fun `un fichero que no se puede leer tampoco se toma por vacio`() {
        // Una carpeta con el nombre del fichero: existe y no se lee.
        val fichero = File(temporal(), Compartidas.FICHERO).also { it.mkdirs() }
        assertFailsWith<ConfigError> { Compartidas.leer(fichero) }
    }

    @Test
    fun `una pareja bisync de un solo tramo y sola en su carpeta se puede mover`() {
        val config = config(pareja("vault", "vault"), pareja("otra", "otra"))
        assertNull(Compartidas.problemaPareja(config, "vault"))
    }

    @Test
    fun `cada regla de la pareja tiene su motivo`() {
        val base = pareja("vault", "vault")
        assertNotNull(Compartidas.problemaPareja(config(base + ("mode" to "up")), "vault"), "no es bisync")
        assertNotNull(
            Compartidas.problemaPareja(config(pareja("vault", "notas/vault")), "vault"),
            "más de un tramo",
        )
        assertNotNull(
            Compartidas.problemaPareja(config(base, pareja("todo", ".")), "vault"),
            "hay una pareja de la raíz",
        )
        assertNotNull(
            Compartidas.problemaPareja(config(pareja("todo", ".")), "todo"),
            "es la de la raíz",
        )
        assertNotNull(
            Compartidas.problemaPareja(config(base, pareja("sub", "vault/sub")), "vault"),
            "otra pareja comparte su carpeta de primer nivel",
        )
        assertNotNull(Compartidas.problemaPareja(config(base), "no-existe"), "no existe")
        val conLlavero = config(base, extra = mapOf("keychain" to mapOf("base" to "x.kdbx")))
        assertNull(Compartidas.problemaPareja(conLlavero, "vault"))
        assertNotNull(Compartidas.problemaPareja(conLlavero, LLAVERO), "es la del llavero")
    }

    @Test
    fun `cada regla de la ruta tiene su motivo`() {
        val raiz = temporal()
        val ruta = raiz.canonicalPath
        val otras = mapOf(
            "otra" to Compartidas.Entrada("/storage/emulated/0/Otra/carpeta", Compartidas.Estado.LISTA),
        )
        fun problema(texto: String) = Compartidas.problemaRuta(texto, raiz, otras)

        assertNull(problema("/storage/emulated/0/Documents/vault"))
        for (mala in listOf(
            "Documents/vault", "/a/./b", "/a/../b", "/a//b", "/a/b/",
            ruta, "$ruta/vault", raiz.canonicalFile.parentFile.path, raiz.absolutePath,
            "/storage/emulated/0/Otra/carpeta",
            "/storage/emulated/0/Otra/carpeta/dentro",
            "/storage/emulated/0/Otra",
        )) {
            assertNotNull(problema(mala), "«$mala»")
        }
        // Por tramos, no por texto: un nombre que empieza igual no es lo mismo.
        assertNull(problema("/storage/emulated/0/Otra/carpeta2"))
        assertNull(problema(ruta + "-hermana"))
    }

    @Test
    fun `problema junta la regla de la pareja y la de la ruta`() {
        val config = config(pareja("vault", "vault"))
        val raiz = temporal()
        assertNull(Compartidas.problema(config, "vault", "/storage/emulated/0/vault", raiz, emptyMap()))
        assertNotNull(Compartidas.problema(config, "otra", "/storage/emulated/0/vault", raiz, emptyMap()))
        assertNotNull(Compartidas.problema(config, "vault", "vault", raiz, emptyMap()))
    }
}
