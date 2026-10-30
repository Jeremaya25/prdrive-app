package prdrive.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MudanzaTest.kt — Mover una pareja a una carpeta compartida y volver: el orden
 * de lo que se escribe, cada parada y lo que deja un corte.
 *
 * Con un rclone de mentira ([RcloneFalso]), que no escribe listados: los estados
 * «ya movida» se montan a mano (la entrada, el `combine` y el baseline). Las
 * pasadas llevan `accesoCompartido = { null }`: sin él, la de una pareja
 * compartida aborta, que es lo que tiene que hacer.
 */
class MudanzaTest {

    private val temporales = ArrayList<File>()

    private fun temporal(): File =
        Files.createTempDirectory("prdrive-mudanza").toFile().also { temporales.add(it) }

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
    }

    /**
     * Un volumen con la pareja `vault` (`local = "vault"`, más lo que diga
     * [pareja]), sin entrada en `compartidas.json`: con baseline y un fichero en
     * su carpeta privada.
     */
    private fun volumen(pareja: Map<String, Any?> = emptyMap()): kotlin.Pair<Volumen, Pareja> {
        val v = Volumen(File(temporal(), "volumen"))
        val raw = mapOf(
            "defaults" to mapOf("device_remote" to "disp", "remote" to "nas"),
            "pair" to listOf(mapOf("name" to "vault", "local" to "vault", "remote_path" to "/copia/vault") + pareja),
        )
        v.escribirConfig(raw, "")
        val config = v.leerConfig()
        v.rcloneConf.writeText("[nas]\ntype = sftp\n\n" + config.seccionCombine(v.raiz.absolutePath))
        val p = config.pairs.single()
        File(v.raiz, "vault").mkdirs()
        File(v.raiz, "vault/nota.md").writeText("hola")
        conBaseline(v, p, listOf("nota.md"))
        return v to p
    }

    /** Una carpeta de destino vacía, ya creada, en otro temporal. */
    private fun destino(): File = File(temporal(), "vault").apply { mkdirs() }

    /**
     * La pareja ya movida a [destino] (entrada `LISTA`, el `combine` al día y su
     * baseline), con su carpeta privada borrada y dos ficheros en el destino.
     */
    private fun desdeLista(v: Volumen, destino: File): Pareja {
        File(v.raiz, "vault").deleteRecursively()
        File(destino, "nota.md").writeText("hola")
        File(destino, ".obsidian").mkdirs()
        File(destino, ".obsidian/app.json").writeText("{}")
        Compartidas.escribir(
            v.compartidas,
            mapOf("vault" to Compartidas.Entrada(destino.path, Compartidas.Estado.LISTA)),
        )
        v.actualizarCombine(v.leerConfig())
        val p = v.leerConfig().pairs.single()
        conBaseline(v, p, listOf("nota.md"))
        return p
    }

    private var recargas = 0

    private fun entorno(
        rclone: Pasada.Rclone,
        probarFechas: (File) -> String? = { null },
        recargar: () -> Unit = { recargas++ },
    ) = Mudanza.Entorno(
        rclone, Oraculo.opciones, Pasada.Contexto(accesoCompartido = { null }), probarFechas, recargar,
    )

    /** Lo que deja rclone al apartar los listados (`markFailed`), hecho a mano. */
    private fun apartarAMano(v: Volumen, p: Pareja) {
        for (lst in v.workdir(p).listFiles()!!) assertTrue(lst.renameTo(File(lst.path + "-err")))
    }

    /** Si el workdir de la pareja tiene listados sin apartar. */
    private fun hayListados(v: Volumen, p: Pareja): Boolean =
        workdir(v, p).any { it.endsWith(Bisync.PATH1_SUFFIX) || it.endsWith(Bisync.PATH2_SUFFIX) }

    /** Hace que apartar los listados falle: su `-err` es una carpeta con algo, que no se borra. */
    private fun apartarFalla(v: Volumen, p: Pareja) {
        val lst = v.workdir(p).listFiles()!!.first { it.name.endsWith(Bisync.PATH1_SUFFIX) }
        File(lst.path + "-err", "dentro").apply { parentFile.mkdirs(); writeText("no me borres") }
    }

    /** Los nombres del workdir de la pareja, ordenados. */
    private fun workdir(v: Volumen, p: Pareja): List<String> = v.workdir(p).list().orEmpty().sorted()

    /** Lo que dice `[disp]` sin ninguna carpeta compartida: la pareja en el volumen. */
    private fun combineSinEntradas(v: Volumen): String =
        v.leerConfig().conCompartidas(emptyMap(), v.raiz).seccionCombine(v.raiz.absolutePath)

    /** Un rclone de mentira que mira algo antes de cada llamada. */
    private class Mirando(val dentro: RcloneFalso, val mirar: () -> Unit) : Pasada.Rclone by dentro {
        override fun ejecutar(llamada: Pasada.Llamada, alProgreso: (Map<String, Any?>) -> Unit): Pasada.Final {
            mirar()
            return dentro.ejecutar(llamada, alProgreso)
        }
    }

    // --- los pasos -------------------------------------------------------

    @Test
    fun `mover y volver dicen sus pasos en orden, para que la ficha los marque`() {
        val (v, _) = volumen()
        val destino = destino()
        val pasos = ArrayList<Mudanza.Paso>()
        val e = entorno(RcloneFalso()).copy(alPaso = { pasos.add(it) })

        assertTrue(Mudanza.entrar(v, "vault", destino, e).ok)
        assertEquals(
            listOf(
                Mudanza.Paso.FECHAS, Mudanza.Paso.PASADA_NORMAL, Mudanza.Paso.APARTAR,
                Mudanza.Paso.BAJAR, Mudanza.Paso.LLEVAR, Mudanza.Paso.BORRAR,
            ),
            pasos,
        )
        assertEquals(pasos, Mudanza.PASOS_ENTRAR, "la lista que enseña la ficha es la de verdad")

        pasos.clear()
        assertTrue(Mudanza.salir(v, "vault", true, e).ok)
        // El rclone de mentira no deja referencia, y sin ella «Volver» se salta su pasada normal.
        assertEquals(listOf(Mudanza.Paso.APARTAR, Mudanza.Paso.BAJAR), pasos)
        assertEquals(Mudanza.PASOS_SALIR.filter { it in pasos }, pasos, "en el orden de la lista que enseña la ficha")
    }

    // --- mover -------------------------------------------------------------

    @Test
    fun `mover hace la pasada, aparta los listados, redirige y solo tras el resync borra la copia privada`() {
        val (v, p) = volumen()
        val destino = destino()
        val privada = Mudanza.carpetaPrivada(v, p)
        val falso = RcloneFalso()
        // Lo que hay justo antes de cada llamada a rclone.
        val vistos = ArrayList<List<Any?>>()
        val rclone = Mirando(falso) {
            vistos.add(
                listOf(
                    privada.exists(),
                    hayListados(v, p),
                    upstream("vault", destino.path) in v.leerRcloneConf(),
                    Compartidas.leer(v.compartidas)["vault"]?.estado,
                    recargas,
                ),
            )
        }
        // Y lo que hay al recargar rclone: listados, entrada y combine.
        val alRecargar = ArrayList<List<Any?>>()
        val recargar = {
            recargas++
            alRecargar.add(
                listOf(
                    hayListados(v, p),
                    Compartidas.leer(v.compartidas)["vault"]?.estado,
                    upstream("vault", destino.path) in v.leerRcloneConf(),
                ),
            )
        }

        val hecho = Mudanza.entrar(v, "vault", destino, entorno(rclone, recargar = { recargar() }))

        assertTrue(hecho.ok, hecho.motivo)
        assertEquals(2, falso.llamadas.size)
        assertNotEquals(true, falso.llamadas[0].parametros["resync"], "la primera es una pasada normal")
        assertNull(falso.llamadas[0].parametros["resyncMode"])
        assertEquals(true, falso.llamadas[1].parametros["resync"])
        assertEquals(Mudanza.RESYNC_MODE, falso.llamadas[1].parametros["resyncMode"])
        // La pasada normal, contra la copia privada y con sus listados.
        assertEquals(listOf<Any?>(true, true, false, null, 0), vistos[0])
        // El --resync: los listados apartados, la entrada y el combine puestos y
        // rclone recargado, y la copia privada todavía ahí.
        assertEquals(true, vistos[1][0], "la copia privada sigue durante el --resync")
        assertEquals(false, vistos[1][1], "los listados están apartados")
        assertEquals(true, vistos[1][2], "el rclone.conf ya apunta a la carpeta compartida")
        assertEquals(Compartidas.Estado.ENTRANDO, vistos[1][3])
        assertTrue((vistos[1][4] as Int) > 0, "rclone se recarga antes del --resync")
        // La primera recarga es la de `entrar`, tras escribir `[disp]`: para
        // entonces los listados ya están apartados y la entrada, apuntada.
        assertEquals(listOf<Any?>(false, Compartidas.Estado.ENTRANDO, true), alRecargar.first())

        assertTrue(upstream("vault", destino.path) in v.leerRcloneConf())
        assertEquals(Compartidas.Estado.LISTA, Compartidas.leer(v.compartidas)["vault"]?.estado)
        assertEquals(destino.path, Compartidas.leer(v.compartidas)["vault"]?.ruta)
        assertFalse(privada.exists(), "la copia privada se borra tras el --resync")
        assertTrue(destino.isDirectory)
        assertEquals(listOf(0, 0), hecho.pasadas.map { it.codigo })
    }

    @Test
    fun `una carpeta con algo no se usa y no se toca nada`() {
        val (v, p) = volumen()
        val destino = destino()
        File(destino, "otra.md").writeText("de antes")
        val antes = workdir(v, p)
        val rclone = RcloneFalso()

        val hecho = Mudanza.entrar(v, "vault", destino, entorno(rclone))

        assertNotNull(hecho.motivo)
        assertTrue(rclone.llamadas.isEmpty(), "no se llama a rclone")
        assertFalse(v.compartidas.exists())
        assertEquals(antes, workdir(v, p), "los listados, como estaban")
        assertTrue(File(destino, "otra.md").isFile, "la carpeta no se toca")
        assertTrue(File(Mudanza.carpetaPrivada(v, p), "nota.md").isFile)
        assertEquals(0, recargas)
    }

    @Test
    fun `si las fechas no valen se para y quita la carpeta que creo`() {
        val (v, _) = volumen()
        val destino = File(temporal(), "vault")
        assertFalse(destino.exists())
        val rclone = RcloneFalso()
        var probada: File? = null

        val hecho = Mudanza.entrar(
            v, "vault", destino,
            entorno(rclone, probarFechas = { probada = it; assertTrue(it.isDirectory); "la hora se pierde" }),
        )

        assertNotNull(hecho.motivo)
        assertTrue("la hora se pierde" in hecho.motivo, "se dice lo que dijo la prueba")
        assertEquals(destino, probada, "se prueba en la carpeta, ya creada")
        assertFalse(destino.exists(), "la carpeta que se creó para probar se quita")
        assertTrue(rclone.llamadas.isEmpty())
        assertFalse(v.compartidas.exists())
    }

    @Test
    fun `si la pasada de antes falla no se redirige nada`() {
        val (v, p) = volumen()
        val destino = destino()
        val antes = workdir(v, p)
        val rcloneAntes = v.leerRcloneConf()
        val rclone = RcloneFalso(Pasada.Final(false, "se cortó", "ERROR : se cortó\n"))

        val hecho = Mudanza.entrar(v, "vault", destino, entorno(rclone))

        assertNotNull(hecho.motivo)
        assertEquals(1, rclone.llamadas.size)
        assertEquals(listOf(Pasada.FALLO), hecho.pasadas.map { it.codigo })
        assertFalse(v.compartidas.exists())
        assertEquals(antes, workdir(v, p), "los listados, intactos")
        assertEquals(rcloneAntes, v.leerRcloneConf())
        assertTrue(destino.isDirectory, "una carpeta que ya estaba no se quita")
        assertEquals(0, recargas)
    }

    @Test
    fun `si el resync falla queda a medio mover con la copia privada y los listados apartados`() {
        val (v, p) = volumen()
        val destino = destino()
        val rclone = RcloneFalso(
            Pasada.Final(false, "se cortó", "ERROR : se cortó\n"),
            Pasada.Final(true, "", "INFO : Bisync successful\n"),
        )

        val hecho = Mudanza.entrar(v, "vault", destino, entorno(rclone))

        assertNotNull(hecho.motivo)
        assertEquals(2, rclone.llamadas.size)
        assertEquals(listOf(0, Pasada.FALLO), hecho.pasadas.map { it.codigo })
        assertEquals(Compartidas.Estado.ENTRANDO, Compartidas.leer(v.compartidas)["vault"]?.estado)
        assertTrue(File(Mudanza.carpetaPrivada(v, p), "nota.md").isFile, "la copia privada sigue")
        val nombres = workdir(v, p)
        assertTrue(nombres.isNotEmpty())
        assertTrue(nombres.all { it.endsWith(Bisync.ERR_SUFFIX) }, "en el workdir solo hay -err: $nombres")
        assertTrue(upstream("vault", destino.path) in v.leerRcloneConf())
    }

    @Test
    fun `un corte entre la entrada y el combine deja una pasada que aborta, y terminar lo arregla`() {
        val (v, p) = volumen()
        val destino = destino()
        // Lo que deja un corte justo tras apuntar la entrada: los listados
        // apartados, la entrada `ENTRANDO` y el `rclone.conf` sin la redirección.
        apartarAMano(v, p)
        Compartidas.escribir(
            v.compartidas,
            mapOf("vault" to Compartidas.Entrada(destino.path, Compartidas.Estado.ENTRANDO)),
        )
        val movida = v.leerConfig().pairs.single()
        assertNotNull(movida.carpetaExterna)
        val pasada = RcloneFalso()

        val r = Pasada.pasar(v, movida, pasada, Oraculo.opciones, Pasada.Contexto(accesoCompartido = { null }))

        assertEquals(Pasada.ABORTO, r.codigo)
        assertEquals(Pasada.COMBINE_VIEJO, r.explicacion)
        assertTrue(pasada.llamadas.isEmpty(), "no se llama a rclone")

        val rclone = RcloneFalso()
        val hecho = Mudanza.terminar(v, "vault", entorno(rclone))

        assertTrue(hecho.ok, hecho.motivo)
        assertEquals(1, rclone.llamadas.size)
        assertEquals(true, rclone.llamadas.single().parametros["resync"])
        assertEquals(Mudanza.RESYNC_MODE, rclone.llamadas.single().parametros["resyncMode"])
        assertTrue(upstream("vault", destino.path) in v.leerRcloneConf())
        assertEquals(Compartidas.Estado.LISTA, Compartidas.leer(v.compartidas)["vault"]?.estado)
        assertFalse(Mudanza.carpetaPrivada(v, p).exists())
        assertTrue(recargas > 0)
    }

    @Test
    fun `si la carpeta ya no esta vacia al hacer el resync gana el remoto`() {
        val (v, _) = volumen()
        val destino = destino()
        val rclone = RcloneFalso()
        // Obsidian abre la carpeta antes de tiempo y deja su `.obsidian/`.
        val obsidian = File(destino, ".obsidian/app.json")
        val recargar = {
            obsidian.parentFile.mkdirs()
            obsidian.writeText("{}")
            recargas++
        }

        val hecho = Mudanza.entrar(v, "vault", destino, entorno(rclone, recargar = { recargar() }))

        assertTrue(hecho.ok, hecho.motivo)
        assertEquals(true, rclone.llamadas[1].parametros["resync"])
        assertEquals(Mudanza.RESYNC_MODE, rclone.llamadas[1].parametros["resyncMode"])
        assertEquals(Compartidas.Estado.LISTA, Compartidas.leer(v.compartidas)["vault"]?.estado)
        assertTrue(obsidian.isFile, "lo de la carpeta compartida no se toca")
    }

    @Test
    fun `terminar lleva a la carpeta compartida lo que solo tenia la copia privada, con su fecha`() {
        val (v, p) = volumen(mapOf("exclude" to listOf("*.tmp")))
        val destino = destino()
        val privada = Mudanza.carpetaPrivada(v, p)
        val fecha = 1_600_000_000_000L
        File(privada, "nota.md").writeText("de la copia")
        // Lo que los filtros dejan fuera y las versiones de Path1: ni en el remoto ni en la carpeta.
        val borrador = File(privada, "borrador.tmp").apply { writeText("sin sincronizar"); setLastModified(fecha) }
        val version = File(privada, "$VERSIONS_DIR/x").apply {
            parentFile.mkdirs()
            writeText("una versión")
            setLastModified(fecha + 2000)
        }
        val rclone = RcloneFalso()
        // Lo que baja el --resync: la nota, que también tiene la copia. Y lo que
        // dejaría una vez anterior cortada a media copia: su temporal.
        val aMedias = File(destino, "borrador.tmp" + Mudanza.SUFIJO_LLEVANDO)
        val recargar = {
            File(destino, "nota.md").writeText("del remoto")
            aMedias.writeText("sin sin")
        }

        val hecho = Mudanza.entrar(v, "vault", destino, entorno(rclone, recargar = { recargar() }))

        assertTrue(hecho.ok, hecho.motivo)
        assertFalse(privada.exists())
        assertEquals("del remoto", File(destino, "nota.md").readText(), "lo que ya tiene la carpeta no se toca")
        val llevado = File(destino, "borrador.tmp")
        assertEquals("sin sincronizar", llevado.readText())
        assertEquals(fecha, llevado.lastModified())
        val llevada = File(destino, "$VERSIONS_DIR/x")
        assertEquals("una versión", llevada.readText())
        assertEquals(fecha + 2000, llevada.lastModified())
        assertFalse(borrador.exists() || version.exists())
        assertFalse(aMedias.exists(), "el temporal de un corte anterior se rehace")
    }

    @Test
    fun `si algo no se puede llevar a la carpeta compartida no se borra la copia privada`() {
        val (v, p) = volumen()
        val destino = destino()
        val privada = Mudanza.carpetaPrivada(v, p)
        File(privada, "$VERSIONS_DIR/x").apply { parentFile.mkdirs(); writeText("una versión") }
        val rclone = RcloneFalso()
        // En la carpeta, un fichero donde la copia tiene una carpeta.
        val recargar = { File(destino, VERSIONS_DIR).writeText("no soy una carpeta") }

        val hecho = Mudanza.entrar(v, "vault", destino, entorno(rclone, recargar = { recargar() }))

        assertNotNull(hecho.motivo)
        assertEquals(2, rclone.llamadas.size, "el --resync ha ido bien")
        assertEquals(Compartidas.Estado.ENTRANDO, Compartidas.leer(v.compartidas)["vault"]?.estado)
        assertTrue(File(privada, "$VERSIONS_DIR/x").isFile, "la copia privada no se borra")
        assertEquals("no soy una carpeta", File(destino, VERSIONS_DIR).readText())
    }

    @Test
    fun `si no se pueden apartar los listados no se apunta ni se redirige nada`() {
        val (v, p) = volumen()
        val destino = destino()
        apartarFalla(v, p)
        val rcloneAntes = v.leerRcloneConf()
        val rclone = RcloneFalso()

        val hecho = Mudanza.entrar(v, "vault", destino, entorno(rclone))

        assertNotNull(hecho.motivo)
        assertEquals(1, rclone.llamadas.size, "solo la pasada de antes")
        assertNotEquals(true, rclone.llamadas.single().parametros["resync"])
        assertFalse(v.compartidas.exists(), "no se escribe compartidas.json")
        assertEquals(rcloneAntes, v.leerRcloneConf())
        assertEquals(0, recargas)
    }

    @Test
    fun `si no se puede apuntar la entrada el rclone_conf no cambia`() {
        val (v, p) = volumen()
        val destino = destino()
        // El temporal de la escritura atómica de `compartidas.json` (`Store.writeText`),
        // ocupado por una carpeta con algo: la escritura falla.
        File(v.programa, v.compartidas.nameWithoutExtension + ".tmp/dentro")
            .apply { parentFile.mkdirs(); writeText("x") }
        val rcloneAntes = v.leerRcloneConf()
        val rclone = RcloneFalso()

        val hecho = Mudanza.entrar(v, "vault", destino, entorno(rclone))

        assertNotNull(hecho.motivo)
        assertFalse(v.compartidas.exists())
        assertEquals(rcloneAntes, v.leerRcloneConf(), "el rclone.conf va después de la entrada")
        assertEquals(1, rclone.llamadas.size, "ningún --resync")
        assertFalse(hayListados(v, p), "los listados ya estaban apartados")
        assertEquals(0, recargas)
    }

    @Test
    fun `terminar quita los temporales de una vez anterior antes de pasar`() {
        val (v, p) = volumen()
        val destino = destino()
        // Cortado tras el --resync, a media copia en el paso de llevar: la entrada
        // `ENTRANDO`, el combine al día, y temporales en la carpeta.
        apartarAMano(v, p)
        Compartidas.escribir(
            v.compartidas,
            mapOf("vault" to Compartidas.Entrada(destino.path, Compartidas.Estado.ENTRANDO)),
        )
        v.actualizarCombine(v.leerConfig())
        val sueltos = listOf(
            File(destino, "nota.md" + Mudanza.SUFIJO_LLEVANDO),
            File(destino, "sub/otra.md" + Mudanza.SUFIJO_LLEVANDO),
        )
        sueltos.forEach { it.parentFile.mkdirs(); it.writeText("a medias") }
        val falso = RcloneFalso()
        val vistos = ArrayList<Boolean>()
        val rclone = Mirando(falso) { vistos.add(sueltos.any { it.exists() }) }

        val hecho = Mudanza.terminar(v, "vault", entorno(rclone))

        assertTrue(hecho.ok, hecho.motivo)
        assertEquals(listOf(false), vistos, "ningún temporal llega a una llamada a rclone")
        assertTrue(sueltos.none { it.exists() })
        assertEquals("hola", File(destino, "nota.md").readText(), "y lo que faltaba se lleva entero")
    }

    @Test
    fun `una pareja que ya tiene entrada no se vuelve a mover`() {
        val (v, _) = volumen()
        val destino = destino()
        desdeLista(v, destino)
        val rclone = RcloneFalso()

        val hecho = Mudanza.entrar(v, "vault", destino(), entorno(rclone))

        assertNotNull(hecho.motivo)
        assertTrue(rclone.llamadas.isEmpty())
        assertEquals(destino.path, Compartidas.leer(v.compartidas)["vault"]?.ruta)
    }

    // --- volver ------------------------------------------------------------

    @Test
    fun `volver sin permiso no pasa antes y no toca la carpeta compartida`() {
        val (v, p) = volumen()
        val destino = destino()
        desdeLista(v, destino)
        val rclone = RcloneFalso()

        val hecho = Mudanza.salir(v, "vault", conPasada = false, entorno(rclone))

        assertTrue(hecho.ok, hecho.motivo)
        assertEquals(1, rclone.llamadas.size)
        assertEquals(true, rclone.llamadas.single().parametros["resync"])
        assertEquals(Mudanza.RESYNC_MODE, rclone.llamadas.single().parametros["resyncMode"])
        assertFalse(upstream("vault", destino.path) in v.leerRcloneConf())
        assertTrue(combineSinEntradas(v) in v.leerRcloneConf(), "la pareja vuelve a apuntar al volumen")
        assertNull(Compartidas.leer(v.compartidas)["vault"])
        assertTrue(File(destino, "nota.md").isFile, "la carpeta compartida se queda como está")
        assertTrue(File(destino, ".obsidian/app.json").isFile)
        assertTrue(Mudanza.carpetaPrivada(v, p).isDirectory, "el --resync baja a la carpeta privada")
        assertTrue(recargas > 0)
    }

    @Test
    fun `volver escribe el combine antes de quitar la entrada`() {
        val (v, p) = volumen()
        val destino = destino()
        desdeLista(v, destino)
        val rclone = RcloneFalso()
        var entradaAlRecargar: Compartidas.Entrada? = null
        var combineAlRecargar = ""
        var listadosAlRecargar = true

        val hecho = Mudanza.salir(
            v, "vault", conPasada = true,
            entorno(rclone, recargar = {
                entradaAlRecargar = Compartidas.leer(v.compartidas)["vault"]
                combineAlRecargar = v.leerRcloneConf()
                listadosAlRecargar = hayListados(v, p)
            }),
        )

        assertTrue(hecho.ok, hecho.motivo)
        assertNotNull(entradaAlRecargar, "al recargar con el combine nuevo, la entrada sigue")
        assertFalse(listadosAlRecargar, "los listados se apartan antes de tocar el combine")
        assertFalse(upstream("vault", destino.path) in combineAlRecargar)
        assertEquals(2, rclone.llamadas.size)
        assertNotEquals(true, rclone.llamadas[0].parametros["resync"], "con permiso, una pasada normal antes")
        assertEquals(true, rclone.llamadas[1].parametros["resync"])
        assertNull(Compartidas.leer(v.compartidas)["vault"])
    }

    @Test
    fun `volver con permiso tras un corte entre el combine y la entrada acaba sin pasar antes`() {
        val (v, p) = volumen()
        val destino = destino()
        desdeLista(v, destino)
        // Un «Volver» cortado tras reescribir `[disp]`: los listados apartados, el
        // combine en el volumen y la entrada todavía `LISTA`.
        apartarAMano(v, p)
        v.actualizarCombine(v.leerConfig(), emptyMap())
        val rclone = RcloneFalso()

        val hecho = Mudanza.salir(v, "vault", conPasada = true, entorno(rclone))

        assertTrue(hecho.ok, hecho.motivo)
        assertEquals(1, rclone.llamadas.size, "no se intenta la pasada normal")
        assertEquals(true, rclone.llamadas.single().parametros["resync"])
        assertEquals(Mudanza.RESYNC_MODE, rclone.llamadas.single().parametros["resyncMode"])
        assertNull(Compartidas.leer(v.compartidas)["vault"])
        assertTrue(combineSinEntradas(v) in v.leerRcloneConf())
        assertTrue(File(destino, "nota.md").isFile)
    }

    @Test
    fun `volver con permiso tras un corte justo despues de apartar los listados acaba sin pasar antes`() {
        val (v, p) = volumen()
        val destino = destino()
        desdeLista(v, destino)
        // Cortado tras apartar los listados: el combine aún apunta a la carpeta.
        apartarAMano(v, p)
        assertTrue(upstream("vault", destino.path) in v.leerRcloneConf())
        val rclone = RcloneFalso()

        val hecho = Mudanza.salir(v, "vault", conPasada = true, entorno(rclone))

        assertTrue(hecho.ok, hecho.motivo)
        assertEquals(1, rclone.llamadas.size, "no se intenta la pasada normal")
        assertEquals(true, rclone.llamadas.single().parametros["resync"])
        assertEquals(Mudanza.RESYNC_MODE, rclone.llamadas.single().parametros["resyncMode"])
        assertNull(Compartidas.leer(v.compartidas)["vault"])
        assertTrue(combineSinEntradas(v) in v.leerRcloneConf())
    }

    @Test
    fun `volver sin poder apartar los listados no toca el combine ni la entrada`() {
        val (v, p) = volumen()
        val destino = destino()
        desdeLista(v, destino)
        apartarFalla(v, p)
        val rcloneAntes = v.leerRcloneConf()
        val rclone = RcloneFalso()

        val hecho = Mudanza.salir(v, "vault", conPasada = false, entorno(rclone))

        assertNotNull(hecho.motivo)
        assertTrue(rclone.llamadas.isEmpty())
        assertEquals(rcloneAntes, v.leerRcloneConf())
        assertEquals(Compartidas.Estado.LISTA, Compartidas.leer(v.compartidas)["vault"]?.estado)
        assertEquals(0, recargas)
    }

    @Test
    fun `volver con algo en la carpeta privada se para`() {
        val (v, p) = volumen()
        val destino = destino()
        desdeLista(v, destino)
        val privada = Mudanza.carpetaPrivada(v, p).apply { mkdirs() }
        File(privada, "suelta.md").writeText("de nadie")
        val rclone = RcloneFalso()

        val hecho = Mudanza.salir(v, "vault", conPasada = false, entorno(rclone))

        assertNotNull(hecho.motivo)
        assertTrue(rclone.llamadas.isEmpty())
        assertEquals(Compartidas.Estado.LISTA, Compartidas.leer(v.compartidas)["vault"]?.estado)
        assertTrue(upstream("vault", destino.path) in v.leerRcloneConf())
        assertTrue(File(privada, "suelta.md").isFile)
        assertEquals(0, recargas)
    }

    @Test
    fun `volver a medio mover no pide la carpeta privada vacia`() {
        val (v, p) = volumen()
        val destino = destino()
        // Cortado tras el combine: la entrada `ENTRANDO`, los listados apartados
        // y la copia privada, que ya está en el remoto, entera.
        apartarAMano(v, p)
        Compartidas.escribir(
            v.compartidas,
            mapOf("vault" to Compartidas.Entrada(destino.path, Compartidas.Estado.ENTRANDO)),
        )
        v.actualizarCombine(v.leerConfig())
        val rclone = RcloneFalso()

        val hecho = Mudanza.salir(v, "vault", conPasada = false, entorno(rclone))

        assertTrue(hecho.ok, hecho.motivo)
        assertEquals(true, rclone.llamadas.single().parametros["resync"])
        assertNull(Compartidas.leer(v.compartidas)["vault"])
        assertTrue(combineSinEntradas(v) in v.leerRcloneConf())
        assertTrue(File(Mudanza.carpetaPrivada(v, p), "nota.md").isFile, "la copia privada se queda")
    }
}
