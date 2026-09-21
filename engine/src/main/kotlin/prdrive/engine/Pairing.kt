package prdrive.engine

import java.util.Base64

/**
 * Pairing.kt — La conexión que llega leyendo el QR que enseña un dispositivo.
 *
 * Es la otra mitad de `common/pairing.py` de prdrive: allí se **construye** la
 * carga útil leyendo el `rclone.conf` del dispositivo, y aquí se **lee**. El
 * `leer()` de Python existe precisamente como definición ejecutable del
 * formato —lo que este fichero tiene que reproducir— y es lo que permite probar
 * la vuelta entera sin cámara; `tests/test_qr.py` del otro repo la da.
 *
 * **El formato es el de `install/profile.py`, a propósito.** La carga es el TOML
 * que escribe `profile.dumps()` con tres claves más —la marca, la clave privada
 * en base64 y los known_hosts—, y como esas tres le sobran a `profile.loads()`,
 * el MISMO texto se le puede pasar tal cual. No se inventa un formato nuevo
 * porque ya hay uno que sabe leer el instalador, y dos formatos para la misma
 * cosa acabarían separándose.
 *
 * **Todas las claves sueltas van antes de `[options]`**, y eso no es estética:
 * en TOML lo que viene después de una cabecera de tabla pertenece a esa tabla,
 * así que una sola línea traspapelada metería la clave privada dentro de las
 * opciones del backend y de ahí acabaría en el rclone.conf del móvil. Al leer,
 * la consecuencia es que [opciones] sale de `[options]` y de ningún otro sitio.
 *
 * **Esto lleva una clave privada dentro.** No es un token y no caduca: quien
 * fotografíe la pantalla del dispositivo se lleva el remoto. Quien lo enseña
 * avisa, y quien lo recibe —la app— no lo guarda en ningún sitio que no sea el
 * `keys/` del volumen.
 */
object Pairing {

    /**
     * La marca y la versión del formato. Van dentro para poder rechazar de plano
     * un QR que no es nuestro: una cámara apuntando a una pantalla lee lo que le
     * pongan delante, y «este código no es de prdrive» es un mensaje mucho mejor
     * que el error que daría intentar conectar con una URL.
     */
    const val MARCA = "prdrive"
    const val FORMATO = 1L

    /**
     * Las dos opciones que no viajan: son rutas del disco de quien las escribió,
     * y en el aparato que las recibe valen otra cosa. Misma lista que
     * `profile.RUTAS_DERIVADAS`.
     */
    val RUTAS_DERIVADAS = listOf("key_file", "known_hosts_file")

    const val DEFAULT_KEY_NAME = "id_ed25519"
    const val DEFAULT_CATALOG_PATH = "/prdrive-catalog/pairs.toml"

    /** No se puede leer la carga, o no es de prdrive. */
    class PairingError(mensaje: String) : Exception(mensaje)

    /**
     * Lo que lleva un QR de emparejamiento, ya separado en sus piezas.
     *
     * [texto] es la carga entera y sin tocar. Se guarda porque es lo que
     * `profile.loads()` recibe en el lado Python, y tenerlo aquí es lo que
     * permite comprobar contra el otro repo que los dos leen lo mismo.
     */
    data class Carga(
        val texto: String,
        val remoteName: String,
        val keyName: String,
        val catalogPath: String,
        val opciones: Map<String, String>,
        val privateKey: ByteArray?,
        val knownHosts: String,
    ) {
        /** ¿Hay al menos un remote con tipo? Es lo mínimo para intentar conectar. */
        val configurado: Boolean get() = remoteName.isNotEmpty() && !opciones["type"].isNullOrEmpty()

        /** ¿El backend se autentica con un fichero de clave? */
        val necesitaClave: Boolean get() = privateKey != null

        /**
         * Una línea para la pantalla: el backend y adónde apunta. Nunca la clave.
         */
        fun describe(): String {
            val tipo = opciones["type"] ?: "?"
            val destino = opciones["host"] ?: opciones["url"] ?: ""
            val usuario = opciones["user"] ?: ""
            val cola = if (usuario.isNotEmpty() && destino.isNotEmpty()) {
                " $usuario@$destino"
            } else {
                " $destino"
            }
            return "$remoteName ($tipo)$cola".trimEnd()
        }

        // `privateKey` es un ByteArray, así que equals/hashCode de data class
        // comparan la referencia. Se escriben a mano para que dos cargas leídas
        // del mismo texto sean iguales, que es lo que esperan los tests.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Carga) return false
            return texto == other.texto &&
                remoteName == other.remoteName &&
                keyName == other.keyName &&
                catalogPath == other.catalogPath &&
                opciones == other.opciones &&
                knownHosts == other.knownHosts &&
                privateKey.contentEquals(other.privateKey)
        }

        override fun hashCode(): Int = texto.hashCode()
    }

    /**
     * Comprobar que el código es nuestro y separar lo que lleva dentro.
     *
     * Los tres rechazos son deliberados y cada uno da su mensaje: no es TOML,
     * no es de prdrive, o es de otra versión del formato. Un QR de otra cosa
     * —una URL, una wifi, un billete— cae en el primero o el segundo.
     */
    fun leer(texto: String): Carga {
        val raw = try {
            loadRaw(texto)
        } catch (e: TomlError) {
            throw PairingError("El código no lleva un TOML válido: ${e.message}")
        }

        val version = raw[MARCA] ?: throw PairingError("Este código no es de prdrive.")
        if (version != FORMATO) {
            throw PairingError(
                "Este código es del formato $version y aquí se entiende el $FORMATO: " +
                    "actualiza el aparato más antiguo de los dos.",
            )
        }

        val b64 = raw["private_key_b64"]?.toString() ?: ""
        val clave: ByteArray? = if (b64.isEmpty()) {
            null
        } else {
            try {
                Base64.getDecoder().decode(b64)
            } catch (e: IllegalArgumentException) {
                throw PairingError("La clave del código no es base64 válido: ${e.message}")
            }
        }

        // Las rutas derivadas se caen aquí aunque el emisor las mandara: apuntan
        // al disco del que las escribió, y la app escribe las suyas propias
        // (`keys/…`, relativas a la carpeta de la app) al montar su rclone.conf.
        val opciones = (tablaDe(raw, "options") ?: emptyMap())
            .filterKeys { it !in RUTAS_DERIVADAS }
            .mapValues { (_, v) -> v.toString() }

        return Carga(
            texto = texto,
            remoteName = raw["remote_name"]?.toString() ?: "",
            keyName = raw["key_name"]?.toString()?.takeIf { it.isNotEmpty() } ?: DEFAULT_KEY_NAME,
            catalogPath = raw["catalog_path"]?.toString()?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_CATALOG_PATH,
            opciones = opciones,
            privateKey = clave,
            knownHosts = raw["known_hosts"]?.toString() ?: "",
        )
    }

    /**
     * `{nombre: {opción: valor}}` de un rclone.conf.
     *
     * A mano y no con un parser de .ini porque rclone escribe algún valor con
     * `%` dentro (los `%` de las plantillas de nombre) y un parser con
     * interpolación revienta. El formato que hace falta entender es una cabecera
     * entre corchetes y `clave = valor`; nada más. Espejo de
     * `pairing.parse_rclone_conf()`, que en el otro repo es el ÚNICO lector de
     * rclone.conf que hay.
     */
    fun parseRcloneConf(texto: String): Map<String, Map<String, String>> {
        val remotes = LinkedHashMap<String, MutableMap<String, String>>()
        var actual: MutableMap<String, String>? = null
        for (cruda in texto.lines()) {
            val linea = cruda.trim()
            if (linea.isEmpty() || linea.startsWith("#") || linea.startsWith(";")) continue
            if (linea.startsWith("[") && linea.endsWith("]")) {
                val nueva = LinkedHashMap<String, String>()
                remotes[linea.substring(1, linea.length - 1).trim()] = nueva
                actual = nueva
                continue
            }
            val corte = linea.indexOf('=')
            if (actual == null || corte < 0) continue
            actual[linea.substring(0, corte).trim()] = linea.substring(corte + 1).trim()
        }
        return remotes
    }

    /**
     * El `rclone.conf` que la app escribe en su volumen a partir de una carga.
     *
     * Las dos rutas van **relativas** (`keys/…`), que es lo que hace portable al
     * dispositivo en el escritorio y lo que aquí resuelve un problema distinto y
     * peor: la ruta absoluta de `filesDir` lleva dentro el id de usuario de
     * Android y cambia si la app se reinstala o se mueve a otro perfil. rclone
     * las resuelve contra su directorio de trabajo, así que la app tiene que
     * ejecutar rclone con el suyo puesto en la carpeta de la aplicación, igual
     * que hace el escritorio.
     *
     * [seccionCombine] es la sección `[disp]` que en el escritorio viaja por
     * variables de entorno; se pasa hecha ([Config.seccionCombine]) porque
     * depende de las parejas y no de la conexión.
     *
     * [dirDeClaves] es el prefijo de `key_file` y `known_hosts_file`. Por
     * defecto es `keys`, **relativo**, que es lo que escribe el escritorio y
     * lo que hace portable un disco: allí rclone corre con
     * `cwd = APP_DIR`. En la app no hay proceso al que fijarle un cwd —rclone
     * es una biblioteca dentro de ella—, así que [Volumen] pasa la ruta
     * absoluta y se encarga de reescribir el fichero cuando la raíz cambia.
     */
    fun rcloneConf(
        carga: Carga,
        seccionCombine: String = "",
        dirDeClaves: String = "keys",
    ): String {
        if (carga.remoteName.isEmpty()) {
            throw PairingError("La carga no dice cómo se llama el remote.")
        }
        val lineas = ArrayList<String>()
        lineas.add("[${carga.remoteName}]")
        carga.opciones.forEach { (k, v) -> lineas.add("$k = $v") }
        val claves = dirDeClaves.trimEnd('/')
        if (carga.privateKey != null) lineas.add("key_file = $claves/${carga.keyName}")
        // Sin known_hosts se acepta la clave de host a la primera (TOFU). Es
        // peor, pero escribir la opción apuntando a un fichero vacío es peor
        // todavía: rclone falla en vez de avisar.
        if (carga.knownHosts.isNotBlank()) lineas.add("known_hosts_file = $claves/known_hosts")
        val texto = lineas.joinToString("\n") + "\n"
        return if (seccionCombine.isEmpty()) texto else texto + "\n" + seccionCombine
    }
}
