package prdrive.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ConflictosTest.kt — El nombre del perdedor, contra lo que dice prdrive.
 *
 * Esto replica a rclone (`cmd/bisync/resolve.go`), así que equivocarse no da
 * ningún error: sale una etiqueta de lado inventada, o un fichero en conflicto
 * que nadie ve. Los valores esperados salen de `vectores.json`, caso por caso
 * y nombre por nombre.
 */
class ConflictosTest {

    private val vec = Vectores.seccion("conflictos")
    private val config = parseConfig(Vectores.objeto(Vectores.seccion("resueltas"), "config"))
    private val notas = config.pairs.first { it.name == "notas" }

    /** La pareja con los flags del caso, que es de donde se lee el esquema. */
    private fun conFlags(caso: Map<String, Any?>): Pareja {
        val flags = LinkedHashMap(notas.flags)
        // El caso dice qué flags quedan puestos; los tres que importan se
        // quitan primero para poder probar también el «de fábrica».
        for (k in listOf("conflict-suffix", "conflict-loser", "suffix-keep-extension")) {
            flags.remove(k)
        }
        flags.putAll(Vectores.objeto(caso, "flags"))
        return notas.copy(flags = flags)
    }

    @Test
    fun `las constantes de un conflicto son las de prdrive`() {
        assertEquals(Vectores.texto(vec, "dispositivo"), Conflictos.DISPOSITIVO)
        assertEquals(Vectores.texto(vec, "remoto"), Conflictos.REMOTO)
        assertEquals(Vectores.texto(vec, "sufijo_rclone"), Conflictos.SUFIJO_RCLONE)
        assertEquals(Vectores.texto(vec, "perdedor_rclone"), Conflictos.PERDEDOR_RCLONE)
    }

    @Test
    fun `el esquema de cada pareja es el que saca prdrive de sus flags`() {
        // Lo que hace `setResolveDefaults`: un sufijo vale para los dos lados,
        // dos separados por coma son uno para cada uno, y los dos llevan punto.
        for (caso in Vectores.objetos(vec, "casos")) {
            val esperado = Vectores.objeto(caso, "esquema")
            val esq = Conflictos.esquema(conFlags(caso))
            val etiqueta = Vectores.texto(caso, "caso")
            assertEquals(Vectores.texto(esperado, "sufijo1"), esq.sufijo1, "$etiqueta: sufijo1")
            assertEquals(Vectores.texto(esperado, "sufijo2"), esq.sufijo2, "$etiqueta: sufijo2")
            assertEquals(Vectores.texto(esperado, "perdedor"), esq.perdedor, "$etiqueta: perdedor")
            assertEquals(
                esperado["mantener_extension"],
                esq.mantenerExtension,
                "$etiqueta: mantener_extension",
            )
        }
    }

    @Test
    fun `de cada nombre sale el mismo original, el mismo lado y el mismo número`() {
        // La parte que más se puede equivocar: qué significa el número depende
        // del esquema. Con dos sufijos el sufijo ES el lado; con uno y
        // `pathname`, 1 es path1; con uno y `num` el lado NO se sabe, y decir
        // que se sabe sería inventarlo.
        var reconocidos = 0
        for (caso in Vectores.objetos(vec, "casos")) {
            val esq = Conflictos.esquema(conFlags(caso))
            val etiqueta = Vectores.texto(caso, "caso")
            for (n in Vectores.objetos(caso, "nombres")) {
                val nombre = Vectores.texto(n, "nombre")
                val leido = Conflictos.leerNombre(nombre, esq)
                val esperado = n["leido"]
                if (esperado == null) {
                    assertNull(leido, "$etiqueta: '$nombre' no es un conflicto")
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val e = esperado as Map<String, Any?>
                assertTrue(leido != null, "$etiqueta: '$nombre' sí es un conflicto")
                assertEquals(e["original"], leido.original, "$etiqueta: original de '$nombre'")
                assertEquals(e["camino"], leido.camino, "$etiqueta: camino de '$nombre'")
                assertEquals((e["numero"] as Long).toInt(), leido.numero, "$etiqueta: nº de '$nombre'")
                assertEquals(
                    e["lado"],
                    Conflictos.lado(conFlags(caso), leido.camino),
                    "$etiqueta: lado de '$nombre'",
                )
                reconocidos++
            }
        }
        assertTrue(reconocidos > 10, "solo $reconocidos nombres reconocidos: ¿faltan casos?")
    }

    @Test
    fun `path1 es el lado local en bisync y el remoto en un down`() {
        for (caso in Vectores.objetos(vec, "lados")) {
            val modo = MODES.getValue(Vectores.texto(caso, "modo"))
            val pareja = notas.copy(mode = modo)
            assertEquals(caso["path1"], Conflictos.lado(pareja, "path1"), "${modo.name}: path1")
            assertEquals(caso["path2"], Conflictos.lado(pareja, "path2"), "${modo.name}: path2")
        }
        assertNull(Conflictos.lado(notas, null))
    }

    // -----------------------------------------------------------------------
    // Agrupar, que es donde se deduce el lado del original
    // -----------------------------------------------------------------------

    @Test
    fun `con una sola copia se deduce el lado del original`() {
        // Tras un conflicto con ganador, rclone deja al ganador con el nombre
        // de verdad y renombra al perdedor: si hay UNA copia de un lado, el
        // original es la del otro.
        conCarpeta { raiz ->
            File(raiz, "plan.md").writeText("el ganador")
            File(raiz, "plan.md.conflicto-remoto1").writeText("el perdedor")
            val conflictos = Conflictos.escanear(notas, raiz)
            assertEquals(1, conflictos.size)
            val c = conflictos.first()
            assertEquals("plan.md", c.relativa)
            assertEquals(2, c.versiones.size)
            assertEquals(Conflictos.DISPOSITIVO, c.versiones[0].lado, "el original es del otro lado")
            assertTrue(c.versiones[0].esOriginal)
            assertEquals(1, c.copias.size)
            assertEquals(Conflictos.DISPOSITIVO, c.version(Conflictos.DISPOSITIVO)?.lado)
        }
    }

    @Test
    fun `con dos copias del mismo lado no se deduce nada`() {
        // Dos copias del mismo lado son dos conflictos seguidos sin resolver en
        // medio: elegir una sería decidir por el usuario.
        conCarpeta { raiz ->
            File(raiz, "plan.md").writeText("x")
            File(raiz, "plan.md.conflicto-remoto1").writeText("x")
            File(raiz, "plan.md.conflicto-remoto2").writeText("x")
            val c = Conflictos.escanear(notas, raiz).single()
            assertNull(c.versiones[0].lado, "el original se queda sin lado")
            assertNull(c.version(Conflictos.REMOTO), "dos del mismo lado: ninguna es LA del lado")
            assertEquals(2, c.copias.size)
        }
    }

    @Test
    fun `un conflicto cuyo original ya no está sigue siendo un conflicto`() {
        conCarpeta { raiz ->
            File(raiz, "plan.md.conflicto-dispositivo1").writeText("x")
            val c = Conflictos.escanear(notas, raiz).single()
            assertEquals("plan.md", c.relativa)
            assertTrue(c.versiones.none { it.esOriginal }, "el original no existe")
            assertEquals(1, c.copias.size)
        }
    }

    @Test
    fun `una pareja que no es bisync no tiene conflictos que buscar`() {
        // Los otros modos copian en un sentido: no hay dos versiones posibles.
        conCarpeta { raiz ->
            File(raiz, "plan.md.conflicto-remoto1").writeText("x")
            val subida = config.pairs.first { it.name == "subida" }
            assertEquals(emptyList(), Conflictos.escanear(subida, raiz))
        }
    }

    @Test
    fun `el aviso nombra unos cuantos y dice cuántos quedan`() {
        // Una pareja con cien conflictos llenaría la pantalla, y el mensaje que
        // importa es el primero.
        conCarpeta { raiz ->
            for (i in 1..8) {
                File(raiz, "f$i.md").writeText("x")
                File(raiz, "f$i.md.conflicto-remoto1").writeText("x")
            }
            val encontrados = Conflictos.escanear(notas, raiz)
            assertEquals(8, encontrados.size)
            val aviso = Conflictos.aviso("notas", encontrados)
            assertTrue(aviso != null)
            assertTrue("8 fichero(s) en conflicto" in aviso, aviso)
            assertEquals(
                Pasada.CONFLICTOS_MOSTRADOS,
                aviso.lines().count { it.startsWith("  conflicto:") },
            )
            assertTrue("y ${8 - Pasada.CONFLICTOS_MOSTRADOS} más" in aviso, aviso)
            assertNull(Conflictos.aviso("notas", emptyList()))
        }
    }

    @Test
    fun `el estado se guarda relativo al volumen y se olvida al desaparecer el fichero`() {
        // El estado es DERIVADO: al leerlo se comprueba que cada copia siga
        // ahí, así que en cuanto desaparecen los ficheros desaparece el aviso,
        // lo borre quien lo borre. Y las rutas van relativas porque `filesDir`
        // cambia al reinstalar la app.
        conCarpeta { raiz ->
            val volumen = Volumen(raiz)
            volumen.crear()
            val carpeta = volumen.carpetaLocal(notas)
            carpeta.mkdirs()
            val copia = File(carpeta, "plan.md.conflicto-remoto1")
            File(carpeta, "plan.md").writeText("x")
            copia.writeText("x")

            assertEquals(1, Conflictos.actualizarPareja(volumen, notas).size)
            val guardado = File(volumen.state, Conflictos.FICHERO).readText()
            assertTrue(raiz.path !in guardado, "la ruta absoluta no puede quedar guardada: $guardado")
            assertTrue("sync-data/notas/plan.md.conflicto-remoto1" in guardado, guardado)

            assertEquals(mapOf("notas" to 1), Conflictos.contar(Conflictos.cargar(volumen, config)))

            copia.delete()
            assertEquals(emptyMap(), Conflictos.contar(Conflictos.cargar(volumen, config)))
        }
    }

    private fun conCarpeta(prueba: (File) -> Unit) {
        val raiz = File(System.getProperty("java.io.tmpdir"), "prdrive-conf-${System.nanoTime()}")
        try {
            File(raiz, "sync-data/notas").mkdirs()
            prueba(raiz)
        } finally {
            raiz.deleteRecursively()
        }
    }
}
