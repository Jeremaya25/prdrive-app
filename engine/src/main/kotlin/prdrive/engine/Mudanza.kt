package prdrive.engine

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Mudanza.kt — Mover una pareja a una carpeta compartida del teléfono, y
 * devolverla al volumen.
 *
 * No es espejo de ningún fichero de Python de prdrive: es una diferencia del
 * teléfono, como el `device_remote` obligatorio y [Compartidas]. Vive en el
 * motor porque es donde un error costaría caro, y aquí se prueba en segundos y
 * sin emulador. El diseño, en
 * `docs/superpowers/specs/2026-10-26-carpeta-compartida-design.md` (§3).
 *
 * Lo que manda es que **ningún corte, en ningún punto, deje una pasada que
 * borre en el remoto**. De ahí el orden de lo que se escribe:
 *
 * - Los listados se apartan ([Bisync.apartarListados]) antes de redirigir nada:
 *   desde ahí, la pasada siguiente pide `--resync`, que no borra. Sin esto, una
 *   pasada normal contra una carpeta a medio llenar vería borrados y los
 *   llevaría al remoto.
 * - Al mover, la entrada de `compartidas.json` antes que el `[disp]` del
 *   `rclone.conf`; al volver, el `[disp]` antes que quitar la entrada. Un corte
 *   entre los dos deja una entrada que el `rclone.conf` no lleva, y la pasada
 *   se para sin llamar a rclone ([Pasada.COMBINE_VIEJO]).
 * - La copia privada solo se borra tras un `--resync` que acaba bien: lo que
 *   se sincroniza está en el remoto (la pasada de antes) y en la carpeta (el
 *   `--resync`), y lo que no (lo que los filtros dejan fuera, `.prversions/`)
 *   se lleva antes a la carpeta, con su fecha.
 * - La carpeta compartida no se borra nunca: es de la persona y de Obsidian.
 *
 * Los dos `--resync` llevan `resync-mode = path2` ([RESYNC_MODE]): en caso de
 * duda, gana el remoto. La carpeta de destino empieza vacía, así que lo único
 * que puede haber en ella es lo de un `--resync` anterior que se cortó (lo
 * mismo que en el remoto) o lo que crea Obsidian al abrirla antes de tiempo
 * (su `.obsidian/` de fábrica, que no debe pisar la del remoto). Las pasadas
 * son las de siempre ([Pasada.pasarOFallar]): se apuntan en `state/` y la fila
 * las ve.
 *
 * El motor no sabe de Android: la prueba de fechas, recargar rclone (que vacía
 * su `fscache`) y si se ve entera una carpeta compartida
 * ([Pasada.Contexto.accesoCompartido]) los pone la app, en el [Entorno].
 */
object Mudanza {

    /** El `--resync-mode` de los `--resync` de la mudanza: gana Path2, el remoto. */
    const val RESYNC_MODE = "path2"

    /**
     * Lo que la mudanza necesita de fuera: quién habla con rclone y cómo se
     * traducen las órdenes ([rclone], [opciones]), el contexto de las pasadas
     * ([ctx], con [Pasada.Contexto.accesoCompartido]: sin él, la pasada de una
     * pareja compartida aborta), la prueba de fechas de una carpeta
     * ([probarFechas]: `null` si vale, o por qué no) y cómo recargar rclone tras
     * reescribir el `rclone.conf` ([recargar]).
     */
    data class Entorno(
        val rclone: Pasada.Rclone,
        val opciones: Pasada.Opciones,
        val ctx: Pasada.Contexto,
        val probarFechas: (File) -> String?,
        val recargar: () -> Unit,
        val alProgreso: (Map<String, Any?>) -> Unit = {},
        val decir: (String) -> Unit = {},
    )

    /**
     * Cómo ha ido: [motivo] es por qué se ha parado y qué hacer (`null` si ha
     * acabado), y [pasadas], cada pasada que se ha hecho, en orden.
     */
    data class Hecho(val motivo: String?, val pasadas: List<Pasada.Resultado>) {
        val ok: Boolean get() = motivo == null
    }

    /**
     * La copia privada de la pareja: su carpeta en el volumen, la de antes de
     * mudarse. [Pareja.carpetaEn] no sirve, porque con la pareja movida da la
     * compartida. Solo para parejas que pueden mudarse, de un solo tramo
     * ([Compartidas.problemaPareja]).
     */
    fun carpetaPrivada(volumen: Volumen, pareja: Pareja): File = File(volumen.raiz, pareja.tramosLocales.single())

    /**
     * Mueve la pareja [nombre] a [carpeta], que tiene que estar vacía o sin
     * crear, y la termina de mover ([terminar]).
     *
     * Por orden: (1) la pareja no tiene entrada y puede vivir en [carpeta]
     * ([Compartidas.problema]); (2) la carpeta está vacía, o se crea; (3) sus
     * fechas valen ([Entorno.probarFechas]); (4) una pasada normal que acaba
     * bien, para que el remoto tenga todo lo de la copia privada; (5) se apartan
     * los listados; (6) la entrada, en `entrando`; (7) el `rclone.conf` y
     * rclone recargado; (8) [terminar]. Si se para antes de apuntar la entrada,
     * no se ha escrito nada que la pasada lea, y la carpeta que se creó en (2)
     * se quita si sigue vacía.
     */
    fun entrar(volumen: Volumen, nombre: String, carpeta: File, e: Entorno): Hecho {
        require(!e.ctx.simulacro) { SIN_SIMULACRO }
        val pasadas = ArrayList<Pasada.Resultado>()
        val ruta = carpeta.path
        var creada = false
        var apuntada = false
        try {
            val (config, entradas) = leer(volumen)
            entradas[nombre]?.let { throw Parada(yaTieneEntrada(config, nombre, it)) }
            Compartidas.problema(config, nombre, ruta, volumen.raiz, entradas)?.let { throw Parada(it) }
            val pareja = parejaDe(config, nombre)

            // Nada que fusionar, nada que perder.
            if (carpeta.exists()) {
                if (!carpeta.isDirectory) {
                    throw Parada("'$ruta' existe y no es una carpeta. Elige una carpeta vacía o una que no exista.")
                }
                val dentro = carpeta.list() ?: throw Parada(
                    "No se puede mirar dentro de '$ruta' para ver si está vacía. Comprueba el permiso de " +
                        "acceso a todos los archivos o elige otra carpeta.",
                )
                if (dentro.isNotEmpty()) {
                    throw Parada(
                        "La carpeta '$ruta' no está vacía, y solo se mueve a una carpeta vacía o que no " +
                            "exista: así no hay nada que mezclar ni que pisar. Elige otra, o vacíala.",
                    )
                }
            } else {
                if (!carpeta.mkdirs()) {
                    throw Parada(
                        "No se ha podido crear la carpeta '$ruta'. Comprueba el permiso de acceso a todos " +
                            "los archivos o elige otra.",
                    )
                }
                creada = true
            }

            e.probarFechas(carpeta)?.let {
                throw Parada(
                    "La prueba de fechas en '$ruta' no ha salido bien: $it. Sin fechas fiables bisync no " +
                        "sabe qué ha cambiado, así que no se ha movido nada. Prueba con otra carpeta.",
                )
            }

            val antes = pasadaNormal(volumen, pareja, e).also { pasadas.add(it) }
            if (!antes.ok) {
                throw Parada(
                    "Antes de mover hace falta una pasada que acabe bien, para que el remoto tenga todo " +
                        "lo de la copia del volumen, y no ha sido así: ${porQue(antes)}. No se ha movido " +
                        "nada; arréglalo y vuelve a intentarlo.",
                )
            }

            e.decir("[$nombre] Se apartan los listados: hasta que acabe la mudanza, la pareja pide --resync.")
            paso({
                "No se han podido apartar los listados de '$nombre' ($it), y sin eso un corte a medio " +
                    "mover podría borrar en el remoto: no se ha movido nada. Si la pareja pide un " +
                    "--resync, hazlo antes de volver a intentarlo."
            }) {
                Bisync.apartarListados(volumen.workdir(pareja))
            }

            // La entrada antes que el rclone.conf: un corte entre las dos deja
            // una entrada que el rclone.conf no lleva, y la pasada lo para.
            paso({
                "No se ha podido apuntar la carpeta compartida ($it). No se ha movido nada, pero los " +
                    "listados de '$nombre' están apartados: la pareja pedirá un --resync antes de volver " +
                    "a intentarlo."
            }) {
                Compartidas.escribir(
                    volumen.compartidas,
                    entradas + (nombre to Compartidas.Entrada(ruta, Compartidas.Estado.ENTRANDO)),
                )
            }
            apuntada = true

            e.decir("[$nombre] Desde ahora, la pareja vive en $ruta.")
            paso({
                "La carpeta compartida está apuntada, pero no se ha podido reescribir el rclone.conf " +
                    "($it): la pareja no sincronizará hasta que se termine. Usa «Terminar de mover»."
            }) {
                volumen.actualizarCombine(volumen.leerConfig())
                e.recargar()
            }
        } catch (p: Parada) {
            return Hecho(p.motivo, pasadas)
        } finally {
            // Parada antes de apuntar la entrada: nadie va a usar la carpeta que
            // se creó para esto. Se quita si sigue vacía (`delete()` no borra
            // una carpeta con algo).
            if (creada && !apuntada) carpeta.delete()
        }
        val resto = terminar(volumen, nombre, e)
        return Hecho(resto.motivo, pasadas + resto.pasadas)
    }

    /**
     * Termina de mover la pareja [nombre], que está `entrando`. Es lo que repite
     * «Terminar de mover» tras un corte o un fallo.
     *
     * Por orden: (1) la entrada está `entrando`; (2) el `rclone.conf` y rclone
     * recargado otra vez, que arregla un corte entre la entrada y el
     * `rclone.conf`; (3) se quitan de la carpeta los temporales que dejara una
     * vez anterior en (4), y el `--resync`, con [RESYNC_MODE] (o, si una vez
     * anterior se cortó después de él y ya hay baseline, una pasada normal): si
     * falla, se para y se deja todo; (4) lo que la copia privada
     * ([carpetaPrivada]) tiene y la carpeta no (lo que los filtros dejan fuera,
     * `.prversions/`) se lleva a la carpeta con su fecha, y después se borra la
     * copia sin seguir enlaces; si algo no se puede llevar, no se borra nada;
     * (5) la entrada pasa a `lista`.
     *
     * A medio mover, la fila solo ofrece «Terminar de mover» (y «Volver al
     * volumen» si la entrada ya no vale): los motivos no mandan a otro sitio.
     */
    fun terminar(volumen: Volumen, nombre: String, e: Entorno): Hecho {
        require(!e.ctx.simulacro) { SIN_SIMULACRO }
        val pasadas = ArrayList<Pasada.Resultado>()
        try {
            val (config, entradas) = leer(volumen)
            val entrada = entradas[nombre]
                ?: throw Parada("'$nombre' no tiene ninguna mudanza a medias que terminar.")
            if (entrada.estado != Compartidas.Estado.ENTRANDO) {
                throw Parada("'$nombre' ya vive en ${entrada.ruta}: no hay nada que terminar.")
            }
            val pareja = parejaDe(config, nombre)
            // Si la entrada ya no vale (el config ha cambiado), la pareja apunta
            // a la copia privada: un --resync ahí y borrarla después la perdería.
            if (pareja.carpetaExterna == null) {
                throw Parada(
                    (pareja.problemaCompartida ?: "La entrada de '$nombre' no se aplica.") +
                        " Arréglalo o usa «Volver al volumen».",
                )
            }

            paso({
                "No se ha podido reescribir el rclone.conf para apuntar '$nombre' a ${entrada.ruta} " +
                    "($it). Vuelve a usar «Terminar de mover»."
            }) {
                volumen.actualizarCombine(config)
                e.recargar()
            }

            // Lo que dejó a medias una vez anterior al llevar ficheros (paso 4) no
            // puede llegar a la pasada: se subiría, y quitarlo después borraría
            // en el remoto.
            paso({
                "No se ha podido quitar de ${entrada.ruta} un temporal de una vez anterior ($it). " +
                    "Quítalo tú (acaba en $SUFIJO_LLEVANDO) y vuelve a usar «Terminar de mover»."
            }) {
                quitarTemporales(File(entrada.ruta))
            }

            // Un --resync; o, si una vez anterior se cortó después de él, una
            // pasada normal (ya hay baseline, y no hace falta otro).
            val conBaseline = Bisync.pairState(volumen.workdir(pareja)).hasBaseline
            val r = resync(volumen, pareja, e).also { pasadas.add(it) }
            if (!r.ok) {
                val cual = if (r.resync || !conBaseline) "El --resync" else "La pasada"
                throw Parada(
                    "$cual en la carpeta compartida no ha acabado bien: ${porQue(r)}. La pareja se queda a " +
                        "medio mover, con la copia del volumen intacta: cuando se arregle, vuelve a usar " +
                        "«Terminar de mover».",
                )
            }

            // Lo que solo tiene la copia privada (lo que los filtros dejan fuera,
            // `.prversions/`, lo escrito en el último momento) no está en el
            // remoto ni en la carpeta: se lleva allí antes de borrarla.
            val privada = carpetaPrivada(volumen, pareja)
            val llevados = paso({
                "El --resync ha ido bien, pero no se ha podido llevar a ${entrada.ruta} lo que solo tenía " +
                    "la copia del volumen ($it). No se ha borrado nada: lo ya llevado está allí y lo demás " +
                    "sigue en la copia. Cuando se arregle, vuelve a usar «Terminar de mover»."
            }) {
                llevarLoQueFalta(privada, File(entrada.ruta))
            }
            if (llevados > 0) {
                e.decir(
                    "[$nombre] $llevados fichero(s) que solo tenía la copia del volumen (lo que no se " +
                        "sincroniza, como $VERSIONS_DIR/) se han llevado a ${entrada.ruta}, con su fecha.",
                )
            }
            e.decir("[$nombre] Lo de la copia del volumen ya está en el remoto y en ${entrada.ruta}: se borra $privada.")
            paso({
                "El --resync ha ido bien, pero no se ha podido borrar la copia del volumen ($it). " +
                    "Vuelve a usar «Terminar de mover»."
            }) {
                borrar(privada)
            }

            paso({
                "Todo está en la carpeta compartida, pero no se ha podido apuntar que la mudanza ha " +
                    "acabado ($it). Vuelve a usar «Terminar de mover»."
            }) {
                Compartidas.escribir(
                    volumen.compartidas,
                    entradas + (nombre to entrada.copy(estado = Compartidas.Estado.LISTA)),
                )
            }
        } catch (p: Parada) {
            return Hecho(p.motivo, pasadas)
        }
        return Hecho(null, pasadas)
    }

    /**
     * Devuelve la pareja [nombre] al volumen. La carpeta compartida no se toca:
     * es de la persona y de Obsidian.
     *
     * Por orden: (1) tiene entrada; (2) con [conPasada] (hay permiso), una
     * pasada normal que acaba bien, para que lo último de la carpeta compartida
     * llegue al remoto; sin él, lo que no se subió se queda en la carpeta
     * compartida. Esa pasada se salta si la pareja no está entera en su carpeta
     * (la entrada no vale, el `rclone.conf` no la lleva o no hay baseline): un
     * «Volver» anterior se cortó después de su pasada, y repetirla abortaría
     * siempre; (3) la carpeta privada está vacía o sin crear, salvo a medio
     * mover (`entrando`: es la copia de antes, que ya está en el remoto); (4) se
     * apartan los listados; (5) el `rclone.conf` sin la redirección y rclone
     * recargado; (6) se quita la entrada; (7) el `--resync`, con [RESYNC_MODE],
     * que baja lo del remoto a la carpeta privada.
     */
    fun salir(volumen: Volumen, nombre: String, conPasada: Boolean, e: Entorno): Hecho {
        require(!e.ctx.simulacro) { SIN_SIMULACRO }
        val pasadas = ArrayList<Pasada.Resultado>()
        try {
            val (config, entradas) = leer(volumen)
            val entrada = entradas[nombre]
                ?: throw Parada("'$nombre' no vive en ninguna carpeta compartida: ya está en el volumen.")
            val pareja = parejaDe(config, nombre)

            if (conPasada) {
                // Solo se pasa si la pareja está entera en su carpeta. Si no, esa
                // pasada abortaría (`COMBINE_VIEJO`) o quedaría saltada (sin
                // baseline), y volver se quedaría atascado justo cuando hay que
                // acabar algo que se cortó.
                val noEntera = when {
                    pareja.carpetaExterna == null ->
                        "la entrada ya no vale" + (pareja.problemaCompartida?.let { " ($it)" } ?: "")
                    !Compartidas.alDia(volumen.leerRcloneConf(), pareja) ||
                        !Bisync.pairState(volumen.workdir(pareja)).hasBaseline ->
                        if (entrada.estado == Compartidas.Estado.ENTRANDO) {
                            "la mudanza a esa carpeta no llegó a acabar"
                        } else {
                            "un «Volver al volumen» anterior se cortó después de su pasada"
                        }
                    else -> null
                }
                if (noEntera == null) {
                    val antes = pasadaNormal(volumen, pareja, e).also { pasadas.add(it) }
                    if (!antes.ok) {
                        throw Parada(
                            "Antes de volver hace falta una pasada que acabe bien, para que lo último de " +
                                "${entrada.ruta} llegue al remoto, y no ha sido así: ${porQue(antes)}. No se " +
                                "ha cambiado nada; arréglalo y vuelve a intentarlo.",
                        )
                    }
                } else {
                    e.decir(
                        "[$nombre] No se pasa antes de volver: $noEntera. Lo que no se haya subido se queda " +
                            "en ${entrada.ruta}, que no se toca.",
                    )
                }
            }

            if (entrada.estado != Compartidas.Estado.ENTRANDO) {
                // La carpeta que llenará el --resync. En una pareja que puede
                // mudarse es la copia privada; se pregunta así para que una cuyo
                // config ha cambiado desde entonces no se salte la regla.
                val privada = pareja.copy(carpetaExterna = null).carpetaEn(volumen.raiz)
                if (privada.exists() && (!privada.isDirectory || privada.list()?.isEmpty() != true)) {
                    throw Parada(
                        "'$privada' no está vacía, y volver la pisaría con lo del remoto. Saca de ahí lo " +
                            "que tenga y vuelve a intentarlo; no se ha cambiado nada.",
                    )
                }
            }

            e.decir("[$nombre] Se apartan los listados: hasta que acabe la vuelta, la pareja pide --resync.")
            paso({
                "No se han podido apartar los listados de '$nombre' ($it), y sin eso un corte a medio " +
                    "volver podría borrar en el remoto: no se ha cambiado nada."
            }) {
                Bisync.apartarListados(volumen.workdir(pareja))
            }

            // El rclone.conf antes que quitar la entrada: un corte entre los dos
            // deja una entrada que el rclone.conf no lleva, y la pasada lo para.
            val quedan = entradas - nombre
            paso({
                "No se ha podido reescribir el rclone.conf para devolver '$nombre' al volumen ($it). " +
                    "Sigue en ${entrada.ruta}, con los listados apartados: vuelve a intentarlo."
            }) {
                volumen.actualizarCombine(config, quedan)
                e.recargar()
            }

            paso({
                "El rclone.conf ya devuelve '$nombre' al volumen, pero no se ha podido quitar su carpeta " +
                    "compartida de ${Compartidas.FICHERO} ($it): la pareja no sincronizará hasta que se " +
                    "vuelva a usar «Volver al volumen»."
            }) {
                Compartidas.escribir(volumen.compartidas, quedan)
            }
            e.decir("[$nombre] La pareja vuelve a vivir en el volumen.")

            val r = resync(volumen, parejaDe(config.conCompartidas(quedan, volumen.raiz), nombre), e)
                .also { pasadas.add(it) }
            if (!r.ok) {
                throw Parada(
                    "'$nombre' ya vuelve a estar en el volumen, pero el --resync que baja lo del remoto no " +
                        "ha acabado bien: ${porQue(r)}. Cuando se arregle, haz el --resync desde su fila; " +
                        "${entrada.ruta} se queda como estaba.",
                )
            }
            e.decir("[$nombre] ${entrada.ruta} se queda como está: bórrala tú cuando ya no la quieras.")
        } catch (p: Parada) {
            return Hecho(p.motivo, pasadas)
        }
        return Hecho(null, pasadas)
    }

    // -----------------------------------------------------------------------

    private const val SIN_SIMULACRO =
        "Una mudanza no se simula: sus pasadas tienen que subir y bajar de verdad antes de borrar nada."

    /** La mudanza se para aquí, con lo que se le dice a la persona. */
    private class Parada(val motivo: String) : Exception(motivo)

    /** Hace [bloque]; un error de E/S o de config es una [Parada] con el texto de [motivo] (que recibe su mensaje). */
    private inline fun <T> paso(motivo: (String) -> String, bloque: () -> T): T = try {
        bloque()
    } catch (ex: IOException) {
        throw Parada(motivo(ex.message ?: ex.toString()))
    } catch (ex: ConfigError) {
        throw Parada(motivo(ex.message ?: ex.toString()))
    }

    /** El config del volumen (con las carpetas compartidas puestas) y las entradas de `compartidas.json`. */
    private fun leer(volumen: Volumen): kotlin.Pair<Config, Map<String, Compartidas.Entrada>> =
        paso({ "No se ha podido leer el config del volumen: $it" }) {
            volumen.leerConfig() to Compartidas.leer(volumen.compartidas)
        }

    private fun parejaDe(config: Config, nombre: String): Pareja =
        config.pairs.firstOrNull { it.name == nombre }
            ?: throw Parada("No existe ninguna pareja que se llame '$nombre' en el config del volumen.")

    /**
     * Por qué no se vuelve a mover una pareja con entrada. A medio mover la fila
     * solo ofrece «Terminar de mover», salvo si la entrada ya no vale
     * ([Pareja.problemaCompartida]): entonces también «Volver al volumen».
     */
    private fun yaTieneEntrada(config: Config, nombre: String, entrada: Compartidas.Entrada): String {
        if (entrada.estado != Compartidas.Estado.ENTRANDO) {
            return "'$nombre' ya vive en ${entrada.ruta}. Para llevarla a otra carpeta, vuélvela antes al volumen."
        }
        val problema = config.pairs.firstOrNull { it.name == nombre }?.problemaCompartida
        return if (problema == null) {
            "'$nombre' ya se está mudando a ${entrada.ruta}: usa «Terminar de mover»."
        } else {
            "'$nombre' se estaba mudando a ${entrada.ruta}, pero la entrada ya no vale: $problema " +
                "Arréglalo y usa «Terminar de mover», o usa «Volver al volumen»."
        }
    }

    /** Una pasada normal: sin `--resync`, aunque el contexto lo pida. */
    private fun pasadaNormal(volumen: Volumen, pareja: Pareja, e: Entorno): Pasada.Resultado =
        Pasada.pasarOFallar(
            volumen, pareja, e.rclone, e.opciones,
            e.ctx.copy(resyncAprobado = false, forzarResync = false), e.alProgreso, e.decir,
        )

    /** El `--resync` de la mudanza: aprobado (lo ha pedido la persona) y con [RESYNC_MODE]. */
    private fun resync(volumen: Volumen, pareja: Pareja, e: Entorno): Pasada.Resultado =
        Pasada.pasarOFallar(
            volumen, pareja, e.rclone, e.opciones,
            e.ctx.copy(resyncAprobado = true, resyncMode = RESYNC_MODE), e.alProgreso, e.decir,
        )

    /** Por qué una pasada no ha acabado bien, para decirlo en una frase. */
    private fun porQue(r: Pasada.Resultado): String =
        r.explicacion
            ?: if (r.saltada) {
                "la pareja pide un --resync, y sin él no corre una pasada normal"
            } else {
                r.lineas.firstOrNull { it.startsWith("[${r.pareja}] ") && ("ERROR" in it || "FALLÓ" in it) }
                    ?: "ha acabado con el código ${r.codigo}"
            }

    /**
     * Lleva a [destino] cada fichero (y cada enlace, como enlace) de [origen]
     * que [destino] no tiene en la misma ruta relativa, con su fecha de
     * modificación y creando las carpetas que falten. Lo que [destino] ya tiene
     * no se toca: es la copia sincronizada. No se sigue ningún enlace, ni en
     * [origen] ni en las carpetas de [destino] por las que se pasa: uno en medio
     * es un error, no un sitio al que escribir. Las carpetas vacías no se llevan.
     * [origen] queda como estaba: quien llama lo borra después, entero.
     *
     * @return cuántos se han llevado.
     * @throws IOException al primero que no se puede llevar (los anteriores ya
     *   están en [destino]).
     */
    private fun llevarLoQueFalta(origen: File, destino: File): Int {
        val raiz = origen.toPath()
        if (!Files.isDirectory(raiz, LinkOption.NOFOLLOW_LINKS)) return 0
        // Primero se apunta todo y después se mueve: así no se saca nada de una
        // carpeta mientras se está recorriendo.
        val ficheros = ArrayList<kotlin.Pair<Path, BasicFileAttributes>>()
        Files.walkFileTree(
            raiz,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile || attrs.isSymbolicLink) ficheros.add(file to attrs)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        val otra = destino.toPath()
        var llevados = 0
        for ((fichero, attrs) in ficheros) {
            val relativa = raiz.relativize(fichero)
            val donde = otra.resolve(relativa)
            if (Files.exists(donde, LinkOption.NOFOLLOW_LINKS)) continue
            relativa.parent?.let { carpetaSinEnlaces(otra, it) }
            // De la memoria de la app a la compartida es otro sistema de
            // ficheros: mover es copiar y borrar, y un corte a media copia dejaría
            // con su nombre un fichero a medias, que la vez siguiente se daría por
            // llevado antes de borrar el original. Así que se copia a un temporal
            // al lado, con su fecha, y se renombra (eso sí, de un golpe). El
            // original se queda: se va con la copia privada entera.
            val temporal = donde.resolveSibling(donde.fileName.toString() + SUFIJO_LLEVANDO)
            Files.deleteIfExists(temporal)
            try {
                Files.copy(fichero, temporal, LinkOption.NOFOLLOW_LINKS)
                if (attrs.isRegularFile) Files.setLastModifiedTime(temporal, attrs.lastModifiedTime())
                // Sin REPLACE_EXISTING: si algo aparece ahí entretanto, falla en
                // vez de pisarlo.
                Files.move(temporal, donde)
            } catch (ex: IOException) {
                // El temporal está en la carpeta que se sincroniza: no se deja ahí.
                try {
                    Files.deleteIfExists(temporal)
                } catch (otro: IOException) {
                    ex.addSuppressed(otro)
                }
                throw ex
            }
            llevados++
        }
        return llevados
    }

    /** El sufijo del temporal de [llevarLoQueFalta], junto a su destino. */
    internal const val SUFIJO_LLEVANDO = ".~prdrive-llevando"

    /**
     * Quita de [carpeta] los temporales de [llevarLoQueFalta] que dejara un
     * corte (los ficheros o enlaces que acaban en [SUFIJO_LLEVANDO]), sin seguir
     * enlaces. Si no existe, no hace nada.
     */
    private fun quitarTemporales(carpeta: File) {
        val raiz = carpeta.toPath()
        if (!Files.isDirectory(raiz, LinkOption.NOFOLLOW_LINKS)) return
        val temporales = ArrayList<Path>()
        Files.walkFileTree(
            raiz,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (file.fileName.toString().endsWith(SUFIJO_LLEVANDO)) temporales.add(file)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        temporales.forEach { Files.deleteIfExists(it) }
    }

    /** Crea (si falta) [relativa] dentro de [raiz], tramo a tramo y sin pasar por un enlace ni un fichero. */
    private fun carpetaSinEnlaces(raiz: Path, relativa: Path) {
        var actual = raiz
        for (tramo in relativa) {
            actual = actual.resolve(tramo)
            when {
                Files.isSymbolicLink(actual) -> throw IOException("'$actual' es un enlace")
                !Files.exists(actual, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectory(actual)
                !Files.isDirectory(actual, LinkOption.NOFOLLOW_LINKS) -> throw IOException("'$actual' no es una carpeta")
            }
        }
    }

    /**
     * Borra [carpeta] entera sin seguir enlaces, como borra el proveedor: un
     * enlace se borra, no lo que señala. `Files.walkFileTree` sin
     * `FOLLOW_LINKS` entrega cada enlace como un fichero, también si el enlace
     * es la propia [carpeta]. Si no existe, no hace nada.
     */
    private fun borrar(carpeta: File) {
        val raiz = carpeta.toPath()
        if (!Files.exists(raiz, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            raiz,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
