package prdrive.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

/**
 * Carpetas.kt — La carpeta de una pareja en el dispositivo: con qué otra choca y
 * qué arriesga un espejo en ella.
 *
 * Espejo de `common/carpetas.py` de prdrive (PR Jeremaya25/prdrive#202, #196,
 * todavía sin juntar: sus valores esperados salen de esa rama, en
 * `vectores_196.json`). La carpeta la elige cada dispositivo al meter una
 * pareja: «Usar aquí» (`EditarParejas.planEnable`) y el instalador
 * (`Volumen.avisosCarpetas`). Los dos avisan de lo mismo porque lo dice esto. No
 * cambia nada: solo mira el disco, y lo que no puede mirar no lo da por
 * peligroso.
 *
 * Dos peligros:
 * - **Dos parejas sobre los mismos ficheros** (la misma carpeta, o una dentro de
 *   la otra): cada una borra lo que la otra no tiene.
 * - **Un espejo en la carpeta equivocada.** El de subida hace del remoto una
 *   copia de la carpeta: vacía, lo vacía, y sin carpeta la pasada aborta sin
 *   crearla. El de bajada hace de la carpeta una copia del remoto: lo que ya
 *   tuviera y el remoto no, se borra.
 */
object Carpetas {

    /**
     * `str.casefold()` de Python, para comparar sin mayúsculas: letra a letra,
     * pasando por la mayúscula (así `ß` sale `ss` y la sigma final `σ`) y sin el
     * contexto de la sigma final que aplica `lowercase()` a un texto entero. Dos
     * letras no siguen esa regla en Python y van a mano: `ı` se queda como está y
     * `ẞ` sale `ss`. Se separa de Python solo en el cheroqui, que no se pliega a
     * la misma letra.
     */
    fun casefoldPy(texto: String): String {
        val salida = StringBuilder(texto.length)
        var i = 0
        while (i < texto.length) {
            val cp = texto.codePointAt(i)
            when (cp) {
                0x131 -> salida.appendCodePoint(cp)
                0x1E9E -> salida.append("ss")
                else -> salida.append(String(Character.toChars(cp)).uppercase(Locale.ROOT).lowercase(Locale.ROOT))
            }
            i += Character.charCount(cp)
        }
        return salida.toString()
    }

    /**
     * Los tramos de una carpeta local, para compararla con otra (`tramos`): sin
     * mayúsculas (Windows y exFAT no las distinguen), con `\` como `/` y sin
     * tramos vacíos ni `.`. La raíz entera (`"."`) no tiene ninguno, y así
     * contiene a todas.
     */
    fun tramos(local: Any?): List<String> {
        val texto = stripPy(strPy(local).replace("\\", "/"))
        return texto.split("/").filter { stripPy(it) !in listOf("", ".") }.map(::casefoldPy)
    }

    /**
     * Las parejas de [otras] (`{nombre: local}`) cuya carpeta choca con [local]
     * (`solapadas`): la misma carpeta, una que la contiene y una que está dentro.
     */
    fun solapadas(otras: Map<String, String>, local: String): List<String> {
        val mia = tramos(local)
        return otras.filter { (_, suya) ->
            val otra = tramos(suya)
            val corto = minOf(mia.size, otra.size)
            mia.subList(0, corto) == otra.subList(0, corto)
        }.keys.toList()
    }

    /** Si la carpeta tiene algo dentro; `null` si no existe o no se puede mirar (`tiene_cosas`). */
    fun tieneCosas(carpeta: File): Boolean? {
        if (!carpeta.isDirectory) return null
        return try {
            Files.newDirectoryStream(carpeta.toPath()).use { it.iterator().hasNext() }
        } catch (_: IOException) {
            null
        } catch (_: java.nio.file.DirectoryIteratorException) {
            // La lectura que falla a medias: el `OSError` de `iterdir()`.
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Qué arriesga un espejo en esa carpeta, como el final de una frase; `null`
     * si nada (`riesgo_espejo`).
     */
    fun riesgoEspejo(modo: String, carpeta: File): String? {
        if (modo == "up-mirror" && !carpeta.exists()) {
            return "no existe, y es un espejo de subida: la pasada no la crea y abortará " +
                "hasta que exista con lo que quieras subir"
        }
        val cosas = tieneCosas(carpeta)
        if (modo == "up-mirror" && cosas == false) {
            return "está vacía, y es un espejo de subida: la primera pasada vaciaría el " +
                "remoto. Pon antes lo que quieras subir"
        }
        if (modo == "down-mirror" && cosas == true) {
            return "tiene cosas, y es un espejo de bajada: la primera pasada borrará de ella " +
                "lo que no esté en el remoto"
        }
        return null
    }

    /**
     * Lo que hay que avisar antes de poner a [nombre] en la carpeta [local]
     * (`avisos`).
     *
     * @param modo su modo.
     * @param local la carpeta elegida, relativa a la raíz.
     * @param raiz la raíz del dispositivo (en la app, la del volumen).
     * @param otras las demás parejas del dispositivo, `{nombre: local}`.
     */
    fun avisos(nombre: String, modo: String, local: String, raiz: File, otras: Map<String, String>): List<String> {
        val salida = ArrayList<String>()
        val relativa = normalizarLocal(local)
        riesgoEspejo(modo, if (relativa.isEmpty()) raiz else File(raiz, relativa))?.let {
            salida.add("La carpeta '$local' de '$nombre' $it.")
        }
        val chocan = solapadas(otras, local)
        if (chocan.isNotEmpty()) {
            salida.add(
                "La carpeta '$local' de '$nombre' ya la usa ${chocan.joinToString(", ")} (la misma, o " +
                    "una dentro de la otra): dos parejas sobre los mismos ficheros se pisan los " +
                    "borrados.",
            )
        }
        return salida
    }
}
