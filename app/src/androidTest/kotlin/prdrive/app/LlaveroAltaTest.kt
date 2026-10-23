package prdrive.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.rclone.Rclone
import prdrive.engine.ConfigError
import prdrive.engine.LLAVERO_LLAVE
import prdrive.engine.LLAVERO_LOCAL
import prdrive.engine.Pairing
import prdrive.engine.Volumen

/**
 * LlaveroAltaTest.kt — Activar en el teléfono el llavero que ya tiene el
 * remoto, y quitarlo ([Instalador.activarLlavero], [Instalador.quitarLlavero]):
 * el config, la carpeta `.keychain/`, la llave y el `combine` de rclone.
 *
 * La llave llega por el content resolver con un `file://` de la caché de la
 * prueba: es el mismo `openInputStream` que un `content://` del selector de
 * archivos, sin necesitar un proveedor de prueba.
 */
@RunWith(AndroidJUnit4::class)
class LlaveroAltaTest {

    private val contexto get() = Banco.contexto

    private fun remota(interna: Boolean): Map<String, Any?> =
        if (interna) {
            mapOf("base" to "personal.kdbx", "fichero_llave" to true, "llave_interna" to true)
        } else {
            mapOf("base" to "personal.kdbx")
        }

    /** Un fichero llave de la caché con estos bytes, y su `Uri`. */
    private fun llave(bytes: ByteArray): Uri {
        val f = File(contexto.cacheDir, "llave-de-prueba.keyx")
        f.writeBytes(bytes)
        return Uri.fromFile(f)
    }

    private fun upstreams(v: Volumen): String =
        Pairing.parseRcloneConf(v.leerRcloneConf()).getValue("disp").getValue("upstreams")

    @Test
    fun activar_con_llave_interna_copia_la_llave_y_sin_ella_no_activa() {
        val (v, _) = Banco.volumen(Banco.pareja("notas"))
        val antes = v.config.readText()
        val confAntes = v.rcloneConf.readText()

        // Sin llave: no activa, y no se toca nada.
        try {
            Instalador.activarLlavero(contexto, remota(interna = true), null)
            fail("un llavero sin contraseña sin su fichero llave no se puede activar")
        } catch (e: ConfigError) {
            assertEquals("Este llavero va sin contraseña: hace falta su fichero llave.", e.message)
        }
        assertEquals(antes, v.config.readText())
        assertEquals(confAntes, v.rcloneConf.readText())
        assertFalse(File(v.raiz, LLAVERO_LOCAL).exists())

        // Con ella: la copia, byte a byte, en el sitio fijo.
        val bytes = ByteArray(200) { (it * 13 + 5).toByte() }
        val config = Instalador.activarLlavero(contexto, remota(interna = true), llave(bytes))
        val copiada = File(File(v.raiz, LLAVERO_LOCAL), LLAVERO_LLAVE)
        assertArrayEquals(bytes, copiada.readBytes())
        assertEquals(setOf(LLAVERO_LLAVE, "LEEME.txt"), File(v.raiz, LLAVERO_LOCAL).list()!!.toSet())
        assertTrue(config.parejaLlavero!!.llaveInterna)
        assertEquals(remota(interna = true), v.leerConfig().llavero)
        assertTrue(v.config.readText().contains("[keychain]"))
    }

    @Test
    fun un_llavero_con_contrasena_no_pide_ni_copia_la_llave() {
        val (v, _) = Banco.volumen(Banco.pareja("notas"))
        val config = Instalador.activarLlavero(contexto, remota(interna = false), llave(ByteArray(4)))
        assertFalse(config.parejaLlavero!!.llaveInterna)
        assertFalse(File(File(v.raiz, LLAVERO_LOCAL), LLAVERO_LLAVE).exists())
        assertTrue(File(File(v.raiz, LLAVERO_LOCAL), "LEEME.txt").isFile)
        assertNotNull(v.leerConfig().parejaLlavero)
    }

    @Test
    fun una_llave_que_no_se_puede_abrir_no_activa_el_llavero() {
        val (v, _) = Banco.volumen(Banco.pareja("notas"))
        val antes = v.config.readText()
        val confAntes = v.rcloneConf.readText()
        val falta = Uri.fromFile(File(contexto.cacheDir, "no-existe.keyx"))
        try {
            Instalador.activarLlavero(contexto, remota(interna = true), falta)
            fail("una llave que no se abre no puede dejar el llavero activo")
        } catch (_: java.io.IOException) {
        }
        assertEquals(antes, v.config.readText())
        assertEquals(confAntes, v.rcloneConf.readText())
        assertFalse(File(File(v.raiz, LLAVERO_LOCAL), LLAVERO_LLAVE).exists())
        // Ni la carpeta, que no estaba.
        assertFalse(File(v.raiz, LLAVERO_LOCAL).exists())
    }

    /**
     * Un fallo de activar después de copiar la llave (aquí, el `rclone.conf`
     * que no se puede escribir) no deja ni la llave ni la carpeta `.keychain/`
     * que no estaba, ni el `rclone.conf` cambiado: sin el config con el
     * llavero, nada las dejaría fuera de una pareja de la raíz entera. Una
     * carpeta que ya estaba, con su base, se queda.
     */
    @Test
    fun un_fallo_al_activar_no_deja_ni_la_llave_ni_la_carpeta_nueva() {
        for (yaEstaba in listOf(false, true)) {
            val (v, _) = Banco.volumen(Banco.pareja("notas"))
            val carpeta = File(v.raiz, LLAVERO_LOCAL)
            val base = File(carpeta, "personal.kdbx")
            if (yaEstaba) {
                carpeta.mkdirs()
                base.writeText("la base")
            }
            val antes = v.config.readText()
            val confAntes = v.rcloneConf.readText()
            // El temporal del rclone.conf ocupado por una carpeta con algo: no se puede escribir.
            val estorbo = File(v.rcloneConf.parentFile, "rclone.tmp").apply { mkdirs() }
            File(estorbo, "algo").writeText("x")
            try {
                Instalador.activarLlavero(contexto, remota(interna = true), llave(ByteArray(32) { it.toByte() }))
                fail("con el rclone.conf sin escribir, activar tenía que fallar")
            } catch (_: java.io.IOException) {
            } finally {
                estorbo.deleteRecursively()
            }
            assertFalse("($yaEstaba) la llave se ha quedado", File(carpeta, LLAVERO_LLAVE).exists())
            assertEquals("($yaEstaba)", yaEstaba, carpeta.exists())
            if (yaEstaba) assertEquals("la base", base.readText())
            assertEquals(antes, v.config.readText())
            assertEquals(confAntes, v.rcloneConf.readText())
            assertNull(v.leerConfig().llavero)
        }
    }

    /** Lo que rclone ve en la raíz del `combine`: una carpeta por upstream. */
    private fun carpetasDelCombine(): Set<String> {
        @Suppress("UNCHECKED_CAST")
        val lista = Rclone.rpc("operations/list", mapOf("fs" to "disp:", "remote" to ""))["list"] as List<Map<String, Any?>>
        return lista.map { it["Name"].toString() }.toSet()
    }

    @Test
    fun activar_y_quitar_rehacen_el_combine() {
        val (v, _) = Banco.volumen(Banco.pareja("notas"))
        assertFalse(upstreams(v).contains(LLAVERO_LOCAL))

        val con = Instalador.activarLlavero(contexto, remota(interna = false), null)
        assertTrue(upstreams(v), upstreams(v).contains(LLAVERO_LOCAL))
        assertEquals(con.upstreamsDeCombine(v.raiz.absolutePath), upstreams(v))
        // El remote de rclone se ha recargado: el combine ya ve la carpeta.
        assertTrue(carpetasDelCombine().contains(LLAVERO_LOCAL))
        assertNotNull(v.leerConfig().parejaLlavero)

        val base = File(File(v.raiz, LLAVERO_LOCAL), "personal.kdbx")
        base.writeText("la base")
        val sin = Instalador.quitarLlavero(contexto)
        assertFalse(upstreams(v), upstreams(v).contains(LLAVERO_LOCAL))
        assertEquals(sin.upstreamsDeCombine(v.raiz.absolutePath), upstreams(v))
        assertFalse(carpetasDelCombine().contains(LLAVERO_LOCAL))
        assertNull(v.leerConfig().llavero)
        assertFalse(v.config.readText().contains("[keychain]"))
        // La carpeta del llavero se queda con lo suyo.
        assertEquals("la base", base.readText())
        // La del remote [nas] sigue donde estaba.
        assertEquals("local", Pairing.parseRcloneConf(v.leerRcloneConf()).getValue("nas")["type"])

        // Y quitarlo otra vez, sin llavero, no es nada que hacer.
        try {
            Instalador.quitarLlavero(contexto)
            fail("sin llavero no hay nada que quitar")
        } catch (e: ConfigError) {
            assertEquals(Volumen.SIN_LLAVERO, e.message)
        }
    }
}
