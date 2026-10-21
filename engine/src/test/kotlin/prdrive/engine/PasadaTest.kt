package prdrive.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PasadaTest.kt — La pasada de una pareja: qué se dice cuando falla, qué logs
 * quedan, cómo se traduce la orden a una llamada rc y qué decide la pasada.
 *
 * La tabla de errores y la poda de logs se comparan con prdrive. La traducción a
 * la llamada rc no tiene equivalente en el escritorio (allí hay línea de
 * órdenes): se comprueba aquí lo que hace con cada clase de flag, y en el
 * dispositivo, contra rclone de verdad, que la llamada funciona.
 */
class PasadaTest {

    private val paso3 get() = Vectores.seccion("paso3")
    private val temporales = ArrayList<File>()

    private fun temporal(): File =
        Files.createTempDirectory("prdrive-pasada").toFile().also { temporales.add(it) }

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
    }

    // --- lo que se dice cuando falla -----------------------------------------

    @Test
    fun `los errores conocidos son los de sync_py, en su orden`() {
        val errores = Vectores.objeto(paso3, "errores")
        assertEquals(Vectores.cadenas(errores, "explicaciones"), Pasada.KNOWN_ERRORS.map { it.explicacion })
        assertEquals(
            Vectores.lista(errores, "agujas_de_texto"),
            Pasada.KNOWN_ERRORS.filter { it.aguja != null }.map { listOf(it.aguja, it.explicacion) },
        )
        assertEquals(errores["log_tail_lines"], Pasada.LOG_TAIL_LINES.toLong())
        assertEquals(errores["logs_por_pareja"], Pasada.LOGS_POR_PAREJA.toLong())
        assertEquals(errores["conflicts_shown"], Pasada.CONFLICTS_SHOWN.toLong())
    }

    @Test
    fun `cada fallo se explica como en el escritorio`() {
        for (caso in Vectores.objetos(Vectores.objeto(paso3, "errores"), "casos")) {
            assertEquals(caso["explicacion"], Pasada.explicar(Vectores.texto(caso, "texto")), "«${caso["caso"]}»")
        }
    }

    @Test
    fun `de los logs de una pareja quedan los mismos que en el escritorio`() {
        val logs = Vectores.objeto(paso3, "logs")
        val dir = temporal()
        val conservar = File(dir, Vectores.texto(logs, "conservar"))
        (Vectores.cadenas(logs, "nombres") + conservar.name).forEach { File(dir, it).writeText("x") }
        Pasada.podarLogs(dir, "notas", conservar)
        assertEquals(Vectores.cadenas(logs, "quedan"), dir.list()!!.sorted())
    }

    // --- de la orden a la llamada rc -----------------------------------------

    /** Lo que diría `options/info` de las opciones que salen en estas órdenes. */
    private val opciones = Pasada.Opciones(
        main = mapOf(
            "transfers" to false, "checkers" to false, "modify_window" to false,
            "stats_one_line" to true, "fast_list" to true, "checksum" to true, "max_delete" to false,
            "suffix" to false, "suffix_keep_extension" to true, "dry_run" to true,
        ),
        filtro = mapOf("include" to false, "exclude" to false, "min_size" to false),
    )

    private fun llamada(pareja: String, pasada: String): Pasada.Llamada {
        val orden = Vectores.objeto(paso3, "orden")
        val caso = Vectores.objetos(orden, "pasadas").first { it["pareja"] == pareja && it["pasada"] == pasada }
        val tramos = Vectores.objetos(caso, "tramos").map {
            Orden.Tramo(Vectores.cadenas(it, "args"), Vectores.texto(it, "origen"), it["nombre"]?.toString())
        }
        return Pasada.llamadaDe(tramos, opciones)
    }

    @Test
    fun `una pareja bisync es una llamada a sync_bisync con sus parametros`() {
        val l = llamada("notas", "normal")
        assertEquals("sync/bisync", l.metodo)
        val p = l.parametros
        assertEquals("disp:sync-data/notas", p["path1"])
        assertEquals("nas:/copia/notas", p["path2"])
        assertEquals("/volumen/.prdrive/filters/notas.txt", p["filtersFile"])
        assertEquals("/volumen/.prdrive/state/notas", p["workdir"])
        // El de bisync es un porcentaje y va como su parámetro, no como el global.
        assertEquals(25L, p["maxDelete"])
        assertNull(p["max_delete"])
        assertEquals(true, p["resilient"])
        assertEquals("2m", p["maxLock"])
        assertEquals("delete", p["conflictLoser"])
        // Las opciones globales, planas y con el texto del flag.
        assertEquals("4", p["transfers"])
        assertEquals("2s", p["modify_window"])
        assertEquals("~20261001-110101", p["suffix"])
        assertEquals("true", p["suffix_keep_extension"])
        // Lo que no tiene equivalente con la biblioteca no viaja.
        // `--stats` es de la línea de órdenes de rclone, no una opción global.
        for (sobra in listOf("config", "log_file", "verbose", "stats")) assertFalse(sobra in p, sobra)
        assertNull(p["resync"])
    }

    @Test
    fun `gana el ultimo valor de un flag, como en la linea de ordenes`() {
        // `max-delete` sale de la base (25), de la pareja (9) y de extra_flags (7=).
        val p = llamada("extras", "normal").parametros
        assertEquals(7L, p["maxDelete"])
        assertEquals("true", p["fast_list"])
        assertEquals("pathname", p["conflictLoser"])
        // `--resync-mode` no llega a una pasada normal: la convertiría en un resync.
        assertNull(p["resyncMode"])
        val r = llamada("extras", "resync").parametros
        assertEquals(true, r["resync"])
        assertEquals("newer", r["resyncMode"])
    }

    @Test
    fun `un espejo es sync_sync con su tope como cuenta y los filtros en _filter`() {
        val l = llamada("bajada", "normal")
        assertEquals("sync/sync", l.metodo)
        assertEquals("nas:/copia/bajada", l.parametros["srcFs"])
        assertEquals("disp:sync-data/bajada", l.parametros["dstFs"])
        assertEquals("3", l.parametros["max_delete"])
        assertEquals("true", l.parametros["checksum"])
        assertEquals(true, l.parametros["createEmptySrcDirs"])
        assertEquals(mapOf("ExcludeRule" to listOf("*.tmp")), l.parametros["_filter"])
        // Y forzada, sin tope (-1), como en el escritorio.
        assertEquals("-1", llamada("bajada", "forzada").parametros["max_delete"])
        assertEquals("sync/copy", llamada("subida", "normal").metodo)
    }

    @Test
    fun `un flag que rclone no conoce se dice como lo diria rclone`() {
        val tramos = listOf(
            Orden.Tramo(listOf("rclone", "bisync", "disp:a", "nas:/a"), ORIGEN_PRDRIVE),
            Orden.Tramo(listOf("--inventado"), ORIGEN_EXTRA),
        )
        val e = assertFailsWith<Pasada.OrdenInvalida> { Pasada.llamadaDe(tramos, opciones) }
        // Y la explicación es la del escritorio para «unknown flag».
        assertEquals(
            Pasada.KNOWN_ERRORS.first { it.aguja == "unknown flag" }.explicacion,
            Pasada.explicar("Error: ${e.message}"),
        )
    }

    // --- la pasada -----------------------------------------------------------

    /** Un rclone de mentira: apunta lo que le piden y contesta lo que se le diga. */
    private class RcloneFalso(var final: Pasada.Final = Pasada.Final(true, "", "INFO : Bisync successful\n")) :
        Pasada.Rclone {
        val llamadas = ArrayList<Pasada.Llamada>()
        override fun ejecutar(llamada: Pasada.Llamada, alProgreso: (Map<String, Any?>) -> Unit): Pasada.Final {
            llamadas.add(llamada)
            return final
        }
    }

    private val opcionesDeLaPasada = Pasada.Opciones(
        main = mapOf("transfers" to false, "modify_window" to false, "stats_one_line" to true,
            "suffix" to false, "suffix_keep_extension" to true, "max_delete" to false),
        filtro = emptyMap(),
    )

    private fun volumen(vararg parejas: Map<String, Any?>): kotlin.Pair<Volumen, Config> {
        val v = Volumen(File(temporal(), "volumen"))
        val raw = mapOf(
            "defaults" to mapOf("device_remote" to "disp", "remote" to "nas"),
            "pair" to parejas.toList(),
        )
        v.escribirConfig(raw, "")
        val config = v.leerConfig()
        v.rcloneConf.writeText("[nas]\ntype = sftp\n\n" + config.seccionCombine(v.raiz.absolutePath))
        return v to config
    }

    private val notas = mapOf("name" to "notas", "local" to "sync-data/notas", "remote_path" to "/copia/notas")

    @Test
    fun `una pareja nueva sin resync aprobado se salta y no se apunta`() {
        val (v, config) = volumen(notas)
        val rclone = RcloneFalso()
        val r = Pasada.pasar(v, config.pairs[0], rclone, opcionesDeLaPasada)
        assertEquals(Pasada.SALTADA, r.codigo)
        assertTrue(rclone.llamadas.isEmpty(), "no se llama a rclone")
        assertFalse(Resultados.rutaEstado(v.state).exists(), "una saltada no se apunta")
    }

    @Test
    fun `con el resync aprobado se pide un resync, se crea la carpeta y se apunta`() {
        val (v, config) = volumen(notas)
        val rclone = RcloneFalso()
        val r = Pasada.pasar(v, config.pairs[0], rclone, opcionesDeLaPasada, Pasada.Contexto(resyncAprobado = true))
        assertEquals(0, r.codigo)
        assertTrue(r.resync)
        assertEquals(true, rclone.llamadas.single().parametros["resync"])
        assertTrue(File(v.raiz, "sync-data/notas").isDirectory)
        assertNull(r.log, "una pasada buena no guarda log")
        assertEquals(emptyList(), Resultados.fallosDe(v.state, v.logs, listOf("notas")))
        assertNotNull(Resultados.ultimasBuenas(v.state, listOf("notas"))["notas"])
    }

    @Test
    fun `una pasada que falla guarda su log, se explica y se apunta`() {
        val (v, config) = volumen(notas)
        val rclone = RcloneFalso(
            Pasada.Final(false, "bisync aborted", "ERROR : prior lock file found: /x.lck\n"),
        )
        val r = Pasada.pasar(v, config.pairs[0], rclone, opcionesDeLaPasada, Pasada.Contexto(resyncAprobado = true))
        assertEquals(Pasada.FALLO, r.codigo)
        assertNotNull(r.log)
        assertTrue("prior lock file found" in r.log.readText())
        assertTrue("bisync aborted" in r.log.readText(), "el error de la llamada va al log")
        assertEquals(Pasada.KNOWN_ERRORS.first { it.aguja == "prior lock file found" }.explicacion, r.explicacion)
        assertEquals(r.log, Resultados.fallosDe(v.state, v.logs, listOf("notas")).single().log)
    }

    @Test
    fun `un espejo de subida sin carpeta local se aborta en vez de vaciar el remoto`() {
        val (v, config) = volumen(notas + mapOf("mode" to "up-mirror"))
        val rclone = RcloneFalso()
        val r = Pasada.pasar(v, config.pairs[0], rclone, opcionesDeLaPasada)
        assertEquals(Pasada.ABORTO, r.codigo)
        assertTrue(rclone.llamadas.isEmpty())
        assertFalse(File(v.raiz, "sync-data/notas").exists(), "no se crea vacía")
    }

    @Test
    fun `con baseline y sin carpeta local se aborta`() {
        val (v, config) = volumen(notas)
        val p = config.pairs[0]
        val tipos = Bisync.tiposDeRemote(v.leerRcloneConf())
        val prefijo = Bisync.expectedPrefix(p, tipos)
        val workdir = v.workdir(p).apply { mkdirs() }
        File(workdir, prefijo + Bisync.PATH1_SUFFIX).writeText("# x\n- 1 - - 2026 \"a\"\n")
        File(workdir, prefijo + Bisync.PATH2_SUFFIX).writeText("# x\n- 1 - - 2026 \"a\"\n")
        Bisync.filtersFileFor(p, v.filtros)!!.let { f ->
            val md5 = java.security.MessageDigest.getInstance("MD5").digest(f.readBytes())
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            File(f.path + ".md5").writeText(md5)
        }
        val r = Pasada.pasar(v, p, RcloneFalso(), opcionesDeLaPasada)
        assertEquals(Pasada.ABORTO, r.codigo)
    }

    @Test
    fun `una referencia vacia se rehace sola, sin pedir aprobacion`() {
        val (v, config) = volumen(notas)
        val p = config.pairs[0]
        val prefijo = Bisync.expectedPrefix(p, Bisync.tiposDeRemote(v.leerRcloneConf()))
        val workdir = v.workdir(p).apply { mkdirs() }
        File(workdir, prefijo + Bisync.PATH1_SUFFIX).writeText("# vacío\n")
        File(workdir, prefijo + Bisync.PATH2_SUFFIX).writeText("# vacío\n")
        File(v.raiz, "sync-data/notas").mkdirs()
        val rclone = RcloneFalso()
        val r = Pasada.pasar(v, p, rclone, opcionesDeLaPasada)
        assertEquals(0, r.codigo)
        assertEquals(true, rclone.llamadas.single().parametros["resync"])
    }

    @Test
    fun `un simulacro no apunta nada`() {
        val (v, config) = volumen(notas)
        Pasada.pasar(v, config.pairs[0], RcloneFalso(), opcionesDeLaPasada,
            Pasada.Contexto(simulacro = true, resyncAprobado = true))
        assertFalse(Resultados.rutaEstado(v.state).exists())
    }
}
