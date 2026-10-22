package prdrive.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ActualizadorTest.kt — Lo que el [Actualizador] mira de un APK antes de
 * dárselo a Android, contra el APK de verdad de esta app: el paquete, la
 * versión y las firmas se leen igual del fichero que de la app instalada.
 *
 * Instalar no se prueba aquí: la versión nueva cerraría el proceso de la
 * prueba. Eso se prueba a mano, como dice AGENTS.md («La autoactualización»).
 */
@RunWith(AndroidJUnit4::class)
class ActualizadorTest {

    private val contexto = Banco.contexto
    private val propio = File(contexto.applicationInfo.sourceDir)

    @Test
    fun el_apk_de_la_app_se_lee_con_las_mismas_firmas_que_la_app_instalada() {
        val info = checkNotNull(Actualizador.leerApk(contexto.packageManager, propio)) { "Android no lee el APK de la app" }
        assertEquals(contexto.packageName, info.packageName)
        val firmas = Actualizador.firmas(info)
        assertTrue("sin firmas", firmas.isNotEmpty())
        assertEquals(Actualizador.firmas(Actualizador.instalada(contexto)), firmas)
    }

    @Test
    fun el_mismo_apk_no_se_pone_encima_porque_no_es_mas_nuevo() {
        val problema = Actualizador.problemaDelApk(contexto, propio, Actualizador.versionInstalada(contexto))
        assertTrue("$problema", problema!!.startsWith("no es más nueva"))
    }

    @Test
    fun un_apk_que_no_es_la_version_de_la_release_no_se_pone_encima() {
        val problema = Actualizador.problemaDelApk(contexto, propio, "99.0.0")
        assertTrue("$problema", problema!!.startsWith("dice ser la versión"))
    }

    @Test
    fun lo_que_no_es_un_apk_no_se_pone_encima() {
        val f = File(contexto.cacheDir, "no-es-un.apk").apply { writeText("hola") }
        try {
            assertEquals("Android no lo reconoce como una app.", Actualizador.problemaDelApk(contexto, f, "0.1.0"))
        } finally {
            f.delete()
        }
    }
}
