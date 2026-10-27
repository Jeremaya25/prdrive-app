package prdrive.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BisyncTest.kt — Lo que replica a rclone, contra prdrive y contra ficheros de
 * verdad.
 *
 * Dos mitades. La primera compara con `vectores.json`: el nombre de sesión y el
 * prefijo esperado son lo que decide si un baseline sirve, y una diferencia de
 * un carácter con lo que calcula rclone significa resincronizar a ciegas. La
 * segunda monta `.lst` de mentira en un directorio temporal, porque
 * `fresh|ok|broken` se lee del disco y no hay forma de comprobarlo sin disco.
 */
class BisyncTest {

    private val temporales = ArrayList<File>()

    private fun temporal(): File =
        Files.createTempDirectory("prdrive-engine").toFile().also { temporales.add(it) }

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
    }

    // --- contra prdrive -----------------------------------------------------

    @Test
    fun `canonical_path y strip_hex_string son los de rclone`() {
        val canonical = Vectores.seccion("canonical")
        for (caso in Vectores.objetos(canonical, "canonical_path")) {
            val entrada = Vectores.texto(caso, "entrada")
            assertEquals(
                Vectores.texto(caso, "salida"),
                Bisync.canonicalPath(entrada),
                "canonicalPath(«$entrada»)",
            )
        }
        for (caso in Vectores.objetos(canonical, "strip_hex_string")) {
            val entrada = Vectores.texto(caso, "entrada")
            assertEquals(
                Vectores.texto(caso, "salida"),
                Bisync.stripHexString(entrada),
                "stripHexString(«$entrada»)",
            )
        }
        for (caso in Vectores.objetos(canonical, "fs_path_remote")) {
            val entrada = Vectores.texto(caso, "entrada")
            assertEquals(
                Vectores.texto(caso, "salida"),
                Bisync.fsPathRemote(entrada, emptyMap()),
                "fsPathRemote(«$entrada»)",
            )
        }
    }

    @Test
    fun `el nombre de sesión es el que va a buscar rclone`() {
        for (caso in Vectores.objetos(Vectores.seccion("canonical"), "session_name")) {
            val p1 = Vectores.texto(caso, "path1")
            val p2 = Vectores.texto(caso, "path2")
            assertEquals(
                Vectores.texto(caso, "salida"),
                Bisync.sessionName(p1, p2),
                "sessionName($p1, $p2)",
            )
        }
    }

    @Test
    fun `el prefijo esperado de cada pareja coincide con el del escritorio`() {
        // Es el test que más importa de todo el módulo: de este nombre depende
        // que bisync encuentre su baseline. Que salga igual en el móvil y en el
        // PC es lo que hace machine-independent al remote 'combine'.
        val resueltas = Vectores.seccion("resueltas")
        val config = parseConfig(Vectores.objeto(resueltas, "config"))
        assertEquals(Vectores.objetos(resueltas, "parejas").size, config.pairs.size, "número de parejas")
        for ((esperada, pareja) in Vectores.objetos(resueltas, "parejas").zip(config.pairs)) {
            assertEquals(
                Vectores.texto(esperada, "expected_prefix"),
                Bisync.expectedPrefix(pareja, emptyMap()),
                "${pareja.name}: prefijo de los listados",
            )
            assertEquals(
                esperada["prefijo_comprobable"],
                Bisync.prefijoComprobable(pareja, emptyMap()),
                "${pareja.name}: el prefijo se puede comprobar",
            )
        }
    }

    @Test
    fun `un alias o un remote local se nombran como los nombra rclone`() {
        // Un alias no tiene nombre propio: FsPath escribe el de su destino. Si
        // el teléfono calculara el del alias, el baseline que escribe rclone
        // nunca sería «el de esta pareja» y pediría resync en cada pasada.
        val extremos = Vectores.seccion("extremos")
        val tipos = Bisync.tiposDeRemote(Vectores.texto(extremos, "rclone_conf"))
        for (caso in Vectores.objetos(extremos, "fs_path_remote")) {
            val entrada = Vectores.texto(caso, "entrada")
            assertEquals(
                Vectores.texto(caso, "salida"),
                Bisync.fsPathRemote(entrada, tipos),
                "fsPathRemote(«$entrada»)",
            )
        }
        for (caso in Vectores.objetos(extremos, "resolver_extremo")) {
            val entrada = Vectores.texto(caso, "entrada")
            assertEquals(
                caso["salida"],
                Bisync.resolverExtremo(entrada, tipos),
                "resolverExtremo(«$entrada»)",
            )
        }
        for (caso in Vectores.objetos(extremos, "raiz_local")) {
            val entrada = Vectores.texto(caso, "entrada")
            assertEquals(Vectores.texto(caso, "salida"), Bisync.raizLocal(entrada), "raizLocal(«$entrada»)")
        }
    }

    @Test
    fun `el estado del baseline se lee igual que en el escritorio, detalle incluido`() {
        // El detalle es lo que lee el usuario cuando una pareja pide resync: se
        // monta en disco lo mismo que montó el generador y se compara letra a
        // letra.
        for (caso in Vectores.objetos(estado, "pair_state")) {
            val nombre = Vectores.texto(caso, "caso")
            val workdir = File(temporal(), "notas")
            montar(workdir, caso["ficheros"])
            val obtenido = Bisync.pairState(workdir)
            assertEquals(Vectores.texto(caso, "status"), obtenido.status, "«$nombre»: estado")
            assertEquals(Vectores.texto(caso, "detail"), obtenido.detail, "«$nombre»: detalle")
            assertEquals(caso["prefix"], obtenido.prefix, "«$nombre»: prefijo")
        }
    }

    @Test
    fun `una referencia vacía solo se rehace si es de esta pareja y hay carpeta local`() {
        // Un baseline vacío hace abortar toda pasada que no sea un resync; con
        // nada que proteger se rehace solo. Pero solo entonces: si uno de los
        // dos lados tenía algo, rehacerlo sería tragarse un borrado.
        val datos = Vectores.objeto(estado, "pareja")
        val p = construirPareja(
            mapOf(
                "name" to Vectores.texto(datos, "name"),
                "local" to Vectores.texto(datos, "local"),
                "remote_path" to Vectores.texto(datos, "remote_path"),
            ),
            mapOf("device_remote" to DEFAULT_DEVICE_REMOTE, "remote" to Vectores.texto(datos, "remote")),
        )
        assertEquals(Vectores.texto(estado, "prefijo"), Bisync.expectedPrefix(p, emptyMap()))
        for (caso in Vectores.objetos(estado, "referencia_vacia")) {
            val nombre = Vectores.texto(caso, "caso")
            val raiz = temporal()
            val workdir = File(raiz, "state/${p.name}")
            montar(workdir, caso["ficheros"])
            val local = File(raiz, "volumen/${p.local}")
            if (caso["local_existe"] == true) local.mkdirs()
            assertEquals(
                caso["vacia"],
                Bisync.referenciaVacia(p, Bisync.pairState(workdir), workdir, local, emptyMap()),
                "«$nombre»",
            )
        }
    }

    /** Crea los ficheros de un caso de los vectores; `null` es «sin workdir». */
    private fun montar(workdir: File, ficheros: Any?) {
        if (ficheros !is Map<*, *>) return
        workdir.mkdirs()
        for ((nombre, contenido) in ficheros) {
            File(workdir, nombre.toString()).writeText(contenido.toString())
        }
    }

    @Test
    fun `el fichero de filtros se escribe igual, línea por línea`() {
        val resueltas = Vectores.seccion("resueltas")
        val config = parseConfig(Vectores.objeto(resueltas, "config"))
        assertEquals(Vectores.objetos(resueltas, "parejas").size, config.pairs.size, "número de parejas")
        for ((esperada, pareja) in Vectores.objetos(resueltas, "parejas").zip(config.pairs)) {
            assertEquals(
                Vectores.texto(esperada, "filters_content"),
                Bisync.filtersContent(pareja),
                "${pareja.name}: contenido de filters/",
            )
        }
    }

    // --- contra el disco ----------------------------------------------------

    private val estado get() = Vectores.seccion("estado")

    /** La pareja con la que el generador montó los casos de disco. */
    private fun pareja(): Pareja {
        val datos = Vectores.objeto(estado, "pareja")
        return construirPareja(
            mapOf(
                "name" to Vectores.texto(datos, "name"),
                "local" to Vectores.texto(datos, "local"),
                "remote_path" to Vectores.texto(datos, "remote_path"),
            ),
            mapOf("device_remote" to DEFAULT_DEVICE_REMOTE, "remote" to Vectores.texto(datos, "remote")),
        )
    }

    private fun md5De(fichero: File): String =
        java.security.MessageDigest.getInstance("MD5").digest(fichero.readBytes())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun `el estado de los filtros se dice como en el escritorio`() {
        // El md5 lo escribe bisync durante el --resync; aquí se imita para
        // comprobar la comparación, que es lo que evita un log rojo de rclone.
        val p = pareja()
        val ffile = Bisync.filtersFileFor(p, temporal())
        assertNotNull(ffile)
        for (caso in Vectores.objetos(estado, "filtros")) {
            val nombre = Vectores.texto(caso, "caso")
            assertEquals(Vectores.texto(caso, "contenido"), ffile.readText(), "«$nombre»: contenido")
            val md5 = File(ffile.path + ".md5")
            val previo = caso["md5"]
            if (previo == null) md5.delete() else md5.writeText(previo.toString())
            val obtenido = Bisync.filtersState(ffile)
            assertEquals(Vectores.texto(caso, "status"), obtenido.status, "«$nombre»")
            assertEquals(Vectores.texto(caso, "detail"), obtenido.detail, "«$nombre»")
        }
        val sin = Vectores.objeto(estado, "sin_fichero")
        assertEquals(
            Bisync.EstadoFiltros(Vectores.texto(sin, "status"), Vectores.texto(sin, "detail")),
            Bisync.filtersState(null),
        )
    }

    @Test
    fun `por qué pide resync una pareja se dice como en el escritorio`() {
        val p = pareja()
        for (caso in Vectores.objetos(estado, "resync_reasons")) {
            val nombre = Vectores.texto(caso, "caso")
            val raiz = temporal()
            val workdir = File(raiz, "state/${p.name}")
            val ficheros = Vectores.objeto(caso, "ficheros")
            if (ficheros.isNotEmpty()) montar(workdir, ficheros)
            val ffile = Bisync.filtersFileFor(p, File(raiz, "filters"))!!
            if (caso["md5"] == true) File(ffile.path + ".md5").writeText(md5De(ffile))
            assertEquals(
                Vectores.cadenas(caso, "razones"),
                Bisync.resyncReasons(p, Bisync.pairState(workdir), Bisync.filtersState(ffile)),
                "«$nombre»",
            )
        }
    }

    @Test
    fun `una pareja que no es bisync no tiene fichero de filtros ni pide resync`() {
        val espejo = construirPareja(
            mapOf(
                "name" to "espejo", "local" to "sync-data/espejo",
                "remote_path" to "/copia/espejo", "mode" to "up-mirror",
            ),
            mapOf("device_remote" to "disp", "remote" to "nas"),
        )
        assertNull(Bisync.filtersFileFor(espejo, temporal()))
        assertEquals(
            emptyList(),
            Bisync.resyncReasons(
                espejo,
                Bisync.EstadoPareja("broken", "lo que sea", null),
                Bisync.EstadoFiltros("changed", "lo que sea"),
            ),
        )
    }

    @Test
    fun `apartar el baseline lo renombra como el escritorio y no borra nada`() {
        val f = Vectores.lista(estado, "fecha_apartado").map { (it as Long).toInt() }
        val sello = Bisync.selloApartado(java.time.LocalDateTime.of(f[0], f[1], f[2], f[3], f[4], f[5]))
        assertEquals(Vectores.texto(estado, "sello_apartado"), sello)

        val state = temporal()
        val esperado = Vectores.lista(estado, "apartar")
        assertEquals(esperado[0], Bisync.shelveBaseline(state, "notas", sello)?.name, "sin baseline")
        File(state, "notas").mkdirs()
        File(state, "notas/algo" + Bisync.PATH1_SUFFIX).writeText("x")
        val primera = Bisync.shelveBaseline(state, "notas", sello)
        assertEquals(esperado[1], primera?.name)
        assertTrue(File(primera, "algo" + Bisync.PATH1_SUFFIX).isFile, "lo apartado sigue ahí")
        File(state, "notas").mkdirs()
        assertEquals(esperado[2], Bisync.shelveBaseline(state, "notas", sello)?.name, "el segundo no pisa al primero")
    }

    @Test
    fun `renombrar una pareja mueve lo mismo que en el escritorio`() {
        // El prefijo NO depende del nombre, así que renombrar es gratis: lo que
        // cuelga del nombre son las rutas, y el .md5 tiene que viajar con su
        // fichero o bisync abortará diciendo que los filtros han cambiado.
        val raiz = temporal()
        val state = File(raiz, "state")
        val filtros = File(raiz, "filters").apply { mkdirs() }
        File(state, "viejo").mkdirs()
        File(filtros, "viejo.txt").writeText("x")
        File(filtros, "viejo.txt.md5").writeText("y")
        File(state, "ocupado").mkdirs()

        val movidos = Bisync.renamePairState(state, filtros, "viejo", "nuevo")
        assertEquals(
            Vectores.lista(estado, "renombrar"),
            movidos.map { (o, d) -> listOf(o.parentFile.name + "/" + o.name, d.parentFile.name + "/" + d.name) },
        )
        // Si el destino del estado ya existe, el estado se queda donde está.
        val aOcupado = Bisync.renamePairState(state, filtros, "nuevo", "ocupado")
        assertEquals(
            Vectores.lista(estado, "renombrar_a_uno_que_existe"),
            aOcupado.map { (o, d) -> listOf(o.name, d.name) },
        )
    }

    @Test
    fun `la última pasada es la fecha del listado más nuevo, y nada fuera de bisync`() {
        val p = pareja()
        val workdir = File(temporal(), p.name).apply { mkdirs() }
        assertNull(Bisync.lastRun(p, workdir), "sin listados no hay fecha que enseñar")

        val listado = File(workdir, "algo" + Bisync.PATH1_SUFFIX)
        listado.writeText("x")
        val cuando = Bisync.lastRun(p, workdir)
        assertNotNull(cuando)
        assertEquals(listado.lastModified(), cuando)

        val espejo = construirPareja(
            mapOf(
                "name" to "espejo", "local" to "a", "remote_path" to "/a", "mode" to "up-mirror",
            ),
            mapOf("device_remote" to "disp"),
        )
        assertNull(Bisync.lastRun(espejo, workdir), "un copy no deja estado: no hay fecha")
    }

    @Test
    fun `apartar los listados los deja en -err como markFailed y la pareja pide resync`() {
        val workdir = File(temporal(), "vault").apply { mkdirs() }
        val path1 = File(workdir, "a" + Bisync.PATH1_SUFFIX).apply { writeText("nuevo 1") }
        val path2 = File(workdir, "a" + Bisync.PATH2_SUFFIX).apply { writeText("nuevo 2") }
        // Un `-err` de un aborto anterior se pisa; lo que no es un listado, no se toca.
        File(path1.path + "-err").writeText("viejo")
        val otro = File(workdir, "a.path1.lst-new").apply { writeText("otro") }

        val apartados = Bisync.apartarListados(workdir)

        assertEquals(listOf(File(path1.path + "-err"), File(path2.path + "-err")), apartados)
        assertEquals("nuevo 1", apartados[0].readText())
        assertEquals("nuevo 2", apartados[1].readText())
        assertTrue(!path1.exists() && !path2.exists())
        assertEquals("otro", otro.readText())
        assertTrue(apartados.all { it.name.endsWith(Bisync.ERR_SUFFIX) })
        assertEquals(false, Bisync.pairState(workdir).hasBaseline)
        assertEquals(emptyList(), Bisync.apartarListados(File(temporal(), "nunca")), "sin workdir, nada")
    }
}
