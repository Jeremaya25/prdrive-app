package prdrive.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.rclone.Rclone
import prdrive.engine.Bisync
import prdrive.engine.Pairing
import prdrive.engine.Pareja
import prdrive.engine.parseConfig

/**
 * RcloneSpikeTest.kt — El spike del `.aar` (PLAN.md, «Cómo se comprueba»):
 * antes de escribir la app, comprobar en un dispositivo que la biblioteca
 * arranca, que `sync/bisync` está registrado, que devuelve `output` y `session`,
 * y que ese `session` es el que calcula el motor.
 *
 * Sin red: el «remoto» es un remote de tipo `local` en otra carpeta de la app.
 * Eso además prueba la rama de `FsPath` que el motor replica para los remotes
 * locales ([Bisync.raizLocal]). Si el prefijo no coincide, el problema está
 * aquí y se ve antes de tocar un remoto de verdad.
 */
@RunWith(AndroidJUnit4::class)
class RcloneSpikeTest {

    companion object {
        private val contexto get() = InstrumentationRegistry.getInstrumentation().targetContext
        private lateinit var raiz: File
        private lateinit var volumen: File
        private lateinit var programa: File
        private lateinit var remoto: File

        @BeforeClass
        @JvmStatic
        fun preparar() {
            raiz = File(contexto.filesDir, "spike").apply { deleteRecursively(); mkdirs() }
            volumen = File(raiz, "volumen")
            programa = File(volumen, ".prdrive").apply { mkdirs() }
            remoto = File(raiz, "remoto").apply { mkdirs() }
            File(volumen, "sync-data").mkdirs()
            escribirConf()
            Rclone.iniciar(File(programa, "rclone.conf"), programa, File(contexto.cacheDir, "rclone"))
            // El `--verbose` de BASE_FLAGS. Tiene que ser el nivel GLOBAL: fs.Infof
            // mira `GetConfig(context.TODO())` (fs/log.go, LogLevelPrintf), no el
            // de la llamada, así que pasarlo con la pasada no sirve. Sin él, una
            // pasada buena no deja nada en `output`: bisync cuenta lo que hace en INFO.
            Rclone.rpc("options/set", mapOf("main" to mapOf("LogLevel" to "INFO")))
        }

        /**
         * Un nombre con lo que separa a rclone de Python y de Java en el `\s` de
         * `CanonicalPath`: un espacio de ancho completo (U+3000, que solo Python
         * sustituye), un tabulador vertical (que solo Java y Python sustituyen) y
         * un espacio normal (que sustituyen todos).
         */
        const val RARO = "a　b\u000bc d"

        private fun config() = parseConfig(
            mapOf(
                "defaults" to mapOf("device_remote" to "disp", "remote" to "nas"),
                "pair" to listOf(
                    mapOf(
                        "name" to "notas",
                        "local" to "sync-data/notas",
                        "remote_path" to File(remoto, "notas").absolutePath,
                    ),
                    mapOf(
                        "name" to "rara",
                        "local" to "sync-data/$RARO",
                        "remote_path" to File(remoto, RARO).absolutePath,
                    ),
                ),
            ),
        )

        private fun escribirConf() {
            // El remote del «remoto» y la sección [disp] que en el escritorio
            // viaja por variables de entorno, escritos por el motor.
            val texto = "[nas]\ntype = local\n\n" + config().seccionCombine(volumen.absolutePath)
            File(programa, "rclone.conf").writeText(texto)
        }

        private fun conf(): String = File(programa, "rclone.conf").readText()
    }

    private val pareja: Pareja get() = config().pairs.first { it.name == "notas" }
    private val workdir: File get() = File(programa, "state/notas")

    /** Los parámetros de `sync/bisync` para la pareja; la traducción completa es del paso 3. */
    private fun parametros(resync: Boolean, p: Pareja = pareja): Map<String, Any?> = mapOf(
        "path1" to p.source,
        "path2" to p.dest,
        "workdir" to File(programa, "state/${p.name}").absolutePath,
        "resync" to resync,
        "createEmptySrcDirs" to true,
        "conflictResolve" to "newer",
        "conflictSuffix" to "conflicto-dispositivo,conflicto-remoto",
        "maxDelete" to 25L,
        "resilient" to true,
        "recover" to true,
        "maxLock" to "2m",
    )

    @Test
    fun la_biblioteca_arranca_y_contesta() {
        val eco = Rclone.rpc("rc/noop", mapOf("hola" to "mundo"))
        assertEquals("mundo", eco["hola"])
        val version = Rclone.rpc("core/version")
        assertEquals("v1.75.2", version["version"])
        assertEquals("v1.75.2", Rclone.version())
    }

    @Test
    fun bisync_esta_registrado_y_su_session_es_la_del_motor() {
        File(volumen, "sync-data/notas").apply { mkdirs() }.resolve("hola.txt").writeText("desde el teléfono")
        File(remoto, "notas").apply { mkdirs() }.resolve("remoto.txt").writeText("desde el remoto")

        val tipos = Bisync.tiposDeRemote(conf())
        val esperado = Bisync.expectedPrefix(pareja, tipos)

        val resync = Rclone.trabajo("sync/bisync", parametros(resync = true))
        assertTrue("el resync falló: ${resync.error}\n${resync.salida["output"]}", resync.ok)
        assertEquals("el session de rclone no es el prefijo del motor", esperado, resync.salida["session"])
        assertTrue("sin log de la pasada", resync.salida["output"].toString().isNotBlank())
        assertEquals(File(workdir, esperado).absolutePath, resync.salida["basePath"])

        // El baseline que escribió rclone es el que el motor da por bueno.
        val estado = Bisync.pairState(workdir)
        assertEquals("ok", estado.status)
        assertEquals(esperado, estado.prefix)

        // Y los dos lados tienen ya los dos ficheros.
        assertTrue(File(remoto, "notas/hola.txt").isFile)
        assertTrue(File(volumen, "sync-data/notas/remoto.txt").isFile)

        // Una segunda pasada, sin resync, encuentra el baseline y propaga un cambio.
        File(volumen, "sync-data/notas/hola.txt").writeText("editado en el teléfono")
        val segunda = Rclone.trabajo("sync/bisync", parametros(resync = false))
        assertTrue("la segunda pasada falló: ${segunda.error}\n${segunda.salida["output"]}", segunda.ok)
        assertEquals("editado en el teléfono", File(remoto, "notas/hola.txt").readText())
        assertEquals(esperado, Bisync.pairState(workdir).prefix)
    }

    @Test
    fun un_nombre_con_espacios_raros_da_el_session_de_rclone() {
        // El motor se separa aquí de prdrive a propósito (Bisync.NO_CANONICAL):
        // manda rclone. Si esto falla, el teléfono pediría resync en cada pasada.
        val rara = config().pairs.first { it.name == "rara" }
        File(volumen, "sync-data/$RARO").mkdirs()
        File(remoto, RARO).mkdirs()
        val esperado = Bisync.expectedPrefix(rara, Bisync.tiposDeRemote(conf()))
        val final = Rclone.trabajo("sync/bisync", parametros(resync = true, p = rara))
        assertTrue("el resync falló: ${final.error}\n${final.salida["output"]}", final.ok)
        assertEquals(esperado, final.salida["session"])
        assertEquals(esperado, Bisync.pairState(File(programa, "state/rara")).prefix)
    }

    @Test
    fun una_pasada_que_falla_conserva_su_log() {
        // Sin baseline y sin resync, bisync se niega: el motivo tiene que llegar
        // con el log, que es lo que se le enseña al usuario.
        val otra = File(programa, "state/sin-baseline")
        val final = Rclone.trabajo(
            "sync/bisync",
            parametros(resync = false) + ("workdir" to otra.absolutePath),
        )
        assertFalse(final.ok)
        val log = final.salida["output"].toString()
        assertTrue("el log no dice por qué: $log", Bisync.MISSING_LISTINGS in log)
    }

    @Test
    fun el_progreso_llega_como_datos() {
        // Los dos lados tienen que existir: bisync no crea el remoto (en la app
        // lo hará la pasada, como `crear_carpeta_remota()` de sync.py).
        File(volumen, "sync-data/notas").mkdirs()
        File(remoto, "notas").mkdirs()
        val vistos = ArrayList<Map<String, Any?>>()
        val final = Rclone.trabajo("sync/bisync", parametros(resync = true), cadaMs = 50) { vistos.add(it) }
        assertTrue(final.error, final.ok)
        // El progreso puede no llegar a verse en una pasada tan corta, pero si
        // llega, trae los campos de core/stats.
        vistos.firstOrNull()?.let { assertTrue("bytes" in it && "transfers" in it) }
    }

    @Test
    fun el_rclone_conf_de_la_app_usa_rutas_relativas_que_rclone_resuelve() {
        // key_file = keys/... se resuelve contra el directorio de trabajo, que
        // la biblioteca pone en la carpeta del programa. Se comprueba con un
        // remote local relativo: si el cwd no fuera ese, apuntaría a otro sitio.
        File(programa, "keys").mkdirs()
        File(programa, "keys/prueba.txt").writeText("x")
        val stat = Rclone.rpc("operations/stat", mapOf("fs" to "keys", "remote" to "prueba.txt"))
        assertTrue("rclone no resuelve las rutas relativas contra .prdrive/: $stat", stat["item"] != null)
        // Y el lector de rclone.conf del motor lee el que se ha escrito.
        assertEquals("combine", Pairing.parseRcloneConf(conf())["disp"]?.get("type"))
    }
}
