package prdrive.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.segundoplano.SegundoPlano
import prdrive.app.segundoplano.TrabajoParejas
import prdrive.engine.Resultados

/**
 * SincronizarSolaTest.kt — «Sincronizar sola»: el trabajo periódico de
 * WorkManager que pasa las parejas (y el llavero) sin abrir la app, contra el
 * rclone de verdad, y cómo se programa según los ajustes del teléfono.
 *
 * WorkManager es el de prueba (`work-testing`): el de verdad ejecutaría el
 * trabajo encima del volumen del test siguiente.
 */
@RunWith(AndroidJUnit4::class)
class SincronizarSolaTest {

    @get:Rule
    val legible = Banco.Legible()

    private val contexto get() = Banco.contexto

    @Before
    fun workManagerDePrueba() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            contexto,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        SegundoPlano.guardar(contexto, SegundoPlano.Ajustes(), intervaloMin = 60)
    }

    private fun trabajos(): List<WorkInfo> =
        WorkManager.getInstance(contexto).getWorkInfosForUniqueWork(SegundoPlano.NOMBRE).get()
            .filter { !it.state.isFinished }

    @Test
    fun el_trabajo_pasa_las_parejas_y_las_apunta() {
        val (v, _) = Banco.volumen(Banco.pareja("fotos", "sync-data/fotos", "mode" to "down"), Banco.pareja("notas"))
        File(Banco.remoto, "fotos").apply { mkdirs() }.resolve("foto.txt").writeText("una foto\n")
        val r = runBlocking { TestListenableWorkerBuilder<TrabajoParejas>(contexto).build().doWork() }
        assertEquals(Result.success(), r)
        assertEquals("una foto\n", File(v.raiz, "sync-data/fotos/foto.txt").readText())
        assertNotNull("no apuntó la pasada", Resultados.ultimasBuenas(v.state, listOf("fotos"))["fotos"])
        // Una bisync sin referencia se salta: no se rehace sola, sin que nadie lo apruebe.
        assertNull(Resultados.ultimasBuenas(v.state, listOf("notas"))["notas"])
    }

    @Test
    fun activarlo_programa_un_trabajo_periodico_con_sus_condiciones() {
        Banco.volumen(Banco.pareja("notas"))
        assertTrue("hay un trabajo sin activarlo", trabajos().isEmpty())

        SegundoPlano.guardar(contexto, SegundoPlano.Ajustes(activo = true, soloWifi = true, soloCargando = true), intervaloMin = 90)
        val t = trabajos().single()
        assertEquals(NetworkType.UNMETERED, t.constraints.requiredNetworkType)
        assertTrue(t.constraints.requiresCharging())
        assertEquals(TimeUnit.MINUTES.toMillis(90), t.periodicityInfo?.repeatIntervalMillis)
        assertTrue(SegundoPlano.leer(contexto).activo)

        SegundoPlano.guardar(contexto, SegundoPlano.Ajustes(activo = true, soloWifi = false), intervaloMin = 60)
        val otro = trabajos().single()
        assertEquals(NetworkType.CONNECTED, otro.constraints.requiredNetworkType)
        assertFalse(otro.constraints.requiresCharging())

        SegundoPlano.guardar(contexto, SegundoPlano.Ajustes(activo = false), intervaloMin = 60)
        assertTrue("sigue programado tras quitarlo", trabajos().isEmpty())
    }

    @Test
    fun el_intervalo_sale_del_daemon_del_config_y_no_baja_de_quince_minutos() {
        val (v, _) = Banco.volumen(Banco.pareja("notas"))
        val conDaemon = { minutos: Any? ->
            val raw = prdrive.engine.loadRaw(v.config.readText()).toMutableMap()
            if (minutos == null) raw.remove("daemon") else raw["daemon"] = mapOf("interval_minutes" to minutos)
            v.escribirConfig(raw, "# Para los tests.\n")
            SegundoPlano.intervalo(v.leerConfig())
        }
        assertEquals(60L, conDaemon(null))
        assertEquals(30L, conDaemon(30L))
        assertEquals(15L, conDaemon(5L))
    }
}
