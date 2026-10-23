package prdrive.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * LlaveroTest.kt — El llavero (la base de KeePassXC) como lo cuenta prdrive.
 * Si una base está entera sin abrirla (`common/kdbx.py`), el `LEEME.txt`, las
 * bases rotas, el listado de bisync y lo pendiente (`common/llavero.py`); lo que
 * se espera sale de `herramientas/vectores_llavero.py`, que se lo pregunta a
 * prdrive.
 */
class LlaveroTest {

    private val v = Vectores.seccion("llavero")

    private val temporales = ArrayList<File>()

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
        temporales.clear()
    }

    private fun temporal(): File =
        Files.createTempDirectory("prdrive-llavero").toFile().also { temporales.add(it) }

    private fun hex(texto: String): ByteArray =
        ByteArray(texto.length / 2) { texto.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun `una base se da por entera o rota como la mira prdrive`() {
        val casos = Vectores.objetos(v, "kdbx")
        assertTrue(casos.isNotEmpty())
        for (caso in casos) {
            val e = Kdbx.mirar(hex(Vectores.texto(caso, "hex")))
            assertEquals(caso["entera"], e.entera, "«${caso["caso"]}»")
            assertEquals(caso["version"], e.version, "«${caso["caso"]}»")
            assertEquals(caso["motivo"], e.motivo, "«${caso["caso"]}»")
        }
    }

    @Test
    fun `comprobar lee el fichero y uno que no se lee cuenta como no entero`() {
        val casos = Vectores.objetos(v, "kdbx")
        val entera = casos.first { it["entera"] == true && it["version"] == "4.1" }
        val cortada = casos.first { it["caso"].toString().startsWith("KDBX 4 cortada en") &&
            it["motivo"] == "está cortada" }
        val d = temporal()
        File(d, "buena.kdbx").writeBytes(hex(Vectores.texto(entera, "hex")))
        File(d, "cortada.kdbx").writeBytes(hex(Vectores.texto(cortada, "hex")))
        assertEquals(true, Kdbx.comprobar(File(d, "buena.kdbx")).entera)
        assertEquals(false, Kdbx.comprobar(File(d, "cortada.kdbx")).entera)

        val sin = Vectores.objeto(v, "kdbx_sin_fichero")
        val e = Kdbx.comprobar(File(d, "no-existe.kdbx"))
        assertEquals(sin["entera"], e.entera)
        assertEquals(sin["version"], e.version)
        assertTrue(e.motivo.startsWith(Vectores.texto(sin, "motivo_empieza")), e.motivo)
    }

    // --- el LEEME, las bases rotas y lo pendiente ------------------------------

    private fun largo(valor: Any?): Long = (valor as Number).toLong()

    /** Los milisegundos de unas esperas dichas en segundos, como las cuenta el motor. */
    private fun enMs(segundos: Any?): Long = ((segundos as Number).toDouble() * 1000).toLong()

    private fun horaDe(fichero: File): Long =
        Files.getLastModifiedTime(fichero.toPath()).to(TimeUnit.NANOSECONDS)

    private fun fijarHora(fichero: File, ns: Long) {
        Files.setLastModifiedTime(fichero.toPath(), FileTime.from(ns, TimeUnit.NANOSECONDS))
    }

    private fun hexDe(datos: ByteArray): String {
        val cifras = "0123456789abcdef"
        val s = StringBuilder()
        for (b in datos) {
            s.append(cifras[(b.toInt() shr 4) and 15]).append(cifras[b.toInt() and 15])
        }
        return s.toString()
    }

    /** Escribe los ficheros de un caso (`ruta`, `hex` o `tamano`, y `mtime_ns`). */
    private fun poner(donde: File, ficheros: List<Map<String, Any?>>) {
        for (f in ficheros) {
            val ruta = File(donde, Vectores.texto(f, "ruta"))
            ruta.parentFile.mkdirs()
            ruta.writeBytes(
                if (f["hex"] != null) {
                    hex(Vectores.texto(f, "hex"))
                } else {
                    ByteArray(largo(f["tamano"]).toInt()) { 'x'.code.toByte() }
                },
            )
        }
        for (f in ficheros) {
            val ns = f["mtime_ns"] ?: continue
            fijarHora(File(donde, Vectores.texto(f, "ruta")), largo(ns))
        }
    }

    /** Lo que hay de `LEEME.txt` en una carpeta, con la forma de los vectores. */
    private fun leemeDe(donde: File): Map<String, Any?> {
        val f = File(donde, Llavero.LEEME)
        return if (f.isFile) {
            mapOf("existe" to true, "hex" to hexDe(f.readBytes()), "mtime_ns" to horaDe(f))
        } else {
            mapOf("existe" to false, "hex" to null, "mtime_ns" to null)
        }
    }

    private fun rutasDe(donde: File): List<String> =
        donde.walkTopDown().filter { it.isFile }.map { it.relativeTo(donde).invariantSeparatorsPath }
            .toList().sorted()

    private fun assertLeeme(esperado: Map<String, Any?>, donde: File, que: String) {
        val real = leemeDe(donde)
        assertEquals(esperado["existe"], real["existe"], "$que: ¿hay LEEME?")
        assertEquals(esperado["hex"], real["hex"], "$que: el contenido del LEEME")
        assertEquals(esperado["mtime_ns"]?.let(::largo), real["mtime_ns"], "$que: la hora del LEEME")
    }

    @Test
    fun `el LEEME es el mismo byte a byte y con la misma hora en todos los dispositivos`() {
        val leeme = Vectores.objeto(v, "leeme")
        assertEquals(Vectores.texto(leeme, "nombre"), Llavero.LEEME)
        assertEquals(Vectores.texto(leeme, "texto"), Llavero.LEEME_TEXTO)
        assertEquals(Vectores.texto(leeme, "texto_hex"), hexDe(Llavero.LEEME_TEXTO.toByteArray(Charsets.UTF_8)))
        assertTrue('\r' !in Llavero.LEEME_TEXTO, "el LEEME lleva \\n en todos los sistemas")
        assertEquals(largo(leeme["hora"]), Llavero.LEEME_HORA)
        assertEquals(enMs(leeme["espera_entera"]), Llavero.ESPERA_ENTERA_MS)
        assertEquals(largo(leeme["tolerancia_hora"]), Llavero.TOLERANCIA_HORA)

        // Qué devuelve `asegurarLeeme` y cómo deja el fichero, con y sin corregir la hora.
        for (caso in Vectores.objetos(leeme, "asegurar")) {
            val nombre = "«${caso["caso"]}» (corregir_hora=${caso["corregir_hora"]})"
            val donde = File(temporal(), LLAVERO_LOCAL)
            if (caso["carpeta_existe"] == true) donde.mkdirs()
            poner(donde, Vectores.objetos(caso, "ficheros"))
            val devuelve = Llavero.asegurarLeeme(donde, corregirHora = caso["corregir_hora"] == true)
            assertEquals(caso["devuelve"], devuelve, "$nombre: lo que devuelve")
            assertLeeme(Vectores.objeto(caso, "despues"), donde, nombre)
        }
    }

    @Test
    fun `la carpeta del llavero se crea con su LEEME y su hora`() {
        val leeme = Vectores.objeto(v, "leeme")
        val raiz = temporal()
        val donde = Llavero.prepararCarpeta(raiz)
        assertEquals(File(raiz, LLAVERO_LOCAL), donde)
        assertTrue(donde.isDirectory)
        assertEquals(Vectores.texto(leeme, "texto_hex"), leemeDe(donde)["hex"])
        assertEquals(largo(leeme["hora"]) * 1_000_000_000, leemeDe(donde)["mtime_ns"])
        // Una segunda vez no cambia nada.
        assertEquals(donde, Llavero.prepararCarpeta(raiz))
        assertEquals(listOf(Llavero.LEEME), rutasDe(donde))
    }

    @Test
    fun `las bases que viajan salen en el mismo orden que en prdrive`() {
        for (caso in Vectores.objetos(Vectores.objeto(v, "leeme"), "bases")) {
            val donde = File(temporal(), LLAVERO_LOCAL)
            if (caso["carpeta_existe"] == true) donde.mkdirs()
            poner(donde, Vectores.cadenas(caso, "ficheros").map { mapOf("ruta" to it, "tamano" to 3L) })
            assertEquals(
                Vectores.cadenas(caso, "bases"),
                Llavero.bases(donde).map { it.relativeTo(donde).invariantSeparatorsPath },
                "«${caso["caso"]}»",
            )
        }
    }

    @Test
    fun `preparar dice lo mismo que prdrive en cada carpeta`() {
        val casos = Vectores.objetos(Vectores.objeto(v, "leeme"), "preparar")
        assertTrue(casos.isNotEmpty())
        for (caso in casos) {
            val nombre = "«${caso["caso"]}»"
            val donde = File(temporal(), LLAVERO_LOCAL)
            if (caso["carpeta_existe"] == true) {
                donde.mkdirs()
                poner(donde, Vectores.objetos(caso, "ficheros"))
            }
            val esperas = ArrayList<Long>()
            val durante = Vectores.objeto(caso, "durante_la_espera")
            val motivo = Llavero.preparar(donde, simulacro = caso["simulacro"] == true) { ms ->
                esperas.add(ms)
                for ((ruta, datos) in durante) File(donde, ruta).writeBytes(hex(datos.toString()))
            }
            assertEquals(caso["motivo"], motivo, "$nombre: el motivo")
            assertEquals(Vectores.lista(caso, "esperas").map(::enMs), esperas, "$nombre: las esperas")
            val despues = Vectores.objeto(caso, "despues")
            assertEquals(despues["carpeta_existe"], donde.isDirectory, "$nombre: la carpeta")
            if (despues["carpeta_existe"] == true) {
                assertLeeme(Vectores.objeto(despues, "leeme"), donde, nombre)
                assertEquals(Vectores.cadenas(despues, "ficheros"), rutasDe(donde), "$nombre: los ficheros")
            }
        }
    }

    @Test
    fun `una base rota se vuelve a mirar a los 2 segundos y una entera no espera`() {
        val donde = File(temporal(), LLAVERO_LOCAL)
        donde.mkdirs()
        val esperas = ArrayList<Long>()
        assertEquals(emptyList(), Llavero.rotas(donde) { esperas.add(it) })
        assertEquals(emptyList(), esperas)
        val cortada = Vectores.objetos(v, "kdbx").first { it["motivo"] == "está cortada" }
        File(donde, "a.kdbx").writeBytes(hex(Vectores.texto(cortada, "hex")))
        val rotas = Llavero.rotas(donde) { esperas.add(it) }
        assertEquals(listOf(Llavero.ESPERA_ENTERA_MS), esperas)
        assertEquals(listOf(File(donde, "a.kdbx")), rotas.map { it.fichero })
        assertEquals(listOf("está cortada"), rotas.map { it.motivo })
    }

    /** El fichero de un listado de un caso de los vectores; sin `hex`, no existe. */
    private fun listadoDeCaso(hexa: Any?): File {
        val f = File(temporal(), "listado.path1.lst")
        if (hexa != null) f.writeBytes(hex(hexa.toString()))
        return f
    }

    @Test
    fun `el listado de bisync se lee como lo lee prdrive`() {
        val listado = Vectores.objeto(v, "listado")
        val casos = Vectores.objetos(listado, "lineas")
        assertTrue(casos.size > 20)
        for (caso in casos) {
            val esperado = (caso["esperado"] as? Map<*, *>)?.entries?.associate { (ruta, par) ->
                val l = par as List<*>
                ruta.toString() to Pair(largo(l[0]), largo(l[1]))
            }
            assertEquals(esperado, Llavero.listadoLocal(listadoDeCaso(caso["hex"])), "«${caso["caso"]}»")
        }
        // Una carpeta donde debería haber un listado no se lee: sin listado.
        assertEquals(null, Llavero.listadoLocal(temporal()))

        // Y es el `path1` de la pareja del llavero, en su workdir.
        val config = parseConfig(Vectores.objeto(listado, "config"))
        val pareja = config.parejaLlavero ?: error("el config de los vectores lleva llavero")
        assertEquals(Vectores.texto(listado, "prefijo"), Bisync.expectedPrefix(pareja, emptyMap()))
        assertEquals(Vectores.texto(listado, "sufijo"), Bisync.PATH1_SUFFIX)
        val raiz = temporal()
        assertEquals(
            File(raiz, Vectores.texto(listado, "ruta")),
            Llavero.listadoDe(Volumen(raiz), pareja, emptyMap()),
        )
    }

    @Test
    fun `lo pendiente y lo sin listar salen como en prdrive`() {
        val casos = Vectores.objetos(Vectores.objeto(v, "listado"), "pendiente")
        assertTrue(casos.size > 15)
        for (caso in casos) {
            val nombre = "«${caso["caso"]}»"
            val donde = File(temporal(), LLAVERO_LOCAL)
            donde.mkdirs()
            poner(donde, Vectores.objetos(caso, "ficheros"))
            val listado = listadoDeCaso(caso["listado_hex"])
            assertEquals(caso["pendiente"], Llavero.pendiente(donde, listado), "$nombre: pendiente")
            assertEquals(Vectores.cadenas(caso, "sin_listar"), Llavero.sinListar(donde, listado), "$nombre: sin listar")
        }
    }

    // --- los frenos de rclone y el alta desde el remoto ----------------------

    private val frenos = Vectores.objeto(v, "frenos")

    @Test
    fun `los frenos del llavero se leen del log como en prdrive`() {
        assertEquals(largo(frenos["freno_sin_copias"]), Llavero.FRENO_SIN_COPIAS)
        val pareja = parseConfig(Vectores.objeto(frenos, "config")).parejaLlavero
            ?: error("el config de los vectores lleva llavero")

        val logs = Vectores.objetos(frenos, "logs")
        assertTrue(logs.size > 20)
        for (caso in logs) {
            val esperado = (caso["solo_copias"] as? List<*>)?.map { it.toString() }
            assertEquals(esperado, Llavero.soloCopiasBorradas(Vectores.texto(caso, "log"), pareja), "«${caso["caso"]}»")
        }

        val vacios = Vectores.objetos(frenos, "remoto_vacio")
        assertTrue(vacios.any { it["vacio"] == true } && vacios.any { it["vacio"] == false })
        for (caso in vacios) {
            assertEquals(caso["vacio"], Llavero.remotoVacio(Vectores.texto(caso, "log")), "«${caso["caso"]}»")
        }

        val simulados = Vectores.objetos(frenos, "borrarian")
        assertTrue(simulados.size > 10)
        for (caso in simulados) {
            assertEquals(Vectores.cadenas(caso, "borrarian"), Pasada.borrarian(Vectores.texto(caso, "log")), "«${caso["caso"]}»")
        }
    }

    @Test
    fun `al llavero se le explica el freno sin decirle que lo fuerce`() {
        val e = Vectores.objeto(frenos, "explicaciones")
        assertEquals(Vectores.texto(e, "llavero"), Pasada.EXPLICACION_LLAVERO)
        assertEquals(Vectores.texto(e, "llavero_cambiados"), Pasada.EXPLICACION_LLAVERO_CAMBIADOS)
        // Las de los frenos son las de prdrive, y por ese orden.
        assertEquals(Vectores.cadenas(e, "entradas"), Pasada.EXPLICACIONES)
        assertEquals(3, Pasada.EXPLICACIONES.size)
        for (caso in Vectores.objetos(e, "explicacion_llavero") + Vectores.objetos(e, "otras")) {
            assertEquals(
                Vectores.texto(caso, "dice"),
                Pasada.explicacionLlavero(Vectores.texto(caso, "explicacion")),
                "«${caso["explicacion"]}»",
            )
        }
        // Ninguna manda a forzar la pasada.
        assertTrue(Pasada.EXPLICACIONES.all { Pasada.COMO_FORZAR in it })
        assertTrue(Pasada.EXPLICACIONES.none { Pasada.COMO_FORZAR in Pasada.explicacionLlavero(it) })
    }

    @Test
    fun `traer el llavero del remoto lleva su tabla tal cual`() {
        val casos = Vectores.objetos(frenos, "alta")
        assertTrue(casos.any { it.containsKey("error") } && casos.any { it.containsKey("tabla") })
        for (caso in casos) {
            @Suppress("UNCHECKED_CAST")
            val remota = caso["remota"] as Map<String, Any?>?
            val nombre = "«${caso["caso"]}»"
            if (caso.containsKey("error")) {
                val error = assertFailsWith<ConfigError>(nombre) { Llavero.decidirAlta(remota) }
                assertEquals(Vectores.texto(caso, "error"), error.message, nombre)
            } else {
                val alta = Llavero.decidirAlta(remota)
                assertEquals(caso["tabla"], alta, nombre)
                // Es una copia: lo que se le haga no toca la del catálogo.
                assertTrue(alta !== remota, nombre)
            }
        }
    }
}
