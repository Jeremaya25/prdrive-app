package prdrive.app

import android.os.Environment
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.ui.Modelo
import prdrive.engine.Compartidas
import prdrive.engine.Resultados
import prdrive.engine.Volumen

/**
 * PantallaCompartidaTest.kt — La fila de una pareja que vive (o puede vivir) en
 * una carpeta compartida: dónde está, y qué botones ofrece. Sin red: las parejas
 * son carpetas locales ([Banco]); `compartidas.json` se escribe a mano, y lo que
 * se prueba es lo que la fila lee de él.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class PantallaCompartidaTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val legible = Banco.Legible()

    /** El permiso de red local, dado: «Sincronizar» lo pide antes, y el diálogo de Android no es de la app. */
    @Before
    fun preparar() {
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(Banco.contexto.packageName, Prdrive.PERMISO_RED_LOCAL)
        }
    }

    /** Una carpeta de Documents que nadie crea: la entrada solo tiene que valer. */
    private val carpeta: File get() = File(Environment.getExternalStorageDirectory(), "Documents/prdrive-prueba-pantalla")

    /**
     * Un volumen con la pareja «vault» (carpeta de primer nivel, que puede
     * mudarse) y la entrada de `compartidas.json` que se pida, con el `combine`
     * ya apuntando a ella: como lo dejaría una mudanza.
     */
    private fun volumenCon(estado: Compartidas.Estado?, ruta: String = carpeta.path): Volumen {
        val (v, config) = Banco.volumen(Banco.pareja("vault", local = "vault"))
        if (estado != null) {
            val entradas = mapOf("vault" to Compartidas.Entrada(ruta, estado))
            Compartidas.escribir(v.compartidas, entradas)
            v.actualizarCombine(config, entradas)
            Prdrive.recargarRclone(Banco.contexto)
        }
        return v
    }

    private fun abrir(): ActivityScenario<MainActivity> {
        val escena = ActivityScenario.launch(MainActivity::class.java)
        esperarSinTrabajo()
        return escena
    }

    private fun esperarSinTrabajo() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("fila-vault")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag("ocupado")).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun hay(etiqueta: String) = compose.onAllNodes(hasTestTag(etiqueta)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun una_pareja_compartida_dice_donde_vive_y_ofrece_volver() {
        volumenCon(Compartidas.Estado.LISTA)
        abrir().use {
            assertTrue("no dice dónde vive", hay("donde-vault"))
            assertTrue("no ofrece volver", hay("volver-vault"))
            assertFalse("ofrece mover una que ya está movida", hay("mover-vault"))
            assertFalse("ofrece terminar una que ya está terminada", hay("terminar-vault"))
        }
    }

    @Test
    fun una_pareja_a_medio_mover_ofrece_terminar_y_no_el_resync() {
        volumenCon(Compartidas.Estado.ENTRANDO)
        abrir().use {
            assertTrue("no ofrece terminar", hay("terminar-vault"))
            assertFalse("ofrece el --resync a una que está a medio mover", hay("resync-vault"))
            assertFalse("ofrece mover una que está a medio mover", hay("mover-vault"))
            // La entrada vale: volver no se ofrece hasta que deje de valer.
            assertFalse("ofrece volver con una entrada que vale", hay("volver-vault"))
        }
    }

    @Test
    fun una_pareja_a_medio_mover_cuya_entrada_ya_no_vale_ofrece_tambien_volver() {
        // Una ruta dentro del volumen no vale como carpeta compartida.
        val (v, _) = Banco.volumen(Banco.pareja("vault", local = "vault"))
        val dentro = File(v.raiz, "vault").path
        Compartidas.escribir(v.compartidas, mapOf("vault" to Compartidas.Entrada(dentro, Compartidas.Estado.ENTRANDO)))
        abrir().use {
            assertTrue("no ofrece terminar", hay("terminar-vault"))
            assertTrue("no ofrece volver con la entrada rota", hay("volver-vault"))
            assertFalse(hay("resync-vault"))
        }
        val fila = Estado.filas(v, v.leerConfig()).single { it.nombre == "vault" }
        assertNotNull("la fila no dice por qué no vale", fila.problemaCompartida)
    }

    @Test
    fun una_pareja_que_se_puede_mover_lo_ofrece_y_una_anidada_no() {
        Banco.volumen(Banco.pareja("vault", local = "vault"), Banco.pareja("notas"))
        abrir().use {
            compose.waitUntil(30_000) { hay("fila-notas") }
            assertTrue("no ofrece mover una de primer nivel", hay("mover-vault"))
            assertFalse("ofrece mover una anidada", hay("mover-notas"))
            assertFalse(hay("volver-vault"))
            assertFalse(hay("donde-vault"))
        }
    }

    @Test
    fun sin_acceso_la_fila_lleva_el_motivo() {
        val v = volumenCon(Compartidas.Estado.LISTA)
        val config = v.leerConfig()
        val con = Estado.filas(v, config, acceso = { "sin" }).single { it.nombre == "vault" }
        assertEquals("sin", con.sinAcceso)
        assertEquals(carpeta.path, con.compartida)
        assertFalse(con.entrando)
        assertNull(con.problemaCompartida)
        assertFalse("una compartida no se ofrece mover", con.sePuedeCompartir)
        // Una pareja que no está en una carpeta compartida no tiene acceso que dar.
        val (v2, config2) = Banco.volumen(Banco.pareja("vault", local = "vault"))
        val sin = Estado.filas(v2, config2, acceso = { "sin" }, conCompartir = true).single()
        assertNull(sin.sinAcceso)
        assertTrue(sin.sePuedeCompartir)
        assertFalse(Estado.filas(v2, config2, conCompartir = false).single().sePuedeCompartir)
    }

    /**
     * Lo único que puede pasar una pareja a medio mover es «Terminar de mover»:
     * una pasada normal contra una carpeta a medio llenar vería borrados y los
     * llevaría al remoto. `Sincronizar` la salta sin llamar a rclone, que se nota
     * en que no apunta nada en `state/`.
     */
    @Test
    fun sincronizar_salta_una_pareja_a_medio_mover() {
        val v = volumenCon(Compartidas.Estado.ENTRANDO)
        abrir().use { escena ->
            var modelo: Modelo? = null
            escena.onActivity { modelo = ViewModelProvider(it)[Modelo::class.java] }
            compose.onNodeWithTag("sincronizar").performClick()
            // «Sincronizar» acaba en un momento y puede no verse el «ocupado»: se
            // espera a la línea con la que el modelo cuenta que la salta.
            compose.waitUntil(60_000) { modelo!!.lineas.any { "[vault] A medio mover" in it } }
            compose.waitUntil(60_000) { !hay("ocupado") }
            assertTrue("la fila dejó de ofrecer terminar", hay("terminar-vault"))
            assertFalse("pasó la pareja a medio mover", hay("resync-vault"))
            val apuntado = Resultados.rutaEstado(v.state)
            assertTrue(
                "se hizo una pasada de la pareja a medio mover: ${if (apuntado.exists()) apuntado.readText() else ""}",
                !apuntado.exists() || "vault" !in apuntado.readText(),
            )
            assertNull("hay un resultado de la pareja", modelo!!.resultados["vault"])
        }
    }
}
