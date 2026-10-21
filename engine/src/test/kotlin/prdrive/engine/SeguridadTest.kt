package prdrive.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SeguridadTest.kt — Lo que el config no puede llevar, dicho con las palabras
 * de prdrive.
 *
 * El catálogo llega del remoto y lo escriben otros. Lo que el escritorio
 * rechaza porque acabaría en la línea de órdenes de rclone (un flag que lanza
 * un programa, un remote que es una cadena de conexión, un `local` que sale del
 * volumen) la app lo tiene que rechazar igual: si aquí colara, el mismo
 * `remote.toml` sería peligroso en el teléfono y no en el PC. Y con el mismo
 * mensaje, letra a letra, porque es lo que lee quien tiene que arreglarlo.
 */
class SeguridadTest {

    private val seguridad get() = Vectores.seccion("seguridad")

    /** `null` en el JSON es «vale»; cualquier texto es el motivo. */
    private fun motivo(caso: Map<String, Any?>): String? = caso["salida"]?.toString()

    @Test
    fun `los flags que pone la pasada y los que escriben ficheros son los mismos`() {
        assertEquals(Vectores.objeto(seguridad, "flags_reservados"), FLAGS_RESERVADOS)
        assertEquals(Vectores.objeto(seguridad, "flags_escriben"), FLAGS_ESCRIBEN)
        assertEquals(Vectores.texto(seguridad, "quitalo"), QUITALO)
    }

    @Test
    fun `un flag se rechaza por las mismas razones`() {
        for (caso in Vectores.objetos(seguridad, "problema_flag")) {
            val entrada = Vectores.texto(caso, "entrada")
            assertEquals(motivo(caso), problemaFlag(entrada), "problemaFlag(«$entrada»)")
        }
    }

    @Test
    fun `los argumentos sueltos y los valores de un flag no esconden otro flag`() {
        for (caso in Vectores.objetos(seguridad, "problema_extra")) {
            val entrada = Vectores.cadenas(caso, "entrada")
            assertEquals(motivo(caso), problemaExtra(entrada), "problemaExtra($entrada)")
        }
        for (caso in Vectores.objetos(seguridad, "problema_valor_flag")) {
            val entrada = caso["entrada"]
            assertEquals(motivo(caso), problemaValorFlag(entrada), "problemaValorFlag($entrada)")
        }
    }

    @Test
    fun `un remote que es una cadena de conexión o una unidad no vale`() {
        for (caso in Vectores.objetos(seguridad, "problema_remote")) {
            val entrada = caso["entrada"]
            val clave = caso["clave"]?.toString() ?: "remote"
            assertEquals(motivo(caso), problemaRemote(entrada, clave), "problemaRemote(«$entrada»)")
        }
    }

    @Test
    fun `un nombre de pareja que saldría de state o de filters no vale`() {
        for (caso in Vectores.objetos(seguridad, "problema_nombre")) {
            val entrada = caso["entrada"]
            assertEquals(motivo(caso), problemaNombre(entrada), "problemaNombre(«$entrada»)")
        }
    }

    @Test
    fun `un local que sale del volumen o cae en la carpeta del programa no vale`() {
        // La carpeta del programa del volumen es la misma que la de un
        // dispositivo: el generador se lo hace creer a prdrive.
        assertEquals(Vectores.texto(Vectores.raiz, "carpeta_programa"), CARPETA_PROGRAMA)
        for (caso in Vectores.objetos(seguridad, "problema_local")) {
            val entrada = Vectores.texto(caso, "entrada")
            assertEquals(motivo(caso), problemaLocal(entrada), "problemaLocal(«$entrada»)")
        }
        for (caso in Vectores.objetos(seguridad, "normalizar_local")) {
            val entrada = Vectores.texto(caso, "entrada")
            assertEquals(Vectores.texto(caso, "salida"), normalizarLocal(entrada))
        }
    }

    @Test
    fun `un config entero se acepta o se rechaza con el mismo mensaje`() {
        for (caso in Vectores.objetos(seguridad, "configs")) {
            val nombre = Vectores.texto(caso, "caso")
            val esperado = caso["error"]?.toString()
            val config = Vectores.objeto(caso, "config")
            val obtenido = try {
                parseConfig(config)
                null
            } catch (e: ConfigError) {
                e.message
            } catch (e: Exception) {
                fail("«$nombre»: tenía que ser un ConfigError y es ${e::class.simpleName}: ${e.message}")
            }
            if (caso["rompe"] != null) {
                // prdrive se cae (con un ${caso["rompe"]}): no hay mensaje que
                // copiar, pero el motor tiene que negarse con un ConfigError.
                assertTrue(obtenido != null, "«$nombre»: prdrive se cae y el motor lo deja pasar")
            } else {
                assertEquals(esperado, obtenido, "«$nombre»")
            }
        }
    }
}
