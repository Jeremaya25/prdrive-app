package prdrive.engine

import java.io.File
import java.io.IOException
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
 * EditarParejasTest.kt — Añadir una pareja del catálogo al teléfono y quitarla.
 *
 * Lo que refleja a `ui/pair_editor.py` (`plan_enable`, `plan_remove`) se compara
 * con lo que dice prdrive (`editar_parejas` en `vectores.json`). Lo del teléfono
 * (la carpeta elegida, los avisos, las carpetas compartidas, el orden de lo que
 * se escribe) no es de prdrive y se prueba por lo que garantiza.
 */
class EditarParejasTest {

    private val vectores = Vectores.seccion("editar_parejas")

    private val temporales = ArrayList<File>()

    private fun temporal(): File =
        Files.createTempDirectory("prdrive-editar").toFile().also { temporales.add(it) }

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
    }

    private fun catalogo(raw: Map<String, Any?>?): Catalogo.Catalog? =
        raw?.let { Catalogo.Catalog(it, "", "remote", "2026-11-01 10:00", "nas:/prdrive-catalog/remote.toml") }

    /** Lo que dice prdrive de un plan, tal como lo apunta el generador. */
    private fun comprobarPlan(esperado: Map<String, Any?>, plan: EditarParejas.EditPlan, raiz: File?, caso: String) {
        @Suppress("UNCHECKED_CAST")
        val raw = esperado["raw"] as Map<String, Any?>
        assertEquals(normalizar(raw), normalizar(plan.raw), "«$caso»: el config")
        // Y el TOML que se escribiría con él.
        assertEquals(dumps(raw), dumps(plan.raw), "«$caso»: el TOML")
        assertEquals(Vectores.cadenas(esperado, "consequences"), plan.consequences, "«$caso»")
        assertEquals(Vectores.cadenas(esperado, "warnings"), plan.warnings, "«$caso»")
        assertEquals(Vectores.cadenas(esperado, "shelve"), plan.shelve, "«$caso»")
        assertEquals(
            Vectores.cadenas(esperado, "borrar_filtros"),
            plan.borrarFiltros.map { it.relativeTo(raiz!!).invariantSeparatorsPath },
            "«$caso»",
        )
    }

    // --- lo que dice prdrive -------------------------------------------------

    @Test
    fun `los avisos de espejo son los de pair_editor_py`() {
        assertEquals(Vectores.cadenas(vectores, "mirror_modes"), EditarParejas.MIRROR_MODES)
        for ((modo, aviso) in Vectores.objeto(vectores, "avisos_espejo")) {
            assertEquals(aviso, EditarParejas.mirrorWarning(modo), modo)
        }
        for ((modo, partes) in Vectores.objeto(vectores, "avisos_espejo_partidos")) {
            assertEquals(partes, EditarParejas.avisoEspejo(modo)?.toList(), modo)
        }
    }

    /** Lo que se espera de un caso que se niega: su mensaje, o un `ConfigError` donde prdrive se cae. */
    private fun comprobarNegativa(caso: Map<String, Any?>, nombre: String, pensar: () -> Unit) {
        val error = caso["error"] as String?
        val mensaje = assertFailsWith<ConfigError>(nombre) { pensar() }.message
        if (error != null) assertEquals(error, mensaje, nombre)
    }

    @Test
    fun `usar aqui una pareja del catalogo piensa lo mismo que plan_enable`() {
        val configs = Vectores.objeto(vectores, "configs")
        val base = Vectores.objeto(vectores, "catalogo")
        for (caso in Vectores.objetos(vectores, "enable")) {
            val nombre = Vectores.texto(caso, "caso")
            val pareja = Vectores.texto(caso, "nombre")
            val carpeta = caso["carpeta"] as String?
            // La carpeta elegida: la misma entrada con otro `local`, como en el generador.
            val raw = if (carpeta == null) base else base + ("pair" to Vectores.objetos(base, "pair").map {
                if (it["name"] == pareja) it + ("local" to carpeta) else it
            })
            val cat = if (caso["catalogo"] == true) catalogo(raw) else null
            val dispositivo = Vectores.objeto(configs, Vectores.texto(caso, "config"))
            if (caso["error"] != null || caso["rompe"] != null) {
                comprobarNegativa(caso, nombre) { EditarParejas.planEnable(dispositivo, cat, pareja) }
            } else {
                comprobarPlan(Vectores.objeto(caso, "plan"), EditarParejas.planEnable(dispositivo, cat, pareja), null, nombre)
            }
        }
    }

    @Test
    fun `quitar una pareja piensa lo mismo que plan_remove, con lo que haya en disco`() {
        val configs = Vectores.objeto(vectores, "configs")
        for (caso in Vectores.objetos(vectores, "remove")) {
            val nombre = Vectores.texto(caso, "caso")
            val carpeta = temporal()
            val state = File(carpeta, "state").apply { mkdirs() }
            val filters = File(carpeta, "filters").apply { mkdirs() }
            for (rel in Vectores.cadenas(caso, "en_disco")) {
                val f = File(carpeta, rel)
                if (rel.endsWith("/")) f.mkdirs() else f.apply { parentFile.mkdirs(); writeText("x") }
            }
            val raw = Vectores.objeto(configs, Vectores.texto(caso, "config"))
            val pareja = Vectores.texto(caso, "nombre")
            val limpiar = caso["limpiar"] == true
            if (caso["error"] != null || caso["rompe"] != null) {
                comprobarNegativa(caso, nombre) { EditarParejas.planRemove(raw, pareja, limpiar, state, filters) }
            } else {
                comprobarPlan(Vectores.objeto(caso, "plan"), EditarParejas.planRemove(raw, pareja, limpiar, state, filters), carpeta, nombre)
            }
        }
    }

    @Test
    fun `pensar no toca el disco ni el config de entrada`() {
        val configs = Vectores.objeto(vectores, "configs")
        val raw = Vectores.objeto(configs, "tres")
        val antes = dumps(raw)
        val carpeta = temporal()
        File(carpeta, "state/notas").mkdirs()
        EditarParejas.planRemove(raw, "notas", true, File(carpeta, "state"), File(carpeta, "filters"))
        EditarParejas.planEnable(raw, catalogo(Vectores.objeto(vectores, "catalogo")), "musica")
        assertEquals(antes, dumps(raw))
        assertTrue(File(carpeta, "state/notas").isDirectory)
    }

    // --- la carpeta elegida ---------------------------------------------------

    @Test
    fun `la carpeta escrita queda como la guarda el config`() {
        assertEquals("Fotos/2026", EditarParejas.limpiarCarpeta("  /Fotos//2026/ "))
        assertEquals("Fotos/2026", EditarParejas.limpiarCarpeta("Fotos\\2026"))
        assertEquals("a/b", EditarParejas.limpiarCarpeta("./a/./b/."))
        assertEquals(".", EditarParejas.limpiarCarpeta(""))
        assertEquals(".", EditarParejas.limpiarCarpeta(" / "))
        // Los `..` se quedan, para que los rechace la regla de siempre.
        assertEquals("../fuera", EditarParejas.limpiarCarpeta("../fuera"))
    }

    @Test
    fun `se sugiere la carpeta del catalogo si vale, y si no el nombre`() {
        val cat = catalogo(Vectores.objeto(vectores, "catalogo"))
        assertEquals("sync-data/fotos", EditarParejas.sugerencia(cat, "fotos"))
        assertEquals("sin-local", EditarParejas.sugerencia(cat, "sin-local"))
        // Una que sale del volumen no se sugiere.
        assertEquals("rara", EditarParejas.sugerencia(cat, "rara"))
        assertEquals("inventada", EditarParejas.sugerencia(null, "inventada"))
    }

    @Test
    fun `una carpeta con un caracter de control no vale, ni la del programa ni la del llavero`() {
        assertNotNull(EditarParejas.problemaTexto("fo\nto"))
        assertNotNull(EditarParejas.problemaTexto("a\u0000b"))
        assertNotNull(EditarParejas.problemaTexto(CARPETA_PROGRAMA))
        assertNotNull(EditarParejas.problemaTexto("$LLAVERO_LOCAL/otra"))
        assertNotNull(EditarParejas.problemaTexto("../fuera"))
        assertNull(EditarParejas.problemaTexto("Fotos/2026"))
        assertNull(EditarParejas.problemaTexto("."))
    }

    @Test
    fun `mirar una carpeta cuenta sus ficheros, sin seguir enlaces y hasta el tope`() {
        val raiz = temporal()
        assertTrue(EditarParejas.mirarCarpeta(File(raiz, "no")).vacia)
        assertFalse(EditarParejas.mirarCarpeta(File(raiz, "no")).existe)
        val carpeta = File(raiz, "c").apply { mkdirs() }
        File(carpeta, "sub/otra").mkdirs()
        assertTrue(EditarParejas.mirarCarpeta(carpeta).vacia, "solo carpetas vacías")
        File(carpeta, "a.txt").writeText("x")
        File(carpeta, "sub/b.txt").writeText("x")
        assertEquals(2, EditarParejas.mirarCarpeta(carpeta).ficheros)
        val fichero = File(raiz, "f").apply { writeText("x") }
        EditarParejas.mirarCarpeta(fichero).let { assertTrue(it.existe); assertFalse(it.esCarpeta); assertFalse(it.vacia) }
        val muchos = File(raiz, "muchos").apply { mkdirs() }
        repeat(EditarParejas.TOPE_FICHEROS + 5) { File(muchos, "f$it").writeText("") }
        val mirada = EditarParejas.mirarCarpeta(muchos)
        assertTrue(mirada.masDe)
        assertEquals("más de ${EditarParejas.TOPE_FICHEROS} ficheros", EditarParejas.cuantos(mirada))
    }

    @Test
    fun `la carpeta elegida va en el sitio de la del catalogo, o detras del nombre`() {
        val cat = catalogo(Vectores.objeto(vectores, "catalogo"))!!
        val con = Catalogo.findPair(EditarParejas.conCarpeta(cat, "fotos", "F"), "fotos")!!
        assertEquals(Catalogo.findPair(cat, "fotos")!!.keys.toList(), con.keys.toList())
        assertEquals("F", con["local"])
        val sin = Catalogo.findPair(EditarParejas.conCarpeta(cat, "sin-local", "otra"), "sin-local")!!
        assertEquals(listOf("name", "local", "remote_path"), sin.keys.toList())
        // Las demás, como estaban.
        assertEquals(Catalogo.findPair(cat, "musica"), Catalogo.findPair(EditarParejas.conCarpeta(cat, "fotos", "F"), "musica"))
    }

    @Test
    fun `lo que cambia en el catalogo no cuenta el local`() {
        val cat = catalogo(Vectores.objeto(vectores, "catalogo"))!!
        val otraCarpeta = EditarParejas.conCarpeta(cat, "fotos", "otra")
        assertEquals(emptyList(), EditarParejas.cambiosDelCatalogo(cat, otraCarpeta, "fotos"))
        val otroModo = cat.copy(raw = cat.raw + ("pair" to Vectores.objetos(cat.raw, "pair").map {
            if (it["name"] == "fotos") it + ("mode" to "up") else it
        }))
        assertEquals(listOf("mode"), EditarParejas.cambiosDelCatalogo(cat, otroModo, "fotos"))
        // Sin la pareja en uno de los dos, difieren todas las claves de la otra, menos `local`.
        assertEquals(listOf("name", "remote_path"), EditarParejas.cambiosDelCatalogo(cat, null, "fotos").filter { it in listOf("local", "name", "remote_path") })
    }

    // --- añadir, en un volumen ---------------------------------------------------

    /** Un volumen sembrado con las parejas de [parejas], su `rclone.conf` y el catálogo de los vectores. */
    private fun volumen(vararg parejas: Map<String, Any?>): Volumen {
        val v = Volumen(File(temporal(), "volumen"))
        val raw = mapOf(
            "defaults" to mapOf("device_remote" to "disp", "remote" to "nas"),
            "pair" to parejas.toList(),
        )
        v.escribirConfig(raw, "# cabecera\n")
        v.rcloneConf.writeText("[nas]\ntype = sftp\n\n" + v.leerConfig().seccionCombine(v.raiz.absolutePath))
        return v
    }

    private fun pareja(nombre: String, local: String, modo: String = "bisync"): Map<String, Any?> =
        mapOf("name" to nombre, "local" to local, "remote_path" to "/copia/$nombre", "mode" to modo)

    private fun raw(v: Volumen): Map<String, Any?> = loadRaw(v.config.readText(Charsets.UTF_8))

    private val cat: Catalogo.Catalog get() = catalogo(Vectores.objeto(vectores, "catalogo"))!!

    @Test
    fun `anadir piensa el plan de plan_enable con la carpeta elegida`() {
        val v = volumen(pareja("notas", "notas"))
        val p = EditarParejas.planAnadir(v, raw(v), cat, "fotos", " Fotos/2026/ ")
        assertEquals("Fotos/2026", p.local)
        val esperado = EditarParejas.planEnable(raw(v), EditarParejas.conCarpeta(cat, "fotos", "Fotos/2026"), "fotos")
        assertEquals(esperado, p.plan)
        assertTrue(p.todasLasConsecuencias.containsAll(esperado.consequences))
    }

    @Test
    fun `una bisync con la carpeta vacia o nueva se inicializa ya, y con ficheros no`() {
        val v = volumen(pareja("notas", "notas"))
        assertTrue(EditarParejas.planAnadir(v, raw(v), cat, "fotos", "nueva").resyncYa)
        File(v.raiz, "vacia").mkdirs()
        assertTrue(EditarParejas.planAnadir(v, raw(v), cat, "fotos", "vacia").resyncYa)
        File(v.raiz, "llena").mkdirs()
        File(v.raiz, "llena/a.jpg").writeText("x")
        val llena = EditarParejas.planAnadir(v, raw(v), cat, "fotos", "llena")
        assertFalse(llena.resyncYa)
        assertEquals(1, llena.carpeta.ficheros)
        // Las que no son bisync no tienen referencia que hacer.
        assertFalse(EditarParejas.planAnadir(v, raw(v), cat, "musica", "nueva").resyncYa)
    }

    @Test
    fun `un espejo avisa de lo que borraria su carpeta`() {
        val v = volumen(pareja("notas", "notas"))
        val sube = EditarParejas.planAnadir(v, raw(v), cat, "espejo-sube", "web")
        assertTrue(sube.avisos.any { "espejo de subida" in it && "no existe" in it }, sube.avisos.toString())
        File(v.raiz, "web/index.html").apply { parentFile.mkdirs(); writeText("x") }
        assertTrue(EditarParejas.planAnadir(v, raw(v), cat, "espejo-sube", "web").avisos.none { "espejo de subida" in it })
        assertTrue(EditarParejas.planAnadir(v, raw(v), cat, "espejo-baja", "nueva").avisos.none { "espejo de bajada" in it })
        val baja = EditarParejas.planAnadir(v, raw(v), cat, "espejo-baja", "web")
        assertTrue(baja.avisos.any { "espejo de bajada" in it && "1 fichero" in it }, baja.avisos.toString())
        // Y el de prdrive va también.
        assertTrue(baja.todosLosAvisos.contains(EditarParejas.mirrorWarning("down-mirror")))
    }

    @Test
    fun `un espejo de subida con su carpeta vacia ya creada no se deja`() {
        val v = volumen(pareja("notas", "notas"))
        File(v.raiz, "web/sub").mkdirs()
        val error = assertFailsWith<ConfigError> { EditarParejas.planAnadir(v, raw(v), cat, "espejo-sube", "web") }
        assertTrue("borraría en el remoto" in error.message.orEmpty(), error.message)
        // Sin crear, sí: la pasada se para hasta que exista.
        EditarParejas.planAnadir(v, raw(v), cat, "espejo-sube", "otra")
    }

    @Test
    fun `la consecuencia que promete crear la carpeta de un espejo de subida no se ensena`() {
        val v = volumen(pareja("notas", "notas"))
        val p = EditarParejas.planAnadir(v, raw(v), cat, "espejo-sube", "web")
        val promesa = p.plan.consequences.single { "se creará" in it }
        assertFalse(promesa in p.todasLasConsecuencias, p.todasLasConsecuencias.toString())
        assertTrue(EditarParejas.NO_SE_CREA in p.todasLasConsecuencias)
        // En los demás modos se queda la de prdrive.
        val bisync = EditarParejas.planAnadir(v, raw(v), cat, "fotos", "fotos")
        assertEquals(bisync.plan.consequences, bisync.todasLasConsecuencias.take(bisync.plan.consequences.size))
    }

    @Test
    fun `un espejo de bajada que se solapa con otra pareja no se deja, ni otra que se solape con uno`() {
        val v = volumen(pareja("notas", "datos/notas"))
        for (carpeta in listOf(".", "datos", "datos/notas", "datos/notas/sub")) {
            val error = assertFailsWith<ConfigError>(carpeta) { EditarParejas.planAnadir(v, raw(v), cat, "espejo-baja", carpeta) }
            assertTrue("«espejo-baja» es un espejo de bajada" in error.message.orEmpty(), error.message)
        }
        EditarParejas.planAnadir(v, raw(v), cat, "espejo-baja", "datos/docs")
        // Y al revés: una bisync dentro de un espejo de bajada que ya está.
        val conEspejo = volumen(pareja("docs", "docs", "down-mirror"), pareja("notas", "notas"))
        val error = assertFailsWith<ConfigError> { EditarParejas.planAnadir(conEspejo, raw(conEspejo), cat, "fotos", "docs/fotos") }
        assertTrue("«docs» es un espejo de bajada" in error.message.orEmpty(), error.message)
        // Un espejo de subida que se solapa solo se avisa: no borra en el teléfono.
        val sube = EditarParejas.planAnadir(v, raw(v), cat, "espejo-sube", "datos/notas")
        assertTrue(sube.avisos.any { "«notas» ya usa" in it }, sube.avisos.toString())
    }

    @Test
    fun `avisa de otra pareja en la misma carpeta, dentro o alrededor`() {
        val v = volumen(pareja("notas", "datos/notas"), pareja("todo", "."))
        val misma = EditarParejas.planAnadir(v, raw(v), cat, "fotos", "datos/notas").avisos
        assertTrue(misma.any { "«notas» ya usa" in it }, misma.toString())
        assertTrue(misma.any { "«todo»" in it && "volumen entero" in it }, misma.toString())
        val dentro = EditarParejas.planAnadir(v, raw(v), cat, "fotos", "datos/notas/fotos").avisos
        assertTrue(dentro.any { "dentro de la carpeta de «notas»" in it }, dentro.toString())
        val fuera = EditarParejas.planAnadir(v, raw(v), cat, "fotos", "datos").avisos
        assertTrue(fuera.any { "contiene la carpeta de «notas»" in it }, fuera.toString())
        val otra = EditarParejas.planAnadir(v, volumen(pareja("notas", "notas")).let(::raw), cat, "fotos", "fotos").avisos
        assertTrue(otra.none { "notas" in it }, otra.toString())
    }

    @Test
    fun `no deja una carpeta que no vale ni una que no es carpeta`() {
        val v = volumen(pareja("notas", "notas"))
        assertFailsWith<ConfigError> { EditarParejas.planAnadir(v, raw(v), cat, "fotos", "../fuera") }
        assertFailsWith<ConfigError> { EditarParejas.planAnadir(v, raw(v), cat, "fotos", CARPETA_PROGRAMA) }
        assertFailsWith<ConfigError> { EditarParejas.planAnadir(v, raw(v), cat, "fotos", "a\nb") }
        File(v.raiz, "fichero").writeText("x")
        val error = assertFailsWith<ConfigError> { EditarParejas.planAnadir(v, raw(v), cat, "fotos", "fichero") }
        assertTrue("no es una carpeta" in error.message.orEmpty(), error.message)
        // Y lo de siempre de plan_enable.
        assertFailsWith<ConfigError> { EditarParejas.planAnadir(v, raw(v), cat, "notas", "otra") }
        assertFailsWith<ConfigError> { EditarParejas.planAnadir(v, raw(v), cat, "inventada", "otra") }
    }

    /** Un volumen con `vault` ya en una carpeta compartida (la entrada `lista`). */
    private fun conCompartida(): kotlin.Pair<Volumen, File> {
        val v = volumen(pareja("vault", "vault"), pareja("notas", "notas"))
        val fuera = File(temporal(), "Documents/vault").apply { mkdirs() }
        Compartidas.escribir(v.compartidas, mapOf("vault" to Compartidas.Entrada(fuera.path, Compartidas.Estado.LISTA)))
        v.actualizarCombine(v.leerConfig())
        assertNotNull(v.leerConfig().pairs.first { it.name == "vault" }.carpetaExterna)
        return v to fuera
    }

    @Test
    fun `no deja una carpeta que dejaria sin redireccion a una pareja compartida`() {
        val (v, _) = conCompartida()
        val dentro = assertFailsWith<ConfigError> { EditarParejas.planAnadir(v, raw(v), cat, "fotos", "vault/fotos") }
        assertTrue("«vault»" in dentro.message.orEmpty(), dentro.message)
        assertFailsWith<ConfigError> { EditarParejas.planAnadir(v, raw(v), cat, "fotos", ".") }
        // Una al lado, sí.
        EditarParejas.planAnadir(v, raw(v), cat, "fotos", "fotos")
    }

    @Test
    fun `anadir aparta la referencia de antes, rehace el combine y escribe el config el ultimo`() {
        val v = volumen(pareja("notas", "notas"))
        File(v.state, "fotos").mkdirs()
        File(v.state, "fotos/viejo.path1.lst").writeText("x")
        val p = EditarParejas.planAnadir(v, raw(v), cat, "fotos", "Fotos")
        assertTrue(p.apartar)
        var recargas = 0
        val hechos = EditarParejas.anadir(v, p, "# cabecera\n") { recargas++ }
        assertEquals(1, recargas)
        assertFalse(File(v.state, "fotos").exists())
        assertTrue(v.state.list()!!.any { it.startsWith("fotos.old-") }, hechos.toString())
        val config = v.leerConfig()
        assertEquals(listOf("notas", "fotos"), config.names)
        assertEquals("Fotos", config.pairs.first { it.name == "fotos" }.local)
        assertTrue(v.leerRcloneConf().contains(config.seccionCombine(v.raiz.absolutePath)))
        assertTrue(v.leerRcloneConf().startsWith("[nas]"))
        assertTrue(v.config.readText().startsWith("# cabecera\n"))
        // La carpeta no la crea: la crea la primera pasada, o no (un espejo de subida).
        assertFalse(File(v.raiz, "Fotos").exists())
    }

    @Test
    fun `si el config no se puede escribir, lo apartado vuelve y el rclone_conf tambien`() {
        val v = volumen(pareja("notas", "notas"))
        File(v.state, "fotos").mkdirs()
        val p = EditarParejas.planAnadir(v, raw(v), cat, "fotos", "Fotos")
        val confAntes = v.leerRcloneConf()
        val configAntes = v.config.readText()
        // Un config que es una carpeta no se puede escribir.
        v.config.delete()
        v.config.mkdirs()
        File(v.config, "algo").writeText("x")
        assertFailsWith<IOException> { EditarParejas.anadir(v, p, "# cabecera\n") { error("no se recarga") } }
        assertTrue(File(v.state, "fotos").isDirectory)
        assertEquals(confAntes, v.leerRcloneConf())
        assertTrue(configAntes.isNotEmpty())
    }

    @Test
    fun `anadir olvida una entrada suelta de una carpeta compartida con su nombre`() {
        val v = volumen(pareja("notas", "notas"))
        val fuera = File(temporal(), "Documents/fotos").apply { mkdirs() }
        Compartidas.escribir(v.compartidas, mapOf("fotos" to Compartidas.Entrada(fuera.path, Compartidas.Estado.LISTA)))
        val p = EditarParejas.planAnadir(v, raw(v), cat, "fotos", "Fotos")
        assertTrue(p.olvidarCompartida)
        EditarParejas.anadir(v, p, "") {}
        assertEquals(emptyMap(), Compartidas.leer(v.compartidas))
        assertNull(v.leerConfig().pairs.first { it.name == "fotos" }.carpetaExterna)
        assertFalse(fuera.path in v.leerRcloneConf())
    }

    // --- quitar, en un volumen ------------------------------------------------------

    @Test
    fun `quitar escribe el config sin ella, rehace el combine y borra sus filtros si se pide`() {
        val v = volumen(pareja("notas", "notas"), pareja("fotos", "fotos"))
        File(v.state, "fotos").mkdirs()
        File(v.filtros, "fotos.txt").writeText("x")
        File(v.filtros, "fotos.txt.md5").writeText("x")
        File(v.raiz, "fotos/a.jpg").apply { parentFile.mkdirs(); writeText("x") }
        val p = EditarParejas.planQuitar(v, raw(v), "fotos", limpiar = true)
        var recargas = 0
        EditarParejas.quitar(v, p, "# cabecera\n") { recargas++ }
        assertEquals(1, recargas)
        val config = v.leerConfig()
        assertEquals(listOf("notas"), config.names)
        assertTrue(v.leerRcloneConf().contains(config.seccionCombine(v.raiz.absolutePath)))
        assertFalse(File(v.filtros, "fotos.txt").exists())
        assertFalse(File(v.state, "fotos").exists())
        // Sus datos no se tocan.
        assertTrue(File(v.raiz, "fotos/a.jpg").isFile)
    }

    @Test
    fun `quitar sin limpiar deja su estado donde estaba`() {
        val v = volumen(pareja("notas", "notas"), pareja("fotos", "fotos"))
        File(v.state, "fotos").mkdirs()
        File(v.filtros, "fotos.txt").writeText("x")
        val p = EditarParejas.planQuitar(v, raw(v), "fotos", limpiar = false)
        assertFalse(p.limpiar)
        EditarParejas.quitar(v, p, "") {}
        assertTrue(File(v.state, "fotos").isDirectory)
        assertTrue(File(v.filtros, "fotos.txt").isFile)
    }

    @Test
    fun `quitar una compartida olvida su entrada, no toca su carpeta y aparta siempre su referencia`() {
        val (v, fuera) = conCompartida()
        File(fuera, "nota.md").writeText("hola")
        File(v.state, "vault").mkdirs()
        val p = EditarParejas.planQuitar(v, raw(v), "vault", limpiar = false)
        assertTrue(p.limpiar)
        assertNotNull(p.compartida)
        assertTrue(p.consecuencias.any { fuera.path in it })
        EditarParejas.quitar(v, p, "") {}
        assertEquals(emptyMap(), Compartidas.leer(v.compartidas))
        assertEquals(listOf("notas"), v.leerConfig().names)
        assertFalse(fuera.path in v.leerRcloneConf())
        assertFalse(File(v.state, "vault").exists())
        assertTrue(File(fuera, "nota.md").isFile)
    }

    @Test
    fun `no se quita una pareja a medio mover`() {
        val (v, fuera) = conCompartida()
        Compartidas.escribir(v.compartidas, mapOf("vault" to Compartidas.Entrada(fuera.path, Compartidas.Estado.ENTRANDO)))
        val error = assertFailsWith<ConfigError> { EditarParejas.planQuitar(v, raw(v), "vault", limpiar = true) }
        assertEquals(EditarParejas.aMedioMover("vault"), error.message)
    }

    @Test
    fun `no se quita la ultima, como en prdrive`() {
        val v = volumen(pareja("notas", "notas"))
        assertFailsWith<ConfigError> { EditarParejas.planQuitar(v, raw(v), "notas", limpiar = false) }
    }

    @Test
    fun `quitar y volver a anadir deja la pareja como al principio, salvo su referencia`() {
        val v = volumen(pareja("notas", "notas"))
        val anadida = EditarParejas.planAnadir(v, raw(v), cat, "fotos", "Fotos")
        EditarParejas.anadir(v, anadida, "") {}
        val conFotos = v.leerRcloneConf()
        EditarParejas.quitar(v, EditarParejas.planQuitar(v, raw(v), "fotos", limpiar = false), "") {}
        assertEquals(listOf("notas"), v.leerConfig().names)
        EditarParejas.anadir(v, EditarParejas.planAnadir(v, raw(v), cat, "fotos", "Fotos"), "") {}
        assertEquals(conFotos, v.leerRcloneConf())
        assertEquals(listOf("notas", "fotos"), v.leerConfig().names)
    }
}
