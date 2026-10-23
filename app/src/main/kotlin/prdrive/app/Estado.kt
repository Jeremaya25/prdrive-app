package prdrive.app

import java.io.File
import java.io.IOException
import prdrive.app.documentos.GuardadoAtomico
import prdrive.engine.Bisync
import prdrive.engine.Catalogo
import prdrive.engine.Config
import prdrive.engine.ConfigError
import prdrive.engine.Conflictos
import prdrive.engine.LLAVERO
import prdrive.engine.LLAVERO_LLAVE
import prdrive.engine.Llavero
import prdrive.engine.Pasada
import prdrive.engine.Resultados
import prdrive.engine.Store
import prdrive.engine.Volumen
import prdrive.engine.strPy

/**
 * Estado.kt — Lo que la pantalla principal enseña de cada pareja, leído de
 * `state/` sin tocar la red: cuándo quedó al día, si la última pasada falló y
 * por qué, si pide `--resync` y si tiene conflictos.
 *
 * Es lo que en el escritorio reparten `results.py`, `conflicts.py` y el estado
 * de `bisync.py`; aquí se junta en una fila por pareja. Y la del llavero
 * ([llavero]), que es la línea de `llavero_editor.linea()`.
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

    /**
     * La fila del llavero ([llavero]).
     *
     * @property activo si el teléfono lleva el llavero (`[keychain]` en su config).
     * @property traer sin llavero activo, si la copia del catálogo lo tiene: se
     *   ofrece «Traer el llavero».
     * @property ultimaBuena milisegundos de época de la última pasada buena.
     * @property fallo la última pasada, si falló.
     * @property explicacion lo que se dice de ese fallo, ya para el teléfono.
     * @property pendiente si hay algo sin subir ([Llavero.pendiente]).
     * @property conflictos los de la pareja del llavero (sus copias).
     * @property cortado lo que se dice de un guardado que llegó cortado
     *   (`state/llavero-cortado.json`), con su motivo.
     * @property faltaLlave si va sin contraseña y falta `.keychain/llave.keyx`.
     * @property base el nombre de la base.
     * @property llaveInterna si va sin contraseña, con su fichero llave
     *   (`llave_interna`): al traerlo hay que darlo.
     * @property avisos lo que avisa prdrive antes de activarlo o de quitarlo
     *   ([Volumen.avisosAlta], [Volumen.avisosBaja]).
     * @property hayBase si la base está en el teléfono.
     */
    data class FilaLlavero(
        val activo: Boolean,
        val traer: Boolean,
        val ultimaBuena: Long?,
        val fallo: Resultados.Fallo?,
        val explicacion: String?,
        val pendiente: Boolean,
        val conflictos: List<Conflictos.Conflicto>,
        val cortado: String?,
        val faltaLlave: Boolean,
        val base: String = "",
        val llaveInterna: Boolean = false,
        val avisos: List<String> = emptyList(),
        val hayBase: Boolean = false,
    ) {
        /** Si va en ámbar: hay algo que mirar. */
        val ambar: Boolean get() = fallo != null || cortado != null || conflictos.isNotEmpty() || faltaLlave
    }

    /** Donde [Pasada.EXPLICACION_LLAVERO] empieza a mandar a «Combinar». */
    const val MANDA_A_COMBINAR = "usa «Combinar»"

    /**
     * Lo que se dice en lugar de [Pasada.EXPLICACION_LLAVERO], que manda a
     * «Combinar» al abrir: eso es del escritorio, y el teléfono no lo tiene.
     * Es la de prdrive hasta [MANDA_A_COMBINAR] y, en lugar de lo demás, la
     * frase del teléfono ([Avisos.COMBINAR_FUERA][prdrive.app.llavero.Avisos.COMBINAR_FUERA]):
     * si prdrive cambia su texto, cambia con él (y `PantallaLlaveroTest`
     * comprueba que el corte sigue ahí).
     */
    val EXPLICACION_LLAVERO_TELEFONO: String =
        Pasada.EXPLICACION_LLAVERO.substringBefore(MANDA_A_COMBINAR) +
            prdrive.app.llavero.Avisos.COMBINAR_FUERA.replaceFirstChar { it.lowercaseChar() }

    /** Lo que se dice de una base rota ([Pasada.LLAVERO_ROTO]), que no tiene log. */
    const val EXPLICACION_ROTA =
        "Una base del llavero no está entera, y no se sube: la del remoto no se toca. Vuelve a " +
            "guardarla entera en tu app de KeePass."

    /**
     * La fila del llavero, leída de `state/` y de `.keychain/` sin tocar la
     * red, como `llavero_editor.linea()`. `null` si el teléfono no lo lleva y
     * la copia del catálogo tampoco lo tiene.
     *
     * @param faltaRedLocal si falta el permiso de red local ([Prdrive.faltaRedLocal]):
     *   entonces un fallo de red lo dice detrás, como los errores de la pantalla.
     */
    fun llavero(v: Volumen, config: Config, faltaRedLocal: Boolean = false): FilaLlavero? {
        val pareja = config.parejaLlavero
        val tabla = config.llavero
        if (pareja == null || tabla == null) {
            val remota = Catalogo.cached(v.state)?.raw?.get("keychain") as? Map<*, *> ?: return null
            return FilaLlavero(
                activo = false, traer = true, ultimaBuena = null, fallo = null, explicacion = null,
                pendiente = false, conflictos = emptyList(), cortado = null, faltaLlave = false,
                base = strPy(remota["base"] ?: ""),
                llaveInterna = remota["llave_interna"] == true,
                avisos = Volumen.avisosAlta(config),
            )
        }
        val carpeta = pareja.carpetaEn(v.raiz)
        val base = strPy(tabla["base"] ?: "")
        val fallo = Resultados.fallosDe(v.state, v.logs, listOf(LLAVERO)).firstOrNull()
        return FilaLlavero(
            activo = true,
            traer = false,
            ultimaBuena = Resultados.ultimasBuenas(v.state, listOf(LLAVERO))[LLAVERO],
            fallo = fallo,
            explicacion = fallo?.let { explicacionLlavero(it, faltaRedLocal) },
            pendiente = try {
                Llavero.pendiente(carpeta, Llavero.listadoDe(v, pareja, Bisync.tiposDeRemote(v.leerRcloneConf())))
            } catch (_: IOException) {
                false
            },
            conflictos = Conflictos.cargar(config, v.raiz, v.state)[LLAVERO].orEmpty(),
            cortado = cortado(v),
            faltaLlave = pareja.llaveInterna && !File(carpeta, LLAVERO_LLAVE).isFile,
            base = base,
            llaveInterna = pareja.llaveInterna,
            avisos = try {
                Volumen.avisosBaja(config)
            } catch (_: ConfigError) {
                emptyList()
            },
            hayBase = base.isNotEmpty() && File(carpeta, base).isFile,
        )
    }

    /**
     * Lo que se dice del fallo del llavero: la de su log (con la del llavero
     * en lugar de la de un freno, como la pasada, y la del teléfono en lugar de
     * la que manda a «Combinar»), o la de una base rota, que no tiene log.
     */
    private fun explicacionLlavero(fallo: Resultados.Fallo, faltaRedLocal: Boolean): String? {
        if (fallo.codigo == java.math.BigInteger.valueOf(Pasada.LLAVERO_ROTO.toLong())) return EXPLICACION_ROTA
        val texto = fallo.log?.let(::leer) ?: return null
        val explicacion = Pasada.explicar(texto)
            ?.let { if (it in Pasada.EXPLICACIONES) Pasada.explicacionLlavero(it) else it }
            ?.let { if (it == Pasada.EXPLICACION_LLAVERO) EXPLICACION_LLAVERO_TELEFONO else it }
        if (faltaRedLocal && Pasada.esDeRed(texto)) {
            return listOfNotNull(explicacion, Prdrive.SIN_RED_LOCAL).joinToString("\n\n")
        }
        return explicacion
    }

    /** El aviso de un guardado cortado, si lo hay: de qué base y por qué. */
    private fun cortado(v: Volumen): String? {
        val aviso = File(v.state, GuardadoAtomico.AVISO)
        if (!aviso.isFile) return null
        val datos = Store.readJson(aviso)
        val fichero = strPy(datos["fichero"] ?: "la base")
        val motivo = datos["motivo"]?.let { strPy(it) }
        return "Un guardado de $fichero no llegó entero" + (motivo?.let { " ($it)" } ?: "") +
            ": no se ha puesto, y la base sigue como estaba. Vuelve a guardar en tu app de KeePass."
    }

    /** El texto de un log, o `null` si ya no se puede leer. */
    fun leer(log: java.io.File): String? = try {
        log.readText(Charsets.UTF_8)
    } catch (_: IOException) {
        null
    }
}
