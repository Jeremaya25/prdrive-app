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
import java.net.ServerSocket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.llavero.Disparos
import prdrive.app.llavero.LlaveroApp
import prdrive.app.llavero.TrabajoLlavero
import prdrive.app.rclone.Rclone
import prdrive.engine.LLAVERO
import prdrive.engine.Pasada
import prdrive.engine.Resultados
import prdrive.engine.Volumen

/**
 * TrabajoLlaveroTest.kt — Las pasadas del llavero en segundo plano
 * ([TrabajoLlavero], [LlaveroApp], [Disparos]) contra el rclone de verdad.
 *
 * WorkManager es el de prueba (`work-testing`) en todos los tests, también en
 * los que solo llaman al trabajo: una pasada que deja algo pendiente encola otra
 * ([Disparos.siPendiente]), y en el WorkManager de verdad se ejecutaría sola
 * encima del volumen del test siguiente.
 */
@RunWith(AndroidJUnit4::class)
class TrabajoLlaveroTest {

    private val contexto get() = Banco.contexto

    @Before
    fun workManagerDePrueba() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            contexto,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    /** Lo que contesta el trabajo, ejecutado como lo ejecutaría WorkManager. */
    private fun trabajar(): Result = runBlocking {
        TestListenableWorkerBuilder<TrabajoLlavero>(contexto).build().doWork()
    }

    /** Los trabajos del llavero que aún no han acabado. */
    private fun encolados(): List<WorkInfo> =
        WorkManager.getInstance(contexto).getWorkInfosForUniqueWork(Disparos.NOMBRE).get()
            .filter { !it.state.isFinished }

    /** El código que `state/` tiene apuntado para el llavero, si falló. */
    private fun fallo(v: Volumen): Int? =
        Resultados.fallosDe(v.state, v.logs, listOf(LLAVERO)).singleOrNull()?.codigo?.toInt()

    /** Lo que dice el log del último fallo del llavero. */
    private fun logDelFallo(v: Volumen): String =
        Resultados.fallosDe(v.state, v.logs, listOf(LLAVERO)).single().log?.readText() ?: ""

    /** El volumen con el llavero y su base en el remoto, para una pasada buena. */
    private fun conLlaveroEnElRemoto(): Volumen {
        val (v, _) = Banco.volumen(Banco.pareja("notas"), llavero = "personal.kdbx")
        val remoto = File(Banco.remoto, "catalogo/keychain").apply { mkdirs() }
        File(remoto, "personal.kdbx").writeBytes(Banco.base(entera = true))
        return v
    }

    @Test
    fun sin_llavero_o_sin_volumen_el_trabajo_acaba_bien_sin_hacer_nada() {
        // Sin volumen (aún sin emparejar): ni se crea nada.
        val v = Prdrive.volumen(contexto)
        v.raiz.deleteRecursively()
        assertEquals(Result.success(), trabajar())
        assertNull(LlaveroApp.pasar(contexto))
        assertFalse("el trabajo ha creado el volumen", v.raiz.exists())
        assertFalse(LlaveroApp.pendiente(contexto))
        assertFalse(LlaveroApp.reciente(contexto))

        // Con volumen y sin llavero (se ha quitado con un trabajo encolado).
        val (sin, _) = Banco.volumen(Banco.pareja("notas"))
        assertEquals(Result.success(), trabajar())
        assertNull(LlaveroApp.pasar(contexto))
        assertFalse("se ha apuntado una pasada", Resultados.rutaEstado(sin.state).exists())
        assertFalse(LlaveroApp.pendiente(contexto))
        assertTrue(encolados().isEmpty())
    }

    @Test
    fun una_base_rota_no_se_reintenta_y_un_fallo_de_red_si() {
        // Una base que no está entera: no se sube, y reintentar no la arregla.
        val (v, config) = Banco.volumen(Banco.pareja("notas"), llavero = "personal.kdbx")
        val carpeta = config.parejaLlavero!!.carpetaEn(v.raiz).apply { mkdirs() }
        File(carpeta, "personal.kdbx").writeBytes(Banco.base(entera = false))
        assertEquals(Result.success(), trabajar())
        assertEquals(Pasada.LLAVERO_ROTO, fallo(v))
        // Y la fila dice que está rota.
        assertEquals(Estado.EXPLICACION_ROTA, Estado.llavero(v, v.leerConfig())!!.explicacion)

        // Sin red: un sftp en un puerto de esta máquina donde no escucha nadie.
        val puerto = ServerSocket(0).use { it.localPort }
        val red = Prdrive.volumen(contexto)
        red.raiz.deleteRecursively()
        red.escribirConfig(
            mapOf(
                "defaults" to mapOf(
                    "device_remote" to "disp", "remote" to "red", "catalog_path" to "/srv/catalogo/remote.toml",
                ),
                "pair" to listOf(Banco.pareja("notas")),
                "keychain" to mapOf("base" to "personal.kdbx"),
            ),
            "# Para los tests.\n",
        )
        val conRed = red.leerConfig()
        // Con contraseña: sin ella, el sftp de rclone busca un agente SSH y no
        // llega a llamar a la puerta.
        val clave = Rclone.rpc("core/obscure", mapOf("clear" to "prueba"))["obscured"]
        red.rcloneConf.writeText(
            "[red]\ntype = sftp\nhost = 127.0.0.1\nport = $puerto\nuser = prueba\npass = $clave\n\n" +
                conRed.seccionCombine(red.raiz.absolutePath),
        )
        Prdrive.iniciarRclone(contexto)
        Rclone.rpc("fscache/clear")
        conRed.parejaLlavero!!.carpetaEn(red.raiz).apply { mkdirs() }
            .let { File(it, "personal.kdbx").writeBytes(Banco.base(entera = true)) }

        assertEquals(Result.retry(), trabajar())
        assertEquals(Pasada.FALLO, fallo(red))
        val log = logDelFallo(red)
        assertTrue("no es un fallo de red: $log", Pasada.esDeRed(log))
    }

    @Test
    fun un_aborto_que_no_es_una_base_rota_se_reintenta_y_la_fila_no_dice_que_lo_este() {
        // `.keychain` es un enlace a `.prdrive/`: la pasada aborta antes de
        // llamar a rclone (`problemaContencion`) con el mismo código que una
        // base rota, y ninguna lo está.
        val (v, config) = Banco.volumen(Banco.pareja("notas"), llavero = "personal.kdbx")
        val carpeta = config.parejaLlavero!!.carpetaEn(v.raiz)
        carpeta.deleteRecursively()
        java.nio.file.Files.createSymbolicLink(carpeta.toPath(), v.programa.toPath())
        assertEquals(Pasada.LLAVERO_ROTO, Pasada.ABORTO)

        assertEquals(Result.retry(), trabajar())
        assertEquals(Pasada.ABORTO, fallo(v))
        val fila = Estado.llavero(v, v.leerConfig())!!
        assertNotNull(fila.fallo)
        assertNotEquals(Estado.EXPLICACION_ROTA, fila.explicacion)
    }

    @Test
    fun dos_pasadas_del_llavero_a_la_vez_van_una_detras_de_otra() {
        conLlaveroEnElRemoto()
        // Cada pasada apunta cuándo empieza («=== keychain») y cuándo acaba bien.
        val tramos = Collections.synchronizedList(ArrayList<LongArray>())
        val resultados = Collections.synchronizedList(ArrayList<Pasada.Resultado?>())
        val salida = CyclicBarrier(2)
        val hilos = List(2) {
            Thread {
                val tramo = longArrayOf(-1, -1)
                salida.await()
                resultados += LlaveroApp.pasar(contexto, decir = { linea ->
                    if (linea.startsWith("=== $LLAVERO ")) tramo[0] = System.nanoTime()
                    if (linea.startsWith("[$LLAVERO] OK.")) tramo[1] = System.nanoTime()
                })
                tramos += tramo
            }
        }
        hilos.forEach { it.start() }
        hilos.forEach { it.join(120_000) }

        assertEquals(2, resultados.size)
        resultados.forEach { assertEquals(it?.lineas?.joinToString("\n"), 0, it?.codigo) }
        val (a, b) = tramos.sortedBy { it[0] }
        assertTrue("una pasada no ha empezado o acabado: ${tramos.map { it.toList() }}", tramos.all { it[0] > 0 && it[1] > 0 })
        assertTrue("las dos pasadas se han solapado: ${a.toList()} y ${b.toList()}", a[1] < b[0])
    }

    @Test
    fun cada_guardado_aplaza_la_subida_y_leer_no_encola_si_es_reciente() {
        conLlaveroEnElRemoto()

        // Cada guardado sustituye al trabajo que esperaba: queda uno, con su
        // calma por delante y pidiendo red.
        Disparos.alGuardar(contexto)
        val primero = encolados().single()
        Disparos.alGuardar(contexto)
        val segundo = encolados().single()
        assertNotEquals(primero.id, segundo.id)
        assertEquals(WorkInfo.State.ENQUEUED, segundo.state)
        assertEquals(Disparos.CALMA.toMillis(), segundo.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, segundo.constraints.requiredNetworkType)

        // Leer sin una pasada reciente encola uno sin retraso, que se queda
        // aunque se vuelva a leer.
        WorkManager.getInstance(contexto).cancelUniqueWork(Disparos.NOMBRE).result.get()
        Disparos.alLeer(contexto)
        val leido = encolados().single()
        assertEquals(0L, leido.initialDelayMillis)
        Disparos.alLeer(contexto)
        assertEquals(leido.id, encolados().single().id)
        WorkManager.getInstance(contexto).cancelUniqueWork(Disparos.NOMBRE).result.get()

        // Tras una pasada buena no queda nada pendiente, y leer no encola.
        val r = LlaveroApp.pasar(contexto)
        assertEquals(r?.lineas?.joinToString("\n"), 0, r?.codigo)
        assertTrue(LlaveroApp.reciente(contexto))
        assertFalse(LlaveroApp.pendiente(contexto))
        assertTrue(encolados().isEmpty())
        Disparos.alLeer(contexto)
        assertTrue("leer ha encolado una pasada justo después de otra", encolados().isEmpty())
    }

    /** Espera a que [hecho] sea cierto, o falla diciendo [que]. */
    private fun esperar(que: String, maxMs: Long = 60_000, hecho: () -> Boolean) {
        val hasta = System.currentTimeMillis() + maxMs
        while (!hecho()) {
            if (System.currentTimeMillis() > hasta) throw AssertionError("no ha pasado: $que")
            Thread.sleep(50)
        }
    }

    /** Los locks de bisync que haya en el workdir del llavero. */
    private fun locks(v: Volumen): List<File> =
        v.workdir(v.leerConfig().parejaLlavero!!).walkTopDown().filter { it.name.endsWith(".lck") }.toList()

    @Test
    fun un_guardado_durante_una_pasada_no_la_corta_y_el_trabajo_nuevo_corre_despues() {
        // Un WorkManager de prueba que ejecuta los trabajos en otro hilo: el
        // trabajo tiene que estar corriendo mientras se guarda.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            contexto,
            Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).build(),
        )
        val driver = WorkManagerTestInitHelper.getTestDriver(contexto)!!
        val wm = WorkManager.getInstance(contexto)
        fun soltar(id: UUID) {
            driver.setInitialDelayMet(id)
            driver.setAllConstraintsMet(id)
        }

        val v = conLlaveroEnElRemoto()
        val primera = LlaveroApp.pasar(contexto)
        assertEquals(primera?.lineas?.joinToString("\n"), 0, primera?.codigo)
        val antes = Resultados.ultimasBuenas(v.state, listOf(LLAVERO)).getValue(LLAVERO)
        val local = File(v.raiz, ".keychain")
        val remoto = File(Banco.remoto, "catalogo/keychain")

        // La base cambia en el remoto (otro contenido: con el mismo, rclone solo
        // le pondría la hora), y bajarla va despacio (rclone a 50 B/s): la
        // pasada dura unos segundos.
        val baseRemota = File(remoto, "personal.kdbx")
        val hora = baseRemota.lastModified() + 60_000
        baseRemota.writeBytes(Banco.otraBase())
        assertTrue(baseRemota.setLastModified(hora))
        Rclone.rpc("core/bwlimit", mapOf("rate" to "50B"))
        try {
            Disparos.alGuardar(contexto)
            val trabajo = encolados().single().id
            soltar(trabajo)
            try {
                esperar("la pasada del trabajo no ha empezado") { locks(v).isNotEmpty() }
            } catch (e: AssertionError) {
                val estado = Resultados.rutaEstado(v.state).takeIf { it.isFile }?.readText()
                val logs = v.logs.listFiles()?.sortedBy { it.name }?.lastOrNull()?.readText()
                throw AssertionError(
                    "${e.message}: ${wm.getWorkInfoById(trabajo).get()?.state}; base remota " +
                        "${baseRemota.lastModified()}, local ${File(local, "personal.kdbx").lastModified()}; " +
                        "state: $estado; log: $logs",
                )
            }
            assertEquals(WorkInfo.State.RUNNING, wm.getWorkInfoById(trabajo).get()?.state)

            // Se guarda otra base en mitad de la pasada: el guardado sustituye al
            // trabajo que está corriendo.
            File(local, "otra.kdbx").writeBytes(Banco.base(entera = true))
            Disparos.alGuardar(contexto)
            val siguiente = encolados().single().id
            assertNotEquals(trabajo, siguiente)

            // La pasada en marcha acaba entera: se apunta, trae la base y no deja
            // el lock de bisync puesto.
            LlaveroApp.enExclusiva { }
            val despues = Resultados.ultimasBuenas(v.state, listOf(LLAVERO))[LLAVERO]
            assertNotNull(despues)
            assertTrue("la pasada cortada no se ha apuntado como buena", despues!! > antes)
            assertTrue("la pasada no ha traído la base", Banco.otraBase().contentEquals(File(local, "personal.kdbx").readBytes()))
            assertEquals(emptyList<File>(), locks(v))

            // Y el trabajo que la sustituyó corre después, tras su calma, y acaba
            // bien con lo guardado arriba. (La propia pasada puede haberlo subido
            // ya: el reapunte del llavero repite la pasada si una base no está en
            // su listado.)
            Rclone.rpc("core/bwlimit", mapOf("rate" to "off"))
            soltar(siguiente)
            esperar("el trabajo siguiente no ha acabado") { wm.getWorkInfoById(siguiente).get()?.state?.isFinished == true }
            assertEquals(WorkInfo.State.SUCCEEDED, wm.getWorkInfoById(siguiente).get()?.state)
            assertTrue("lo guardado durante la pasada no ha subido", File(remoto, "otra.kdbx").isFile)
            assertTrue(Banco.base(entera = true).contentEquals(File(remoto, "otra.kdbx").readBytes()))
        } finally {
            Rclone.rpc("core/bwlimit", mapOf("rate" to "off"))
        }
    }
}
