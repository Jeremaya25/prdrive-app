package prdrive.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JsonTest.kt — El JSON del motor contra el de Go.
 *
 * Lo que se comprueba aquí no es «¿sabe leer JSON?», que se vería enseguida:
 * es que lo que **escribe** salga letra por letra como lo escribe
 * `encoding/json`. De eso depende que la comparación de `SesionesTest` —el
 * JSON del motor contra el que rclone aceptó de verdad— signifique algo, y el
 * último test de este fichero la ataca por el otro lado: relee las cadenas que
 * escribió Go y las vuelve a escribir.
 */
class JsonTest {

    @Test
    fun `las claves de un objeto salen ordenadas, como en Go`() {
        // Un mapa de Go no tiene orden, así que `encoding/json` las ordena. Si
        // el motor las dejara en el orden de inserción, dos JSON con el mismo
        // contenido no serían el mismo texto.
        assertEquals(
            """{"a":1,"b":2,"z":3}""",
            escribirJson(linkedMapOf("z" to 3L, "a" to 1L, "b" to 2L)),
        )
    }

    @Test
    fun `un entero no lleva parte decimal aunque llegue como Double`() {
        // Go escribe `4` para un float64 que vale 4; Kotlin escribiría "4.0",
        // y un `maxDelete: 25.0` no es lo que rclone espera leer.
        assertEquals("4", escribirJson(4L))
        assertEquals("4", escribirJson(4.0))
        assertEquals("4.5", escribirJson(4.5))
        assertEquals("-1", escribirJson(-1))
    }

    @Test
    fun `se escapa lo que obliga el JSON y nada más`() {
        // Lo que NO se escapa es igual de importante: `json.Marshal` convierte
        // `<`, `>` y `&` en < y compañía, y el spike escribe con
        // SetEscapeHTML(false) para que los dos textos coincidan.
        assertEquals("""["a&b<c>d"]""", escribirJson(listOf("a&b<c>d")))
        // Aquí van con escapes de Kotlin y no entre triples comillas: una
        // comilla final pegada al cierre de un raw string no se puede escribir.
        assertEquals("\"di\\\"cho\\\\\"", escribirJson("di\"cho\\"))
        assertEquals("\"uno\\ndos\\ttres\"", escribirJson("uno\ndos\ttres"))
        // Go escribe los demás de control como \u00xx en minúsculas, y no usa
        // \b ni \f.
        assertEquals("\"\\u0008\\u000c\\u0001\"", escribirJson("\b\u000C\u0001"))
        // Y estos dos siempre, porque rompen JavaScript.
        assertEquals("\"\\u2028\\u2029\"", escribirJson("  "))
        // Las tildes NO se escapan: el JSON es UTF-8.
        assertEquals("\"añoño\"", escribirJson("añoño"))
    }

    @Test
    fun `un valor que no sabe escribir es un error y no un toString de casualidad`() {
        // Lo que se le manda a rclone no puede quedar en manos del toString de
        // una clase cualquiera: saldría una cadena con pinta de válida.
        val e = assertFailsWith<JsonError> { escribirJson(mapOf("x" to Regex("a"))) }
        assertTrue("Regex" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `dos claves iguales en el mismo objeto se rechazan`() {
        // Solo puede pasar mezclando claves que no son cadenas, y el resultado
        // sería un JSON con una clave repetida: rclone se quedaría con una de
        // las dos y no se sabría cuál.
        assertFailsWith<JsonError> { escribirJson(mapOf(1 to "a", "1" to "b")) }
    }

    @Test
    fun `lo que se escribe se vuelve a leer igual`() {
        val dato = mapOf(
            "texto" to "con \"comillas\" y \\ y salto\n",
            "entero" to 25L,
            "decimal" to 1.5,
            "si" to true,
            "no" to false,
            "nada" to null,
            "lista" to listOf("a", 1L, true, null),
            "dentro" to mapOf("hondo" to mapOf("mas" to listOf<Any?>())),
        )
        assertEquals(dato.toSortedMap().toString(), ordenar(leerJson(escribirJson(dato))).toString())
    }

    @Test
    fun `un JSON ilegible dice dónde se ha atascado`() {
        val e = assertFailsWith<JsonError> { leerJson("""{"a": }""") }
        assertTrue("posición" in e.message.orEmpty(), e.message.orEmpty())
        assertFailsWith<JsonError> { leerJson("""{"a": 1} sobra""") }
        assertFailsWith<JsonError> { leerJson("""{"a" 1}""") }
        assertFailsWith<JsonError> { leerObjetoJson("""[1,2]""") }
    }

    @Test
    fun `lo que escribió Go se reescribe igual, carácter a carácter`() {
        // La prueba de fuego, y no es un caso inventado: estas cadenas las
        // escribió `encoding/json` y rclone las aceptó (son las llamadas de
        // las que salieron las sesiones de `sesiones.json`). Si el motor las
        // relee y las vuelve a escribir exactamente igual, entonces escribe
        // como Go para todo lo que el RPC necesita.
        assertTrue(Sesiones.casos.isNotEmpty(), "sesiones.json sin casos")
        for (caso in Sesiones.casos) {
            for (clave in listOf("rpc_resync", "rpc_pasada")) {
                val deGo = Sesiones.texto(caso, clave)
                assertEquals(deGo, escribirJson(leerJson(deGo)), "$clave de ${caso["pareja"]}")
            }
        }
    }

    /** Un mapa con sus claves ordenadas a cualquier profundidad, para comparar. */
    private fun ordenar(valor: Any?): Any? = when (valor) {
        is Map<*, *> -> valor.entries
            .sortedBy { it.key.toString() }
            .associate { it.key.toString() to ordenar(it.value) }
            .toSortedMap()
        is List<*> -> valor.map { ordenar(it) }
        else -> valor
    }
}
