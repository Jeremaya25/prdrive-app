package prdrive.engine

import java.io.File

/**
 * Resultados.kt — Cómo acabó la última pasada de cada pareja.
 *
 * Espejo de `common/results.py` de prdrive. Existe para que un fallo no se
 * quede escondido: el log de una pasada fallida se guarda, pero nadie lo mira,
 * y una pareja puede pasarse días fallando mientras el usuario cree que el
 * teléfono está al día. Con esto la pantalla principal sabe, al abrirse, qué
 * parejas fallaron la última vez y qué log lo explica.
 *
 * Lo escribe la pasada, que es quien sabe el resultado y dónde ha dejado el
 * log. Una pareja SALTADA (pide `--resync` y nadie lo ha aprobado) no se
 * apunta: no se ha ejecutado, así que su último resultado de verdad sigue
 * siendo el anterior.
 *
 * El fichero es el mismo `state/last_run.json` del escritorio, con el mismo
 * formato. Las carpetas se pasan: el motor no sabe dónde está el volumen.
 */
object Resultados {

    /** `state/last_run.json`. */
    fun rutaEstado(stateDir: File): File = File(stateDir, "last_run.json")

    /**
     * Una pareja cuya última pasada falló: cuándo, con qué código, el log que lo
     * explica (o `null` si no quedó) y la última pasada buena, que dice desde
     * cuándo falla.
     */
    data class Fallo(
        val pareja: String,
        val cuando: String,
        val codigo: Int,
        val log: File?,
        val buena: String? = null,
    )

    /**
     * La fecha de la última pasada buena que conste de una pareja. Solo cabe un
     * resultado por pareja, así que sin la clave `buena` un fallo borraría la
     * fecha de cuando sí quedó al día. El segundo camino es por los registros de
     * antes de que existiera la clave: si la última pasada apuntada fue buena,
     * su fecha es la que se busca.
     */
    fun buena(dato: Map<*, *>): String? {
        (dato["buena"] as? String)?.let { return it }
        val codigo = intPy(if (dato.containsKey("codigo")) dato["codigo"] else 1L) ?: return null
        if (codigo != 0L) return null
        return dato["cuando"] as? String
    }

    @Suppress("UNCHECKED_CAST")
    private fun parejas(stateDir: File): Map<String, Any?>? =
        Store.readJson(rutaEstado(stateDir))["parejas"] as? Map<String, Any?>

    /**
     * Apunta el resultado de una pasada. Nunca falla: si no se puede escribir,
     * el aviso se pierde, pero la sincronización no puede caerse por él. El log
     * se guarda por su nombre, no con la ruta entera.
     */
    fun apuntar(
        stateDir: File,
        nombre: String,
        codigo: Int,
        log: File?,
        reloj: () -> String = Store::stamp,
    ) {
        val parejas = LinkedHashMap<String, Any?>(parejas(stateDir) ?: emptyMap())
        val anterior = parejas[nombre] as? Map<*, *>
        val ahora = reloj()
        parejas[nombre] = linkedMapOf(
            "cuando" to ahora,
            "codigo" to codigo.toLong(),
            "log" to log?.name,
            "buena" to if (codigo == 0) ahora else buena(anterior ?: emptyMap<String, Any?>()),
        )
        Store.writeJson(rutaEstado(stateDir), linkedMapOf("parejas" to parejas))
    }

    /**
     * Cuándo terminó BIEN por última vez cada una de esas parejas, en
     * milisegundos de época. Es lo que le falta a [Bisync.lastRun], que solo
     * sabe de los listados y por tanto solo de las parejas bisync. Las que no
     * constan no salen.
     */
    fun ultimasBuenas(stateDir: File, nombres: Iterable<String>): Map<String, Long> {
        val parejas = parejas(stateDir) ?: return emptyMap()
        val marcas = LinkedHashMap<String, Long>()
        for (nombre in nombres) {
            val dato = parejas[nombre] as? Map<*, *> ?: continue
            Store.desdeSello(buena(dato))?.let { marcas[nombre] = it }
        }
        return marcas
    }

    /** Las parejas del config cuya última pasada falló, en el orden del config. */
    fun fallos(stateDir: File, logDir: File, config: Config): List<Fallo> =
        fallosDe(stateDir, logDir, config.names)

    /** Los fallos de una lista de nombres. */
    fun fallosDe(stateDir: File, logDir: File, nombres: Iterable<String>): List<Fallo> {
        val parejas = parejas(stateDir) ?: return emptyList()
        val salida = ArrayList<Fallo>()
        for (nombre in nombres) {
            val dato = parejas[nombre] as? Map<*, *> ?: continue
            val codigo = intPy(if (dato.containsKey("codigo")) dato["codigo"] else 0L) ?: continue
            if (codigo == 0L) continue
            // Solo el nombre, aunque el fichero diga una ruta: el log vive en logs/.
            val log = (dato["log"] as? String)
                ?.let { File(logDir, it.trimEnd('/').substringAfterLast('/')) }
                ?.takeIf { it.isFile }
            val cuando = strPy(if (dato.containsKey("cuando")) dato["cuando"] else "")
            salida.add(Fallo(nombre, cuando, codigo.toInt(), log, buena(dato)))
        }
        return salida
    }
}
