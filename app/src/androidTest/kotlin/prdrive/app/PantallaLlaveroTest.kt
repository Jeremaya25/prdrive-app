package prdrive.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.app.NotificationManager
import android.content.Intent
import android.provider.DocumentsContract
import android.service.notification.StatusBarNotification
import androidx.compose.ui.semantics.SemanticsProperties
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.intent.matcher.IntentMatchers.hasFlags
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.documentos.GuardadoAtomico
import prdrive.app.documentos.VolumenProvider
import prdrive.app.llavero.Avisos
import prdrive.app.llavero.LlaveroApp
import prdrive.app.rclone.Adaptadores
import prdrive.app.ui.TextosLlavero
import prdrive.app.ui.conLaLlave
import prdrive.app.ui.intentDeAbrir
import prdrive.engine.Catalogo
import prdrive.engine.Conflictos
import prdrive.engine.LLAVERO
import prdrive.engine.LLAVERO_LLAVE
import prdrive.engine.LLAVERO_LOCAL
import prdrive.engine.Pairing
import prdrive.engine.Volumen
import prdrive.engine.dumps
import prdrive.engine.loadRaw

/**
 * PantallaLlaveroTest.kt — El llavero en las pantallas de verdad: la fila de
 * la principal, «Abrir llavero» (que pide la base por el proveedor),
 * «Traer el llavero» (al emparejar y desde la principal), quitarlo, y las
 * notificaciones ([Avisos]).
 *
 * El remoto es el de [Banco]: una carpeta que rclone ve como un remote
 * `local`, con el catálogo en `catalogo/remote.toml` y el llavero en
 * `catalogo/keychain`. WorkManager es el de prueba: lo que se encole no corre
 * solo encima del volumen del test siguiente.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class PantallaLlaveroTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val contexto get() = Banco.contexto
    private val notificaciones get() = contexto.getSystemService(NotificationManager::class.java)

    /** La carpeta del llavero en el remoto. */
    private val remotoLlavero: File get() = File(Banco.remoto, "catalogo/keychain")

    @Suppress("UNCHECKED_CAST")
    private val enConflicto: String
        get() = ((Banco.vectores["llavero"] as Map<String, Any?>)["avisos"] as Map<String, Any?>)["LLAVERO_EN_CONFLICTO"].toString()

    @Before
    fun preparar() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            contexto,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        // Los permisos que la pantalla pide al activar el llavero y al
        // conectar, dados: el diálogo de Android no es de la app.
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (android.os.Build.VERSION.SDK_INT >= 37) ui.grantRuntimePermission(contexto.packageName, Prdrive.PERMISO_RED_LOCAL)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ui.grantRuntimePermission(contexto.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        notificaciones.cancelAll()
        File(contexto.noBackupFilesDir, "llavero").deleteRecursively()
    }

    // --- ayudas -------------------------------------------------------------------

    /** El catálogo del remoto, con esas [parejas] (una `notas`) y (si [llavero]) el `[keychain]`. */
    private fun catalogo(llavero: Map<String, Any?>?, parejas: List<Map<String, Any?>> = listOf(Banco.pareja("notas"))) {
        val raw = mapOf(
            "defaults" to mapOf("remote" to "nas"),
            "pair" to parejas,
        ) + if (llavero != null) mapOf("keychain" to llavero) else emptyMap()
        File(Banco.remoto, "catalogo").mkdirs()
        File(Banco.remoto, "catalogo/remote.toml").writeText(dumps(raw, "# El catálogo de la prueba.\n"))
        parejas.forEach { File(it["remote_path"].toString()).mkdirs() }
    }

    /**
     * La carga de un QR cuyo remote es una carpeta del teléfono, con el
     * catálogo de [catalogo] (`dumps` es el del config, y no escribe las
     * claves de una carga).
     */
    private fun cargaDelQr(): String =
        "catalog_path = \"${File(Banco.remoto, "catalogo/remote.toml").absolutePath}\"\n" +
            "${Pairing.MARCA} = ${Pairing.FORMATO}\nremote_name = \"nas\"\n[options]\ntype = \"local\"\n"

    /** Del código a «Ir a la pantalla principal», con «El llavero» marcado. */
    private fun emparejarConLlavero() {
        compose.onNodeWithText("Pegar el texto del código").performClick()
        compose.onNodeWithTag("carga").performTextInput(cargaDelQr())
        compose.onNodeWithTag("usarTexto").performScrollTo().performClick()
        esperar("conectar")
        compose.onNodeWithTag("conectar").performClick()
        esperar("elegir-llavero")
        compose.onNodeWithTag("elegir-llavero").assertIsOff()
        compose.onNodeWithTag("elegir-llavero").performClick()
        compose.onNodeWithTag("elegir-llavero").assertIsOn()
        compose.onNodeWithTag("instalar").performClick()
        esperar("empezar", 180)
        compose.onNodeWithTag("error").assertDoesNotExist()
        esperar("resultado-$LLAVERO", "hecha")
    }

    /** La base en el remoto, para que una pasada la traiga. */
    private fun baseEnElRemoto(): ByteArray {
        val bytes = Banco.base(entera = true)
        remotoLlavero.mkdirs()
        File(remotoLlavero, "personal.kdbx").writeBytes(bytes)
        return bytes
    }

    private fun baseLocal(v: Volumen): File = File(File(v.raiz, LLAVERO_LOCAL), "personal.kdbx")

    /** Un nodo con [tag], o dentro de uno con él, que dice [texto]. */
    private fun dice(tag: String, texto: String): SemanticsMatcher =
        (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and hasText(texto, substring = true)

    /** Espera a que la pantalla diga [texto] en [tag]; si no, el fallo cuenta lo que dice. */
    private fun esperar(tag: String, texto: String, segundos: Long = 60) {
        try {
            compose.waitUntil(segundos * 1000) { compose.onAllNodes(dice(tag, texto)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("«$tag» no dice «$texto»; en pantalla: ${textos()}", e)
        }
    }

    /** Espera a que aparezca [tag]. */
    private fun esperar(tag: String, segundos: Long = 60) {
        try {
            compose.waitUntil(segundos * 1000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("no aparece «$tag»; en pantalla: ${textos()}", e)
        }
    }

    /** La sección Llavero, donde están traerlo, quitarlo y lo que lo pone en ámbar. */
    private fun irAlLlavero() {
        compose.onNodeWithTag("seccion-llavero").performClick()
        esperar("pantalla-llavero")
    }

    /** Espera a que no quede nada en marcha. */
    private fun libre(segundos: Long = 120) {
        compose.waitUntil(segundos * 1000) { compose.onAllNodes(hasTestTag("ocupado")).fetchSemanticsNodes().isEmpty() }
    }

    private fun textos(): List<String> =
        compose.onAllNodes(SemanticsMatcher("con texto") { it.config.getOrNull(SemanticsProperties.Text) != null })
            .fetchSemanticsNodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString() }

    /** La app pasa a segundo plano y vuelve, como al volver de la app de KeePass. */
    private fun volver(escena: ActivityScenario<MainActivity>) {
        escena.moveToState(Lifecycle.State.STARTED)
        escena.moveToState(Lifecycle.State.RESUMED)
    }

    /** Las notificaciones del llavero que se ven, cuando dejan de cambiar. */
    private fun delLlavero(esperadas: Int): List<StatusBarNotification> {
        val hasta = System.currentTimeMillis() + 5_000
        var hay: List<StatusBarNotification>
        do {
            hay = notificaciones.activeNotifications.filter { it.notification.channelId == Avisos.CANAL }
            if (hay.size == esperadas) {
                // Un momento más, por si llegara otra.
                Thread.sleep(300)
                return notificaciones.activeNotifications.filter { it.notification.channelId == Avisos.CANAL }
            }
            Thread.sleep(50)
        } while (System.currentTimeMillis() < hasta)
        return hay
    }

    private fun texto(n: StatusBarNotification): String =
        n.notification.extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString()
            ?: n.notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString() ?: ""

    /** Los `ACTION_VIEW` que la app ha lanzado desde que empezó a grabar `Intents`. */
    private fun vistas(): List<Intent> = Intents.getIntents().filter { it.action == Intent.ACTION_VIEW }

    // --- la fila ------------------------------------------------------------------

    @Test
    fun la_fila_del_llavero_dice_al_dia_pendiente_o_por_que_esta_en_ambar() {
        val (v, config) = Banco.volumen(Banco.pareja("notas"), llavero = "personal.kdbx")
        val bytes = baseEnElRemoto()
        ActivityScenario.launch(MainActivity::class.java).use { escena ->
            // Sin pasada todavía, la base no está: «Sincronizar» pasa también
            // el llavero, el último, y la trae.
            esperar("fila-llavero", "todavía no está en el teléfono")
            compose.onNodeWithTag("sincronizar").performClick()
            libre()
            esperar("fila-llavero", "al día")
            assertArrayEquals(bytes, baseLocal(v).readBytes())
            irAlLlavero()
            compose.onNodeWithTag("llavero-ambar").assertDoesNotExist()

            // La base cambia en el teléfono (lo que haría la app de KeePass):
            // hasta que suba, la fila lo dice.
            val base = baseLocal(v)
            val hora = base.lastModified() + 60_000
            base.writeBytes(Banco.otraBase())
            assertTrue(base.setLastModified(hora))
            assertTrue(LlaveroApp.pendiente(contexto))
            volver(escena)
            esperar("llavero-estado", "con cambios que aún no han subido")
            compose.onNodeWithTag("llavero-ambar").assertDoesNotExist()

            // Un guardado que llegó cortado: en ámbar, con su motivo.
            GuardadoAtomico.apuntarCortado(v.state, "personal.kdbx", GuardadoAtomico.MOTIVO_SOLTADO)
            volver(escena)
            esperar("llavero-ambar", GuardadoAtomico.MOTIVO_SOLTADO)
            GuardadoAtomico.quitarCortado(v.state, "personal.kdbx")
            volver(escena)
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("llavero-ambar")).fetchSemanticsNodes().isEmpty() }

            // Una copia de conflicto: en ámbar, con lo que dice prdrive y la copia.
            File(File(v.raiz, LLAVERO_LOCAL), "personal.conflicto-remoto1.kdbx").writeBytes(Banco.base(entera = true))
            assertEquals(1, Conflictos.actualizarPareja(config.parejaLlavero!!, v.raiz, v.state).size)
            volver(escena)
            esperar("llavero-ambar", "personal.conflicto-remoto1.kdbx")
            esperar("llavero-ambar", enConflicto.trim())
            esperar("llavero-ambar", Avisos.COMBINAR_FUERA)
        }
    }

    @Test
    fun sin_la_llave_interna_la_fila_lo_dice_y_abrir_avisa_antes() {
        val (v, _) = Banco.volumen(Banco.pareja("notas"), llavero = "personal.kdbx")
        // El llavero sin contraseña, con la llave borrada a mano.
        val raw = loadRaw(v.config.readText()).toMutableMap()
        raw["keychain"] = mapOf("base" to "personal.kdbx", "fichero_llave" to true, "llave_interna" to true)
        v.escribirConfig(raw, "# Para los tests.\n")
        assertTrue(v.leerConfig().parejaLlavero!!.llaveInterna)
        baseEnElRemoto()
        // La pasada no usa la llave: trae la base igual.
        val r = LlaveroApp.pasar(contexto)
        assertEquals(r?.lineas?.joinToString("\n"), 0, r?.codigo)
        assertFalse(File(File(v.raiz, LLAVERO_LOCAL), LLAVERO_LLAVE).exists())

        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_VIEW)).respondWith(ActivityResult(Activity.RESULT_OK, null))
            ActivityScenario.launch(MainActivity::class.java).use {
                irAlLlavero()
                esperar("llavero-ambar", "falta su fichero llave")
                compose.onNodeWithTag("abrir-llavero").performClick()
                // Antes de abrir, el aviso; y no se abre hasta que se cierra.
                esperar("aviso-llavero")
                esperar("aviso-llavero", "falta su fichero llave")
                assertTrue("se ha abierto antes del aviso", vistas().isEmpty())
                compose.onNodeWithTag("cerrar-aviso-llavero").performClick()
                compose.waitUntil(10_000) { vistas().isNotEmpty() }
                assertEquals(1, vistas().size)
            }
        } finally {
            Intents.release()
        }
    }

    // --- traerlo y quitarlo -------------------------------------------------------

    @Test
    fun traer_el_llavero_lo_activa_y_lo_baja() {
        // 1. Al emparejar: el catálogo trae `[keychain]`, y la casilla «El
        //    llavero» (desmarcada) lo activa y hace su primera pasada.
        val v = Prdrive.volumen(contexto)
        v.raiz.deleteRecursively()
        Banco.remoto.deleteRecursively()
        catalogo(mapOf("base" to "personal.kdbx"))
        val bytes = baseEnElRemoto()
        ActivityScenario.launch(MainActivity::class.java).use {
            emparejarConLlavero()
            assertArrayEquals(bytes, baseLocal(v).readBytes())
            assertNotNull(v.leerConfig().parejaLlavero)
            compose.onNodeWithTag("empezar").performClick()
            esperar("fila-llavero", "al día")
        }

        // 2. Desde la principal: un teléfono sin llavero cuyo catálogo ya lo
        //    tiene. «Traer el llavero» lo relee, lo activa y trae la base.
        val (w, _) = Banco.volumen(Banco.pareja("notas"), catalogo = true)
        catalogo(mapOf("base" to "personal.kdbx"))
        Catalogo.pull(Adaptadores.catalogo(Prdrive.temporal(contexto)), loadRaw(w.config.readText()), w.state)
        val otra = baseEnElRemoto()
        ActivityScenario.launch(MainActivity::class.java).use {
            irAlLlavero()
            esperar("traer-llavero")
            assertNull(w.leerConfig().llavero)
            compose.onNodeWithTag("traer-llavero").performClick()
            esperar("confirmar-traer")
            compose.onNodeWithTag("confirmar-traer").performClick()
            esperar("llavero-estado", "al día", 120)
            compose.onNodeWithTag("error").assertDoesNotExist()
            assertArrayEquals(otra, baseLocal(w).readBytes())
            assertNotNull(w.leerConfig().parejaLlavero)

            // 3. Y quitarlo: el config lo pierde, la base se queda, y la fila
            //    vuelve a ofrecer traerlo.
            // Sin menú: el botón está en la sección, al final.
            esperar("quitar-llavero")
            compose.onNodeWithTag("quitar-llavero").performScrollTo()
            compose.onNodeWithTag("quitar-llavero").performClick()
            esperar("confirmar-quitar")
            compose.onNodeWithTag("confirmar-quitar").performClick()
            esperar("traer-llavero", 60)
            assertNull(w.leerConfig().llavero)
            assertArrayEquals(otra, baseLocal(w).readBytes())
        }
    }

    /**
     * Una pareja bisync de la raíz entera no se lleva `.keychain/` al
     * emparejar con «El llavero», ni la llave de uno sin contraseña: el
     * llavero se activa después de su `--resync` inicial, y desde entonces la
     * pareja lleva su regla (`REGLA_SIN_LLAVERO`).
     */
    @Test
    fun emparejar_con_el_llavero_no_sube_la_llave_por_una_pareja_de_la_raiz() {
        val v = Prdrive.volumen(contexto)
        v.raiz.deleteRecursively()
        Banco.remoto.deleteRecursively()
        val todo = Banco.pareja("todo", ".")
        catalogo(mapOf("base" to "personal.kdbx", "fichero_llave" to true, "llave_interna" to true), listOf(todo))
        File(Banco.remoto, "todo/del-pc.txt").writeText("del PC\n")
        val bytes = baseEnElRemoto()
        // El fichero llave lo da el selector de documentos (aquí, uno de la caché).
        val llave = File(contexto.cacheDir, "llave-de-prueba.keyx").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(android.net.Uri.fromFile(llave))))
            ActivityScenario.launch(MainActivity::class.java).use {
                emparejarConLlavero()
                esperar("resultado-todo", "hecha")
            }
        } finally {
            Intents.release()
        }
        val local = File(v.raiz, LLAVERO_LOCAL)
        assertArrayEquals(llave.readBytes(), File(local, LLAVERO_LLAVE).readBytes())
        assertArrayEquals(bytes, File(local, "personal.kdbx").readBytes())
        assertEquals("del PC\n", File(v.raiz, "del-pc.txt").readText())
        val subido = File(Banco.remoto, "todo/$LLAVERO_LOCAL")
        assertFalse(
            "la pareja de la raíz ha subido el llavero: ${subido.walkTopDown().map { it.name }.toList()}",
            subido.exists(),
        )
        // En el remoto del llavero, la base y su LEEME; la llave, nunca.
        assertFalse(File(remotoLlavero, LLAVERO_LLAVE).exists())
    }

    // --- abrirlo ------------------------------------------------------------------

    @Test
    fun abrir_el_llavero_pide_la_base_por_el_proveedor() {
        // Sin pasada reciente: «Abrir llavero» trae la base y después la abre.
        val (v, _) = Banco.volumen(Banco.pareja("notas"), llavero = "personal.kdbx")
        val bytes = baseEnElRemoto()
        assertFalse(LlaveroApp.reciente(contexto))
        val uri = DocumentsContract.buildDocumentUri(
            VolumenProvider.AUTORIDAD, "${VolumenProvider.ID_RAIZ}/$LLAVERO_LOCAL/personal.kdbx",
        )
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_VIEW)).respondWith(ActivityResult(Activity.RESULT_OK, null))
            ActivityScenario.launch(MainActivity::class.java).use {
                esperar("abrir-llavero")
                compose.onNodeWithTag("abrir-llavero").performClick()
                compose.waitUntil(120_000) { vistas().isNotEmpty() }
                assertArrayEquals("no ha traído la base antes de abrirla", bytes, baseLocal(v).readBytes())
                assertTrue(LlaveroApp.reciente(contexto))
                intended(
                    allOf(
                        hasAction(Intent.ACTION_VIEW),
                        hasData(uri),
                        // El tipo de las apps de KeePass, no el genérico: el selector sale solo con ellas.
                        hasType("application/x-kdbx"),
                        // Persistible, para que la app de KeePass pueda volver a abrirla desde sus
                        // recientes, y en su propia tarea, no encima de prdrive.
                        hasFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK,
                        ),
                    ),
                )
                compose.onNodeWithTag("aviso-llavero").assertDoesNotExist()
                compose.onNodeWithTag("error").assertDoesNotExist()
            }
        } finally {
            Intents.release()
        }
    }

    /** «Abrir llavero» de la sección Llavero hace lo mismo que el de Inicio: trae la base y la abre. */
    @Test
    fun abrir_llavero_desde_su_seccion_abre_la_base() {
        val (v, _) = Banco.volumen(Banco.pareja("notas"), llavero = "personal.kdbx")
        val bytes = baseEnElRemoto()
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_VIEW)).respondWith(ActivityResult(Activity.RESULT_OK, null))
            ActivityScenario.launch(MainActivity::class.java).use {
                irAlLlavero()
                esperar("abrir-llavero")
                compose.onNodeWithTag("abrir-llavero").performClick()
                compose.waitUntil(120_000) { vistas().isNotEmpty() }
                assertArrayEquals("no ha traído la base antes de abrirla", bytes, baseLocal(v).readBytes())
                intended(allOf(hasAction(Intent.ACTION_VIEW), hasData(documento("personal.kdbx")), hasType("application/x-kdbx")))
                compose.onNodeWithTag("error").assertDoesNotExist()
            }
        } finally {
            Intents.release()
        }
    }

    private fun documento(nombre: String) = DocumentsContract.buildDocumentUri(
        VolumenProvider.AUTORIDAD, "${VolumenProvider.ID_RAIZ}/$LLAVERO_LOCAL/$nombre",
    )

    /**
     * La llave de un llavero sin contraseña va como la lee KeePassDX (un
     * `ClipData` «keyFile» con su URI) y dirigida a su paquete; a cualquier
     * otra app, o con un selector delante (`android`), no va.
     */
    @Test
    fun la_llave_solo_va_a_keepassdx_y_dirigida_a_su_paquete() {
        val base = documento("personal.kdbx")
        val llave = documento(LLAVERO_LLAVE)
        val keepass = intentDeAbrir(base, TextosLlavero.TIPO_KEEPASS, persistible = true)
        for (paquete in TextosLlavero.PAQUETES_KEEPASSDX) {
            val con = conLaLlave(keepass, llave, paquete)
            assertEquals(paquete, con.`package`)
            assertEquals(Intent.ACTION_VIEW, con.action)
            assertEquals(base, con.data)
            assertEquals(TextosLlavero.TIPO_KEEPASS, con.type)
            assertEquals(keepass.flags, con.flags)
            val clip = con.clipData
            assertNotNull("sin la llave", clip)
            assertEquals("keyFile", clip!!.description.label.toString())
            assertEquals(1, clip.itemCount)
            assertEquals(llave, clip.getItemAt(0).uri)
        }
        for (paquete in listOf(null, "android", "keepass2android.keepass2android")) {
            val sin = conLaLlave(keepass, llave, paquete)
            assertNull("la llave ha ido a $paquete", sin.clipData)
            assertNull(sin.`package`)
        }
        assertNull(conLaLlave(keepass, null, TextosLlavero.PAQUETES_KEEPASSDX.first()).clipData)
        assertNull("ha cambiado el de entrada", keepass.clipData)
    }

    /**
     * «Abrir llavero» con un llavero sin contraseña y su llave: la llave va
     * con la base si Android la abriría con KeePassDX, y si no, no sale.
     */
    @Test
    fun abrir_un_llavero_sin_contrasena_da_la_llave_solo_a_keepassdx() {
        val (v, _) = Banco.volumen(Banco.pareja("notas"), llavero = "personal.kdbx")
        val raw = loadRaw(v.config.readText()).toMutableMap()
        raw["keychain"] = mapOf("base" to "personal.kdbx", "fichero_llave" to true, "llave_interna" to true)
        v.escribirConfig(raw, "# Para los tests.\n")
        File(File(v.raiz, LLAVERO_LOCAL).apply { mkdirs() }, LLAVERO_LLAVE).writeText("la llave\n")
        baseEnElRemoto()
        val r = LlaveroApp.pasar(contexto)
        assertEquals(r?.lineas?.joinToString("\n"), 0, r?.codigo)
        // Lo que resolvería Android en este dispositivo: con KeePassDX instalado
        // y por defecto, él; si no, nadie, otra app o el selector.
        val resuelve = contexto.packageManager.resolveActivity(
            intentDeAbrir(documento("personal.kdbx"), TextosLlavero.TIPO_KEEPASS, persistible = true),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        val conKeePassDx = resuelve in TextosLlavero.PAQUETES_KEEPASSDX
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_VIEW)).respondWith(ActivityResult(Activity.RESULT_OK, null))
            ActivityScenario.launch(MainActivity::class.java).use {
                esperar("abrir-llavero")
                compose.onNodeWithTag("abrir-llavero").performClick()
                compose.waitUntil(60_000) { vistas().isNotEmpty() }
                compose.onNodeWithTag("aviso-llavero").assertDoesNotExist()
                val vista = vistas().single()
                assertEquals(documento("personal.kdbx"), vista.data)
                if (conKeePassDx) {
                    assertEquals(resuelve, vista.`package`)
                    assertEquals(documento(LLAVERO_LLAVE), vista.clipData?.getItemAt(0)?.uri)
                } else {
                    assertNull("la llave ha salido hacia $resuelve", vista.clipData)
                    assertNull(vista.`package`)
                }
            }
        } finally {
            Intents.release()
        }
    }

    // --- lo que dice la fila de un freno ------------------------------------------

    /**
     * Lo que la fila dice del freno de borrados del llavero es lo de prdrive
     * (`frenos.EXPLICACION_LLAVERO`, del vector) hasta donde manda a
     * «Combinar», que el teléfono no tiene, y la frase del teléfono detrás.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun la_explicacion_del_freno_del_llavero_es_la_de_prdrive_sin_combinar() {
        val frenos = (Banco.vectores["llavero"] as Map<String, Any?>)["frenos"] as Map<String, Any?>
        val dePrdrive = (frenos["explicaciones"] as Map<String, Any?>)["llavero"].toString()
        assertTrue("prdrive ya no manda a «Combinar» así: $dePrdrive", dePrdrive.contains(Estado.MANDA_A_COMBINAR))
        val telefono = Estado.EXPLICACION_LLAVERO_TELEFONO
        assertTrue(telefono, telefono.startsWith(dePrdrive.substringBefore(Estado.MANDA_A_COMBINAR)))
        assertFalse(telefono, telefono.contains("«Combinar»"))
        assertTrue(telefono, telefono.endsWith(Avisos.COMBINAR_FUERA.substring(1)))
    }

    // --- las notificaciones -------------------------------------------------------

    @Test
    fun una_copia_de_conflicto_se_notifica_una_sola_vez() {
        val (v, config) = Banco.volumen(Banco.pareja("notas"), llavero = "personal.kdbx")
        val carpeta = File(v.raiz, LLAVERO_LOCAL).apply { mkdirs() }
        File(carpeta, "personal.kdbx").writeBytes(Banco.base(entera = true))
        File(carpeta, "personal.conflicto-remoto1.kdbx").writeBytes(Banco.otraBase())
        val conflictos = Conflictos.actualizarPareja(config.parejaLlavero!!, v.raiz, v.state)
        assertEquals(1, conflictos.size)

        // La primera vez, una notificación, con lo que dice prdrive y lo del teléfono.
        Avisos.trasPasada(contexto, conflictos)
        val una = delLlavero(1)
        assertEquals(1, una.size)
        assertEquals(enConflicto + Avisos.COMBINAR_FUERA, texto(una.single()))

        // La misma copia no se vuelve a avisar, aunque se haya quitado la notificación.
        notificaciones.cancelAll()
        Avisos.trasPasada(contexto, conflictos)
        assertEquals(0, delLlavero(0).size)

        // Sin copias, se olvida lo avisado: una copia con el mismo nombre se avisa otra vez.
        Avisos.trasPasada(contexto, emptyList())
        Avisos.trasPasada(contexto, conflictos)
        assertEquals(1, delLlavero(1).size)

        // Un guardado cortado, también, con el nombre de la base.
        notificaciones.cancelAll()
        Avisos.guardadoCortado(contexto, "personal.kdbx")
        val cortado = delLlavero(1)
        assertEquals(1, cortado.size)
        assertTrue(texto(cortado.single()), texto(cortado.single()).contains("personal.kdbx"))
    }
}
