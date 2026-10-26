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
 * Y lo de `install/profile.py` que trata la conexión al llegar al dispositivo:
 * el `rclone.conf` que se escribe (`render_conf()`), y lo que el catálogo
 * decide de ella (`align_with_catalog()`, con `es_secreta()` y `faltan()`).
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
    /** El de fábrica, el mismo que el de [Config]: `pairing.py` lo toma de `catalog.py`. */
    const val DEFAULT_CATALOG_PATH = prdrive.engine.DEFAULT_CATALOG_PATH

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
            // Solo la línea: el mensaje del lector cita el valor que no ha
            // podido leer, y en esta carga ese valor puede ser la clave privada.
            // (El de tomllib no cita valores; el de aquí sí.)
            val linea = Regex("^Línea ([0-9]+)").find(e.message ?: "")?.groupValues?.get(1)
            throw PairingError(
                "El código no lleva un TOML válido" + (linea?.let { " (falla la línea $it)" } ?: "") +
                    ". Si lo has pegado, comprueba que está entero.",
            )
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
        for (cruda in splitlinesPy(texto)) {
            val linea = stripPy(cruda)
            if (linea.isEmpty() || linea.startsWith("#") || linea.startsWith(";")) continue
            if (linea.startsWith("[") && linea.endsWith("]")) {
                val nueva = LinkedHashMap<String, String>()
                remotes[stripPy(linea.substring(1, linea.length - 1))] = nueva
                actual = nueva
                continue
            }
            val corte = linea.indexOf('=')
            if (actual == null || corte < 0) continue
            actual[stripPy(linea.substring(0, corte))] = stripPy(linea.substring(corte + 1))
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
     */
    fun rcloneConf(carga: Carga, seccionCombine: String = ""): String {
        if (carga.remoteName.isEmpty()) {
            throw PairingError("La carga no dice cómo se llama el remote.")
        }
        // `render_conf()` de install/profile.py: un nombre o un valor que el
        // formato no sabe escribir metería en el rclone.conf una opción (u otro
        // remote) que nadie tecleó.
        if (!NOMBRE_RCLONE.matches(carga.remoteName)) {
            throw PairingError(
                "El nombre de remote ${reprPy(carga.remoteName)} no vale en un " +
                    "rclone.conf: no admite , : = [ ] ni comillas ni saltos de línea, " +
                    "ni empezar por «-» ni acabar en espacio.",
            )
        }
        val ejecutan = carga.opciones.keys.filter { it in OPCIONES_QUE_EJECUTAN }
        if (ejecutan.isNotEmpty()) {
            // En el escritorio, la persona puede teclearlas en su equipo; en el
            // teléfono no hay programa que lanzar, y rclone fallaría al conectar.
            throw PairingError(
                "La conexión usa ${ejecutan.joinToString(", ") { "'$it'" }}, que hace que rclone " +
                    "lance un programa del ordenador; en el teléfono no lo hay. Quítalo del " +
                    "rclone.conf del dispositivo que enseña el código.",
            )
        }
        val lineas = ArrayList<String>()
        lineas.add("[${carga.remoteName}]")
        carga.opciones.forEach { (k, v) -> lineas.add(linea(k, v)) }
        if (carga.privateKey != null) {
            lineas.add(linea("key_file", "keys/${nombreDeClave(carga.keyName)}"))
            // Sin known_hosts se acepta la clave de host a la primera (TOFU). Es
            // peor, pero escribir la opción apuntando a un fichero vacío es peor
            // todavía: rclone falla en vez de avisar.
            if (carga.knownHosts.isNotBlank()) lineas.add(linea("known_hosts_file", "keys/known_hosts"))
        }
        val texto = lineas.joinToString("\n") + "\n"
        return if (seccionCombine.isEmpty()) texto else texto + "\n" + seccionCombine
    }

    /**
     * El remote que se escribe en el teléfono, según lo que diga el catálogo
     * (`profile.align_with_catalog()`). La conexión se teclea una vez y el
     * catálogo la reparte, con una regla que manda sobre la comodidad: **el
     * nombre lo decide el catálogo**. Los `remote_path` de todas las parejas se
     * resuelven contra él, y si el `rclone.conf` del teléfono lo llamara de otra
     * forma, cada pasada fallaría con un «unknown remote» que no se parece a la
     * causa. Es el de `[remote].name` o, si no, el de `[defaults].remote`.
     *
     * Las opciones del backend son las del código, salvo que el catálogo traiga
     * un `[remote]` completo (el que dejó el primer dispositivo): entonces es la
     * definición buena, menos lo que ejecuta algo o es secreto, que no se hereda
     * ([opcionesDelCatalogo]). La clave privada y los known_hosts no son
     * opciones: viajan con el QR y nunca con el catálogo.
     *
     * **Diferencia deliberada con prdrive: solo del mismo tipo.** prdrive adopta
     * el `type` del `[remote]` aunque no sea el de la conexión que se acaba de
     * comprobar, y el teléfono no: el catálogo lo escribe cualquiera con acceso
     * al remoto, y con `type = "local"` las parejas apuntarían al almacenamiento
     * interno de la app (una pareja hacia `.prdrive/keys` dejaría la clave privada
     * en una carpeta que se ve en la app de Archivos, y un espejo de subida
     * vaciaría lo de dentro); con `alias` o un `webdav` sin contraseña, a otro
     * sitio. Un cambio de tipo de verdad pediría credenciales nuevas, que el
     * catálogo no lleva, así que no se pierde nada. Se compara sin espacios
     * alrededor, como `faltan()`. Apuntado en PLAN.md §2 para arreglarlo en
     * prdrive.
     *
     * @return la carga ajustada y las notas de lo que se ha cambiado o dejado de
     *   heredar (ninguna si todo coincidía), para poder decirlo.
     */
    fun alinearConCatalogo(carga: Carga, catalogoRaw: Map<String, Any?>): kotlin.Pair<Carga, List<String>> {
        val notas = ArrayList<String>()
        val tabla = tablaDe(catalogoRaw, "remote") ?: emptyMap()
        val defaults = tablaDe(catalogoRaw, "defaults") ?: emptyMap()
        val elegido = listOf(tabla["name"], defaults["remote"]).firstOrNull { verdad(it) } ?: ""
        val nombre = stripPy(strPy(elegido))
        var ajustada = carga
        if (nombre.isNotEmpty() && nombre != carga.remoteName) {
            ajustada = ajustada.copy(remoteName = nombre)
            notas.add(
                "En el dispositivo el remote se llamará '$nombre' y no '${carga.remoteName}': es el " +
                    "nombre que usan los remote_path del catálogo.",
            )
        }
        val (delCatalogo, deOpciones) = opcionesDelCatalogo(carga.opciones, tabla)
        val propio = carga.opciones["type"] ?: ""
        val opciones = delCatalogo?.takeIf { stripPy(it.getValue("type")) == stripPy(propio) }
        if (opciones != null) {
            // Se adoptan aunque sean las mismas: el orden de las líneas del
            // rclone.conf es entonces el del catálogo, como en el escritorio.
            if (ajustada.opciones != opciones) notas.add(NOTA_OPCIONES_DEL_CATALOGO)
            ajustada = ajustada.copy(opciones = opciones)
        }
        notas.addAll(deOpciones)
        if (delCatalogo != null && opciones == null) notas.add(notaOtroTipo(delCatalogo.getValue("type"), propio))
        return ajustada to notas
    }

    /** La nota de `align_with_catalog()` cuando las opciones del backend salen del catálogo. */
    const val NOTA_OPCIONES_DEL_CATALOGO =
        "Las opciones del backend salen del [remote] del catálogo, que es el que comparten todos los " +
            "dispositivos."

    /**
     * La nota del teléfono cuando no adopta un `[remote]` de otro tipo que el del
     * código. Es suya, no de prdrive (que lo adopta): ver [alinearConCatalogo].
     */
    fun notaOtroTipo(delCatalogo: String, propio: String): String =
        "El [remote] del catálogo es de tipo '${stripPy(delCatalogo)}' y no '${stripPy(propio)}', que es " +
            "el de la conexión del código: este teléfono se queda con la que se acaba de comprobar, " +
            "porque el catálogo no puede cambiar el tipo de conexión."

    /**
     * `_opciones_del_catalogo()` de install/profile.py: las opciones del backend
     * que se heredan del `[remote]` del catálogo.
     *
     * El catálogo lo escribe cualquiera con acceso al remoto y lo leen todos los
     * dispositivos, así que de él no se hereda lo que ejecuta algo
     * ([OPCIONES_QUE_EJECUTAN]) ni lo secreto ([esSecreta]). Y una conexión que
     * lleva algo secreto o una orden se queda como está: es la que se acaba de
     * comprobar al leer el catálogo, y sus secretos no se mezclan con el destino
     * que describa el catálogo, que nadie ha comprobado.
     *
     * @param propias Las opciones de la carga del QR.
     * @param tabla El `[remote]` del catálogo.
     * @return las opciones que valen, o `null` si no hay nada que adoptar (el
     *   `[remote]` no trae `type`; la carga lleva algo secreto o una orden; o sin
     *   lo que no se hereda le falta algo que rclone exige, [faltan]); y las
     *   notas de lo que no se ha heredado o de por qué no se usa.
     */
    fun opcionesDelCatalogo(
        propias: Map<String, String>,
        tabla: Map<String, Any?>,
    ): kotlin.Pair<Map<String, String>?, List<String>> {
        val ejecutan = LinkedHashMap<String, String>()
        val secretas = ArrayList<String>()
        val heredadas = LinkedHashMap<String, String>()
        for ((clave, valor) in tabla) {
            if (clave == "name" || clave in RUTAS_DERIVADAS) continue
            when {
                clave in OPCIONES_QUE_EJECUTAN -> ejecutan[clave] = textoRclone(valor)
                esSecreta(clave) -> secretas.add(clave)
                else -> heredadas[clave] = textoRclone(valor)
            }
        }
        // Una tabla incompleta no pisa lo que ya funciona.
        if (heredadas["type"].isNullOrEmpty()) return null to emptyList()

        val notas = ArrayList<String>()
        if (ejecutan.isNotEmpty()) {
            val uno = ejecutan.size == 1
            notas.add(
                "El catálogo traía ${enumerar(ejecutan.keys)}: " +
                    (if (uno) "no se hereda, porque es una orden" else "no se heredan, porque son órdenes") +
                    " de este equipo.",
            )
        }
        if (secretas.isNotEmpty()) {
            val uno = secretas.size == 1
            notas.add(
                "El catálogo traía ${enumerar(secretas)}: " +
                    (if (uno) "no se hereda" else "no se heredan") +
                    ", porque el catálogo no guarda secretos. " +
                    (if (uno) "Se puede quitar" else "Se pueden quitar") +
                    " de su [remote].",
            )
        }

        val propiasSensibles = propias.keys.filter { it in OPCIONES_QUE_EJECUTAN || esSecreta(it) }.toSet()
        if (propiasSensibles.isNotEmpty()) {
            if (heredadas != propias.filterKeys { it !in propiasSensibles }) {
                notas.add(
                    "El [remote] del catálogo describe otra conexión: este dispositivo se queda con " +
                        "la que se acaba de comprobar, porque sus contraseñas, claves y órdenes no se " +
                        "dan a una conexión que no se ha comprobado.",
                )
            }
            return null to notas
        }

        val falta = faltan(heredadas)
        if (falta.isNotEmpty()) {
            // Solo se atribuye a lo quitado si con ello el [remote] sí valdría.
            val sin = if (ejecutan.isNotEmpty() && faltan(heredadas + ejecutan).isEmpty()) {
                "sin ${enumerar(ejecutan.keys)}, " +
                    (if (ejecutan.size == 1) "que no se hereda" else "que no se heredan") + ", "
            } else {
                ""
            }
            notas.add(
                "No se usa el [remote] del catálogo: " + sin + "le falta " +
                    falta.joinToString(" y ") { "«$it = …»" } + ".",
            )
            return null to notas
        }
        return heredadas to notas
    }

    /**
     * `OBLIGATORIAS` de install/profile.py: por tipo de backend, lo que no puede
     * faltar (lo que rclone marca `Required` y no admite otra salida). Allí va
     * con para qué sirve cada una, que es la frase de un error del asistente; el
     * teléfono no la usa.
     */
    val OBLIGATORIAS: Map<String, List<String>> = mapOf("sftp" to listOf("host"), "webdav" to listOf("url"))

    /**
     * `faltan()` de install/profile.py: las opciones obligatorias de ese backend
     * que no están o están en blanco. Un sftp con `ssh` (el ssh externo) no
     * necesita `host`.
     */
    fun faltan(opciones: Map<String, String>): List<String> {
        val tipo = stripPy(opciones["type"] ?: "")
        if (tipo == "sftp" && stripPy(opciones["ssh"] ?: "").isNotEmpty()) return emptyList()
        return OBLIGATORIAS[tipo].orEmpty().filter { stripPy(opciones[it] ?: "").isEmpty() }
    }

    /** Los nombres que son un secreto tal cual (`_SECRETAS_EXACTAS`). */
    private val SECRETAS_EXACTAS = setOf("key", "pass", "password", "2fa")

    /** Los trozos que delatan un secreto en cualquier parte del nombre (`_SECRETAS_CONTIENEN`). */
    private val SECRETAS_CONTIENEN = listOf(
        "pass", "token", "secret", "credential", "_pem", "sas_url", "account_key",
        "access_grant", "connection_string", "cookie", "authorization", "headers",
    )

    /**
     * `es_secreta()` de install/profile.py: si una opción de rclone lleva un
     * secreto, por su nombre (ni se interpreta el backend ni se mira el valor:
     * unas credenciales dentro de una `url` no se detectan). Peca de más a
     * propósito. Es secreto un nombre que:
     * - sea exactamente `key`, `pass`, `password` o `2fa`;
     * - contenga uno de [SECRETAS_CONTIENEN];
     * - acabe en `_key` o lleve `_key_` en medio (`api_key`,
     *   `sse_customer_key_base64`), salvo si acaba en `_key_id`, que es un
     *   identificador (`access_key_id`).
     *
     * Lo último es `_key(_|$)` en Python, escrito sin expresión regular: el `$`
     * de Java no es el de Python (AGENTS.md, «Ojo con Android»), y tras el
     * `strip()` las dos formas dicen lo mismo.
     */
    fun esSecreta(clave: String): Boolean {
        val nombre = stripPy(clave).lowercase()
        return nombre in SECRETAS_EXACTAS ||
            SECRETAS_CONTIENEN.any { it in nombre } ||
            ((nombre.endsWith("_key") || "_key_" in nombre) && !nombre.endsWith("_key_id"))
    }

    /**
     * `_texto_rclone()`: un valor del catálogo como lo escribe rclone en su conf.
     * TOML trae booleanos de verdad, y `str(True)` da «True»: el mismo valor
     * parecería otra conexión y llevaría una mayúscula que rclone no lee como
     * booleano.
     */
    private fun textoRclone(valor: Any?): String =
        if (valor is Boolean) (if (valor) "true" else "false") else strPy(valor)

    /** `_enumerar()`: las claves entre comillas inversas, «`a`, `b` y `c`», ordenadas. */
    private fun enumerar(claves: Iterable<String>): String {
        val nombres = claves.sortedWith(::compararPy).map { "`$it`" }
        if (nombres.size == 1) return nombres[0]
        return nombres.dropLast(1).joinToString(", ") + " y " + nombres.last()
    }

    /** `NOMBRE_RCLONE` de install/profile.py: la misma regla que [problemaRemote]. */
    private val NOMBRE_RCLONE get() = NOMBRE_REMOTE

    /** Si un nombre de remote se puede escribir como cabecera de un `rclone.conf`. */
    fun nombreValido(nombre: String): Boolean = NOMBRE_RCLONE.matches(nombre)

    /** Los nombres de opción que se escriben en un `rclone.conf`. */
    private val CLAVE_VALIDA = Regex("[A-Za-z0-9_]+")

    /** Lo que parte una línea al leer un `rclone.conf` (`str.splitlines()`). */
    private val SALTO_DE_LINEA = Regex("[\\r\\n\\u000b\\u000c\\u001c-\\u001e\\u0085\\u2028\\u2029]")

    /** Opciones de rclone cuyo valor es una orden que se ejecuta (`OPCIONES_QUE_EJECUTAN`). */
    val OPCIONES_QUE_EJECUTAN: Set<String> = setOf("bearer_token_command", "ssh")

    /** `_linea()` de install/profile.py: `clave = valor`, o por qué no se puede escribir. */
    fun linea(clave: String, valor: String): String {
        if (!CLAVE_VALIDA.matches(clave)) {
            throw PairingError(
                "La opción ${reprPy(clave)} no vale en un rclone.conf: solo letras, " +
                    "números y guión bajo.",
            )
        }
        if (SALTO_DE_LINEA.containsMatchIn(valor)) {
            throw PairingError(
                "El valor de '$clave' lleva un salto de línea y un rclone.conf no " +
                    "tiene cómo escribirlo.",
            )
        }
        return "$clave = $valor"
    }

    /**
     * El nombre del fichero de la clave tal como llega en el QR. En el
     * escritorio sale siempre de `Path.name`; aquí se comprueba, porque con él
     * se escribe en `keys/` y un `../` escribiría fuera.
     */
    fun nombreDeClave(nombre: String): String {
        if (nombre.isEmpty() || nombre == "." || nombre == ".." || nombre.any { it in "/\\" || it.code < 32 }) {
            throw PairingError("El nombre de la clave (${reprPy(nombre)}) no es un nombre de fichero.")
        }
        return nombre
    }
}
