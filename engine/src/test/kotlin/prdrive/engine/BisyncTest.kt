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
                Bisync.fsPathRemote(entrada),
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
        for ((esperada, pareja) in Vectores.objetos(resueltas, "parejas").zip(config.pairs)) {
            assertEquals(
                Vectores.texto(esperada, "expected_prefix"),
                Bisync.expectedPrefix(pareja),
                "${pareja.name}: prefijo de los listados",
            )
            assertEquals(
                esperada["prefijo_comprobable"],
                Bisync.prefijoComprobable(pareja),
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
        val estado = Vectores.seccion("estado")
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
        val estado = Vectores.seccion("estado")
        val datos = Vectores.objeto(estado, "pareja")
        val p = construirPareja(
            mapOf(
                "name" to Vectores.texto(datos, "name"),
                "local" to Vectores.texto(datos, "local"),
                "remote_path" to Vectores.texto(datos, "remote_path"),
            ),
            mapOf("device_remote" to DEFAULT_DEVICE_REMOTE, "remote" to Vectores.texto(datos, "remote")),
        )
        assertEquals(Vectores.texto(estado, "prefijo"), Bisync.expectedPrefix(p))
        for (caso in Vectores.objetos(estado, "referencia_vacia")) {
            val nombre = Vectores.texto(caso, "caso")
            val raiz = temporal()
            val workdir = File(raiz, "state/${p.name}")
            montar(workdir, caso["ficheros"])
            val local = File(raiz, "volumen/${p.local}")
            if (caso["local_existe"] == true) local.mkdirs()
            assertEquals(
                caso["vacia"],
                Bisync.referenciaVacia(p, Bisync.pairState(workdir), workdir, local),
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
        for ((esperada, pareja) in Vectores.objetos(resueltas, "parejas").zip(config.pairs)) {
            assertEquals(
                Vectores.texto(esperada, "filters_content"),
                Bisync.filtersContent(pareja),
                "${pareja.name}: contenido de filters/",
            )
        }
    }

    // --- contra el disco ----------------------------------------------------

    private fun pareja(nombre: String = "notas"): Pareja = construirPareja(
        mapOf("name" to nombre, "local" to "sync-data/$nombre", "remote_path" to "/copia/$nombre"),
        mapOf("device_remote" to "disp", "remote" to "nas"),
    )

    @Test
    fun `sin workdir la pareja está fresh y pide resync`() {
        val state = temporal()
        val p = pareja()
        val estado = Bisync.pairState(File(state, p.name))
        assertEquals("fresh", estado.status)
        assertNull(estado.prefix)
        assertTrue(Bisync.resyncReasons(p, estado, Bisync.EstadoFiltros("ok", "")).isNotEmpty())
    }

    @Test
    fun `con los dos listados la pareja está ok y dice su prefijo`() {
        val state = temporal()
        val p = pareja()
        val workdir = File(state, p.name).apply { mkdirs() }
        val prefijo = Bisync.expectedPrefix(p)
        File(workdir, prefijo + Bisync.PATH1_SUFFIX).writeText("x")
        File(workdir, prefijo + Bisync.PATH2_SUFFIX).writeText("x")

        val estado = Bisync.pairState(workdir)
        assertEquals("ok", estado.status)
        assertEquals(prefijo, estado.prefix)
        assertEquals(emptyList(), Bisync.resyncReasons(p, estado, Bisync.EstadoFiltros("ok", "")))
    }

    @Test
    fun `los filtros sin md5 previo piden resync, y con el md5 bueno no`() {
        val p = pareja()
        val filtros = temporal()
        val ffile = Bisync.filtersFileFor(p, filtros)
        assertNotNull(ffile)

        assertEquals("new", Bisync.filtersState(ffile).status)

        // El md5 lo escribe bisync durante el --resync; aquí se imita para
        // comprobar la comparación, que es lo que evita un log rojo de rclone.
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(ffile.readBytes()).joinToString("") { "%02x".format(it) }
        File(ffile.path + ".md5").writeText(md5)
        assertEquals("ok", Bisync.filtersState(ffile).status)

        ffile.writeText(ffile.readText() + "- otro\n")
        assertEquals("changed", Bisync.filtersState(ffile).status)
    }

    @Test
    fun `una pareja sin fichero de filtros no pide resync por los filtros`() {
        val espejo = construirPareja(
            mapOf(
                "name" to "espejo", "local" to "sync-data/espejo",
                "remote_path" to "/copia/espejo", "mode" to "up-mirror",
            ),
            mapOf("device_remote" to "disp", "remote" to "nas"),
        )
        assertNull(Bisync.filtersFileFor(espejo, temporal()))
        assertEquals("ok", Bisync.filtersState(null).status)
        // Y una pareja que no es bisync nunca pide resync, sea cual sea el estado.
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
    fun `apartar el baseline deja la pareja fresh y no borra nada`() {
        val state = temporal()
        val workdir = File(state, "notas").apply { mkdirs() }
        File(workdir, "algo" + Bisync.PATH1_SUFFIX).writeText("x")

        val apartado = Bisync.shelveBaseline(state, "notas", "20260918_120000")
        assertNotNull(apartado)
        assertTrue(apartado.isDirectory, "el baseline apartado tiene que seguir ahí")
        assertEquals("fresh", Bisync.pairState(File(state, "notas")).status)
        // Y el directorio apartado es inerte: no lo ve quien mira el primer nivel.
        assertTrue(apartado.name.startsWith("notas.old-"))
    }

    @Test
    fun `apartar sin baseline no hace nada`() {
        assertNull(Bisync.shelveBaseline(temporal(), "no-existe", "20260918_120000"))
    }

    @Test
    fun `renombrar una pareja mueve su estado y el md5 con su fichero`() {
        // El prefijo NO depende del nombre, así que renombrar es gratis: lo que
        // cuelga del nombre son las rutas, y el .md5 tiene que viajar con su
        // fichero o bisync abortará diciendo que los filtros han cambiado.
        val state = temporal()
        val filtros = temporal()
        File(state, "viejo").apply { mkdirs() }
        File(state, "viejo/algo" + Bisync.PATH1_SUFFIX).writeText("x")
        File(filtros, "viejo.txt").writeText("filtros")
        File(filtros, "viejo.txt.md5").writeText("hash")

        val movimientos = Bisync.renamePairState(state, filtros, "viejo", "nuevo")

        assertEquals(3, movimientos.size, "se mueven el workdir, el .txt y el .md5")
        assertTrue(File(state, "nuevo/algo" + Bisync.PATH1_SUFFIX).isFile)
        assertTrue(File(filtros, "nuevo.txt").isFile)
        assertTrue(File(filtros, "nuevo.txt.md5").isFile)
        assertTrue(!File(state, "viejo").exists())
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
}
