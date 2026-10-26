package prdrive.app.documentos

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.time.LocalDateTime
import prdrive.engine.Store

/**
 * Lecturas.kt — Qué versión de cada base del llavero tiene cada app de KeePass,
 * para que un guardado hecho sobre una versión vieja no pise la nueva.
 *
 * El caso que evita: la app de KeePass abre la base (versión A) y la tiene en
 * memoria; mientras, una pasada trae la versión B de otro dispositivo; la app
 * avisa de que el fichero ha cambiado, pero deja guardar encima. Ese guardado
 * es A más lo que se ha tocado aquí, y borraría lo que trajo B sin copia de
 * conflicto ni aviso: solo quedaría en `.prversions/`. KeePassDX 4.5.6 lo hace
 * así: detecta el cambio por la fecha y el tamaño (`checkDatabaseInfo` en
 * `DatabaseTaskNotificationService`), sin leer el fichero, y guardar es solo
 * escribir con `"wt"` (`SaveDatabaseRunnable`). Abrir, recargar y combinar sí
 * leen la base entera (`LoadDatabaseRunnable`, `ReloadDatabaseRunnable`,
 * `MergeDatabaseRunnable`).
 *
 * Así que el proveedor apunta, por app y por base, la huella (SHA-256) de lo
 * que le ha servido al leer ([apuntarLectura]) y de lo que ha escrito ella
 * misma ([apuntarEscritura]). Al cerrar un guardado ([destino]), si la base del
 * disco ya no es la que esa app conoce, el guardado no va encima: va a una
 * copia de conflicto ([nombreAparte]), con el nombre que reconocen la fila del
 * teléfono y «Combinar» en el PC (`conflicts.leer_nombre` de prdrive). Los
 * guardados viejos que siguen van a esa misma copia mientras nadie la toque.
 *
 * - Solo cuenta como lectura abrir con `"r"`. Un `"rw"` da una copia de la
 *   base, pero no se sabe si la app la lee: contarlo dejaría pasar un guardado
 *   viejo, y no contarlo, como mucho, aparta uno bueno (que no se pierde).
 * - Sin nada apuntado para esa app y esa base (abrió la base antes de esta
 *   versión de prdrive, o es la primera vez), el guardado va encima, como
 *   siempre.
 * - Se apunta en `state/` ([FICHERO]): es de este volumen, y si se rehace, se va
 *   con él. No viaja: `state/` es de `.prdrive/`. Si no se puede apuntar, el
 *   guardado siguiente va encima, como antes: falla abierto, porque fallar
 *   cerrado sería apartar todos los guardados.
 */
internal object Lecturas {

    /** Lo apuntado, en `state/`. */
    const val FICHERO = "llavero-lecturas.json"

    /**
     * El sufijo de la copia: el de path1 en el llavero (`conflict-suffix` de la
     * pareja, `Model.kt`), así que en el teléfono la fila la cuenta como de
     * este dispositivo.
     */
    const val SUFIJO = "conflicto-dispositivo"

    /** Lo apuntado se lee y se escribe de uno en uno: abren los hilos del binder y cierra el del proveedor. */
    private val cerrojo = Any()

    /**
     * Lo que se sabe de una app y una base.
     *
     * @property leida la huella de la versión que tiene la app: la última que
     *   leyó o que puso ella misma encima.
     * @property aparte la copia adonde han ido sus guardados viejos, si los hay.
     * @property escrita la huella de lo último que escribió en [aparte].
     */
    data class Conocida(val leida: String, val aparte: String? = null, val escrita: String? = null)

    /** La huella de [f], o `null` si no es un fichero que se pueda leer. */
    fun huella(f: File): String? = try {
        RandomAccessFile(f, "r").use { huella(it.channel) }
    } catch (_: IOException) {
        null
    }

    /**
     * La huella de lo que hay en [canal], con lecturas posicionales: no mueve
     * su posición, así que el descriptor se puede dar después tal cual.
     */
    fun huella(canal: FileChannel): String {
        val suma = MessageDigest.getInstance("SHA-256")
        val trozo = ByteBuffer.allocate(64 * 1024)
        var donde = 0L
        while (true) {
            trozo.clear()
            val leidos = canal.read(trozo, donde)
            if (leidos < 0) break
            trozo.flip()
            suma.update(trozo)
            donde += leidos
        }
        return suma.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /** Lo que se sabe de [app] y [base] (el nombre de la base en `.keychain/`). */
    fun conocida(state: File, app: String, base: String): Conocida? = synchronized(cerrojo) {
        @Suppress("UNCHECKED_CAST")
        val de = ((leer(state)[app] as? Map<String, Any?>)?.get(base) as? Map<String, Any?>) ?: return null
        val leida = de["leida"] as? String ?: return null
        Conocida(leida, de["aparte"] as? String, de["escrita"] as? String)
    }

    /** [app] ha leído [base] y tiene la versión de [huella]: lo de antes se olvida. */
    fun apuntarLectura(state: File, app: String, base: String, huella: String) =
        apuntar(state, app, base, Conocida(huella))

    /**
     * [app] ha guardado [base], lo guardado tiene la huella [huella] y ha ido a
     * [puesto]: si es la base, ya conoce lo que hay en ella; si es una copia
     * aparte, sigue con la versión que tenía, y sus guardados siguientes van a
     * esa copia.
     *
     * La huella es la de lo que se puso, no la de lo que hay ahora en
     * [puesto]: una pasada puede haberlo cambiado ya, y entonces se daría por
     * sabida una versión que la app no tiene.
     */
    fun apuntarEscritura(state: File, app: String, base: File, puesto: File, huella: String) {
        // Leer y escribir de una vez: una lectura de la misma app en medio no se pierde.
        synchronized(cerrojo) {
            if (puesto == base) {
                apuntar(state, app, base.name, Conocida(huella))
                return
            }
            val antes = conocida(state, app, base.name) ?: return
            apuntar(state, app, base.name, antes.copy(aparte = puesto.name, escrita = huella))
        }
    }

    /**
     * Adónde va un guardado de [base] que hace [app]: la base, si [app] tiene
     * la versión que hay en el disco (o no se sabe qué tiene); si no, la copia
     * aparte de sus guardados viejos, si sigue como la dejó, o una nueva.
     */
    fun destino(state: File, app: String?, base: File, ahora: () -> LocalDateTime = LocalDateTime::now): File {
        if (app == null) return base
        val sabida = conocida(state, app, base.name) ?: return base
        // Sin base (se ha borrado en otro dispositivo) o con el marcador vacío
        // de `createDocument`, no hay versión que pisar.
        if (!base.isFile || base.length() == 0L) return base
        val actual = huella(base) ?: return base
        if (actual == sabida.leida) return base
        val carpeta = base.parentFile ?: return base
        sabida.aparte?.let { File(carpeta, it) }
            ?.takeIf { it.isFile && sabida.escrita != null && huella(it) == sabida.escrita }
            ?.let { return it }
        return nuevaCopia(carpeta, base.name, ahora())
    }

    /**
     * El nombre de una copia aparte de [base] hecha en [cuando]:
     * `personal.conflicto-dispositivo20261026153012.kdbx`, la forma de los
     * perdedores de bisync en el llavero (`--conflict-suffix` de path1 y
     * `--suffix-keep-extension`, que lleva por tener versiones), con la fecha y
     * la hora como número. Con el primer número libre, como el `copia_propia()`
     * de prdrive, dos dispositivos que apartan a la vez sacarían los dos un `1`,
     * y bisync haría una copia de la copia, que «Combinar» no ofrece.
     *
     * Las cifras se escriben a mano: `String.format` usa las del idioma del
     * teléfono.
     */
    fun nombreAparte(base: String, cuando: LocalDateTime): String {
        val punto = base.lastIndexOf('.')
        val (raiz, ext) = if (punto > 0) base.substring(0, punto) to base.substring(punto) else base to ""
        fun dos(n: Int) = n.toString().padStart(2, '0')
        val numero = cuando.year.toString().padStart(4, '0') + dos(cuando.monthValue) + dos(cuando.dayOfMonth) +
            dos(cuando.hour) + dos(cuando.minute) + dos(cuando.second)
        return "$raiz.$SUFIJO$numero$ext"
    }

    /** Una copia aparte que no existe: si la de este segundo ya está, la del siguiente. */
    private fun nuevaCopia(carpeta: File, base: String, cuando: LocalDateTime): File {
        var t = cuando
        while (true) {
            val copia = File(carpeta, nombreAparte(base, t))
            if (!copia.exists()) return copia
            t = t.plusSeconds(1)
        }
    }

    private fun apuntar(state: File, app: String, base: String, que: Conocida) = synchronized(cerrojo) {
        val todo = leer(state).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val deLaApp = ((todo[app] as? Map<String, Any?>) ?: emptyMap()).toMutableMap()
        deLaApp[base] = buildMap {
            put("leida", que.leida)
            que.aparte?.let { put("aparte", it) }
            que.escrita?.let { put("escrita", it) }
        }
        todo[app] = deLaApp
        state.mkdirs()
        // Si no se puede apuntar, ese guardado irá encima como antes de esto:
        // no se tumba una lectura ni un guardado por ello.
        if (!Store.writeJson(File(state, FICHERO), todo)) Log.w("Lecturas", "No se ha podido apuntar en $FICHERO")
    }

    private fun leer(state: File): Map<String, Any?> = Store.readJson(File(state, FICHERO))
}
