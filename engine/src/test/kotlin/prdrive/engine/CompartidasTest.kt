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
 * CompartidasTest.kt — El fichero de las parejas que viven fuera del volumen y
 * las reglas de qué parejas y qué rutas pueden hacerlo.
 */
class CompartidasTest {

    private val temporales = ArrayList<File>()

    private fun temporal(): File =
        Files.createTempDirectory("prdrive-compartidas").toFile().also { temporales.add(it) }

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
    }

    private fun pareja(nombre: String, local: String, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        mapOf("name" to nombre, "mode" to "bisync", "local" to local, "remote_path" to "x/$nombre") + extra

    private fun config(vararg parejas: Map<String, Any?>, extra: Map<String, Any?> = emptyMap()): Config =
        parseConfig(
            mapOf(
                "defaults" to mapOf("device_remote" to "disp", "remote" to "nas"),
                "pair" to parejas.toList(),
            ) + extra,
        )

    @Test
    fun `lo escrito se lee igual`() {
        val fichero = File(temporal(), Compartidas.FICHERO)
        val entradas = linkedMapOf(
            "vault" to Compartidas.Entrada("/storage/emulated/0/Documents/vault", Compartidas.Estado.LISTA),
            "fotos" to Compartidas.Entrada("/storage/emulated/0/DCIM/fotos", Compartidas.Estado.ENTRANDO),
        )
        Compartidas.escribir(fichero, entradas)
        assertEquals(entradas, Compartidas.leer(fichero))
        // Y en el orden de la brief: cada entrada con `ruta` antes que `estado`, y en su orden.
        val texto = fichero.readText(Charsets.UTF_8)
        val posiciones = listOf("\"vault\"", "\"ruta\"", "\"estado\"", "\"lista\"", "\"fotos\"", "\"entrando\"")
            .map { texto.indexOf(it) }
        assertEquals(posiciones.sorted(), posiciones, texto)
        assertTrue(posiciones.all { it >= 0 }, texto)
    }

    @Test
    fun `sin fichero no hay ninguna`() {
        assertEquals(emptyMap(), Compartidas.leer(File(temporal(), Compartidas.FICHERO)))
    }

    @Test
    fun `un fichero roto no se lee como si no hubiera ninguna`() {
        val fichero = File(temporal(), Compartidas.FICHERO)
        for (texto in listOf(
            "no es json",
            "[]",
            """{"vault": {"ruta": 3, "estado": "lista"}}""",
            """{"vault": {"ruta": "/x", "estado": "otra"}}""",
            """{"vault": "/x"}""",
        )) {
            fichero.writeText(texto, Charsets.UTF_8)
            val error = assertFailsWith<ConfigError>(texto) { Compartidas.leer(fichero) }
            assertTrue(Compartidas.FICHERO in error.message.orEmpty(), "«$texto»: ${error.message}")
        }
    }

    @Test
    fun `un fichero que no se puede leer tampoco se toma por vacio`() {
        // Una carpeta con el nombre del fichero: existe y no se lee.
        val fichero = File(temporal(), Compartidas.FICHERO).also { it.mkdirs() }
        assertFailsWith<ConfigError> { Compartidas.leer(fichero) }
    }

    @Test
    fun `una pareja bisync de un solo tramo y sola en su carpeta se puede mover`() {
        val config = config(pareja("vault", "vault"), pareja("otra", "otra"))
        assertNull(Compartidas.problemaPareja(config, "vault"))
    }

    @Test
    fun `cada regla de la pareja tiene su motivo`() {
        val base = pareja("vault", "vault")
        assertNotNull(Compartidas.problemaPareja(config(base + ("mode" to "up")), "vault"), "no es bisync")
        assertNotNull(
            Compartidas.problemaPareja(config(pareja("vault", "notas/vault")), "vault"),
            "más de un tramo",
        )
        assertNotNull(
            Compartidas.problemaPareja(config(base, pareja("todo", ".")), "vault"),
            "hay una pareja de la raíz",
        )
        assertNotNull(
            Compartidas.problemaPareja(config(pareja("todo", ".")), "todo"),
            "es la de la raíz",
        )
        assertNotNull(
            Compartidas.problemaPareja(config(base, pareja("sub", "vault/sub")), "vault"),
            "otra pareja comparte su carpeta de primer nivel",
        )
        assertNotNull(Compartidas.problemaPareja(config(base), "no-existe"), "no existe")
        val conLlavero = config(base, extra = mapOf("keychain" to mapOf("base" to "x.kdbx")))
        assertNull(Compartidas.problemaPareja(conLlavero, "vault"))
        assertNotNull(Compartidas.problemaPareja(conLlavero, LLAVERO), "es la del llavero")
    }

    @Test
    fun `cada regla de la ruta tiene su motivo`() {
        val raiz = temporal()
        val ruta = raiz.canonicalPath
        val otras = mapOf(
            "otra" to Compartidas.Entrada("/storage/emulated/0/Otra/carpeta", Compartidas.Estado.LISTA),
        )
        fun problema(texto: String) = Compartidas.problemaRuta(texto, raiz, otras)

        assertNull(problema("/storage/emulated/0/Documents/vault"))
        for (mala in listOf(
            "Documents/vault", "/a/./b", "/a/../b", "/a//b", "/a/b/",
            ruta, "$ruta/vault", raiz.canonicalFile.parentFile.path, raiz.absolutePath,
            "/storage/emulated/0/Otra/carpeta",
            "/storage/emulated/0/Otra/carpeta/dentro",
            "/storage/emulated/0/Otra",
        )) {
            assertNotNull(problema(mala), "«$mala»")
        }
        // Por tramos, no por texto: un nombre que empieza igual no es lo mismo.
        assertNull(problema("/storage/emulated/0/Otra/carpeta2"))
        assertNull(problema(ruta + "-hermana"))
    }

    @Test
    fun `problema junta la regla de la pareja y la de la ruta`() {
        val config = config(pareja("vault", "vault"))
        val raiz = temporal()
        assertNull(Compartidas.problema(config, "vault", "/storage/emulated/0/vault", raiz, emptyMap()))
        assertNotNull(Compartidas.problema(config, "otra", "/storage/emulated/0/vault", raiz, emptyMap()))
        assertNotNull(Compartidas.problema(config, "vault", "vault", raiz, emptyMap()))
    }

    // --- La redirección en el combine ---

    private val rutaVault = "/storage/emulated/0/Documents/vault"

    private fun dosParejas(localVault: String = "vault"): Config =
        config(pareja("notas", "notas"), pareja("vault", localVault))

    private fun entradaVault(estado: Compartidas.Estado = Compartidas.Estado.LISTA) =
        mapOf("vault" to Compartidas.Entrada(rutaVault, estado))

    /** Los tramos de un `upstreams`: entre comillas y separados por espacios. */
    private fun tramos(upstreams: String): List<String> =
        upstreams.removeSurrounding("\"").split("\" \"")

    @Test
    fun `con una entrada el upstream de la pareja es su carpeta y los demas no cambian`() {
        val raiz = temporal()
        val c = dosParejas()
        val sin = c.upstreamsDeCombine(raiz.absolutePath)
        val con = c.conCompartidas(entradaVault(), raiz).upstreamsDeCombine(raiz.absolutePath)
        val esperado = tramos(sin).map {
            if (it.startsWith("vault=")) upstream("vault", rutaVault).removeSurrounding("\"") else it
        }
        assertEquals(esperado, tramos(con))
        assertTrue(con != sin)
    }

    @Test
    fun `una ruta con espacios cabe en el upstream`() {
        val raiz = temporal()
        val ruta = "/storage/emulated/0/Documents/mi vault"
        val e = mapOf("vault" to Compartidas.Entrada(ruta, Compartidas.Estado.LISTA))
        val con = dosParejas().conCompartidas(e, raiz).upstreamsDeCombine(raiz.absolutePath)
        assertTrue(con.contains(upstream("vault", ruta)), con)
    }

    @Test
    fun `una entrada que no vale no redirige nada y deja su motivo`() {
        val raiz = temporal()
        val c = dosParejas("a/vault")
        val aplicada = c.conCompartidas(entradaVault(), raiz)
        assertEquals(c.upstreamsDeCombine(raiz.absolutePath), aplicada.upstreamsDeCombine(raiz.absolutePath))
        val vault = aplicada.pairs.first { it.name == "vault" }
        assertNull(vault.carpetaExterna)
        assertNotNull(vault.problemaCompartida)
    }

    @Test
    fun `una entrada de una pareja que no esta se ignora`() {
        val raiz = temporal()
        val c = config(pareja("notas", "notas"))
        assertEquals(c, c.conCompartidas(entradaVault(), raiz))
    }

    @Test
    fun `aplicar las compartidas dos veces da lo mismo`() {
        val raiz = temporal()
        val c = dosParejas()
        val una = c.conCompartidas(entradaVault(), raiz)
        assertEquals(una, una.conCompartidas(entradaVault(), raiz))
        // Y sin entradas lo anterior se borra: un config ya aplicado no arrastra nada.
        assertEquals(c, una.conCompartidas(emptyMap(), raiz))
    }

    @Test
    fun `la pareja compartida conserva su nombre de sesion y sus filtros`() {
        val raiz = temporal()
        val tipos = Bisync.tiposDeRemote("[nas]\ntype = sftp\n")
        val c = dosParejas()
        val con = c.conCompartidas(entradaVault(), raiz)
        val antes = c.pairs.first { it.name == "vault" }
        val despues = con.pairs.first { it.name == "vault" }
        assertNotNull(despues.carpetaExterna)
        assertEquals(Bisync.expectedPrefix(antes, tipos), Bisync.expectedPrefix(despues, tipos))
        assertEquals(Bisync.filtersContent(antes), Bisync.filtersContent(despues))
    }

    @Test
    fun `la carpeta de una pareja compartida es la suya`() {
        val raiz = temporal()
        val vault = dosParejas().conCompartidas(entradaVault(), raiz).pairs.first { it.name == "vault" }
        assertEquals(File(rutaVault), vault.carpetaEn(raiz))
    }

    private fun volumenConDosParejas(): Volumen {
        val v = Volumen(File(temporal(), "volumen"))
        v.escribirConfig(
            mapOf(
                "defaults" to mapOf("device_remote" to "disp", "remote" to "nas"),
                "pair" to listOf(pareja("notas", "notas"), pareja("vault", "vault")),
            ),
            "",
        )
        return v
    }

    @Test
    fun `leerConfig trae las compartidas puestas`() {
        val v = volumenConDosParejas()
        Compartidas.escribir(v.compartidas, entradaVault())
        val config = v.leerConfig()
        assertEquals(rutaVault, config.pairs.first { it.name == "vault" }.carpetaExterna)
        assertNull(config.pairs.first { it.name == "notas" }.carpetaExterna)
    }

    @Test
    fun `leerConfig con el fichero de compartidas roto lo dice`() {
        val v = volumenConDosParejas()
        v.compartidas.writeText("{esto no es json")
        assertFailsWith<ConfigError> { v.leerConfig() }
    }

    @Test
    fun `reescribir el combine con un config sin compartidas conserva la redireccion`() {
        val v = volumenConDosParejas()
        Compartidas.escribir(v.compartidas, entradaVault())
        v.rcloneConf.writeText("[nas]\ntype = sftp\n")
        v.actualizarCombine(parseConfig(loadRaw(v.config.readText())))
        assertTrue(v.rcloneConf.readText().contains(upstream("vault", rutaVault)), v.rcloneConf.readText())
    }

    @Test
    fun `una ruta con un caracter de control no vale`() {
        val raiz = temporal()
        for (mala in listOf("a\nb", "a\rb", "a\u0000b")) {
            val ruta = "/storage/emulated/0/Documents/$mala"
            assertNotNull(Compartidas.problemaRuta(ruta, raiz, emptyMap()), ruta)
        }
    }

    @Test
    fun `una entrada con un salto de linea no redirige nada y deja su motivo`() {
        val raiz = temporal()
        val c = dosParejas()
        val e = mapOf("vault" to Compartidas.Entrada("$rutaVault\nx = y", Compartidas.Estado.LISTA))
        val aplicada = c.conCompartidas(e, raiz)
        assertEquals(c.upstreamsDeCombine(raiz.absolutePath), aplicada.upstreamsDeCombine(raiz.absolutePath))
        assertNotNull(aplicada.pairs.first { it.name == "vault" }.problemaCompartida)
    }

    @Test
    fun `un upstream con un salto de linea en el nombre o en la ruta se rechaza`() {
        assertFailsWith<ConfigError> { upstream("vault", "/x\ny") }
        assertFailsWith<ConfigError> { upstream("vault", "/x\ry") }
        assertFailsWith<ConfigError> { upstream("va\nult", "/x") }
    }

    private fun conf(combine: String) = "[nas]\ntype = sftp\n\n$combine"

    @Test
    fun `alDia solo dice que si el combine lleva el par de su carpeta`() {
        val raiz = temporal()
        val c = dosParejas()
        val con = c.conCompartidas(entradaVault(), raiz)
        val vault = con.pairs.first { it.name == "vault" }
        assertTrue(Compartidas.alDia(conf(con.seccionCombine(raiz.absolutePath)), vault))
        // El `combine` de antes del movimiento, el de otra carpeta y sin sección.
        assertFalse(Compartidas.alDia(conf(c.seccionCombine(raiz.absolutePath)), vault))
        val otra = c.conCompartidas(
            mapOf("vault" to Compartidas.Entrada("$rutaVault-otra", Compartidas.Estado.LISTA)), raiz,
        )
        assertFalse(Compartidas.alDia(conf(otra.seccionCombine(raiz.absolutePath)), vault))
        assertFalse(Compartidas.alDia("[nas]\ntype = sftp\n", vault))
        // Una pareja que no está en una carpeta compartida no está «al día».
        val sinMover = c.pairs.first { it.name == "vault" }
        assertFalse(Compartidas.alDia(conf(con.seccionCombine(raiz.absolutePath)), sinMover))
    }

    @Test
    fun `alDia compara el par entero, no un trozo de otra ruta`() {
        val raiz = temporal()
        val vault = dosParejas().conCompartidas(entradaVault(), raiz).pairs.first { it.name == "vault" }
        // Una carpeta que lleva dentro, como texto, el par de `vault`.
        val trampa = "[disp]\ntype = combine\nupstreams = " + upstream("otra", "/x ${upstream("vault", rutaVault)} y")
        assertFalse(Compartidas.alDia(trampa, vault), trampa)
        // Con la ruta del propio `vault` llena de espacios y comillas, sí. Se pone a
        // mano: en Windows, donde también corren los tests, `"` no cabe en una ruta.
        val rara = "/storage/emulated/0/Documents/mi \"vault\" 2"
        val parejaRara = vault.copy(carpetaExterna = rara)
        fun combine(ruta: String) = conf("[disp]\ntype = combine\nupstreams = ${upstream("vault", ruta)}\n")
        assertTrue(Compartidas.alDia(combine(rara), parejaRara))
        assertFalse(Compartidas.alDia(combine(rutaVault), parejaRara))
    }

    // La prueba de fechas de una carpeta compartida: la hora de lo copiado tiene que
    // ser la del original (dentro de la precisión que dice rclone) y no moverse sola.

    private val hora = java.time.Instant.parse("2023-11-14T22:13:20.123Z")
    private val milisegundo = java.time.Duration.ofMillis(1)

    @Test
    fun `las fechas valen con la misma hora dentro de la precision y que no cambia`() {
        // Igual, y con una diferencia que cabe en la precisión.
        assertNull(Compartidas.problemaFechas(hora, hora, hora, milisegundo))
        val redondeada = hora.plusNanos(500_000)
        assertNull(Compartidas.problemaFechas(hora, redondeada, redondeada, milisegundo))
        // Justo en el límite todavía vale.
        val limite = hora.plus(milisegundo)
        assertNull(Compartidas.problemaFechas(hora, limite, limite, milisegundo))
    }

    @Test
    fun `una hora que cambia al releerla no vale`() {
        assertNotNull(Compartidas.problemaFechas(hora, hora, hora.plusSeconds(2), milisegundo))
        // Ni la que ya sale distinta al copiar, por más que luego se quede quieta.
        val otra = hora.plusSeconds(1)
        assertNotNull(Compartidas.problemaFechas(hora, otra, otra, milisegundo))
    }

    @Test
    fun `una carpeta sin fechas no vale`() {
        // rclone dice 100 años de precisión cuando el sitio no guarda fechas
        // (`fs.ModTimeNotSupported`): todo cabría en ella, y no vale.
        val cien = java.time.Duration.ofDays(365L * 100)
        assertNotNull(Compartidas.problemaFechas(hora, hora, hora, cien))
        assertNotNull(Compartidas.problemaFechas(hora, hora.plusSeconds(3), hora.plusSeconds(3), cien))
    }
}
