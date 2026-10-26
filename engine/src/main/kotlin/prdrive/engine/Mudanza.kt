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
 *   tenía está en el remoto (la pasada de antes) y en la carpeta (el `--resync`).
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
            entradas[nombre]?.let { throw Parada(yaTieneEntrada(nombre, it)) }
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
     * `rclone.conf`; (3) el `--resync`, con [RESYNC_MODE]: si falla, se para y se
     * deja todo; (4) se borra la copia privada ([carpetaPrivada]) sin seguir
     * enlaces; (5) la entrada pasa a `lista`.
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

            val r = resync(volumen, pareja, e).also { pasadas.add(it) }
            if (!r.ok) {
                throw Parada(
                    "El --resync en la carpeta compartida no ha acabado bien: ${porQue(r)}. La pareja se " +
                        "queda a medio mover, con la copia del volumen intacta: cuando se arregle, usa " +
                        "«Terminar de mover» otra vez, o «Volver al volumen».",
                )
            }

            val privada = carpetaPrivada(volumen, pareja)
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
     * compartida; (3) la carpeta privada está vacía o sin crear, salvo a medio
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
                val antes = pasadaNormal(volumen, pareja, e).also { pasadas.add(it) }
                if (!antes.ok) {
                    throw Parada(
                        "Antes de volver hace falta una pasada que acabe bien, para que lo último de " +
                            "${entrada.ruta} llegue al remoto, y no ha sido así: ${porQue(antes)}. No se ha " +
                            "cambiado nada; arréglalo y vuelve a intentarlo.",
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

    private fun yaTieneEntrada(nombre: String, entrada: Compartidas.Entrada): String =
        if (entrada.estado == Compartidas.Estado.ENTRANDO) {
            "'$nombre' ya se está mudando a ${entrada.ruta}: usa «Terminar de mover» o «Volver al volumen»."
        } else {
            "'$nombre' ya vive en ${entrada.ruta}. Para llevarla a otra carpeta, vuélvela antes al volumen."
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
