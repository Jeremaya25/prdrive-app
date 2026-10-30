package prdrive.engine

import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HistorialTest.kt — El diario de pasadas (`state/historial.jsonl`), comparado
 * con el `common/historial.py` de prdrive.
 *
 * Los casos y sus resultados salen de `vectores.json` (sección `historial`, de
 * `herramientas/vectores_historial.py`): cada línea, cada rechazo, cada recorte
 * y cada racha los ha contestado Python. Donde un valor es un texto enorme, el
 * JSON lo da en partes (cadenas y `{"repetir": ..., "veces": n}`) que [armar]
 * junta igual que el generador.
 */
class HistorialTest {

    private val historial get() = Vectores.seccion("historial")
    private val temporales = ArrayList<File>()

    private fun temporal(): File =
        Files.createTempDirectory("prdrive-historial").toFile().also { temporales.add(it) }

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
    }

    // --- leer los vectores -----------------------------------------------------

    /** Un texto de los vectores: tal cual, o en partes (`_armar()` del generador). */
    private fun armar(valor: Any?): String = when (valor) {
        is String -> valor
        is List<*> -> valor.joinToString("") { parte ->
            if (parte is String) {
                parte
            } else {
                parte as Map<*, *>
                parte["repetir"].toString().repeat((parte["veces"] as Long).toInt())
            }
        }
        else -> error("vectores.json: no es un texto: $valor")
    }

    private fun entero(valor: Any?): BigInteger? = valor?.let { enteroPy(it) }

    /** Una `Pasada` tal como la escribe el generador (`_d()`). */
    private fun pasadaDe(m: Map<*, *>): Historial.Pasada = Historial.Pasada(
        armar(m["pareja"]),
        m["inicio"].toString(),
        entero(m["codigo"])!!,
        (m["segundos"] as Number?)?.toDouble(),
        entero(m["transferido"]),
    )

    private fun pasadas(m: Map<String, Any?>, clave: String): List<Historial.Pasada> =
        Vectores.lista(m, clave).map { pasadaDe(it as Map<*, *>) }

    private fun rachaDe(m: Map<*, *>): Historial.Racha = Historial.Racha(
        m["desde"].toString(), m["exacta"] as Boolean, (m["buenas"] as Long).toInt(), (m["pasadas"] as Long).toInt(),
    )

    private fun hexABytes(hexa: String): ByteArray =
        ByteArray(hexa.length / 2) { hexa.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** Los bytes de un fichero de los vectores: `<prefijo>texto` o `<prefijo>hex`. */
    private fun bytesDe(m: Map<String, Any?>, prefijo: String = ""): ByteArray =
        m[prefijo + "hex"]?.let { hexABytes(it.toString()) } ?: armar(m[prefijo + "texto"]).toByteArray(Charsets.UTF_8)

    /** Una pasada de los vectores con solo lo que lee `racha()`: `"<inicio> <código>"`. */
    private fun deInicioYCodigo(pareja: String, texto: String): Historial.Pasada =
        Historial.Pasada(pareja, texto.substring(0, 19), BigInteger(texto.substring(20)))

    private fun lineasDe(fichero: File): List<String> {
        val texto = fichero.readText(Charsets.UTF_8)
        if (texto.isEmpty()) return emptyList()
        assertTrue(texto.endsWith("\n"), "el diario acaba en salto de línea")
        return texto.removeSuffix("\n").split("\n").map { it + "\n" }
    }

    // --- las constantes y las líneas ---------------------------------------------

    @Test
    fun `las constantes del diario son las de historial_py`() {
        val c = Vectores.objeto(historial, "constantes")
        assertEquals((c["POR_PAREJA"] as Long).toInt(), Historial.POR_PAREJA)
        assertEquals((c["RECORTE"] as Long).toInt(), Historial.RECORTE)
        assertEquals((c["TOPE_BYTES"] as Long).toInt(), Historial.TOPE_BYTES)
        assertEquals(c["fichero"], Historial.ruta(File("state")).name)
        assertEquals("state", Historial.ruta(File("state")).parentFile.name)
    }

    @Test
    fun `cada pasada se escribe como la linea de json_dumps con ensure_ascii False`() {
        for (caso in Vectores.objetos(historial, "lineas")) {
            val pasada = pasadaDe(caso["pasada"] as Map<*, *>)
            assertEquals(caso["texto"], Historial.linea(pasada), "la línea de ${pasada.pareja}")
        }
    }

    @Test
    fun `una linea se lee como la lee historial_py y lo que no es una pasada se salta`() {
        val casos = Vectores.objetos(historial, "analizar")
        for (caso in casos) {
            val esperada = (caso["pasada"] as Map<*, *>?)?.let { pasadaDe(it) }
            assertEquals(esperada, Historial.deLinea(armar(caso["texto"])), "«${caso["caso"]}»")
        }
        // Que los casos que rechazan y los que aceptan no sean todos de un lado.
        assertTrue(casos.any { it["pasada"] == null })
        assertTrue(casos.any { it["pasada"] != null })
    }

    @Test
    fun `lo que se escribe se lee de vuelta tal cual`() {
        for (caso in Vectores.objetos(historial, "lineas")) {
            val pasada = pasadaDe(caso["pasada"] as Map<*, *>)
            assertEquals(pasada, Historial.deLinea(Historial.linea(pasada)), "la línea de ${pasada.pareja}")
        }
    }

    // --- leer y apuntar ------------------------------------------------------------

    @Test
    fun `leer salta lo que no entiende y parte solo por salto de linea`() {
        for (caso in Vectores.objetos(historial, "leer")) {
            val state = File(temporal(), "state").apply { mkdirs() }
            val fichero = Historial.ruta(state)
            when {
                caso["carpeta"] == true -> assertTrue(fichero.mkdirs())
                caso["existe"] == false -> assertFalse(fichero.exists())
                else -> fichero.writeBytes(bytesDe(caso))
            }
            assertEquals(pasadas(caso, "pasadas"), Historial.leer(state), "«${caso["caso"]}»")
        }
    }

    @Test
    fun `apuntar deja en el fichero lo mismo que historial_py y empieza una linea propia tras una a medias`() {
        for (caso in Vectores.objetos(historial, "apuntar")) {
            val raiz = temporal()
            val state = File(raiz, "state")
            val pasada = pasadaDe(caso["pasada"] as Map<*, *>)
            val fichero = Historial.ruta(state)
            when {
                caso["sin_carpeta"] == true -> Unit
                caso["carpeta"] == true -> assertTrue(fichero.mkdirs())
                else -> {
                    state.mkdirs()
                    if (caso.containsKey("antes_texto") || caso.containsKey("antes_hex")) fichero.writeBytes(bytesDe(caso, "antes_"))
                }
            }
            assertEquals(caso["devuelve"], Historial.apuntar(state, pasada), "«${caso["caso"]}»")
            if (caso["devuelve"] == true) {
                assertEquals(
                    bytesDe(caso, "despues_").toList(), fichero.readBytes().toList(), "«${caso["caso"]}»",
                )
            }
        }
    }

    @Test
    fun `apuntar no deja un temporal ni toca nada mas en state`() {
        val state = File(temporal(), "state").apply { mkdirs() }
        assertTrue(Historial.apuntar(state, Historial.Pasada("notas", "2026-10-01 10:00:00", BigInteger.ZERO)))
        assertEquals(listOf("historial.jsonl"), state.list()!!.sorted())
    }

    @Test
    fun `apuntar desde varios hilos no pierde ninguna linea`() {
        val state = File(temporal(), "state").apply { mkdirs() }
        val hilos = (0 until 8).map { h ->
            Thread {
                for (i in 0 until 50) {
                    Historial.apuntar(
                        state, Historial.Pasada("hilo-$h-pasada-$i", "2026-10-01 10:00:00", BigInteger.valueOf(i.toLong())),
                    )
                }
            }.also { it.start() }
        }
        hilos.forEach { it.join() }
        val leidas = Historial.leer(state)
        assertEquals(400, leidas.size)
        assertEquals(400, leidas.map { it.pareja }.toSet().size)
    }

    // --- recortar --------------------------------------------------------------------

    @Test
    fun `las lineas que quedan tras un recorte son las de historial_py`() {
        for (caso in Vectores.objetos(historial, "recortadas")) {
            val formas = Vectores.lista(caso, "pasadas").map { armar(it) }
            val entrada = formas.map { Historial.deLinea(it)!! }
            val quedan = Vectores.lista(caso, "quedan").map { (it as Long).toInt() }
            val recortadas = Historial.recortadas(entrada)
            assertEquals(quedan.map { formas[it] + "\n" }, recortadas, "«${caso["caso"]}»")
            assertEquals(
                caso["bytes"], recortadas.sumOf { it.toByteArray(Charsets.UTF_8).size }.toLong(), "«${caso["caso"]}»",
            )
        }
    }

    @Test
    fun `el diario se recorta cuando una pareja o el fichero pasan de su tope y no antes`() {
        val casos = Vectores.objetos(historial, "recortar")
        for (caso in casos) {
            val state = File(temporal(), "state").apply { mkdirs() }
            val fichero = Historial.ruta(state)
            val lineas = Vectores.lista(caso, "lineas").map { armar(it) + "\n" }
            fichero.writeBytes(lineas.joinToString("").toByteArray(Charsets.UTF_8))
            assertEquals((caso["bytes"] as Long), fichero.length(), "«${caso["caso"]}»: el diario de partida")

            assertEquals(caso["devuelve"], Historial.recortarSiToca(state), "«${caso["caso"]}»")

            val quedan = Vectores.lista(caso, "quedan").map { lineas[(it as Long).toInt()] }
            assertEquals(quedan, lineasDe(fichero), "«${caso["caso"]}»")
            assertEquals(caso["bytes_despues"], fichero.length(), "«${caso["caso"]}»")
            assertFalse(File(state, "historial.tmp").exists(), "«${caso["caso"]}»: sin temporal")
        }
        assertTrue(casos.any { it["devuelve"] == true })
        assertTrue(casos.any { it["devuelve"] == false })
    }

    @Test
    fun `sin diario no hay nada que recortar`() {
        val state = File(temporal(), "state").apply { mkdirs() }
        assertFalse(Historial.recortarSiToca(state))
        assertFalse(Historial.ruta(state).exists(), "recortar no crea el diario")
    }

    @Test
    fun `apuntar recorta solo y deja las mismas lineas que el escritorio`() {
        for (caso in Vectores.objetos(historial, "secuencias")) {
            val state = File(temporal(), "state").apply { mkdirs() }
            val fichero = Historial.ruta(state)
            val formas = Vectores.lista(caso, "pasadas").map { armar(it) }
            val cuenta = Vectores.lista(caso, "cuenta").map { (it as Long).toInt() }
            formas.forEachIndexed { i, forma ->
                assertTrue(Historial.apuntar(state, Historial.deLinea(forma)!!))
                assertEquals(cuenta[i], Historial.leer(state).size, "«${caso["caso"]}» tras apuntar la ${i + 1}")
            }
            val quedan = Vectores.lista(caso, "quedan").map { formas[(it as Long).toInt()] + "\n" }
            assertEquals(quedan, lineasDe(fichero), "«${caso["caso"]}»")
            assertEquals(caso["bytes"], fichero.length(), "«${caso["caso"]}»")
        }
    }

    // --- medir una pasada --------------------------------------------------------------

    private fun relojDe(caso: Map<String, Any?>): Historial.Reloj {
        val tiempos = listOf((caso["t0"] as Number).toDouble(), (caso["t1"] as Number).toDouble()).iterator()
        return Historial.Reloj(sello = { caso["sello"].toString() }, monotono = { tiempos.next() })
    }

    @Test
    fun `el reloj apunta cuando empezo y cuanto duro redondeado a una decima como round de Python`() {
        val reloj = Vectores.objeto(historial, "reloj")
        for (caso in Vectores.objetos(reloj, "con_reloj")) {
            val esperada = pasadaDe(caso["pasada"] as Map<*, *>)
            val obtenida = relojDe(caso).pasada(
                caso["pareja"].toString(), (caso["codigo"] as Long).toInt(), (caso["transferido"] as Long?),
            )
            assertEquals(esperada, obtenida, "de ${caso["t0"]} a ${caso["t1"]}")
        }
    }

    @Test
    fun `una pasada sin reloj consta con la hora de ahora y sin duracion`() {
        val reloj = Vectores.objeto(historial, "reloj")
        for (caso in Vectores.objetos(reloj, "sin_reloj")) {
            val esperada = pasadaDe(caso["pasada"] as Map<*, *>)
            val obtenida = Historial.pasada(
                caso["pareja"].toString(), (caso["codigo"] as Long).toInt(), null, (caso["transferido"] as Long?),
                sello = { caso["sello"].toString() },
            )
            assertEquals(esperada, obtenida)
            assertNull(obtenida.segundos)
        }
    }

    @Test
    fun `una pasada con reloj es la que da el reloj`() {
        val reloj = Vectores.objeto(historial, "reloj")
        for (caso in Vectores.objetos(reloj, "con_reloj_por_pasada")) {
            val esperada = pasadaDe(caso["pasada"] as Map<*, *>)
            val obtenida = Historial.pasada(
                caso["pareja"].toString(), (caso["codigo"] as Long).toInt(), relojDe(caso), (caso["transferido"] as Long?),
                sello = { error("con reloj no se pregunta la hora") },
            )
            assertEquals(esperada, obtenida)
        }
    }

    @Test
    fun `el reloj real mide con un reloj que avanza y fecha con la hora de pared`() {
        val antes = Store.stamp()
        val reloj = Historial.Reloj()
        Thread.sleep(30)
        val p = reloj.pasada("notas", 0)
        assertTrue(antes <= reloj.inicio && reloj.inicio <= Store.stamp())
        val duracion = p.segundos
        assertTrue(duracion != null && duracion >= 0.0 && duracion < 60.0, "duró $duracion")
        assertEquals(reloj.inicio, p.inicio)
        assertNull(p.transferido)
    }

    // --- las rachas -----------------------------------------------------------------------

    @Test
    fun `la racha de una pareja es la de historial_py`() {
        val casos = Vectores.objetos(historial, "racha")
        for (caso in casos) {
            val pasadas = Vectores.cadenas(caso, "pasadas").map { deInicioYCodigo("notas", it) }
            val esperada = (caso["racha"] as Map<*, *>?)?.let { rachaDe(it) }
            assertEquals(esperada, Historial.racha(pasadas), "«${caso["caso"]}»")
        }
        assertTrue(casos.any { it["racha"] == null })
        assertTrue(casos.any { it["racha"] != null })
    }

    private fun rachasDe(m: Map<String, Any?>): List<kotlin.Pair<String, Historial.Racha>> =
        Vectores.objeto(m, "rachas").map { (nombre, racha) -> nombre to rachaDe(racha as Map<*, *>) }

    @Test
    fun `las rachas de varias parejas salen una vez cada una y en el orden del diario`() {
        val state = File(temporal(), "state").apply { mkdirs() }
        val diario = Vectores.objeto(historial, "rachas")
        Historial.ruta(state).writeBytes(bytesDe(Vectores.objeto(diario, "diario")))
        for (caso in Vectores.objetos(diario, "consultas")) {
            val obtenidas = Historial.rachas(state, Vectores.cadenas(caso, "nombres")).toList()
            assertEquals(rachasDe(caso), obtenidas, "«${caso["caso"]}»")
        }
    }

    @Test
    fun `las rachas cuentan con las ultimas cincuenta pasadas de una pareja`() {
        val state = File(temporal(), "state").apply { mkdirs() }
        val larga = Vectores.objeto(Vectores.objeto(historial, "rachas"), "larga")
        val pasadas = Vectores.cadenas(larga, "pasadas").map { deInicioYCodigo(larga["pareja"].toString(), it) }
        Historial.ruta(state).writeText(pasadas.joinToString("") { Historial.linea(it) })
        assertEquals(rachasDe(larga), Historial.rachas(state, Vectores.cadenas(larga, "nombres")).toList())
    }

    @Test
    fun `sin diario no hay rachas`() {
        val state = File(temporal(), "state").apply { mkdirs() }
        val sin = Vectores.objeto(Vectores.objeto(historial, "rachas"), "sin_diario")
        assertEquals(rachasDe(sin), Historial.rachas(state, Vectores.cadenas(sin, "nombres")).toList())
    }
}
