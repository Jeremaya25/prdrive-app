package prdrive.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SincronizacionTest.kt — La pasada entera, sin rclone y sin teléfono.
 *
 * Aquí no se comprueba qué se le manda a rclone —eso es `PasadaTest` y
 * `SesionesTest`— sino **el orden y las decisiones**: qué se salta, qué se
 * aborta, qué se apunta y qué se avisa. Son las que en el escritorio están en
 * `run_pair`, y las que si se equivocan no dan un error sino un baseline
 * perdido o un fallo que nadie ve.
 *
 * El [Rclone] es un guion: contesta lo que contestaría rclone y apunta lo que
 * le han pedido.
 */
class SincronizacionTest {

    private val config = parseConfig(Vectores.objeto(Vectores.seccion("resueltas"), "config"))
    private val notas = config.pairs.first { it.name == "notas" }
    private val espejo = config.pairs.first { it.name == "espejo" }

    /** Un rclone de guion: un job que acaba a la segunda vuelta. */
    private class Guion(
        val exito: Boolean = true,
        val log: String = "",
        val sesion: String? = null,
        val fallaLaLlamada: String? = null,
        val vueltasHastaAcabar: Int = 2,
    ) : Rclone {
        val metodos = ArrayList<String>()
        val entradas = LinkedHashMap<String, String>()
        var nivel: String? = null
        var reinicios = 0
        private var vueltas = 0

        override fun rpc(metodo: String, entrada: String): RespuestaRpc {
            metodos.add(metodo)
            entradas[metodo] = entrada
            return when (metodo) {
                "job/status" -> {
                    vueltas++
                    val acabado = vueltas >= vueltasHastaAcabar
                    RespuestaRpc(
                        escribirJson(
                            mapOf(
                                "finished" to acabado,
                                "success" to (acabado && exito),
                                "error" to if (acabado && !exito) "bisync aborted" else "",
                                "output" to buildMap {
                                    if (acabado) {
                                        put("output", log)
                                        sesion?.let { put("session", it) }
                                    }
                                },
                            ),
                        ),
                        200,
                    )
                }
                "core/stats" -> RespuestaRpc(
                    escribirJson(mapOf("bytes" to 512L, "totalBytes" to 1024L, "speed" to 256.0)),
                    200,
                )
                else -> if (fallaLaLlamada != null) {
                    RespuestaRpc(escribirJson(mapOf("error" to fallaLaLlamada)), 500)
                } else {
                    RespuestaRpc(escribirJson(mapOf("jobid" to 1L)), 200)
                }
            }
        }

        override fun logReiniciar() { reinicios++ }
        override fun logTexto(): String = log
        override fun nivelDeLog(nombre: String) { nivel = nombre }
    }

    /** Lo que la pantalla habría visto. */
    private class Testigo : Observador {
        val empezadas = ArrayList<String>()
        val resyncs = ArrayList<String>()
        val progresos = ArrayList<Progreso>()
        val avisos = ArrayList<String>()
        val acabadas = ArrayList<Resultado>()

        override fun empieza(pareja: Pareja, resync: Boolean) {
            empezadas.add(pareja.name)
            if (resync) resyncs.add(pareja.name)
        }

        override fun progreso(pareja: Pareja, progreso: Progreso) { progresos.add(progreso) }
        override fun avisa(texto: String) { avisos.add(texto) }
        override fun acaba(resultado: Resultado) { acabadas.add(resultado) }
    }

    private fun conVolumen(prueba: (Volumen) -> Unit) {
        val raiz = File(System.getProperty("java.io.tmpdir"), "prdrive-sinc-${System.nanoTime()}")
        try {
            val volumen = Volumen(raiz)
            volumen.crear()
            prueba(volumen)
        } finally {
            raiz.deleteRecursively()
        }
    }

    private fun sincronizador(volumen: Volumen, rclone: Rclone) =
        Sincronizador(rclone, volumen, cada = 0, esperar = {})

    /** Un baseline de mentira, con el nombre que el motor espera. */
    private fun ponerBaseline(volumen: Volumen, pareja: Pareja) {
        if (!pareja.isBisync) return       // un modo espejo no tiene baseline
        val wd = volumen.workdir(pareja)
        wd.mkdirs()
        val prefijo = Bisync.expectedPrefix(pareja)
        File(wd, "$prefijo${Bisync.PATH1_SUFFIX}").writeText("x")
        File(wd, "$prefijo${Bisync.PATH2_SUFFIX}").writeText("x")
        // El fichero de filtros y su md5, que si no la pareja pediría resync.
        val ffile = Bisync.filtersFileFor(pareja, volumen.filters)!!
        File("${ffile.path}.md5").writeText(md5(ffile.readBytes()))
    }

    private fun md5(datos: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5").digest(datos)
            .joinToString("") { "%02x".format(it) }

    // -----------------------------------------------------------------------

    @Test
    fun `una pareja sin baseline se SALTA si nadie ha aprobado el resync`() {
        // Rehacer un baseline es la operación que decide qué se borra, así que
        // no se hace sin que alguien diga sí. Y una saltada NO se apunta: no se
        // ha ejecutado, así que su último resultado sigue siendo el anterior.
        conVolumen { v ->
            val rclone = Guion()
            val obs = Testigo()
            val r = sincronizador(v, rclone).unaPareja(notas, obs = obs)
            assertEquals(Pasada.SALTADA, r.codigo)
            assertTrue(r.saltada)
            assertTrue(obs.avisas("requiere --resync"), obs.avisos.toString())
            assertTrue("sync/bisync" !in rclone.metodos, "no se ha ejecutado nada")
            assertEquals(emptyList(), Resultados.fallos(v.state, v.logs, listOf("notas")))
        }
    }

    @Test
    fun `aprobado el resync, la pasada va y lo dice`() {
        conVolumen { v ->
            val rclone = Guion(sesion = Bisync.expectedPrefix(notas))
            val obs = Testigo()
            val r = sincronizador(v, rclone).unaPareja(
                notas, OpcionesDePasada(resyncAprobado = true), obs,
            )
            assertTrue(r.ok, r.error)
            assertEquals(listOf("notas"), obs.resyncs)
            assertTrue(leerObjetoJson(rclone.entradas.getValue("sync/bisync"))["resync"] == true)
            // Y la carpeta local se ha creado, que es lo que pasa la primera vez.
            assertTrue(v.carpetaLocal(notas).isDirectory)
        }
    }

    @Test
    fun `con baseline bueno no hace falta resync y no se pide`() {
        conVolumen { v ->
            ponerBaseline(v, notas)
            v.carpetaLocal(notas).mkdirs()
            val rclone = Guion(sesion = Bisync.expectedPrefix(notas))
            val obs = Testigo()
            val r = sincronizador(v, rclone).unaPareja(notas, obs = obs)
            assertTrue(r.ok, r.error)
            assertEquals(emptyList(), obs.resyncs)
            assertTrue("resync" !in leerObjetoJson(rclone.entradas.getValue("sync/bisync")))
        }
    }

    @Test
    fun `con baseline y sin carpeta local se ABORTA, y no se crea vacía`() {
        // El aborto deliberado de `_bisync_preflight`: una carpeta local vacía
        // donde había baseline le dice a bisync que se ha borrado todo, con
        // `--max-delete` como único freno.
        conVolumen { v ->
            ponerBaseline(v, notas)
            val rclone = Guion()
            val obs = Testigo()
            val r = sincronizador(v, rclone).unaPareja(notas, obs = obs)
            assertTrue(!r.ok && !r.saltada, "tiene que ser un fallo, no una saltada")
            assertTrue("no se crea vacía" in r.error, r.error)
            assertTrue(!v.carpetaLocal(notas).exists(), "NO se ha creado la carpeta")
            assertTrue("sync/bisync" !in rclone.metodos, "no se ha ejecutado nada")
            // Y esto sí se apunta: es un fallo de verdad.
            assertEquals(listOf("notas"), Resultados.fallos(v.state, v.logs, listOf("notas")).map { it.pareja })
        }
    }

    @Test
    fun `una pasada fallida se apunta, guarda su log y lo traduce`() {
        conVolumen { v ->
            ponerBaseline(v, notas)
            v.carpetaLocal(notas).mkdirs()
            val log = "2026/09/21 07:56:16 ERROR : Bisync critical error: " +
                "${Bisync.MISSING_LISTINGS}\n"
            val rclone = Guion(exito = false, log = log)
            val obs = Testigo()
            val r = sincronizador(v, rclone).unaPareja(notas, obs = obs)
            assertTrue(!r.ok)
            assertEquals("bisync aborted", r.error, "el error del job no explica nada")
            assertEquals(Pasada.KNOWN_ERRORS_PRDRIVE[0].second, r.explicacion)
            // El log queda en logs/, que es lo que `dispose_log` hace con los
            // fallos: los buenos no se guardan.
            val fallos = Resultados.fallos(v.state, v.logs, listOf("notas"))
            assertEquals(1, fallos.size)
            assertNotNull(fallos[0].log)
            assertTrue(Bisync.MISSING_LISTINGS in fallos[0].log!!.readText())
        }
    }

    @Test
    fun `una pasada buena no deja log en el dispositivo`() {
        // Es lo que `dispose_log()` cuida en prdrive: menos ciclos de escritura
        // y una carpeta logs/ que solo tiene lo que hay que mirar.
        conVolumen { v ->
            ponerBaseline(v, notas)
            v.carpetaLocal(notas).mkdirs()
            val rclone = Guion(log = "todo bien\n", sesion = Bisync.expectedPrefix(notas))
            sincronizador(v, rclone).unaPareja(notas)
            assertEquals(0, (v.logs.list() ?: emptyArray()).size, "no debería quedar ningún log")
            // Salvo si se piden.
            sincronizador(v, rclone).unaPareja(notas, OpcionesDePasada(guardarLogs = true))
            assertEquals(1, (v.logs.list() ?: emptyArray()).size)
        }
    }

    @Test
    fun `un dry-run no apunta nada`() {
        // Un simulacro bueno no puede tapar un fallo real.
        conVolumen { v ->
            ponerBaseline(v, notas)
            v.carpetaLocal(notas).mkdirs()
            val rclone = Guion(exito = false, log = "fallo simulado\n")
            sincronizador(v, rclone).unaPareja(notas, OpcionesDePasada(dryRun = true))
            assertEquals(emptyList(), Resultados.fallos(v.state, v.logs, listOf("notas")))
            assertTrue(leerObjetoJson(rclone.entradas.getValue("sync/bisync"))["dryRun"] == true)
        }
    }

    @Test
    fun `el progreso se pregunta por el grupo de la pareja mientras corre`() {
        conVolumen { v ->
            ponerBaseline(v, notas)
            v.carpetaLocal(notas).mkdirs()
            val rclone = Guion(vueltasHastaAcabar = 3, sesion = Bisync.expectedPrefix(notas))
            val obs = Testigo()
            sincronizador(v, rclone).unaPareja(notas, obs = obs)
            assertEquals(2, obs.progresos.size, "una lectura por vuelta sin acabar")
            assertEquals(512L, obs.progresos.first().hecho)
            assertEquals(
                Pasada.grupoDe(notas),
                leerObjetoJson(rclone.entradas.getValue("core/stats"))["group"],
            )
        }
    }

    @Test
    fun `si rclone ha usado otro baseline se avisa, porque la próxima pasada lo perdería`() {
        // La red que el escritorio no tiene: `sync/bisync` DEVUELVE el nombre
        // que ha usado, así que se puede comprobar contra el que calcula el
        // motor en vez de cruzar los dedos.
        conVolumen { v ->
            ponerBaseline(v, notas)
            v.carpetaLocal(notas).mkdirs()
            val rclone = Guion(sesion = "otro_nombre..raro")
            val obs = Testigo()
            sincronizador(v, rclone).unaPareja(notas, obs = obs)
            assertTrue(obs.avisas("otro_nombre..raro"), obs.avisos.toString())
            assertTrue(obs.avisas(Bisync.expectedPrefix(notas)), obs.avisos.toString())
        }
    }

    @Test
    fun `los conflictos se buscan tras cada pasada, buena o mala`() {
        // rclone renombra al perdedor en cuanto lo detecta, así que un fallo
        // más adelante no quita el conflicto.
        conVolumen { v ->
            ponerBaseline(v, notas)
            val carpeta = v.carpetaLocal(notas)
            carpeta.mkdirs()
            File(carpeta, "plan.md").writeText("x")
            File(carpeta, "plan.md.conflicto-remoto1").writeText("x")
            val obs = Testigo()
            sincronizador(v, Guion(exito = false, log = "algo\n")).unaPareja(notas, obs = obs)
            assertTrue(obs.avisas("en conflicto"), obs.avisos.toString())
            assertTrue(obs.avisas("plan.md"), obs.avisos.toString())
        }
    }

    @Test
    fun `si la llamada no sale, la pareja falla con el mensaje de rclone`() {
        conVolumen { v ->
            ponerBaseline(v, notas)
            v.carpetaLocal(notas).mkdirs()
            val rclone = Guion(fallaLaLlamada = "didn't find section in config file")
            val obs = Testigo()
            val r = sincronizador(v, rclone).unaPareja(notas, obs = obs)
            assertTrue(!r.ok)
            assertTrue("didn't find section" in r.error, r.error)
            // Y se traduce, que para eso están las agujas de la API rc.
            assertTrue("emparejar" in r.explicacion.orEmpty(), r.explicacion.orEmpty())
        }
    }

    @Test
    fun `un flag que no puede viajar tumba esa pareja y no las demás`() {
        conVolumen { v ->
            val raw = Vectores.objeto(Vectores.seccion("resueltas"), "config").toMutableMap()
            @Suppress("UNCHECKED_CAST")
            val parejas = (raw["pair"] as List<Map<String, Any?>>).map { LinkedHashMap(it) }
            parejas[0]["flags"] = mapOf("resilient" to "sí")     // tendría que ser booleano
            raw["pair"] = parejas
            val roto = parseConfig(raw)
            roto.pairs.forEach { ponerBaseline(v, it); v.carpetaLocal(it).mkdirs() }

            val obs = Testigo()
            val resultados = sincronizador(v, Guion(sesion = null))
                .sincronizar(roto.pairs, OpcionesDePasada(resyncAprobado = true), obs)
            assertEquals(roto.pairs.size, resultados.size, "todas se han intentado")
            assertTrue(!resultados[0].ok, "la de los flags rotos falla")
            assertTrue("resilient" in resultados[0].error, resultados[0].error)
            assertTrue(resultados.drop(1).all { it.ok }, "las demás sincronizan igual")
        }
    }

    @Test
    fun `las parejas van de una en una y el nivel de log se pone antes`() {
        // De una en una porque `bilib.CaptureOutput` redirige la salida del
        // proceso entero: dos a la vez se robarían el log. Y el nivel es
        // global, así que si no se pone antes el log sale vacío.
        conVolumen { v ->
            config.pairs.forEach { ponerBaseline(v, it); v.carpetaLocal(it).mkdirs() }
            val rclone = Guion(log = "x\n")
            val obs = Testigo()
            val resultados = sincronizador(v, rclone).sincronizar(config.pairs, obs = obs)
            assertEquals(config.names, resultados.map { it.pareja })
            assertEquals(config.names, obs.acabadas.map { it.pareja })
            assertEquals("INFO", rclone.nivel, "el verbose de prdrive es INFO")
            // Y el log se reinicia una vez por pareja: lo que se enseña es el
            // de ESA pareja, no el de la sesión.
            assertEquals(config.pairs.size, rclone.reinicios)
        }
    }

    @Test
    fun `las razones de resync se pueden preguntar antes de empezar`() {
        // Para poder preguntar UNA vez y no pareja por pareja a mitad de faena.
        conVolumen { v ->
            ponerBaseline(v, notas)
            val razones = sincronizador(v, Guion()).razonesDeResync(config.pairs)
            assertTrue("notas" !in razones, "notas tiene baseline bueno: $razones")
            assertTrue("fotos" in razones, "fotos no tiene baseline: $razones")
            assertTrue("espejo" !in razones, "un modo espejo no usa baseline")
            assertTrue(razones.getValue("fotos").isNotEmpty())
        }
    }

    @Test
    fun `un modo espejo no mira baselines ni conflictos`() {
        conVolumen { v ->
            v.carpetaLocal(espejo).mkdirs()
            val rclone = Guion()
            val obs = Testigo()
            val r = sincronizador(v, rclone).unaPareja(espejo, obs = obs)
            assertTrue(r.ok, r.error)
            assertEquals("sync/sync", rclone.metodos.first())
            assertNull(r.sesion)
            assertTrue(obs.avisos.none { "conflicto" in it }, obs.avisos.toString())
        }
    }

    private fun Testigo.avisas(trozo: String): Boolean = avisos.any { trozo in it }
}
