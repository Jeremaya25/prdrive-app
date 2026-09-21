package prdrive.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * VolumenTest.kt — El volumen virtual: la distribución, y el config que sale
 * de elegir parejas del catálogo.
 *
 * El config esperado sale de `vectores.json`, o sea del
 * `install/deploy.device_config()` de verdad: es lo que en el escritorio
 * escribe el instalador y aquí escribe el primer arranque, y tiene que salir
 * igual porque después lo lee el mismo `parseConfig`.
 */
class VolumenTest {

    private val dc = Vectores.objeto(Vectores.seccion("catalogo"), "device_config")

    private fun catalogo(): Catalogo = Catalogo.leer(
        Vectores.texto(dc, "catalogo_texto"), Catalogo.REMOTO, "hoy", "nas:/x/pairs.toml",
    )

    private fun conVolumen(prueba: (Volumen) -> Unit) {
        val raiz = File(System.getProperty("java.io.tmpdir"), "prdrive-vol-${System.nanoTime()}")
        try {
            prueba(Volumen(raiz))
        } finally {
            raiz.deleteRecursively()
        }
    }

    private fun carga(): Pairing.Carga =
        Pairing.leer(Vectores.texto(Vectores.objeto(Vectores.seccion("pairing"), "con_clave"), "texto"))

    // -----------------------------------------------------------------------

    @Test
    fun `la distribución es la de un disco de verdad`() {
        // No es estética: los ficheros de estado que escribe el móvil los lee
        // el programa de escritorio sin traducir nada.
        val v = Volumen(File("/vol"))
        assertEquals(".prdrive", Volumen.APP_SUBDIR)
        assertEquals(File("/vol/.prdrive"), v.prdrive)
        assertEquals(File("/vol/.prdrive/rclone.conf"), v.rcloneConf)
        assertEquals(File("/vol/.prdrive/sync_config.toml"), v.syncConfig)
        for (d in listOf(v.keys, v.state, v.filters, v.logs)) {
            assertEquals(v.prdrive, d.parentFile)
        }
    }

    @Test
    fun `crear deja la distribución hecha y se puede repetir`() {
        conVolumen { v ->
            v.crear()
            v.crear()
            for (d in listOf(v.raiz, v.prdrive, v.keys, v.state, v.filters, v.logs)) {
                assertTrue(d.isDirectory, "falta $d")
            }
        }
    }

    @Test
    fun `el workdir y la carpeta de cada pareja cuelgan de la raíz`() {
        val config = parseConfig(Vectores.objeto(Vectores.seccion("resueltas"), "config"))
        val v = Volumen(File("/vol"))
        val notas = config.pairs.first { it.name == "notas" }
        assertEquals(File("/vol/.prdrive/state/notas"), v.workdir(notas))
        assertEquals(File("/vol/sync-data/notas"), v.carpetaLocal(notas))
        // Y la pareja de la RAÍZ es la raíz, no una carpeta llamada «.».
        val todo = config.pairs.first { it.name == "todo" }
        assertEquals(File("/vol"), v.carpetaLocal(todo))
    }

    @Test
    fun `el rclone conf lleva rutas absolutas, y por eso se reescribe`() {
        // En el escritorio `key_file = keys/…` es relativo y eso hace portable
        // el disco, porque sync.py ejecuta rclone con cwd = APP_DIR. Aquí
        // rclone es una biblioteca dentro de la app: no hay proceso al que
        // fijarle un cwd, así que las rutas van absolutas y el fichero se
        // regenera cuando la raíz cambia — que es lo que pasa al reinstalar.
        conVolumen { v ->
            val config = parseConfig(Vectores.objeto(Vectores.seccion("resueltas"), "config"))
            v.escribirClaves(carga())
            v.escribirRcloneConf(carga(), config)
            val texto = v.rcloneConf.readText()
            assertTrue(
                "key_file = ${v.keys.absolutePath}/id_ed25519" in texto,
                texto,
            )
            assertTrue("[disp]" in texto, "falta la sección del volumen:\n$texto")
            assertTrue(
                v.raiz.absolutePath in texto,
                "el upstreams tiene que llevar la raíz de verdad:\n$texto",
            )
            // Y la clave queda escrita, que eso sí pasa una sola vez.
            val clave = File(v.keys, "id_ed25519")
            assertTrue(clave.isFile)
            assertEquals(
                (Vectores.objeto(Vectores.seccion("pairing"), "con_clave")["private_key_len"] as Long),
                clave.length(),
            )
            assertTrue(File(v.keys, "known_hosts").isFile)
        }
    }

    @Test
    fun `sin parejas todavía el rclone conf ya sirve para comprobar la conexión`() {
        // El primer arranque comprueba la conexión y lee el catálogo ANTES de
        // que exista ninguna pareja, así que la sección [disp] no puede ser
        // obligatoria para escribir el fichero.
        conVolumen { v ->
            v.escribirRcloneConf(carga())
            val texto = v.rcloneConf.readText()
            assertTrue("[nas]" in texto, texto)
            assertTrue("[disp]" !in texto, texto)
        }
    }

    @Test
    fun `el config del dispositivo es el que escribe el instalador del PC`() {
        val esperado = Vectores.objeto(dc, "raw")
        val salida = Volumen.configDeDispositivo(
            catalogo(),
            Vectores.cadenas(dc, "elegidas"),
            Vectores.texto(dc, "catalog_path"),
        )
        assertEquals(normalizar(esperado), normalizar(salida))
    }

    @Test
    fun `sin ruta de catálogo se queda la que traía el catálogo`() {
        val esperado = Vectores.objeto(dc, "sin_ruta")
        val salida = Volumen.configDeDispositivo(catalogo(), Vectores.cadenas(dc, "elegidas"))
        assertEquals(normalizar(esperado), normalizar(salida))
    }

    @Test
    fun `el daemon del catálogo se recorta a las parejas que este móvil lleva`() {
        // Si nombrara una que no está, el servicio fallaría en cada ciclo. Y si
        // no queda ninguna, mejor quitar la clave que dejarla vacía.
        val soloFotos = Volumen.configDeDispositivo(catalogo(), listOf("fotos"))
        @Suppress("UNCHECKED_CAST")
        val daemon = soloFotos["daemon"] as Map<String, Any?>
        assertTrue("pairs" !in daemon, "el [daemon] del catálogo nombra 'notas', que no está: $daemon")
        assertEquals(15L, daemon["interval_minutes"], "lo demás del [daemon] se conserva")
    }

    @Test
    fun `elegir una pareja que el catálogo no tiene se rechaza, y ninguna también`() {
        val e = assertFailsWith<ConfigError> {
            Volumen.configDeDispositivo(catalogo(), listOf("notas", "inventada"))
        }
        assertTrue("inventada" in e.message.orEmpty(), e.message.orEmpty())
        assertFailsWith<ConfigError> { Volumen.configDeDispositivo(catalogo(), emptyList()) }
    }

    @Test
    fun `lo que se escribe se relee, y con las parejas dentro`() {
        // `dumpsChecked` relee lo que ha generado y se niega a escribir si el
        // dict no se reproduce: este fichero es editable a mano y lo lee el
        // programa de escritorio.
        conVolumen { v ->
            val raw = Volumen.configDeDispositivo(catalogo(), Vectores.cadenas(dc, "elegidas"))
            v.escribirConfig(raw)
            val leido = v.configActual()
            assertNotNull(leido)
            assertEquals(Vectores.cadenas(dc, "elegidas"), leido.names)
            assertEquals(DEFAULT_DEVICE_REMOTE, leido.deviceRemote)
            assertTrue(v.syncConfig.readText().startsWith("#"), "la cabecera se escribe")
        }
    }

    @Test
    fun `sin config todavía no hay config, y no es un error`() {
        conVolumen { v ->
            v.crear()
            assertEquals(null, v.configActual())
        }
    }

    @Test
    fun `las carpetas de las parejas se crean, también la de la raíz`() {
        conVolumen { v ->
            val config = parseConfig(Vectores.objeto(Vectores.seccion("resueltas"), "config"))
            v.crear()
            v.crearCarpetasDeParejas(config)
            for (pareja in config.pairs) {
                assertTrue(v.carpetaLocal(pareja).isDirectory, "[${pareja.name}] falta su carpeta")
            }
        }
    }
}
