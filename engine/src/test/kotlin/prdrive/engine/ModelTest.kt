package prdrive.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ModelTest.kt — El motor contra lo que dice prdrive, caso por caso.
 *
 * Ni un valor esperado escrito a mano: todos salen de `vectores.json`, generado
 * desde el checkout de prdrive con `herramientas/vectores.py`. Cuando prdrive
 * mueva una constante, aquí falla el test que la nombra y dice cuál.
 */
class ModelTest {

    @Test
    fun `los vectores dicen de qué prdrive salieron`() {
        // No comprueba nada del motor: existe para que el fallo de cualquier
        // otro test se pueda leer sabiendo contra qué versión está escrito.
        val version = Vectores.texto(Vectores.prdrive, "version")
        assertTrue(version.isNotEmpty(), "los vectores no dicen la versión de prdrive")
        println("vectores generados desde prdrive $version")
    }

    @Test
    fun `las constantes sueltas no se han movido`() {
        val c = Vectores.seccion("constantes")
        assertEquals(Vectores.texto(c, "DEFAULT_REMOTE"), DEFAULT_REMOTE)
        assertEquals(Vectores.texto(c, "DEFAULT_MODE"), DEFAULT_MODE)
        assertEquals(Vectores.texto(c, "DEFAULT_DEVICE_REMOTE"), DEFAULT_DEVICE_REMOTE)
        assertEquals(Vectores.texto(c, "RAIZ_UPSTREAM"), RAIZ_UPSTREAM)
        assertEquals(Vectores.texto(c, "PATH1_SUFFIX"), Bisync.PATH1_SUFFIX)
        assertEquals(Vectores.texto(c, "PATH2_SUFFIX"), Bisync.PATH2_SUFFIX)
        assertEquals(Vectores.texto(c, "ERR_SUFFIX"), Bisync.ERR_SUFFIX)
        assertEquals(Vectores.texto(c, "MISSING_LISTINGS"), Bisync.MISSING_LISTINGS)
        assertEquals(Vectores.texto(c, "FILTERS_HEADER"), Bisync.FILTERS_HEADER)
    }

    @Test
    fun `la capa base de flags es la misma`() {
        assertEquals(normalizar(Vectores.seccion("base_flags")), normalizar(BASE_FLAGS))
    }

    @Test
    fun `cada modo lleva su verbo, su sentido y sus flags`() {
        val modos = Vectores.seccion("modos")
        assertEquals(modos.keys, MODES.keys, "la lista de modos no es la misma")
        for ((nombre, esperadoCrudo) in modos) {
            val esperado = Vectores.objeto(modos, nombre)
            val modo = MODES.getValue(nombre)
            assertEquals(Vectores.texto(esperado, "verb"), modo.verb, "$nombre: verbo")
            assertEquals(Vectores.texto(esperado, "source"), modo.source, "$nombre: origen")
            assertEquals(Vectores.texto(esperado, "dest"), modo.dest, "$nombre: destino")
            assertEquals(
                normalizar(Vectores.objeto(esperado, "flags_propios")),
                normalizar(modo.flags),
                "$nombre: flags del modo",
            )
            // Y la fusión con la base, que es lo que de verdad recibe rclone.
            assertEquals(
                normalizar(Vectores.objeto(esperado, "flags_con_base")),
                normalizar(BASE_FLAGS + modo.flags),
                "$nombre: flags fundidos con la base",
            )
            assertTrue(esperadoCrudo != null)
        }
    }

    @Test
    fun `los flags se traducen a argumentos de rclone igual`() {
        for (caso in Vectores.objetos(Vectores.raiz, "flags_to_args")) {
            val flags = Vectores.objeto(caso, "flags")
            assertEquals(
                Vectores.cadenas(caso, "args"),
                flagsToArgs(flags),
                "flagsToArgs($flags)",
            )
        }
    }

    @Test
    fun `el upstreams del combine se entrecomilla como lo lee rclone`() {
        for (caso in Vectores.objetos(Vectores.raiz, "upstream")) {
            val nombre = Vectores.texto(caso, "nombre")
            val ruta = Vectores.texto(caso, "ruta")
            assertEquals(
                Vectores.texto(caso, "salida"),
                upstream(nombre, ruta),
                "upstream($nombre, $ruta)",
            )
        }
    }

    @Test
    fun `una config entera se resuelve en las mismas parejas`() {
        val resueltas = Vectores.seccion("resueltas")
        val config = parseConfig(Vectores.objeto(resueltas, "config"))

        assertEquals(Vectores.cadenas(resueltas, "names"), config.names)
        assertEquals(Vectores.texto(resueltas, "device_remote"), config.deviceRemote)
        assertEquals(resueltas["keep_logs"], config.keepLogs)

        val esperadas = Vectores.objetos(resueltas, "parejas")
        assertEquals(esperadas.size, config.pairs.size, "número de parejas")
        for ((esperada, pareja) in esperadas.zip(config.pairs)) {
            val n = Vectores.texto(esperada, "name")
            assertEquals(n, pareja.name)
            assertEquals(Vectores.texto(esperada, "mode"), pareja.mode.name, "$n: modo")
            assertEquals(Vectores.texto(esperada, "local"), pareja.local, "$n: local")
            assertEquals(
                Vectores.texto(esperada, "remote_path"), pareja.remotePath, "$n: remote_path",
            )
            assertEquals(
                Vectores.texto(esperada, "remote_name"), pareja.remoteName, "$n: remote",
            )
            assertEquals(Vectores.cadenas(esperada, "includes"), pareja.includes, "$n: includes")
            assertEquals(Vectores.cadenas(esperada, "excludes"), pareja.excludes, "$n: excludes")
            assertEquals(
                normalizar(Vectores.objeto(esperada, "flags")),
                normalizar(pareja.flags),
                "$n: flags fundidos",
            )
            assertEquals(
                Vectores.cadenas(esperada, "extra_flags"), pareja.extraFlags, "$n: extra_flags",
            )
            assertEquals(
                esperada["use_filters_file"], pareja.useFiltersFile, "$n: use_filters_file",
            )
            assertEquals(
                esperada["wants_filters_file"], pareja.wantsFiltersFile, "$n: wants_filters_file",
            )
            assertEquals(esperada["is_bisync"], pareja.isBisync, "$n: is_bisync")
            assertEquals(
                Vectores.cadenas(esperada, "tramos_locales"),
                pareja.tramosLocales,
                "$n: tramos de la ruta local",
            )
            assertEquals(
                Vectores.texto(esperada, "top_level_dir"), pareja.topLevelDir, "$n: upstream",
            )
            assertEquals(
                Vectores.texto(esperada, "ruta_en_combine"),
                pareja.rutaEnCombine,
                "$n: ruta dentro del combine",
            )
            assertEquals(
                Vectores.texto(esperada, "local_endpoint"),
                pareja.localEndpoint,
                "$n: extremo local",
            )
            assertEquals(
                Vectores.texto(esperada, "remote_endpoint"),
                pareja.remoteEndpoint,
                "$n: extremo remoto",
            )
            assertEquals(Vectores.texto(esperada, "source"), pareja.source, "$n: origen")
            assertEquals(Vectores.texto(esperada, "dest"), pareja.dest, "$n: destino")
            assertEquals(Vectores.texto(esperada, "workdir"), pareja.workdir, "$n: workdir")
        }
    }

    @Test
    fun `el upstreams se calcula con todas las parejas, no con las elegidas`() {
        val resueltas = Vectores.seccion("resueltas")
        val config = parseConfig(Vectores.objeto(resueltas, "config"))
        val entorno = Vectores.objeto(resueltas, "pen_environment")
        val raiz = Vectores.texto(Vectores.raiz, "raiz_dispositivo")

        // El escritorio lo pasa por variables de entorno y la app lo escribe en
        // su rclone.conf; lo que no puede cambiar es el texto, porque de él sale
        // el nombre de los listados de bisync.
        val esperado = entorno.getValue("RCLONE_CONFIG_DISP_UPSTREAMS").toString()
        assertEquals(esperado, config.upstreamsDeCombine(raiz))
        assertEquals("combine", entorno.getValue("RCLONE_CONFIG_DISP_TYPE"))

        assertEquals(
            "[disp]\ntype = combine\nupstreams = $esperado\n",
            config.seccionCombine(raiz),
        )
    }

    @Test
    fun `select devuelve las pedidas y se niega con un nombre que no existe`() {
        val resueltas = Vectores.seccion("resueltas")
        val config = parseConfig(Vectores.objeto(resueltas, "config"))
        assertEquals(listOf("notas", "todo"), config.select(listOf("todo", "notas")).map { it.name })
        val e = assertFailsWith<ConfigError> { config.select(listOf("notas", "inventada")) }
        assertTrue("inventada" in (e.message ?: ""), "el mensaje no nombra la pareja que falta")
    }

    @Test
    fun `un modo inventado se rechaza al leer, no al ejecutar`() {
        val e = assertFailsWith<ConfigError> {
            parseConfig(
                mapOf(
                    "defaults" to mapOf("device_remote" to "disp"),
                    "pair" to listOf(
                        mapOf("name" to "x", "local" to "a", "remote_path" to "/a", "mode" to "sync"),
                    ),
                ),
            )
        }
        assertTrue("'sync'" in (e.message ?: ""), "el mensaje no dice qué modo es inválido")
    }

    @Test
    fun `sin device_remote la app se niega, y el escritorio no`() {
        // Diferencia deliberada con prdrive: allí un config antiguo sin
        // device_remote funciona con rutas absolutas. Aquí la ruta absoluta es
        // `filesDir`, que cambia al reinstalar la app, así que aceptarlo sería
        // aceptar romper los baselines más adelante.
        val e = assertFailsWith<ConfigError> {
            parseConfig(
                mapOf(
                    "defaults" to mapOf("remote" to "nas"),
                    "pair" to listOf(mapOf("name" to "x", "local" to "a", "remote_path" to "/a")),
                ),
            )
        }
        assertTrue("device_remote" in (e.message ?: ""))
    }

    @Test
    fun `un device_remote con guiones se rechaza`() {
        // No cabe en RCLONE_CONFIG_<NOMBRE>_*, que es como lo pasa el
        // escritorio: si aquí se aceptara, el mismo catálogo daría un nombre de
        // listado en el móvil y otro en el PC.
        assertFailsWith<ConfigError> { nombreDeviceRemote(mapOf("device_remote" to "mi-disp")) }
        assertEquals("disp", nombreDeviceRemote(mapOf("device_remote" to "disp")))
        assertEquals(null, nombreDeviceRemote(emptyMap()))
    }

    @Test
    fun `dos parejas no pueden declarar el mismo upstream en sitios distintos`() {
        // Solo pasa con la raíz: `local = "."` la declara como RAIZ_UPSTREAM y
        // otra pareja tiene una carpeta llamada justo así. Sería un upstream
        // apuntando a donde no es, callando.
        val config = parseConfig(
            mapOf(
                "defaults" to mapOf("device_remote" to "disp", "remote" to "nas"),
                "pair" to listOf(
                    mapOf("name" to "todo", "local" to ".", "remote_path" to "/a"),
                    mapOf("name" to "choque", "local" to RAIZ_UPSTREAM, "remote_path" to "/b"),
                ),
            ),
        )
        val e = assertFailsWith<ConfigError> { config.upstreamsDeCombine("/volumen") }
        assertTrue(RAIZ_UPSTREAM in (e.message ?: ""))
    }
}
