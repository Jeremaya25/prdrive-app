package prdrive.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OraculoRcloneTest.kt — Donde el motor se separa de prdrive a propósito, el
 * valor esperado lo da rclone.
 *
 * `vectores.json` sale de prdrive, así que solo puede vigilar lo que el motor
 * hace igual que prdrive. El `\s` de `CanonicalPath` y el `Root()` del backend
 * local siguen a rclone, y sus valores esperados salen de
 * `oraculo_rclone.json`, que genera `rclone/go/oraculo` llamando al código del
 * rclone que fija `go.mod`:
 *
 *     cd rclone/go && go run ./oraculo ../../engine/src/test/resources/oraculo_rclone.json
 */
class OraculoRcloneTest {

    private val oraculo: Map<String, Any?> by lazy {
        val recurso = OraculoRcloneTest::class.java.getResourceAsStream("/oraculo_rclone.json")
            ?: error("No hay oraculo_rclone.json en los recursos de test (ver la cabecera de este fichero).")
        @Suppress("UNCHECKED_CAST")
        leerJson(recurso.bufferedReader().use { it.readText() }) as Map<String, Any?>
    }

    private fun casos(clave: String) = Vectores.objetos(oraculo, clave).also {
        assertTrue(it.isNotEmpty(), "el oráculo no trae casos de '$clave'")
    }

    @Test
    fun `el oraculo es del rclone que va en el aar`() {
        // El .aar se construye con el go.mod de rclone/go, y prdrive fija la misma.
        assertEquals(Vectores.raiz["rclone_version"], oraculo["rclone"])
    }

    @Test
    fun `la raiz de un remote local es la que escribe rclone`() {
        for (c in casos("raiz_local")) {
            val ruta = Vectores.texto(c, "ruta")
            assertEquals(Vectores.texto(c, "raiz"), Bisync.raizLocal(ruta), "ruta: ${reprPy(ruta)}")
        }
    }

    @Test
    fun `el nombre canonico separa los espacios como RE2`() {
        for (c in casos("canonical")) {
            val remote = Vectores.texto(c, "remote")
            assertEquals(Vectores.texto(c, "canonical"), Bisync.canonicalPath(remote), "remote: ${reprPy(remote)}")
            assertEquals(Vectores.texto(c, "sin_hex"), Bisync.stripHexString(remote), "remote: ${reprPy(remote)}")
        }
    }

    @Test
    fun `el nombre de sesion es el de rclone`() {
        for (c in casos("session_name")) {
            val (a, b) = Vectores.texto(c, "path1") to Vectores.texto(c, "path2")
            assertEquals(Vectores.texto(c, "session"), Bisync.sessionName(a, b), "${reprPy(a)} / ${reprPy(b)}")
        }
    }
}
