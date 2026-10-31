package prdrive.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import prdrive.app.rclone.Adaptadores
import prdrive.app.rclone.Rclone
import prdrive.engine.Config
import prdrive.engine.Orden
import prdrive.engine.Pareja
import prdrive.engine.Pasada
import prdrive.engine.Volumen
import prdrive.engine.leerJson

/**
 * Banco.kt — Lo que comparten los tests instrumentados: los vectores del motor,
 * un volumen limpio en el sitio de verdad de la app y un «remoto» que es una
 * carpeta local, para probar contra rclone sin red.
 *
 * Todos usan el volumen de la app (`filesDir/volumen`) porque rclone solo se
 * arranca una vez por proceso, con un `rclone.conf` y un directorio de trabajo,
 * y los tests corren en el proceso de la app.
 */
object Banco {

    val contexto get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("UNCHECKED_CAST")
    val vectores: Map<String, Any?> by lazy {
        val recurso = Banco::class.java.getResourceAsStream("/vectores.json")
            ?: error("vectores.json no está en los recursos del test")
        leerJson(recurso.bufferedReader().use { it.readText() }) as Map<String, Any?>
    }

    /** El «remoto»: una carpeta de la app que rclone ve como un remote `local`. */
    val remoto: File get() = File(contexto.filesDir, "remoto-de-prueba")

    /**
     * Deja el volumen y el remoto vacíos, escribe el config con esas parejas y
     * un `rclone.conf` con `[nas]` de tipo local y la sección del `combine`, y
     * arranca rclone. Vacía la caché de remotos de rclone, que si no recordaría
     * durante cinco minutos los de la prueba anterior.
     *
     * Con [llavero] (el nombre de su base) el config lleva `[keychain]`, y su
     * carpeta en el remoto es `catalogo/keychain` dentro de [remoto]. Con
     * [catalogo], el config dice dónde está el catálogo (`catalogo/remote.toml`
     * dentro de [remoto]) aunque no lleve el llavero.
     */
    fun volumen(
        vararg parejas: Map<String, Any?>,
        llavero: String? = null,
        catalogo: Boolean = llavero != null,
    ): kotlin.Pair<Volumen, Config> {
        val v = Prdrive.volumen(contexto)
        v.raiz.deleteRecursively()
        remoto.deleteRecursively()
        remoto.mkdirs()
        v.escribirConfig(
            mapOf(
                "defaults" to mapOf("device_remote" to "disp", "remote" to "nas") +
                    if (catalogo) mapOf("catalog_path" to File(remoto, "catalogo/remote.toml").absolutePath) else emptyMap(),
                "pair" to parejas.toList(),
            ) + if (llavero != null) mapOf("keychain" to mapOf("base" to llavero)) else emptyMap(),
            "# Para los tests.\n",
        )
        val config = v.leerConfig()
        v.rcloneConf.writeText("[nas]\ntype = local\n\n" + config.seccionCombine(v.raiz.absolutePath))
        Prdrive.iniciarRclone(contexto)
        Rclone.rpc("fscache/clear")
        return v to config
    }

    /**
     * Una base de KeePassXC de los vectores de `kdbx.py`: la de KDBX 4.1 entera,
     * o la primera que no lo está.
     */
    fun base(entera: Boolean): ByteArray =
        bytes(
            if (entera) {
                casosKdbx.first { it["entera"] == true && it["version"] == "4.1" }
            } else {
                casosKdbx.first { it["entera"] == false }
            },
        )

    /** Otra base entera, distinta de la de [base]: la de KDBX 4.1 con otra semilla. */
    fun otraBase(): ByteArray = bytes(casosKdbx.first { it["caso"] == "KDBX 4 con otra semilla" && it["entera"] == true })

    /** Una tercera base entera, distinta de [base] y de [otraBase]: la de KDBX 4 sin bloques de datos. */
    fun terceraBase(): ByteArray = bytes(casosKdbx.first { it["caso"] == "KDBX 4 sin bloques de datos" && it["entera"] == true })

    @Suppress("UNCHECKED_CAST")
    private val casosKdbx: List<Map<String, Any?>>
        get() = (vectores["llavero"] as Map<String, Any?>)["kdbx"] as List<Map<String, Any?>>

    private fun bytes(caso: Map<String, Any?>): ByteArray {
        val hexa = caso["hex"].toString()
        return ByteArray(hexa.length / 2) { hexa.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    /**
     * Abre la ficha de la pareja [nombre]: la sección Parejas (desde su raíz) y
     * su fila, y espera a que esté delante. Las filas se leen al abrir la app:
     * se espera también a que estén.
     */
    fun abrirFicha(compose: ComposeTestRule, nombre: String) {
        compose.onNodeWithTag("seccion-parejas").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("fila-$nombre")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("fila-$nombre").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("pantalla-pareja-$nombre")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Una pareja cuyo remoto es una carpeta de [remoto]. */
    fun pareja(nombre: String, local: String = "sync-data/$nombre", vararg extra: kotlin.Pair<String, Any?>): Map<String, Any?> =
        mapOf("name" to nombre, "local" to local, "remote_path" to File(remoto, nombre).absolutePath) + extra

    /**
     * Los fallos, sin los colores que bisync mete en su log ni otros caracteres
     * de control: el informe es XML 1.0, que no los admite, y con uno dentro
     * Gradle no dice qué test ha fallado, solo «Could not load test results».
     * Los mensajes de estos tests llevan el log de la pasada, que es lo que
     * explica el fallo.
     */
    class Legible : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (e: AssumptionViolatedException) {
                    throw e
                } catch (e: Throwable) {
                    val texto = e.message ?: throw e
                    if (!CONTROL.containsMatchIn(texto)) throw e
                    val limpio = texto.replace(COLOR, "").replace(CONTROL, "?")
                    val nuevo = if (e is AssertionError) AssertionError(limpio) else RuntimeException("${e.javaClass.name}: $limpio")
                    throw nuevo.apply { stackTrace = e.stackTrace }
                }
            }
        }

        private companion object {
            val COLOR = Regex("\\x{1b}\\[[0-9;]*[A-Za-z]")
            val CONTROL = Regex("[\\x{0}-\\x{8}\\x{b}\\x{c}\\x{e}-\\x{1f}]")
        }
    }

    /** La llamada rc de una pasada de la pareja, montada por el motor como la monta la pasada. */
    fun llamada(v: Volumen, p: Pareja, resync: Boolean): Pasada.Llamada {
        val filtros = prdrive.engine.Bisync.filtersFileFor(p, v.filtros)
        val tramos = Orden.tramos(
            p, binario = "rclone", filtros = filtros?.absolutePath, config = v.rcloneConf.absolutePath,
            logfile = "", workdir = v.workdir(p).absolutePath, resync = resync, sello = Pasada.selloVersiones(),
        )
        return Pasada.llamadaDe(tramos, Adaptadores.opciones)
    }
}
