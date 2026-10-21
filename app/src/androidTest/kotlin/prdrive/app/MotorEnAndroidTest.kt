package prdrive.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import prdrive.engine.Bisync
import prdrive.engine.ConfigError
import prdrive.engine.Conflictos
import prdrive.engine.Pairing
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
 * Los nombres de los métodos no llevan espacios porque el `.dex` de minSdk 26
 * no los admite; por eso esto no reutiliza los tests de `engine/` tal cual.
 */
@RunWith(AndroidJUnit4::class)
class MotorEnAndroidTest {

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
            assertEquals("«$caso» lee «${c["nombre"]}»", c["salida"], leido?.let { listOf(it.original, it.camino, it.numero.toLong()) })
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
}
