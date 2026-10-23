package prdrive.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.rclone.Adaptadores
import prdrive.engine.Catalogo
import prdrive.engine.Conflictos
import prdrive.engine.Pasada
import prdrive.engine.Resultados

/**
 * PasadaEnAndroidTest.kt — La pasada entera ([Pasada.pasar]) contra rclone de
 * verdad, y el catálogo leído con el adaptador del RPC.
 *
 * Es el paso 3 en un dispositivo: lo que en el motor se prueba con un rclone de
 * mentira, aquí va contra la biblioteca. El remoto sigue siendo una carpeta
 * local (la prueba contra un servidor de verdad es la del paso 4).
 */
@RunWith(AndroidJUnit4::class)
class PasadaEnAndroidTest {

    @get:Rule
    val legible = Banco.Legible()

    /** Lo que dijo la pasada y su log, para que un fallo se explique solo. */
    private fun todo(r: Pasada.Resultado): String = r.lineas.joinToString("\n") + "\n" + (r.log?.readText() ?: "")

    private fun pasar(v: prdrive.engine.Volumen, p: prdrive.engine.Pareja, ctx: Pasada.Contexto = Pasada.Contexto()) =
        Pasada.pasar(v, p, Adaptadores.pasada, Adaptadores.opciones, ctx)

    @Test
    fun una_pareja_nueva_se_inicializa_y_despues_sincroniza_en_los_dos_sentidos() {
        val (v, config) = Banco.volumen(Banco.pareja("notas"))
        val p = config.pairs.single()
        File(Banco.remoto, "notas").mkdirs()
        File(Banco.remoto, "notas/del-remoto.txt").writeText("hola")

        // Nueva y sin aprobar: se salta, como en el escritorio.
        assertEquals(Pasada.SALTADA, pasar(v, p).codigo)

        val inicial = pasar(v, p, Pasada.Contexto(resyncAprobado = true))
        assertEquals(inicial.lineas.joinToString("\n"), 0, inicial.codigo)
        assertTrue(inicial.resync)
        assertEquals("hola", File(v.raiz, "sync-data/notas/del-remoto.txt").readText())

        File(v.raiz, "sync-data/notas/del-telefono.txt").writeText("adiós")
        val normal = pasar(v, p, Pasada.Contexto(keepLogs = true))
        assertEquals(todo(normal), 0, normal.codigo)
        assertEquals(false, normal.resync)
        assertEquals("adiós", File(Banco.remoto, "notas/del-telefono.txt").readText())
        assertNotNull(Resultados.ultimasBuenas(v.state, listOf("notas"))["notas"])
        assertEquals(emptyList<Any>(), Resultados.fallosDe(v.state, v.logs, listOf("notas")))
    }

    @Test
    fun un_cambio_en_los_dos_lados_deja_un_conflicto_que_se_ve() {
        val (v, config) = Banco.volumen(Banco.pareja("notas"))
        val p = config.pairs.single()
        File(Banco.remoto, "notas").mkdirs()
        File(Banco.remoto, "notas/plan.md").writeText("uno")
        // Uno que no se toca: si cambiaran todos los ficheros de un lado, bisync
        // pararía por su freno de seguridad en vez de resolver el conflicto.
        File(Banco.remoto, "notas/quieto.txt").writeText("quieto")
        assertEquals(0, pasar(v, p, Pasada.Contexto(resyncAprobado = true)).codigo)

        File(v.raiz, "sync-data/notas/plan.md").writeText("cambiado en el teléfono")
        File(Banco.remoto, "notas/plan.md").writeText("cambiado en el remoto, y más largo")
        // Que el del remoto sea claramente el más nuevo.
        File(Banco.remoto, "notas/plan.md").setLastModified(System.currentTimeMillis() + 60_000)
        val r = pasar(v, p, Pasada.Contexto(keepLogs = true))
        assertEquals(todo(r), 0, r.codigo)
        assertEquals(todo(r), 1, r.conflictos.size)
        val conflicto = r.conflictos.single()
        assertEquals("plan.md", conflicto.relativa)
        // El perdedor es la del teléfono, y se sabe que es de este lado.
        assertNotNull(conflicto.version(Conflictos.DISPOSITIVO))
        assertEquals(1, Conflictos.contar(Conflictos.cargar(config, v.raiz, v.state))["notas"])
    }

    @Test
    fun una_pasada_que_falla_guarda_el_log_y_lo_explica() {
        val (v, config) = Banco.volumen(Banco.pareja("notas"))
        val p = config.pairs.single()
        File(Banco.remoto, "notas").mkdirs()
        assertEquals(0, pasar(v, p, Pasada.Contexto(resyncAprobado = true)).codigo)
        // Alguien deja el lock de otra pasada que aún no ha caducado. Tiene que
        // ser JSON como el que escribe rclone: con --max-lock puesto, uno que no
        // se puede leer cuenta como caducado (cmd/bisync/lockfile.go,
        // lockFileIsExpired).
        val lck = v.workdir(p).listFiles()!!.first { it.name.endsWith(".path1.lst") }
            .let { File(it.parentFile, it.name.removeSuffix(".path1.lst") + ".lck") }
        val ahora = java.time.Instant.now()
        lck.writeText("""{"Session":"x","PID":"9999","TimeRenewed":"$ahora","TimeExpires":"${ahora.plusSeconds(600)}"}""")
        val r = pasar(v, p)
        assertEquals(Pasada.FALLO, r.codigo)
        assertNotNull(r.log)
        assertNotNull("sin explicación para: ${r.log!!.readText()}", r.explicacion)
        assertEquals(r.log, Resultados.fallosDe(v.state, v.logs, listOf("notas")).single().log)
    }

    @Test
    fun una_pasada_cancelada_para_rclone_no_se_apunta_y_no_deja_el_lock_puesto() {
        val (v, config) = Banco.volumen(Banco.pareja("notas", "sync-data/notas", "flags" to mapOf("transfers" to 1, "checkers" to 1)))
        val p = config.pairs.single()
        File(Banco.remoto, "notas").mkdirs()
        // Muchos ficheros, de uno en uno: lo bastante para cortarla a medias.
        repeat(3000) { File(Banco.remoto, "notas/f$it.txt").writeText("fichero $it\n".repeat(50)) }
        var lanzado: Throwable? = null
        var codigo: Int? = null
        val hilo = Thread {
            try {
                codigo = pasar(v, p, Pasada.Contexto(resyncAprobado = true)).codigo
            } catch (e: Throwable) {
                lanzado = e
            }
        }
        hilo.start()
        Thread.sleep(1500)
        hilo.interrupt()
        hilo.join(60_000)
        assertNull("la pasada acabó antes de cortarla (código $codigo): hacen falta más ficheros", codigo)
        assertTrue("lanzó $lanzado", lanzado is java.util.concurrent.CancellationException)
        // No se apunta, ni como buena ni como fallida.
        assertNull(Resultados.ultimasBuenas(v.state, listOf("notas"))["notas"])
        assertEquals(emptyList<Any>(), Resultados.fallosDe(v.state, v.logs, listOf("notas")))
        // Y la siguiente no se encuentra un lock de la cortada.
        val r = pasar(v, p, Pasada.Contexto(resyncAprobado = true, keepLogs = true))
        assertEquals(todo(r), 0, r.codigo)
    }

    /**
     * Parada a medias, rclone puede contestar «Bisync successful» y dejar unos
     * listados con copias que no ha hecho: `sync` se traga el
     * `context.Canceled` de un `job/stop` si no había una transferencia en
     * vuelo (fs/sync/sync.go, `processError`). La pasada siguiente vería
     * «borrados» en el teléfono los ficheros que no llegaron, y los borraría del
     * remoto.
     */
    @Test
    fun una_pasada_normal_cancelada_no_deja_un_baseline_que_borre_en_el_remoto() {
        val (v, config) = Banco.volumen(Banco.pareja("notas", "sync-data/notas", "flags" to mapOf("transfers" to 1, "checkers" to 1)))
        val p = config.pairs.single()
        File(Banco.remoto, "notas").mkdirs()
        File(Banco.remoto, "notas/quieto.txt").writeText("quieto")
        assertEquals(0, pasar(v, p, Pasada.Contexto(resyncAprobado = true)).codigo)
        repeat(3000) { File(Banco.remoto, "notas/f$it.txt").writeText("fichero $it\n".repeat(50)) }
        var lanzado: Throwable? = null
        var codigo: Int? = null
        // Se corta con las copias ya en marcha: es la fase en la que rclone dice
        // que ha ido bien. Cortada al listar o comparar, aborta como debe.
        val copiando = java.util.concurrent.CountDownLatch(1)
        val hilo = Thread {
            try {
                codigo = Pasada.pasar(v, p, Adaptadores.pasada, Adaptadores.opciones, alProgreso = {
                    if (((it["transfers"] as? Number)?.toLong() ?: 0L) > 0L) copiando.countDown()
                }).codigo
            } catch (e: Throwable) {
                lanzado = e
            }
        }
        hilo.start()
        assertTrue("no ha empezado a copiar", copiando.await(60, java.util.concurrent.TimeUnit.SECONDS))
        hilo.interrupt()
        hilo.join(60_000)
        assertNull("la pasada acabó antes de cortarla (código $codigo): hacen falta más ficheros", codigo)
        assertTrue("lanzó $lanzado", lanzado is java.util.concurrent.CancellationException)

        // La siguiente no se fía del baseline de la cortada: pide --resync, y en
        // el remoto no se ha borrado nada.
        val r = pasar(v, p, Pasada.Contexto(keepLogs = true))
        assertEquals(todo(r), Pasada.SALTADA, r.codigo)
        assertEquals(3001, File(Banco.remoto, "notas").list()!!.size)
    }

    @Test
    fun un_espejo_de_bajada_trae_lo_del_remoto_y_su_log_se_captura() {
        val (v, config) = Banco.volumen(Banco.pareja("bajada", "sync-data/bajada", "mode" to "down-mirror"))
        val p = config.pairs.single()
        File(Banco.remoto, "bajada").mkdirs()
        File(Banco.remoto, "bajada/a.txt").writeText("a")
        val r = pasar(v, p, Pasada.Contexto(keepLogs = true))
        assertEquals(r.lineas.joinToString("\n"), 0, r.codigo)
        assertEquals("a", File(v.raiz, "sync-data/bajada/a.txt").readText())
        // `sync/sync` no devuelve su log: lo captura el adaptador.
        assertTrue("el log no cuenta la copia: ${r.log?.readText()}", r.log!!.readText().contains("a.txt"))
    }

    @Test
    fun el_catalogo_se_lee_del_remoto_con_el_rpc() {
        val (v, _) = Banco.volumen(Banco.pareja("notas"))
        val carpeta = File(Banco.remoto, "prdrive-catalog").apply { mkdirs() }
        val texto = "[defaults]\nremote = \"nas\"\n\n[[pair]]\nname = \"notas\"\nlocal = \"sync-data/notas\"\nremote_path = \"/x\"\n"
        File(carpeta, "pairs.toml").writeText(texto)
        val local = mapOf("defaults" to mapOf("remote" to "nas", "catalog_path" to File(carpeta, "remote.toml").absolutePath))
        val ejecutar = Adaptadores.catalogo(File(Banco.contexto.cacheDir, "catalogo"))

        // remote.toml no está: se lee el pairs.toml de un remoto de antes.
        val cat = Catalogo.pull(ejecutar, local, v.state)
        assertEquals(listOf("notas"), cat.names)
        assertTrue(cat.endpoint.endsWith("pairs.toml"))
        assertTrue(Catalogo.cacheToml(v.state).isFile)

        // Y si la ruta es la carpeta, se dice.
        val carpetaComoRuta = mapOf("defaults" to mapOf("remote" to "nas", "catalog_path" to carpeta.absolutePath))
        val e = runCatching { Catalogo.pull(ejecutar, carpetaComoRuta, v.state) }.exceptionOrNull()
        assertTrue("no explica la carpeta: ${e?.message}", e?.message?.contains("es una carpeta") == true)

        // Un remoto sin catálogo: «no hay catálogo» nombrando los dos ficheros.
        File(carpeta, "pairs.toml").delete()
        val (sin, aviso) = Catalogo.load(ejecutar, local, File(v.state, "otro"))
        assertNull(sin)
        assertTrue("aviso: $aviso", aviso!!.contains("remote.toml") && aviso.contains("pairs.toml"))
    }
}
