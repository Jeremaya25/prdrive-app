package prdrive.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ConflictosTest.kt — Los perdedores de bisync, leídos como los lee prdrive.
 *
 * De qué lado viene una copia sale de su nombre, y su nombre depende de los
 * flags de la pareja: con sufijos distintos el sufijo es el lado; con uno solo
 * y `pathname` el número lo es; con uno solo y `num` no se sabe. Un error aquí
 * es decirle al usuario «esta es la tuya» de la copia equivocada. Los casos y
 * los recorridos sobre disco salen del generador.
 */
class ConflictosTest {

    private val conflictos get() = Vectores.seccion("conflictos")
    private val temporales = ArrayList<File>()

    private fun temporal(): File =
        Files.createTempDirectory("prdrive-conflictos").toFile().also { temporales.add(it) }

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
    }

    /** La pareja con la que el generador calculó un esquema: la base más lo propio del caso. */
    private fun pareja(caso: String): Pareja {
        val base = Vectores.objeto(conflictos, "base")
        val extra = Vectores.objeto(Vectores.objeto(conflictos, "extras"), caso)
        return parseConfig(
            mapOf(
                "defaults" to Vectores.objeto(base, "defaults"),
                "pair" to listOf(Vectores.objeto(base, "pair") + extra),
            ),
        ).pairs[0]
    }

    @Test
    fun `las constantes de los conflictos son las de prdrive`() {
        assertEquals(Vectores.texto(conflictos, "dispositivo"), Conflictos.DISPOSITIVO)
        assertEquals(Vectores.texto(conflictos, "remoto"), Conflictos.REMOTO)
        assertEquals(Vectores.texto(conflictos, "sufijo_rclone"), Conflictos.SUFIJO_RCLONE)
        assertEquals(Vectores.texto(conflictos, "perdedor_rclone"), Conflictos.PERDEDOR_RCLONE)
        assertEquals(Vectores.cadenas(Vectores.raiz, "ruido_del_sistema"), RUIDO_DEL_SISTEMA)
    }

    @Test
    fun `el esquema de nombres sale de los flags fundidos como en rclone`() {
        for ((caso, esperado) in Vectores.objeto(conflictos, "esquemas")) {
            val e = Conflictos.esquema(pareja(caso))
            assertEquals(esperado, listOf(e.sufijo1, e.sufijo2, e.perdedor, e.mantenerExtension), "«$caso»")
        }
    }

    @Test
    fun `cada nombre se lee con su lado y su número`() {
        for (caso in Vectores.objetos(conflictos, "leer_nombre")) {
            val esquema = Conflictos.esquema(pareja(Vectores.texto(caso, "esquema")))
            val nombre = Vectores.texto(caso, "nombre")
            val leido = Conflictos.leerNombre(nombre, esquema)
            val obtenido = leido?.let { listOf(it.original, it.camino, comoJson(it.numero)) }
            assertEquals(caso["salida"], obtenido, "«${caso["esquema"]}» lee «$nombre»")
        }
    }

    @Test
    fun `un recorrido de verdad encuentra, agrupa y apunta lo mismo`() {
        val sellos = Vectores.cadenas(Vectores.raiz, "sellos")
        for (caso in Vectores.objetos(conflictos, "escenarios")) {
            val nombre = Vectores.texto(caso, "caso")
            val raiz = temporal()
            val volumen = File(raiz, "volumen")
            val state = File(raiz, "state").apply { mkdirs() }
            val crudo = Vectores.objeto(caso, "pareja")
            val config = parseConfig(
                mapOf("defaults" to mapOf("device_remote" to "disp", "remote" to "nas"), "pair" to listOf(crudo)),
            )
            val p = config.pairs[0]
            for (f in Vectores.cadenas(caso, "ficheros")) {
                File(p.carpetaEn(volumen), f).apply { parentFile.mkdirs() }.writeText("x")
            }

            var n = 0
            val encontrados = Conflictos.actualizarPareja(p, volumen, state) { sellos[n++] }
            assertEquals(
                normalizar(Vectores.lista(caso, "conflictos")),
                normalizar(encontrados.map { comoMapa(it, volumen) }),
                "«$nombre»: conflictos",
            )
            assertEquals(Vectores.texto(caso, "estado"), Conflictos.rutaEstado(state).readText(), "«$nombre»: estado")
            assertEquals(caso["sin_apuntar"], Conflictos.sinApuntar(state, p.name))
            assertEquals(
                normalizar(Vectores.objeto(caso, "contar")),
                normalizar(Conflictos.contar(mapOf(p.name to encontrados))),
            )

            val borrada = caso["borrada"]?.toString() ?: continue
            assertEquals(borrada, relativa(encontrados[0].copias[0], volumen))
            encontrados[0].copias[0].delete()
            assertEquals(
                normalizar(Vectores.objeto(caso, "cargar")),
                normalizar(Conflictos.cargar(config, volumen, state).mapValues { (_, l) -> l.map { comoMapa(it, volumen) } }),
                "«$nombre»: lo cargado sin recorrer, con una copia ya borrada",
            )
        }
    }

    private fun relativa(f: File, base: File): String =
        base.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/')

    private fun comoMapa(c: Conflictos.Conflicto, volumen: File): Map<String, Any?> = linkedMapOf(
        "pareja" to c.pareja,
        "relativa" to c.relativa,
        "original" to relativa(c.original, volumen),
        "versiones" to c.versiones.map {
            linkedMapOf(
                "ruta" to relativa(it.ruta, volumen), "lado" to it.lado,
                "es_original" to it.esOriginal, "numero" to comoJson(it.numero),
            )
        },
    )
}
