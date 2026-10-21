package prdrive.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Conflictos.kt — Los ficheros en conflicto que deja bisync, vistos desde el
 * teléfono.
 *
 * Espejo de `common/conflicts.py` de prdrive. Cuando un mismo fichero cambia en
 * los dos lados entre dos pasadas, bisync no elige en silencio: con
 * `--conflict-resolve newer` se queda con el más reciente, renombra al otro con
 * un sufijo y copia los dos a los dos lados. Solo queda una línea en el log y un
 * fichero con un nombre raro que nadie mira, mientras las dos versiones siguen
 * separándose. Esto los encuentra y dice de qué lado viene cada uno. **No
 * decide nada ni toca ninguno**: en el paso 1 la app solo avisa.
 *
 * El estado es DERIVADO: `state/conflicts.json` solo guarda lo que encontró el
 * último recorrido, para pintar la pantalla sin recorrer el árbol; al leerlo se
 * comprueba que cada fichero sigue ahí, así que el aviso desaparece cuando
 * desaparecen los ficheros, los borre quien los borre.
 *
 * Réplica de rclone, como [Bisync]: el nombre del perdedor lo decide
 * cmd/bisync/resolve.go (`setResolveDefaults`, `resolve`, `SuffixName`) y la
 * posición del sufijo lib/transform/transform.go (`SuffixKeepExtension`). Se lee
 * de los flags YA FUNDIDOS de la pareja, que es lo que recibe rclone.
 *
 * Las carpetas se pasan (el volumen y `state/`): el motor no sabe dónde están.
 */
object Conflictos {

    const val DISPOSITIVO = "dispositivo"
    const val REMOTO = "remoto"

    /**
     * Sufijo de conflicto que rclone pone por defecto (`setResolveDefaults`). Se
     * sigue reconociendo aunque la pareja lleve otros: son los de conflictos
     * anteriores al cambio.
     */
    const val SUFIJO_RCLONE = "conflict"

    /** Valor por defecto de `--conflict-loser` (`setResolveDefaults`). */
    const val PERDEDOR_RCLONE = "num"

    /** `state/conflicts.json`. */
    fun rutaEstado(stateDir: File): File = File(stateDir, "conflicts.json")

    /**
     * Cómo nombra rclone a los perdedores de una pareja concreta: el sufijo de
     * cada lado (con su punto), el `--conflict-loser` y si el sufijo va delante
     * de la extensión.
     */
    data class Esquema(
        val sufijo1: String,
        val sufijo2: String,
        val perdedor: String,
        val mantenerExtension: Boolean,
    )

    /**
     * Un flag de la pareja, con guiones o con guiones bajos: los dos acaban
     * siendo el mismo argumento, y si están los dos rclone se queda con el que
     * va más tarde en los flags fundidos.
     */
    private fun flag(pareja: Pareja, nombre: String): Any? {
        var valor: Any? = null
        for ((clave, v) in pareja.flags) {
            if (clave.replace("_", "-") == nombre) valor = v
        }
        return valor
    }

    /**
     * El esquema de nombres de conflicto de una pareja. Replica
     * `setResolveDefaults()`: un sufijo vale para los dos lados; dos separados
     * por coma son uno para cada uno. Una pareja con `versions` lleva siempre
     * `--suffix-keep-extension`: no está en sus flags, lo pone la pasada.
     */
    fun esquema(pareja: Pareja): Esquema {
        val crudo = flag(pareja, "conflict-suffix").takeIf { verdad(it) }?.let { strPy(it) } ?: SUFIJO_RCLONE
        val partes = crudo.split(",").filter { it.isNotEmpty() }.ifEmpty { listOf(SUFIJO_RCLONE) }
        val (s1, s2) = if (partes.size == 1) partes[0] to partes[0] else partes[0] to partes[1]
        return Esquema(
            sufijo1 = ".$s1",
            sufijo2 = ".$s2",
            perdedor = flag(pareja, "conflict-loser").takeIf { verdad(it) }?.let { strPy(it) }
                ?: PERDEDOR_RCLONE,
            mantenerExtension = verdad(flag(pareja, "suffix-keep-extension")) || pareja.versions,
        )
    }

    /**
     * El patrón de nombre de un perdedor: `SuffixName` pone el sufijo al final
     * (`plan.md.conflict1`) o, con `--suffix-keep-extension`, delante de la
     * extensión (`plan.conflict1.md`). Grupos: 1 el nombre, 2 el número, 3 la
     * extensión. El `\d` de Python es Unicode: `\p{Nd}`, escrito así porque
     * Android no admite `(?U)`.
     */
    private fun patron(sufijo: String, mantenerExtension: Boolean): Regex {
        val extension = if (mantenerExtension) "((?:\\.[^.]+)+)" else "()"
        return Regex("^(.+)${Regex.escape(sufijo)}(\\p{Nd}*)$extension$")
    }

    /** Lo que se lee del nombre de un perdedor: su original, su camino y su número. */
    data class Nombre(val original: String, val camino: String?, val numero: Int)

    /**
     * Interpreta el nombre de un fichero como el de un perdedor de conflicto, o
     * `null` si no lo es. Qué significa el número depende del esquema (ver
     * `resolve()`):
     * - sufijos distintos: el sufijo es el lado y el número solo un orden;
     * - un sufijo y `--conflict-loser pathname`: `1` es path1 y `2` es path2;
     * - un sufijo y `--conflict-loser num`: el número es el primero que estaba
     *   libre (`numerate`), así que el lado no se sabe.
     */
    fun leerNombre(nombre: String, esq: Esquema): Nombre? {
        val candidatos = listOf(esq.sufijo1, esq.sufijo2, ".$SUFIJO_RCLONE")
        if (candidatos.none { it in nombre }) return null
        for (sufijo in candidatos.distinct()) {
            val m = patron(sufijo, esq.mantenerExtension).matchEntire(nombre) ?: continue
            val original = m.groupValues[1] + m.groupValues[3]
            val cifras = m.groupValues[2]
            val numero = if (cifras.isEmpty()) 0 else cifras.toInt()
            if (esq.sufijo1 != esq.sufijo2 && (sufijo == esq.sufijo1 || sufijo == esq.sufijo2)) {
                return Nombre(original, if (sufijo == esq.sufijo1) "path1" else "path2", numero)
            }
            if (cifras.isEmpty()) continue // un solo sufijo sin número no lo escribe rclone
            if (sufijo == esq.sufijo1 && esq.perdedor == "pathname" && numero in 1..2) {
                return Nombre(original, "path$numero", numero)
            }
            return Nombre(original, null, numero)
        }
        return null
    }

    /**
     * `path1`/`path2` a este dispositivo o al remoto. En bisync path1 es el
     * origen de la pareja, que es el lado local.
     */
    fun lado(pareja: Pareja, camino: String?): String? {
        if (camino == null) return null
        val extremo = if (camino == "path1") pareja.mode.source else pareja.mode.dest
        return if (extremo == "local") DISPOSITIVO else REMOTO
    }

    /** Uno de los ficheros de un conflicto. */
    data class Version(val ruta: File, val lado: String?, val esOriginal: Boolean, val numero: Int = 0)

    /**
     * Un fichero con más de una versión en disco: [original] es el nombre de
     * verdad, que puede no existir, y [versiones] todas, el original primero si
     * existe.
     */
    data class Conflicto(
        val pareja: String,
        val raiz: File,
        val original: File,
        val versiones: List<Version>,
    ) {
        /** La ruta que se enseña: dentro de la pareja y con barras normales. */
        val relativa: String get() = relativaA(original, raiz) ?: posix(original)

        /** Los ficheros con sufijo, que son los que tienen que desaparecer para resolverlo. */
        val copias: List<File> get() = versiones.filter { !it.esOriginal }.map { it.ruta }

        /**
         * LA versión de ese lado, o `null` si tiene cero o más de una: dos copias
         * del mismo lado son dos conflictos seguidos sin resolver, y elegir una
         * sería decidir por el usuario.
         */
        fun version(cual: String): Version? = versiones.filter { it.lado == cual }.singleOrNull()
    }

    /**
     * Junta las copias con su original. El lado del original se deduce: tras un
     * conflicto con ganador, rclone deja el ganador con el nombre de verdad, así
     * que si hay UNA copia de un lado el original es la versión del otro. Con
     * varias, o con alguna sin lado, el original se queda sin lado: una
     * etiqueta inventada es peor que ninguna.
     */
    private fun agrupar(pareja: Pareja, carpeta: File, copias: List<File>): List<Conflicto> {
        val esq = esquema(pareja)
        val grupos = LinkedHashMap<File, MutableList<Version>>()
        for (ruta in copias) {
            val leido = leerNombre(ruta.name, esq) ?: continue
            grupos.getOrPut(File(ruta.parentFile, leido.original)) { ArrayList() }
                .add(Version(ruta, lado(pareja, leido.camino), false, leido.numero))
        }
        val salida = ArrayList<Conflicto>()
        for ((original, versiones) in grupos.entries.sortedWith(compareBy(PorTramos) { it.key })) {
            versiones.sortWith(compareBy<Version>({ it.lado ?: "~" }, { it.numero }, { it.ruta.name }))
            if (original.isFile) {
                val deducido = if (versiones.size == 1 && versiones[0].lado != null) {
                    if (versiones[0].lado == DISPOSITIVO) REMOTO else DISPOSITIVO
                } else {
                    null
                }
                versiones.add(0, Version(original, deducido, true))
            }
            salida.add(Conflicto(pareja.name, carpeta, original, versiones.toList()))
        }
        return salida
    }

    /** Como ordena Python dos rutas: tramo a tramo, no como texto. */
    private object PorTramos : Comparator<File> {
        override fun compare(a: File, b: File): Int {
            val ta = posix(a).split("/")
            val tb = posix(b).split("/")
            for (i in 0 until minOf(ta.size, tb.size)) {
                val c = ta[i].compareTo(tb[i])
                if (c != 0) return c
            }
            return ta.size.compareTo(tb.size)
        }
    }

    /**
     * Los ficheros bajo [raiz], sin entrar en lo que se ignora de su primer
     * nivel. Como `os.walk`: lo que no se puede leer se salta sin ruido, y un
     * enlace a una carpeta se ve pero no se abre.
     */
    fun recorrer(raiz: File, ignorar: List<String> = emptyList()): List<File> {
        val salida = ArrayList<File>()
        fun visitar(carpeta: Path, enLaRaiz: Boolean) {
            val entradas = try {
                Files.newDirectoryStream(carpeta).use { it.toList() }
            } catch (_: IOException) {
                return
            } catch (_: SecurityException) {
                return
            }
            for (entrada in entradas) {
                val nombre = entrada.fileName.toString()
                if (enLaRaiz && seIgnora(nombre, ignorar)) continue
                if (Files.isDirectory(entrada)) {
                    if (!Files.isSymbolicLink(entrada)) visitar(entrada, false)
                } else {
                    salida.add(entrada.toFile())
                }
            }
        }
        if (raiz.isDirectory) visitar(raiz.toPath(), true)
        return salida
    }

    /** `huella.se_ignora`: si una entrada de la raíz casa con alguno de los patrones. */
    private fun seIgnora(nombre: String, patrones: List<String>): Boolean {
        val minusculas = nombre.lowercase()
        return patrones.any { fnmatchcase(minusculas, it) }
    }

    /**
     * Lo que cuelga de la carpeta de la pareja y no es suyo, en minúsculas:
     * `.prversions/` guarda versiones, no conflictos; y si la pareja sincroniza
     * la raíz entera, tampoco son suyos el programa, el llavero ni el ruido del
     * sistema.
     */
    private fun ignorados(pareja: Pareja): List<String> {
        val patrones = ArrayList<String>()
        patrones.add(VERSIONS_DIR)
        if (pareja.esRaiz) {
            patrones.add(CARPETA_PROGRAMA)
            patrones.add(LLAVERO_LOCAL)
            patrones.addAll(RUIDO_DEL_SISTEMA)
        }
        return patrones.map { it.lowercase() }
    }

    /**
     * Los conflictos de una pareja, recorriendo su carpeta local en el
     * [volumen]. Solo bisync deja conflictos: los demás modos copian en un
     * sentido.
     */
    fun escanear(pareja: Pareja, volumen: File): List<Conflicto> {
        val carpeta = pareja.carpetaEn(volumen)
        if (!pareja.isBisync || !carpeta.isDirectory) return emptyList()
        val esq = esquema(pareja)
        val copias = recorrer(carpeta, ignorados(pareja)).filter { leerNombre(it.name, esq) != null }
        return agrupar(pareja, carpeta, copias)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parejasGuardadas(stateDir: File): Map<String, Any?>? =
        Store.readJson(rutaEstado(stateDir))["parejas"] as? Map<String, Any?>

    /**
     * Escribe en `state/conflicts.json` las copias encontradas de esas parejas,
     * con su ruta relativa al volumen. Si lo que quedaría escrito es lo que ya
     * hay, no escribe nada. [previas] es el estado con el que mezclar: por
     * defecto el que hay en disco; vacío descarta las parejas que no estén.
     */
    private fun guardar(
        stateDir: File,
        volumen: File,
        parejas: Map<String, List<Conflicto>>,
        previas: Map<String, Any?>? = null,
        reloj: () -> String,
    ) {
        val enDisco = parejasGuardadas(stateDir) ?: emptyMap()
        val guardadas = LinkedHashMap(previas ?: enDisco)
        for ((nombre, encontrados) in parejas) {
            guardadas[nombre] = encontrados.flatMap { it.copias }.map { relativaA(it, volumen) ?: posix(it) }
        }
        if (guardadas == enDisco) return
        Store.writeJson(rutaEstado(stateDir), linkedMapOf("cuando" to reloj(), "parejas" to guardadas))
    }

    /**
     * Si el último escaneo de esa pareja se apuntó y no encontró ninguna copia.
     * Es lo que deja ahorrarse el recorrido tras una pasada que no movió nada.
     * Una pareja que nunca se escaneó da `false`.
     */
    fun sinApuntar(stateDir: File, nombre: String): Boolean =
        (parejasGuardadas(stateDir)?.get(nombre) as? List<*>)?.isEmpty() == true

    /** Escanea una pareja y apunta el resultado: lo llama la pasada al acabar. */
    fun actualizarPareja(
        pareja: Pareja,
        volumen: File,
        stateDir: File,
        reloj: () -> String = Store::stamp,
    ): List<Conflicto> {
        val encontrados = escanear(pareja, volumen)
        guardar(stateDir, volumen, mapOf(pareja.name to encontrados), reloj = reloj)
        return encontrados
    }

    /** Escanea todas las parejas y rehace el estado entero; las que ya no están se caen. */
    fun refrescar(
        config: Config,
        volumen: File,
        stateDir: File,
        reloj: () -> String = Store::stamp,
    ): Map<String, List<Conflicto>> {
        val todos = LinkedHashMap<String, List<Conflicto>>()
        for (pareja in config.pairs) {
            if (pareja.isBisync) todos[pareja.name] = escanear(pareja, volumen)
        }
        guardar(stateDir, volumen, todos, previas = emptyMap(), reloj = reloj)
        return todos
    }

    /**
     * Lo del último escaneo, sin recorrer nada: solo se mira que cada copia siga
     * existiendo. Es lo que pinta la pantalla nada más abrirse.
     */
    fun cargar(config: Config, volumen: File, stateDir: File): Map<String, List<Conflicto>> {
        val guardadas = parejasGuardadas(stateDir) ?: emptyMap()
        val salida = LinkedHashMap<String, List<Conflicto>>()
        for (pareja in config.pairs) {
            if (!pareja.isBisync) continue
            val rutas = guardadas[pareja.name] as? List<*>
            val copias = rutas?.filterIsInstance<String>()?.map { File(volumen, it) } ?: emptyList()
            salida[pareja.name] = agrupar(pareja, pareja.carpetaEn(volumen), copias.filter { it.isFile })
        }
        return salida
    }

    /** Cuántos conflictos tiene cada pareja; solo las que tienen alguno. */
    fun contar(porPareja: Map<String, List<Conflicto>>): Map<String, Int> =
        porPareja.filterValues { it.isNotEmpty() }.mapValues { it.value.size }

    private fun posix(f: File): String = f.path.replace(File.separatorChar, '/')

    /** La ruta de [f] dentro de [base], con `/`, o `null` si no cuelga de ella. */
    private fun relativaA(f: File, base: File): String? {
        val dentro = base.toPath().normalize()
        val ruta = f.toPath().normalize()
        if (!ruta.startsWith(dentro)) return null
        return dentro.relativize(ruta).toString().replace(File.separatorChar, '/')
    }
}
