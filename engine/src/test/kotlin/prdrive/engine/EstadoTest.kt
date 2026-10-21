package prdrive.engine

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * EstadoTest.kt — Lo que la app escribe en `state/`, comparado con lo que
 * escribe el escritorio: el JSON letra a letra, el formato de las fechas y
 * `last_run.json` paso a paso.
 *
 * El volumen tiene la distribución de un dispositivo para que el programa de
 * escritorio pueda leer el estado del teléfono. Eso solo es verdad si los
 * ficheros salen iguales, así que se comparan con los que escribió prdrive
 * haciendo lo mismo.
 */
class EstadoTest {

    private val temporales = ArrayList<File>()

    private fun temporal(): File =
        Files.createTempDirectory("prdrive-estado").toFile().also { temporales.add(it) }

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
    }

    /** Un reloj que devuelve las fechas fijas del generador, una por llamada. */
    private class Reloj(private val sellos: List<String>) : () -> String {
        private var n = 0
        override fun invoke(): String = sellos[n++ % sellos.size]
    }

    /** Las fechas que el generador le hace devolver a `store.stamp()`, en su orden. */
    private fun sellos(): List<String> = Vectores.cadenas(Vectores.raiz, "sellos")

    @Test
    fun `el JSON se escribe como json_dumps con indent 1`() {
        for (caso in Vectores.lista(Vectores.raiz, "json")) {
            caso as Map<*, *>
            val esperado = caso["texto"].toString()
            assertEquals(esperado, escribirJson(caso["valor"]), "escribirJson(${caso["valor"]})")
            // Y se lee igual que se escribió.
            assertEquals(normalizar(caso["valor"]), normalizar(leerJson(esperado)))
        }
    }

    @Test
    fun `las fechas de state se escriben con el formato de store`() {
        val store = Vectores.seccion("store")
        assertEquals(Vectores.texto(store, "formato"), Store.FORMATO_PY)
        assertEquals(Vectores.texto(store, "ejemplo"), Store.sello(LocalDateTime.of(2026, 1, 2, 3, 4, 5)))
        assertNotNull(Store.desdeSello(Vectores.texto(store, "ejemplo")))
        assertNull(Store.desdeSello("no es una fecha"))
    }

    @Test
    fun `last_run se escribe paso a paso como en el escritorio`() {
        val resultados = Vectores.seccion("resultados")
        val raiz = temporal()
        val state = File(raiz, "state").apply { mkdirs() }
        val logs = File(raiz, "logs").apply { mkdirs() }
        File(logs, "notas-2.log").writeText("x")
        val reloj = Reloj(sellos())
        for (paso in Vectores.objetos(resultados, "pasos")) {
            val log = paso["log"]?.let { File(logs, it.toString()) }
            Resultados.apuntar(
                state, Vectores.texto(paso, "nombre"), (paso["codigo"] as Long).toInt(), log, reloj,
            )
            assertEquals(
                Vectores.texto(paso, "texto"),
                Resultados.rutaEstado(state).readText(),
                "tras apuntar ${paso["nombre"]} con ${paso["codigo"]}",
            )
        }
        val nombres = listOf("notas", "fotos", "nunca")
        assertEquals(
            Vectores.lista(resultados, "fallos"),
            Resultados.fallosDe(state, logs, nombres).map { comoMapa(it) },
        )
        val buenas = Vectores.objeto(resultados, "buenas")
        val datos = Store.readJson(Resultados.rutaEstado(state))["parejas"] as Map<*, *>
        for ((nombre, buena) in buenas) {
            assertEquals(buena, Resultados.buena(datos[nombre] as Map<*, *>), "buena de $nombre")
        }
    }

    @Test
    fun `los registros de antes y la basura se leen como en el escritorio`() {
        val legado = Vectores.objeto(Vectores.seccion("resultados"), "legado")
        val raiz = temporal()
        val state = File(raiz, "state").apply { mkdirs() }
        val logs = File(raiz, "logs").apply { mkdirs() }
        File(logs, "notas-2.log").writeText("x")
        Resultados.rutaEstado(state).writeText(Vectores.texto(legado, "texto"))
        @Suppress("UNCHECKED_CAST")
        val parejas = (leerJson(Vectores.texto(legado, "texto")) as Map<String, Any?>)["parejas"] as Map<String, Any?>
        val todos = parejas.keys + "nunca"
        assertEquals(
            Vectores.lista(legado, "fallos"),
            Resultados.fallosDe(state, logs, todos).map { comoMapa(it) },
        )
        for ((nombre, buena) in Vectores.objeto(legado, "buenas")) {
            assertEquals(buena, Resultados.buena(parejas[nombre] as Map<*, *>), "buena de $nombre")
        }
        assertEquals(
            Vectores.cadenas(legado, "ultimas_buenas"),
            Resultados.ultimasBuenas(state, todos).keys.sorted(),
        )
    }

    private fun comoMapa(f: Resultados.Fallo): Map<String, Any?> = linkedMapOf(
        "pareja" to f.pareja, "cuando" to f.cuando, "codigo" to f.codigo.toLong(),
        "log" to f.log?.name, "buena" to f.buena,
    )
}
