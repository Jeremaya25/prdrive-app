package prdrive.app

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.compartidas.Compartir
import prdrive.app.rclone.Adaptadores
import prdrive.engine.Bisync
import prdrive.engine.Compartidas
import prdrive.engine.Mudanza
import prdrive.engine.Pareja
import prdrive.engine.Pasada
import prdrive.engine.Volumen

/**
 * MudanzaEnAndroidTest.kt — Mover una pareja a una carpeta compartida de
 * verdad, y volver, contra el rclone del `.aar`.
 *
 * El motor prueba la secuencia con un rclone de mentira y en segundos
 * (`MudanzaTest`); esto es lo que no se puede probar así: que rclone, en
 * `/storage/emulated/0/Documents/` (FUSE), sincronice sin repetir copias, que
 * los listados de bisync se llamen igual con la pareja fuera del volumen, y que
 * sin el permiso la pasada se pare antes de llamarle. Con el permiso dado por
 * `appops`, como en [FechasCompartidasTest]. El remoto es una carpeta local;
 * contra un servidor SFTP, lo hace `PuntaAPuntaTest`.
 */
@RunWith(AndroidJUnit4::class)
class MudanzaEnAndroidTest {

    @get:Rule
    val legible = Banco.Legible()

    private lateinit var destino: File

    /** Lo que ha ido contando la mudanza, para que un fallo se explique solo. */
    private val dicho = ArrayList<String>()

    @Before
    fun dar_el_permiso_y_elegir_el_destino() {
        // El permiso de «acceso a todos los ficheros» no se da en un test con una
        // pantalla: se da por appops, y el shell solo acaba si se lee hasta el final.
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set prdrive.app MANAGE_EXTERNAL_STORAGE allow")
        java.io.FileInputStream(shell.fileDescriptor).use { it.readBytes() }
        shell.close()
        assertTrue("No se pudo dar MANAGE_EXTERNAL_STORAGE", Environment.isExternalStorageManager())
        // Sin crear: la mudanza lo crea, que es lo que hace la persona.
        destino = File(Environment.getExternalStorageDirectory(), "Documents/prdrive-mudanza-${System.nanoTime()}")
    }

    @After
    fun borrar_el_destino() {
        if (::destino.isInitialized) destino.deleteRecursively()
    }

    private fun entorno(): Mudanza.Entorno = Compartir.entorno(Banco.contexto, true, {}) { dicho.add(it) }

    private fun contado(hecho: Mudanza.Hecho): String =
        "motivo: ${hecho.motivo}\n" + dicho.joinToString("\n") + "\n" +
            hecho.pasadas.joinToString("\n") { it.lineas.joinToString("\n") + "\n" + (it.log?.readText() ?: "") }

    private fun todo(r: Pasada.Resultado): String = r.lineas.joinToString("\n") + "\n" + (r.log?.readText() ?: "")

    /** La pareja tal como el config del volumen la lee ahora, con su carpeta compartida si la tiene. */
    private fun pareja(v: Volumen): Pareja = v.leerConfig().pairs.single()

    private fun pasar(v: Volumen, ctx: Pasada.Contexto): Pasada.Resultado =
        Pasada.pasar(v, pareja(v), Adaptadores.pasada, Adaptadores.opciones, ctx)

    private fun normal() = Pasada.Contexto(keepLogs = true, accesoCompartido = Prdrive.accesoCompartido())

    /** Los listados vivos del workdir (`.lst`): el nombre sale del de sesión, y no puede cambiar al mudarse. */
    private fun listados(v: Volumen): List<String> =
        pareja(v).let { p -> v.workdir(p).list()!!.filter { it.endsWith(".lst") }.sorted() }

    /** Una pareja `vault` inicializada, con una nota de cada lado y una que no se toca (un fichero solo cambiaría todos). */
    private fun vaultInicializado(): Volumen {
        val (v, _) = Banco.volumen(Banco.pareja("vault", "vault"))
        File(Banco.remoto, "vault").mkdirs()
        File(Banco.remoto, "vault/del-remoto.md").writeText("del remoto")
        File(Banco.remoto, "vault/quieto.md").writeText("quieto")
        File(v.raiz, "vault").mkdirs()
        File(v.raiz, "vault/del-telefono.md").writeText("del teléfono")
        val inicial = pasar(v, Pasada.Contexto(resyncAprobado = true, keepLogs = true))
        assertEquals(todo(inicial), 0, inicial.codigo)
        return v
    }

    @Test
    fun una_pareja_se_muda_a_una_carpeta_compartida_y_vuelve() {
        val v = vaultInicializado()
        val antes = listados(v)
        assertTrue("sin listados tras el --resync", antes.isNotEmpty())
        // Lo que ningún filtro sube y solo tiene la copia privada: tiene que
        // llegar a la carpeta antes de borrarla.
        File(v.raiz, "vault/.prversions").mkdirs()
        File(v.raiz, "vault/.prversions/viejo.md").writeText("una versión vieja")

        val entrada = Mudanza.entrar(v, "vault", destino, entorno())
        assertTrue(contado(entrada), entrada.ok)
        // Lo del remoto está en la carpeta compartida; la copia privada no está.
        assertEquals("del remoto", File(destino, "del-remoto.md").readText())
        assertEquals("del teléfono", File(destino, "del-telefono.md").readText())
        assertEquals("una versión vieja", File(destino, ".prversions/viejo.md").readText())
        assertFalse("la copia privada sigue ahí", File(v.raiz, "vault").exists())
        assertEquals(
            Compartidas.Entrada(destino.path, Compartidas.Estado.LISTA),
            Compartidas.leer(v.compartidas)["vault"],
        )
        assertEquals(destino.path, pareja(v).carpetaExterna)
        // El nombre de sesión de bisync no depende de adónde apunte el upstream.
        assertEquals(antes, listados(v))

        // Un cambio en la carpeta compartida y otro en el remoto, y una pasada normal.
        File(destino, "del-telefono.md").writeText("cambiado en la carpeta")
        File(Banco.remoto, "vault/del-remoto.md").writeText("cambiado en el remoto")
        File(destino, "nueva.md").writeText("la crea Obsidian")
        val normal = pasar(v, normal())
        assertEquals(todo(normal), 0, normal.codigo)
        assertEquals("cambiado en la carpeta", File(Banco.remoto, "vault/del-telefono.md").readText())
        assertEquals("cambiado en el remoto", File(destino, "del-remoto.md").readText())
        assertEquals("la crea Obsidian", File(Banco.remoto, "vault/nueva.md").readText())
        // Y la siguiente no tiene nada que hacer: las fechas se conservan.
        val segunda = pasar(v, normal())
        assertEquals(todo(segunda), 0, segunda.codigo)
        assertTrue("la segunda pasada no estaba en paz:\n${todo(segunda)}", "No changes found" in todo(segunda))

        dicho.clear()
        val vuelta = Mudanza.salir(v, "vault", conPasada = true, e = entorno())
        assertTrue(contado(vuelta), vuelta.ok)
        assertEquals(emptyMap<String, Compartidas.Entrada>(), Compartidas.leer(v.compartidas))
        assertEquals(null, pareja(v).carpetaExterna)
        // Los ficheros bajan a la carpeta privada; la compartida no se toca.
        assertEquals("cambiado en la carpeta", File(v.raiz, "vault/del-telefono.md").readText())
        assertEquals("cambiado en el remoto", File(v.raiz, "vault/del-remoto.md").readText())
        assertEquals("la crea Obsidian", File(v.raiz, "vault/nueva.md").readText())
        assertEquals("cambiado en el remoto", File(destino, "del-remoto.md").readText())
        assertEquals("quieto", File(destino, "quieto.md").readText())
        assertEquals(antes, listados(v))
        // Y la pareja, otra vez en el volumen, sigue sincronizando.
        File(v.raiz, "vault/otra.md").writeText("otra")
        val despues = pasar(v, normal())
        assertEquals(todo(despues), 0, despues.codigo)
        assertEquals("otra", File(Banco.remoto, "vault/otra.md").readText())
    }

    @Test
    fun una_mudanza_cortada_tras_la_entrada_se_termina() {
        val v = vaultInicializado()
        // Lo que deja un corte entre apuntar la carpeta y reescribir el
        // rclone.conf: los listados apartados y la entrada en `entrando`.
        assertTrue(destino.mkdirs())
        Bisync.apartarListados(v.workdir(pareja(v)))
        Compartidas.escribir(
            v.compartidas,
            mapOf("vault" to Compartidas.Entrada(destino.path, Compartidas.Estado.ENTRANDO)),
        )

        // El rclone.conf aún apunta a la copia privada: la pasada se para sin sincronizar.
        val parada = pasar(v, normal())
        assertEquals(todo(parada), Pasada.ABORTO, parada.codigo)
        assertEquals(Pasada.COMBINE_VIEJO, parada.explicacion)
        assertFalse(File(destino, "del-remoto.md").exists())

        val hecho = Mudanza.terminar(v, "vault", entorno())
        assertTrue(contado(hecho), hecho.ok)
        assertEquals("del remoto", File(destino, "del-remoto.md").readText())
        assertEquals("del teléfono", File(destino, "del-telefono.md").readText())
        assertFalse("la copia privada sigue ahí", File(v.raiz, "vault").exists())
        assertEquals(Compartidas.Estado.LISTA, Compartidas.leer(v.compartidas)["vault"]!!.estado)
        assertTrue(Compartidas.alDia(v.leerRcloneConf(), pareja(v)))
        // La pareja vuelve a sincronizar sola.
        File(destino, "nueva.md").writeText("nueva")
        val normal = pasar(v, normal())
        assertEquals(todo(normal), 0, normal.codigo)
        assertEquals("nueva", File(Banco.remoto, "vault/nueva.md").readText())
    }

    @Test
    fun sin_permiso_una_pareja_compartida_aborta_sin_llamar_a_rclone() {
        val v = vaultInicializado()
        val entrada = Mudanza.entrar(v, "vault", destino, entorno())
        assertTrue(contado(entrada), entrada.ok)
        val remotoAntes = instantanea(File(Banco.remoto, "vault"))
        assertNotNull(remotoAntes["del-remoto.md"])

        // Lo que Obsidian acaba de crear y Android esconde sin el permiso: la
        // pasada no puede ni mirar, y menos mandar nada al remoto.
        File(destino, "recien-creada.md").writeText("nueva")
        val sinRclone = object : Pasada.Rclone {
            override fun ejecutar(llamada: Pasada.Llamada, alProgreso: (Map<String, Any?>) -> Unit): Pasada.Final =
                error("la pasada ha llamado a rclone sin permiso")

            override fun mkdir(destino: String): Pasada.Final = error("la pasada ha llamado a rclone sin permiso")
        }
        val ctx = Pasada.Contexto(keepLogs = true, accesoCompartido = Prdrive.accesoCompartido(tienePermiso = { false }))
        val r = Pasada.pasar(v, pareja(v), sinRclone, Adaptadores.opciones, ctx)
        assertEquals(r.lineas.joinToString("\n"), Pasada.ABORTO, r.codigo)
        assertEquals(Prdrive.SIN_PERMISO_COMPARTIDO, r.explicacion)
        assertEquals(remotoAntes, instantanea(File(Banco.remoto, "vault")))
        assertFalse(File(Banco.remoto, "vault/recien-creada.md").exists())
    }

    /** Ruta relativa → contenido de cada fichero de [carpeta]. */
    private fun instantanea(carpeta: File): Map<String, String> =
        carpeta.walkTopDown().filter { it.isFile }.associate { it.relativeTo(carpeta).path to it.readText() }
}
