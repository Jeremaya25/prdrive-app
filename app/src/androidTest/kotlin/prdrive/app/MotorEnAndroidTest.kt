package prdrive.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.engine.Catalogo
import prdrive.engine.Resultados
import prdrive.engine.Store
import prdrive.engine.Bisync
import prdrive.engine.ConfigError
import prdrive.engine.Conflictos
import prdrive.engine.Pairing
import prdrive.engine.comoJson
import prdrive.engine.dumps
import prdrive.engine.escribirJson
import prdrive.engine.leerJson
import prdrive.engine.loadRaw
import prdrive.engine.normalizar
import prdrive.engine.parseConfig
import prdrive.engine.problemaExtra
import prdrive.engine.problemaFlag
import prdrive.engine.problemaLocal
import prdrive.engine.problemaNombre
import prdrive.engine.problemaRemote
import prdrive.engine.problemaValorFlag

/**
 * MotorEnAndroidTest.kt — Los vectores del motor, otra vez, pero en Android.
 *
 * `./gradlew :engine:test` prueba el motor en la JVM, y no basta: en Android
 * las expresiones regulares son las de ICU (`\d`, `\w` y `\s` son Unicode, y
 * `(?U)` no existe), y `String.format` usa las cifras del idioma del teléfono.
 * El spike tropezó con lo primero —el motor no llegaba a cargar—, así que aquí
 * se vuelve a pasar por lo que depende de eso: los mensajes de seguridad, el
 * nombre de sesión, los nombres de los conflictos, el TOML y el JSON.
 *
 * Todo con el teléfono en árabe: es el idioma en el que `String.format` y las
 * fechas sacarían cifras que no son ASCII si algo las pidiera al idioma.
 *
 * Los nombres de los métodos no llevan espacios porque el `.dex` de minSdk 26
 * no los admite; por eso esto no reutiliza los tests de `engine/` tal cual.
 */
@RunWith(AndroidJUnit4::class)
class MotorEnAndroidTest {

    private val idioma = Locale.getDefault()

    @Before
    fun enArabe() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
    }

    @After
    fun comoEstaba() {
        Locale.setDefault(idioma)
    }

    @Suppress("UNCHECKED_CAST")
    private val vectores: Map<String, Any?> by lazy {
        val recurso = javaClass.getResourceAsStream("/vectores.json")
            ?: error("vectores.json no está en los recursos del test")
        leerJson(recurso.bufferedReader().use { it.readText() }) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun objeto(donde: Map<String, Any?>, clave: String) = donde[clave] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun objetos(donde: Map<String, Any?>, clave: String) = donde[clave] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun cadenas(donde: Map<String, Any?>, clave: String) = donde[clave] as List<String>

    /**
     * Lo que el motor imita de Python, con las regex de ICU y el `BigDecimal`
     * de Android, que no son los de la JVM: `fnmatch` y `strptime` se traducen a
     * regex, y los dígitos de `repr()` salen de redondear un `BigDecimal`.
     */
    @Test
    fun lo_que_imita_a_python_sale_igual() {
        val python = objeto(vectores, "python")
        val floats = objeto(python, "repr_float")
        val bits = floats["bits"] as List<*>
        val reprs = cadenas(floats, "repr")
        for ((i, b) in bits.withIndex()) {
            assertEquals("bits $b", reprs[i], prdrive.engine.floatPy(Double.fromBits((b as Number).toLong())))
        }
        val f = objeto(python, "fnmatch")
        val nombres = cadenas(f, "nombres")
        val casa = cadenas(f, "casa")
        for ((i, patron) in cadenas(f, "patrones").withIndex()) {
            for ((j, nombre) in nombres.withIndex()) {
                assertEquals("«$patron» con «$nombre»", casa[i][j] == '1', prdrive.engine.fnmatchcase(nombre, patron))
            }
        }
        for (c in objetos(python, "json_leer")) {
            val normal = c["normal"] as String?
            val leido = runCatching { escribirJson(leerJson(c["texto"].toString())) }.getOrNull()
            assertEquals("json «${c["texto"].toString().take(40)}»", normal, leido)
        }
        for (c in objetos(python, "int")) {
            assertEquals("int «${c["valor"]}»", c["int"], prdrive.engine.enteroPy(c["valor"])?.toString())
        }
        for (c in objetos(objeto(python, "strptime"), "casos")) {
            @Suppress("UNCHECKED_CAST")
            val campos = (c["campos"] as List<Number>?)?.map { it.toInt() }
            val esperado = campos?.let { LocalDateTime.of(it[0], it[1], it[2], it[3], it[4], it[5]) }
            assertEquals("strptime «${c["texto"]}»", esperado, Store.leerSello(c["texto"].toString()))
        }
        val orden = objeto(python, "sorted")
        assertEquals(cadenas(orden, "salida"), cadenas(orden, "entrada").sortedWith(prdrive.engine.ORDEN_PY))
        for (c in objetos(objeto(python, "splitlines"), "rclone_conf")) {
            assertEquals(c["remotes"], Pairing.parseRcloneConf(c["texto"].toString()))
        }
    }

    /**
     * Donde el motor sigue a rclone y no a prdrive, contra el oráculo de rclone
     * (`rclone/go/oraculo`): el `\s` de `CanonicalPath` es justo lo que en ICU
     * significa otra cosa.
     */
    @Test
    fun el_nombre_canonico_y_la_raiz_local_son_los_de_rclone() {
        val recurso = javaClass.getResourceAsStream("/oraculo_rclone.json")
            ?: error("oraculo_rclone.json no está en los recursos del test")
        @Suppress("UNCHECKED_CAST")
        val oraculo = leerJson(recurso.bufferedReader().use { it.readText() }) as Map<String, Any?>
        for (c in objetos(oraculo, "raiz_local")) {
            assertEquals("raíz de «${c["ruta"]}»", c["raiz"], Bisync.raizLocal(c["ruta"].toString()))
        }
        for (c in objetos(oraculo, "canonical")) {
            assertEquals("canónico de «${c["remote"]}»", c["canonical"], Bisync.canonicalPath(c["remote"].toString()))
        }
        for (c in objetos(oraculo, "session_name")) {
            assertEquals(c["session"], Bisync.sessionName(c["path1"].toString(), c["path2"].toString()))
        }
    }

    @Test
    fun los_mensajes_de_seguridad_son_los_de_prdrive() {
        val s = objeto(vectores, "seguridad")
        for (c in objetos(s, "problema_flag")) {
            assertEquals("problemaFlag(${c["entrada"]})", c["salida"], problemaFlag(c["entrada"].toString()))
        }
        for (c in objetos(s, "problema_extra")) {
            @Suppress("UNCHECKED_CAST")
            val args = (c["entrada"] as List<Any?>).map { it.toString() }
            assertEquals("problemaExtra($args)", c["salida"], problemaExtra(args))
        }
        for (c in objetos(s, "problema_valor_flag")) {
            assertEquals("problemaValorFlag(${c["entrada"]})", c["salida"], problemaValorFlag(c["entrada"]))
        }
        for (c in objetos(s, "problema_remote")) {
            val clave = c["clave"]?.toString() ?: "remote"
            assertEquals("problemaRemote(${c["entrada"]})", c["salida"], problemaRemote(c["entrada"], clave))
        }
        for (c in objetos(s, "problema_nombre")) {
            assertEquals("problemaNombre(${c["entrada"]})", c["salida"], problemaNombre(c["entrada"]))
        }
        for (c in objetos(s, "problema_local")) {
            assertEquals("problemaLocal(${c["entrada"]})", c["salida"], problemaLocal(c["entrada"].toString()))
        }
    }

    @Test
    fun los_configs_se_aceptan_o_rechazan_igual() {
        for (c in objetos(objeto(vectores, "seguridad"), "configs")) {
            val obtenido = try {
                parseConfig(objeto(c, "config"))
                null
            } catch (e: ConfigError) {
                e.message
            } catch (e: Exception) {
                fail("«${c["caso"]}»: ${e::class.java.simpleName}: ${e.message}")
            }
            if (c["rompe"] != null) {
                assertNotNull("«${c["caso"]}» tenía que rechazarse", obtenido)
            } else {
                assertEquals("«${c["caso"]}»", c["error"], obtenido)
            }
        }
    }

    @Test
    fun el_nombre_de_sesion_y_los_extremos_son_los_mismos() {
        val canonical = objeto(vectores, "canonical")
        for (c in objetos(canonical, "canonical_path")) {
            assertEquals(c["salida"], Bisync.canonicalPath(c["entrada"].toString()))
        }
        for (c in objetos(canonical, "session_name")) {
            assertEquals(c["salida"], Bisync.sessionName(c["path1"].toString(), c["path2"].toString()))
        }
        val extremos = objeto(vectores, "extremos")
        val tipos = Bisync.tiposDeRemote(extremos["rclone_conf"].toString())
        for (c in objetos(extremos, "fs_path_remote")) {
            assertEquals(c["salida"], Bisync.fsPathRemote(c["entrada"].toString(), tipos))
        }
        for (c in objetos(extremos, "raiz_local")) {
            assertEquals(c["salida"], Bisync.raizLocal(c["entrada"].toString()))
        }
        val resueltas = objeto(vectores, "resueltas")
        val config = parseConfig(objeto(resueltas, "config"))
        val parejas = objetos(resueltas, "parejas")
        assertEquals(parejas.size, config.pairs.size)
        for ((esperada, pareja) in parejas.zip(config.pairs)) {
            assertEquals(esperada["expected_prefix"], Bisync.expectedPrefix(pareja, emptyMap()))
            assertEquals(esperada["filters_content"], Bisync.filtersContent(pareja))
        }
    }

    @Test
    fun los_nombres_de_conflicto_se_leen_igual() {
        val conflictos = objeto(vectores, "conflictos")
        val base = objeto(conflictos, "base")
        val extras = objeto(conflictos, "extras")
        for (c in objetos(conflictos, "leer_nombre")) {
            val caso = c["esquema"].toString()
            val pareja = parseConfig(
                mapOf("defaults" to objeto(base, "defaults"), "pair" to listOf(objeto(base, "pair") + objeto(extras, caso))),
            ).pairs[0]
            val leido = Conflictos.leerNombre(c["nombre"].toString(), Conflictos.esquema(pareja))
            assertEquals("«$caso» lee «${c["nombre"]}»", c["salida"], leido?.let { listOf(it.original, it.camino, comoJson(it.numero)) })
        }
    }

    @Test
    fun el_toml_y_el_json_se_escriben_igual() {
        for (c in objetos(objeto(vectores, "toml"), "dumps")) {
            assertEquals(c["texto"], dumps(objeto(c, "raw"), c["head"].toString()))
            assertEquals(normalizar(c["raw"]), normalizar(loadRaw(c["texto"].toString())))
        }
        @Suppress("UNCHECKED_CAST")
        for (c in vectores["json"] as List<Map<String, Any?>>) {
            assertEquals(c["texto"], escribirJson(c["valor"]))
        }
    }

    @Test
    fun la_carga_del_qr_se_lee_y_la_basura_se_rechaza() {
        val pairing = objeto(vectores, "pairing")
        val carga = Pairing.leer(objeto(pairing, "con_clave")["texto"].toString())
        assertTrue(carga.configurado)
        for (c in objetos(pairing, "rechazos")) {
            try {
                Pairing.leer(c["texto"].toString())
                fail("«${c["caso"]}» tenía que rechazarse")
            } catch (_: Pairing.PairingError) {
                // lo esperado
            }
        }
    }

    @Test
    fun las_fechas_salen_en_cifras_ascii() {
        val store = objeto(vectores, "store")
        assertEquals(store["ejemplo"], Store.sello(LocalDateTime.of(2026, 1, 2, 3, 4, 5)))
        val estado = objeto(vectores, "estado")
        @Suppress("UNCHECKED_CAST")
        val f = (estado["fecha_apartado"] as List<Long>).map { it.toInt() }
        assertEquals(
            estado["sello_apartado"],
            Bisync.selloApartado(LocalDateTime.of(f[0], f[1], f[2], f[3], f[4], f[5])),
        )
    }

    @Test
    fun el_catalogo_se_lee_igual() {
        val catalogo = objeto(vectores, "catalogo")
        @Suppress("UNCHECKED_CAST")
        val sellos = vectores["sellos"] as List<String>
        val base = File(Banco.contexto.cacheDir, "motor-catalogo")
        for ((n, caso) in objetos(catalogo, "escenarios").withIndex()) {
            val tabla = objeto(caso, "rclone")
            val respuestas = objeto(tabla, "respuestas")
            val defecto = tabla["por_defecto"] as List<*>
            val rclone = Catalogo.Ejecutar { args ->
                val r = respuestas[args.joinToString(" ")] as? List<*> ?: defecto
                Catalogo.Resultado((r[0] as Long).toInt(), r[1].toString(), r[2].toString())
            }
            val state = File(base, "$n").apply { deleteRecursively(); mkdirs() }
            @Suppress("UNCHECKED_CAST")
            val local = caso["raw_local"] as Map<String, Any?>?
            val pull = objeto(caso, "pull")
            val obtenido = runCatching { Catalogo.pull(rclone, local, state) { sellos[0] } }
            if (pull["error"] != null) {
                val msg = obtenido.exceptionOrNull()?.message ?: ""
                assertTrue("«${caso["caso"]}»: $msg", msg.startsWith(pull["error"].toString()))
            } else {
                assertEquals("«${caso["caso"]}»", pull["endpoint"], obtenido.getOrThrow().endpoint)
                assertEquals(objeto(caso, "copia")["json"], Catalogo.cacheMeta(state).readText())
            }
        }
        base.deleteRecursively()
    }

    @Test
    fun last_run_se_escribe_igual() {
        val resultados = objeto(vectores, "resultados")
        @Suppress("UNCHECKED_CAST")
        val sellos = vectores["sellos"] as List<String>
        val raiz = File(Banco.contexto.cacheDir, "motor-resultados").apply { deleteRecursively() }
        val state = File(raiz, "state").apply { mkdirs() }
        val logs = File(raiz, "logs").apply { mkdirs() }
        var n = 0
        for (paso in objetos(resultados, "pasos")) {
            val log = paso["log"]?.let { File(logs, it.toString()) }
            Resultados.apuntar(state, paso["nombre"].toString(), (paso["codigo"] as Long).toInt(), log) { sellos[n++] }
            assertEquals(paso["texto"], Resultados.rutaEstado(state).readText())
        }
        raiz.deleteRecursively()
    }

    @Test
    fun los_conflictos_se_encuentran_igual_y_el_ruido_se_ignora() {
        val conflictos = objeto(vectores, "conflictos")
        @Suppress("UNCHECKED_CAST")
        val sellos = vectores["sellos"] as List<String>
        val base = File(Banco.contexto.cacheDir, "motor-conflictos")
        for ((n, caso) in objetos(conflictos, "escenarios").withIndex()) {
            val raiz = File(base, "$n").apply { deleteRecursively() }
            val volumen = File(raiz, "volumen")
            val state = File(raiz, "state").apply { mkdirs() }
            val config = parseConfig(
                mapOf("defaults" to mapOf("device_remote" to "disp", "remote" to "nas"), "pair" to listOf(objeto(caso, "pareja"))),
            )
            val p = config.pairs[0]
            @Suppress("UNCHECKED_CAST")
            for (f in caso["ficheros"] as List<String>) File(p.carpetaEn(volumen), f).apply { parentFile!!.mkdirs() }.writeText("x")
            Conflictos.actualizarPareja(p, volumen, state) { sellos[0] }
            assertEquals("«${caso["caso"]}»", caso["estado"], Conflictos.rutaEstado(state).readText())
        }
        base.deleteRecursively()
    }
}
