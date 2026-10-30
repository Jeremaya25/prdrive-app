package prdrive.app.ui

import android.app.Application
import android.net.Uri
import android.os.Build
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
import prdrive.app.compartidas.Compartir
import prdrive.app.documentos.VolumenProvider
import prdrive.app.llavero.Avisos
import prdrive.app.llavero.LlaveroApp
import prdrive.app.rclone.Adaptadores
import prdrive.app.rclone.Rclone
import prdrive.engine.Bisync
import prdrive.engine.Catalogo
import prdrive.engine.Compartidas
import prdrive.engine.Config
import prdrive.engine.ConfigError
import prdrive.engine.Conflictos
import prdrive.engine.LLAVERO
import prdrive.engine.LLAVERO_LLAVE
import prdrive.engine.LLAVERO_LOCAL
import prdrive.engine.Mudanza
import prdrive.engine.Pairing
import prdrive.engine.Pasada
import prdrive.engine.Volumen
import prdrive.engine.loadRaw

/**
 * Modelo.kt — El estado de las pantallas y lo que hacen sus botones.
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

    /**
     * Lo que el catálogo cambia de la conexión del código, o no hereda
     * ([Pairing.alinearConCatalogo]). Se dice al elegir las parejas, antes de
     * instalar, que es cuando todavía se puede no seguir: el «Según el catálogo»
     * del asistente del escritorio.
     */
    var notasDelCatalogo by mutableStateOf<List<String>>(emptyList())
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

    /** Lo que el instalador ha ajustado (el remote, según el catálogo) y los espejos, que no se inicializan. */
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

    /**
     * El remoto de las parejas y su tipo, como lo dice Inicio debajo de «Este
     * teléfono» («nas · sftp»); con varios, todos. `null` hasta que se lee.
     */
    var remoto by mutableStateOf<String?>(null)
        private set

    /** La pareja que se está moviendo (o devolviendo al volumen), si hay una: su ficha enseña cómo va. */
    var mudando by mutableStateOf<String?>(null)
        private set

    /** Las parejas del catálogo (de su copia, sin red) que este teléfono no usa. */
    var sinUsar by mutableStateOf<List<String>>(emptyList())
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
    var porAbrir by mutableStateOf<Apertura?>(null)
        private set

    /** La base que se abrirá al cerrar [avisoLlavero]. */
    private var trasElAviso: Apertura? = null

    /**
     * Lo que «Abrir llavero» le da a la app de KeePass: el `content://` de la
     * base y, si el llavero va sin contraseña y está, el de su fichero llave
     * ([llave]), que KeePassDX no sabe encontrar solo.
     */
    data class Apertura(val base: Uri, val llave: Uri?)

    /** Lo que se está sincronizando ahora: las parejas por orden y cómo han ido las ya pasadas. */
    data class EnCurso(val orden: List<String>, val hechas: Map<String, Pasada.Resultado> = emptyMap()) {
        /** La que está pasando, o `null` entre dos (o al acabar). */
        val ahora: String? get() = orden.firstOrNull { it !in hechas }
    }

    /** La pasada de «Sincronizar» en marcha, pareja a pareja; `null` si no hay. */
    var enCurso by mutableStateOf<EnCurso?>(null)
        private set

    /** Si lo que está en marcha se puede parar ahora: una pasada de «Sincronizar», fuera del llavero. */
    var sePuedeParar by mutableStateOf(false)
        private set

    /** El hilo que hace el trabajo de ahora ([trabajo]), para pedirle a rclone que lo pare. */
    @Volatile
    private var hiloDelTrabajo: Thread? = null

    /** Si se ha pedido parar: no se pasa ninguna pareja más. */
    @Volatile
    private var parando = false

    /** Si está pasando el llavero, que no se corta nunca. */
    @Volatile
    private var enElLlavero = false

    /** Para que pedir parar y empezar el llavero no se crucen: el llavero no se corta nunca. */
    private val cerrojoDeParar = Any()

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
    private fun trabajo(que: String, parable: Boolean = false, bloque: () -> Unit) {
        if (ocupado != null) return
        if (!Actualizador.empezarTrabajo()) {
            error = "Se está instalando la versión nueva de prdrive y la app se cerrará en un momento. " +
                "Vuelve a abrirla para seguir."
            return
        }
        ocupado = que
        error = null
        sePuedeParar = parable
        viewModelScope.launch {
            try {
                runInterruptible(Dispatchers.IO) {
                    hiloDelTrabajo = Thread.currentThread()
                    try {
                        bloque()
                    } finally {
                        synchronized(cerrojoDeParar) {
                            hiloDelTrabajo = null
                            Rclone.olvidarParada(Thread.currentThread())
                            parando = false
                        }
                    }
                }
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
                sePuedeParar = false
                enCurso = null
                Actualizador.acabarTrabajo(contexto)
            }
        }
    }

    /**
     * «Parar la pasada»: rclone para la pareja que está pasando y espera a que
     * la suelte ([Rclone.pedirParada]), y no se pasa ninguna más. Lo cortado no
     * se apunta, como un Ctrl+C en el escritorio; si era una bisync a medias,
     * sus listados se apartan y la pasada siguiente pide rehacer la referencia,
     * que no borra nada. El llavero no se corta nunca: mientras pasa, no se puede.
     */
    fun parar() {
        synchronized(cerrojoDeParar) {
            val hilo = hiloDelTrabajo ?: return
            if (!sePuedeParar || parando || enElLlavero) return
            parando = true
            Rclone.pedirParada(hilo)
        }
        sePuedeParar = false
        ocupado = PARANDO
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

    /** «Volver» desde el código leído: se olvida, con su clave, y se vuelve a la bienvenida. */
    fun olvidarCodigo() {
        if (ocupado != null) return
        carga = null
        error = null
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
            notasDelCatalogo = Pairing.alinearConCatalogo(c, cat.raw).second
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
        filas = filasDe(v, config)
        llavero = Estado.llavero(v, config, Prdrive.faltaRedLocal(contexto))
        remoto = remotoDe(v, config)
        sinUsar = Catalogo.cached(v.state)?.names?.filter { it !in config.names }.orEmpty()
        rutaCatalogo = try {
            @Suppress("UNCHECKED_CAST")
            val defaults = loadRaw(v.config.readText(Charsets.UTF_8))["defaults"] as? Map<String, Any?> ?: emptyMap()
            val remote = defaults["catalog_remote"] ?: defaults["remote"] ?: ""
            "$remote:${defaults["catalog_path"] ?: prdrive.engine.DEFAULT_CATALOG_PATH}"
        } catch (_: Exception) {
            null
        }
    }

    /** Dónde está el catálogo de parejas en el remoto («nas:/prdrive-catalog/remote.toml»), para Ajustes. */
    var rutaCatalogo by mutableStateOf<String?>(null)
        private set

    /** «nas · sftp»: el `remoteName` de las parejas y el tipo de su sección en el `rclone.conf`. */
    private fun remotoDe(v: Volumen, config: Config): String? {
        val tipos = try {
            Bisync.tiposDeRemote(v.leerRcloneConf())
        } catch (_: java.io.IOException) {
            emptyMap()
        }
        val nombres = config.delUsuario.map { it.remoteName }.distinct()
        if (nombres.isEmpty()) return null
        return nombres.joinToString(", ") { n -> tipos[n]?.tipo?.let { "$n · $it" } ?: n }
    }

    /**
     * Las filas de las parejas, con el acceso a las carpetas compartidas de
     * ahora y la mudanza solo donde Android la deja (desde la API 30).
     */
    private fun filasDe(v: Volumen, config: Config): List<Estado.Fila> =
        Estado.filas(v, config, Prdrive.accesoCompartido(), Build.VERSION.SDK_INT >= 30)

    /** La fila del llavero otra vez, de `state/` y del disco. */
    private fun releerLlavero() {
        llavero = Estado.llavero(volumen, config(), Prdrive.faltaRedLocal(contexto))
    }

    /**
     * Al volver a la app (de la app de KeePass, por ejemplo), la fila del
     * llavero puede haber cambiado: un guardado sin subir, uno cortado, una
     * pasada de fondo. Y las filas de las parejas, porque el permiso de acceso a
     * todos los archivos se da y se quita en los ajustes. Solo se lee, sin red;
     * con algo en marcha no hace falta, que al acabar se relee.
     */
    fun alVolver() {
        if (!sembrado || ocupado != null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                filas = filasDe(volumen, config())
                releerLlavero()
            } catch (e: Exception) {
                // Un config a medio escribir: la próxima vez.
                android.util.Log.w("Modelo", "Releyendo las filas", e)
            }
        }
    }

    /**
     * Pasa todas las parejas, una detrás de otra, como `run_all()`: un error de
     * una no se lleva las demás ([Pasada.pasarOFallar]), y las filas se
     * refrescan pase lo que pase.
     *
     * Con [solo], solo esa pareja («Sincronizar esta pareja», en su ficha), sin
     * el llavero: como `sync.py` con un nombre.
     */
    fun sincronizar(solo: String? = null) {
        trabajo(if (solo == null) "Sincronizando…" else "Sincronizando «$solo»…", parable = true) {
            val v = Prdrive.iniciarRclone(contexto)
            val config = config()
            val ctx = Pasada.Contexto(keepLogs = config.keepLogs, accesoCompartido = Prdrive.accesoCompartido())
            lineas = emptyList()
            // Una pareja a medio mover no se pasa con las demás: su carpeta
            // compartida puede estar a medio llenar, y una pasada normal vería
            // borrados y los llevaría al remoto. Solo la pasa «Terminar de mover».
            val entradas = Compartidas.leer(v.compartidas)
            val parejas = config.delUsuario.filter { solo == null || it.name == solo }
            val conLlavero = solo == null && config.parejaLlavero != null
            enCurso = EnCurso(parejas.map { it.name } + if (conLlavero) listOf(LLAVERO) else emptyList())
            try {
                for (p in parejas) {
                    if (parando) break
                    if (entradas[p.name]?.estado == Compartidas.Estado.ENTRANDO) {
                        decir("[${p.name}] A medio mover: se pasa con «Terminar de mover».")
                        enCurso = enCurso?.let { it.copy(hechas = it.hechas + (p.name to Pasada.Resultado(p.name, Pasada.SALTADA, emptyList()))) }
                        continue
                    }
                    val r = try {
                        Pasada.pasarOFallar(
                            v, p, Adaptadores.pasada, Adaptadores.opciones, ctx,
                            { progreso = Progreso.de(p.name, it) }, ::decir,
                        )
                    } catch (_: Rclone.Cancelada) {
                        // «Parar la pasada»: cortada, y no se apunta.
                        decir("[${p.name}] Parada: no se apunta, y la siguiente sigue donde se quedó.")
                        break
                    }
                    resultados = resultados + (p.name to r)
                    enCurso = enCurso?.let { it.copy(hechas = it.hechas + (p.name to r)) }
                }
                // El llavero, el último, como `sync.py` sin nombres: no sale
                // entre las parejas, pero «Sincronizar» lo pasa. No se corta: desde
                // aquí ya no se puede parar.
                val llavero = synchronized(cerrojoDeParar) {
                    if (parando || !conLlavero) {
                        false
                    } else {
                        enElLlavero = true
                        Rclone.olvidarParada(Thread.currentThread())
                        true
                    }
                }
                if (llavero) {
                    sePuedeParar = false
                    try {
                        pasarLlavero()
                    } finally {
                        enElLlavero = false
                    }
                }
            } finally {
                filas = filasDe(v, config)
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
     * 4. [porAbrir] con el `content://` de la base en [VolumenProvider] (y
     *    el de su fichero llave, si va sin contraseña), que la pantalla abre.
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
            fun documento(nombre: String) = DocumentsContract.buildDocumentUri(
                VolumenProvider.AUTORIDAD, "${VolumenProvider.ID_RAIZ}/$LLAVERO_LOCAL/$nombre",
            )
            // La llave, solo la de un llavero sin contraseña: con contraseña y
            // fichero llave, el teléfono no guarda ninguna (la pide la app de KeePass).
            val llave = if (pareja.llaveInterna && java.io.File(pareja.carpetaEn(v.raiz), LLAVERO_LLAVE).isFile) {
                documento(LLAVERO_LLAVE)
            } else {
                null
            }
            val apertura = Apertura(documento(base), llave)
            if (avisos.isEmpty()) {
                porAbrir = apertura
            } else {
                trasElAviso = apertura
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
                    Pasada.Contexto(
                        resyncAprobado = true, keepLogs = config.keepLogs, accesoCompartido = Prdrive.accesoCompartido(),
                    ),
                    { progreso = Progreso.de(p.name, it) }, ::decir,
                )
                resultados = resultados + (p.name to r)
            } finally {
                filas = filasDe(v, config)
            }
        }
    }

    // --- las carpetas compartidas ---------------------------------------------------

    /**
     * «Mover a una carpeta compartida…»: lleva la pareja [nombre] a la carpeta
     * [arbol] que ha elegido la persona ([Mudanza.entrar]).
     *
     * Sin el permiso de acceso a todos los archivos no se llama a [Mudanza]: la
     * pasada de después no vería la carpeta entera. Y un árbol que la app no sabe
     * traducir a una ruta (una SD, otro proveedor) se dice aquí, no se intenta.
     */
    fun moverACompartida(nombre: String, arbol: Uri) {
        if (ocupado != null) return
        if (!Prdrive.permisoCompartido()) {
            error = Prdrive.SIN_PERMISO_COMPARTIDO
            return
        }
        val carpeta = Compartir.rutaDeArbol(arbol)
        if (carpeta == null) {
            error = SIN_RUTA_COMPARTIDA
            return
        }
        mudanza("Moviendo $nombre a ${carpeta.name}…", nombre) { v, e -> Mudanza.entrar(v, nombre, carpeta, e) }
    }

    /** «Terminar de mover»: acaba la mudanza de [nombre] que quedó a medias ([Mudanza.terminar]). */
    fun terminarDeMover(nombre: String) {
        if (ocupado != null) return
        if (!Prdrive.permisoCompartido()) {
            error = Prdrive.SIN_PERMISO_COMPARTIDO
            return
        }
        mudanza("Terminando de mover $nombre…", nombre) { v, e -> Mudanza.terminar(v, nombre, e) }
    }

    /**
     * «Volver al volumen»: devuelve [nombre] al volumen ([Mudanza.salir]). Sin el
     * permiso se vuelve igual, sin la pasada de antes: la carpeta compartida no
     * se toca, y lo que no se subió se queda en ella.
     */
    fun volverAlVolumen(nombre: String) {
        if (ocupado != null) return
        mudanza("Devolviendo $nombre al volumen…", nombre) { v, e ->
            Mudanza.salir(v, nombre, Prdrive.permisoCompartido(), e)
        }
    }

    /**
     * Una mudanza ([hacer]) como trabajo de la pantalla y con el cerrojo del
     * llavero: reescribe el `rclone.conf` y recarga rclone, como activar o quitar
     * el llavero, y no puede hacerse con una pasada suya en marcha. Las pasadas
     * que ha hecho se apuntan como las demás ([resultados]); si se ha parado,
     * [error] dice por qué y qué hacer. Las filas se releen pase lo que pase.
     */
    private fun mudanza(que: String, nombre: String, hacer: (Volumen, Mudanza.Entorno) -> Mudanza.Hecho) {
        trabajo(que) {
            val v = Prdrive.iniciarRclone(contexto)
            lineas = emptyList()
            mudando = nombre
            try {
                val entorno = Compartir.entorno(contexto, config().keepLogs, { progreso = Progreso.de(nombre, it) }, ::decir)
                val hecho = LlaveroApp.enExclusiva { hacer(v, entorno) }
                for (r in hecho.pasadas) resultados = resultados + (r.pareja to r)
                error = hecho.motivo
            } finally {
                mudando = null
                filas = filasDe(v, config())
                releerLlavero()
            }
        }
    }

    companion object {
        const val MAX_LINEAS = 200

        /** Lo que se dice mientras rclone suelta la pareja que se ha parado. */
        const val PARANDO = "Parando: se espera a que rclone suelte la pareja…"

        /** Lo que se dice cuando la carpeta elegida no es una que la app pueda usar. */
        const val SIN_RUTA_COMPARTIDA =
            "Esa carpeta no sirve: tiene que estar en el almacenamiento interno del teléfono (dentro de " +
                "Documents, por ejemplo), no en una tarjeta SD, en Descargas ni en una nube, y no puede ser " +
                "el almacenamiento entero. Elige otra."
    }
}
