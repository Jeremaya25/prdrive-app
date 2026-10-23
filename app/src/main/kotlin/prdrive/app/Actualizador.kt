package prdrive.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import prdrive.engine.Actualizacion
import prdrive.engine.Store

/**
 * Actualizador.kt — La app se pone al día sola desde las releases de GitHub.
 *
 * Qué hay y si es más nuevo lo dice el motor ([Actualizacion], espejo de
 * `common/update.py`); aquí está lo que es de Android: bajar el APK, comprobar
 * que es de esta app, y dárselo a instalar a `PackageInstaller`.
 *
 * Cuándo:
 * - Al abrir la app se pregunta a GitHub, como mucho una vez al día (la caché
 *   de `update.py`).
 * - Si hay versión nueva, se baja sola, salvo con una conexión de pago (los
 *   datos móviles: son unos 50 MB) o en una compilación de depuración, que va
 *   firmada con otra clave y no podría instalarla encima. Ahí se ofrece con un
 *   botón.
 * - Se instala al salir de la app, sin preguntar, cuando Android lo deja: desde
 *   Android 12, si la persona ha permitido una vez que prdrive instale apps
 *   (`setRequireUserAction` y `UPDATE_PACKAGES_WITHOUT_USER_ACTION`). Al salir,
 *   porque instalarse cierra la app, y delante de la persona sería cerrársela en
 *   la cara. Y nunca con algo en marcha ([empezarTrabajo]): una pasada cortada a
 *   medias deja el lock de bisync puesto.
 * - Si Android pide confirmación (antes de Android 12, o sin ese permiso), la
 *   versión nueva espera a que la persona toque «Instalar».
 * - Con Play Protect, el primer intento sin preguntar de cada release no suele
 *   salir: no verifica en segundo plano un APK que no ha visto. Se intenta otra
 *   vez al salir la próxima ([fallosSinPreguntar]), y mientras, el botón.
 *
 * Antes de instalar se comprueba el tamaño y la suma SHA-256 que da GitHub, y
 * que el APK sea de esta app, de la versión que dice la release, más nuevo
 * (`versionCode`) y con la misma firma. Android vuelve a mirar las dos últimas y
 * es quien manda: esto es para decir por qué no, antes de pedirle nada a nadie.
 *
 * Todo va por un solo hilo, el suyo ([ambito]): buscar, bajar e instalar no se
 * cruzan. El estado son `mutableStateOf`, que la pantalla lee tal cual.
 */
object Actualizador {

    enum class Fase { QUIETA, BUSCANDO, DESCARGANDO, LISTA, INSTALANDO, CONFIRMAR }

    /** Por dónde va. */
    var fase by mutableStateOf(Fase.QUIETA)
        private set

    /** La release más nueva que la instalada, si se sabe de una. */
    var nueva by mutableStateOf<Actualizacion.Release?>(null)
        private set

    /** Los bytes del APK que ya están aquí, mientras se baja. */
    var bajado by mutableStateOf(0L)
        private set

    /** Por qué [nueva] no se baja o no se instala sola, si es el caso. */
    var espera by mutableStateOf<String?>(null)
        private set

    /** Lo último que hay que contarle a la persona: lo que contesta «buscar», un fallo, que ya se ha actualizado. */
    var aviso by mutableStateOf<String?>(null)
        private set

    /** La pantalla de Android que pide confirmar la instalación, mientras espera. */
    @Volatile
    private var confirmacion: Intent? = null

    /** Si la app está a la vista (entre `onStart` y `onStop`). */
    @Volatile
    private var visible = false

    /**
     * Los intentos sin preguntar que no han salido en este proceso. Con
     * [MAX_SIN_PREGUNTAR] ya no se intenta al salir, y queda el botón, con la
     * app delante.
     *
     * Hay dos razones para que no salga, y no se tratan igual:
     * - Android pide confirmación aun con el permiso dado: lo va a seguir
     *   pidiendo, y se para a la primera.
     * - Play Protect no deja instalar en segundo plano un APK que aún no ha
     *   visto («Install not allowed», al medio minuto), pero ese intento hace
     *   que lo mire, y el siguiente pasa. Cada release es un APK que no ha visto
     *   nunca: por eso hay un segundo intento, la próxima vez que se sale.
     *
     * Sin el permiso, que pregunte es lo esperado y no cuenta. Es estado de la
     * pantalla: la tarjeta cambia de «se instalará sola» a «instálala tú».
     */
    var fallosSinPreguntar by mutableIntStateOf(0)
        private set

    private const val MAX_SIN_PREGUNTAR = 2

    /** Si la instalación en curso se pidió con el permiso dado, esperando que no se preguntara. */
    @Volatile
    private var esperabaSilencio = false

    /** La sesión de `PackageInstaller` cuya respuesta se espera; `-1` si ninguna. */
    @Volatile
    private var sesion = -1

    /** Solo se toca desde [ambito]. */
    private var arrancado = false

    private val cerrojo = Any()
    private var trabajos = 0
    private var instalando = false

    private val ambito = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { Thread(it, "prdrive-actualizador") }.asCoroutineDispatcher(),
    )

    // --- lo que llama la app -------------------------------------------------------

    /** Al abrirse la app (`onStart`): pregunta, si toca, y sigue donde se quedó. */
    fun alAbrir(contexto: Context) {
        visible = true
        enSuHilo(contexto) {
            arrancar(it)
            // Una instalación cuya sesión ya no existe no va a contestar, y no puede dejar la app sin
            // poder sincronizar. Si la respuesta llega después, se atiende igual.
            if (fase == Fase.INSTALANDO && it.packageManager.packageInstaller.getSessionInfo(sesion) == null) {
                soltar()
                fase = Fase.LISTA
            }
            buscar(it, forzar = false, contar = false)
        }
    }

    /** Al salir de la app (`onStop`, sin contar los giros de pantalla): es cuando se instala sola. */
    fun alSalir(contexto: Context) {
        visible = false
        enSuHilo(contexto) { if (fase == Fase.LISTA && sePuedeSola(it)) instalar(it) }
    }

    /** «Buscar actualizaciones»: sin caché, y diciendo qué ha contestado GitHub. */
    fun buscarAhora(contexto: Context) = enSuHilo(contexto) { buscar(it, forzar = true, contar = true) }

    /** «Descargar»: la versión nueva aunque no se fuera a bajar sola. */
    fun descargarAhora(contexto: Context) = enSuHilo(contexto) { app -> nueva?.let { preparar(app, it, aMano = true) } }

    /** «Instalar»: ahora, con la app delante. Se cierra al instalarse. */
    fun instalarAhora(contexto: Context) {
        val confirmar = confirmacion
        if (fase == Fase.CONFIRMAR && confirmar != null) {
            contexto.startActivity(confirmar)
            return
        }
        enSuHilo(contexto) { if (fase == Fase.LISTA) instalar(it) }
    }

    /**
     * Antes de empezar algo que no se puede cortar (una pasada, el
     * emparejamiento). `false` si ya se está instalando la versión nueva: la app
     * se va a cerrar en un momento.
     */
    fun empezarTrabajo(): Boolean = synchronized(cerrojo) {
        if (instalando) return false
        trabajos++
        true
    }

    /** Al acabar lo que empezó [empezarTrabajo]. Si la app está fuera de la vista, es el momento de instalar. */
    fun acabarTrabajo(contexto: Context) {
        val libre = synchronized(cerrojo) {
            trabajos--
            trabajos == 0
        }
        if (libre) enSuHilo(contexto) { if (fase == Fase.LISTA && sePuedeSola(it)) instalar(it) }
    }

    /** Si la versión nueva se instalará sola al salir: desde Android 12, con el permiso de instalar apps. */
    fun seInstalaSola(contexto: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && contexto.packageManager.canRequestPackageInstalls() &&
            fallosSinPreguntar < MAX_SIN_PREGUNTAR

    /** Si falta el permiso que hace que se instale sola. Antes de Android 12 no hay manera: siempre se pregunta. */
    fun faltaPermiso(contexto: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && !contexto.packageManager.canRequestPackageInstalls()

    /** La versión instalada, la del `VERSION` con el que se compiló. */
    fun versionInstalada(contexto: Context): String = instalada(contexto).versionName.orEmpty()

    // --- dónde vive cada cosa ------------------------------------------------------

    /** Fuera del volumen: nada de esto es del disco de prdrive, ni lo lee el escritorio. */
    private fun carpeta(c: Context) = File(c.noBackupFilesDir, "actualizacion")
    private fun cache(c: Context) = File(carpeta(c), "update.json")

    /** La versión que no se pudo instalar y por qué, para no bajarla otra vez cada día. */
    private fun rechazo(c: Context) = File(carpeta(c), "rechazada.json")

    /** La versión que se le dio a instalar a Android: al volver a abrir, dice si se instaló. */
    private fun marca(c: Context) = File(carpeta(c), "instalando")

    /** El APK, en la caché: si el sistema la vacía, se vuelve a bajar. */
    private fun descargas(c: Context) = File(c.cacheDir, "actualizacion")
    private fun apkDe(c: Context, version: String) = File(descargas(c), Actualizacion.nombreApk(version))

    // --- el hilo del actualizador ---------------------------------------------------

    /** Ejecuta [bloque] en el hilo del actualizador. Es el borde: un fallo se cuenta, no tumba la app. */
    private fun enSuHilo(contexto: Context, bloque: (Context) -> Unit) {
        val app = contexto.applicationContext
        ambito.launch {
            try {
                bloque(app)
            } catch (e: Exception) {
                aviso = "La actualización ha fallado: ${e.message ?: e}"
                if (fase == Fase.BUSCANDO || fase == Fase.DESCARGANDO) fase = Fase.QUIETA
            }
        }
    }

    /** Lo primero de cada proceso: recoger lo que dejó el anterior. */
    private fun arrancar(c: Context) {
        if (arrancado) return
        arrancado = true
        // Una sesión de instalación de otro proceso no la va a terminar nadie.
        val instalador = c.packageManager.packageInstaller
        for (s in instalador.mySessions) {
            try {
                instalador.abandonSession(s.sessionId)
            } catch (_: SecurityException) {
                // Ya no es nuestra: nada que hacer.
            }
        }
        val actual = versionInstalada(c)
        val m = marca(c)
        if (m.isFile) {
            val v = m.readText().trim()
            if (v.isNotEmpty() && v == actual) aviso = "prdrive se ha actualizado a la versión $v."
            m.delete()
        }
        // Lo bajado que ya no es más nuevo que lo instalado (o que ya se instaló) sobra.
        for (f in descargas(c).listFiles().orEmpty()) {
            val v = f.name.removePrefix("prdrive-").removeSuffix(".part").removeSuffix(".apk")
            if (!Actualizacion.isNewer(v, actual)) f.delete()
        }
    }

    private fun buscar(c: Context, forzar: Boolean, contar: Boolean) {
        // Con la instalación en manos de Android (o de la persona), nada que buscar.
        if (fase == Fase.INSTALANDO || fase == Fase.CONFIRMAR) return
        if (contar) {
            fase = Fase.BUSCANDO
            aviso = null
        }
        val (rel, motivo) = Actualizacion.check(cache(c), red, forzar)
        val actual = versionInstalada(c)
        if (contar) aviso = Actualizacion.veredicto(rel, motivo, actual)
        val n = rel?.takeIf { Actualizacion.isNewer(it.version, actual) }
        nueva = n
        if (n == null) {
            fase = Fase.QUIETA
            espera = null
            return
        }
        preparar(c, n, aMano = false)
    }

    /** Deja la versión nueva bajada y comprobada ([Fase.LISTA]), si se puede, y la instala si es el momento. */
    private fun preparar(c: Context, rel: Actualizacion.Release, aMano: Boolean) {
        val apk = rel.apk
        if (apk == null) {
            fase = Fase.QUIETA
            espera = "La ${rel.tag} no trae el APK para Android: bájalo a mano de su página."
            return
        }
        val fichero = apkDe(c, rel.version)
        if (!fichero.isFile) {
            val porQueNo = if (aMano) null else porQueNoSeBaja(c, rel, apk)
            if (porQueNo != null) {
                fase = Fase.QUIETA
                espera = porQueNo
                return
            }
            espera = null
            try {
                descargar(c, apk, fichero)
            } catch (e: IOException) {
                fase = Fase.QUIETA
                espera = "No se ha podido bajar la ${rel.tag}: ${e.message ?: e}. Se volverá a intentar al abrir la app."
                return
            }
        }
        val problema = problemaDelApk(c, fichero, rel.version)
        if (problema != null) {
            fichero.delete()
            carpeta(c).mkdirs()
            Store.writeJson(rechazo(c), linkedMapOf("version" to rel.version, "motivo" to problema))
            fase = Fase.QUIETA
            espera = "La ${rel.tag} no se puede instalar encima de esta: $problema"
            return
        }
        fase = Fase.LISTA
        espera = null
        if (sePuedeSola(c)) instalar(c)
    }

    private fun porQueNoSeBaja(c: Context, rel: Actualizacion.Release, apk: Actualizacion.Apk): String? {
        val rechazada = Store.readJson(rechazo(c))
        if (rechazada["version"] == rel.version) {
            return "La ${rel.tag} no se puede instalar encima de esta: ${rechazada["motivo"]}"
        }
        if (depuracion(c)) {
            return "Esta es una compilación de depuración, firmada con otra clave que la de las releases: " +
                "la ${rel.tag} no se baja sola, porque no se instalaría encima."
        }
        val red = c.getSystemService(ConnectivityManager::class.java)
        if (red == null || red.isActiveNetworkMetered) {
            return "Pesa ${megas(apk.tamano)} y la conexión es de pago (datos móviles): se bajará sola con wifi."
        }
        return null
    }

    private fun depuracion(c: Context): Boolean = (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Megas redondeados, sin `String.format` (que usaría las cifras del idioma del teléfono). */
    private fun megas(bytes: Long): String = "${(bytes + 512 * 1024) / (1024 * 1024)} MB"

    /** Si se puede instalar ya, sin preguntar a nadie y sin cortar nada. */
    private fun sePuedeSola(c: Context): Boolean =
        !visible && seInstalaSola(c) && synchronized(cerrojo) { trabajos == 0 } && Prdrive.volumen(c).sembrado

    // --- la red ----------------------------------------------------------------------

    /** El `fetch` de `update.py`: la única petición a la API, que contesta unos pocos KB. */
    private val red = Actualizacion.Fetch { url, timeout ->
        val con = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            con.connectTimeout = timeout * 1000
            con.readTimeout = timeout * 1000
            con.setRequestProperty("User-Agent", Actualizacion.USER_AGENT)
            con.setRequestProperty("Accept", "application/vnd.github+json")
            val codigo = con.responseCode
            if (codigo != HttpURLConnection.HTTP_OK) throw IOException("GitHub ha contestado $codigo")
            con.inputStream.use { leerHasta(it, 1 shl 20) }
        } finally {
            con.disconnect()
        }
    }

    private fun leerHasta(entrada: InputStream, maximo: Int): ByteArray {
        val salida = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = entrada.read(buf)
            if (n < 0) return salida.toByteArray()
            if (salida.size() + n > maximo) throw IOException("la respuesta de GitHub es demasiado grande")
            salida.write(buf, 0, n)
        }
    }

    /**
     * Baja el APK a `<nombre>.part` y lo renombra solo si pesa lo que dice
     * GitHub y su suma cuadra. Un `.part` de una vez anterior se continúa
     * (`Range`), que en el móvil la red se corta.
     */
    private fun descargar(c: Context, apk: Actualizacion.Apk, fichero: File) {
        fase = Fase.DESCARGANDO
        fichero.parentFile?.mkdirs()
        val parte = File(fichero.path + ".part")
        var desde = if (parte.isFile && parte.length() < apk.tamano) parte.length() else 0L
        if (desde == 0L) parte.delete()
        bajado = desde
        val con = URI(apk.url).toURL().openConnection() as HttpURLConnection
        try {
            con.connectTimeout = Actualizacion.TIMEOUT_API * 1000
            con.readTimeout = Actualizacion.TIMEOUT_DESCARGA * 1000
            con.setRequestProperty("User-Agent", Actualizacion.USER_AGENT)
            if (desde > 0) con.setRequestProperty("Range", "bytes=$desde-")
            when (val codigo = con.responseCode) {
                // Sin `Range`, o si el servidor no lo atiende: desde el principio.
                HttpURLConnection.HTTP_OK -> desde = 0L
                HttpURLConnection.HTTP_PARTIAL -> Unit
                else -> {
                    if (codigo == 416) parte.delete()
                    throw IOException("GitHub ha contestado $codigo")
                }
            }
            FileOutputStream(parte, desde > 0).use { salida ->
                con.inputStream.use { entrada ->
                    copiar(entrada, salida, apk.tamano - desde) { bajado = desde + it }
                }
                salida.fd.sync()
            }
        } finally {
            con.disconnect()
        }
        if (parte.length() != apk.tamano) {
            throw IOException("se han bajado ${parte.length()} bytes de ${apk.tamano}")
        }
        val esperada = apk.sha256
        if (esperada != null && sha256(parte) != esperada) {
            parte.delete()
            throw IOException("lo bajado no es el APK de la release (su suma SHA-256 no cuadra)")
        }
        if (!parte.renameTo(fichero)) throw IOException("no se ha podido guardar el APK")
    }

    private fun copiar(entrada: InputStream, salida: FileOutputStream, maximo: Long, avance: (Long) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        var contado = 0L
        while (true) {
            val n = entrada.read(buf)
            if (n < 0) break
            total += n
            if (total > maximo) throw IOException("GitHub manda más de lo que dice que pesa el APK")
            salida.write(buf, 0, n)
            if (total - contado >= 512 * 1024) {
                contado = total
                avance(total)
            }
        }
        avance(total)
    }

    private fun sha256(f: File): String {
        val suma = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { entrada ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = entrada.read(buf)
                if (n < 0) break
                suma.update(buf, 0, n)
            }
        }
        return hex(suma.digest())
    }

    /** Hexadecimal en minúsculas, sin `String.format`. */
    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    // --- el APK ------------------------------------------------------------------------

    /**
     * Qué impide instalar [f] encima de esta app, en una frase, o `null` si nada.
     * Como `_verificar` de `update.py`: que sea esta app y la versión pedida; y
     * además lo que Android exige para ponerla encima, más nueva y con la misma
     * firma.
     */
    internal fun problemaDelApk(c: Context, f: File, version: String): String? {
        val suya = leerApk(c.packageManager, f) ?: return "Android no lo reconoce como una app."
        if (suya.packageName != c.packageName) return "es de otra app (${suya.packageName})."
        if (suya.versionName != version) return "dice ser la versión ${suya.versionName}, y la release es la $version."
        val propia = instalada(c)
        val nuevo = PackageInfoCompat.getLongVersionCode(suya)
        val viejo = PackageInfoCompat.getLongVersionCode(propia)
        if (nuevo <= viejo) return "no es más nueva que la instalada (versionCode $nuevo, y la instalada tiene $viejo)."
        val nuevas = firmas(suya)
        val propias = firmas(propia)
        if (nuevas.isNotEmpty() && propias.isNotEmpty() && nuevas.intersect(propias).isEmpty()) {
            return "está firmada con otra clave, y Android solo pone una versión encima de otra con la misma " +
                "firma. Habría que desinstalar esta, y eso borra el volumen."
        }
        return null
    }

    private val FLAGS_FIRMA: Int
        @Suppress("DEPRECATION")
        get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    /** Esta app, con sus firmas. */
    @Suppress("DEPRECATION")
    internal fun instalada(c: Context): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            c.packageManager.getPackageInfo(c.packageName, PackageManager.PackageInfoFlags.of(FLAGS_FIRMA.toLong()))
        } else {
            c.packageManager.getPackageInfo(c.packageName, FLAGS_FIRMA)
        }

    /** Lo que dice un APK de sí mismo, con sus firmas; `null` si no es un APK. */
    @Suppress("DEPRECATION")
    internal fun leerApk(pm: PackageManager, f: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(f.path, PackageManager.PackageInfoFlags.of(FLAGS_FIRMA.toLong()))
        } else {
            pm.getPackageArchiveInfo(f.path, FLAGS_FIRMA)
        }

    /**
     * Las huellas SHA-256 de los certificados de un paquete: el de ahora y los
     * que lo precedieron, si la clave se rotó. Dos paquetes que comparten alguno
     * son de la misma mano; Android decide después si la rotación vale.
     */
    @Suppress("DEPRECATION")
    internal fun firmas(info: PackageInfo): Set<String> {
        val certificados = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo
            when {
                si == null -> info.signatures
                si.hasMultipleSigners() -> si.apkContentsSigners
                else -> si.signingCertificateHistory
            }
        } else {
            info.signatures
        }
        return certificados.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    // --- la instalación ----------------------------------------------------------------

    /**
     * Antes de dárselo a `PackageInstaller`: `false` con algo en marcha. Desde
     * aquí hasta [soltar], [empezarTrabajo] se niega. `internal` para que las
     * pruebas pongan el actualizador «instalando» sin instalar nada.
     */
    internal fun reservar(): Boolean = synchronized(cerrojo) {
        if (trabajos > 0) return false
        instalando = true
        true
    }

    internal fun soltar() = synchronized(cerrojo) { instalando = false }

    /** Cuántos [empezarTrabajo] siguen sin su [acabarTrabajo]. Solo lo miran las pruebas. */
    internal fun trabajosEnMarcha(): Int = synchronized(cerrojo) { trabajos }

    /**
     * Le da el APK a `PackageInstaller`. Pide que no se pregunte
     * (`USER_ACTION_NOT_REQUIRED`): si Android lo deja, la app se cierra al
     * acabar y la próxima vez que se abra ya es la nueva; si no, contesta que
     * hace falta la persona ([resultado]).
     */
    private fun instalar(c: Context) {
        val rel = nueva ?: return
        val f = apkDe(c, rel.version)
        if (!f.isFile) {
            fase = Fase.QUIETA
            return
        }
        if (!reservar()) {
            aviso = "La versión nueva se instalará cuando acabe lo que está en marcha."
            return
        }
        val instalador = c.packageManager.packageInstaller
        var id = -1
        try {
            fase = Fase.INSTALANDO
            val parametros = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(c.packageName)
                setSize(f.length())
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            id = instalador.createSession(parametros)
            instalador.openSession(id).use { sesion ->
                sesion.openWrite(f.name, 0, f.length()).use { salida ->
                    f.inputStream().use { it.copyTo(salida) }
                    sesion.fsync(salida)
                }
                carpeta(c).mkdirs()
                Store.writeText(marca(c), rel.version)
                esperabaSilencio = seInstalaSola(c)
                this.sesion = id
                sesion.commit(receptor(c, id))
            }
        } catch (e: Exception) {
            sesion = -1
            if (id >= 0) {
                try {
                    instalador.abandonSession(id)
                } catch (_: Exception) {
                    // Si no se puede abandonar, la recoge el próximo arranque.
                }
            }
            marca(c).delete()
            soltar()
            fase = Fase.LISTA
            aviso = "No se ha podido instalar la ${rel.tag}: ${e.message ?: e}"
        }
    }

    private fun receptor(c: Context, id: Int): IntentSender {
        // Mutable porque Android le añade el resultado; explícito, así que nadie más lo recibe.
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(c, ResultadoDeInstalacion::class.java)
        return PendingIntent.getBroadcast(c, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable).intentSender
    }

    /** Lo que contesta Android a [instalar], por [ResultadoDeInstalacion]. */
    internal fun resultado(c: Context, intent: Intent) {
        // Solo cuenta la sesión en curso. Las de otro proceso, que `arrancar` abandona, contestan
        // también («Session was abandoned»), y a veces cuando ya hay otra en marcha.
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sesion) return
        val estado = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val mensaje = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val cual = nueva?.tag?.let { "la $it" } ?: "la versión nueva"
        soltar()
        // Un intento sin preguntar que no ha salido cuenta ([fallosSinPreguntar]); si Android ha pedido
        // confirmación, la va a seguir pidiendo, y no se vuelve a intentar.
        if (esperabaSilencio && estado != PackageInstaller.STATUS_SUCCESS) {
            fallosSinPreguntar = if (estado == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                MAX_SIN_PREGUNTAR
            } else {
                fallosSinPreguntar + 1
            }
        }
        when (estado) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android quiere que la persona diga que sí. Con la app delante se le pregunta ya; si no,
                // espera a que toque «Instalar».
                val confirmar = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                confirmacion = confirmar
                fase = if (confirmar != null) Fase.CONFIRMAR else Fase.LISTA
                if (visible && confirmar != null) {
                    c.startActivity(Intent(confirmar).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            // Al ponerse al día a sí misma, Android cierra la app antes de que llegue esto; si llega, es
            // al proceso nuevo, y el aviso bueno lo da `arrancar` con la marca.
            PackageInstaller.STATUS_SUCCESS -> {
                confirmacion = null
                nueva = null
                fase = Fase.QUIETA
                aviso = "prdrive se ha actualizado."
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                confirmacion = null
                marca(c).delete()
                fase = Fase.LISTA
                // Con la persona delante, lo aborta ella; sin preguntar, lo ha parado otro, y se dice quién.
                aviso = if (esperabaSilencio) {
                    "No se ha podido instalar $cual sin preguntar: ${mensaje ?: "Android no dice por qué"}. " +
                        "Puedes instalarla desde aquí."
                } else {
                    "Se ha cancelado la instalación de $cual. Puedes instalarla desde aquí cuando quieras."
                }
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                // Otra firma, o algo de este teléfono que la versión nueva no admite: no se va a arreglar
                // volviendo a intentarlo.
                confirmacion = null
                marca(c).delete()
                val rel = nueva
                if (rel != null) {
                    apkDe(c, rel.version).delete()
                    Store.writeJson(rechazo(c), linkedMapOf("version" to rel.version, "motivo" to (mensaje ?: "Android no la admite.")))
                }
                fase = Fase.QUIETA
                espera = "Android no deja instalar $cual encima de esta: ${mensaje ?: "no dice por qué"}."
            }
            else -> {
                confirmacion = null
                marca(c).delete()
                fase = Fase.LISTA
                aviso = "No se ha podido instalar $cual: ${mensaje ?: "Android no dice por qué"}."
            }
        }
    }
}

/** Recibe lo que contesta `PackageInstaller` a una instalación del [Actualizador]. Solo lo llama Android. */
class ResultadoDeInstalacion : BroadcastReceiver() {
    override fun onReceive(contexto: Context, intent: Intent) {
        Actualizador.resultado(contexto.applicationContext, intent)
    }
}
