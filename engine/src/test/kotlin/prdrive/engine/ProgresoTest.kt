package prdrive.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ProgresoTest.kt — La frase que lee el usuario, contra la del PC.
 *
 * El progreso en la app sale de `core/stats` y en el escritorio de leer el
 * log, así que el canal no se puede comparar. Lo que sí se compara —y es lo
 * que se ve— es el **texto**: los tamaños con su coma decimal y sus
 * redondeos, y la frase entera. Todos los valores esperados salen de
 * `vectores.json`, o sea del `common/progress.py` de verdad.
 */
class ProgresoTest {

    private val vec = Vectores.seccion("progreso")

    @Test
    fun `la etiqueta de la línea de progreso es la misma`() {
        // La pantalla la busca para reescribir la línea en su sitio; si se
        // separan, no se queda sin color: se llena de cientos de líneas.
        assertEquals(Vectores.texto(vec, "etiqueta"), ETIQUETA_PROGRESO)
    }

    @Test
    fun `los tamaños se escriben como en el PC, redondeo incluido`() {
        // El caso que importa es el empate: 1280 octetos son 1,25 KB exactos,
        // y ahí Python redondea a la cifra par y String.format hacia arriba.
        // Sin esto, el móvil y el PC dirían números distintos de la misma
        // pasada y nadie lo vería venir.
        val casos = Vectores.objetos(vec, "tamanos")
        assertTrue(casos.size > 8, "pocos tamaños en los vectores: ${casos.size}")
        for (caso in casos) {
            val octetos = caso["octetos"] as Long
            assertEquals(Vectores.texto(caso, "texto"), Progresos.tamano(octetos), "$octetos octetos")
        }
    }

    @Test
    fun `la frase entera es la del PC`() {
        for (caso in Vectores.objetos(vec, "textos")) {
            val progreso = Progreso(
                hecho = caso["hecho"] as Long,
                total = caso["total"] as Long,
                porcentaje = (caso["porcentaje"] as Long?)?.toInt(),
                velocidad = (caso["velocidad"] as Number).toDouble(),
            )
            assertEquals(Vectores.texto(caso, "texto"), progreso.texto(), "$progreso")
        }
    }

    @Test
    fun `el lector de líneas acepta y rechaza lo mismo`() {
        // Se conserva porque la cola del log que se enseña cuando algo falla
        // va sin estadísticas, y un `stats` escrito a mano en el TOML las
        // traería. Rechazar es la mitad importante: una línea cortada a media
        // escritura, la cuenta de ficheros y la de un fichero suelto no son
        // progreso.
        for (caso in Vectores.objetos(vec, "lineas")) {
            val linea = Vectores.texto(caso, "linea")
            val leido = Progresos.leer(linea)
            val esperado = caso["leido"]
            if (esperado == null) {
                assertNull(leido, "esta línea no debería dar progreso: $linea")
            } else {
                @Suppress("UNCHECKED_CAST")
                val e = esperado as Map<String, Any?>
                assertTrue(leido != null, "esta línea sí debería dar progreso: $linea")
                assertEquals(e["hecho"] as Long, leido.hecho, "hecho de: $linea")
                assertEquals(e["total"] as Long, leido.total, "total de: $linea")
                assertEquals((e["porcentaje"] as Long?)?.toInt(), leido.porcentaje, linea)
                assertEquals((e["velocidad"] as Number).toDouble(), leido.velocidad, 0.0, linea)
            }
        }
    }

    @Test
    fun `de un trozo de log vale la última lectura`() {
        // Cada estadística cuenta desde el principio de la pasada, así que la
        // última es la verdad aunque diga menos que las de antes o llegue
        // detrás de un error.
        val caso = Vectores.objeto(vec, "ultimo")
        val leido = Progresos.ultimo(Vectores.texto(caso, "texto"))
        @Suppress("UNCHECKED_CAST")
        val esperado = caso["leido"] as Map<String, Any?>
        assertTrue(leido != null)
        assertEquals(esperado["hecho"] as Long, leido.hecho)
        assertEquals(esperado["total"] as Long, leido.total)
        assertEquals((esperado["porcentaje"] as Long?)?.toInt(), leido.porcentaje)
    }

    // -----------------------------------------------------------------------
    // Y la parte que el escritorio no tiene: las estadísticas como datos
    // -----------------------------------------------------------------------

    @Test
    fun `core-stats da el mismo porcentaje que rclone escribiría`() {
        // `percent()` de rclone (fs/accounting/stats.go) redondea con +0.5, no
        // trunca: 1 de 3 es 33 % y 2 de 3 es 67 %.
        assertEquals(33, Progresos.deStats(stats(1, 3))?.porcentaje)
        assertEquals(67, Progresos.deStats(stats(2, 3))?.porcentaje)
        assertEquals(100, Progresos.deStats(stats(3, 3))?.porcentaje)
    }

    @Test
    fun `sin total no hay porcentaje, y sin nada no hay progreso`() {
        // Al principio de una pasada rclone no sabe todavía cuánto hay que
        // mover, y «0 B de 0 B» no informa de nada: mejor no enseñar línea.
        assertNull(Progresos.deStats(stats(0, 0)))
        assertNull(Progresos.deStats(emptyMap()))
        val sinTotal = Progresos.deStats(stats(512, 0))
        assertTrue(sinTotal != null)
        assertNull(sinTotal.porcentaje)
        assertEquals(512L, sinTotal.hecho)
    }

    @Test
    fun `la velocidad llega como decimal y se queda como decimal`() {
        // `speed` de core/stats es un float, y el JSON lo trae como Double: si
        // se leyera como entero, una pasada lenta diría «0 B/s».
        val p = Progresos.deStats(mapOf("bytes" to 10L, "totalBytes" to 100L, "speed" to 1536.5))
        assertEquals(1536.5, p?.velocidad)
        assertTrue(Progresos.linea(p!!).startsWith("  $ETIQUETA_PROGRESO "))
    }

    private fun stats(hecho: Long, total: Long): Map<String, Any?> =
        mapOf("bytes" to hecho, "totalBytes" to total, "speed" to 0.0)
}
