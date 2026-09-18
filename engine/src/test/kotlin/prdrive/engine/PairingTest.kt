package prdrive.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PairingTest.kt — La vuelta del QR, desde el lado que la recibe.
 *
 * En el otro repo `tests/test_qr.py` da la vuelta entera —dispositivo -> carga
 * -> QR -> decodificado -> `profile.loads()`— y es lo que impide que el formato
 * del payload y el del perfil se separen. Aquí se cierra el círculo por el otro
 * extremo: la carga que genera prdrive de verdad, leída por el lector de la app.
 * Si los dos formatos se separan, falla aquí.
 */
class PairingTest {

    private val pairing get() = Vectores.seccion("pairing")

    @Test
    fun `la marca y la versión del formato son las de prdrive`() {
        // Si prdrive sube el formato y la app no, el móvil tiene que decir «el
        // aparato más antiguo de los dos» en vez de leer un payload que no
        // entiende. Eso solo funciona si los dos números son el mismo.
        assertEquals(Vectores.texto(pairing, "marca"), Pairing.MARCA)
        assertEquals(pairing["formato"], Pairing.FORMATO)
        assertEquals(Vectores.cadenas(pairing, "rutas_derivadas"), Pairing.RUTAS_DERIVADAS)
    }

    @Test
    fun `la carga que genera prdrive se lee entera`() {
        val con = Vectores.objeto(pairing, "con_clave")
        val esperado = Vectores.objeto(con, "leido")
        val carga = Pairing.leer(Vectores.texto(con, "texto"))

        assertEquals(Vectores.texto(esperado, "remote_name"), carga.remoteName)
        assertEquals(Vectores.texto(esperado, "known_hosts"), carga.knownHosts)
        assertEquals(
            esperado["private_key_len"],
            carga.privateKey?.size?.toLong(),
            "la clave privada no llega con el tamaño que salió",
        )
        // Y la longitud que dijo el generador, no solo la que dijo su propio leer.
        assertEquals(con["private_key_len"], carga.privateKey?.size?.toLong())
        assertTrue(carga.configurado, "la carga trae type, así que se puede intentar conectar")
        assertTrue(carga.necesitaClave)
    }

    @Test
    fun `las opciones del backend salen de options y de ningún otro sitio`() {
        // Es la razón por la que todas las claves sueltas van ANTES de
        // `[options]`: en TOML, lo de después de la cabecera pertenece a la
        // tabla, así que una línea traspapelada metería la clave privada dentro
        // de las opciones del backend y de ahí al rclone.conf del móvil.
        val carga = Pairing.leer(Vectores.texto(Vectores.objeto(pairing, "con_clave"), "texto"))
        assertEquals("sftp", carga.opciones["type"])
        assertTrue("private_key_b64" !in carga.opciones, "la clave NO es una opción del backend")
        assertTrue("prdrive" !in carga.opciones)
        assertTrue("known_hosts" !in carga.opciones)
        assertTrue("catalog_path" !in carga.opciones)
    }

    @Test
    fun `una carga sin clave es válida, porque hay backends que no la usan`() {
        // Un sftp con contraseña, o un webdav: no hay nada que escribir en keys/.
        val carga = Pairing.leer(Vectores.texto(Vectores.objeto(pairing, "sin_clave"), "texto"))
        assertNull(carga.privateKey)
        assertTrue(!carga.necesitaClave)
        assertTrue(carga.configurado)
    }

    @Test
    fun `lo que prdrive rechaza, la app también`() {
        for (caso in Vectores.objetos(pairing, "rechazos")) {
            val etiqueta = Vectores.texto(caso, "caso")
            val texto = Vectores.texto(caso, "texto")
            val rechazaPrdrive = caso["rechaza"] == true
            assertTrue(rechazaPrdrive, "el generador dice que prdrive acepta «$etiqueta»")
            assertFailsWith<Pairing.PairingError>("«$etiqueta» tenía que rechazarse") {
                Pairing.leer(texto)
            }
        }
    }

    @Test
    fun `el mensaje de un código ajeno dice que no es de prdrive`() {
        // Una cámara apuntando a una pantalla lee lo que le pongan delante: un
        // QR de wifi, una URL, un billete de tren. El mensaje tiene que ser ese
        // y no el error de intentar conectar con lo que sea.
        val e = assertFailsWith<Pairing.PairingError> { Pairing.leer("clave = \"valor\"\n") }
        assertTrue("no es de prdrive" in (e.message ?: ""), "mensaje: ${e.message}")

        val otro = assertFailsWith<Pairing.PairingError> { Pairing.leer("https://ejemplo.invalid") }
        assertNotNull(otro.message)
    }

    @Test
    fun `un formato futuro dice cuál actualizar`() {
        val e = assertFailsWith<Pairing.PairingError> {
            Pairing.leer("prdrive = 99\nremote_name = \"nas\"\n")
        }
        assertTrue("99" in (e.message ?: "") && "${Pairing.FORMATO}" in (e.message ?: ""))
    }

    @Test
    fun `el lector de rclone_conf lee lo mismo que el de prdrive`() {
        for (caso in Vectores.objetos(pairing, "rclone_conf")) {
            val texto = Vectores.texto(caso, "texto")
            val esperado = Vectores.objeto(caso, "remotes")
            val leido = Pairing.parseRcloneConf(texto)
            assertEquals(esperado.keys, leido.keys, "los remotes de «$texto»")
            for (nombre in esperado.keys) {
                assertEquals(
                    Vectores.objeto(esperado, nombre),
                    leido.getValue(nombre),
                    "las opciones de [$nombre]",
                )
            }
        }
    }

    @Test
    fun `el rclone_conf de la app usa rutas relativas`() {
        // Es lo que hace que el volumen siga valiendo cuando Android mueve la
        // carpeta de la app: la ruta absoluta de filesDir lleva dentro el id de
        // usuario y cambia al reinstalar.
        val carga = Pairing.leer(Vectores.texto(Vectores.objeto(pairing, "con_clave"), "texto"))
        val conf = Pairing.rcloneConf(carga)
        assertTrue("[nas]" in conf)
        assertTrue("key_file = keys/id_ed25519" in conf, conf)
        assertTrue("known_hosts_file = keys/known_hosts" in conf, conf)
        assertTrue("/data/" !in conf, "no puede aparecer ninguna ruta absoluta")

        // Y lo que se lee de vuelta es lo que rclone va a ver.
        val remotes = Pairing.parseRcloneConf(conf)
        assertEquals("sftp", remotes.getValue("nas")["type"])
        assertEquals("keys/id_ed25519", remotes.getValue("nas")["key_file"])
    }

    @Test
    fun `sin known_hosts no se escribe la opción apuntando a un fichero vacío`() {
        // rclone falla en vez de avisar si la opción está y el fichero no.
        // Aceptar la clave de host a la primera es peor, pero es peor todavía
        // no poder conectar.
        val carga = Pairing.leer(Vectores.texto(Vectores.objeto(pairing, "sin_clave"), "texto"))
        val conf = Pairing.rcloneConf(carga)
        assertTrue("known_hosts_file" !in conf, conf)
        assertTrue("key_file" !in conf, "sin clave privada no hay key_file que escribir")
    }

    @Test
    fun `la sección del combine se añade al rclone_conf sin tocar la del remoto`() {
        val carga = Pairing.leer(Vectores.texto(Vectores.objeto(pairing, "con_clave"), "texto"))
        val resueltas = Vectores.seccion("resueltas")
        val config = parseConfig(Vectores.objeto(resueltas, "config"))
        val conf = Pairing.rcloneConf(carga, config.seccionCombine("/volumen"))

        val remotes = Pairing.parseRcloneConf(conf)
        assertEquals(setOf("nas", "disp"), remotes.keys)
        assertEquals("combine", remotes.getValue("disp")["type"])
        assertEquals(
            config.upstreamsDeCombine("/volumen"),
            remotes.getValue("disp")["upstreams"],
        )
    }

    @Test
    fun `describe no enseña la clave`() {
        val carga = Pairing.leer(Vectores.texto(Vectores.objeto(pairing, "con_clave"), "texto"))
        val texto = carga.describe()
        assertTrue("nas" in texto && "sftp" in texto, texto)
        assertTrue("BEGIN" !in texto && "AAAA" !in texto, "describe() no puede llevar la clave")
    }

    @Test
    fun `una carga sin remote_name no puede escribir un rclone_conf`() {
        val carga = Pairing.leer("prdrive = 1\n")
        assertFailsWith<Pairing.PairingError> { Pairing.rcloneConf(carga) }
    }
}
