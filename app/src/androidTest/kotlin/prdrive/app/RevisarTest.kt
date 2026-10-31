package prdrive.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.app.ui.Destino
import prdrive.app.ui.inicio.revisar
import prdrive.app.ui.piezas.TonoAviso
import prdrive.engine.Conflictos
import prdrive.engine.Pasada
import prdrive.engine.Resultados

/**
 * RevisarTest.kt — Lo que Inicio cuenta como «cosas que revisar», con filas
 * hechas a mano: qué va primero, a dónde lleva cada una y qué no se vuelve a
 * pedir. No toca el volumen ni rclone.
 */
@RunWith(AndroidJUnit4::class)
class RevisarTest {

    private fun fila(
        nombre: String,
        fallo: Resultados.Fallo? = null,
        pideResync: List<String> = emptyList(),
        conflictos: Int = 0,
    ) = Estado.Fila(
        nombre = nombre,
        modo = "bisync",
        local = nombre,
        ultimaBuena = null,
        fallo = fallo,
        explicacion = null,
        pideResync = pideResync,
        conflictos = List(conflictos) { i ->
            Conflictos.Conflicto(nombre, File("/v/$nombre"), File("/v/$nombre/nota$i.md"), emptyList())
        },
    )

    private fun fallo(nombre: String) = Resultados.Fallo(nombre, "2026-10-30 10:00:00", BigInteger.ONE, null)

    @Test
    fun una_pareja_que_fallo_va_la_primera_y_lleva_a_su_ficha() {
        val cosas = revisar(
            listOf(fila("docs", pideResync = listOf("no hay listados")), fila("fotos", fallo = fallo("fotos"))),
            llavero = null,
            resultados = emptyMap(),
        )
        assertEquals(2, cosas.size)
        assertEquals(TonoAviso.PELIGRO, cosas[0].tono)
        assertEquals(Destino.Ficha("fotos"), cosas[0].destino)
        assertEquals(TonoAviso.AVISO, cosas[1].tono)
        assertEquals(Destino.Ficha("docs"), cosas[1].destino)
    }

    @Test
    fun un_resync_aprobado_en_esta_sesion_no_se_pide_otra_vez() {
        val cosas = revisar(
            listOf(fila("docs", pideResync = listOf("no hay listados"))),
            llavero = null,
            resultados = mapOf("docs" to Pasada.Resultado("docs", 0, emptyList())),
        )
        assertTrue("sigue pidiéndolo: $cosas", cosas.isEmpty())
    }

    @Test
    fun uno_o_varios_conflictos_se_cuentan_bien() {
        assertEquals("1 fichero en conflicto en «vault»", revisar(listOf(fila("vault", conflictos = 1)), null, emptyMap()).single().titulo)
        assertEquals("3 ficheros en conflicto en «vault»", revisar(listOf(fila("vault", conflictos = 3)), null, emptyMap()).single().titulo)
    }

    @Test
    fun sin_nada_que_revisar_la_lista_esta_vacia() {
        assertTrue(revisar(listOf(fila("docs"), fila("fotos")), null, emptyMap()).isEmpty())
    }
}
