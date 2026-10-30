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
        assertFalse(Historial.ruta(v.state).exists(), "ni en el diario de pasadas")
    }

    // --- el diario de pasadas --------------------------------------------------

    @Test
    fun `una pasada apunta una linea en el diario con su inicio, su duracion y los bytes que movio`() {
        val (v, config) = volumen(notas)
        val rclone = RcloneFalso()
        rclone.progreso.addAll(listOf(mapOf("bytes" to 100L, "totalBytes" to 900L), mapOf("bytes" to 2048L, "totalBytes" to 2048L)))
        var n = 0
        val sellos = listOf("2026-10-01 10:00:00", "2026-10-01 10:00:07")
        val r = Pasada.pasar(
            v, config.pairs[0], rclone, opcionesDeLaPasada, Pasada.Contexto(resyncAprobado = true),
            reloj = { sellos[n++ % sellos.size] },
        )
        assertEquals(0, r.codigo)
        val p = Historial.leer(v.state).single()
        assertEquals("notas", p.pareja)
        assertEquals(sellos[0], p.inicio, "el inicio es el de cuando empezó la pareja")
        assertEquals(0, p.codigo.toInt())
        val duracion = p.segundos
        assertTrue(duracion != null && duracion >= 0.0 && duracion < 60.0, "duró $duracion")
        assertEquals(2048, p.transferido!!.toInt(), "el último bytes que llegó por el progreso")
        assertEquals(1, Historial.ruta(v.state).readLines().size)
    }

    @Test
    fun `una pasada que no recibe progreso apunta sin bytes`() {
        val (v, config) = volumen(notas)
        Pasada.pasar(v, config.pairs[0], RcloneFalso(), opcionesDeLaPasada, Pasada.Contexto(resyncAprobado = true))
        val p = Historial.leer(v.state).single()
        assertNull(p.transferido)
        assertNotNull(p.segundos)
    }

    @Test
    fun `el progreso de la pasada sigue llegando a quien lo pidio`() {
        val (v, config) = volumen(notas)
        val rclone = RcloneFalso()
        rclone.progreso.add(mapOf("bytes" to 5L))
        val vistos = ArrayList<Map<String, Any?>>()
        Pasada.pasar(
            v, config.pairs[0], rclone, opcionesDeLaPasada, Pasada.Contexto(resyncAprobado = true),
            alProgreso = { vistos.add(it) },
        )
        assertEquals(listOf(mapOf<String, Any?>("bytes" to 5L)), vistos)
    }

    @Test
    fun `una pasada que falla tambien consta en el diario, con su codigo`() {
        val (v, config) = volumen(notas)
        val rclone = RcloneFalso(Pasada.Final(false, "bisync aborted", "ERROR : prior lock file found: /x.lck\n"))
        rclone.progreso.add(mapOf("bytes" to 7L))
        Pasada.pasar(v, config.pairs[0], rclone, opcionesDeLaPasada, Pasada.Contexto(resyncAprobado = true))
        val p = Historial.leer(v.state).single()
        assertEquals(Pasada.FALLO, p.codigo.toInt())
        assertEquals(7, p.transferido!!.toInt())
    }

    @Test
    fun `dos pasadas dejan dos lineas, una por pasada`() {
        val (v, config) = volumen(notas)
        val ctx = Pasada.Contexto(resyncAprobado = true)
        Pasada.pasar(v, config.pairs[0], RcloneFalso(), opcionesDeLaPasada, ctx)
        Pasada.pasar(v, config.pairs[0], RcloneFalso(Pasada.Final(false, "x", "ERROR : x\n")), opcionesDeLaPasada, ctx)
        assertEquals(listOf(0, Pasada.FALLO), Historial.leer(v.state).map { it.codigo.toInt() })
    }

    @Test
    fun `una pareja saltada no consta en el diario`() {
        val (v, config) = volumen(notas)
        val r = Pasada.pasar(v, config.pairs[0], RcloneFalso(), opcionesDeLaPasada)
        assertEquals(Pasada.SALTADA, r.codigo)
        assertFalse(Historial.ruta(v.state).exists(), "una saltada no es un resultado")
    }

    @Test
    fun `una pasada que aborta antes de llamar a rclone consta en el diario sin bytes`() {
        val (v, config) = volumen(notas + mapOf("mode" to "up-mirror"))
        val rclone = RcloneFalso()
        val r = Pasada.pasar(v, config.pairs[0], rclone, opcionesDeLaPasada)
        assertEquals(Pasada.ABORTO, r.codigo)
        assertTrue(rclone.llamadas.isEmpty())
        val p = Historial.leer(v.state).single()
        assertEquals(Pasada.ABORTO, p.codigo.toInt())
        assertNull(p.transferido)
        assertNotNull(p.segundos, "con reloj: la pareja ya había empezado")
    }

    @Test
    fun `una pareja que se corta por un error de E_S consta en el diario sin duracion`() {
        val (v, config) = volumen(notas)
        val cortada = object : Pasada.Rclone {
            override fun ejecutar(llamada: Pasada.Llamada, alProgreso: (Map<String, Any?>) -> Unit): Pasada.Final =
                throw java.io.IOException("disco")
            override fun mkdir(destino: String): Pasada.Final = Pasada.Final(true, "", "")
        }
        val r = Pasada.pasarOFallar(
            v, config.pairs[0], cortada, opcionesDeLaPasada, Pasada.Contexto(resyncAprobado = true),
            reloj = { "2026-10-01 10:00:00" },
        )
        assertEquals(Pasada.FALLO, r.codigo)
        val p = Historial.leer(v.state).single()
        assertEquals(Pasada.FALLO, p.codigo.toInt())
        assertEquals("2026-10-01 10:00:00", p.inicio)
        assertNull(p.segundos, "sin reloj no hay duración, y un cero sería inventado")
        assertNull(p.transferido)
    }

    @Test
    fun `un simulacro cortado por un error de E_S tampoco consta en el diario`() {
        val (v, config) = volumen(notas)
        val cortada = object : Pasada.Rclone {
            override fun ejecutar(llamada: Pasada.Llamada, alProgreso: (Map<String, Any?>) -> Unit): Pasada.Final =
                throw java.io.IOException("disco")
            override fun mkdir(destino: String): Pasada.Final = Pasada.Final(true, "", "")
        }
        Pasada.pasarOFallar(
            v, config.pairs[0], cortada, opcionesDeLaPasada, Pasada.Contexto(simulacro = true, resyncAprobado = true),
        )
        assertFalse(Historial.ruta(v.state).exists())
        assertFalse(Resultados.rutaEstado(v.state).exists())
    }

    // --- una pareja en una carpeta compartida ----------------------------------

    /**
     * Un volumen con la pareja `vault` (`local = "vault"`) movida a una carpeta
     * compartida: la entrada de `compartidas.json` ([ruta], en otro temporal y
     * con la carpeta creada) y el `rclone.conf` con su `combine` al día.
     */
    private fun volumenCompartido(
        ruta: String = File(temporal(), "Documents/vault").apply { mkdirs() }.absolutePath,
    ): kotlin.Pair<Volumen, Pareja> {
        val v = Volumen(File(temporal(), "volumen"))
        val raw = mapOf(
            "defaults" to mapOf("device_remote" to "disp", "remote" to "nas"),
            "pair" to listOf(mapOf("name" to "vault", "local" to "vault", "remote_path" to "/copia/vault")),
        )
        v.escribirConfig(raw, "")
        Compartidas.escribir(v.compartidas, mapOf("vault" to Compartidas.Entrada(ruta, Compartidas.Estado.LISTA)))
        val config = v.leerConfig()
        v.rcloneConf.writeText("[nas]\ntype = sftp\n\n" + config.seccionCombine(v.raiz.absolutePath))
        return v to config.pairs.single()
    }

    @Test
    fun `una pareja con una entrada que no vale aborta sin llamar a rclone`() {
        val (v, p) = volumenCompartido(ruta = "relativa/vault")
        assertNotNull(p.problemaCompartida)
        val rclone = RcloneFalso()
        val r = Pasada.pasar(v, p, rclone, opcionesDeLaPasada, Pasada.Contexto(accesoCompartido = { null }))
        assertEquals(Pasada.ABORTO, r.codigo)
        assertEquals(p.problemaCompartida, r.explicacion)
        assertTrue(rclone.llamadas.isEmpty(), "no se llama a rclone")
        assertTrue(rclone.mkdirs.isEmpty())
    }

    @Test
    fun `una pareja compartida sin comprobar el acceso aborta`() {
        val (v, p) = volumenCompartido()
        assertNotNull(p.carpetaExterna)
        val rclone = RcloneFalso()
        val r = Pasada.pasar(v, p, rclone, opcionesDeLaPasada, Pasada.Contexto())
        assertEquals(Pasada.ABORTO, r.codigo)
        assertEquals(Pasada.SIN_COMPROBAR_ACCESO, r.explicacion)
        assertTrue(rclone.llamadas.isEmpty(), "no se llama a rclone")
        assertEquals(
            listOf("vault"),
            Resultados.fallosDe(v.state, v.logs, listOf("vault")).map { it.pareja },
            "el aborto se apunta",
        )
    }

    @Test
    fun `una pareja compartida aborta con lo que diga el acceso`() {
        val (v, p) = volumenCompartido()
        val rclone = RcloneFalso()
        val r = Pasada.pasar(v, p, rclone, opcionesDeLaPasada, Pasada.Contexto(accesoCompartido = { "sin permiso" }))
        assertEquals(Pasada.ABORTO, r.codigo)
        assertEquals("sin permiso", r.explicacion)
        assertTrue(r.lineas.any { "sin permiso" in it }, "se cuenta")
        assertTrue(rclone.llamadas.isEmpty(), "no se llama a rclone")
    }

    @Test
    fun `una pareja compartida con el rclone_conf viejo aborta`() {
        val (v, p) = volumenCompartido()
        // Un movimiento cortado entre escribir la entrada y reescribir `[disp]`:
        // el `combine` sigue apuntando a la carpeta privada del volumen.
        val sinEntradas = v.leerConfig().conCompartidas(emptyMap(), v.raiz)
        v.rcloneConf.writeText("[nas]\ntype = sftp\n\n" + sinEntradas.seccionCombine(v.raiz.absolutePath))
        val rclone = RcloneFalso()
        val r = Pasada.pasar(v, p, rclone, opcionesDeLaPasada, Pasada.Contexto(accesoCompartido = { null }))
        assertEquals(Pasada.ABORTO, r.codigo)
        assertEquals(Pasada.COMBINE_VIEJO, r.explicacion)
        assertTrue(rclone.llamadas.isEmpty(), "no se llama a rclone")
    }

    @Test
    fun `una pareja compartida con acceso y el combine al dia pasa por su carpeta`() {
        val (v, p) = volumenCompartido()
        conBaseline(v, p, listOf("a.txt"))
        val rclone = RcloneFalso()
        val r = Pasada.pasar(v, p, rclone, opcionesDeLaPasada, Pasada.Contexto(accesoCompartido = { null }))
        assertEquals(0, r.codigo, r.lineas.joinToString("\n"))
        assertEquals(1, rclone.llamadas.size)
    }

    @Test
    fun `a una pareja sin carpeta compartida no se le pregunta el acceso`() {
        val (v, config) = volumen(notas)
        val r = Pasada.pasar(
            v, config.pairs[0], RcloneFalso(), opcionesDeLaPasada,
            Pasada.Contexto(resyncAprobado = true, accesoCompartido = { error("no toca") }),
        )
        assertEquals(0, r.codigo)
    }

    // --- la pasada del llavero -------------------------------------------------

    private val repeticiones get() = Vectores.objeto(Vectores.seccion("llavero"), "repeticiones")

    /** Una base de KeePassXC de los vectores de `kdbx.py`: entera o no. */
    private fun baseDeLosVectores(entera: Boolean): ByteArray {
        val caso = Vectores.objetos(Vectores.seccion("llavero"), "kdbx").first { it["entera"] == entera }
        val hexa = Vectores.texto(caso, "hex")
        return ByteArray(hexa.length / 2) { hexa.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    /** No espera de verdad: el llavero mira otra vez una base rota a los dos segundos. */
    private val sinEsperar: (Long) -> Unit = {}

    /**
     * Un volumen con el config de los vectores de las repeticiones (el llavero
     * con `personal.kdbx`), con [cambiar] encima de su `[keychain]`, y una base
     * en `.keychain/`.
     */
    private fun volumenLlavero(
        entera: Boolean = true,
        cambiar: Map<String, Any?> = emptyMap(),
    ): kotlin.Pair<Volumen, Pareja> {
        @Suppress("UNCHECKED_CAST")
        val raw = LinkedHashMap(Vectores.objeto(repeticiones, "config"))
        raw["keychain"] = LinkedHashMap(Vectores.objeto(raw, "keychain")) + cambiar
        val v = Volumen(File(temporal(), "volumen"))
        v.escribirConfig(raw, "")
        val config = v.leerConfig()
        v.rcloneConf.writeText("[nas]\ntype = sftp\n\n" + config.seccionCombine(v.raiz.absolutePath))
        val pareja = config.parejaLlavero!!
        val carpeta = pareja.carpetaEn(v.raiz).apply { mkdirs() }
        File(carpeta, Vectores.texto(Vectores.objeto(raw, "keychain"), "base")).writeBytes(baseDeLosVectores(entera))
        return v to pareja
    }

    /** Lo que hay en `.keychain/`, con su LEEME: lo que el listado de la última pasada buena tiene que nombrar. */
    private fun todoListado(v: Volumen, p: Pareja) {
        Llavero.asegurarLeeme(p.carpetaEn(v.raiz))
        conBaseline(v, p, p.carpetaEn(v.raiz).list()!!.sorted())
    }

    @Test
    fun `los codigos del llavero son los de sync_py`() {
        assertEquals(repeticiones["llavero_roto"], Pasada.LLAVERO_ROTO.toLong())
        assertEquals(repeticiones["llavero_sin_cifrar"], Pasada.LLAVERO_SIN_CIFRAR.toLong())
        assertEquals(repeticiones["versions_dir"], VERSIONS_DIR)
    }

    @Test
    fun `el llavero sin baseline se resincroniza solo y antes crea su carpeta en el remoto`() {
        val (v, p) = volumenLlavero()
        val rclone = RcloneFalso()
        // Sin aprobar ningún resync: el llavero no espera a nadie.
        val r = Pasada.pasar(v, p, rclone, opcionesDeLaPasada, Pasada.Contexto(dormir = sinEsperar))
        assertEquals(0, r.codigo)
        assertEquals(listOf(p.dest), rclone.mkdirs)
        assertTrue(p.dest.endsWith("/$LLAVERO_REMOTO"), p.dest)
        val llamada = rclone.llamadas.first()
        assertEquals(true, llamada.parametros["resync"])
        assertEquals(LLAVERO_FLAGS["resync-mode"], llamada.parametros["resyncMode"])
        assertTrue(File(p.carpetaEn(v.raiz), Llavero.LEEME).isFile)
    }

    @Test
    fun `una base rota para la pasada sin llamar a rclone y se apunta`() {
        val (v, p) = volumenLlavero(entera = false)
        val rclone = RcloneFalso()
        val r = Pasada.pasar(v, p, rclone, opcionesDeLaPasada, Pasada.Contexto(dormir = sinEsperar))
        assertEquals(Pasada.LLAVERO_ROTO, r.codigo)
        assertTrue(rclone.llamadas.isEmpty())
        assertTrue(rclone.mkdirs.isEmpty())
        assertEquals(Pasada.LLAVERO_ROTO, Resultados.fallosDe(v.state, v.logs, listOf(p.name)).single().codigo.toInt())
    }

    @Test
    fun `sin contrasena y sin cifrar no se sincroniza`() {
        val sinContrasena = mapOf("fichero_llave" to true, "llave_interna" to true)
        val (v, p) = volumenLlavero(cambiar = sinContrasena)
        assertTrue(p.llaveInterna)
        val rclone = RcloneFalso()
        val r = Pasada.pasar(v, p, rclone, opcionesDeLaPasada, Pasada.Contexto(dormir = sinEsperar))
        assertEquals(Pasada.LLAVERO_SIN_CIFRAR, r.codigo)
        assertTrue(rclone.llamadas.isEmpty())
        assertEquals(Pasada.LLAVERO_SIN_CIFRAR, Resultados.fallosDe(v.state, v.logs, listOf(p.name)).single().codigo.toInt())

        val cifrado = Pasada.pasar(v, p, rclone, opcionesDeLaPasada, Pasada.Contexto(cifrado = true, dormir = sinEsperar))
        assertEquals(0, cifrado.codigo)
        assertEquals(1, rclone.llamadas.size)
    }

    private fun finalDe(objeto: Any?): Pasada.Final {
        @Suppress("UNCHECKED_CAST")
        val o = objeto as Map<String, Any?>
        return Pasada.Final(o["rc"] == 0L, "", Vectores.texto(o, "log"))
    }

    @Test
    fun `cada freno del llavero se repite una vez como en prdrive`() {
        val marca = Vectores.texto(repeticiones, "marca_log")
        for (caso in Vectores.objetos(repeticiones, "casos")) {
            val simulacro = caso["dry_run"] == true
            val donde = "«${caso["caso"]}»${if (simulacro) " (simulacro)" else ""}"
            val (v, p) = volumenLlavero()
            todoListado(v, p)
            // La pasada parada, la simulación con --force si la hay, y la repetición si la hay.
            val cola = listOfNotNull(
                Pasada.Final(false, "", Vectores.texto(caso, "log")),
                caso["simulacion"]?.let(::finalDe),
                caso["repetida"]?.let(::finalDe),
            )
            val rclone = RcloneFalso(Pasada.Final(false, "", "sobra una llamada\n"), *cola.toTypedArray())
            val r = Pasada.pasar(v, p, rclone, opcionesDeLaPasada,
                Pasada.Contexto(simulacro = simulacro, dormir = sinEsperar))

            val ordenes = Vectores.objetos(caso, "ordenes")
            assertEquals(1 + ordenes.size, rclone.llamadas.size, donde)
            val primera = rclone.llamadas.first().parametros
            assertNull(primera["force"], donde)
            assertNull(primera["resync"], donde)
            for ((orden, llamada) in ordenes.zip(rclone.llamadas.drop(1))) {
                val q = llamada.parametros
                assertEquals(orden["dry_run"] == true, q["dryRun"] == true, "$donde dryRun")
                assertEquals(orden["force"] == true, q["force"] == true, "$donde force")
                assertEquals(orden["resync"] == true, q["resync"] == true, "$donde resync")
                assertEquals(Pasada.maxDeleteBisync(orden["max_delete"] as Long?), q["maxDelete"], "$donde maxDelete")
            }
            // `rclone mkdir --dry-run` no crea nada: en un simulacro no se le pide.
            val mkdirs = Vectores.objetos(caso, "mkdir").filter { it["dry_run"] != true }.map { it["destino"] }
            assertEquals(mkdirs, rclone.mkdirs, donde)
            assertEquals(if (caso["rc"] == 0L) 0 else Pasada.FALLO, r.codigo, donde)

            // Lo que dice, con el log que nombra cambiado por la marca de los vectores.
            val guardados = v.logs.list().orEmpty().toList()
            val lineas = r.lineas.map { l -> guardados.fold(l) { a, g -> a.replace(g, marca) } }
            val esperadas = Vectores.cadenas(caso, "lineas")
            assertTrue(java.util.Collections.indexOfSubList(lineas, esperadas) >= 0, "$donde: $lineas")
            // El log de la pasada parada se guarda cuando prdrive lo guarda.
            val parada = Vectores.objetos(caso, "guardados").count { it["rc"] != 0L || it["siempre"] == true }
            val delFinal = if (r.codigo != 0) 1 else 0
            assertEquals(parada + delFinal, guardados.size, "$donde: $guardados")
        }
    }

    @Test
    fun `una base que rclone deja sin listar se apunta con otra pasada`() {
        val (v, p) = volumenLlavero()
        // El listado de la última pasada buena solo tiene el LEEME: la base, no.
        Llavero.asegurarLeeme(p.carpetaEn(v.raiz))
        conBaseline(v, p, listOf(Llavero.LEEME))
        val rclone = RcloneFalso()
        val r = Pasada.pasar(v, p, rclone, opcionesDeLaPasada, Pasada.Contexto(dormir = sinEsperar))
        assertEquals(0, r.codigo)
        assertEquals(2, rclone.llamadas.size)
        assertNull(rclone.llamadas[1].parametros["resync"])
        assertEquals(rclone.llamadas[0], rclone.llamadas[1], "la misma pasada, otra vez")

        // Con todo en el listado, una sola.
        val (v2, p2) = volumenLlavero()
        todoListado(v2, p2)
        val otro = RcloneFalso()
        Pasada.pasar(v2, p2, otro, opcionesDeLaPasada, Pasada.Contexto(dormir = sinEsperar))
        assertEquals(1, otro.llamadas.size)
    }

    @Test
    fun `al llavero se le explica el freno sin decirle que lo fuerce`() {
        val caso = Vectores.objetos(repeticiones, "casos")
            .first { it["dry_run"] != true && it["repite"] == true && it["simulada"] == true }
        val (v, p) = volumenLlavero()
        todoListado(v, p)
        val parada = Pasada.Final(false, "", Vectores.texto(caso, "log"))
        // La repetida vuelve a pararse igual: no hay otra, y se explica.
        val rclone = RcloneFalso(parada, parada, finalDe(caso["simulacion"]), parada)
        val r = Pasada.pasar(v, p, rclone, opcionesDeLaPasada, Pasada.Contexto(dormir = sinEsperar))
        assertEquals(Pasada.FALLO, r.codigo)
        assertEquals(3, rclone.llamadas.size)
        assertEquals(Pasada.EXPLICACION_CAMBIADOS, Pasada.explicar(Vectores.texto(caso, "log")))
        assertEquals(Pasada.EXPLICACION_LLAVERO_CAMBIADOS, r.explicacion)
    }
}
