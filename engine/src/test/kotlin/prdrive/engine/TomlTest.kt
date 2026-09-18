package prdrive.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TomlTest.kt — El serializador, contra el de prdrive y contra sí mismo.
 *
 * Tres cosas que comprobar, y las tres importan por separado:
 *
 *  1. Que **escribe** el mismo texto que `common/config_file.py` (vectores).
 *     Si no, el `sync_config.toml` del móvil y el del PC dejan de ser el mismo
 *     fichero, y el catálogo —que los dos escriben— sería un campo de minas.
 *  2. Que **lee** lo que él mismo escribe (round-trip), que es la garantía que
 *     `dumpsChecked` promete y de la que depende no subir un catálogo roto.
 *  3. Que lo que no entiende lo **rechaza en voz alta**, en vez de leerlo a
 *     medias: `tomllib` acepta más que este lector, así que un `pairs.toml`
 *     escrito a mano puede traer TOML legítimo que aquí no entre.
 */
class TomlTest {

    @Test
    fun `escribe el mismo texto que prdrive`() {
        for (caso in Vectores.objetos(Vectores.seccion("toml"), "dumps")) {
            val raw = Vectores.objeto(caso, "raw")
            val head = Vectores.texto(caso, "head")
            assertEquals(
                Vectores.texto(caso, "texto"),
                dumps(raw, head),
                "dumps() no reproduce el texto de prdrive",
            )
        }
    }

    @Test
    fun `una tabla suelta se escribe igual que en el editor de flags`() {
        for (caso in Vectores.objetos(Vectores.seccion("toml"), "dumps_table")) {
            val tabla = Vectores.objeto(caso, "tabla")
            assertEquals(
                Vectores.texto(caso, "texto"),
                dumpsTable(tabla),
                "dumpsTable($tabla)",
            )
        }
    }

    @Test
    fun `la cabecera de comentarios se recorta igual`() {
        for (caso in Vectores.objetos(Vectores.seccion("toml"), "header_of")) {
            val entrada = Vectores.texto(caso, "entrada")
            assertEquals(
                Vectores.texto(caso, "salida"),
                headerOf(entrada),
                "headerOf(«${entrada.replace("\n", "\\n")}»)",
            )
        }
    }

    @Test
    fun `el orden de las claves de una pareja es el de prdrive`() {
        assertEquals(Vectores.cadenas(Vectores.seccion("toml"), "pair_key_order"), PAIR_KEY_ORDER)
    }

    @Test
    fun `lo que escribe se vuelve a leer igual`() {
        // El round-trip es la promesa de dumpsChecked, y es lo que permite que
        // el fichero siga siendo editable a mano sin que la app lo estropee.
        for (caso in Vectores.objetos(Vectores.seccion("toml"), "dumps")) {
            val raw = Vectores.objeto(caso, "raw")
            val texto = Vectores.texto(caso, "texto")
            assertEquals(
                normalizar(raw),
                normalizar(loadRaw(texto)),
                "el TOML de prdrive no se relee igual",
            )
        }
    }

    @Test
    fun `dumpsChecked escribe cuando el round-trip cuadra`() {
        val resueltas = Vectores.seccion("resueltas")
        val raw = Vectores.objeto(resueltas, "config")
        val texto = dumpsChecked(raw, "# cabecera\n")
        assertEquals(normalizar(raw), normalizar(loadRaw(texto)))
        assertTrue(texto.startsWith("# cabecera\n"))
    }

    @Test
    fun `dumpsChecked se niega antes que escribir un config sin sentido`() {
        // Primero pasa por parseConfig: un config que no se puede ejecutar no se
        // escribe, aunque el serializador sepa escribirlo.
        assertFailsWith<ConfigError> { dumpsChecked(mapOf("defaults" to mapOf("remote" to "nas"))) }
    }

    @Test
    fun `los enteros escritos como Int y releídos como Long son el mismo config`() {
        // Sin `normalizar` esto se negaría a escribir un config perfectamente
        // bueno, que es el peor fallo posible aquí: negarse en falso.
        val raw = mapOf(
            "defaults" to mapOf("device_remote" to "disp", "flags" to mapOf("transfers" to 4)),
            "pair" to listOf(mapOf("name" to "x", "local" to "a", "remote_path" to "/a")),
        )
        assertTrue("transfers = 4" in dumpsChecked(raw))
    }

    @Test
    fun `pair punto flags se engancha a la última pareja escrita`() {
        // Es la trampa del formato: una subtabla después de la última [[pair]]
        // pertenece a ESA pareja. Por eso se escribe pegada a la suya, y por eso
        // hay que leerla igual.
        val raw = mapOf(
            "defaults" to mapOf("device_remote" to "disp"),
            "pair" to listOf(
                mapOf("name" to "uno", "local" to "a", "remote_path" to "/a"),
                mapOf(
                    "name" to "dos", "local" to "b", "remote_path" to "/b",
                    "flags" to mapOf("max-delete" to 9L),
                ),
            ),
        )
        val releido = loadRaw(dumps(raw))
        val parejas = parejasDe(releido)
        assertEquals(null, parejas[0]["flags"], "la primera pareja no lleva flags")
        assertEquals(mapOf("max-delete" to 9L), parejas[1]["flags"])
    }

    @Test
    fun `un array de varias líneas se lee entero`() {
        // Así los escribe dumps() en cuanto hay más de un elemento.
        val texto = "exclude = [\n    \"*.tmp\",\n    \"*.bak\",\n]\n"
        assertEquals(mapOf("exclude" to listOf("*.tmp", "*.bak")), loadRaw(texto))
    }

    @Test
    fun `un array vacío y uno de un solo elemento se leen como los escribe dumps`() {
        assertEquals(mapOf("a" to emptyList<Any?>()), loadRaw("a = []\n"))
        assertEquals(mapOf("a" to listOf("x")), loadRaw("a = [\"x\"]\n"))
    }

    @Test
    fun `una almohadilla dentro de una cadena no es un comentario`() {
        assertEquals(mapOf("a" to "con # dentro"), loadRaw("a = \"con # dentro\"  # y aquí sí\n"))
    }

    @Test
    fun `las barras y los saltos de línea sobreviven a la ida y a la vuelta`() {
        // El known_hosts del QR llega con saltos escapados, y una ruta de
        // Windows con barras invertidas: si el escape se pierde, el fichero que
        // se escribe en el móvil no es el que se leyó.
        val raw = mapOf(
            "defaults" to mapOf(
                "device_remote" to "disp",
                "flags" to mapOf(
                    "a" to "linea1\nlinea2",
                    "b" to "F:\\datos",
                    "c" to "con \"comillas\"",
                    "d" to "con\ttab",
                ),
            ),
            "pair" to listOf(mapOf("name" to "x", "local" to "a", "remote_path" to "/a")),
        )
        assertEquals(normalizar(raw), normalizar(loadRaw(dumps(raw))))
    }

    @Test
    fun `dumps solo escribe las cuatro secciones, y dumpsChecked no deja que eso cuele`() {
        // `dumps` ignora cualquier clave de primer nivel que no sea una de sus
        // secciones — igual que `config_file.py`, que escribe remote, defaults,
        // daemon y pair y nada más. Callado, eso perdería datos; lo que lo hace
        // seguro es que `dumpsChecked` relee lo generado y se niega, así que la
        // pérdida se convierte en un error antes de tocar el disco.
        val conIntrusa = mapOf(
            "defaults" to mapOf("device_remote" to "disp"),
            "pair" to listOf(mapOf("name" to "x", "local" to "a", "remote_path" to "/a")),
            "inventada" to "se cae",
        )
        assertTrue("inventada" !in dumps(conIntrusa), "dumps no escribe lo que no conoce")
        val e = assertFailsWith<TomlError> { dumpsChecked(conIntrusa) }
        assertTrue("no reproduce" in (e.message ?: ""), "mensaje: ${e.message}")
    }

    @Test
    fun `lo que no se entiende se rechaza en voz alta`() {
        // Cada uno de estos es TOML que tomllib aceptaría o casi, y que este
        // lector no cubre. Callar y seguir sería leer media pareja.
        val malos = mapOf(
            "sin igual" to "clave sin igual\n",
            "clave duplicada" to "a = 1\na = 2\n",
            "cabecera sin cerrar" to "[defaults\n",
            "cabecera vacía" to "[]\n",
            "valor que no existe" to "a = 1979-05-27\n",
            "cadena sin cerrar" to "a = \"abierta\n",
            "array sin cerrar" to "a = [1, 2\n",
            "pair punto flags sin pareja" to "[pair.flags]\na = 1\n",
            "array de tablas anidado" to "[[a.b]]\nc = 1\n",
            "tabla sobre un escalar" to "a = 1\n[a]\nb = 2\n",
        )
        for ((caso, texto) in malos) {
            assertFailsWith<TomlError>("«$caso» tenía que fallar") { loadRaw(texto) }
        }
    }

    @Test
    fun `los enteros llegan siempre como Long`() {
        // Es lo que hace comparables un mapa leído del TOML y otro del JSON de
        // los vectores, y lo que hace que normalizar() tenga un destino claro.
        val leido = loadRaw("a = 1\nb = -2\nc = 1_000\nd = 1.5\ne = true\n")
        assertEquals(1L, leido["a"])
        assertEquals(-2L, leido["b"])
        assertEquals(1000L, leido["c"])
        assertEquals(1.5, leido["d"])
        assertEquals(true, leido["e"])
    }
}
