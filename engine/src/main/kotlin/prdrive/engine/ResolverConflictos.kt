package prdrive.engine

import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * ResolverConflictos.kt — Resolver un conflicto de bisync en este dispositivo,
 * quedándose con una de sus versiones.
 *
 * Espejo de la parte de `ui/conflict_editor.py` de prdrive que decide y ejecuta
 * (el módulo no dibuja nada: «la ventana pide un plan, enseña sus consecuencias
 * y solo si la persona dice que sí lo ejecuta»). Resolver es siempre LOCAL: no se
 * habla con el remoto. Basta con dejar en el teléfono un solo fichero con el
 * nombre de verdad, y la siguiente pasada de bisync lleva el resultado al otro
 * lado (el fichero que cambia se sube, las copias que desaparecen se borran allí
 * también). Qué hace «quedarse con la versión de este dispositivo» depende de dónde
 * esté esa versión: si perdió el teléfono, su versión ES la copia y hay que
 * devolverle el nombre; si ganó, es el original y lo que sobra es la copia.
 * [Conflictos] ya sabe cuál es cuál; esto solo pregunta.
 *
 * **Lo que no está, y por qué: «Combinar».** En el escritorio el llavero tiene una
 * salida más (`puede_combinar`, `en_versiones`, `no_combinado`, `CombinarPlan`,
 * `plan_combinar`, y las frases `ESPERANDO_CONSOLA` y las de la consola de
 * KeePassXC), que junta la base del llavero con sus copias de conflicto llamando a
 * `keepassxc-cli`. El teléfono no tiene `keepassxc-cli` ni dónde pedirle la
 * contraseña: las copias del llavero se combinan en el PC («Abrir llavero» →
 * «Combinar») o a mano en la app de KeePass (AGENTS.md, «El llavero»). Aquí solo
 * queda elegir una versión. Por eso [PIERDE_LLAVERO] (el aviso de que elegir en el
 * llavero pierde lo que se guardó en la otra), que es literal de prdrive y nombra
 * «Combinar», sigue diciéndolo: la pantalla, que es del teléfono, tiene que
 * cambiar ese texto como cambia `Pasada.EXPLICACION_LLAVERO`.
 *
 * Lo que no es de Python, y por qué:
 *
 * - **[Entorno].** `huella()` lee la hora de un fichero con `Path.stat()`, y
 *   `mover()`, `borrar()` y `fecha()` son funciones de módulo (los tests de
 *   prdrive las sustituyen para hacer fallar un borrado). En el motor, lo que
 *   toca el disco o el reloj se pasa: la hora con ns de un fichero no sale de
 *   java.nio en Android hasta el 15 ([Llavero.horaJvm] lo cuenta) y la app le da
 *   `Prdrive.hora`; y la zona de [fecha] es la del teléfono *ahora*, que puede
 *   cambiar con la app viva.
 * - **Fechas y tamaños como texto** sin `String.format`, que usaría las cifras
 *   del idioma del teléfono (en árabe saldría `٠١/٠٩`): cada número se escribe
 *   a mano. [fecha] sigue el redondeo a microsegundos de
 *   `datetime.fromtimestamp()` (importa en el cambio de minuto) y [tamano] el
 *   `{:.1f}` de Python, que redondea el valor EXACTO del `double` y a igual
 *   distancia el par.
 * - **Los fallos del sistema.** `except OSError as e` y `{e}` de Python dicen el
 *   texto del sistema operativo; aquí es el de java.nio (la razón, si la hay), y
 *   se parece menos. Lo que esos mensajes llevan delante y detrás es lo mismo.
 *   Un [mover] y un [borrar] reales, además: `os.replace` se hace con un
 *   `ATOMIC_MOVE` (que en Linux y en Android es el `rename(2)` y sustituye al
 *   destino), y `Path.unlink()` no borra carpetas, aunque `Files.delete` sí las
 *   vacías: [borrar] las rechaza.
 * - **Los nombres de `ResolvePlan`.** `consequences`, `warnings` y `execute()` se
 *   quedan con el nombre de Python, que es el del contrato de `EditPlan`: es lo
 *   que la pantalla pinta antes de confirmar y lo que lanza tras confirmar.
 *
 * Quien lo llame tiene que tener en cuenta que `execute()` es una operación
 * larga de disco (renombrar y borrar) y que no hay ningún cerrojo: que no corra
 * una pasada de esa pareja mientras tanto, como no corre con una mudanza
 * ([Mudanza]) ni con activar el llavero.
 */
object ResolverConflictos {

    /** Cómo se llama para la persona la versión de cada lado. */
    val ETIQUETA_LADO: Map<String, String> = linkedMapOf(
        Conflictos.DISPOSITIVO to "versión de este dispositivo",
        Conflictos.REMOTO to "versión del remoto",
    )

    /** Nombre de la versión con el nombre de verdad cuando no se sabe de qué lado viene. */
    const val ETIQUETA_ORIGINAL = "versión con su nombre"

    /** Nombre de una copia de conflicto cuando no se sabe de qué lado viene. */
    const val ETIQUETA_COPIA = "otra versión"

    /** Lo que se dice siempre al resolver: solo se toca este dispositivo. */
    const val NOTA_LOCAL = "Solo se tocan ficheros de este dispositivo. La próxima sincronización " +
        "lleva el resultado al remoto y borra allí las copias."

    /**
     * El aviso de «quedarse con…» en el llavero. Literal de prdrive: nombra
     * «Combinar», que el teléfono no tiene (ver arriba).
     */
    const val PIERDE_LLAVERO = "Es la base del llavero: lo que se guardó en la versión que se descarta " +
        "se pierde. «Combinar» junta las dos sin perder nada."

    /**
     * El plan no se puede pensar o ejecutar. El mensaje es para la persona.
     */
    class ResolucionImposible(mensaje: String, causa: Throwable? = null) : Exception(mensaje, causa)

    /**
     * Lo que `conflict_editor.py` toma del sistema y aquí se pasa: cómo se lee la
     * hora de un fichero, cómo se mueve y se borra uno, y en qué zona se escribe
     * una fecha.
     *
     * @property hora La hora de modificación en ns (el `st_mtime_ns`), que lee
     *   una compilación de Android con `Prdrive.hora`. Puede lanzar [IOException].
     * @property mover Pone `origen` encima de `destino` de una vez (por defecto,
     *   [mover]). Lanza [IOException] si no puede.
     * @property borrar Borra un fichero (por defecto, [borrar]). Lanza
     *   [NoSuchFileException] si ya no estaba y [IOException] si no puede.
     * @property zona La zona en la que se escriben las fechas, mirada cada vez.
     */
    class Entorno(
        val hora: (File) -> Long = Llavero::horaJvm,
        val mover: (File, File) -> Unit = ResolverConflictos::mover,
        val borrar: (File) -> Unit = ResolverConflictos::borrar,
        val zona: () -> ZoneId = { ZoneId.systemDefault() },
    )

    /**
     * Mueve un fichero sobre otro y vuelve cuando está hecho.
     *
     * Es un renombrado atómico y no una copia seguida de un borrado: sustituye al
     * destino si existe y lo hace de una vez. O la versión elegida queda con su
     * nombre y la anterior se va, o no ha cambiado nada; no hay un momento
     * intermedio sin fichero.
     *
     * @throws IOException Si no se puede (también si el sistema de ficheros no
     *   sabe hacerlo atómico: no hay un plan B que no sea una copia).
     */
    fun mover(origen: File, destino: File) {
        Files.move(origen.toPath(), destino.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Borra un fichero.
     *
     * @throws NoSuchFileException Si no estaba.
     * @throws IOException Si no se puede; también si es una carpeta, que
     *   `Path.unlink()` de Python no borra.
     */
    fun borrar(ruta: File) {
        val camino = ruta.toPath()
        if (Files.isDirectory(camino, LinkOption.NOFOLLOW_LINKS)) {
            throw FileSystemException(ruta.path, null, "es una carpeta")
        }
        Files.delete(camino)
    }

    /**
     * Cada versión con su nombre para la persona, en el orden del conflicto.
     *
     * Nunca el sufijo: `.conflicto-remoto1` es cosa de rclone. Si dos versiones
     * se llaman igual (dos conflictos seguidos que perdió el mismo lado) se
     * numeran en el orden en que rclone las fue apartando.
     */
    fun etiquetas(conflicto: Conflictos.Conflicto): List<Pair<Conflictos.Version, String>> {
        fun base(v: Conflictos.Version): String {
            val lado = v.lado
            if (lado != null && lado in ETIQUETA_LADO) return ETIQUETA_LADO.getValue(lado)
            return if (v.esOriginal) ETIQUETA_ORIGINAL else ETIQUETA_COPIA
        }

        val nombres = conflicto.versiones.map { base(it) }
        val salida = ArrayList<Pair<Conflictos.Version, String>>()
        val vistos = HashMap<String, Int>()
        for ((i, v) in conflicto.versiones.withIndex()) {
            var nombre = nombres[i]
            if (nombres.count { it == nombre } > 1) {
                val n = (vistos[nombre] ?: 0) + 1
                vistos[nombre] = n
                nombre = "$nombre ($n)"
            }
            salida.add(Pair(v, nombre))
        }
        return salida
    }

    /**
     * El nombre para la persona de esa versión del conflicto.
     *
     * @throws NoSuchElementException Si la versión no es de este conflicto (el
     *   `KeyError` de Python).
     */
    fun etiqueta(conflicto: Conflictos.Conflicto, version: Conflictos.Version): String =
        etiquetas(conflicto).associate { it.first.ruta to it.second }.getValue(version.ruta)

    /**
     * `(tamaño, fecha en ns)` del fichero, o `null` si no está (o no se puede
     * mirar): lo que se compara para saber que no ha cambiado.
     */
    fun huella(ruta: File, entorno: Entorno = Entorno()): Pair<Long, Long>? = try {
        Pair(Files.size(ruta.toPath()), entorno.hora(ruta))
    } catch (_: IOException) {
        null
    } catch (_: InvalidPathException) {
        null
    }

    /**
     * Un tamaño en octetos como texto («12,3 KB»): una cifra decimal, con coma,
     * salvo los octetos, que van enteros.
     */
    fun tamano(octetos: Long): String {
        var valor = octetos.toDouble()
        var unidad = "B"
        for (u in listOf("B", "KB", "MB", "GB")) {
            unidad = u
            if (valor < 1024 || u == "GB") break
            valor /= 1024
        }
        // El valor exacto del double, como el `{:.1f}` de Python (que no pasa por
        // `x * 10`): a igual distancia, el par.
        val texto = if (unidad == "B") {
            BigDecimal(valor).setScale(0, RoundingMode.HALF_EVEN).toPlainString()
        } else {
            BigDecimal(valor).setScale(1, RoundingMode.HALF_EVEN).toPlainString().replace('.', ',')
        }
        return "$texto $unidad"
    }

    /**
     * Una fecha en nanosegundos como texto («10/09/2026 18:20»), en la hora de
     * la [zona].
     *
     * Es `datetime.fromtimestamp(ns / 1e9)`: los segundos son un `double`, y
     * Python los redondea a microsegundos (a igual distancia, el par) antes de
     * mirar el minuto. Un fichero a menos de medio microsegundo del siguiente
     * minuto sale con el minuto siguiente.
     */
    fun fecha(ns: Long, zona: ZoneId = ZoneId.systemDefault()): String {
        val t = ns.toDouble() / 1e9
        val entera = if (t < 0) Math.ceil(t) else Math.floor(t) // el `modf` de C
        val micros = Math.rint((t - entera) * 1e6)
        val segundos = entera.toLong() + when {
            micros >= 1e6 -> 1L
            micros < 0 -> -1L
            else -> 0L
        }
        val f = LocalDateTime.ofInstant(Instant.ofEpochSecond(segundos), zona)
        return "${dos(f.dayOfMonth)}/${dos(f.monthValue)}/${f.year.toString().padStart(4, '0')} " +
            "${dos(f.hour)}:${dos(f.minute)}"
    }

    /** Un número de dos cifras, con ceros delante; `Int.toString()` no usa las cifras del idioma. */
    private fun dos(n: Int): String = n.toString().padStart(2, '0')

    /**
     * Lo que hace falta para elegir: «12,3 KB · 10/09/2026 18:20», o «ya no
     * está».
     */
    fun describir(ruta: File, entorno: Entorno = Entorno()): String {
        val dato = huella(ruta, entorno) ?: return "ya no está"
        return "${tamano(dato.first)} · ${fecha(dato.second, entorno.zona())}"
    }

    /**
     * El texto de un fallo del sistema. java.nio no trae uno legible: de un
     * `NoSuchFileException` o un `AccessDeniedException` el mensaje es la ruta
     * (que el texto ya dice por delante), así que se dice qué pasó; del resto, la
     * razón si la hay, o lo que haya.
     */
    private fun textoDe(e: IOException): String = when (e) {
        is NoSuchFileException -> "el fichero no existe"
        is AccessDeniedException -> "sin permiso"
        is FileSystemException -> e.reason ?: e.javaClass.simpleName
        else -> e.message ?: e.javaClass.simpleName
    }

    /**
     * Qué versión se queda con el nombre de verdad y qué se borra.
     *
     * [consequences] y [warnings] se enseñan antes de confirmar; [execute] solo se
     * llama si la persona dice que sí.
     *
     * @property conflicto El conflicto que se resuelve.
     * @property conserva La versión que se queda con el nombre de verdad.
     * @property descartar Las copias que se borran. El original descartado no
     *   está: lo sustituye [mover] de una vez.
     * @property huellas Tamaño y fecha de cada fichero cuando se pensó el plan
     *   (`null`: no estaba), en el orden del conflicto.
     * @property consequences Una línea por consecuencia.
     * @property warnings Lo que conviene saber antes de confirmar.
     */
    class ResolvePlan(
        val conflicto: Conflictos.Conflicto,
        val conserva: Conflictos.Version,
        val descartar: List<File>,
        val huellas: Map<File, Pair<Long, Long>?>,
        val consequences: List<String>,
        val warnings: List<String>,
        private val entorno: Entorno = Entorno(),
    ) {
        /**
         * Pone la versión elegida en su sitio, borra lo demás y devuelve qué ha
         * hecho.
         *
         * No hace falta deshacer nada, y es por el orden. Lo primero que cambia
         * el disco es [mover], que es atómico: si falla, no ha cambiado nada.
         * Después solo quedan borrados de versiones que se han decidido
         * descartar; si alguno falla, la elegida ya está en su sitio y lo que no
         * se ha podido borrar sigue teniendo su nombre de conflicto, así que
         * vuelve a salir en la lista. El único estado a medias posible es «falta
         * por borrar una copia», que no pierde nada y se arregla repitiendo.
         *
         * Antes de tocar nada se comprueba que ningún fichero haya cambiado
         * desde que se pensó el plan: lo que se confirmó eran esos tamaños y
         * esas fechas, no los de ahora.
         *
         * @throws ResolucionImposible Si algo ha cambiado o no se ha podido
         *   mover o borrar.
         */
        fun execute(): List<String> {
            for ((ruta, antes) in huellas) {
                if (huella(ruta, entorno) != antes) {
                    throw ResolucionImposible(
                        "«${ruta.name}» ha cambiado desde que se preparó esto. Vuelve a " +
                            "abrir el conflicto para ver cómo está ahora.",
                    )
                }
            }

            val original = conflicto.original
            val hechos = ArrayList<String>()
            if (conserva.ruta != original) {
                try {
                    entorno.mover(conserva.ruta, original)
                } catch (e: IOException) {
                    throw ResolucionImposible(
                        "No se ha podido poner «${conserva.ruta.name}» como " +
                            "«${original.name}»; no se ha tocado nada.\n\n${textoDe(e)}",
                        e,
                    )
                }
                hechos.add("«${original.name}» es ahora la ${etiqueta(conflicto, conserva)}")
            }

            val fallidos = ArrayList<String>()
            for (ruta in descartar) {
                try {
                    entorno.borrar(ruta)
                    hechos.add("Borrado «${ruta.name}»")
                } catch (_: NoSuchFileException) {
                    // ya no estaba: lo que se quería
                } catch (e: IOException) {
                    fallidos.add("«${ruta.name}»: ${textoDe(e)}")
                }
            }
            if (fallidos.isNotEmpty()) {
                throw ResolucionImposible(
                    "La versión elegida ya está como «${original.name}», pero no se ha " +
                        "podido borrar:\n\n" + fallidos.joinToString("\n") +
                        "\n\nSigue apareciendo como conflicto; se puede repetir.",
                )
            }
            return hechos
        }
    }

    /** Si hay UNA versión de ese lado que conservar; habilita cada botón. */
    fun puede(conflicto: Conflictos.Conflicto, lado: String, entorno: Entorno = Entorno()): Boolean {
        val version = conflicto.version(lado)
        return version != null && huella(version.ruta, entorno) != null
    }

    /**
     * El plan de quedarse con la versión de un lado: la de este dispositivo o la
     * del remoto.
     *
     * @throws ResolucionImposible Si el lado no tiene una sola versión.
     * @throws NoSuchElementException Si [lado] no es ni `dispositivo` ni
     *   `remoto` (el `KeyError` de Python).
     */
    fun planLado(conflicto: Conflictos.Conflicto, lado: String, entorno: Entorno = Entorno()): ResolvePlan {
        val version = conflicto.version(lado)
            ?: throw ResolucionImposible(
                "No se sabe cuál es la ${ETIQUETA_LADO.getValue(lado)} de «${conflicto.relativa}»: " +
                    "elige una versión concreta de la lista.",
            )
        return planConservar(conflicto, version, entorno)
    }

    /**
     * El plan de conservar [version] y borrar las demás. La elegida se queda con
     * el nombre de verdad.
     *
     * @throws ResolucionImposible Si la versión ya no está.
     * @throws NoSuchElementException Si [version] no es de este conflicto.
     */
    fun planConservar(
        conflicto: Conflictos.Conflicto,
        version: Conflictos.Version,
        entorno: Entorno = Entorno(),
    ): ResolvePlan {
        if (huella(version.ruta, entorno) == null) {
            throw ResolucionImposible(
                "«${version.ruta.name}» ya no está: no se puede conservar. Vuelve a " +
                    "abrir los conflictos para ver cómo están ahora.",
            )
        }

        val rel = conflicto.relativa
        val otras = conflicto.versiones.filter { it.ruta != version.ruta }
        // El original descartado no se borra: lo sustituye `mover()`, de una vez.
        val aBorrar = otras.filter { !it.esOriginal }.map { it.ruta }
        val huellas = LinkedHashMap<File, Pair<Long, Long>?>()
        for (v in conflicto.versiones) huellas[v.ruta] = huella(v.ruta, entorno)

        val consequences = ArrayList<String>()
        val warnings = ArrayList<String>()
        val nombre = etiqueta(conflicto, version)
        if (version.esOriginal) {
            consequences.add("Se queda «$rel» tal como está: la $nombre (${describir(version.ruta, entorno)}).")
        } else {
            consequences.add("La $nombre (${describir(version.ruta, entorno)}) pasa a ser «$rel».")
        }
        for (otra in otras) {
            val texto = "la ${etiqueta(conflicto, otra)} (${describir(otra.ruta, entorno)})"
            if (otra.esOriginal) {
                consequences.add("Sustituye a $texto, que se pierde.")
            } else {
                consequences.add("Se borra $texto.")
            }
        }
        consequences.add("Lo que se descarta se borra del dispositivo, sin papelera.")
        consequences.add(NOTA_LOCAL)

        val elegida = huella(version.ruta, entorno)
        val masReciente = otras.any { o ->
            val h = huella(o.ruta, entorno)
            h != null && elegida != null && h.second > elegida.second
        }
        if (masReciente) {
            warnings.add(
                "La versión que se descarta es más reciente que la que se conserva. " +
                    "Comprueba que es la que quieres perder.",
            )
        }
        if (esDelLlavero(conflicto)) warnings.add(PIERDE_LLAVERO)
        return ResolvePlan(conflicto, version, aBorrar, huellas, consequences, warnings, entorno)
    }

    /** Cómo se enseña la pareja de un conflicto: la del llavero, sin su nombre interno. */
    fun nombrePareja(nombre: String): String = if (nombre == LLAVERO) "(el llavero)" else nombre

    /**
     * Si el conflicto es de la base del llavero, que se combina en el PC en vez
     * de elegir (ver arriba).
     */
    fun esDelLlavero(conflicto: Conflictos.Conflicto): Boolean =
        conflicto.pareja == LLAVERO && sufijoPy(conflicto.original.name).lowercase() == ".kdbx"

    /**
     * `PurePath.suffix` de Python: desde el último punto, salvo que sea el
     * primer carácter del nombre (`.kdbx` no tiene) o el último (`a.` tampoco).
     */
    private fun sufijoPy(nombre: String): String {
        val i = nombre.lastIndexOf('.')
        return if (i > 0 && i < nombre.length - 1) nombre.substring(i) else ""
    }
}
