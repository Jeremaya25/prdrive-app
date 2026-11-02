package prdrive.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CarpetasTest.kt — Con qué otra carpeta choca la de una pareja y qué arriesga
 * un espejo en ella, lo que dice `common/carpetas.py` de prdrive#202
 * (`carpetas` en `vectores_196.json`), con el disco montado igual que allí.
 */
class CarpetasTest {

    private val vectores = Vectores196.seccion("carpetas")

    private val temporales = ArrayList<File>()

    @AfterTest
    fun limpiar() {
        temporales.forEach { it.deleteRecursively() }
    }

    /**
     * Una raíz como la de `vectores_196.py` (`_Disco`: con `.prdrive/state/`) y
     * con lo que dice el caso: `x/` es una carpeta, lo demás un fichero.
     */
    private fun raiz(disco: List<String>): File {
        val raiz = File(Files.createTempDirectory("prdrive-carpetas").toFile().also { temporales.add(it) }, "volumen")
        File(raiz, "$CARPETA_PROGRAMA/state").mkdirs()
        for (rel in disco) {
            val f = File(raiz, rel)
            if (rel.endsWith("/")) f.mkdirs() else f.apply { parentFile.mkdirs(); writeText("x") }
        }
        return raiz
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapa(caso: Map<String, Any?>, clave: String): Map<String, String> =
        (caso[clave] as Map<String, Any?>).mapValues { it.value.toString() }

    @Test
    fun `casefold es el de Python`() {
        for (caso in Vectores196.raiz["casefold"] as List<*>) {
            caso as Map<*, *>
            assertEquals(caso["salida"], Carpetas.casefoldPy(caso["entrada"] as String), caso["entrada"].toString())
        }
    }

    @Test
    fun `los tramos y las que se solapan son los de carpetas_py`() {
        for (caso in Vectores.objetos(vectores, "tramos")) {
            assertEquals(Vectores.cadenas(caso, "salida"), Carpetas.tramos(caso["entrada"]), "«${caso["entrada"]}»")
        }
        for (caso in Vectores.objetos(vectores, "solapadas")) {
            assertEquals(
                Vectores.cadenas(caso, "salida"),
                Carpetas.solapadas(mapa(caso, "otras"), Vectores.texto(caso, "local")),
                "«${caso["local"]}»",
            )
        }
    }

    @Test
    fun `lo que arriesga un espejo en cada carpeta es lo de carpetas_py`() {
        for (caso in Vectores.objetos(vectores, "riesgo_espejo")) {
            val r = raiz(Vectores.cadenas(caso, "disco"))
            assertEquals(
                caso["salida"],
                Carpetas.riesgoEspejo(Vectores.texto(caso, "modo"), File(r, Vectores.texto(caso, "local"))),
                "«${caso["caso"]}», ${caso["modo"]}",
            )
        }
    }

    @Test
    fun `lo que se avisa antes de poner una pareja en una carpeta es lo de carpetas_py`() {
        for (caso in Vectores.objetos(vectores, "avisos")) {
            val r = raiz(Vectores.cadenas(caso, "disco"))
            assertEquals(
                Vectores.cadenas(caso, "salida"),
                Carpetas.avisos(
                    Vectores.texto(caso, "nombre"), Vectores.texto(caso, "modo"), Vectores.texto(caso, "local"),
                    r, mapa(caso, "otras"),
                ),
                "«${caso["caso"]}», ${caso["modo"]}, ${caso["local"]}",
            )
        }
    }
}
