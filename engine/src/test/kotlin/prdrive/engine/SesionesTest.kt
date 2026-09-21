package prdrive.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SesionesTest.kt — El motor contra rclone, no contra prdrive.
 *
 * `ModelTest` y `BisyncTest` comprueban que el motor dice lo mismo que el
 * Python de prdrive. Eso deja una pregunta sin responder: **¿y si los dos se
 * equivocan igual?** El nombre de los listados de bisync lo decide rclone, y
 * hasta aquí nadie se lo había preguntado a rclone.
 *
 * Estos vectores salen de `rclone/spike`, que sincroniza tres parejas de
 * verdad con rclone como biblioteca y apunta lo que rclone devolvió. Así que
 * lo que se compara aquí no es «lo que prdrive calcula» sino **lo que rclone
 * hizo**: el nombre de sesión, dónde dejó los listados, y el md5 que escribió
 * junto al fichero de filtros.
 *
 * Si esto falla, el baseline de bisync se rompería en el móvil: el motor
 * buscaría unos listados con un nombre y rclone escribiría otros con otro, así
 * que cada pasada parecería la primera y cada pasada pediría `--resync`.
 *
 *     go run ./spike -json ../engine/src/test/resources/sesiones.json
 *
 * Se regenera desde `rclone/`, y el spike falla solo si alguna de las
 * afirmaciones del PLAN.md deja de ser cierta.
 */
class SesionesTest {

    private val config = parseConfig(loadRaw(Sesiones.configToml))

    private fun pareja(nombre: String): Pareja =
        config.pairs.firstOrNull { it.name == nombre }
            ?: error("sesiones.json habla de la pareja '$nombre', que no está en su config_toml")

    @Test
    fun `el prefijo que calcula el motor es el que rclone usó`() {
        // El test que justifica todo el spike.
        for (caso in Sesiones.casos) {
            assertEquals(
                Sesiones.texto(caso, "session"),
                Bisync.expectedPrefix(pareja(Sesiones.texto(caso, "pareja"))),
                "el prefijo de '${Sesiones.texto(caso, "pareja")}' no es el que usó rclone",
            )
        }
    }

    @Test
    fun `los tres casos que pueden romper el prefijo están cubiertos`() {
        // Una pareja normal, la de la RAÍZ —que necesita el upstream con
        // nombre, `raiz`— y una con un espacio en la ruta, que es lo que
        // obliga a entrecomillar el par entero del `upstreams`. Si alguien
        // quita un caso del spike, esto lo dice.
        val locales = Sesiones.casos.map { pareja(Sesiones.texto(it, "pareja")).local }
        assertTrue("." in locales, "falta la pareja de la raíz: $locales")
        assertTrue(locales.any { " " in it }, "falta una pareja con espacios: $locales")
        assertTrue(locales.any { it != "." && " " !in it }, "falta una pareja normal: $locales")
    }

    @Test
    fun `los extremos que arma el motor son los que recibió rclone`() {
        for (caso in Sesiones.casos) {
            val p = pareja(Sesiones.texto(caso, "pareja"))
            assertEquals(Sesiones.texto(caso, "path1"), p.source, "[${p.name}] path1")
            assertEquals(Sesiones.texto(caso, "path2"), p.dest, "[${p.name}] path2")
        }
    }

    @Test
    fun `la sección del dispositivo es la que rclone resolvió`() {
        // El `upstreams` con el que rclone montó el remote `combine`, incluido
        // el entrecomillado de la ruta con espacios. Si esto no cuadra, o
        // rclone no encuentra la carpeta o el prefijo cambia.
        assertEquals(Sesiones.seccionDisp, config.seccionCombine(Sesiones.raizVolumen))
        assertTrue(
            Sesiones.rcloneConf.endsWith(Sesiones.seccionDisp),
            "el rclone.conf del spike no acaba en su sección [disp]",
        )
    }

    @Test
    fun `los listados caen donde el motor los busca`() {
        // `Pair.workdir` es `state/<nombre>`, y dentro rclone pone
        // `<session>.path1.lst`. Es lo que lee pairState() para decidir si hay
        // baseline, así que el nombre completo tiene que coincidir, no solo el
        // prefijo.
        for (caso in Sesiones.casos) {
            val p = pareja(Sesiones.texto(caso, "pareja"))
            val workDir = Sesiones.texto(caso, "workDir")
            assertTrue(
                workDir.endsWith("/${p.workdir}"),
                "[${p.name}] el workdir de rclone ($workDir) no acaba en '${p.workdir}'",
            )
            val basePath = "$workDir/${Bisync.expectedPrefix(p)}"
            assertEquals(basePath, Sesiones.texto(caso, "basePath"), "[${p.name}] basePath")
            assertEquals("$basePath.path1.lst", Sesiones.texto(caso, "listing1"))
            assertEquals("$basePath.path2.lst", Sesiones.texto(caso, "listing2"))
        }
    }

    @Test
    fun `el fichero de filtros es el que bisync firmó`() {
        // bisync guarda el md5 del fichero de filtros junto a él y solo lo
        // reescribe en un --resync. Si el motor escribiera un contenido
        // distinto —una línea más, otro orden— el md5 no cuadraría y bisync
        // abortaría con un error crítico. Aquí se compara contra el md5 que
        // bisync escribió de verdad.
        assertTrue(Sesiones.filtros.isNotEmpty(), "el spike no probó ningún fichero de filtros")
        for (f in Sesiones.filtros) {
            val p = pareja(Sesiones.texto(f, "pareja"))
            assertEquals(
                Sesiones.texto(f, "contenido"),
                Bisync.filtersContent(p),
                "[${p.name}] el contenido del fichero de filtros",
            )
        }
    }

    @Test
    fun `filtersState dice ok con el md5 que escribió bisync`() {
        // Lo anterior compara textos; esto ejecuta la función que decide, con
        // el hash real de bisync en el disco.
        val dir = File(System.getProperty("java.io.tmpdir"), "prdrive-filtros-${System.nanoTime()}")
        try {
            for (f in Sesiones.filtros) {
                val p = pareja(Sesiones.texto(f, "pareja"))
                val ffile = Bisync.filtersFileFor(p, dir)
                    ?: error("[${p.name}] el motor no quiere fichero de filtros para esta pareja")
                File("${ffile.path}.md5").writeText(Sesiones.texto(f, "md5"))
                val estado = Bisync.filtersState(ffile)
                assertEquals("ok", estado.status, "[${p.name}] ${estado.detail}")
                assertTrue(!estado.needsResync, "[${p.name}] pediría resync sin motivo")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `ningún prefijo trae el hexstring que rclone añade a las configs de paso`() {
        // La razón por la que `[disp]` va en el rclone.conf y no en variables
        // de entorno: rclone le pega un sufijo `{hexstring}` al nombre del Fs
        // cuando su configuración viene de flags, del entorno o de una
        // connection string (`cmd/bisync/bilib/canonical.go`). Definiéndolo en
        // el fichero no hay hexstring, y el nombre coincide con el que ya
        // calcula el escritorio.
        for (caso in Sesiones.casos) {
            val session = Sesiones.texto(caso, "session")
            assertTrue('{' !in session && '}' !in session, "sesión con hexstring: $session")
        }
    }

    @Test
    fun `los vectores dicen de qué rclone salieron`() {
        // Igual que con los de prdrive: si el rclone del spike no es el que
        // fija common/pins.py, lo que se comprobó no es lo que se va a
        // ejecutar.
        val version = Sesiones.texto(Sesiones.raiz, "rclone")
        assertTrue(version.startsWith("v"), "versión de rclone rara: $version")
        println("sesiones generadas con rclone $version")
    }

    // -----------------------------------------------------------------------
    // La llamada: lo que rclone aceptó de verdad
    // -----------------------------------------------------------------------

    @Test
    fun `el JSON que monta el motor es el que rclone aceptó`() {
        // El test más fuerte del fichero. Estas cadenas no son un valor
        // esperado que escribiera nadie: son el JSON EXACTO que el spike le
        // pasó a `librclone.RPC`, y de esas llamadas salieron las sesiones y
        // los listados que los tests de arriba comprueban. Así que si lo que
        // monta `Pasada` sale carácter por carácter igual, entonces sus
        // nombres, sus tipos y su forma son los que rclone acepta — no los que
        // parecían.
        //
        // Y es la única manera de comprobarlo, porque equivocarse aquí no da
        // error: un parámetro con el nombre mal escrito, o con el tipo
        // cambiado, se descarta en silencio.
        for (caso in Sesiones.casos) {
            val p = pareja(Sesiones.texto(caso, "pareja"))
            val op = Opciones(
                workdir = Sesiones.texto(caso, "workDir"),
                filtersFile = Sesiones.texto(caso, "filters_file"),
                grupo = GRUPO_DEL_SPIKE,
            )
            assertEquals(
                Sesiones.texto(caso, "rpc_resync"),
                Pasada.peticion(p, op.copy(resync = true)).json,
                "[${p.name}] la llamada del --resync",
            )
            assertEquals(
                Sesiones.texto(caso, "rpc_pasada"),
                Pasada.peticion(p, op).json,
                "[${p.name}] la llamada de una pasada normal",
            )
        }
    }

    @Test
    fun `los parámetros de la tabla son los que rclone declara, con su tipo`() {
        // `parametros_bisync` sale de la ayuda que rclone REGISTRA con el
        // método, y esa ayuda la genera su propio código (`go generate` sobre
        // `cmd/bisync/rc.md`). O sea que esto compara la tabla de `Pasada` con
        // lo que dice rclone, no con lo que leyó quien la escribió: cuando
        // rclone renombre un parámetro o le cambie el tipo, falla aquí en vez
        // de dejar de aplicarse un flag.
        val declarados = Sesiones.parametrosBisync.associate {
            Sesiones.texto(it, "nombre") to Sesiones.texto(it, "tipo")
        }
        assertTrue(declarados.size > 20, "la ayuda de sync/bisync trae $declarados")
        for ((flag, tipo) in Pasada.PARAMETROS_BISYNC) {
            val nombre = Pasada.aCamello(flag)
            if (nombre in NO_DECLARADOS) continue
            val declarado = declarados[nombre]
            assertTrue(
                declarado != null,
                "rclone no declara el parámetro '$nombre' (del flag --$flag). " +
                    "Los que declara: ${declarados.keys.sorted()}",
            )
            // Los tipos de rclone son los de su ayuda: `bool`, `int`, y para
            // todo lo demás una cadena — también `Duration` y los enumerados,
            // que se leen con GetString y se ignoran si no lo son.
            val esperado = when (declarado) {
                "bool" -> TipoRpc.BOOL
                "int" -> TipoRpc.ENTERO
                else -> TipoRpc.CADENA
            }
            assertEquals(esperado, tipo, "el tipo de '$nombre', que rclone declara ($declarado)")
        }
    }

    @Test
    fun `los flags sueltos que manda el motor son opciones que rclone conoce`() {
        // Un flag suelto que rclone no conozca lo descarta sin decir nada
        // (`configstruct.SetAny` recorre SUS items, no los de la llamada), así
        // que no hay error que esperar: o se comprueba aquí o no se comprueba.
        // `opciones_sueltas` son las etiquetas `config:` de `fs.ConfigInfo` y
        // de `filter.Options`, preguntadas a rclone en marcha.
        // Es el único test que necesita las dos fuentes: la lista de opciones
        // la da rclone, y las parejas salen del config de los vectores de
        // prdrive, que es el que trae flags de todo tipo —`transfers` en los
        // [defaults], el `max-delete` de un modo espejo, include y exclude—.
        // El config del spike solo lleva los del modo bisync, que son todos
        // parámetros del método, así que no probaría nada.
        val conocidas = Sesiones.opcionesSueltas.toSet()
        assertTrue(conocidas.size > 100, "solo ${conocidas.size} opciones: ¿se leyeron bien?")
        val deVectores = parseConfig(Vectores.objeto(Vectores.seccion("resueltas"), "config"))
        var comprobadas = 0
        for (p in deVectores.pairs) {
            val op = Opciones(workdir = "/da/igual", filtersFile = "/da/igual.txt")
            for ((clave, _) in Pasada.traducir(p, op.copy(dryRun = true)).params) {
                if (clave in DEL_METODO || clave.startsWith("_")) continue
                assertTrue(
                    clave in conocidas,
                    "[${p.name}] rclone no conoce la opción suelta '$clave', así que la " +
                        "descartaría sin avisar",
                )
                comprobadas++
            }
        }
        assertTrue(comprobadas > 0, "ninguna pareja del spike manda flags sueltos")
    }

    private companion object {
        /** El `_group` con el que el spike lanzó las pasadas. */
        const val GRUPO_DEL_SPIKE = "spike"

        /**
         * `maxDelete` lo LEE `rcBisync` —y encima valida que esté entre 0 y
         * 100, porque en bisync es un porcentaje— pero **no lo declara** en su
         * ayuda: `--max-delete` es un flag global, no uno de bisync, así que
         * `rc.md` no lo lista. Que funciona lo prueba el test de arriba: va en
         * el JSON que rclone aceptó.
         */
        val NO_DECLARADOS = setOf("maxDelete")

        /**
         * Los nombres que son parámetros del método y no opciones de rclone,
         * así que no tienen por qué estar en la lista de opciones sueltas.
         */
        val DEL_METODO = setOf(
            "path1", "path2", "srcFs", "dstFs", "workdir", "filtersFile", "resync", "dryRun",
        ) + Pasada.PARAMETROS_BISYNC.keys.map { Pasada.aCamello(it) }
    }
}
