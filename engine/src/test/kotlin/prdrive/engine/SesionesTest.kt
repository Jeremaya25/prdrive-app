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
}
