package prdrive.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import org.hamcrest.Matchers.allOf
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import prdrive.app.documentos.VolumenProvider
import prdrive.app.segundoplano.SegundoPlano
import androidx.activity.compose.setContent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.ui.Modelo
import prdrive.app.ui.Seccion
import prdrive.app.ui.Tema
import prdrive.app.ui.ajustes.Ajustes

/**
 * AjustesTest.kt — La sección Ajustes: que «Buscar actualizaciones» viva aquí
 * (y ya no en Inicio) y que cada permiso diga si está dado o cómo darlo, también
 * después de volver de los ajustes de Android.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class AjustesTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun hay(etiqueta: String) = compose.onAllNodes(hasTestTag(etiqueta)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun buscar_actualizaciones_esta_en_ajustes() {
        Banco.volumen(Banco.pareja("uno"))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(30_000) { hay("pantalla-inicio") }
            assertFalse("sigue en Inicio", hay("buscar-version"))
            compose.onNodeWithTag(Seccion.AJUSTES.etiqueta).performClick()
            compose.waitUntil(10_000) { hay("pantalla-ajustes") }
            assertTrue("no está en Ajustes", hay("buscar-version"))
        }
    }

    /** «En segundo plano» programa el trabajo de «Sincronizar sola», e Inicio dice cada cuánto. */
    @Test
    fun sincronizar_sola_se_activa_en_ajustes_e_inicio_lo_dice() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            Banco.contexto,
            androidx.work.Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        SegundoPlano.guardar(Banco.contexto, SegundoPlano.Ajustes(), intervaloMin = 60)
        Banco.volumen(Banco.pareja("uno"))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(30_000) { hay("pantalla-inicio") }
                assertFalse(hay("proxima"))
                compose.onNodeWithTag(Seccion.AJUSTES.etiqueta).performClick()
                compose.waitUntil(10_000) { hay("segundo-plano") }
                compose.onNodeWithTag("segundo-plano").performClick()
                compose.waitUntil(10_000) { SegundoPlano.leer(Banco.contexto).activo }
                compose.waitUntil(10_000) {
                    WorkManager.getInstance(Banco.contexto).getWorkInfosForUniqueWork(SegundoPlano.NOMBRE).get()
                        .any { !it.state.isFinished }
                }
                compose.onNodeWithTag(Seccion.INICIO.etiqueta).performClick()
                compose.waitUntil(10_000) { hay("proxima") }
            }
        } finally {
            SegundoPlano.guardar(Banco.contexto, SegundoPlano.Ajustes(), intervaloMin = 60)
        }
    }

    /** «Ver en Archivos» abre la raíz del volumen en la app de Archivos del sistema. */
    @Test
    fun ver_en_archivos_abre_el_volumen() {
        Banco.volumen(Banco.pareja("uno"))
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(30_000) { hay("pantalla-inicio") }
                compose.onNodeWithTag(Seccion.AJUSTES.etiqueta).performClick()
                compose.waitUntil(10_000) { hay("ver-en-archivos") }
                compose.onNodeWithTag("ver-en-archivos").performScrollTo().performClick()
                intended(
                    allOf(
                        hasAction(Intent.ACTION_VIEW),
                        hasData(DocumentsContract.buildRootUri(VolumenProvider.AUTORIDAD, VolumenProvider.ID_RAIZ)),
                        hasType(DocumentsContract.Root.MIME_TYPE_ITEM),
                    ),
                )
            }
        } finally {
            Intents.release()
        }
    }

    /**
     * Tras dos negativas Android ya no pregunta, y pedir el permiso no hace
     * nada: la fila lo sabe y lleva a los ajustes de la app. Se finge como el de
     * arriba.
     */
    @Test
    fun si_android_ya_no_pregunta_la_red_local_la_fila_abre_los_ajustes() {
        if (android.os.Build.VERSION.SDK_INT < 37) return
        Banco.volumen(Banco.pareja("uno"))
        Intents.init()
        try {
            intending(hasAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            ActivityScenario.launch(ComponentActivity::class.java).use { escena ->
                escena.onActivity { actividad ->
                    val modelo = ViewModelProvider(actividad)[Modelo::class.java]
                    actividad.setContent {
                        Tema { Ajustes(modelo, faltaRedLocal = { true }, yaNoPregunta = { _, _ -> true }) }
                    }
                }
                compose.waitUntil(10_000) { hay("permiso-red-local") }
                compose.onNode(hasText("Abrir ajustes") and hasAnyAncestor(hasTestTag("permiso-red-local")))
                    .performScrollTo().performClick()
                intended(
                    allOf(
                        hasAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
                        hasData(Uri.fromParts("package", Banco.contexto.packageName, null)),
                    ),
                )
            }
        } finally {
            Intents.release()
        }
    }

    /**
     * El permiso de red local se finge: quitarlo de verdad
     * (`revokeRuntimePermission`) mata el proceso de la app, que es también el
     * del test. Lo que se prueba es que la fila lo diga y lo relea al volver.
     */
    @Test
    fun el_permiso_de_red_local_se_ve_dado_o_por_dar() {
        // Antes de Android 17 no hay permiso de red local que enseñar. Un `return` y no
        // `assumeTrue`: el motor de tests de AGP cuenta un test saltado como fallido.
        if (android.os.Build.VERSION.SDK_INT < 37) return
        Banco.volumen(Banco.pareja("uno"))
        var falta = true
        ActivityScenario.launch(ComponentActivity::class.java).use { escena ->
            escena.onActivity { actividad ->
                val modelo = ViewModelProvider(actividad)[Modelo::class.java]
                actividad.setContent { Tema { Ajustes(modelo, faltaRedLocal = { falta }) } }
            }
            compose.waitUntil(10_000) { hay("permiso-red-local") }
            val fila = hasTestTag("permiso-red-local")
            val dice = { texto: String ->
                compose.onAllNodes((fila or hasAnyAncestor(fila)) and hasText(texto, substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue("no ofrece darlo", dice("Dar"))
            falta = false
            escena.moveToState(Lifecycle.State.STARTED)
            escena.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { dice("dado") }
        }
    }
}
