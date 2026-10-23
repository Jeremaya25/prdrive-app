package prdrive.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LlaveroTest.kt — El llavero (la base de KeePassXC) como lo cuenta prdrive.
 * Hoy, si una base está entera sin abrirla (`common/kdbx.py`); lo que se
 * espera sale de `herramientas/vectores_llavero.py`, que se lo pregunta a
 * prdrive.
 */
class LlaveroTest {

    private val v = Vectores.seccion("llavero")

    private val temporales = ArrayList<File>()

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
        temporales.clear()
    }

    private fun temporal(): File =
        Files.createTempDirectory("prdrive-llavero").toFile().also { temporales.add(it) }

    private fun hex(texto: String): ByteArray =
        ByteArray(texto.length / 2) { texto.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun `una base se da por entera o rota como la mira prdrive`() {
        val casos = Vectores.objetos(v, "kdbx")
        assertTrue(casos.isNotEmpty())
        for (caso in casos) {
            val e = Kdbx.mirar(hex(Vectores.texto(caso, "hex")))
            assertEquals(caso["entera"], e.entera, "«${caso["caso"]}»")
            assertEquals(caso["version"], e.version, "«${caso["caso"]}»")
            assertEquals(caso["motivo"], e.motivo, "«${caso["caso"]}»")
        }
    }

    @Test
    fun `comprobar lee el fichero y uno que no se lee cuenta como no entero`() {
        val casos = Vectores.objetos(v, "kdbx")
        val entera = casos.first { it["entera"] == true && it["version"] == "4.1" }
        val cortada = casos.first { it["caso"].toString().startsWith("KDBX 4 cortada en") &&
            it["motivo"] == "está cortada" }
        val d = temporal()
        File(d, "buena.kdbx").writeBytes(hex(Vectores.texto(entera, "hex")))
        File(d, "cortada.kdbx").writeBytes(hex(Vectores.texto(cortada, "hex")))
        assertEquals(true, Kdbx.comprobar(File(d, "buena.kdbx")).entera)
        assertEquals(false, Kdbx.comprobar(File(d, "cortada.kdbx")).entera)

        val sin = Vectores.objeto(v, "kdbx_sin_fichero")
        val e = Kdbx.comprobar(File(d, "no-existe.kdbx"))
        assertEquals(sin["entera"], e.entera)
        assertEquals(sin["version"], e.version)
        assertTrue(e.motivo.startsWith(Vectores.texto(sin, "motivo_empieza")), e.motivo)
    }
}
