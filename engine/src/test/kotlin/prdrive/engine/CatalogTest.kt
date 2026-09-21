package prdrive.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CatalogTest.kt — El catálogo: dónde está, qué sale de él, y que leerlo no
 * pueda tumbar la pantalla.
 *
 * Los valores esperados salen de `vectores.json`, o sea del `common/catalog.py`
 * y del `install/deploy.py` de verdad. El `Rclone` es un doble que escribe el
 * fichero que rclone habría escrito: así se prueba el camino entero —montar la
 * llamada, leer el fichero, cachearlo— sin un rclone y sin red.
 */
class CatalogTest {

    private val vec = Vectores.seccion("catalogo")
    private val dc = Vectores.objeto(vec, "device_config")

    private fun catalogo(): Catalogo = Catalogo.leer(
        Vectores.texto(dc, "catalogo_texto"),
        Catalogo.REMOTO,
        "2026-01-02 03:04:05",
        "nas:/prdrive-catalog/pairs.toml",
    )

    /** Un rclone que hace lo que haría rclone: dejar el fichero en su sitio. */
    private class RcloneFalso(
        private val contenido: String?,
        private val estado: Int = 200,
        private val error: String = "",
    ) : Rclone {
        val llamadas = ArrayList<Pair<String, String>>()

        override fun rpc(metodo: String, entrada: String): RespuestaRpc {
            llamadas.add(metodo to entrada)
            if (contenido == null) {
                return RespuestaRpc(escribirJson(mapOf("error" to error)), estado)
            }
            val params = leerObjetoJson(entrada)
            val destino = File(params["dstFs"].toString(), params["dstRemote"].toString())
            destino.parentFile?.mkdirs()
            destino.writeText(contenido)
            return RespuestaRpc("{}", 200)
        }

        override fun logReiniciar() = Unit
        override fun logTexto(): String = ""
        override fun nivelDeLog(nombre: String) = Unit
    }

    private fun conDirectorio(prueba: (File) -> Unit) {
        val dir = File(System.getProperty("java.io.tmpdir"), "prdrive-cat-${System.nanoTime()}")
        try {
            dir.mkdirs()
            prueba(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    // -----------------------------------------------------------------------

    @Test
    fun `dónde está el catálogo lo dicen los defaults, como en el PC`() {
        for (caso in Vectores.objetos(vec, "endpoint")) {
            val defaults = Vectores.objeto(caso, "defaults")
            assertEquals(Vectores.texto(caso, "salida"), Catalogo.extremo(defaults), "$defaults")
        }
        assertEquals(Vectores.texto(vec, "default_catalog_path"), Catalogo.RUTA_POR_DEFECTO)
    }

    @Test
    fun `los flags con los que se habla con el catálogo son los del PC`() {
        // Son los que evitan que un remoto caído congele la pantalla. Se
        // comparan convertidos a argumentos, que es como los guarda prdrive,
        // porque aquí viajan como parámetros sueltos del RPC.
        assertEquals(Vectores.cadenas(vec, "net_flags"), flagsToArgs(Catalogo.FLAGS_RED))
    }

    @Test
    fun `las claves en las que difieren dos parejas son las mismas`() {
        // De esto sale la procedencia de una pareja —«catálogo», «modificada
        // aquí»—, que es derivada y no guardada.
        for (caso in Vectores.objetos(vec, "diff_keys")) {
            @Suppress("UNCHECKED_CAST")
            val a = caso["a"] as Map<String, Any?>?
            @Suppress("UNCHECKED_CAST")
            val b = caso["b"] as Map<String, Any?>?
            assertEquals(Vectores.cadenas(caso, "salida"), Catalogo.clavesDistintas(a, b), "$a vs $b")
        }
    }

    @Test
    fun `la llamada que baja el catálogo parte el extremo como quiere rclone`() {
        // `operations/copyfile` quiere el Fs y el nombre por separado, y el
        // destino es una ruta local a secas: el backend `local` está dentro.
        val peticion = Catalogo.peticionDescarga(
            "nas:/prdrive-catalog/pairs.toml",
            File("/vol/.prdrive/state/catalog.toml"),
        )
        assertEquals("operations/copyfile", peticion.metodo)
        assertEquals("nas:/prdrive-catalog", peticion.params["srcFs"])
        assertEquals("pairs.toml", peticion.params["srcRemote"])
        assertEquals("/vol/.prdrive/state", peticion.params["dstFs"])
        assertEquals("catalog.toml", peticion.params["dstRemote"])
        // Y los flags de red van sueltos, que es la única forma que funciona.
        assertEquals("10s", peticion.params["contimeout"])
        assertTrue("_config" !in peticion.params)
    }

    @Test
    fun `un extremo sin remoto o que nombra una carpeta se rechaza con su motivo`() {
        assertFailsWith<ConfigError> { Catalogo.peticionDescarga("/sin/remoto.toml", File("x")) }
        assertFailsWith<ConfigError> { Catalogo.peticionDescarga("nas:/acaba/en/barra/", File("x")) }
        assertFailsWith<ConfigError> { Catalogo.peticionDescarga("nas:", File("x")) }
        // Y un catálogo en la raíz del remoto SÍ vale: el Fs es el remoto a
        // secas, que es lo que rclone entiende por `nas:`.
        val peticion = Catalogo.peticionDescarga("nas:pairs.toml", File("/vol/catalog.toml"))
        assertEquals("nas:", peticion.params["srcFs"])
        assertEquals("pairs.toml", peticion.params["srcRemote"])
    }

    @Test
    fun `bajar el catálogo lo deja leído y cacheado`() {
        conDirectorio { dir ->
            val rclone = RcloneFalso(Vectores.texto(dc, "catalogo_texto"))
            val cat = Catalogo.bajar(rclone, mapOf("remote" to "nas"), dir)
            assertEquals(Catalogo.REMOTO, cat.origen)
            assertTrue(cat.editable, "lo que se acaba de leer del remoto sí se puede editar")
            assertEquals(Vectores.cadenas(dc, "elegidas").toSet(), cat.names.toSet().intersect(
                Vectores.cadenas(dc, "elegidas").toSet(),
            ))
            // La copia local queda escrita, que es lo que permite abrir sin red.
            assertTrue(File(dir, Catalogo.FICHERO_COPIA).isFile)
            assertTrue(File(dir, Catalogo.FICHERO_META).isFile)
            // Y se lee igual la próxima vez, ya como copia.
            val copia = Catalogo.cacheado(dir)
            assertNotNull(copia)
            assertEquals(Catalogo.COPIA, copia.origen)
            assertTrue(!copia.editable, "una copia local NO es editable")
            assertEquals(cat.names, copia.names)
            assertEquals(cat.extremo, copia.extremo, "la copia recuerda de dónde salió")
        }
    }

    @Test
    fun `sin red se cae a la copia y se dice, sin lanzar`() {
        conDirectorio { dir ->
            Catalogo.bajar(RcloneFalso(Vectores.texto(dc, "catalogo_texto")), emptyMap(), dir)
            val (cat, aviso) = Catalogo.cargar(
                RcloneFalso(null, 500, "couldn't connect: i/o timeout"),
                mapOf("remote" to "nas"),
                dir,
            )
            assertNotNull(cat)
            assertEquals(Catalogo.COPIA, cat.origen)
            assertNotNull(aviso)
            assertTrue("copia local" in aviso, aviso)
            assertTrue("i/o timeout" in aviso, "el motivo de verdad también se dice: $aviso")
        }
    }

    @Test
    fun `sin red y sin copia se dice eso, y la pantalla se abre igual`() {
        conDirectorio { dir ->
            val (cat, aviso) = Catalogo.cargar(RcloneFalso(null, 500, "no route to host"), emptyMap(), dir)
            assertNull(cat)
            assertNotNull(aviso)
            assertTrue("ni copia local" in aviso, aviso)
        }
    }

    @Test
    fun `un catálogo que no es TOML no se traga, y tampoco lanza desde cargar`() {
        conDirectorio { dir ->
            val e = assertFailsWith<TomlError> {
                Catalogo.leer("esto no { es toml", Catalogo.REMOTO, "hoy", "nas:/x.toml")
            }
            assertTrue("nas:/x.toml" in e.message.orEmpty(), e.message.orEmpty())
            // Y por la vía de la pantalla, un aviso en vez de una excepción.
            val (cat, aviso) = Catalogo.cargar(RcloneFalso("esto no { es toml"), emptyMap(), dir)
            assertNull(cat)
            assertNotNull(aviso)
        }
    }

    @Test
    fun `una copia ilegible se trata como si no hubiera copia`() {
        conDirectorio { dir ->
            File(dir, Catalogo.FICHERO_COPIA).writeText("esto no { es toml")
            assertNull(Catalogo.cacheado(dir))
        }
    }

    @Test
    fun `el catálogo dice cómo se llama el remoto y de qué tipo es`() {
        val cat = catalogo()
        assertEquals("nas", cat.remote["name"])
        assertEquals("sftp", cat.remote["type"])
        assertEquals("nas", cat.defaults["remote"], "de aquí cuelga todo remote_path")
        assertNull(cat.backendNoSoportado(), "sftp está en el .aar")
    }

    @Test
    fun `un backend que no viaja en el aar se avisa al leer el catálogo`() {
        // Es la contrapartida de haber elegido los backends curados: el aviso
        // se da aquí, no dejando que falle la primera pasada con un mensaje de
        // rclone que no explica nada.
        val cat = Catalogo.leer(
            "[remote]\nname = \"dri\"\ntype = \"drive\"\n\n[defaults]\nremote = \"dri\"\n" +
                "device_remote = \"disp\"\n\n[[pair]]\nname = \"x\"\nlocal = \"x\"\n" +
                "remote_path = \"/x\"\n",
            Catalogo.REMOTO, "hoy", "dri:/x.toml",
        )
        val aviso = cat.backendNoSoportado()
        assertNotNull(aviso)
        assertTrue("drive" in aviso, aviso)
        assertTrue("sftp" in aviso, "el aviso dice qué sí se puede: $aviso")
    }
}
