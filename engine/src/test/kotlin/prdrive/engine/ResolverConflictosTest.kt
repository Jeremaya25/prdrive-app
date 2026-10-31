package prdrive.engine

import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.FileTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ResolverConflictosTest.kt — Resolver un conflicto quedándose con una versión,
 * comparado con el `ui/conflict_editor.py` de prdrive.
 *
 * Los casos y sus resultados salen de `vectores.json` (sección
 * `resolver_conflictos`, de `herramientas/vectores_conflictos_editor.py`). Cada
 * escenario es un volumen con una pareja y unos ficheros con su contenido y su
 * hora en ns; este test los monta igual, busca los conflictos con
 * [Conflictos.escanear] y comprueba que el editor de prdrive y este dicen lo
 * mismo de cada uno: cómo se llama cada versión, si hay una de cada lado, el plan
 * de quedarse con cada una (qué se mueve, qué se borra, con qué huella, y los
 * textos que se enseñan antes de confirmar) y, ejecutando cada plan en un volumen
 * recién montado y con lo que pueda pasar entre pensarlo y ejecutarlo (un
 * fichero que cambia o desaparece, un `mover` o un `borrar` que fallan), qué
 * dice y qué cambia en el disco.
 *
 * Las cadenas con varios campos van como las escribe el generador (ver su
 * docstring): `"<ruta>\t<ns>\t<contenido>"` para un fichero, y la operación
 * (`escribir`, `tocar`, `borrar`) delante, separada por tabuladores, para lo
 * que cambia en el disco.
 */
class ResolverConflictosTest {

    private val editor get() = Vectores.seccion("resolver_conflictos")
    private val temporales = ArrayList<File>()

    private fun temporal(): File =
        Files.createTempDirectory("prdrive-resolver").toFile().also { temporales.add(it) }

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
    }

    // --- el disco ---------------------------------------------------------------

    private fun hora(fichero: File): Long = Llavero.horaJvm(fichero)

    private fun escribir(fichero: File, contenido: String, ns: Long) {
        fichero.parentFile.mkdirs()
        fichero.writeBytes(contenido.toByteArray(Charsets.UTF_8))
        tocar(fichero, ns)
    }

    private fun tocar(fichero: File, ns: Long) {
        Files.setLastModifiedTime(fichero.toPath(), FileTime.from(ns, TimeUnit.NANOSECONDS))
    }

    /** Hace en el disco lo que dicen esas operaciones (rutas relativas a [carpeta]). */
    private fun aplicar(carpeta: File, ops: List<String>) {
        for (texto in ops) {
            val partes = texto.split('\t', limit = 4)
            val fichero = File(carpeta, partes[1])
            when (partes[0]) {
                "escribir" -> escribir(fichero, partes[3], partes[2].toLong())
                "tocar" -> tocar(fichero, partes[2].toLong())
                "borrar" -> assertTrue(fichero.delete(), "borrar ${partes[1]}")
                else -> error("operación desconocida: ${partes[0]}")
            }
        }
    }

    private fun relativa(f: File, base: File): String =
        base.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/')

    /** Los ficheros bajo [carpeta]: ruta -> `"<ns> <contenido>"`, como el generador. */
    private fun arbol(carpeta: File): Map<String, String> {
        val salida = LinkedHashMap<String, String>()
        for (f in carpeta.walkTopDown().filter { it.isFile }) {
            salida[relativa(f, carpeta)] = "${hora(f)} ${f.readBytes().toString(Charsets.UTF_8)}"
        }
        return salida
    }

    private fun cambios(antes: Map<String, String>, despues: Map<String, String>): Map<String, String?> {
        val salida = LinkedHashMap<String, String?>()
        for ((ruta, valor) in despues) if (antes[ruta] != valor) salida[ruta] = valor
        for (ruta in antes.keys) if (ruta !in despues) salida[ruta] = null
        return salida
    }

    /** Un escenario del generador, montado en un volumen nuevo. */
    private inner class Montaje(esc: Map<String, Any?>) {
        val dir: File = temporal()
        val volumen = File(dir, "volumen").apply { mkdirs() }
        val pareja: Pareja = parseConfig(Vectores.objeto(esc, "config")).pairs.first { it.name == esc["pareja"] }
        val carpeta: File = pareja.carpetaEn(volumen)
        val conflictos: List<Conflictos.Conflicto>

        init {
            for (linea in Vectores.cadenas(esc, "ficheros")) {
                val (ruta, ns, contenido) = linea.split('\t', limit = 3)
                escribir(File(carpeta, ruta), contenido, ns.toLong())
            }
            conflictos = Conflictos.escanear(pareja, volumen)
            aplicar(carpeta, Vectores.cadenas(esc, "ops_antes"))
        }

        fun rel(f: File): String = relativa(f, carpeta)

        fun soltar() {
            dir.deleteRecursively()
        }
    }

    // --- lo que se llama al editor ----------------------------------------------------

    private val zonaEscenarios: ZoneId
        get() = ZoneOffset.ofTotalSeconds((editor["zona_escenarios"] as Long).toInt())

    /** Los `mover` y `borrar` de una ejecución, que apuntan lo que les piden y fallan donde se diga. */
    private inner class Ganchos(private val m: Montaje, ej: Map<String, Any?>) {
        val llamadas = ArrayList<String>()
        private val fallaMover = ej["falla_mover"]?.toString()
        private val fallaBorrar: Map<String, String> =
            ((ej["falla_borrar"] as List<*>?) ?: emptyList<Any?>()).associate {
                val (ruta, mensaje) = it.toString().split('\t', limit = 2)
                ruta to mensaje
            }
        private val yaNoEstaba: Set<String> = ((ej["ya_no_estaba"] as List<*>?) ?: emptyList<Any?>()).map { it.toString() }.toSet()

        fun mover(origen: File, destino: File) {
            llamadas.add("mover ${m.rel(origen)} -> ${m.rel(destino)}")
            if (fallaMover != null) throw IOException(fallaMover)
            ResolverConflictos.mover(origen, destino)
        }

        fun borrar(ruta: File) {
            val r = m.rel(ruta)
            llamadas.add("borrar $r")
            fallaBorrar[r]?.let { throw IOException(it) }
            if (r in yaNoEstaba) {
                // Alguien lo borró justo antes: el fichero se va y el borrado se queja.
                ResolverConflictos.borrar(ruta)
                throw NoSuchFileException(ruta.path)
            }
            ResolverConflictos.borrar(ruta)
        }
    }

    private fun entorno(ganchos: Ganchos? = null) = ResolverConflictos.Entorno(
        zona = { zonaEscenarios },
        mover = ganchos?.let { it::mover } ?: ResolverConflictos::mover,
        borrar = ganchos?.let { it::borrar } ?: ResolverConflictos::borrar,
    )

    /** El plan de `como` para el conflicto `ci`, o el error o la caída que dé, como el generador. */
    private fun planear(
        m: Montaje,
        ci: Int,
        como: Map<String, Any?>,
        entorno: ResolverConflictos.Entorno,
    ): Pair<String, Any?> {
        val c = m.conflictos[ci]
        return try {
            val plan = when {
                "lado" in como -> ResolverConflictos.planLado(c, como["lado"] as String, entorno)
                "version" in como ->
                    ResolverConflictos.planConservar(c, c.versiones[(como["version"] as Long).toInt()], entorno)
                else -> {
                    val (otro, version) = como["ajena"] as List<*>
                    ResolverConflictos.planConservar(
                        c, m.conflictos[(otro as Long).toInt()].versiones[(version as Long).toInt()], entorno,
                    )
                }
            }
            Pair("plan", plan)
        } catch (e: ResolverConflictos.ResolucionImposible) {
            Pair("error", e.message)
        } catch (_: NoSuchElementException) {
            Pair("rompe", "KeyError")
        }
    }

    private fun huellaTexto(h: Pair<Long, Long>?): String? = h?.let { "${it.first} ${it.second}" }

    /** Lo que un plan dice de sí mismo, como `_plan()` del generador. */
    private fun descripcion(plan: ResolverConflictos.ResolvePlan, m: Montaje): Map<String, Any?> = linkedMapOf(
        "conserva" to m.rel(plan.conserva.ruta),
        "descartar" to plan.descartar.map { m.rel(it) },
        "huellas" to plan.huellas.entries.associate { m.rel(it.key) to huellaTexto(it.value) },
        "consequences" to plan.consequences,
        "warnings" to plan.warnings,
    )

    @Suppress("UNCHECKED_CAST")
    private fun mapa(valor: Any?): Map<String, Any?> = valor as Map<String, Any?>

    private fun escenarios() = Vectores.objetos(editor, "escenarios")

    private fun conflictosDe(esc: Map<String, Any?>) = Vectores.objetos(esc, "conflictos")

    // --- las constantes y lo suelto ----------------------------------------------------

    @Test
    fun `las etiquetas y los textos fijos son los de conflict_editor_py`() {
        val c = Vectores.objeto(editor, "constantes")
        assertEquals(c["ETIQUETA_LADO"], ResolverConflictos.ETIQUETA_LADO)
        assertEquals(c["ETIQUETA_ORIGINAL"], ResolverConflictos.ETIQUETA_ORIGINAL)
        assertEquals(c["ETIQUETA_COPIA"], ResolverConflictos.ETIQUETA_COPIA)
        assertEquals(c["NOTA_LOCAL"], ResolverConflictos.NOTA_LOCAL)
        assertEquals(c["PIERDE_LLAVERO"], ResolverConflictos.PIERDE_LLAVERO)
        assertEquals(c["LLAVERO"], LLAVERO)
        assertEquals(listOf(Conflictos.DISPOSITIVO, Conflictos.REMOTO), ResolverConflictos.ETIQUETA_LADO.keys.toList())
    }

    @Test
    fun `la pareja del llavero se enseña sin su nombre interno`() {
        val casos = Vectores.objetos(editor, "nombre_pareja")
        for (caso in casos) {
            assertEquals(caso["texto"], ResolverConflictos.nombrePareja(caso["nombre"].toString()), "«${caso["nombre"]}»")
        }
        assertTrue(casos.any { it["texto"] != it["nombre"] })
        assertTrue(casos.any { it["texto"] == it["nombre"] })
    }

    @Test
    fun `un tamano se escribe como tamano de conflict_editor_py`() {
        val casos = Vectores.cadenas(editor, "tamano")
        for (caso in casos) {
            val (octetos, texto) = caso.split(' ', limit = 2)
            assertEquals(texto, ResolverConflictos.tamano(octetos.toLong()), "$octetos octetos")
        }
        assertTrue(casos.any { it.endsWith(" B") })
        assertTrue(casos.any { it.endsWith(" KB") })
        assertTrue(casos.any { it.endsWith(" MB") })
        assertTrue(casos.any { it.endsWith(" GB") })
    }

    @Test
    fun `una fecha se escribe como fecha de conflict_editor_py en cada zona`() {
        val casos = Vectores.cadenas(editor, "fecha")
        for (caso in casos) {
            val (ns, zona, texto) = caso.split(' ', limit = 3)
            assertEquals(
                texto, ResolverConflictos.fecha(ns.toLong(), ZoneOffset.ofTotalSeconds(zona.toInt())),
                "$ns ns en la zona de $zona s",
            )
        }
        assertTrue(casos.map { it.split(' ', limit = 3)[1] }.toSet().size >= 3)
    }

    @Test
    fun `un tamano y una fecha no usan las cifras del idioma del telefono`() {
        val antes = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            for (caso in Vectores.cadenas(editor, "tamano")) {
                val (octetos, texto) = caso.split(' ', limit = 2)
                assertEquals(texto, ResolverConflictos.tamano(octetos.toLong()), "$octetos octetos")
            }
            for (caso in Vectores.cadenas(editor, "fecha").take(60)) {
                val (ns, zona, texto) = caso.split(' ', limit = 3)
                assertEquals(texto, ResolverConflictos.fecha(ns.toLong(), ZoneOffset.ofTotalSeconds(zona.toInt())))
            }
        } finally {
            Locale.setDefault(antes)
        }
    }

    @Test
    fun `sin zona una fecha es la del sistema`() {
        val ns = 1_788_000_000_000_000_000L
        assertEquals(
            ResolverConflictos.fecha(ns, ZoneId.systemDefault()),
            ResolverConflictos.fecha(ns),
        )
    }

    // --- las etiquetas ---------------------------------------------------------------------

    @Test
    fun `cada version se llama como la llama conflict_editor_py y las repetidas se numeran`() {
        val casos = Vectores.objetos(editor, "etiquetas")
        for (caso in casos) {
            val texto = caso["versiones"].toString()
            val versiones = (if (texto.isEmpty()) emptyList() else texto.split(' ')).mapIndexed { i, token ->
                val original = token.startsWith("*")
                val lado = token.removePrefix("*").takeIf { it != "ninguno" }
                Conflictos.Version(
                    File("sin/disco", if (original) "original.txt" else "original.txt.copia$i"),
                    lado, original, BigInteger.valueOf(i.toLong()),
                )
            }
            val c = Conflictos.Conflicto("notas", File("sin/disco"), File("sin/disco/original.txt"), versiones)
            val obtenidas = ResolverConflictos.etiquetas(c)
            assertEquals(caso["etiquetas"], obtenidas.joinToString(" | ") { it.second }, "«$texto»")
            assertEquals(versiones, obtenidas.map { it.first }, "«$texto»: las versiones, en su orden")
            for ((v, nombre) in obtenidas) assertEquals(nombre, ResolverConflictos.etiqueta(c, v), "«$texto»")
        }
        // Que estén las repetidas, las que no se saben y las de cada lado.
        val todas = casos.joinToString("\n") { it["etiquetas"].toString() }
        assertTrue("(1)" in todas && "(2)" in todas && "(3)" in todas)
        assertTrue(ResolverConflictos.ETIQUETA_COPIA in todas && ResolverConflictos.ETIQUETA_ORIGINAL in todas)
        assertTrue(ResolverConflictos.ETIQUETA_LADO.values.all { it in todas })
    }

    @Test
    fun `la etiqueta de una version que no es del conflicto es un KeyError`() {
        val c = Conflictos.Conflicto(
            "notas", File("sin/disco"), File("sin/disco/original.txt"),
            listOf(Conflictos.Version(File("sin/disco/original.txt"), null, true)),
        )
        val ajena = Conflictos.Version(File("sin/disco/otra.txt"), null, true)
        assertFailsWith<NoSuchElementException> { ResolverConflictos.etiqueta(c, ajena) }
    }

    // --- los escenarios sobre el disco ------------------------------------------------------

    @Test
    fun `cada escenario se monta igual y encuentra los mismos conflictos`() {
        for (esc in escenarios()) {
            val m = Montaje(esc)
            val caso = "«${esc["caso"]}»"
            assertEquals(Vectores.objeto(esc, "arbol_inicial"), arbol(m.carpeta), "$caso: el árbol")
            val esperados = conflictosDe(esc)
            assertEquals(esperados.size, m.conflictos.size, "$caso: cuántos conflictos")
            for ((c, esperado) in m.conflictos.zip(esperados)) {
                assertEquals(esperado["original"], m.rel(c.original), caso)
                assertEquals(esperado["relativa"], c.relativa, caso)
                val versiones = c.versiones.map {
                    linkedMapOf(
                        "ruta" to m.rel(it.ruta), "lado" to it.lado,
                        "es_original" to it.esOriginal, "numero" to comoJson(it.numero),
                    )
                }
                assertEquals(esperado["versiones"], versiones, caso)
            }
            m.soltar()
        }
    }

    @Test
    fun `lo que se enseña de cada version y de cada conflicto es lo de conflict_editor_py`() {
        for (esc in escenarios()) {
            val m = Montaje(esc)
            val entorno = entorno()
            for ((c, esperado) in m.conflictos.zip(conflictosDe(esc))) {
                val caso = "«${esc["caso"]}», ${c.relativa}"
                assertEquals(esperado["etiquetas"], ResolverConflictos.etiquetas(c).map { it.second }, caso)
                assertEquals(
                    esperado["describir"], c.versiones.map { ResolverConflictos.describir(it.ruta, entorno) }, caso,
                )
                assertEquals(
                    Vectores.objeto(esperado, "huellas"),
                    c.versiones.associate { m.rel(it.ruta) to huellaTexto(ResolverConflictos.huella(it.ruta, entorno)) },
                    caso,
                )
                assertEquals(esperado["es_del_llavero"], ResolverConflictos.esDelLlavero(c), caso)
                assertEquals(esperado["nombre_pareja"], ResolverConflictos.nombrePareja(c.pareja), caso)
            }
            m.soltar()
        }
    }

    @Test
    fun `puede dice si hay una sola version de ese lado y sigue en el disco`() {
        val verdaderos = ArrayList<Boolean>()
        for (esc in escenarios()) {
            val m = Montaje(esc)
            for ((c, esperado) in m.conflictos.zip(conflictosDe(esc))) {
                for ((lado, quiza) in Vectores.objeto(esperado, "puede")) {
                    val dice = ResolverConflictos.puede(c, lado, entorno())
                    assertEquals(quiza, dice, "«${esc["caso"]}», ${c.relativa}: puede($lado)")
                    verdaderos.add(dice)
                }
            }
            m.soltar()
        }
        assertTrue(true in verdaderos && false in verdaderos)
    }

    @Test
    fun `cada plan dice lo que dice conflict_editor_py antes de confirmar`() {
        var planes = 0
        var errores = 0
        var caidas = 0
        for (esc in escenarios()) {
            val m = Montaje(esc)
            for ((ci, cv) in conflictosDe(esc).withIndex()) {
                for (esperado in Vectores.objetos(cv, "planes")) {
                    val como = mapa(esperado["como"])
                    val caso = "«${esc["caso"]}», ${m.conflictos[ci].relativa}, $como"
                    val (que, valor) = planear(m, ci, como, entorno())
                    when {
                        "plan" in esperado -> {
                            assertEquals("plan", que, caso)
                            val plan = valor as ResolverConflictos.ResolvePlan
                            val dicho = Vectores.objeto(esperado, "plan")
                            val obtenido = descripcion(plan, m)
                            assertEquals(dicho["conserva"], obtenido["conserva"], caso)
                            assertEquals(dicho["descartar"], obtenido["descartar"], caso)
                            // Las huellas, y en el orden del conflicto.
                            assertEquals(
                                (dicho["huellas"] as Map<*, *>).toList(), (obtenido["huellas"] as Map<*, *>).toList(), caso,
                            )
                            assertEquals(dicho["consequences"], obtenido["consequences"], caso)
                            assertEquals(dicho["warnings"], obtenido["warnings"], caso)
                            planes++
                        }
                        "error" in esperado -> {
                            assertEquals("error", que, caso)
                            assertEquals(esperado["error"], valor, caso)
                            errores++
                        }
                        else -> {
                            assertEquals("rompe", que, caso)
                            assertEquals(esperado["rompe"], valor, caso)
                            caidas++
                        }
                    }
                }
            }
            m.soltar()
        }
        assertTrue(planes > 50 && errores > 5 && caidas > 2, "planes=$planes errores=$errores caidas=$caidas")
    }

    @Test
    fun `quedarse con una version avisa si la que se pierde es mas reciente y si es la base del llavero`() {
        val avisos = ArrayList<List<*>>()
        for (esc in escenarios()) {
            for (cv in conflictosDe(esc)) {
                for (p in Vectores.objetos(cv, "planes")) {
                    (p["plan"] as Map<*, *>?)?.let { avisos.add(it["warnings"] as List<*>) }
                }
            }
        }
        val reciente = avisos.count { l -> l.any { it.toString().startsWith("La versión que se descarta es más reciente") } }
        val llavero = avisos.count { ResolverConflictos.PIERDE_LLAVERO in it }
        assertTrue(reciente > 5 && llavero > 5, "reciente=$reciente llavero=$llavero")
        assertTrue(avisos.any { it.isEmpty() })
        assertTrue(avisos.any { it.size == 2 })
    }

    @Test
    fun `ejecutar un plan hace en el disco y dice lo mismo que conflict_editor_py`() {
        var hechos = 0
        var errores = 0
        for (esc in escenarios()) {
            for ((ci, cv) in conflictosDe(esc).withIndex()) {
                for (planVisto in Vectores.objetos(cv, "planes")) {
                    if ("plan" !in planVisto) continue // un error o una caída: no hay nada que ejecutar
                    val como = mapa(planVisto["como"])
                    for (ej in Vectores.objetos(planVisto, "ejecuciones")) {
                        val m = Montaje(esc)
                        val caso = "«${esc["caso"]}», ${m.conflictos[ci].relativa}, $como, «${ej["caso"]}»"
                        try {
                            val ganchos = Ganchos(m, ej)
                            val (que, valor) = planear(m, ci, como, entorno(ganchos))
                            assertEquals("plan", que, caso)
                            val plan = valor as ResolverConflictos.ResolvePlan
                            val dicho = Vectores.objeto(planVisto, "plan")
                            assertEquals(dicho["consequences"], plan.consequences, caso)
                            assertEquals(dicho["warnings"], plan.warnings, caso)

                            aplicar(m.carpeta, ((ej["despues"] as List<*>?) ?: emptyList<Any?>()).map { it.toString() })
                            val antes = arbol(m.carpeta)
                            val resultado = try {
                                Pair("hechos", plan.execute())
                            } catch (e: ResolverConflictos.ResolucionImposible) {
                                Pair("error", e.message)
                            }

                            if ("hechos" in ej) {
                                assertEquals(ej["hechos"], resultado.second, caso)
                                assertEquals("hechos", resultado.first, caso)
                                hechos++
                            } else {
                                assertEquals(ej["error"], resultado.second, caso)
                                assertEquals("error", resultado.first, caso)
                                errores++
                            }
                            assertEquals(ej["llamadas"], ganchos.llamadas, "$caso: lo que toca, en orden")
                            assertEquals(ej["cambios"], cambios(antes, arbol(m.carpeta)), "$caso: lo que cambia")
                        } finally {
                            m.soltar()
                        }
                    }
                }
            }
        }
        assertTrue(hechos > 100 && errores > 100, "hechos=$hechos errores=$errores")
    }

    @Test
    fun `mover pone una version sobre otra de un golpe y conserva su fecha`() {
        val dir = temporal()
        val origen = File(dir, "a.txt.conflicto-remoto1")
        val destino = File(dir, "a.txt")
        escribir(origen, "la buena", 1_788_000_000_000_000_000L)
        escribir(destino, "la vieja", 1_788_000_600_000_000_000L)
        ResolverConflictos.mover(origen, destino)
        assertFalse(origen.exists())
        assertEquals("la buena", destino.readText())
        assertEquals(1_788_000_000_000_000_000L, hora(destino))
        // Sin el origen no hay nada que mover, y el destino no se toca.
        assertFailsWith<IOException> { ResolverConflictos.mover(origen, destino) }
        assertEquals("la buena", destino.readText())
    }

    @Test
    fun `borrar no borra una carpeta y avisa si el fichero ya no estaba`() {
        val dir = temporal()
        val carpeta = File(dir, "carpeta").apply { mkdirs() }
        assertFailsWith<IOException> { ResolverConflictos.borrar(carpeta) }
        assertTrue(carpeta.isDirectory)
        val fichero = File(dir, "f.txt").apply { writeText("x") }
        ResolverConflictos.borrar(fichero)
        assertFalse(fichero.exists())
        assertFailsWith<NoSuchFileException> { ResolverConflictos.borrar(fichero) }
    }

    @Test
    fun `una huella sin fichero es null y la hora la da el entorno`() {
        val dir = temporal()
        val f = File(dir, "f.txt").apply { writeText("hola") }
        assertEquals(null, ResolverConflictos.huella(File(dir, "no-esta.txt")))
        val h = ResolverConflictos.huella(f, ResolverConflictos.Entorno(hora = { 12345L }))
        assertEquals(Pair(4L, 12345L), h)
        // Si leer la hora falla, no consta: como un `stat` que lanza `OSError`.
        val sinHora = ResolverConflictos.Entorno(hora = { throw IOException("no se puede") })
        assertEquals(null, ResolverConflictos.huella(f, sinHora))
    }
}
