package prdrive.app

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.rclone.Rclone
import prdrive.engine.LLAVERO
import prdrive.engine.LLAVERO_LOCAL

/**
 * PuntaAPuntaTest.kt — Las pantallas de verdad contra un
 * servidor de verdad, de la carga del QR a una segunda pasada.
 *
 * Necesita un servidor SFTP que el emulador vea en `10.0.2.2` con un catálogo
 * (el de la prueba tiene una pareja bisync `notas` y un espejo de bajada
 * `fotos`, con ficheros ya en el remoto, y el llavero con su base entera en
 * `keychain/`), y la carga de su QR en base64 como
 * argumento de la instrumentación:
 *
 *     ./gradlew :app:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.PuntaAPuntaTest \
 *         -Pandroid.testInstrumentationRunnerArguments.carga_b64=<base64>
 *
 * Sin el argumento, app/build.gradle.kts la deja fuera (y si se pide por su
 * nombre, se salta): el resto de la batería no necesita red.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class PuntaAPuntaTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val contexto get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Espera a que aparezca [tag]. Si antes sale un error en la pantalla, el
     * fallo lo cuenta, en vez de esperar a que se acabe el tiempo.
     */
    private fun esperar(tag: String, segundos: Long = 120) {
        compose.waitUntil(segundos * 1000) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() ||
                (errorEnPantalla() != null && compose.onAllNodes(hasTestTag("ocupado")).fetchSemanticsNodes().isEmpty())
        }
        errorEnPantalla()?.let { if (compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()) fail("la pantalla dice: $it") }
    }

    private fun errorEnPantalla(): String? =
        compose.onAllNodes(hasTestTag("error")).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString()

    /**
     * Una captura de la pantalla en /data/local/tmp, para verla después con
     * `adb pull`: la hace el usuario `shell`, así que sobrevive a que Gradle
     * desinstale la app al acabar.
     */
    private fun captura(nombre: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/prdrive-$nombre.png")
            .let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } }
    }

    @Test
    fun del_qr_a_la_segunda_pasada_con_un_servidor_de_verdad() {
        val b64 = InstrumentationRegistry.getArguments().getString("carga_b64")
        assumeTrue("sin carga_b64: no hay servidor de prueba", !b64.isNullOrBlank())
        val carga = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)

        // Un teléfono recién instalado: sin volumen.
        val v = Prdrive.volumen(contexto)
        v.raiz.deleteRecursively()
        // El servidor está en la red local del emulador (10.0.2.2): en API 37
        // eso pide permiso, que es lo que la persona da al pulsar «Conectar».
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(contexto.packageName, Prdrive.PERMISO_RED_LOCAL)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            // 1. El código: pegado, leído y conectado.
            compose.onNodeWithTag("carga").performTextInput(carga)
            compose.onNodeWithTag("usarTexto").performScrollTo().performClick()
            captura("1-codigo")
            compose.onNodeWithTag("conectar").performScrollTo().performClick()

            // 2. Las parejas del catálogo, todas marcadas.
            esperar("instalar")
            compose.onNodeWithTag("elegir-notas").assertExists()
            compose.onNodeWithTag("elegir-fotos").assertExists()
            captura("2-parejas")
            compose.onNodeWithTag("instalar").performClick()

            // 3. El --resync inicial de la bisync: trae lo del PC.
            esperar("empezar", 180)
            // «Ir a la principal» sale también si algo ha fallado: que no.
            errorEnPantalla()?.let { fail("la pantalla dice: $it") }
            compose.onNodeWithTag("aviso-espejos").assertExists()
            compose.waitUntilAtLeastOneExists(hasText("notas: OK") and hasAnyAncestor(hasTestTag("resultado-notas")), 5000)
            captura("3-inicio")
            assertEquals("escrito en el PC\n", File(v.raiz, "sync-data/notas/del-pc.txt").readText())
            assertTrue("el espejo no se inicializa", !File(v.raiz, "sync-data/fotos/foto.txt").exists())
            compose.onNodeWithTag("empezar").performClick()

            // 4. La principal, y una pasada normal: sube lo nuevo del teléfono y
            //    el espejo de bajada trae lo suyo.
            esperar("sincronizar")
            esperar("fila-notas", 30)
            File(v.raiz, "sync-data/notas/del-telefono.txt").writeText("escrito en el teléfono\n")
            compose.onNodeWithTag("sincronizar").performClick()
            compose.waitUntil(180_000) { compose.onAllNodes(hasTestTag("ocupado")).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("error").assertDoesNotExist()
            captura("4-principal")
            assertEquals("una foto\n", File(v.raiz, "sync-data/fotos/foto.txt").readText())

            // En el servidor, visto con el rclone de la app.
            val stat = Rclone.rpc("operations/stat", mapOf("fs" to "nas:/copia/notas", "remote" to "del-telefono.txt"))
            assertTrue("no ha llegado al servidor: $stat", stat["item"] != null)

            // La fila dice que está al día, sin fallos ni resync pendiente. Un
            // fallo de la pasada no es un error de la pantalla: se ve en la fila.
            compose.waitUntilAtLeastOneExists(hasText("Al día", substring = true) and hasAnyAncestor(hasTestTag("fila-notas")), 5000)
            for (pareja in listOf("notas", "fotos")) {
                val fallos = compose.onAllNodes(hasText("Falló", substring = true) and hasAnyAncestor(hasTestTag("fila-$pareja")))
                assertTrue("la pasada de $pareja ha fallado", fallos.fetchSemanticsNodes().isEmpty())
            }
            compose.onAllNodes(hasTestTag("resync-notas")).fetchSemanticsNodes().let {
                assertTrue("sigue pidiendo resync", it.isEmpty())
            }
        }
    }

    /** Un nodo con [tag], o dentro de uno con él, que dice [texto]. */
    private fun dice(tag: String, texto: String): SemanticsMatcher =
        (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and hasText(texto, substring = true)

    /**
     * Emparejar con «El llavero» marcado trae la base: la pasada del llavero
     * corre tras el `--resync` de las parejas (el catálogo del servidor lleva
     * `[keychain]`, y su base está entera en `keychain/`), y la principal la
     * enseña al día. Es el llavero entero, de la casilla al servidor, con el
     * rclone del `.aar` hablando SFTP de verdad.
     */
    @Test
    fun el_llavero_se_trae_al_emparejar_y_la_base_llega() {
        val b64 = InstrumentationRegistry.getArguments().getString("carga_b64")
        assumeTrue("sin carga_b64: no hay servidor de prueba", !b64.isNullOrBlank())
        val carga = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)

        val v = Prdrive.volumen(contexto)
        v.raiz.deleteRecursively()
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(contexto.packageName, Prdrive.PERMISO_RED_LOCAL)
        }
        // Las notificaciones del llavero se piden al instalar: el diálogo de
        // Android no es de la app.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(contexto.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("carga").performTextInput(carga)
            compose.onNodeWithTag("usarTexto").performScrollTo().performClick()
            compose.onNodeWithTag("conectar").performScrollTo().performClick()

            // La casilla sale porque el catálogo lleva `[keychain]`, y sale
            // desmarcada: el llavero se pide, no se impone.
            esperar("instalar")
            compose.onNodeWithTag("elegir-llavero").assertIsOff()
            compose.onNodeWithTag("elegir-llavero").performClick()
            compose.onNodeWithTag("elegir-llavero").assertIsOn()
            compose.onNodeWithTag("instalar").performClick()

            esperar("empezar", 180)
            errorEnPantalla()?.let { fail("la pantalla dice: $it") }
            compose.waitUntilAtLeastOneExists(dice("resultado-$LLAVERO", "$LLAVERO: OK"), 30_000)
            captura("5-llavero-al-emparejar")

            // Los bytes del servidor, tal cual, en `.keychain/`.
            val base = File(File(v.raiz, LLAVERO_LOCAL), "personal.kdbx")
            assertTrue("la base no ha bajado", base.isFile)
            assertArrayEquals(Banco.base(entera = true), base.readBytes())
            assertTrue("el config no lleva [keychain]", v.leerConfig().parejaLlavero != null)

            // Y la principal la enseña al día.
            compose.onNodeWithTag("empezar").performClick()
            compose.waitUntilAtLeastOneExists(dice("fila-llavero", "al día"), 60_000)
            compose.onNodeWithTag("error").assertDoesNotExist()
            compose.onNodeWithTag("llavero-ambar").assertDoesNotExist()
        }
    }
}
