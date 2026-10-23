package prdrive.app.ui

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
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
import prdrive.app.documentos.VolumenProvider
import prdrive.app.llavero.Avisos
import prdrive.app.llavero.LlaveroApp
import prdrive.app.rclone.Adaptadores
import prdrive.engine.Catalogo
import prdrive.engine.Config
import prdrive.engine.ConfigError
import prdrive.engine.Conflictos
import prdrive.engine.LLAVERO
import prdrive.engine.LLAVERO_LOCAL
import prdrive.engine.Pairing
import prdrive.engine.Pasada
import prdrive.engine.Volumen
import prdrive.engine.loadRaw

/**
 * Modelo.kt — El estado de las dos pantallas y lo que hacen sus botones.
 *
 * Todo lo que habla con rclone o con el disco bloquea, así que va fuera del
 * hilo de la pantalla, y de uno en uno: [ocupado] dice qué se está haciendo, y
 * mientras lo diga no empieza nada más (las pasadas, además, las serializa
 * [Adaptadores.pasada]). El estado son `mutableStateOf`, que Compose deja
 * escribir desde cualquier hilo, siempre que el modelo no se cree dentro de una
 * composición (`MainActivity.onCreate` dice por qué).
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

    /** Si al instalar se trae también el llavero (la casilla «El llavero»). */
    var conLlavero by mutableStateOf(false)
        private set

    /** El fichero llave elegido para un llavero sin contraseña (`llave_interna`). */
    var llaveDelLlavero by mutableStateOf<Uri?>(null)
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

    /** La fila del llavero; `null` si no lo lleva ni se puede traer. */
    var llavero by mutableStateOf<Estado.FilaLlavero?>(null)
        private set

    /**
     * Lo que «Abrir llavero» dice antes de abrir (copias de conflicto, la llave
     * que falta); al cerrarlo, se abre.
     */
    var avisoLlavero by mutableStateOf<String?>(null)
        private set

    /**
     * La base que hay que abrir ya, de una sola vez: la pantalla la abre con
     * su contexto de ese momento y la quita ([abierta]). Un estado y no una
     * lambda de la pantalla: girar el teléfono durante la pasada o el aviso
     * cambia la actividad, y la de antes ya no puede abrir nada.
     */
    var porAbrir by mutableStateOf<Uri?>(null)
        private set

    /** La base que se abrirá al cerrar [avisoLlavero]. */
    private var trasElAviso: Uri? = null

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
            // El llavero, desmarcado: es un paso opcional, como en el instalador.
            conLlavero = false
            llaveDelLlavero = null
            paso = Paso.PAREJAS
        }
    }

    fun elegir(nombre: String, si: Boolean) {
        elegidas = if (si) elegidas + nombre else elegidas - nombre
    }

    /** Marca o desmarca «El llavero»; con [llave], el fichero llave de uno sin contraseña. */
    fun elegirLlavero(si: Boolean, llave: Uri? = null) {
        conLlavero = si
        llaveDelLlavero = if (si) llave else null
    }

    /**
     * Siembra el volumen con las elegidas y hace el `--resync` inicial de las
     * bisync.
     *
     * Con «El llavero», lo activa después de esos `--resync` y lo pasa el
     * último, como `sync.py`. Después y no antes: el `--resync` va con el
     * config de [Instalador.instalar], de antes del llavero, y una pareja de la
     * raíz entera aún no lleva la regla que deja fuera `.keychain/`
     * (`REGLA_SIN_LLAVERO` se le pone al leer el config); con la carpeta ya
     * creada, subiría la llave. Así pedirá un `--resync` más, que es lo que
     * avisa prdrive (`Volumen.avisosAlta`). Si activarlo falla, el error se
     * dice al final: el llavero se puede traer después desde la principal.
     */
    fun instalar() {
        val c = carga ?: return
        val cat = catalogo ?: return
        val orden = cat.names.filter { it in elegidas }
        val remota = if (conLlavero) tablaLlavero(cat) else null
        val llave = llaveDelLlavero
        trabajo("Instalando…") {
            val hecho = Instalador.instalar(contexto, c, cat, orden)
            instalado = hecho
            paso = Paso.INICIANDO
            lineas = emptyList()
            var falloLlavero: Exception? = null
            try {
                for (nombre in Instalador.aInicializar(hecho.config, cat)) {
                    val r = Instalador.inicializar(contexto, hecho.config, nombre, { progreso = Progreso.de(nombre, it) }, ::decir)
                    resultados = resultados + (nombre to r)
                }
                if (remota != null) {
                    val activado = try {
                        LlaveroApp.enExclusiva { Instalador.activarLlavero(contexto, remota, llave) }
                        true
                    } catch (e: InterruptedException) {
                        // La app se cierra: no es un fallo del llavero.
                        throw e
                    } catch (e: Exception) {
                        falloLlavero = e
                        false
                    }
                    if (activado) pasarLlavero()
                }
            } finally {
                // Bien o mal, el volumen ya está sembrado: se puede ir a la principal.
                inicioTerminado = true
            }
            falloLlavero?.let { throw Exception("No se ha podido traer el llavero: ${it.message}", it) }
        }
    }

    /** La tabla `[keychain]` de un catálogo, o `null` si no la tiene. */
    @Suppress("UNCHECKED_CAST")
    private fun tablaLlavero(cat: Catalogo.Catalog): Map<String, Any?>? = cat.raw["keychain"] as? Map<String, Any?>

    /** Del primer arranque a la pantalla principal. */
    fun empezar() {
        sembrado = volumen.sembrado
        if (sembrado) trabajo("Preparando…") { refrescar() }
    }

    // --- principal --------------------------------------------------------------

    private fun config(): Config = volumen.leerConfig()

    private fun refrescar() {
        val v = Prdrive.iniciarRclone(contexto)
        val config = config()
        filas = Estado.filas(v, config)
        llavero = Estado.llavero(v, config, Prdrive.faltaRedLocal(contexto))
    }

    /** La fila del llavero otra vez, de `state/` y del disco. */
    private fun releerLlavero() {
        llavero = Estado.llavero(volumen, config(), Prdrive.faltaRedLocal(contexto))
    }

    /**
     * Al volver a la app (de la app de KeePass, por ejemplo), la fila del
     * llavero puede haber cambiado: un guardado sin subir, uno cortado, una
     * pasada de fondo. Solo se lee, sin red; con algo en marcha no hace falta,
     * que al acabar se relee.
     */
    fun alVolver() {
        if (!sembrado || ocupado != null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                releerLlavero()
            } catch (e: Exception) {
                // Un config a medio escribir: la próxima vez.
                android.util.Log.w("Modelo", "Releyendo la fila del llavero", e)
            }
        }
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
                // El llavero, el último, como `sync.py` sin nombres: no sale
                // entre las parejas, pero «Sincronizar» lo pasa.
                if (config.parejaLlavero != null) pasarLlavero()
            } finally {
                filas = Estado.filas(v, config)
                releerLlavero()
            }
        }
    }

    // --- el llavero -----------------------------------------------------------------

    /**
     * Pasa el llavero con el progreso de siempre ([LlaveroApp.pasar]: su
     * cerrojo, `cifrado = true` y los avisos), y apunta cómo ha ido.
     *
     * @return su código, o `null` si no había llavero que pasar.
     */
    private fun pasarLlavero(): Int? {
        val r = LlaveroApp.pasar(contexto, { progreso = Progreso.de(LLAVERO, it) }, ::decir) ?: return null
        resultados = resultados + (LLAVERO to r)
        return r.codigo
    }

    /**
     * «Abrir llavero», en el orden de `llavero_editor.abrir()`:
     *
     * 1. Si la última pasada buena no es reciente ([LlaveroApp.reciente]), trae
     *    lo último. Si falla, se abre igual y se dice.
     * 2. Sin base tras la pasada, se dice por qué y no se abre.
     * 3. Con copias de conflicto de la base, o sin el fichero llave de un
     *    llavero sin contraseña, un aviso ([avisoLlavero]) que al cerrarse abre.
     * 4. [porAbrir] con el `content://` de la base en [VolumenProvider], que
     *    la pantalla abre.
     */
    fun abrirLlavero() {
        trabajo("Abriendo el llavero…") {
            val v = Prdrive.volumen(contexto)
            val config = config()
            val pareja = config.parejaLlavero ?: throw ConfigError(Volumen.SIN_LLAVERO)
            val base = config.llavero?.get("base")?.toString() ?: throw ConfigError(Volumen.SIN_LLAVERO)
            val fichero = java.io.File(pareja.carpetaEn(v.raiz), base)
            var codigo = 0
            if (!LlaveroApp.reciente(contexto)) {
                ocupado = TextosLlavero.TRAYENDO
                lineas = emptyList()
                try {
                    codigo = pasarLlavero() ?: 0
                } finally {
                    ocupado = "Abriendo el llavero…"
                    progreso = null
                    releerLlavero()
                }
            }
            if (!fichero.isFile) {
                error = if (codigo == 0) TextosLlavero.SIN_BASE else TextosLlavero.sinTraerBase(codigo)
                return@trabajo
            }
            if (codigo != 0) error = TextosLlavero.sinTraer(codigo)
            // Las copias de la base, de nuevo del disco (`conflicto_de_la_base()`):
            // pueden haberse combinado fuera desde la última pasada. Con el
            // cerrojo del llavero, que también apunta en `state/`.
            val copias = LlaveroApp.enExclusiva { Conflictos.actualizarPareja(pareja, v.raiz, v.state) }
                .filter { it.original.name == base }.flatMap { it.copias }
            releerLlavero()
            val avisos = listOfNotNull(
                if (llavero?.faltaLlave == true) TextosLlavero.FALTA_LLAVE else null,
                if (copias.isNotEmpty()) {
                    Avisos.LLAVERO_EN_CONFLICTO + Avisos.COMBINAR_FUERA + "\n\n" +
                        copias.joinToString("\n") { "· ${it.name}" }
                } else {
                    null
                },
            )
            val uri = DocumentsContract.buildDocumentUri(
                VolumenProvider.AUTORIDAD, "${VolumenProvider.ID_RAIZ}/$LLAVERO_LOCAL/$base",
            )
            if (avisos.isEmpty()) {
                porAbrir = uri
            } else {
                trasElAviso = uri
                avisoLlavero = avisos.joinToString("\n\n")
            }
        }
    }

    /** Se ha leído [avisoLlavero]: se abre la base. */
    fun cerrarAvisoLlavero() {
        porAbrir = trasElAviso
        trasElAviso = null
        avisoLlavero = null
    }

    /** La pantalla ya ha pedido abrir [porAbrir]. */
    fun abierta() {
        porAbrir = null
    }

    /**
     * «Traer el llavero»: relee el catálogo del remoto y, si tiene llavero, lo
     * activa en el teléfono ([Instalador.activarLlavero], con [llave] si va sin
     * contraseña) y lo pasa por primera vez, que trae la base.
     *
     * Activarlo reescribe el `rclone.conf` y recarga rclone: va con el cerrojo
     * del llavero, para no hacerlo con una pasada suya en marcha.
     */
    fun traerLlavero(llave: Uri?) {
        trabajo("Leyendo el catálogo del remoto…") {
            val v = Prdrive.iniciarRclone(contexto)
            try {
                val cat = Catalogo.pull(
                    Adaptadores.catalogo(Prdrive.temporal(contexto)),
                    loadRaw(v.config.readText(Charsets.UTF_8)),
                    v.state,
                )
                val remota = tablaLlavero(cat) ?: throw ConfigError(TextosLlavero.SIN_LLAVERO_REMOTO)
                ocupado = "Activando el llavero…"
                LlaveroApp.enExclusiva { Instalador.activarLlavero(contexto, remota, llave) }
                ocupado = TextosLlavero.TRAYENDO
                lineas = emptyList()
                pasarLlavero()
            } finally {
                releerLlavero()
            }
        }
    }

    /**
     * «Quitar el llavero de este teléfono» ([Instalador.quitarLlavero]): con el
     * cerrojo del llavero, como [traerLlavero]. `.keychain/` se queda.
     */
    fun quitarLlavero() {
        trabajo("Quitando el llavero…") {
            try {
                LlaveroApp.enExclusiva { Instalador.quitarLlavero(contexto) }
            } finally {
                releerLlavero()
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
