package prdrive.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.rclone.Adaptadores
import prdrive.app.rclone.Rclone
import prdrive.engine.Bisync
import prdrive.engine.Config
import prdrive.engine.Pairing
import prdrive.engine.Volumen

/**
 * RcloneSpikeTest.kt — El spike del `.aar`:
 * la biblioteca arranca, `sync/bisync` está registrado, devuelve `output` y
 * `session`, y ese `session` es el que calcula el motor.
 *
 * Sin red: el «remoto» es un remote de tipo `local` en otra carpeta de la app.
 * Eso además prueba la rama de `FsPath` que el motor replica para los remotes
 * locales ([Bisync.raizLocal]). Los parámetros de cada llamada los monta el
 * motor ([Banco.llamada]): es la traducción de la pasada, contra rclone de
 * verdad.
 */
@RunWith(AndroidJUnit4::class)
class RcloneSpikeTest {

    companion object {
        /**
         * Un nombre con lo que separa a rclone de Python y de Java en el `\s` de
         * `CanonicalPath`: un espacio de ancho completo (U+3000, que solo Python
         * sustituye), un tabulador vertical (que solo Java y Python sustituyen) y
         * un espacio normal (que sustituyen todos).
         */
        const val RARO = "a　b\u000bc d"
    }

    @get:Rule
    val legible = Banco.Legible()

    private lateinit var v: Volumen
    private lateinit var config: Config

    @Before
    fun preparar() {
        val (volumen, _) = Banco.volumen(Banco.pareja("notas"))
        v = volumen
        // La pareja del nombre raro no cabe en el TOML (un `` crudo no lo
        // admite tomllib, y el escritorio no lo escribiría): se monta en memoria,
        // que es lo que hace falta para preguntarle a rclone por su `session`.
        // Comparte el upstream `sync-data` con la otra, así que el `combine` del
        // rclone.conf ya la cubre.
        config = prdrive.engine.parseConfig(
            mapOf(
                "defaults" to mapOf("device_remote" to "disp", "remote" to "nas"),
                "pair" to listOf(Banco.pareja("notas"), Banco.pareja(RARO, "sync-data/$RARO") + ("name" to "rara")),
            ),
        )
        File(v.raiz, "sync-data/notas").mkdirs()
        File(Banco.remoto, "notas").mkdirs()
    }

    private val notas get() = config.pairs.first { it.name == "notas" }

    private fun conf(): String = v.leerRcloneConf()

    private fun pasar(p: prdrive.engine.Pareja = notas, resync: Boolean) =
        Adaptadores.pasada.ejecutar(Banco.llamada(v, p, resync)) {}

    @Test
    fun la_biblioteca_arranca_y_contesta() {
        val eco = Rclone.rpc("rc/noop", mapOf("hola" to "mundo"))
        assertEquals("mundo", eco["hola"])
        // La versión que fija prdrive (common/pins.py), que es la del .aar.
        val fijada = Banco.vectores["rclone_version"]
        assertEquals(fijada, Rclone.rpc("core/version")["version"])
        assertEquals(fijada, Rclone.version())
    }

    @Test
    fun bisync_esta_registrado_y_su_session_es_la_del_motor() {
        File(v.raiz, "sync-data/notas/hola.txt").writeText("desde el teléfono")
        File(Banco.remoto, "notas/remoto.txt").writeText("desde el remoto")

        val esperado = Bisync.expectedPrefix(notas, Bisync.tiposDeRemote(conf()))
        val resync = pasar(resync = true)
        assertTrue("el resync falló: ${resync.error}\n${resync.log}", resync.ok)
        assertEquals("el session de rclone no es el prefijo del motor", esperado, resync.salida["session"])
        assertTrue("sin log de la pasada", resync.log.isNotBlank())
        assertEquals(File(v.workdir(notas), esperado).absolutePath, resync.salida["basePath"])

        // El baseline que escribió rclone es el que el motor da por bueno.
        val estado = Bisync.pairState(v.workdir(notas))
        assertEquals("ok", estado.status)
        assertEquals(esperado, estado.prefix)
        assertTrue(File(Banco.remoto, "notas/hola.txt").isFile)
        assertTrue(File(v.raiz, "sync-data/notas/remoto.txt").isFile)

        // Una segunda pasada, sin resync, encuentra el baseline y propaga un cambio.
        File(v.raiz, "sync-data/notas/hola.txt").writeText("editado en el teléfono")
        val segunda = pasar(resync = false)
        assertTrue("la segunda pasada falló: ${segunda.error}\n${segunda.log}", segunda.ok)
        assertEquals("editado en el teléfono", File(Banco.remoto, "notas/hola.txt").readText())
        assertEquals(esperado, Bisync.pairState(v.workdir(notas)).prefix)
    }

    @Test
    fun un_nombre_con_espacios_raros_da_el_session_de_rclone() {
        // El motor se separa aquí de prdrive a propósito (Bisync.NO_CANONICAL y
        // raizLocal): manda rclone. Si esto falla, el teléfono pediría resync
        // en cada pasada.
        val rara = config.pairs.first { it.name == "rara" }
        File(v.raiz, "sync-data/$RARO").mkdirs()
        File(Banco.remoto, RARO).mkdirs()
        val esperado = Bisync.expectedPrefix(rara, Bisync.tiposDeRemote(conf()))
        val final = pasar(rara, resync = true)
        assertTrue("el resync falló: ${final.error}\n${final.log}", final.ok)
        assertEquals(esperado, final.salida["session"])
        assertEquals(esperado, Bisync.pairState(v.workdir(rara)).prefix)
    }

    @Test
    fun una_pasada_que_falla_conserva_su_log() {
        // Sin baseline y sin resync, bisync se niega: el motivo tiene que llegar
        // con el log, que es lo que se le enseña al usuario.
        val final = pasar(resync = false)
        assertFalse(final.ok)
        // Con fichero de filtros, lo primero que mira bisync es su md5: el
        // motivo es ese, y tiene que llegar al log y tener explicación.
        val texto = final.log + "\n" + final.error
        assertTrue("el log no dice por qué: $texto", "must run --resync" in texto)
        assertTrue("sin explicación para: $texto", prdrive.engine.Pasada.explicar(texto) != null)
    }

    @Test
    fun el_progreso_llega_como_datos() {
        // Lo bastante para que la pasada dure algo; y al acabar se entrega la
        // última estadística, aunque la pasada sea más corta que el intervalo.
        val datos = ByteArray(256 * 1024) { (it % 251).toByte() }
        repeat(20) { File(v.raiz, "sync-data/notas/f$it.bin").writeBytes(datos) }
        val vistos = ArrayList<Map<String, Any?>>()
        val final = Adaptadores.pasada.ejecutar(Banco.llamada(v, notas, resync = true)) { vistos.add(it) }
        assertTrue(final.error, final.ok)
        assertTrue("no ha llegado ninguna estadística", vistos.isNotEmpty())
        val ultima = vistos.last()
        assertTrue("faltan campos de core/stats: $ultima", "bytes" in ultima && "transfers" in ultima)
        assertEquals(20L, (ultima["transfers"] as Number).toLong())
    }

    @Test
    fun el_rclone_conf_de_la_app_usa_rutas_relativas_que_rclone_resuelve() {
        // key_file = keys/... se resuelve contra el directorio de trabajo, que
        // la biblioteca pone en la carpeta del programa. Se comprueba con un
        // remote local relativo: si el cwd no fuera ese, apuntaría a otro sitio.
        File(v.programa, "keys").mkdirs()
        File(v.programa, "keys/prueba.txt").writeText("x")
        val stat = Rclone.rpc("operations/stat", mapOf("fs" to "keys", "remote" to "prueba.txt"))
        assertTrue("rclone no resuelve las rutas relativas contra .prdrive/: $stat", stat["item"] != null)
        assertEquals("combine", Pairing.parseRcloneConf(conf())["disp"]?.get("type"))
    }
}
