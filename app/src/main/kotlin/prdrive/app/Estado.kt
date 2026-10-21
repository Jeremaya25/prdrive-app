package prdrive.app

import java.io.IOException
import prdrive.engine.Bisync
import prdrive.engine.Config
import prdrive.engine.Conflictos
import prdrive.engine.Pasada
import prdrive.engine.Resultados
import prdrive.engine.Volumen

/**
 * Estado.kt — Lo que la pantalla principal enseña de cada pareja, leído de
 * `state/` sin tocar la red: cuándo quedó al día, si la última pasada falló y
 * por qué, si pide `--resync` y si tiene conflictos.
 *
 * Es lo que en el escritorio reparten `results.py`, `conflicts.py` y el estado
 * de `bisync.py`; aquí se junta en una fila por pareja.
 */
object Estado {

    data class Fila(
        val nombre: String,
        val modo: String,
        val local: String,
        /** Milisegundos de época de la última pasada buena, o `null` si no consta. */
        val ultimaBuena: Long?,
        val fallo: Resultados.Fallo?,
        /** La explicación de `KNOWN_ERRORS` para el log del fallo, si la hay. */
        val explicacion: String?,
        /** Por qué pide `--resync`; vacío si no lo pide (o no es bisync). */
        val pideResync: List<String>,
        val conflictos: List<Conflictos.Conflicto>,
    )

    fun filas(v: Volumen, config: Config): List<Fila> {
        val buenas = Resultados.ultimasBuenas(v.state, config.names)
        val fallos = Resultados.fallos(v.state, v.logs, config).associateBy { it.pareja }
        val conflictos = Conflictos.cargar(config, v.raiz, v.state)
        return config.delUsuario.map { p ->
            val fallo = fallos[p.name]
            Fila(
                nombre = p.name,
                modo = p.mode.name,
                local = p.local,
                ultimaBuena = buenas[p.name],
                fallo = fallo,
                explicacion = fallo?.log?.let { leer(it)?.let(Pasada::explicar) },
                pideResync = razonesResync(v, p),
                conflictos = conflictos[p.name].orEmpty(),
            )
        }
    }

    private fun razonesResync(v: Volumen, p: prdrive.engine.Pareja): List<String> {
        if (!p.isBisync) return emptyList()
        return try {
            Bisync.resyncReasons(p, Bisync.pairState(v.workdir(p)), Bisync.filtersState(Bisync.filtersFileFor(p, v.filtros)))
        } catch (e: IOException) {
            listOf("no se puede leer su estado: ${e.message}")
        }
    }

    /** El texto de un log, o `null` si ya no se puede leer. */
    fun leer(log: java.io.File): String? = try {
        log.readText(Charsets.UTF_8)
    } catch (_: IOException) {
        null
    }
}
