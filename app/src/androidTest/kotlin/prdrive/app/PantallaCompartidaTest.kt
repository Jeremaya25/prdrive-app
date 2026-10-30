package prdrive.app

import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
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
import prdrive.app.ui.Tema
import prdrive.app.ui.parejas.Mover
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
            Banco.abrirFicha(compose, "vault")
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
            Banco.abrirFicha(compose, "vault")
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
            Banco.abrirFicha(compose, "vault")
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
            Banco.abrirFicha(compose, "vault")
            assertTrue("no ofrece mover una de primer nivel", hay("mover-vault"))
            assertFalse(hay("volver-vault"))
            assertFalse(hay("donde-vault"))
            Banco.abrirFicha(compose, "notas")
            assertFalse("ofrece mover una anidada", hay("mover-notas"))
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
            Banco.abrirFicha(compose, "vault")
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

    /** #24, la parte pequeña: una pareja que no se puede mover dice por qué, en vez de no ofrecerlo sin más. */
    @Test
    fun una_pareja_anidada_dice_por_que_no_se_mueve() {
        val (v, config) = Banco.volumen(Banco.pareja("notas"))
        val anidada = Estado.filas(v, config, conCompartir = true).single()
        assertNotNull("no dice por qué no se mueve", anidada.porQueNoSeComparte)
        assertFalse(anidada.sePuedeCompartir)
        val (v2, config2) = Banco.volumen(Banco.pareja("notas", local = "vault"))
        val suelta = Estado.filas(v2, config2, conCompartir = true).single()
        assertNull(suelta.porQueNoSeComparte)
        assertTrue(suelta.sePuedeCompartir)
    }

    @Test
    fun una_pareja_anidada_lo_dice_en_su_ficha() {
        Banco.volumen(Banco.pareja("notas"))
        ActivityScenario.launch(MainActivity::class.java).use {
            Banco.abrirFicha(compose, "notas")
            assertTrue("no dice por qué no se puede mover", hay("no-compartir-notas"))
            assertFalse(hay("mover-notas"))
        }
    }

    /** Rehacer la referencia se confirma en una hoja que dice qué va a pasar; «Ahora no» no pasa nada. */
    @Test
    fun el_resync_se_confirma_en_una_hoja() {
        Banco.volumen(Banco.pareja("vault", local = "vault"))
        ActivityScenario.launch(MainActivity::class.java).use { escena ->
            var modelo: Modelo? = null
            escena.onActivity { modelo = ViewModelProvider(it)[Modelo::class.java] }
            // Sin listados, una bisync pide --resync.
            Banco.abrirFicha(compose, "vault")
            compose.waitUntil(10_000) { hay("resync-vault") }
            compose.onNodeWithTag("resync-vault").performClick()
            compose.waitUntil(10_000) { hay("confirmar-resync") }
            compose.onNodeWithText("Ahora no").performClick()
            compose.waitUntil(10_000) { !hay("confirmar-resync") }
            assertNull("hizo una pasada", modelo!!.resultados["vault"])
            assertTrue(hay("resync-vault"))
        }
    }

    /**
     * Sin el permiso de acceso a todos los archivos no se puede elegir carpeta;
     * con él, sí, y se nota al volver a la app (de los ajustes de Android, donde
     * se da). El permiso se finge: quitarlo de verdad con `appops` mata el
     * proceso de la app, que es también el del test.
     */
    @Test
    fun mover_pide_el_permiso_antes_de_elegir_la_carpeta() {
        org.junit.Assume.assumeTrue("antes de Android 11 no se ofrece mover", android.os.Build.VERSION.SDK_INT >= 30)
        Banco.volumen(Banco.pareja("vault", local = "vault"))
        var permitido = false
        ActivityScenario.launch(ComponentActivity::class.java).use { escena ->
            escena.onActivity { actividad ->
                val modelo = ViewModelProvider(actividad)[Modelo::class.java]
                actividad.setContent { Tema { Mover(modelo, "vault", volver = {}, tienePermiso = { permitido }) } }
            }
            compose.waitUntil(10_000) { hay("pantalla-mover-vault") }
            assertTrue("no ofrece dar el permiso", hay("dar-permiso"))
            compose.onNodeWithTag("elegir-carpeta").performScrollTo().assertIsNotEnabled()
            permitido = true
            escena.moveToState(Lifecycle.State.STARTED)
            escena.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("elegir-carpeta") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("elegir-carpeta").assertIsEnabled()
            assertFalse("sigue pidiendo el permiso", hay("dar-permiso"))
        }
    }

    /** «Sincronizar esta pareja» (en su ficha) pasa esa y ninguna más, ni el llavero. */
    @Test
    fun sincronizar_una_pareja_no_pasa_las_demas() {
        Banco.volumen(Banco.pareja("uno"), Banco.pareja("dos"))
        ActivityScenario.launch(MainActivity::class.java).use { escena ->
            var modelo: Modelo? = null
            escena.onActivity { modelo = ViewModelProvider(it)[Modelo::class.java] }
            compose.waitUntil(30_000) { hay("fila-uno") && !hay("ocupado") }
            escena.onActivity { modelo!!.sincronizar(solo = "uno") }
            compose.waitUntil(60_000) { modelo!!.resultados.containsKey("uno") }
            compose.waitUntil(60_000) { !hay("ocupado") }
            assertNull("pasó también «dos»", modelo!!.resultados["dos"])
        }
    }
}
