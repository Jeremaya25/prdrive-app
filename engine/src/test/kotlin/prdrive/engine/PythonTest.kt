package prdrive.engine

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * PythonTest.kt — Las piezas de Python que el motor imita a mano, contra el
 * Python de verdad (`herramientas/vectores_python.py`): `repr()` de un float,
 * `fnmatch`, `json.loads`, `int()`, `strptime`, `==` y `sorted()` en
 * `diff_keys`, y `splitlines()`.
 */
class PythonTest {

    private val python = Vectores.seccion("python")

    @Test
    fun `un float se escribe con los digitos de repr`() {
        val floats = Vectores.objeto(python, "repr_float")
        val bits = Vectores.lista(floats, "bits")
        val reprs = Vectores.cadenas(floats, "repr")
        assertEquals(bits.size, reprs.size)
        for ((i, b) in bits.withIndex()) {
            val valor = Double.fromBits((b as Number).toLong())
            assertEquals(reprs[i], floatPy(valor), "bits ${b}")
        }
    }

    @Test
    fun `fnmatchcase casa lo mismo que el de Python`() {
        val f = Vectores.objeto(python, "fnmatch")
        val nombres = Vectores.cadenas(f, "nombres")
        val casa = Vectores.cadenas(f, "casa")
        for ((i, patron) in Vectores.cadenas(f, "patrones").withIndex()) {
            for ((j, nombre) in nombres.withIndex()) {
                assertEquals(casa[i][j] == '1', fnmatchcase(nombre, patron), "${reprPy(patron)} con ${reprPy(nombre)}")
            }
        }
    }

    @Test
    fun `leerJson acepta lo que acepta json_loads y nada mas`() {
        for (caso in Vectores.objetos(python, "json_leer")) {
            val texto = Vectores.texto(caso, "texto")
            val normal = caso["normal"] as String?
            if (normal == null) {
                assertFailsWith<JsonError>("se ha leído ${reprPy(texto.take(40))}") { leerJson(texto) }
            } else {
                assertEquals(normal, escribirJson(leerJson(texto)), "leído de ${reprPy(texto.take(40))}")
            }
        }
    }

    @Test
    fun `int lee lo mismo que el de Python`() {
        for (caso in Vectores.objetos(python, "int")) {
            assertEquals(caso["int"], enteroPy(caso["valor"])?.toString(), "int(${reprPy(caso["valor"]).take(40)})")
        }
    }

    @Test
    fun `un sello se lee como lo lee strptime`() {
        val strptime = Vectores.objeto(python, "strptime")
        assertEquals(Vectores.texto(strptime, "formato"), Store.FORMATO_PY)
        for (caso in Vectores.objetos(strptime, "casos")) {
            val texto = Vectores.texto(caso, "texto")
            @Suppress("UNCHECKED_CAST")
            val campos = (caso["campos"] as List<Number>?)?.map { it.toInt() }
            val esperado = campos?.let { LocalDateTime.of(it[0], it[1], it[2], it[3], it[4], it[5]) }
            assertEquals(esperado, Store.leerSello(texto), "strptime(${reprPy(texto)})")
            // Y la marca de tiempo es la de esa hora local, sin más.
            val marca = esperado?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            assertEquals(marca, Store.desdeSello(texto), "timestamp(${reprPy(texto)})")
        }
        assertNull(Store.desdeSello(null))
    }

    @Test
    fun `diffKeys compara con el == de Python y ordena con su sorted`() {
        for (caso in Vectores.objetos(python, "diff_keys")) {
            @Suppress("UNCHECKED_CAST")
            val (a, b) = (caso["a"] as Map<String, Any?>) to (caso["b"] as Map<String, Any?>)
            assertEquals(caso["diff"], Catalogo.diffKeys(a, b), "${reprPy(a)} contra ${reprPy(b)}")
        }
    }

    @Test
    fun `las cadenas se ordenan por punto de codigo`() {
        val orden = Vectores.objeto(python, "sorted")
        assertEquals(Vectores.cadenas(orden, "salida"), Vectores.cadenas(orden, "entrada").sortedWith(ORDEN_PY))
    }

    @Test
    fun `las lineas se parten como splitlines`() {
        val s = Vectores.objeto(python, "splitlines")
        for (caso in Vectores.objetos(s, "lineas")) {
            val texto = Vectores.texto(caso, "texto")
            assertEquals(caso["lineas"], splitlinesPy(texto), reprPy(texto))
        }
        for (caso in Vectores.objetos(s, "rclone_conf")) {
            val texto = Vectores.texto(caso, "texto")
            assertEquals(caso["remotes"], Pairing.parseRcloneConf(texto), reprPy(texto))
        }
        for (caso in Vectores.objetos(s, "cabeceras")) {
            val texto = Vectores.texto(caso, "texto")
            assertEquals(caso["cabecera"], headerOf(texto), reprPy(texto))
        }
    }
}
