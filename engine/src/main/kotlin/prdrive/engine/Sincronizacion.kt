package prdrive.engine

import java.io.File
import java.time.LocalDateTime

/**
 * Sincronizacion.kt — Ejecutar las parejas y contar cómo ha ido. La otra mitad
 * de `sync.py`: `run_pair`, `run_all` y `resolve_resync_approval`.
 *
 * [Pasada] monta la llamada; esto la lleva a cabo — las comprobaciones previas,
 * el orden, el log, lo que se apunta y lo que se avisa. Está en `engine/` y no
 * en `app/` por la misma razón que todo lo demás: es donde se toman las
 * decisiones que pueden salir caras, así que es lo que hay que poder probar sin
 * teléfono. La app aporta el [Rclone] de verdad y pinta lo que le cuente el
 * [Observador].
 *
 * Tres cosas que no son de adorno:
 *
 *  - **Las parejas se ejecutan de UNA EN UNA.** No es prudencia: en una pasada
 *    de bisync, `bilib.CaptureOutput` redirige la salida del proceso entero
 *    (`cmd/bisync/bilib/output.go`), así que dos a la vez se robarían el log.
 *  - **Una pareja saltada no se apunta.** No se ha ejecutado, así que su
 *    último resultado de verdad sigue siendo el anterior. Y un `--dry-run`
 *    tampoco: un simulacro bueno no puede tapar un fallo real.
 *  - **El `--resync` se pregunta una vez, antes de empezar** ([razonesDeResync]),
 *    y no pareja por pareja a mitad de faena. Sin aprobación, la pareja se
 *    salta en vez de resincronizarse sola: rehacer un baseline es la operación
 *    que decide qué se borra, y eso no se hace sin que alguien diga sí.
 */

/** Lo que la pantalla quiere saber mientras la pasada corre. */
interface Observador {
    /** Empieza una pareja. [resync] dice si esta pasada rehace el baseline. */
    fun empieza(pareja: Pareja, resync: Boolean) {}

    /** Una lectura del progreso de la pareja que está corriendo. */
    fun progreso(pareja: Pareja, progreso: Progreso) {}

    /** Algo que contar por el camino: un conflicto, un flag que no viaja. */
    fun avisa(texto: String) {}

    /** La pareja ha acabado. */
    fun acaba(resultado: Resultado) {}
}

/** Lo que el usuario ha pedido para ESTA pasada. */
data class OpcionesDePasada(
    /** Simular: no se escribe nada y no se apunta el resultado. */
    val dryRun: Boolean = false,
    /** Rehacer el baseline aunque no haga falta. */
    val forzarResync: Boolean = false,
    /** El usuario ha dicho sí a los `--resync` que hagan falta. */
    val resyncAprobado: Boolean = false,
    /** Guardar también el log de las pasadas buenas (`keep_logs`). */
    val guardarLogs: Boolean = false,
)

class Sincronizador(
    private val rclone: Rclone,
    private val volumen: Volumen,
    /**
     * Cada cuánto se le pregunta a rclone cómo va, en milisegundos. Es el
     * `PROGRESS_POLL_S` de `sync.py`, que son 0,5 s.
     */
    private val cada: Long = 500,
    /** Se inyecta para que un test no espere de verdad. */
    private val esperar: (Long) -> Unit = { Thread.sleep(it) },
    private val ahora: () -> LocalDateTime = { LocalDateTime.now() },
) {

    /**
     * Por qué cada pareja necesita `--resync`, para poder preguntarlo **una
     * vez** antes de empezar. Vacío = ninguna lo necesita.
     */
    fun razonesDeResync(parejas: List<Pareja>): Map<String, List<String>> {
        val salida = LinkedHashMap<String, List<String>>()
        for (pareja in parejas) {
            if (!pareja.isBisync) continue
            val razones = razones(pareja)
            if (razones.isNotEmpty()) salida[pareja.name] = razones
        }
        return salida
    }

    private fun razones(pareja: Pareja): List<String> = Bisync.resyncReasons(
        pareja,
        Bisync.pairState(volumen.workdir(pareja)),
        Bisync.filtersState(ficheroDeFiltros(pareja)),
    )

    /**
     * Sincroniza las parejas, de una en una y en el orden que se pidan.
     *
     * No se para en la primera que falle: cada pareja es independiente, y
     * pararse dejaría al usuario con la mitad sin sincronizar y sin saber si
     * las demás iban bien. Es lo que hace `run_all`.
     */
    fun sincronizar(
        parejas: List<Pareja>,
        op: OpcionesDePasada = OpcionesDePasada(),
        obs: Observador = object : Observador {},
    ): List<Resultado> {
        // El nivel de log es global y hay que ponerlo antes: si no, el log sale
        // vacío y no avisa (ver Pasada.nivelDeLog).
        parejas.firstOrNull()?.let { rclone.nivelDeLog(Pasada.nivelDeLog(it.flags)) }
        return parejas.map { unaPareja(it, op, obs) }
    }

    /** Una pareja, de principio a fin. */
    fun unaPareja(
        pareja: Pareja,
        op: OpcionesDePasada = OpcionesDePasada(),
        obs: Observador = object : Observador {},
    ): Resultado {
        val ffile = try {
            ficheroDeFiltros(pareja)
        } catch (e: java.io.IOException) {
            return fallo(pareja, "No se puede escribir el fichero de filtros: ${e.message}", op, obs)
        }

        var resync = op.forzarResync
        if (pareja.isBisync) {
            val estado = Bisync.pairState(volumen.workdir(pareja))
            val razones = Bisync.resyncReasons(pareja, estado, Bisync.filtersState(ffile))
            resync = op.forzarResync || razones.isNotEmpty()

            if (resync && !op.resyncAprobado) {
                razones.forEach { obs.avisa("[${pareja.name}] requiere --resync -> $it") }
                val saltada = Resultado(
                    pareja = pareja.name,
                    codigo = Pasada.SALTADA,
                    log = "",
                    explicacion = "Saltada: requiere --resync y no está aprobado.",
                )
                obs.acaba(saltada)
                return saltada
            }

            // Si YA había baseline y la carpeta local no está, algo va mal.
            // Crearla vacía le diría a bisync que se ha borrado todo, y con
            // `--max-delete` como único freno. Es el aborto deliberado de
            // `_bisync_preflight`.
            val carpeta = volumen.carpetaLocal(pareja)
            if (estado.hasBaseline && !carpeta.exists()) {
                return fallo(
                    pareja,
                    "Existe baseline pero la carpeta '${carpeta.path}' no está. Se aborta: " +
                        "no se crea vacía a propósito, porque bisync leería que se ha " +
                        "borrado todo.",
                    op,
                    obs,
                )
            }
        }

        val carpeta = volumen.carpetaLocal(pareja)
        if (!carpeta.exists() && !carpeta.mkdirs()) {
            return fallo(pareja, "No se puede crear la carpeta '${carpeta.path}'.", op, obs)
        }
        volumen.workdir(pareja).mkdirs()

        val opciones = Opciones(
            resync = resync,
            dryRun = op.dryRun,
            workdir = volumen.workdir(pareja).absolutePath,
            filtersFile = ffile?.absolutePath,
            grupo = Pasada.grupoDe(pareja),
        )
        val peticion = try {
            val t = Pasada.traducir(pareja, opciones)
            t.ignorados.forEach { (flag, motivo) ->
                obs.avisa("[${pareja.name}] el flag '$flag' no se aplica aquí: $motivo")
            }
            Pasada.peticion(pareja, opciones)
        } catch (e: ConfigError) {
            // Un flag que no puede viajar es un fallo de config, no de red, y
            // se cuenta como tal — pero solo de ESTA pareja: las demás no
            // tienen por qué quedarse sin sincronizar por ella.
            return fallo(pareja, e.message ?: "config inválida", op, obs)
        }

        obs.empieza(pareja, resync)
        rclone.logReiniciar()
        val estado = try {
            val jobid = Pasada.jobid(rclone.rpc(peticion.metodo, peticion.json))
            esperarElJob(jobid, pareja, opciones.grupo, obs)
        } catch (e: RcloneError) {
            return fallo(pareja, e.message ?: "rclone no ha podido con la llamada", op, obs)
        }

        val log = rclone.logTexto()
        val codigo = if (estado.exito) 0 else CODIGO_FALLO
        val guardado = Resultados.disponerDelLog(
            volumen.logs, pareja.name, log, codigo, op.guardarLogs, ahora(),
        )
        if (!op.dryRun) {
            Resultados.apuntar(volumen.state, pareja.name, codigo, guardado, ahora())
        }

        val resultado = Resultado(
            pareja = pareja.name,
            codigo = codigo,
            log = log,
            salida = estado.salida,
            error = estado.error,
            explicacion = if (codigo == 0) null else Pasada.explicarFallo(log),
        )

        comprobarLaSesion(pareja, resultado, obs)
        avisarDeConflictos(pareja, op, obs)
        obs.acaba(resultado)
        return resultado
    }

    // -----------------------------------------------------------------------

    private fun esperarElJob(
        jobid: Long,
        pareja: Pareja,
        grupo: String?,
        obs: Observador,
    ): EstadoJob {
        val entrada = escribirJson(mapOf("jobid" to jobid))
        while (true) {
            val estado = Pasada.estadoJob(rclone.rpc("job/status", entrada))
            if (estado.acabado) return estado
            progresoDe(grupo)?.let { obs.progreso(pareja, it) }
            esperar(cada)
        }
    }

    /**
     * El progreso, preguntado a `core/stats` por el grupo de esta pasada.
     *
     * Es puramente informativo, así que **cualquier fallo se traga**: un
     * `core/stats` que no contesta no puede tumbar una sincronización que va
     * bien. Misma regla que el hilo que lee el log en el escritorio.
     */
    private fun progresoDe(grupo: String?): Progreso? {
        if (grupo == null) return null
        return runCatching {
            val respuesta = rclone.rpc("core/stats", escribirJson(mapOf("group" to grupo)))
            if (!respuesta.ok) return null
            Progresos.deStats(leerObjetoJson(respuesta.salida))
        }.getOrNull()
    }

    /**
     * El nombre de sesión que usó rclone contra el que calcula el motor.
     *
     * Es una red que el escritorio no tiene: ahí el prefijo se calcula y se
     * cruza los dedos, y aquí `sync/bisync` **devuelve** el que ha usado. Si no
     * cuadran, el baseline se va a perder en la pasada siguiente, y es mejor
     * decirlo ahora que descubrirlo cuando cada pasada pida `--resync`.
     */
    private fun comprobarLaSesion(pareja: Pareja, resultado: Resultado, obs: Observador) {
        val sesion = resultado.sesion ?: return
        val esperado = Bisync.expectedPrefix(pareja)
        if (sesion != esperado) {
            obs.avisa(
                "[${pareja.name}] AVISO: rclone ha usado el baseline '$sesion' y el " +
                    "motor esperaba '$esperado'. La pasada siguiente no lo encontraría.",
            )
        }
    }

    /**
     * Los conflictos que haya dejado bisync, apuntados y avisados.
     *
     * Después de **cada** pasada, buena o mala: rclone renombra al perdedor en
     * cuanto lo detecta, así que un fallo más adelante no quita el conflicto.
     */
    private fun avisarDeConflictos(pareja: Pareja, op: OpcionesDePasada, obs: Observador) {
        if (!pareja.isBisync || op.dryRun) return
        val encontrados = runCatching {
            Conflictos.actualizarPareja(volumen, pareja, ahora())
        }.getOrNull() ?: return
        Conflictos.aviso(pareja.name, encontrados)?.let { obs.avisa(it) }
    }

    private fun ficheroDeFiltros(pareja: Pareja): File? =
        Bisync.filtersFileFor(pareja, volumen.filters)

    private fun fallo(
        pareja: Pareja,
        mensaje: String,
        op: OpcionesDePasada,
        obs: Observador,
    ): Resultado {
        val log = rclone.logTexto()
        val guardado = Resultados.disponerDelLog(
            volumen.logs, pareja.name, if (log.isBlank()) mensaje else log,
            CODIGO_FALLO, op.guardarLogs, ahora(),
        )
        if (!op.dryRun) {
            Resultados.apuntar(volumen.state, pareja.name, CODIGO_FALLO, guardado, ahora())
        }
        val resultado = Resultado(
            pareja = pareja.name,
            codigo = CODIGO_FALLO,
            log = log,
            error = mensaje,
            // El mensaje también se traduce, no solo el log: cuando la llamada
            // ni sale, el único texto que hay es el error de rclone, y ahí
            // están justo las agujas de la API rc («didn't find section…»).
            explicacion = Pasada.explicarFallo(log) ?: Pasada.explicarFallo(mensaje) ?: mensaje,
        )
        obs.acaba(resultado)
        return resultado
    }

    companion object {
        /**
         * Con qué código se apunta una pasada fallida.
         *
         * En el escritorio es el código de salida de rclone, porque allí es un
         * proceso. Aquí no hay proceso ni código: el rc dice si el job fue bien
         * o mal y nada más. Da igual cuál sea el número mientras no sea 0,
         * porque lo único que se lee de él es eso (`results.fallos_de`), pero
         * queda dicho para que nadie lo interprete como un código de rclone.
         */
        const val CODIGO_FALLO = 1
    }
}
