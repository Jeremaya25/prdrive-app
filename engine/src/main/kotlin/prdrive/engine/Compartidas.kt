package prdrive.engine

import java.io.File
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant

/**
 * Compartidas.kt — Las parejas que viven fuera del volumen, en una carpeta de
 * la memoria compartida del teléfono (`Documents/…`), y lo que hace falta para
 * que una pareja o una ruta puedan hacerlo.
 *
 * No es espejo de ningún fichero de Python de prdrive: es una diferencia del
 * teléfono, como el `device_remote` obligatorio. El escritorio no tiene
 * volumen virtual del que sacar una carpeta, y `compartidas.json` no lo lee ni
 * lo escribe. Va en `.prdrive/`, fuera del `sync_config.toml`, para que el
 * config siga siendo el de un dispositivo de prdrive. El diseño, en
 * `docs/superpowers/specs/2026-10-26-carpeta-compartida-design.md` (§1).
 *
 * ```
 * {"vault": {"ruta": "/storage/emulated/0/Documents/vault", "estado": "lista"}}
 * ```
 *
 * El motor no toca el almacenamiento compartido: lee y escribe el fichero y
 * dice qué vale. Quien lo aplica es la app.
 */
object Compartidas {

    /** El nombre del fichero, dentro de `.prdrive/`. */
    const val FICHERO = "compartidas.json"

    /**
     * En qué punto del traslado está una pareja: [ENTRANDO] cuando se ha
     * decidido y aún no se ha terminado, [LISTA] cuando la carpeta compartida
     * es la buena.
     */
    enum class Estado(val texto: String) {
        ENTRANDO("entrando"),
        LISTA("lista"),
    }

    /** Dónde vive una pareja: la ruta absoluta de su carpeta y en qué estado está. */
    data class Entrada(val ruta: String, val estado: Estado)

    /**
     * Las entradas del fichero, por nombre de pareja; ninguna si no existe.
     *
     * Un fichero que no se lee, que no es JSON o que no tiene la forma de
     * arriba **no** se toma por vacío: sin él, las parejas compartidas
     * apuntarían a su carpeta privada del volumen, que está vacía, y la
     * pasada siguiente las daría por borradas. Por eso se lee con [leerJson]
     * y no con `Store.readJson`, que se traga los errores.
     */
    fun leer(fichero: File): Map<String, Entrada> {
        if (!fichero.exists()) return emptyMap()
        val datos = try {
            leerJson(fichero.readText(Charsets.UTF_8))
        } catch (e: IOException) {
            throw roto(fichero, "no se puede leer (${e.message})")
        } catch (e: JsonError) {
            throw roto(fichero, "no es JSON (${e.message})")
        }
        if (datos !is Map<*, *>) throw roto(fichero, "no es un objeto")
        val entradas = LinkedHashMap<String, Entrada>()
        for ((nombre, valor) in datos) {
            val entrada = valor as? Map<*, *>
            val ruta = entrada?.get("ruta")
            val estado = Estado.entries.firstOrNull { it.texto == entrada?.get("estado") }
            if (nombre !is String || ruta !is String || estado == null) {
                throw roto(
                    fichero,
                    "tiene una entrada que no es {\"ruta\": texto, \"estado\": " +
                        "\"entrando\" o \"lista\"} (la de '$nombre')",
                )
            }
            entradas[nombre] = Entrada(ruta, estado)
        }
        return entradas
    }

    private fun roto(fichero: File, motivo: String) = ConfigError(
        "El fichero de las carpetas compartidas ($fichero) $motivo. Sin él, las parejas " +
            "compartidas apuntarían a su carpeta privada del volumen, que está vacía. " +
            "Arréglalo o restáuralo desde una copia antes de sincronizar.",
    )

    /** Escribe las entradas de forma atómica; `IOException` si no se ha podido. */
    fun escribir(fichero: File, entradas: Map<String, Entrada>) {
        val datos = LinkedHashMap<String, Any?>()
        for ((nombre, entrada) in entradas) {
            datos[nombre] = linkedMapOf("ruta" to entrada.ruta, "estado" to entrada.estado.texto)
        }
        if (!Store.writeJson(fichero, datos)) throw IOException("No he podido escribir $fichero")
    }

    /**
     * Por qué la pareja no puede vivir fuera del volumen, o `null`.
     *
     * Una pareja compartida es una carpeta de primer nivel que cambia de sitio
     * (`combine` la declara por su primer tramo), así que tiene que ser una sola
     * carpeta, sin nada más colgando de ella.
     */
    fun problemaPareja(config: Config, nombre: String): String? {
        val pareja = config.pairs.firstOrNull { it.name == nombre }
            ?: return "No existe ninguna pareja que se llame '$nombre' en el config."
        if (pareja.llavero) {
            return "'$nombre' es la pareja del llavero: vive en .keychain/ y no se puede " +
                "mover. Elige otra pareja."
        }
        if (!pareja.isBisync) {
            return "'$nombre' no es bisync (es '${pareja.mode.name}'): solo las parejas " +
                "bisync pueden vivir en una carpeta compartida. Cámbiale el modo o elige otra."
        }
        if (pareja.tramosLocales.size != 1) {
            return "'$nombre' sincroniza '${pareja.local}', y solo se puede mover una pareja " +
                "cuya carpeta local sea una sola, de primer nivel. Cámbiale 'local' a un " +
                "nombre de carpeta suelto o elige otra pareja."
        }
        val deLaRaiz = config.pairs.firstOrNull { it !== pareja && it.esRaiz }
        if (deLaRaiz != null) {
            return "La pareja '${deLaRaiz.name}' sincroniza el volumen entero, y lo que saliera " +
                "de él seguiría dentro de ella. Cámbiale 'local' o quítala antes de mover '$nombre'."
        }
        val comparte = config.pairs.firstOrNull { it !== pareja && it.topLevelDir == pareja.topLevelDir }
        if (comparte != null) {
            return "La pareja '${comparte.name}' también sincroniza dentro de " +
                "'${pareja.topLevelDir}': moverla a otra carpeta la dejaría sin ella. " +
                "Cámbiale 'local' a otra carpeta o mueve solo una pareja por carpeta."
        }
        return null
    }

    /**
     * Por qué la ruta no vale como carpeta compartida de la pareja, o `null`.
     *
     * Tiene que ser absoluta y estar ya normalizada (sin `.`, `..`, tramos
     * vacíos ni `/` al final), y no puede ser el volumen ni tocarlo, ni tocar la
     * de otra pareja: dos parejas sobre las mismas carpetas se pisarían. Todo se
     * compara por tramos (`Path.startsWith`), no por texto, y vale igual con
     * rutas de Linux que con las de Windows, donde también corren los tests.
     */
    fun problemaRuta(ruta: String, raiz: File, otras: Map<String, Entrada>): String? {
        // La ruta acaba escrita en el `rclone.conf` (el upstream del `combine`):
        // un salto de línea en el nombre de una carpeta añadiría líneas al fichero.
        if (ruta.any { it.isISOControl() }) {
            return "La ruta '${ruta.filter { !it.isISOControl() }}' lleva algún carácter de control " +
                "(un salto de línea, por ejemplo) en el nombre de una carpeta. Elige otra " +
                "carpeta o renómbrala sin él."
        }
        val camino = caminoDe(ruta)
        if (camino == null || !(camino.isAbsolute || ruta.startsWith("/"))) {
            return "La ruta '$ruta' no es absoluta. Elige una carpeta con su ruta completa " +
                "(por ejemplo /storage/emulated/0/Documents/mi-carpeta)."
        }
        if (camino.normalize().toString().replace('\\', '/') != ruta.replace('\\', '/')) {
            return "La ruta '$ruta' no está normalizada: no puede llevar '.', '..', barras " +
                "repetidas ni acabar en '/'. Escríbela directa, como " +
                "/storage/emulated/0/Documents/mi-carpeta."
        }
        // La raíz, tal como se dio y resuelta: en Android los enlaces pueden
        // llevar dos nombres a la misma carpeta.
        for (volumen in listOf(raiz.absoluteFile, raiz.canonicalFile).distinct()) {
            if (solapan(camino, volumen.toPath())) {
                return "La ruta '$ruta' es el volumen de prdrive ('$volumen'), está dentro de él " +
                    "o lo contiene. Elige una carpeta que esté fuera, como una de Documents."
            }
        }
        for ((nombre, otra) in otras) {
            val caminoOtra = caminoDe(otra.ruta) ?: continue
            if (solapan(camino, caminoOtra)) {
                return "La ruta '$ruta' se solapa con la de la pareja '$nombre' (${otra.ruta}): " +
                    "dos parejas no pueden compartir carpeta ni caer una dentro de otra. " +
                    "Elige una carpeta aparte."
            }
        }
        return null
    }

    /** Las dos reglas, la de la pareja primero. */
    fun problema(config: Config, nombre: String, ruta: String, raiz: File, otras: Map<String, Entrada>): String? =
        problemaPareja(config, nombre) ?: problemaRuta(ruta, raiz, otras)

    /**
     * Si el `rclone.conf` ya apunta la carpeta de [pareja] a su carpeta
     * compartida: el `upstreams` de su `combine` ([Pareja.deviceRemote]) trae el
     * par que [Config.upstreamsDeCombine] escribiría para ella.
     *
     * Un movimiento cortado entre apuntar la entrada y reescribir `[disp]`
     * deja el `combine` en la carpeta privada del volumen, que está vacía: una
     * pasada la daría por borrada. Los pares se comparan enteros, no por
     * subcadena: el `upstreams` es una lista CSV con el espacio de separador
     * (`fs.SpaceSepList`), y una ruta con comillas o espacios no puede
     * hacerse pasar por otro par.
     */
    fun alDia(rcloneConf: String, pareja: Pareja): Boolean {
        val carpeta = pareja.carpetaExterna ?: return false
        val upstreams = Pairing.parseRcloneConf(rcloneConf)[pareja.deviceRemote]?.get("upstreams")
            ?: return false
        return upstream(pareja.topLevelDir, carpeta) in separarUpstreams(upstreams)
    }

    /** Los pares de un `upstreams`: campos separados por espacios, entrecomillados si empiezan por comilla. */
    private fun separarUpstreams(texto: String): List<String> {
        val campos = ArrayList<String>()
        var i = 0
        while (i < texto.length) {
            if (texto[i] == ' ') {
                i++
                continue
            }
            val inicio = i
            if (texto[i] == '"') {
                i++
                while (i < texto.length) {
                    if (texto[i] != '"') {
                        i++
                    } else if (i + 1 < texto.length && texto[i + 1] == '"') {
                        i += 2
                    } else {
                        break
                    }
                }
                i = minOf(i + 1, texto.length)
            } else {
                while (i < texto.length && texto[i] != ' ') i++
            }
            campos.add(texto.substring(inicio, i))
        }
        return campos
    }

    /**
     * Por qué las fechas de una carpeta no sirven para bisync, o `null`.
     *
     * Bisync decide por la fecha de modificación: si la carpeta la redondea más
     * de lo que dice o la cambia después de escribirla, una pareja ahí repetiría
     * copias para siempre. Se prueba copiando un fichero con una hora conocida
     * ([original]) y mirando la que queda ([copiada]) y la que sale 2 s después
     * ([releida]). Vale si la copia está dentro de [precision] de la original,
     * no se mueve sola y la [precision] es de verdad una precisión: cuando el
     * sitio no guarda fechas, rclone dice 100 años (`fs.ModTimeNotSupported`,
     * `fs/types.go`), y cualquier hora cabría en ella.
     */
    fun problemaFechas(original: Instant, copiada: Instant, releida: Instant, precision: Duration): String? {
        if (precision >= Duration.ofDays(1)) {
            return "Esa carpeta no guarda la fecha de modificación de los ficheros (rclone le calcula " +
                "una precisión de ${precision.toDays()} días), y sin ella bisync no sabe qué ha " +
                "cambiado. Elige una carpeta del almacenamiento interno del teléfono."
        }
        if (Duration.between(original, copiada).abs() > precision) {
            return "Esa carpeta no conserva la fecha de modificación de lo que se copia: el fichero de " +
                "prueba tenía $original y ha quedado con $copiada (rclone le da una precisión de " +
                "${precision.toNanos()} ns). Bisync repetiría copias sin parar. Elige otra carpeta."
        }
        if (releida != copiada) {
            return "Esa carpeta cambia sola la fecha de modificación de los ficheros: el de prueba " +
                "quedó con $copiada y dos segundos después tenía $releida. Bisync repetiría copias " +
                "sin parar. Elige otra carpeta."
        }
        return null
    }

    /**
     * Las carpetas de primer nivel del volumen que ya no son de su pareja: el
     * [Pareja.topLevelDir] de cada pareja de [config] con entrada en [entradas].
     *
     * Valga o no la entrada y en cualquier estado: mientras la pareja tenga
     * entrada, su carpeta privada del volumen no es la buena (o va a dejar de
     * serlo), y lo que se escriba ahí lo daría por cambios de la pareja la
     * pasada siguiente. Una entrada de una pareja que no está en el config se
     * ignora. Quien lo usa es el proveedor de documentos, que no deja escribir
     * en ellas.
     */
    fun tramosReservados(config: Config, entradas: Map<String, Entrada>): Set<String> =
        config.pairs.filter { it.name in entradas }.mapTo(LinkedHashSet()) { it.topLevelDir }

    private fun caminoDe(ruta: String): Path? = try {
        Paths.get(ruta)
    } catch (_: InvalidPathException) {
        null
    }

    /** Si una es la otra, o una cae dentro de la otra. */
    private fun solapan(a: Path, b: Path): Boolean = a.startsWith(b) || b.startsWith(a)
}
