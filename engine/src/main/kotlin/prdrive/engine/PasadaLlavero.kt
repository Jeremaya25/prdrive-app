package prdrive.engine

import java.io.File

/**
 * PasadaLlavero.kt — Lo que la pasada hace distinto cuando la pareja es el
 * llavero.
 *
 * Espejo de la parte del llavero de `sync.py` de prdrive: lo que va en
 * `_pasar_pareja()` bajo `pair.llavero` (la llave sin cifrar, la base que no
 * está entera y la carpeta del remoto antes de un `--resync`),
 * `crear_carpeta_remota()`, `lo_que_borraria_forzando()`, `repetir_sin_freno()`
 * y `reapuntar_llavero()`. [Pasada.pasar] lo llama en su sitio; aquí está
 * aparte para que la pasada de cualquier pareja se siga leyendo de un tirón.
 *
 * Las llamadas a rclone no se montan aquí: cada repetición es la misma orden de
 * la pasada ([Orden.tramos] + [Pasada.llamadaDe]) con la pareja cambiada
 * (`pareja.copy(flags = pareja.flags + cambio)`), como el
 * `replace(pair, flags=…)` de prdrive, y la hace quien llama ([Llamar]).
 */
internal object PasadaLlavero {

    /**
     * Por qué no está cifrado, en el teléfono (el `motivo` de `cifrada.estado()`).
     *
     * En el escritorio lo dice `cifrada.estado()` (VeraCrypt, BitLocker); aquí lo
     * decide la app ([Pasada.Contexto.cifrado]) y no hay más que decir.
     */
    const val SIN_CIFRAR = "Este dispositivo no está cifrado."

    /**
     * Una pasada de rclone de una pareja: la de `build_command()` + `execute()`.
     * [resync] y [simulacro] son los de esa ejecución, que no tienen por qué ser
     * los del [Pasada.Contexto] (la simulación con `--force` es siempre un
     * simulacro).
     */
    fun interface Llamar {
        fun llamar(pareja: Pareja, resync: Boolean, simulacro: Boolean): Pasada.Final
    }

    /**
     * Guarda un log como `dispose_log()`: se tira si la pasada fue bien y no hay
     * que guardarlo [siempre]; si no, va a `logs/`. Devuelve dónde ha quedado.
     */
    fun interface Disponer {
        fun disponer(texto: String, codigo: Int, siempre: Boolean): File?
    }

    /**
     * Lo que se comprueba del llavero antes de llamar a rclone, en el orden de
     * `_pasar_pareja()`: sin contraseña en un dispositivo sin cifrar no se baja
     * ni se sube nada; con una base que no está entera, tampoco; y antes de un
     * `--resync` se crea su carpeta en el remoto.
     *
     * @return el código con el que acaba la pasada, o `null` para seguir.
     */
    fun antes(
        pareja: Pareja,
        carpeta: File,
        ctx: Pasada.Contexto,
        needResync: Boolean,
        rclone: Pasada.Rclone,
        decir: (String) -> Unit,
    ): Int? {
        if (pareja.llaveInterna && !ctx.cifrado) {
            // Sin cifrar, la llave estaría junto a la base.
            decir(
                "[${pareja.name}] NO SE SINCRONIZA: el llavero va sin contraseña y " +
                    "${minusculaDelante(SIN_CIFRAR)} Cifra el dispositivo, o quita el llavero de aquí.",
            )
            return Pasada.LLAVERO_SIN_CIFRAR
        }
        Llavero.preparar(carpeta, simulacro = ctx.simulacro, dormir = ctx.dormir)?.let {
            decir("[${pareja.name}] NO SE SUBE: $it")
            return Pasada.LLAVERO_ROTO
        }
        if (needResync) crearCarpetaRemota(pareja, ctx, rclone, decir)
        return null
    }

    /** `motivo[:1].lower() + motivo[1:]`: la primera letra, en minúscula. */
    private fun minusculaDelante(texto: String): String {
        if (texto.isEmpty()) return texto
        val primera = texto.offsetByCodePoints(0, 1)
        return texto.substring(0, primera).lowercase() + texto.substring(primera)
    }

    /**
     * Crea en el remoto la carpeta del llavero antes de su `--resync`
     * (`crear_carpeta_remota()`, `rclone mkdir`).
     *
     * Su carpeta (`keychain/`, junto al catálogo) la pone el código y nadie más
     * la crea, y `bisync --resync` aborta si la del remoto no existe («error
     * reading source root directory: directory not found»). Si ya está, `mkdir`
     * no hace nada. Un fallo solo se dice: la pasada lo volverá a decir con su
     * log.
     *
     * En un simulacro no se pide: en el escritorio va con `--dry-run`, con el que
     * `mkdir` no crea nada (`operations.Mkdir`, `SkipDestructive`).
     */
    fun crearCarpetaRemota(pareja: Pareja, ctx: Pasada.Contexto, rclone: Pasada.Rclone, decir: (String) -> Unit) {
        if (ctx.simulacro) return
        if (!rclone.mkdir(pareja.dest).ok) {
            decir("[${pareja.name}] No se ha podido crear ${pareja.dest} (código ${Pasada.FALLO}).")
        }
    }

    /**
     * Simula la pasada del llavero con `--force` y dice qué ficheros borraría
     * (`lo_que_borraria_forzando()`).
     *
     * Es la misma orden que se repetiría, con `--dry-run` (que no apunta nada en
     * el listado). Cuenta lo que rclone dice que saltaría al simular
     * ([Pasada.borrarian]: `Skipped delete` y `Skipped move into backup dir`), no
     * lo que sobrescribiría, que el backup-dir guarda.
     *
     * @return los nombres que borraría (vacío si ninguno), o `null` si la
     *   simulación ha fallado.
     */
    fun loQueBorrariaForzando(pareja: Pareja, llamar: Llamar): List<String>? {
        val simulada = llamar.llamar(pareja.copy(flags = pareja.flags + ("force" to true)), resync = false, simulacro = true)
        if (!simulada.ok) return null
        return Pasada.borrarian(Pasada.textoDe(simulada))
    }

    /**
     * Repite UNA vez la pasada del llavero sin el freno que la ha parado, si ese
     * freno se puede quitar (`repetir_sin_freno()`).
     *
     * Quitarlos es seguro en el llavero porque lo que pisa cada lado se queda en
     * `.prversions/` (backup-dir, también en un conflicto y en un `--resync`):
     *
     * - **Borrados** (`--max-delete`, un % del listado anterior): si todo lo
     *   borrado son copias de conflicto ([Llavero.soloCopiasBorradas]), se
     *   repite con [Llavero.FRENO_SIN_COPIAS]; con una base entre lo borrado, no.
     * - **Han cambiado todos los ficheros de un lado** (#115 de prdrive): se
     *   repite con `--force`, que también salta el de borrados; por eso antes se
     *   simula ([loQueBorrariaForzando]) y solo se fuerza si la simulación sale
     *   bien y no borra nada.
     * - **El remoto está vacío** ([Llavero.remotoVacio]): se crea la carpeta y se
     *   repite con `--resync`, que no borra nada y se queda con la base más
     *   nueva (`resync-mode = newer`).
     *
     * Una sola repetición por pasada, sea cual sea el freno: si la repetida
     * vuelve a pararse, se queda el fallo, que [Pasada.explicar] explica. En un
     * simulacro, si el freno es el de «han cambiado todos», la simulación ES la
     * repetición (sin otra llamada) y se dice qué se haría en una pasada de
     * verdad; el log de la pasada parada pasa a ser el de la pasada.
     *
     * @param parada cómo acabó la pasada que se ha parado.
     * @return cómo acaba la pasada: la repetida, la misma si no se repite, o en
     *   un simulacro que habría forzado sin borrar nada, una buena con el log de
     *   la parada.
     */
    fun repetirSinFreno(
        pareja: Pareja,
        ctx: Pasada.Contexto,
        parada: Pasada.Final,
        rclone: Pasada.Rclone,
        llamar: Llamar,
        disponer: Disponer,
        decir: (String) -> Unit,
    ): Pasada.Final {
        val texto = Pasada.textoDe(parada)
        val copias = if ("too many deletes" in texto) Llavero.soloCopiasBorradas(texto, pareja) else null
        var cambio: Map<String, Any?> = emptyMap()
        var resync = false
        when {
            copias != null -> {
                decir(
                    "[${pareja.name}] Lo borrado son copias de conflicto (${copias.joinToString(", ")}), y cada " +
                        "una queda en $VERSIONS_DIR: " +
                        if (ctx.simulacro) {
                            "en una pasada de verdad se repetiría una vez sin el freno de borrados."
                        } else {
                            "se repite la pasada sin el freno de borrados."
                        },
                )
                disponer.disponer(texto, 0, ctx.keepLogs)
                cambio = mapOf("max-delete" to Llavero.FRENO_SIN_COPIAS)
            }
            Pasada.esCambiadosBisync(texto) -> {
                // `--force` salta también el freno de borrados, y la repetición es
                // otra llamada que vuelve a listar los dos lados: se simula antes,
                // y si borrara algo (o no se pudiera saber) no se fuerza.
                val borraria = loQueBorrariaForzando(pareja, llamar)
                if (borraria == null || borraria.isNotEmpty()) {
                    val que = borraria?.let { "borraría ${it.joinToString(", ")}" } ?: "no se ha podido simular"
                    decir(
                        "[${pareja.name}] Han cambiado todos los ficheros de un lado y bisync se ha parado, " +
                            "pero forzar la pasada $que: no se fuerza, el freno manda.",
                    )
                    return parada
                }
                if (ctx.simulacro) {
                    // La simulación de arriba es la repetición: no se vuelve a
                    // llamar a rclone. La pasada parada acaba bien, con su log.
                    decir(
                        "[${pareja.name}] Han cambiado todos los ficheros de un lado y bisync se ha " +
                            "parado. El llavero deja lo que pisa cada lado en $VERSIONS_DIR: en una " +
                            "pasada de verdad se repetiría una vez con --force (no borraría nada).",
                    )
                    return Pasada.Final(true, "", texto, parada.salida)
                }
                // El log de la pasada parada se guarda aunque la repetida vaya
                // bien: es lo que dice por qué se forzó.
                val guardado = disponer.disponer(texto, Pasada.FALLO, true)
                decir(
                    "[${pareja.name}] Han cambiado todos los ficheros de un lado y bisync se ha parado" +
                        (guardado?.let { " (Log: ${it.name})" } ?: "") +
                        ". El llavero deja lo que pisa cada lado en $VERSIONS_DIR: " +
                        "se repite la pasada una vez con --force.",
                )
                cambio = mapOf("force" to true)
            }
            Llavero.remotoVacio(texto) -> {
                // Su log se guarda como el del forzado: dice por qué se ha vuelto a subir.
                val guardado = disponer.disponer(texto, Pasada.FALLO, true)
                decir(
                    "[${pareja.name}] La carpeta del llavero en el remoto está vacía o ya no está, y " +
                        "bisync no sincroniza contra un lado vacío" +
                        (guardado?.let { " (Log: ${it.name})" } ?: "") +
                        ". Un --resync no borra nada y lo que pise queda en $VERSIONS_DIR: " +
                        "se crea la carpeta y se repite la pasada una vez con --resync.",
                )
                crearCarpetaRemota(pareja, ctx, rclone, decir)
                resync = true
            }
            else -> return parada
        }
        return llamar.llamar(pareja.copy(flags = pareja.flags + cambio), resync = resync, simulacro = ctx.simulacro)
    }

    /**
     * Repite en seguida la pasada del llavero si rclone ha dejado una base fuera
     * de su listado (`reapuntar_llavero()`).
     *
     * Pasa tras un conflicto que gana el remoto, por un fallo de rclone
     * ([Llavero.sinListar]). Tiene que ser ya, con las dos bases iguales: así
     * bisync solo la vuelve a apuntar. Si falla, solo se dice: lo peor es que un
     * cambio antes de la próxima pasada buena salga como otro conflicto.
     */
    fun reapuntar(
        pareja: Pareja,
        carpeta: File,
        listado: File,
        ctx: Pasada.Contexto,
        llamar: Llamar,
        disponer: Disponer,
        decir: (String) -> Unit,
    ) {
        val faltan = Llavero.sinListar(carpeta, listado)
        if (faltan.isEmpty()) return
        decir(
            "[${pareja.name}] rclone no ha apuntado ${faltan.joinToString(", ")} tras el conflicto: " +
                "otra pasada, ahora que están iguales, para que lo haga.",
        )
        val final = llamar.llamar(pareja, resync = false, simulacro = ctx.simulacro)
        val codigo = if (final.ok) 0 else Pasada.FALLO
        val guardado = disponer.disponer(Pasada.textoDe(final), codigo, ctx.keepLogs)
        if (codigo != 0) {
            decir("[${pareja.name}] Esa pasada ha fallado (código $codigo)." + (guardado?.let { " Log: ${it.name}" } ?: ""))
        }
    }
}
