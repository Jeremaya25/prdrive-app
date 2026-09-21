package prdrive.engine

import java.io.File
import java.time.LocalDateTime

/**
 * Conflictos.kt — Los ficheros en conflicto que deja bisync, vistos desde el
 * móvil. Espejo de `common/conflicts.py`.
 *
 * Cuando un mismo fichero cambia en los dos lados entre dos pasadas, bisync no
 * elige en silencio: con `--conflict-resolve newer` se queda con el más
 * reciente, al otro le cambia el nombre y copia los dos a los dos lados. Eso
 * es todo lo que queda: una línea en un log que se borra si la pasada fue
 * bien, y un fichero con un nombre raro que nadie mira. Mientras tanto las dos
 * versiones siguen separándose.
 *
 * Este fichero los **encuentra y dice de qué lado viene cada uno**. No decide
 * nada ni toca ninguno: resolver está fuera del paso 1.
 *
 * **El estado es derivado.** Lo que se guarda en `state/conflicts.json` es solo
 * lo que encontró el último recorrido, para poder pintar la pantalla sin
 * recorrer el árbol entero; al leerlo se comprueba que cada fichero sigue ahí,
 * así que en cuanto desaparecen los ficheros desaparece el aviso, lo borre
 * quien lo borre.
 *
 * Réplica de rclone, como [Bisync]: el nombre del perdedor lo decide
 * `cmd/bisync/resolve.go` (`setResolveDefaults`, `resolve`, `SuffixName`) y la
 * posición del sufijo `lib/transform/transform.go` (`SuffixKeepExtension`). Se
 * lee de los flags **ya fundidos** de la pareja, que es exactamente lo que
 * recibe rclone. **Conservar estas citas.**
 */

/** Una de las versiones de un fichero en conflicto. */
data class Version(
    val ruta: File,
    /** [Conflictos.DISPOSITIVO] | [Conflictos.REMOTO] | null si no se sabe. */
    val lado: String?,
    /** El que tiene el nombre de verdad. */
    val esOriginal: Boolean,
    val numero: Int = 0,
)

/** Un fichero con más de una versión en disco. */
data class Conflicto(
    val pareja: String,
    /** La carpeta local de la pareja. */
    val raiz: File,
    /** El nombre de verdad (puede no existir). */
    val original: File,
    val versiones: List<Version>,
) {
    /** La ruta que se enseña: dentro de la pareja y con barras normales. */
    val relativa: String
        get() {
            val raizTexto = raiz.path.trimEnd('/') + "/"
            val ruta = original.path
            return if (ruta.startsWith(raizTexto)) ruta.removePrefix(raizTexto) else ruta
        }

    /** Los ficheros con sufijo: lo que tiene que desaparecer para resolverlo. */
    val copias: List<File> get() = versiones.filter { !it.esOriginal }.map { it.ruta }

    /**
     * LA versión de ese lado, o null si no hay una sola.
     *
     * Dos copias del mismo lado son dos conflictos seguidos sin resolver en
     * medio: las dos son «de este móvil», de momentos distintos, y elegir una
     * por su cuenta sería decidir por el usuario.
     */
    fun version(cual: String): Version? =
        versiones.filter { it.lado == cual }.singleOrNull()
}

object Conflictos {

    const val DISPOSITIVO = "dispositivo"
    const val REMOTO = "remoto"

    /**
     * Lo que rclone pone si no se le dice nada (`setResolveDefaults`). Aunque
     * la pareja lleve otros sufijos, estos se siguen reconociendo: son los de
     * los conflictos que ya había antes de cambiarlos, y siguen ahí hasta que
     * alguien los resuelva.
     */
    const val SUFIJO_RCLONE = "conflict"
    const val PERDEDOR_RCLONE = "num"

    /** `state/conflicts.json`. */
    const val FICHERO = "conflicts.json"

    /** Cómo nombra rclone a los perdedores de una pareja concreta. */
    data class Esquema(
        /** El de path1, ya con su punto delante. */
        val sufijo1: String,
        /** El de path2. */
        val sufijo2: String,
        /** `--conflict-loser`: num | pathname | delete. */
        val perdedor: String,
        /** `--suffix-keep-extension`. */
        val mantenerExtension: Boolean,
    )

    /** Lo que se saca de un nombre con sufijo. */
    data class Leido(val original: String, val camino: String?, val numero: Int)

    /**
     * Un flag de la pareja escrito con guiones o con guiones bajos: los dos
     * acaban siendo el mismo argumento. Si están los dos —el modo trae
     * `conflict-suffix` y la pareja escribe `conflict_suffix`—, rclone recibe
     * el flag dos veces y se queda con el último, que es el que va más tarde
     * en el mapa fundido.
     */
    private fun flag(pareja: Pareja, nombre: String): Any? {
        var valor: Any? = null
        for ((clave, v) in pareja.flags) {
            if (clave.replace("_", "-") == nombre) valor = v
        }
        return valor
    }

    /**
     * Lo que hace `setResolveDefaults()`: un sufijo vale para los dos lados,
     * dos separados por coma son uno para cada uno, y a los dos se les pone un
     * punto delante.
     *
     * Los comodines de fecha (`{DateOnly}`…) no se pueden deshacer desde el
     * nombre, así que una pareja que los use verá sus conflictos **sin lado**,
     * que es mejor que con uno inventado.
     */
    fun esquema(pareja: Pareja): Esquema {
        val crudo = flag(pareja, "conflict-suffix")?.toString() ?: SUFIJO_RCLONE
        val partes = crudo.split(",").filter { it.isNotEmpty() }.ifEmpty { listOf(SUFIJO_RCLONE) }
        val s1 = partes[0]
        val s2 = if (partes.size == 1) partes[0] else partes[1]
        return Esquema(
            sufijo1 = ".$s1",
            sufijo2 = ".$s2",
            perdedor = flag(pareja, "conflict-loser")?.toString() ?: PERDEDOR_RCLONE,
            mantenerExtension = flag(pareja, "suffix-keep-extension") == true,
        )
    }

    /**
     * `SuffixName` pone el sufijo al final (`plan.md.conflict1`) o, con
     * `--suffix-keep-extension`, delante de la extensión (`plan.conflict1.md`).
     */
    private fun patron(sufijo: String, mantenerExtension: Boolean): Regex {
        val extension = if (mantenerExtension) """(?<ext>(?:\.[^.]+)+)""" else """(?<ext>)"""
        return Regex("""^(?<base>.+)${Regex.escape(sufijo)}(?<n>\d*)$extension$""")
    }

    /**
     * El nombre original, de qué lado viene y con qué número; null si no es un
     * conflicto.
     *
     * Qué significa el número depende del esquema, y es justo lo que hay que
     * acertar (ver `resolve()`):
     *
     *  - sufijos distintos: **el sufijo es el lado**, el número solo un orden;
     *  - un sufijo y `--conflict-loser pathname`: `1` es path1 y `2` es path2;
     *  - un sufijo y `--conflict-loser num`: el número es el primero que
     *    estaba libre (`numerate`), así que el lado **no se sabe**.
     */
    fun leerNombre(nombre: String, esq: Esquema): Leido? {
        val candidatos = listOf(esq.sufijo1, esq.sufijo2, ".$SUFIJO_RCLONE").distinct()
        for (sufijo in candidatos) {
            val m = patron(sufijo, esq.mantenerExtension).matchEntire(nombre) ?: continue
            val base = m.groups["base"]!!.value
            val ext = m.groups["ext"]!!.value
            val n = m.groups["n"]!!.value
            val original = base + ext
            val numero = if (n.isEmpty()) 0 else n.toInt()
            if (esq.sufijo1 != esq.sufijo2 && (sufijo == esq.sufijo1 || sufijo == esq.sufijo2)) {
                val camino = if (sufijo == esq.sufijo1) "path1" else "path2"
                return Leido(original, camino, numero)
            }
            // Un solo sufijo sin número no lo escribe rclone.
            if (n.isEmpty()) continue
            if (sufijo == esq.sufijo1 && esq.perdedor == "pathname" && numero in 1..2) {
                return Leido(original, "path$numero", numero)
            }
            return Leido(original, null, numero)
        }
        return null
    }

    /**
     * `path1`/`path2` traducido a este móvil / el remoto.
     *
     * En bisync path1 es el primer extremo de la llamada, y `Pasada` pone ahí
     * `pareja.source`, que es el lado local según [MODES]. En los modos que
     * copian en un sentido no hay conflictos, pero la traducción sigue siendo
     * la del modo.
     */
    fun lado(pareja: Pareja, camino: String?): String? {
        if (camino == null) return null
        val extremo = if (camino == "path1") pareja.mode.source else pareja.mode.dest
        return if (extremo == "local") DISPOSITIVO else REMOTO
    }

    // -----------------------------------------------------------------------
    // Encontrarlos
    // -----------------------------------------------------------------------

    /**
     * Todos los ficheros bajo una carpeta. Se puede sustituir, como
     * `conflicts.recorrer()` en prdrive, para que un test no necesite árbol.
     *
     * Se salta sin ruido lo que no puede leer: es un recorrido que solo busca
     * avisos, así que una carpeta sin permiso no puede tumbarlo.
     */
    var recorrer: (File) -> Sequence<File> = { raiz ->
        raiz.walkTopDown().onFail { _, _ -> }.filter { it.isFile }
    }

    /**
     * Los conflictos de una pareja, recorriendo su carpeta local.
     *
     * Solo bisync deja conflictos: los demás modos copian en un sentido.
     */
    fun escanear(pareja: Pareja, carpetaLocal: File): List<Conflicto> {
        if (!pareja.isBisync || !carpetaLocal.isDirectory) return emptyList()
        val esq = esquema(pareja)
        val copias = recorrer(carpetaLocal).filter { leerNombre(it.name, esq) != null }.toList()
        return agrupar(pareja, carpetaLocal, copias)
    }

    /**
     * Las copias, juntadas con su original.
     *
     * El lado del original se **deduce**: tras un conflicto con ganador, rclone
     * deja al ganador con el nombre de verdad y renombra al perdedor
     * (`resolve()`, caso winningPath 1 o 2), así que si hay UNA copia de un
     * lado, el original es la versión del otro. Con varias copias, o con alguna
     * sin lado, eso ya no se puede afirmar y el original se queda sin lado: una
     * etiqueta inventada es peor que ninguna.
     */
    fun agrupar(pareja: Pareja, carpetaLocal: File, copias: List<File>): List<Conflicto> {
        val esq = esquema(pareja)
        val grupos = LinkedHashMap<File, MutableList<Version>>()
        for (ruta in copias) {
            val leido = leerNombre(ruta.name, esq) ?: continue
            val original = File(ruta.parentFile, leido.original)
            grupos.getOrPut(original) { ArrayList() }
                .add(Version(ruta, lado(pareja, leido.camino), false, leido.numero))
        }

        val salida = ArrayList<Conflicto>()
        for (original in grupos.keys.sortedBy { it.path }) {
            val versiones = grupos.getValue(original)
                .sortedWith(compareBy({ it.lado ?: "~" }, { it.numero }, { it.ruta.name }))
                .toMutableList()
            if (existe(original)) {
                val lados = versiones.map { it.lado }.toSet()
                val deducido = if (versiones.size == 1 && null !in lados) {
                    if (versiones[0].lado == DISPOSITIVO) REMOTO else DISPOSITIVO
                } else {
                    null
                }
                versiones.add(0, Version(original, deducido, true))
            }
            salida.add(Conflicto(pareja.name, carpetaLocal, original, versiones))
        }
        return salida
    }

    private fun existe(ruta: File): Boolean = runCatching { ruta.isFile }.getOrDefault(false)

    // -----------------------------------------------------------------------
    // Lo que se recuerda entre una pantalla y la siguiente
    // -----------------------------------------------------------------------

    /**
     * Escanea una pareja y apunta el resultado. Se llama tras **cada** pasada,
     * buena o mala: rclone renombra al perdedor en cuanto lo detecta, así que
     * un fallo más adelante no quita el conflicto.
     */
    fun actualizarPareja(
        volumen: Volumen,
        pareja: Pareja,
        cuando: LocalDateTime = LocalDateTime.now(),
    ): List<Conflicto> {
        val encontrados = escanear(pareja, volumen.carpetaLocal(pareja))
        guardar(volumen, mapOf(pareja.name to encontrados), cuando = cuando)
        return encontrados
    }

    /**
     * Lo del último escaneo, sin recorrer nada: solo se mira que cada copia
     * siga existiendo. Es lo que pinta la pantalla nada más abrirse.
     */
    fun cargar(volumen: Volumen, config: Config): Map<String, List<Conflicto>> {
        val guardadas = leerGuardadas(volumen)
        val salida = LinkedHashMap<String, List<Conflicto>>()
        for (pareja in config.pairs) {
            if (!pareja.isBisync) continue
            val rutas = (guardadas[pareja.name] as? List<*>)?.mapNotNull { it as? String }
                ?: emptyList()
            val copias = rutas.map { File(volumen.raiz, it) }.filter { existe(it) }
            salida[pareja.name] = agrupar(pareja, volumen.carpetaLocal(pareja), copias)
        }
        return salida
    }

    /** Cuántos conflictos tiene cada pareja, solo las que tienen alguno. */
    fun contar(porPareja: Map<String, List<Conflicto>>): Map<String, Int> =
        porPareja.filterValues { it.isNotEmpty() }.mapValues { it.value.size }

    /**
     * El aviso que se enseña tras una pasada, o null si no hay conflictos.
     *
     * Con el mismo recorte que `sync.py`: se nombran los primeros y se dice
     * cuántos quedan, porque una pareja con cien conflictos llenaría la
     * pantalla y el mensaje que importa es el primero.
     */
    fun aviso(pareja: String, encontrados: List<Conflicto>): String? {
        if (encontrados.isEmpty()) return null
        val lineas = ArrayList<String>()
        lineas.add(
            "[$pareja] AVISO: ${encontrados.size} fichero(s) en conflicto: cambiaron en " +
                "los dos lados y hay dos versiones.",
        )
        encontrados.take(Pasada.CONFLICTOS_MOSTRADOS).forEach { lineas.add("  conflicto: ${it.relativa}") }
        if (encontrados.size > Pasada.CONFLICTOS_MOSTRADOS) {
            lineas.add("  … y ${encontrados.size - Pasada.CONFLICTOS_MOSTRADOS} más")
        }
        return lineas.joinToString("\n")
    }

    /**
     * Guarda las rutas relativas a la raíz del volumen.
     *
     * Relativas porque `filesDir` cambia al reinstalar la app, igual que en el
     * escritorio cambia la letra de unidad. Nunca lanza: es un aviso.
     */
    fun guardar(
        volumen: Volumen,
        parejas: Map<String, List<Conflicto>>,
        desdeCero: Boolean = false,
        cuando: LocalDateTime = LocalDateTime.now(),
    ) {
        val guardadas = LinkedHashMap<String, Any?>(if (desdeCero) emptyMap() else leerGuardadas(volumen))
        for ((nombre, encontrados) in parejas) {
            guardadas[nombre] = encontrados.flatMap { it.copias }.map { relativa(volumen, it) }
        }
        runCatching {
            volumen.state.mkdirs()
            File(volumen.state, FICHERO).writeText(
                escribirJson(
                    mapOf("cuando" to Resultados.sello(cuando), "parejas" to guardadas),
                ),
            )
        }
    }

    private fun relativa(volumen: Volumen, ruta: File): String {
        val raiz = volumen.raiz.path.trimEnd('/') + "/"
        return if (ruta.path.startsWith(raiz)) ruta.path.removePrefix(raiz) else ruta.path
    }

    private fun leerGuardadas(volumen: Volumen): Map<String, Any?> {
        val texto = runCatching { File(volumen.state, FICHERO).readText() }.getOrNull()
            ?: return emptyMap()
        val raiz = runCatching { leerObjetoJson(texto) }.getOrNull() ?: return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return raiz["parejas"] as? Map<String, Any?> ?: emptyMap()
    }
}
