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
        assertEquals(Vectores.cadenas(Vectores.seccion("toml"), "tablas_conocidas"), TABLAS_CONOCIDAS)
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
    fun `se lee lo que lee tomllib y se rechaza lo que rechaza`() {
        // El veredicto y el valor los da tomllib (herramientas/vectores_python.py).
        // Lo que tomllib acepta y este lector no cubre (fechas, tablas en línea,
        // claves con punto…) se rechaza en voz alta: callar y seguir sería leer
        // media pareja, y leer otra cosa, peor.
        for (caso in Vectores.objetos(Vectores.seccion("python"), "toml_leer")) {
            val texto = Vectores.texto(caso, "texto")
            if (caso["tomllib_acepta"] == true && caso["soportado"] == true) {
                val leido = loadRaw(texto)
                assertEquals(caso["valor"], leido, reprPy(texto))
                // Los enteros, siempre Long: es lo que hace comparable un mapa
                // leído del TOML con otro leído del JSON.
                fun enteros(v: Any?): List<Any?> = when (v) {
                    is Map<*, *> -> v.values.flatMap { enteros(it) }
                    is List<*> -> v.flatMap { enteros(it) }
                    is Number -> if (v is Double) emptyList() else listOf(v)
                    else -> emptyList()
                }
                assertTrue(enteros(leido).all { it is Long }, "enteros que no son Long en ${reprPy(texto)}")
            } else {
                assertFailsWith<TomlError>("${reprPy(texto)} tenía que fallar") { loadRaw(texto) }
            }
        }
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
    fun `dumps no escribe una clave suelta de primer nivel, y dumpsChecked no deja que eso cuele`() {
        // Las tablas que no conoce las escribe al final (lo comprueban los
        // vectores, con `[keychain]` y dos inventadas), pero una clave suelta
        // de primer nivel no — igual que `config_file.py`. Callado, eso perdería
        // datos; lo que lo hace seguro es que `dumpsChecked` relee lo generado y
        // se niega, así que la pérdida se convierte en un error antes de tocar
        // el disco.
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
    fun `dumpsChecked se niega a escribir lo que el escritorio no podría leer`() {
        // `dumps` no escapa los caracteres de control (tampoco el de prdrive), y
        // tomllib no los admite: releer tiene que fallar antes de escribir.
        val raw = mapOf(
            "defaults" to mapOf("device_remote" to "disp", "exclude" to listOf("a\u0001b")),
            "pair" to listOf(mapOf("name" to "x", "local" to "a", "remote_path" to "/a")),
        )
        assertFailsWith<TomlError> { dumpsChecked(raw) }
    }
}
