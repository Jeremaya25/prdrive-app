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

    private val orden get() = Vectores.objeto(paso3, "orden")

    private fun tramos(pareja: String, pasada: String): List<Orden.Tramo> {
        val caso = Vectores.objetos(orden, "pasadas").first { it["pareja"] == pareja && it["pasada"] == pasada }
        return Vectores.objetos(caso, "tramos").map {
            Orden.Tramo(Vectores.cadenas(it, "args"), Vectores.texto(it, "origen"), it["nombre"]?.toString())
        }
    }

    private fun llamada(pareja: String, pasada: String) = Pasada.llamadaDe(tramos(pareja, pasada), Oraculo.opciones)

    /** Los argumentos de la orden, sin `rclone <verbo> <origen> <destino>`. */
    private fun args(pareja: String, pasada: String) = tramos(pareja, pasada).drop(1).flatMap { it.args }

    private fun booleana(flag: String, bisync: Boolean): Boolean {
        val opcion = flag.replace('-', '_')
        if (bisync && flag in Pasada.PARAMETROS_BISYNC) {
            return flag in listOf("resync", "dry-run", "check-access", "force", "create-empty-src-dirs",
                "remove-empty-dirs", "no-cleanup", "ignore-listing-checksum", "resilient", "recover",
                "no-slow-hash", "slow-hash-sync-only", "download-hash")
        }
        val o = Oraculo.opciones
        return (o.main[opcion] ?: o.filtro[opcion] ?: o.log[opcion])?.booleana ?: (flag in listOf("verbose", "create-empty-src-dirs"))
    }

    /**
     * Cada valor que se le da a un flag largo, en orden: `--x=v`, `--x v`, o
     * `"true"` si es booleano (lo dice el `options/info` del oráculo).
     */
    private fun valores(args: List<String>, flag: String, bisync: Boolean): List<String> {
        val salida = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i++]
            if (!a.startsWith("--")) continue
            val nombre = a.substring(2).substringBefore('=')
            val valor = when {
                '=' in a -> a.substringAfter('=')
                booleana(nombre, bisync) -> "true"
                else -> args[i++]
            }
            if (nombre == flag) salida.add(valor)
        }
        return salida
    }

    @Test
    fun `cada flag de la orden llega con su nombre de rc y su ultimo valor`() {
        val sinEquivalente = setOf("config", "log-file", "verbose", "stats")
        for (caso in Vectores.objetos(orden, "pasadas")) {
            val (pareja, pasada) = Vectores.texto(caso, "pareja") to Vectores.texto(caso, "pasada")
            val a = args(pareja, pasada)
            if (a.any { it.startsWith("-") && !it.startsWith("--") }) continue // los de una letra, aparte
            val cabeza = tramos(pareja, pasada).first().args
            val bisync = cabeza[1] == "bisync"
            val p = llamada(pareja, pasada).parametros
            val donde = "«$pareja» ($pasada)"
            assertEquals(if (bisync) cabeza[2] else cabeza[2], p[if (bisync) "path1" else "srcFs"], donde)
            assertEquals(cabeza[3], p[if (bisync) "path2" else "dstFs"], donde)
            val flags = a.filter { it.startsWith("--") }.map { it.substring(2).substringBefore('=') }.toSet()
            for (flag in flags) {
                val ultimo = valores(a, flag, bisync).last()
                val opcion = flag.replace('-', '_')
                when {
                    flag == "max-delete" && bisync -> Unit // tiene su prueba
                    bisync && flag in Pasada.PARAMETROS_BISYNC -> {
                        val esperado: Any = if (booleana(flag, true)) ultimo.toBoolean() else ultimo
                        assertEquals(esperado, p[Pasada.PARAMETROS_BISYNC.getValue(flag)], "$donde --$flag")
                    }
                    flag == "create-empty-src-dirs" -> assertEquals(ultimo.toBoolean(), p["createEmptySrcDirs"], donde)
                    flag in sinEquivalente -> assertFalse(opcion in p, "$donde: --$flag no tiene que viajar")
                    flag == "include" || flag == "exclude" -> {
                        val regla = if (flag == "include") "IncludeRule" else "ExcludeRule"
                        @Suppress("UNCHECKED_CAST")
                        assertEquals(valores(a, flag, bisync), (p["_filter"] as Map<String, Any?>)[regla], "$donde --$flag")
                    }
                    else -> {
                        val tipo = Oraculo.opciones.main[opcion] ?: Oraculo.opciones.filtro.getValue(opcion)
                        val esperado: Any = if (tipo.lista) valores(a, flag, bisync) else ultimo
                        assertEquals(esperado, p[opcion], "$donde --$flag")
                    }
                }
            }
            assertEquals(if (bisync) "sync/bisync" else if (cabeza[1] == "copy") "sync/copy" else "sync/sync", llamada(pareja, pasada).metodo)
        }
    }

    @Test
    fun `el tope de bisync va siempre, con el sentido de su linea de ordenes`() {
        // cmd/bisync/cmd.go (applyContext): sin --max-delete o negativo, el de
        // fábrica; si no, entre 0 y 100. El RPC no lo hace: sin `maxDelete` usa 0.
        val tope = Vectores.objeto(paso3, "errores")["tope_bisync_de_fabrica"] as Long
        assertEquals(tope, Pasada.TOPE_BISYNC_DE_FABRICA.toLong())
        assertEquals(Oraculo.raiz["bisync_default_max_delete"], tope, "el de prdrive es el DefaultMaxDelete de rclone")
        for (pareja in listOf("notas", "extras", "sin-tope", "tope-negativo", "tope-alto")) {
            val dado = valores(args(pareja, "normal"), "max-delete", true).lastOrNull()?.toLong()
            val esperado = if (dado == null || dado < 0) tope else minOf(dado, 100L)
            assertEquals(esperado, llamada(pareja, "normal").parametros["maxDelete"], pareja)
        }
        // En un espejo es la cuenta de ficheros de siempre, global.
        assertEquals(valores(args("bajada", "normal"), "max-delete", false).last(), llamada("bajada", "normal").parametros["max_delete"])
        assertNull(llamada("bajada", "normal").parametros["maxDelete"])
    }

    @Test
    fun `las letras y los flags que se acumulan se leen como en la linea de ordenes`() {
        val a = args("cortos", "normal")
        val p = llamada("cortos", "normal").parametros
        // `-uc` son dos booleanos; cuáles, lo dice el ShortOpt de options/info.
        val letras = a.first { it.startsWith("-") && !it.startsWith("--") }.substring(1)
        for (letra in letras.map { it.toString() }) {
            val opcion = Oraculo.opciones.main.entries.first { it.value.corto == letra }.key
            assertEquals("true", p[opcion], "-$letra")
        }
        // `--exclude-from` se acumula: van los dos, como lista y con su coma.
        assertEquals(valores(a, "exclude-from", false), p["exclude_from"])
    }

    private fun conExtra(vararg extra: String) = listOf(
        Orden.Tramo(listOf("rclone", "bisync", "disp:a", "nas:/a"), ORIGEN_PRDRIVE),
        Orden.Tramo(extra.toList(), ORIGEN_EXTRA),
    )

    @Test
    fun `un flag que rclone no conoce se dice como lo diria rclone`() {
        val desconocido = Pasada.KNOWN_ERRORS.first { it.aguja == "unknown flag" }.explicacion
        // Ni uno inventado ni el nombre de la opción con guion bajo: pflag no normaliza.
        for (flag in listOf("--inventado", "--max_delete=3", "--modify_window=2s")) {
            val e = assertFailsWith<Pasada.OrdenInvalida> { Pasada.llamadaDe(conExtra(flag), Oraculo.opciones) }
            assertEquals(desconocido, Pasada.explicar("Error: ${e.message}"), flag)
            assertNull(e.explicacion, flag)
        }
        assertFailsWith<Pasada.OrdenInvalida> { Pasada.llamadaDe(conExtra("-Z"), Oraculo.opciones) }
    }

    @Test
    fun `lo que el escritorio acepta y el telefono no puede pasar se explica aparte`() {
        val backend = Vectores.objetos(Oraculo.raiz, "backends").first { it["Prefix"] == "sftp" }
        val deBackend = "--sftp-" + Vectores.cadenas(backend, "Options").first().replace('_', '-') + "=x"
        for (flag in listOf(deBackend, "--delete-after", "--header=X-Y: z")) {
            val e = assertFailsWith<Pasada.OrdenInvalida> { Pasada.llamadaDe(conExtra(flag), Oraculo.opciones) }
            assertNotNull(e.explicacion, flag)
            assertTrue(flag.substringBefore('=') in e.explicacion, flag)
        }
    }

    @Test
    fun `una letra pasa por la misma lista que su flag largo`() {
        // `problema_extra` solo mira los de dos guiones: `-1` sería un --resync
        // en cada pasada. Una letra se lee como su flag largo, y se le pregunta
        // a `problemaFlag` como a él.
        for ((letra, largo) in listOf("-1" to "resync", "-n" to "dry-run")) {
            val reservado = problemaFlag(largo)
            if (reservado == null) continue // solo cuenta si su largo no se admite
            val e = assertFailsWith<Pasada.OrdenInvalida>(letra) { Pasada.llamadaDe(conExtra(letra), Oraculo.opciones) }
            assertNotNull(e.explicacion, letra)
            assertTrue(reservado in e.explicacion, letra)
        }
        assertNotNull(problemaFlag("resync"), "el --resync lo pone la pasada, no el config")
    }

    @Test
    fun `la cola del log es la que imprime sync_py`() {
        for (caso in Vectores.objetos(Vectores.objeto(paso3, "errores"), "cola")) {
            assertEquals(Vectores.cadenas(caso, "cola"), Pasada.cola(Vectores.texto(caso, "texto")), "«${caso["caso"]}»")
        }
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

    private val opcionesDeLaPasada get() = Oraculo.opciones

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
