package prdrive.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import prdrive.app.Actualizador
import prdrive.app.Estado
import prdrive.app.Instalador
import prdrive.app.Prdrive
import prdrive.app.rclone.Adaptadores
import prdrive.engine.Catalogo
import prdrive.engine.Config
import prdrive.engine.Pairing
import prdrive.engine.Pasada
import prdrive.engine.Volumen

/**
 * Modelo.kt — El estado de las dos pantallas y lo que hacen sus botones.
 *
 * Todo lo que habla con rclone o con el disco bloquea, así que va fuera del
 * hilo de la pantalla, y de uno en uno: [ocupado] dice qué se está haciendo, y
 * mientras lo diga no empieza nada más (las pasadas, además, las serializa
 * [Adaptadores.pasada]). El estado son `mutableStateOf`, que Compose deja
 * escribir desde cualquier hilo.
 */
class Modelo(app: Application) : AndroidViewModel(app) {

    /** Por dónde va el primer arranque. */
    enum class Paso { CODIGO, PAREJAS, INICIANDO }

    /** Lo que llega de `core/stats` mientras pasa una pareja. */
    data class Progreso(
        val pareja: String,
        val bytes: Long,
        val totalBytes: Long,
        val ficheros: Long,
        val totalFicheros: Long,
        val velocidad: Double,
    ) {
        /** De 0 a 1, o `null` si rclone aún no sabe cuánto hay. */
        val fraccion: Float? get() = if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null

        companion object {
            private fun numero(v: Any?): Number = v as? Number ?: 0

            fun de(pareja: String, stats: Map<String, Any?>) = Progreso(
                pareja,
                numero(stats["bytes"]).toLong(),
                numero(stats["totalBytes"]).toLong(),
                numero(stats["transfers"]).toLong(),
                numero(stats["totalTransfers"]).toLong(),
                numero(stats["speed"]).toDouble(),
            )
        }
    }

    private val contexto get() = getApplication<Application>()
    private val volumen: Volumen get() = Prdrive.volumen(contexto)

    /** Si el volumen ya está sembrado: entonces la pantalla es la principal. */
    var sembrado by mutableStateOf(volumen.sembrado)
        private set

    /** Qué se está haciendo ahora, para enseñarlo; `null` si nada. */
    var ocupado by mutableStateOf<String?>(null)
        private set

    /** El último error, para la persona; se borra al empezar otra cosa. */
    var error by mutableStateOf<String?>(null)
        private set

    // --- primer arranque -----------------------------------------------------

    var paso by mutableStateOf(Paso.CODIGO)
        private set
    var carga by mutableStateOf<Pairing.Carga?>(null)
        private set
    var catalogo by mutableStateOf<Catalogo.Catalog?>(null)
        private set
    var elegidas by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Si el `--resync` inicial ha terminado (bien o mal). */
    var inicioTerminado by mutableStateOf(false)
        private set

    /** Lo que el instalador ha ajustado (el nombre del remote) y los espejos, que no se inicializan. */
    var instalado by mutableStateOf<Instalador.Instalado?>(null)
        private set

    // --- pasadas (las del primer arranque y las de la principal) --------------

    var progreso by mutableStateOf<Progreso?>(null)
        private set

    /** Lo que van contando las pasadas, las últimas [MAX_LINEAS]. */
    var lineas by mutableStateOf<List<String>>(emptyList())
        private set

    /** El resultado de la última pasada de cada pareja en esta sesión. */
    var resultados by mutableStateOf<Map<String, Pasada.Resultado>>(emptyMap())
        private set

    // --- principal --------------------------------------------------------------

    var filas by mutableStateOf<List<Estado.Fila>>(emptyList())
        private set

    init {
        if (sembrado) trabajo("Preparando…") { refrescar() }
    }

    /**
     * Ejecuta [bloque] fuera del hilo de la pantalla, si no hay otra cosa en
     * marcha. Lo que lanza acaba en [error]: es el borde, y aquí un fallo se
     * cuenta en vez de cerrar la app.
     *
     * `runInterruptible`: si el modelo se cierra (la app sale del todo) con una
     * pasada en marcha, el hilo se interrumpe, y eso para el trabajo de rclone
     * y espera a que suelte el lock, sin apuntar la pasada como fallida
     * ([Rclone.trabajo][prdrive.app.rclone.Rclone.trabajo]).
     *
     * Y se lo dice al [Actualizador]: mientras haya algo en marcha, la versión
     * nueva no se instala (instalarse cierra la app y lo cortaría), y con ella
     * instalándose no empieza nada.
     */
    private fun trabajo(que: String, bloque: () -> Unit) {
        if (ocupado != null) return
        if (!Actualizador.empezarTrabajo()) {
            error = "Se está instalando la versión nueva de prdrive y la app se cerrará en un momento. " +
                "Vuelve a abrirla para seguir."
            return
        }
        ocupado = que
        error = null
        viewModelScope.launch {
            try {
                runInterruptible(Dispatchers.IO) { bloque() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val mensaje = e.message?.takeIf { it.isNotBlank() } ?: e.toString()
                error = if (Pasada.esDeRed(mensaje) && Prdrive.faltaRedLocal(contexto)) {
                    "$mensaje\n\n${Prdrive.SIN_RED_LOCAL}"
                } else {
                    mensaje
                }
            } finally {
                ocupado = null
                progreso = null
                Actualizador.acabarTrabajo(contexto)
            }
        }
    }

    private fun decir(linea: String) {
        lineas = (lineas + linea).takeLast(MAX_LINEAS)
    }

    // --- primer arranque -----------------------------------------------------

    /** Lee el texto de un QR (escaneado o pegado). No toca nada todavía. */
    fun leerCodigo(texto: String) {
        error = null
        carga = try {
            Pairing.leer(texto.trim())
        } catch (e: Pairing.PairingError) {
            error = e.message
            null
        }
    }

    fun avisar(mensaje: String) {
        error = mensaje
    }

    /** Escribe la conexión y lee el catálogo: si se lee, la conexión va. */
    fun conectar() {
        val c = carga ?: return
        trabajo("Conectando y leyendo el catálogo…") {
            Instalador.conectar(contexto, c)
            val cat = Instalador.leerCatalogo(contexto, c)
            catalogo = cat
            elegidas = cat.names.toSet()
            paso = Paso.PAREJAS
        }
    }

    fun elegir(nombre: String, si: Boolean) {
        elegidas = if (si) elegidas + nombre else elegidas - nombre
    }

    /** Siembra el volumen con las elegidas y hace el `--resync` inicial de las bisync. */
    fun instalar() {
        val c = carga ?: return
        val cat = catalogo ?: return
        val orden = cat.names.filter { it in elegidas }
        trabajo("Instalando…") {
            val hecho = Instalador.instalar(contexto, c, cat, orden)
            instalado = hecho
            paso = Paso.INICIANDO
            lineas = emptyList()
            try {
                for (nombre in Instalador.aInicializar(hecho.config, cat)) {
                    val r = Instalador.inicializar(contexto, hecho.config, nombre, { progreso = Progreso.de(nombre, it) }, ::decir)
                    resultados = resultados + (nombre to r)
                }
            } finally {
                // Bien o mal, el volumen ya está sembrado: se puede ir a la principal.
                inicioTerminado = true
            }
        }
    }

    /** Del primer arranque a la pantalla principal. */
    fun empezar() {
        sembrado = volumen.sembrado
        if (sembrado) trabajo("Preparando…") { refrescar() }
    }

    // --- principal --------------------------------------------------------------

    private fun config(): Config = volumen.leerConfig()

    private fun refrescar() {
        val v = Prdrive.iniciarRclone(contexto)
        filas = Estado.filas(v, config())
    }

    /**
     * Pasa todas las parejas, una detrás de otra, como `run_all()`: un error de
     * una no se lleva las demás ([Pasada.pasarOFallar]), y las filas se
     * refrescan pase lo que pase.
     */
    fun sincronizar() {
        trabajo("Sincronizando…") {
            val v = Prdrive.iniciarRclone(contexto)
            val config = config()
            val ctx = Pasada.Contexto(keepLogs = config.keepLogs)
            lineas = emptyList()
            try {
                for (p in config.delUsuario) {
                    val r = Pasada.pasarOFallar(
                        v, p, Adaptadores.pasada, Adaptadores.opciones, ctx,
                        { progreso = Progreso.de(p.name, it) }, ::decir,
                    )
                    resultados = resultados + (p.name to r)
                }
            } finally {
                filas = Estado.filas(v, config)
            }
        }
    }

    /** Pasa una pareja con su `--resync` aprobado: lo pide quien ha leído por qué. */
    fun aprobarResync(nombre: String) {
        trabajo("Rehaciendo la referencia de $nombre…") {
            val v = Prdrive.iniciarRclone(contexto)
            val config = config()
            val p = config.pairs.first { it.name == nombre }
            lineas = emptyList()
            try {
                val r = Pasada.pasarOFallar(
                    v, p, Adaptadores.pasada, Adaptadores.opciones,
                    Pasada.Contexto(resyncAprobado = true, keepLogs = config.keepLogs),
                    { progreso = Progreso.de(p.name, it) }, ::decir,
                )
                resultados = resultados + (p.name to r)
            } finally {
                filas = Estado.filas(v, config)
            }
        }
    }

    companion object {
        const val MAX_LINEAS = 200
    }
}
