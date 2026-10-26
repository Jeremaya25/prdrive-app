package prdrive.app

import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.OffsetDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.rclone.Adaptadores
import prdrive.app.rclone.Rclone
import prdrive.engine.upstream

/**
 * FechasCompartidasTest.kt — El paso 0 de «una pareja en una carpeta
 * compartida»: ¿conserva rclone las fechas de modificación en
 * `/storage/emulated/0/` (FUSE), con `MANAGE_EXTERNAL_STORAGE` dado?
 *
 * Bisync decide por las fechas. Si el almacenamiento compartido las redondea o
 * las cambia después de escribirlas, una pareja ahí repetiría copias para
 * siempre, y no hay forma de arreglarlo desde la app. Por eso esto se mira
 * antes de construir nada. Lo que sale (la precisión y las tres horas) va al
 * logcat con la etiqueta `FechasCompartidas`.
 */
@RunWith(AndroidJUnit4::class)
class FechasCompartidasTest {

    @get:Rule
    val legible = Banco.Legible()

    private lateinit var carpeta: File

    @Before
    fun dar_el_permiso_y_crear_la_carpeta() {
        // El permiso de «acceso a todos los ficheros» no se concede en un test
        // con una pantalla: se da por appops, y el shell acaba antes de que
        // `executeShellCommand` devuelva el descriptor, pero solo si se lee
        // hasta el final.
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set prdrive.app MANAGE_EXTERNAL_STORAGE allow")
        java.io.FileInputStream(shell.fileDescriptor).use { it.readBytes() }
        shell.close()
        assertTrue("No se pudo dar MANAGE_EXTERNAL_STORAGE", Environment.isExternalStorageManager())
        carpeta = File(Environment.getExternalStorageDirectory(), "Documents/prdrive-fechas-${System.nanoTime()}")
        assertTrue("No se pudo crear $carpeta", carpeta.mkdirs())
    }

    @After
    fun borrar_la_carpeta() {
        if (::carpeta.isInitialized) carpeta.deleteRecursively()
    }

    private fun horaDe(fs: String, remote: String): OffsetDateTime {
        @Suppress("UNCHECKED_CAST")
        val item = Rclone.rpc("operations/stat", mapOf("fs" to fs, "remote" to remote))["item"] as Map<String, Any?>
        return OffsetDateTime.parse(item["ModTime"].toString())
    }

    @Test
    fun rclone_conserva_la_fecha_de_lo_que_copia_a_una_carpeta_compartida() {
        // Solo para arrancar rclone con un config.
        Banco.volumen(Banco.pareja("notas"))
        val origen = File(Prdrive.temporal(Banco.contexto), "fechas-origen").apply { deleteRecursively(); mkdirs() }
        try {
            val fichero = File(origen, "nota.md").apply { writeText("hola") }
            // Una hora pasada y con milisegundos que no son cero: si la
            // precisión del destino fuera de segundos, se vería.
            assertTrue(fichero.setLastModified(1_700_000_000_123L))

            Rclone.rpc(
                "operations/copyfile",
                mapOf(
                    "srcFs" to origen.absolutePath, "srcRemote" to "nota.md",
                    "dstFs" to carpeta.absolutePath, "dstRemote" to "nota.md",
                ),
            )
            val original = horaDe(origen.absolutePath, "nota.md")
            val copiada = horaDe(carpeta.absolutePath, "nota.md")
            val precisionNs = (Rclone.rpc("operations/fsinfo", mapOf("fs" to carpeta.absolutePath))["Precision"] as Number).toLong()
            Thread.sleep(2000)
            val despues = horaDe(carpeta.absolutePath, "nota.md")

            Log.i(
                "FechasCompartidas",
                "API ${Build.VERSION.SDK_INT}: precision=$precisionNs ns, original=$original, copiada=$copiada, " +
                    "dos segundos despues=$despues",
            )
            val diferenciaNs = java.time.Duration.between(original, copiada).abs().toNanos()
            assertTrue("copiada=$copiada original=$original precision=$precisionNs ns", diferenciaNs <= precisionNs)
            assertEquals("La fecha cambió sola en dos segundos", copiada.toInstant(), despues.toInstant())
        } finally {
            origen.deleteRecursively()
        }
    }

    @Test
    fun una_bisync_contra_una_carpeta_compartida_no_repite_copias() {
        val (v, config) = Banco.volumen(Banco.pareja("notas", "notas"))
        val p = config.pairs.single()
        // El `notas` del volumen es la carpeta compartida: el `combine` lo apunta ahí.
        v.rcloneConf.writeText(
            "[nas]\ntype = local\n\n[disp]\ntype = combine\nupstreams = ${upstream("notas", carpeta.absolutePath)}\n",
        )
        Rclone.rpc("fscache/clear")
        val remoto = File(Banco.remoto, "notas").apply { mkdirs() }
        File(remoto, "del-remoto.txt").writeText("uno")
        File(carpeta, "de-la-carpeta.txt").writeText("uno")

        val inicial = Adaptadores.pasada.ejecutar(Banco.llamada(v, p, resync = true)) {}
        assertTrue(inicial.error + "\n" + inicial.log, inicial.ok)
        assertEquals("uno", File(carpeta, "del-remoto.txt").readText())
        assertEquals("uno", File(remoto, "de-la-carpeta.txt").readText())

        // Un cambio en cada lado, de distinto tamaño.
        File(carpeta, "de-la-carpeta.txt").writeText("cambiado en la carpeta")
        File(remoto, "del-remoto.txt").writeText("cambiado en el remoto")
        val primera = Adaptadores.pasada.ejecutar(Banco.llamada(v, p, resync = false)) {}
        assertTrue(primera.error + "\n" + primera.log, primera.ok)
        assertEquals("cambiado en el remoto", File(carpeta, "del-remoto.txt").readText())
        assertEquals("cambiado en la carpeta", File(remoto, "de-la-carpeta.txt").readText())

        // Si la carpeta cambiara las fechas después de escribirlas, esta
        // volvería a copiar lo mismo.
        val segunda = Adaptadores.pasada.ejecutar(Banco.llamada(v, p, resync = false)) {}
        Log.i("FechasCompartidas", "API ${Build.VERSION.SDK_INT}: segunda bisync:\n${segunda.log}")
        assertTrue(segunda.error + "\n" + segunda.log, segunda.ok)
        assertTrue("La segunda bisync no estaba en paz:\n${segunda.log}", "No changes found" in segunda.log)
    }
}
