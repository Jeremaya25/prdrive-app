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
import prdrive.app.compartidas.Compartir
import prdrive.app.rclone.Rclone
import prdrive.engine.LLAVERO
import prdrive.engine.LLAVERO_LOCAL
import prdrive.engine.Mudanza

/**
 * PuntaAPuntaTest.kt — Las pantallas de verdad contra un
 * servidor de verdad, de la carga del QR a una segunda pasada.
 *
 * Necesita un servidor SFTP que el emulador vea en `10.0.2.2` con un catálogo
 * (el de la prueba tiene dos parejas bisync, `notas` y `vault`, un espejo de
 * bajada `fotos`, con ficheros ya en el remoto, y el llavero con su base entera
 * en `keychain/`), y la carga de su QR en base64 como
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
            // El chip de la fila en Inicio («al día», «falló»), y nada que revisar.
            compose.waitUntilAtLeastOneExists(dice("fila-notas", "al día"), 5000)
            for (pareja in listOf("notas", "fotos")) {
                val fallos = compose.onAllNodes(dice("fila-$pareja", "falló"))
                assertTrue("la pasada de $pareja ha fallado", fallos.fetchSemanticsNodes().isEmpty())
            }
            compose.onAllNodes(hasText("«notas» pide rehacer la referencia", substring = true)).fetchSemanticsNodes().let {
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

    /**
     * Emparejar con la pantalla y mudar `vault` a una carpeta compartida de
     * verdad (la que la persona elige con el selector del sistema, que un test
     * no puede manejar: aquí la elige el test): lo del servidor baja, lo que
     * Obsidian crea allí sube con «Sincronizar», y la fila dice dónde vive.
     */
    @Test
    fun la_pareja_se_muda_a_una_carpeta_compartida_y_sincroniza_con_el_servidor() {
        val b64 = InstrumentationRegistry.getArguments().getString("carga_b64")
        assumeTrue("sin carga_b64: no hay servidor de prueba", !b64.isNullOrBlank())
        val carga = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)

        val v = Prdrive.volumen(contexto)
        v.raiz.deleteRecursively()
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(contexto.packageName, Prdrive.PERMISO_RED_LOCAL)
        }
        // El permiso de «acceso a todos los archivos» no se da con una pantalla
        // en un test: se da por appops, y el shell solo acaba si se lee hasta el final.
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set prdrive.app MANAGE_EXTERNAL_STORAGE allow")
        java.io.FileInputStream(shell.fileDescriptor).use { it.readBytes() }
        shell.close()
        assertTrue("No se pudo dar MANAGE_EXTERNAL_STORAGE", android.os.Environment.isExternalStorageManager())
        val destino = File(android.os.Environment.getExternalStorageDirectory(), "Documents/prdrive-e2e-${System.nanoTime()}")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("carga").performTextInput(carga)
                compose.onNodeWithTag("usarTexto").performScrollTo().performClick()
                compose.onNodeWithTag("conectar").performScrollTo().performClick()

                esperar("instalar")
                compose.onNodeWithTag("elegir-vault").assertExists()
                compose.onNodeWithTag("instalar").performClick()
                esperar("empezar", 180)
                errorEnPantalla()?.let { fail("la pantalla dice: $it") }
                compose.waitUntilAtLeastOneExists(dice("resultado-vault", "vault: OK"), 30_000)
                assertEquals("una nota del vault\n", File(v.raiz, "vault/nota.md").readText())
                compose.onNodeWithTag("empezar").performClick()
                esperar("sincronizar")
                esperar("fila-vault", 30)
                compose.onNodeWithTag("donde-vault").assertDoesNotExist()

                // La mudanza: lo que hace la fila tras elegir la carpeta.
                val dicho = ArrayList<String>()
                val hecho = Mudanza.entrar(v, "vault", destino, Compartir.entorno(contexto, true, {}) { dicho.add(it) })
                assertTrue("${hecho.motivo}\n" + dicho.joinToString("\n"), hecho.ok)
                assertEquals("una nota del vault\n", File(destino, "nota.md").readText())
                assertTrue("la copia privada sigue ahí", !File(v.raiz, "vault").exists())

                // Lo que Obsidian crea en la carpeta compartida.
                File(destino, "de-obsidian.md").writeText("escrito en Obsidian\n")
                compose.onNodeWithTag("sincronizar").performClick()
                compose.waitUntil(180_000) { compose.onAllNodes(hasTestTag("ocupado")).fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("error").assertDoesNotExist()
                captura("6-carpeta-compartida")

                // En el servidor, visto con el rclone de la app: lo nuevo ha subido y lo de antes sigue.
                val stat = Rclone.rpc("operations/stat", mapOf("fs" to "nas:/copia/vault", "remote" to "de-obsidian.md"))
                assertTrue("no ha llegado al servidor: $stat", stat["item"] != null)
                val nota = Rclone.rpc("operations/stat", mapOf("fs" to "nas:/copia/vault", "remote" to "nota.md"))
                assertTrue("el servidor ha perdido la nota: $nota", nota["item"] != null)

                // La fila dice dónde vive, y que la pasada fue bien.
                compose.waitUntilAtLeastOneExists(hasTestTag("donde-vault"), 5000)
                compose.waitUntilAtLeastOneExists(dice("fila-vault", "al día"), 5000)
                val fallos = compose.onAllNodes(dice("fila-vault", "falló"))
                assertTrue("la pasada de vault ha fallado", fallos.fetchSemanticsNodes().isEmpty())
            }
        } finally {
            destino.deleteRecursively()
        }
    }
}
