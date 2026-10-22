package prdrive.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CatalogoTest.kt — El catálogo, con el mismo rclone de mentira que se le puso
 * delante a prdrive.
 *
 * Cada escenario de los vectores trae la tabla de respuestas de ese rclone
 * (qué contesta a cada `cat` y a cada `lsjson --stat`) y lo que dijo prdrive:
 * qué fichero leyó, qué catálogo devolvió o con qué palabras se negó, qué dejó
 * en la copia local y qué dice `load()` cuando después se va la red. El motor,
 * con la misma tabla, tiene que decir lo mismo.
 */
class CatalogoTest {

    private val catalogo get() = Vectores.seccion("catalogo")
    private val temporales = ArrayList<File>()

    private fun temporal(): File =
        Files.createTempDirectory("prdrive-catalogo").toFile().also { temporales.add(it) }

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
    }

    /** El rclone de mentira de un escenario: una tabla de respuestas. */
    private fun rcloneFalso(tabla: Map<String, Any?>): Catalogo.Ejecutar {
        val respuestas = Vectores.objeto(tabla, "respuestas")
        val defecto = Vectores.lista(tabla, "por_defecto")
        return Catalogo.Ejecutar { args ->
            val r = respuestas[args.joinToString(" ")] as? List<*> ?: defecto
            Catalogo.Resultado((r[0] as Long).toInt(), r[1].toString(), r[2].toString())
        }
    }

    private fun relojDe(sellos: List<String>): () -> String {
        var n = 0
        return { sellos[n++ % sellos.size] }
    }

    @Test
    fun `los nombres y las constantes del catálogo son los de prdrive`() {
        assertEquals(Vectores.texto(catalogo, "fichero"), Catalogo.FICHERO)
        assertEquals(Vectores.texto(catalogo, "fichero_anterior"), Catalogo.FICHERO_ANTERIOR)
        assertEquals(Vectores.cadenas(catalogo, "nombres"), Catalogo.NOMBRES)
        assertEquals(
            Vectores.lista(catalogo, "rc_no_existe").map { (it as Long).toInt() }.toSet(),
            Catalogo.RC_NO_EXISTE,
        )
        assertEquals(Vectores.cadenas(catalogo, "net_flags"), Catalogo.NET_FLAGS)
        assertEquals(Vectores.texto(catalogo, "sin_fecha"), Catalogo.SIN_FECHA)
    }

    @Test
    fun `dónde se busca el catálogo sale igual`() {
        for (caso in Vectores.objetos(catalogo, "endpoint")) {
            @Suppress("UNCHECKED_CAST")
            val raw = caso["raw_local"] as Map<String, Any?>?
            assertEquals(Vectores.texto(caso, "salida"), Catalogo.endpoint(raw), "endpoint($raw)")
        }
        for (caso in Vectores.objetos(catalogo, "partir")) {
            val entrada = Vectores.texto(caso, "entrada")
            val esperado = Vectores.cadenas(caso, "salida")
            assertEquals(esperado[0] to esperado[1], Catalogo.partir(entrada), "partir($entrada)")
        }
        for (caso in Vectores.objetos(catalogo, "candidatos")) {
            val entrada = Vectores.texto(caso, "entrada")
            assertEquals(Vectores.cadenas(caso, "salida"), Catalogo.candidatos(entrada), "candidatos($entrada)")
        }
        for (caso in Vectores.objetos(catalogo, "dentro")) {
            val args = Vectores.cadenas(caso, "entrada")
            val obtenido = if (args.size == 1) Catalogo.dentro(args[0]) else Catalogo.dentro(args[0], args[1])
            assertEquals(Vectores.texto(caso, "salida"), obtenido, "dentro($args)")
        }
        for (caso in Vectores.objetos(catalogo, "problema_de_ruta")) {
            val entrada = Vectores.texto(caso, "entrada")
            assertEquals(caso["salida"], Catalogo.problemaDeRuta(entrada), "problemaDeRuta(«$entrada»)")
        }
    }

    @Test
    fun `cada escenario se lee, se cachea y se explica como en el escritorio`() {
        val sellos = Vectores.cadenas(Vectores.raiz, "sellos")
        for (caso in Vectores.objetos(catalogo, "escenarios")) {
            val nombre = Vectores.texto(caso, "caso")
            @Suppress("UNCHECKED_CAST")
            val rawLocal = caso["raw_local"] as Map<String, Any?>?
            val rclone = rcloneFalso(Vectores.objeto(caso, "rclone"))
            val state = temporal()

            assertEquals(Vectores.texto(caso, "endpoint"), Catalogo.endpoint(rawLocal), "«$nombre»: endpoint")
            val leer = Vectores.objeto(caso, "leer")
            val (res, cual) = Catalogo.leer(rclone, Catalogo.endpoint(rawLocal))
            assertEquals(Vectores.texto(leer, "cual"), cual, "«$nombre»: qué fichero se lee")
            assertEquals((leer["codigo"] as Long).toInt(), res.codigo, "«$nombre»: código")

            val pull = Vectores.objeto(caso, "pull")
            val obtenido = try {
                Catalogo.pull(rclone, rawLocal, state, relojDe(sellos))
            } catch (e: ConfigError) {
                val esperado = Vectores.texto(pull, "error")
                if (pull["detalle_del_parser"] == true) {
                    // El detalle es del lector de TOML, que no dice lo mismo que tomllib.
                    assertTrue(e.message!!.startsWith(esperado), "«$nombre»: ${e.message}")
                } else {
                    assertEquals(esperado, e.message, "«$nombre»: el motivo")
                }
                null
            }
            if (obtenido != null) {
                assertNull(pull["error"], "«$nombre»: prdrive se negó y el motor no")
                assertEquals(Vectores.texto(pull, "text"), obtenido.text, "«$nombre»: texto")
                assertEquals(Vectores.texto(pull, "source"), obtenido.source)
                assertEquals(Vectores.texto(pull, "stamp"), obtenido.stamp)
                assertEquals(Vectores.texto(pull, "endpoint"), obtenido.endpoint, "«$nombre»: endpoint leído")
                assertEquals(Vectores.cadenas(pull, "names"), obtenido.names, "«$nombre»: parejas")
                assertEquals(normalizar(Vectores.objeto(pull, "defaults")), normalizar(obtenido.defaults))
            }

            val copia = Vectores.objeto(caso, "copia")
            val toml = Catalogo.cacheToml(state)
            val json = Catalogo.cacheMeta(state)
            assertEquals(copia["toml"], if (toml.exists()) toml.readText() else null, "«$nombre»: copia")
            assertEquals(copia["json"], if (json.exists()) json.readText() else null, "«$nombre»: metadatos")

            val sinRed = Vectores.objeto(caso, "load_sin_red")
            val (codigo, salida, error) = Vectores.lista(catalogo, "rclone_sin_red")
            val rcloneSinRed = Catalogo.Ejecutar {
                Catalogo.Resultado((codigo as Number).toInt(), salida.toString(), error.toString())
            }
            val (cat, aviso) = Catalogo.load(rcloneSinRed, rawLocal, state, relojDe(sellos))
            assertEquals(sinRed["source"], cat?.source, "«$nombre»: de dónde sale sin red")
            assertEquals(sinRed["stamp"], cat?.stamp)
            assertEquals(sinRed["endpoint"], cat?.endpoint)
            assertEquals(sinRed["aviso"], aviso, "«$nombre»: el aviso sin red")
        }
    }

    @Test
    fun `las parejas del catálogo se buscan por nombre y se comparan como en Python`() {
        val parejas = Vectores.objeto(catalogo, "pairs_by_name")
        val escenario = Vectores.objetos(catalogo, "escenarios").first()
        val texto = Vectores.texto(Vectores.objeto(escenario, "pull"), "text")
        val cat = Catalogo.Catalog(loadRaw(texto), texto, "remote", "", "")
        assertEquals(normalizar(parejas), normalizar(Catalogo.pairsByName(cat)))
        assertNotNull(Catalogo.findPair(cat, parejas.keys.first()))
        assertNull(Catalogo.findPair(cat, "no-existe"))
        assertEquals(emptyMap(), Catalogo.pairsByName(null))
        for (caso in Vectores.objetos(catalogo, "diff_keys")) {
            @Suppress("UNCHECKED_CAST")
            val a = caso["a"] as Map<String, Any?>?
            @Suppress("UNCHECKED_CAST")
            val b = caso["b"] as Map<String, Any?>?
            assertEquals(Vectores.cadenas(caso, "salida"), Catalogo.diffKeys(a, b), "diffKeys($a, $b)")
        }
    }
}
