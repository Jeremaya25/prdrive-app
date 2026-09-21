package prdrive.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import prdrive.engine.Catalogo
import prdrive.engine.Conflictos
import prdrive.engine.Config
import prdrive.engine.ConfigError
import prdrive.engine.Fallo
import prdrive.engine.Observador
import prdrive.engine.OpcionesDePasada
import prdrive.engine.Pairing
import prdrive.engine.Pareja
import prdrive.engine.Progreso
import prdrive.engine.Resultado
import prdrive.engine.Resultados
import prdrive.engine.Volumen
import prdrive.engine.loadRaw
import prdrive.engine.parseConfig

/**
 * Modelo.kt — El estado de la pantalla, y nada más.
 *
 * Aquí no se decide nada de sincronización: cada función de este fichero es
 * una llamada a `:engine` y un `update` del estado. Lo que se prueba del
 * comportamiento se prueba allí, sin teléfono; lo que se prueba de aquí es que
 * la app se abre y hace lo que se le pide, y eso hay que verlo en un móvil.
 *
 * **Nada corre en el hilo principal.** Una pasada de bisync tarda lo que tarde,
 * y `librclone.RPC` es una llamada JNI que bloquea: va en `Dispatchers.IO`.
 */
class Modelo(private val app: Aplicacion) : ViewModel() {

    /** Lo que la pantalla necesita saber para pintarse. */
    data class Estado(
        /** Todavía no se ha mirado nada. */
        val cargando: Boolean = true,
        /** La conexión que hay, si ya se emparejó. */
        val carga: Pairing.Carga? = null,
        /** El config del volumen, si ya hay parejas elegidas. */
        val config: Config? = null,
        /** El catálogo leído, para la pantalla de elegir parejas. */
        val catalogo: Catalogo? = null,
        /** Lo que hay que contarle al usuario, en ámbar. */
        val aviso: String? = null,
        /** La pareja que está corriendo ahora, y su progreso. */
        val corriendo: String? = null,
        val progreso: Progreso? = null,
        /** El diario de la pasada: lo que iría a la ventana de salida. */
        val salida: List<String> = emptyList(),
        /** Cómo acabó la última pasada de cada pareja. */
        val fallos: List<Fallo> = emptyList(),
        /** Cuántos conflictos tiene cada pareja. */
        val conflictos: Map<String, Int> = emptyMap(),
        /** Las parejas que piden `--resync`, y por qué. Vacío = ninguna. */
        val pidenResync: Map<String, List<String>> = emptyMap(),
    ) {
        /** ¿Hay que enseñar el primer arranque? */
        val emparejado: Boolean get() = carga != null
        val listo: Boolean get() = config != null
        val ocupado: Boolean get() = corriendo != null
    }

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    private val volumen: Volumen get() = app.volumen

    init {
        refrescar()
    }

    /** Lee del volumen lo que ya hay. No toca la red. */
    fun refrescar() {
        viewModelScope.launch {
            val nuevo = withContext(Dispatchers.IO) {
                volumen.crear()
                val carga = volumen.emparejamientoActual()
                val config = runCatching { volumen.configActual() }.getOrNull()
                // El rclone.conf se regenera aquí: lleva rutas absolutas y
                // `filesDir` cambia al reinstalar la app (ver Volumen).
                if (carga != null) {
                    runCatching { volumen.escribirRcloneConf(carga, config) }
                }
                Estado(
                    cargando = false,
                    carga = carga,
                    config = config,
                    fallos = config?.let {
                        Resultados.fallos(volumen.state, volumen.logs, it.names)
                    } ?: emptyList(),
                    conflictos = config?.let {
                        Conflictos.contar(Conflictos.cargar(volumen, it))
                    } ?: emptyMap(),
                    pidenResync = config?.let {
                        app.sincronizador.razonesDeResync(it.pairs)
                    } ?: emptyMap(),
                )
            }
            _estado.value = nuevo
        }
    }

    /**
     * Guarda la conexión que venía en el QR.
     *
     * La carga se lee con `Pairing.leer`, que es el mismo formato que escribe
     * el PC: si el código no es de prdrive, lo dice con una frase en vez de con
     * una excepción rara.
     */
    fun emparejar(texto: String) {
        viewModelScope.launch {
            val aviso = withContext(Dispatchers.IO) {
                try {
                    val carga = Pairing.leer(texto)
                    if (!carga.configurado) {
                        return@withContext "El código no trae un remoto configurado."
                    }
                    volumen.escribirClaves(carga)
                    volumen.escribirRcloneConf(carga, volumen.configActual())
                    null
                } catch (e: Pairing.PairingError) {
                    e.message
                } catch (e: java.io.IOException) {
                    "No se puede escribir en el volumen: ${e.message}"
                }
            }
            if (aviso != null) {
                _estado.update { it.copy(aviso = aviso) }
            } else {
                refrescar()
            }
        }
    }

    /** Baja el catálogo del remoto. Si no hay red, cae a la copia y lo dice. */
    fun leerCatalogo() {
        viewModelScope.launch {
            val (cat, aviso) = withContext(Dispatchers.IO) {
                val defaults = _estado.value.carga?.let {
                    mapOf("remote" to it.remoteName, "catalog_path" to it.catalogPath)
                } ?: emptyMap()
                Catalogo.cargar(app.rclone, defaults, volumen.state)
            }
            _estado.update {
                it.copy(
                    catalogo = cat,
                    // Un backend que no viaja en el .aar se dice AQUÍ, al leer
                    // el catálogo, y no dejando que falle la primera pasada.
                    aviso = cat?.backendNoSoportado() ?: aviso,
                )
            }
        }
    }

    /**
     * Escribe el config con las parejas elegidas y crea sus carpetas.
     *
     * No sincroniza: la primera pasada la pide el usuario, porque es un
     * `--resync` y eso decide qué se borra.
     */
    fun elegirParejas(nombres: List<String>) {
        val cat = _estado.value.catalogo ?: return
        viewModelScope.launch {
            val aviso = withContext(Dispatchers.IO) {
                try {
                    val raw = Volumen.configDeDispositivo(
                        cat, nombres, _estado.value.carga?.catalogPath ?: "",
                    )
                    volumen.escribirConfig(raw)
                    val config = parseConfig(loadRaw(volumen.syncConfig.readText()))
                    volumen.crearCarpetasDeParejas(config)
                    // Y el rclone.conf otra vez, que ahora ya lleva [disp].
                    _estado.value.carga?.let { volumen.escribirRcloneConf(it, config) }
                    null
                } catch (e: ConfigError) {
                    e.message
                } catch (e: java.io.IOException) {
                    "No se puede escribir el config: ${e.message}"
                }
            }
            if (aviso != null) _estado.update { it.copy(aviso = aviso) } else refrescar()
        }
    }

    /**
     * Sincroniza. Las parejas van de una en una, que es lo que hace el motor
     * —`bilib.CaptureOutput` redirige la salida del proceso entero—.
     */
    fun sincronizar(
        parejas: List<Pareja>? = null,
        dryRun: Boolean = false,
        resyncAprobado: Boolean = false,
    ) {
        val config = _estado.value.config ?: return
        if (_estado.value.ocupado) return
        val elegidas = parejas ?: config.pairs
        _estado.update { it.copy(salida = emptyList(), aviso = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                app.sincronizador.sincronizar(
                    elegidas,
                    OpcionesDePasada(
                        dryRun = dryRun,
                        resyncAprobado = resyncAprobado,
                        guardarLogs = config.keepLogs,
                    ),
                    observador,
                )
            }
            refrescar()
        }
    }

    /** El log de una pareja que falló, para poder leerlo. */
    fun logDe(fallo: Fallo): String =
        fallo.log?.let { runCatching { it.readText() }.getOrNull() }
            ?: "El log ya no está en el volumen."

    fun descartarAviso() = _estado.update { it.copy(aviso = null) }

    /**
     * Lo que el motor cuenta mientras corre, convertido en estado.
     *
     * La línea de progreso se **reescribe en su sitio** en vez de apilarse, que
     * es lo que hace la ventana de salida del escritorio: con una lectura cada
     * medio segundo, apilarlas llenaría la pantalla de números.
     */
    private val observador = object : Observador {
        override fun empieza(pareja: Pareja, resync: Boolean) {
            val cabecera = "=== ${pareja.name} (${pareja.mode.name}) ===" +
                if (resync) "  [--resync]" else ""
            _estado.update {
                it.copy(corriendo = pareja.name, progreso = null, salida = it.salida + cabecera)
            }
        }

        override fun progreso(pareja: Pareja, progreso: Progreso) {
            _estado.update { it.copy(progreso = progreso) }
        }

        override fun avisa(texto: String) {
            _estado.update { it.copy(salida = it.salida + texto.lines()) }
        }

        override fun acaba(resultado: Resultado) {
            val cola = when {
                resultado.saltada -> listOf("[${resultado.pareja}] ${resultado.explicacion}")
                resultado.ok -> listOf("[${resultado.pareja}] OK.")
                else -> buildList {
                    add("[${resultado.pareja}] FALLÓ. ${resultado.error}")
                    addAll(prdrive.engine.Pasada.colaDelLog(resultado.log).map { "  $it" })
                    resultado.explicacion?.let { add("  >> $it") }
                }
            }
            _estado.update {
                it.copy(corriendo = null, progreso = null, salida = it.salida + cola)
            }
        }
    }

    /**
     * La fábrica, que es lo único que este modelo necesita del sistema.
     *
     * Los nombres de los parámetros son los de la API de Android y no los del
     * dominio (`modelClass`, no `clase`): es lo que dice el convenio del
     * proyecto —los del TOML y de prdrive en español, lo que es API de
     * Kotlin/Java como se escribe en Kotlin— y además el compilador avisa.
     */
    class Factoria(private val app: Aplicacion) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = Modelo(app) as T
    }
}
