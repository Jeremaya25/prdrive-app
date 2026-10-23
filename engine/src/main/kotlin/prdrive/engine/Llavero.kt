package prdrive.engine

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Llavero.kt — El llavero: la base de KeePassXC de este dispositivo, que se
 * sincroniza sola. La parte pura de lo que decide el llavero antes y después de
 * cada pasada.
 *
 * Espejo de `common/llavero.py` de prdrive. La base vive en `.keychain/`, en la
 * raíz del volumen (`LLAVERO_LOCAL`), y su pareja la construye el código cuando
 * hay `[keychain]` (`Config.parejaLlavero`): bisync con versiones contra la
 * subcarpeta `keychain/` de la carpeta del catálogo. Aquí van las decisiones del
 * llavero que no son de la pareja en sí:
 *
 * - El compañero fijo, `LEEME.txt` ([asegurarLeeme]): con una sola base, un
 *   guardado sería «han cambiado todos los ficheros» y bisync se pararía
 *   («Safety abort: all files were changed», `cmd/bisync`). Si falta se pone,
 *   siempre con la misma hora ([LEEME_HORA]); si la persona lo cambió, no se
 *   toca.
 * - Que cada base esté entera ([rotas], con [Kdbx]): una a medio guardar no se
 *   sube. Se mira otra vez a los [ESPERA_ENTERA_MS], por si el guardado estaba
 *   acabando. [preparar] junta las dos cosas y es lo que llama la pasada.
 * - Qué hay sin subir ([pendiente], [sinListar]), contra el listado `path1` de
 *   la última pasada buena ([listadoLocal]).
 * - Lo que lee de un log de rclone ([remotoVacio], [soloCopiasBorradas]) y cómo
 *   entra el llavero del remoto ([decidirAlta], solo la rama «traer el del
 *   remoto»).
 *
 * Lo que de `llavero.py` NO entra, y por qué: todo lo que es del equipo de
 * escritorio y no del teléfono. El vigilante y su registro, los procesos de
 * KeePassXC y su `Vigilancia` (la app no lanza KeePassXC ni vigila una carpeta:
 * quien dice cuándo toca una pasada es el planificador de la app), los
 * lanzadores `Llavero.bat` y `llavero.sh`, «Combinar», «Expulsar», la copia de la
 * base (`poner_base`; el fichero llave sí, [ponerLlave]) y, de `decidir_alta`, la rama de una base propia y el chequeo del
 * cifrado (Android cuenta como cifrado). Y tampoco
 * `huella()`, que existe solo para el vigilante.
 *
 * Lo que se afirma del formato del listado está contrastado con rclone v1.75.1
 * (`cmd/bisync/listing.go`, `lineFormat`); lo que se afirma de la ventana de la
 * hora, con `timeDiffers()` (`cmd/bisync/compare.go`).
 *
 * Las regex llevan las clases de Python escritas a mano (`\s` es [CLASE_ESPACIO_PY]
 * y `\d` es `\p{Nd}`), no las de Java o ICU. Y un par de rarezas de Python se
 * dejan en lo que haría la app: una hora con el año 0 o el mes 13 hace que
 * `calendar.timegm` lance en Python (y `listado_local` se caiga entero); aquí la
 * línea no se lee, como con cualquier otra que no es una hora. Y lo mismo una
 * hora o un tamaño que no cabe en un `Long` (rclone no los escribe nunca).
 */
object Llavero {

    /** El compañero fijo de la base en `.keychain/`. */
    const val LEEME = "LEEME.txt"

    /**
     * Lo que dice `LEEME.txt`, con `\n` en todos los sistemas y en UTF-8. No se
     * cambia: un cambio viajaría a todos los dispositivos. El test lo compara
     * byte a byte con el de prdrive.
     */
    val LEEME_TEXTO: String =
        "Esta carpeta es el llavero de prdrive: la base de KeePassXC de este\n" +
            "dispositivo. prdrive la sincroniza sola con la carpeta keychain/ del\n" +
            "catálogo, en el remoto.\n" +
            "\n" +
            "No la toques a mano. Para abrir la base usa «Abrir llavero», en la ventana\n" +
            "de prdrive, o Llavero.bat: así KeePassXC se abre con la configuración que\n" +
            "viaja en la unidad y lo que guardes sube solo.\n" +
            "\n" +
            "Este fichero no cambia nunca. Está aquí para que una base guardada no\n" +
            "parezca «ha cambiado todo» a quien sincroniza.\n"

    /**
     * La hora de modificación de `LEEME.txt` en todos los dispositivos y
     * versiones, en segundos: 2000-01-01 00:00:00 UTC.
     *
     * No se cambia nunca: dos versiones con horas distintas se pisarían la hora
     * entre sí. Si cada dispositivo lo crea con la hora de ese momento, el
     * segundo en llegar parece «más nuevo» y su primer `--resync`
     * (`resync-mode = newer`) le cambia la hora al del remoto; el primer
     * dispositivo ve entonces cambiados todos sus ficheros y deja de subir
     * (#115 de prdrive). Es un número entero y par de segundos, que es lo único
     * que FAT y exFAT guardan sin redondear.
     */
    const val LEEME_HORA = 946684800L

    /** Lo que se espera antes de mirar otra vez una base que no está entera (2 s). */
    const val ESPERA_ENTERA_MS = 2000L

    private const val NS_POR_SEGUNDO = 1_000_000_000L

    /**
     * Lo que pueden diferir dos horas de la misma base para que [pendiente] las
     * dé por iguales, en nanosegundos.
     *
     * Es la ventana de rclone ([VENTANA_HORA], `modify-window`): en exFAT y
     * FAT32 la hora se redondea hacia arriba hasta 2 s, así que la base que
     * acaba de bajar `rclone` no tiene la hora de su línea del listado. Como en
     * `timeDiffers()` (`cmd/bisync/compare.go`), «igual» es MENOS que la
     * ventana, en los dos sentidos.
     */
    const val TOLERANCIA_HORA: Long = VENTANA_HORA * NS_POR_SEGUNDO

    /**
     * Una base que no está entera.
     *
     * @property fichero La base.
     * @property motivo Qué le pasa ([Kdbx.Estado.motivo]).
     */
    data class Rota(val fichero: File, val motivo: String)

    // -----------------------------------------------------------------------
    // Las bases
    // -----------------------------------------------------------------------

    /**
     * Las bases de esa carpeta que viajan, también las de un conflicto.
     *
     * Son los `*.kdbx` de la carpeta y de sus subcarpetas, salvo `.prversions/`
     * ([VERSIONS_DIR]) y las copias de antes de guardar (`*.old.kdbx`, en
     * minúsculas o no), que no viajan (`LLAVERO_REGLAS`). En el orden de
     * `sorted()` de Python sobre rutas: por partes de la ruta, no por su texto
     * (`a/x.kdbx` va antes que `a-b.kdbx` y que `a.kdbx`).
     *
     * La extensión se distingue en mayúsculas, como el filtro `+ *.kdbx` y como
     * un sistema de ficheros POSIX. No se entra en `.prversions/` (todo lo de
     * dentro quedaría descartado) ni se sigue un enlace a una carpeta.
     */
    fun bases(donde: File): List<File> {
        val hallados = ArrayList<Pair<List<String>, File>>()
        recorrer(donde, emptyList(), hallados)
        return hallados.sortedWith { a, b -> compararPartes(a.first, b.first) }.map { it.second }
    }

    private fun recorrer(carpeta: File, partes: List<String>, salida: MutableList<Pair<List<String>, File>>) {
        val hijos = carpeta.listFiles() ?: return
        for (hijo in hijos) {
            val suyas = partes + hijo.name
            if (hijo.name.endsWith(".kdbx") && !hijo.name.lowercase().endsWith(".old.kdbx")) {
                salida.add(Pair(suyas, hijo))
            }
            if (hijo.isDirectory && hijo.name != VERSIONS_DIR && !Files.isSymbolicLink(hijo.toPath())) {
                recorrer(hijo, suyas, salida)
            }
        }
    }

    private fun compararPartes(a: List<String>, b: List<String>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = compararPy(a[i], b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    /**
     * Las bases que no están enteras, mirándolas dos veces.
     *
     * Una que no está entera se vuelve a mirar a los [ESPERA_ENTERA_MS]: puede
     * ser un guardado que estaba acabando. Un formato que no se conoce no cuenta
     * como roto ([Kdbx.mirar]).
     *
     * @param dormir Espera tantos milisegundos; se pasa para que los tests no
     *   esperen de verdad.
     */
    fun rotas(donde: File, dormir: (Long) -> Unit = { Thread.sleep(it) }): List<Rota> {
        val malas = bases(donde).filter { Kdbx.comprobar(it).entera == false }
        if (malas.isEmpty()) return emptyList()
        dormir(ESPERA_ENTERA_MS)
        val salida = ArrayList<Rota>()
        for (fichero in malas) {
            val estado = Kdbx.comprobar(fichero)
            if (estado.entera == false) salida.add(Rota(fichero, estado.motivo))
        }
        return salida
    }

    // -----------------------------------------------------------------------
    // El LEEME
    // -----------------------------------------------------------------------

    /**
     * Pone `LEEME.txt` si falta, con la hora fija; si dice otra cosa, no lo toca.
     *
     * Se escriben los bytes tal cual, con `\n`, y se le pone [LEEME_HORA]: dos
     * dispositivos que lo pongan cada uno el suyo tienen que dejar el mismo
     * fichero, con la misma hora, o se pisarían en el remoto. Uno que ya estaba
     * con el texto de siempre y otra hora se corrige: es nuestro y no cambia de
     * contenido. Si dice otra cosa, ni el texto ni la hora.
     *
     * @param donde La carpeta del llavero.
     * @param corregirHora Si se le corrige la hora a uno que ya existía. Una
     *   simulación (`--dry-run`) no escribe en el dispositivo: pasa `false`, y
     *   la corrección espera a la pasada de verdad. Uno recién creado siempre
     *   sale con su hora.
     * @return `true` si lo ha escrito.
     */
    fun asegurarLeeme(donde: File, corregirHora: Boolean = true): Boolean {
        val ruta = File(donde, LEEME)
        val creado = crearExclusivo(ruta, LEEME_TEXTO.toByteArray(Charsets.UTF_8))
        if (creado != null && (creado || corregirHora) && esElLeemeNuestro(ruta)) ponerHoraFija(ruta)
        return creado == true
    }

    /**
     * Crea [ruta] con [datos] solo si no existe (`CREATE_NEW` es lo que hace de
     * esto un cerrojo: de dos que lo intentan a la vez solo uno lo crea).
     *
     * @return `true` si lo ha creado, `false` si ya estaba, `null` si no se
     *   puede escribir.
     */
    private fun crearExclusivo(ruta: File, datos: ByteArray): Boolean? = try {
        Files.newOutputStream(ruta.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            .use { salida ->
                try {
                    salida.write(datos)
                } catch (_: IOException) {
                    // vacío también cuenta como tomado
                }
            }
        true
    } catch (_: FileAlreadyExistsException) {
        false
    } catch (_: IOException) {
        null
    }

    /** Si `LEEME.txt` dice exactamente lo que dice el de prdrive. */
    private fun esElLeemeNuestro(ruta: File): Boolean = try {
        ruta.readBytes().contentEquals(LEEME_TEXTO.toByteArray(Charsets.UTF_8))
    } catch (_: IOException) {
        false
    }

    /**
     * Le pone a `LEEME.txt` su hora fija, salvo que rclone ya la dé por la
     * misma: «la misma» es la ventana de rclone ([TOLERANCIA_HORA]), y
     * reescribirla cada pasada solo gastaría escrituras. Si el sistema de
     * ficheros no deja, se queda como está: no es motivo para no sincronizar.
     */
    private fun ponerHoraFija(ruta: File) {
        val fija = LEEME_HORA * NS_POR_SEGUNDO
        try {
            val actual = Files.getLastModifiedTime(ruta.toPath()).to(TimeUnit.NANOSECONDS)
            if (abs(actual - fija) < TOLERANCIA_HORA) return
            Files.setLastModifiedTime(ruta.toPath(), FileTime.from(fija, TimeUnit.NANOSECONDS))
        } catch (_: IOException) {
            return
        }
    }

    /**
     * Prepara la carpeta del llavero para una pasada y dice si no se puede subir.
     *
     * Pone el compañero fijo y comprueba las bases. No crea la carpeta: eso lo
     * decide quien llama, con sus reglas (nunca si hay baseline).
     *
     * @param carpeta La carpeta del llavero (`.keychain/` del volumen).
     * @param simulacro Si la pasada es un `--dry-run`: no le corrige la hora a
     *   un `LEEME.txt` que ya existe ([asegurarLeeme]).
     * @param dormir Lo que espera [rotas].
     * @return El motivo para no hacer la pasada, o `null` si se puede.
     */
    fun preparar(
        carpeta: File,
        simulacro: Boolean = false,
        dormir: (Long) -> Unit = { Thread.sleep(it) },
    ): String? {
        if (!carpeta.isDirectory) return null
        asegurarLeeme(carpeta, corregirHora = !simulacro)
        val malas = rotas(carpeta, dormir)
        if (malas.isEmpty()) return null
        val lista = malas.joinToString("; ") { "${it.fichero.name}: ${it.motivo}" }
        return "la base no está entera ($lista). No se sube: puede que KeePassXC " +
            "la estuviera guardando. Se volverá a intentar en la próxima pasada."
    }

    /**
     * Crea `.keychain/` si falta, con su compañero fijo; devuelve la carpeta.
     * (En el escritorio también la oculta en Windows; aquí el punto del nombre
     * ya lo hace.)
     *
     * @param raiz La raíz del volumen.
     * @throws IOException Si no se puede crear la carpeta.
     */
    fun prepararCarpeta(raiz: File): File {
        val donde = File(raiz, LLAVERO_LOCAL)
        if (!donde.isDirectory && !donde.mkdirs() && !donde.isDirectory) {
            throw IOException("No he podido crear $donde")
        }
        asegurarLeeme(donde)
        return donde
    }

    /**
     * Copia el fichero llave de un llavero sin contraseña a `.keychain/llave.keyx`
     * (`llavero.poner_llave()`, con `poner_base()`): la única copia que guarda la
     * app, dentro del almacenamiento privado, y nunca sube (ningún filtro de
     * [LLAVERO_REGLAS] la deja pasar).
     *
     * Se copia a un temporal al lado y se renombra, de una vez: una copia cortada
     * no queda con el nombre de la llave. Se relee entera antes del renombrado y
     * tiene que ser igual a lo leído. Lo único que se retiene es una suma
     * SHA-256 para esa comprobación: la llave no se guarda en memoria, ni se
     * dice en ningún mensaje. Sea cual sea el fallo, no queda ni la llave ni el
     * temporal. Luego se deja solo legible por la app, donde el sistema de
     * ficheros lo permite.
     *
     * Es el [InputStream] y no un fichero porque en Android la llave llega de un
     * `content://`; quien lo abre es quien lo cierra.
     *
     * @param origen El contenido del fichero llave que da la persona.
     * @param raiz La raíz del volumen.
     * @return `.keychain/llave.keyx`.
     * @throws IOException Si no se puede copiar, o la copia no ha quedado igual.
     */
    fun ponerLlave(origen: InputStream, raiz: File): File {
        val destino = File(prepararCarpeta(raiz), LLAVERO_LLAVE)
        val temporal = File(destino.parentFile, "$LLAVERO_LLAVE.copiando")
        try {
            Files.deleteIfExists(temporal.toPath())
            val suma = MessageDigest.getInstance("SHA-256")
            Files.newOutputStream(temporal.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                .use { salida ->
                    val bloque = ByteArray(8192)
                    while (true) {
                        val n = origen.read(bloque)
                        if (n < 0) break
                        suma.update(bloque, 0, n)
                        salida.write(bloque, 0, n)
                    }
                }
            val releida = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(temporal.toPath()).use { entrada ->
                val bloque = ByteArray(8192)
                while (true) {
                    val n = entrada.read(bloque)
                    if (n < 0) break
                    releida.update(bloque, 0, n)
                }
            }
            if (!MessageDigest.isEqual(suma.digest(), releida.digest())) {
                throw IOException("la copia del fichero llave no ha quedado igual")
            }
            try {
                Files.move(
                    temporal.toPath(), destino.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporal.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            Files.deleteIfExists(temporal.toPath())
            throw e
        }
        // Solo para la app, como el `chmod 0o600`; donde no se pueda, se queda como está.
        destino.setReadable(false, false)
        destino.setReadable(true, true)
        destino.setWritable(false, false)
        destino.setWritable(true, true)
        return destino
    }

    // -----------------------------------------------------------------------
    // El listado de la última pasada buena
    // -----------------------------------------------------------------------

    /**
     * Una línea de un listado de bisync: banderas, tamaño, hash, id, hora y
     * nombre. Es el `lineFormat` de `cmd/bisync/listing.go` («%s %8d %s %s %s
     * %q»), visto con rclone v1.75.1:
     * `-        5 - - 2026-10-05T06:44:29.752645891+0000 "personal.kdbx"`.
     *
     * Es la `_LINEA` de Python con su `\S`, `\s` y `\d`, y el `.` y el `$` como
     * los entiende Python (ver «Ojo con Android» en AGENTS.md).
     */
    private val LINEA: Regex by lazy {
        val e = "[$CLASE_ESPACIO_PY]"
        val n = "[^$CLASE_ESPACIO_PY]"
        Regex("$n+$e+(-?\\p{Nd}+)$e+$n+$e+$n+$e+($n+)$e+(\"[^\\n]*\")\\n?")
    }

    /**
     * La hora de un listado: con los nanosegundos y la zona
     * (`2006-01-02T15:04:05.000000000-0700`).
     */
    private val HORA: Regex by lazy {
        val d = "\\p{Nd}"
        Regex("($d{4})-($d{2})-($d{2})T($d{2}):($d{2}):($d{2})(?:\\.($d{1,9}))?([+-])($d{2}):?($d{2})\\n?")
    }

    /** Convierte la hora de un listado en nanosegundos desde 1970, o `null` si no se lee. */
    private fun nanosegundos(texto: String): Long? {
        val m = HORA.matchEntire(texto) ?: return null
        val g = m.groupValues
        val ano = intPy(g[1]) ?: return null
        val mes = intPy(g[2]) ?: return null
        val dia = intPy(g[3]) ?: return null
        val hh = intPy(g[4]) ?: return null
        val mm = intPy(g[5]) ?: return null
        val ss = intPy(g[6]) ?: return null
        val cifras = g[7].ifEmpty { "0" }
        // `ljust(9, "0")` cuenta caracteres, no unidades UTF-16.
        val fraccion = intPy(cifras + "0".repeat(9 - cifras.codePointCount(0, cifras.length))) ?: return null
        val signo = if (g[8] == "-") -1L else 1L
        val zona = signo * ((intPy(g[9]) ?: return null) * 3600 + (intPy(g[10]) ?: return null) * 60)
        // `calendar.timegm` no valida el día, la hora ni los minutos (el 31 de
        // febrero es el 3 de marzo), pero sí el año y el mes (`datetime.date`).
        if (ano !in 1..9999 || mes !in 1..12) return null
        val dias = LocalDate.of(ano.toInt(), mes.toInt(), 1).toEpochDay() + dia - 1
        val segundos = ((dias * 24 + hh) * 60 + mm) * 60 + ss - zona
        return try {
            Math.addExact(Math.multiplyExact(segundos, NS_POR_SEGUNDO), fraccion)
        } catch (_: ArithmeticException) {
            null
        }
    }

    /**
     * Lee el listado `path1` de la última pasada buena del lado local.
     *
     * Las líneas que no son de bisync, o cuyo nombre o hora no se leen, se
     * saltan. Un fichero que no está o que no es UTF-8 es «no hay listado».
     *
     * @return `{ruta: (tamaño, mtime_ns)}`, o `null` si no hay listado o no se lee.
     */
    fun listadoLocal(listado: File): Map<String, Pair<Long, Long>>? {
        val texto = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(listado.readBytes()))
                .toString()
        } catch (_: IOException) {
            return null
        } catch (_: CharacterCodingException) {
            return null
        }
        val salida = LinkedHashMap<String, Pair<Long, Long>>()
        for (linea in splitlinesPy(texto)) {
            val m = LINEA.matchEntire(linea) ?: continue
            val nombre = try {
                leerJson(m.groupValues[3])
            } catch (_: JsonError) {
                continue
            }
            val hora = nanosegundos(m.groupValues[2])
            val tamano = intPy(m.groupValues[1])
            if (nombre is String && hora != null && tamano != null) salida[nombre] = Pair(tamano, hora)
        }
        return salida
    }

    /**
     * Dónde deja bisync el listado `path1` de esa pareja:
     * `<workdir>/<prefijo de sesión>.path1.lst`.
     */
    fun listadoDe(volumen: Volumen, pareja: Pareja, tipos: Map<String, Bisync.TipoRemote>): File =
        File(volumen.workdir(pareja), Bisync.expectedPrefix(pareja, tipos) + Bisync.PATH1_SUFFIX)

    private fun rutaRelativa(donde: File, fichero: File): String =
        fichero.relativeTo(donde).invariantSeparatorsPath

    /**
     * Si hay algo del llavero sin subir.
     *
     * Es que alguna base no coincide con su línea del listado `path1` de la
     * última pasada buena: otro tamaño, o una hora que difiere en
     * [TOLERANCIA_HORA] o más. No hace falta otro registro, y pilla también lo
     * que se guardó DURANTE una pasada. Sin listado (no ha habido pasada buena)
     * cualquier base está pendiente.
     *
     * @param carpeta La carpeta del llavero.
     * @param listado El listado `path1` ([listadoDe]).
     */
    fun pendiente(carpeta: File, listado: File): Boolean {
        val lista = bases(carpeta)
        if (lista.isEmpty()) return false
        val visto = listadoLocal(listado) ?: return true
        for (fichero in lista) {
            val atributos = try {
                Files.readAttributes(fichero.toPath(), BasicFileAttributes::class.java)
            } catch (_: IOException) {
                return true
            }
            val linea = visto[rutaRelativa(carpeta, fichero)] ?: return true
            val hora = atributos.lastModifiedTime().to(TimeUnit.NANOSECONDS)
            if (linea.first != atributos.size() || abs(hora - linea.second) >= TOLERANCIA_HORA) return true
        }
        return false
    }

    /**
     * Las bases de la carpeta que no están en el listado de la última pasada
     * buena.
     *
     * Es por un fallo de rclone (v1.75.1, y sigue en `master`): tras un
     * conflicto que gana el remoto, `modifyListing()` (`cmd/bisync/listing.go`)
     * apunta el renombrado del perdedor de este lado quitando el nombre de la
     * base de los dos listados, aunque la copia del ganador ya está con ese
     * nombre. La pasada siguiente la ve «nueva en los dos lados»: si son iguales
     * no pasa nada, pero si la base ha cambiado aquí entretanto sale otro
     * conflicto, con una copia que no trae nada nuevo. Sin listado, nada: no ha
     * habido pasada buena.
     */
    fun sinListar(carpeta: File, listado: File): List<String> {
        val visto = listadoLocal(listado) ?: return emptyList()
        return bases(carpeta).map { rutaRelativa(carpeta, it) }.filter { it !in visto }
    }

    // -----------------------------------------------------------------------
    // Los frenos de rclone y el alta desde el remoto (llavero.py)
    // -----------------------------------------------------------------------

    /**
     * Lo que dice bisync cuando el remoto (Path2) no trae nada que sincronizar
     * (`llavero._REMOTO_VACIO`).
     *
     * Es `checkListing()` (`cmd/bisync/listing.go`) sobre el listado de ahora,
     * desde `findDeltas()`: «Empty current Path2 listing. Cannot sync to an empty
     * directory: <listado>», código 7. Con `--resilient` el error es «retryable
     * without --resync», no aparta los listados a `.lst-err`, y cada pasada
     * siguiente falla igual.
     */
    private const val REMOTO_VACIO = "Empty current Path2 listing"

    /**
     * Si la pasada se ha parado porque la carpeta del llavero en el remoto está
     * vacía o no está (`llavero.remoto_vacio()`). Pasa si alguien la borra a mano
     * en el remoto: con baseline la pasada es normal, nadie vuelve a crear
     * `keychain/` y rclone no sincroniza contra un lado vacío. Solo el remoto: el
     * lado del dispositivo no se queda vacío nunca, porque [preparar] pone
     * `LEEME.txt` antes de cada pasada.
     */
    fun remotoVacio(log: String): Boolean = REMOTO_VACIO in log

    /** El `--max-delete` (un %) de la pasada que se repite cuando lo borrado son solo copias. */
    const val FRENO_SIN_COPIAS = 100L

    /**
     * Una línea de bisync que dice que algo del listado anterior ya no está en
     * ese lado (`llavero._BORRADO`).
     *
     * Es `b.indent(msg, file, "File was deleted")` de `findDeltas()`
     * (`cmd/bisync/deltas.go`) con el formato «- %-18s%-43s - %s» de `indent()`
     * (`cmd/bisync/log.go`); el nombre va entre comillas si tiene algo no
     * imprimible (`escapePath()`). Las líneas ya vienen de [splitlinesPy], así
     * que no llevan saltos y el `(.*)$` de Python es aquí `[^\n]*`.
     */
    private val BORRADO = Regex(": - (Path[12]) +File was deleted +- ([^\\n]*)")

    /**
     * Lo que bisync ve borrado, si son solo copias de conflicto de una base
     * (`llavero.solo_copias_borradas()`).
     *
     * Es para el freno de borrados (`--max-delete`, un % del listado anterior
     * que vale para los dos lados): el llavero tiene tres o cuatro ficheros, así
     * que quitar una copia de conflicto ya pasa del 25 % y la pasada aborta con
     * «too many deletes» una y otra vez. Esas copias se pueden borrar, porque el
     * backup-dir guarda la de cada lado. Una base o `LEEME.txt` no: si están
     * entre lo borrado, el freno sigue mandando.
     *
     * @return los nombres, si todo lo borrado es copia de conflicto de una base,
     *   o `null` si no hay nada borrado o algo no lo es.
     */
    fun soloCopiasBorradas(log: String, pareja: Pareja): List<String>? {
        val nombres = splitlinesPy(log).mapNotNull { BORRADO.find(it)?.groupValues?.get(2) }
        if (nombres.isEmpty()) return null
        val esq = Conflictos.esquema(pareja)
        for (nombre in nombres) {
            val leido = if (nombre.startsWith('"') || '/' in nombre) null else Conflictos.leerNombre(nombre, esq)
            if (leido == null || !leido.original.endsWith(".kdbx")) return null
        }
        return nombres
    }

    /**
     * El `[keychain]` con el que entra el llavero cuando se trae el del remoto
     * (`llavero.decidir_alta()` con `origen = None`): una copia de la tabla del
     * catálogo, y la base es la suya. Sin ella, no hay nada que traer.
     *
     * No comprueba el cifrado, que en prdrive hace falta si el llavero va sin
     * contraseña (`llave_interna`): Android cuenta como cifrado.
     *
     * @throws ConfigError si el remoto no tiene llavero.
     */
    fun decidirAlta(remota: Map<String, Any?>?): Map<String, Any?> {
        if (remota == null) {
            throw ConfigError("El remoto no tiene llavero que traer: activa el llavero con una base.")
        }
        return LinkedHashMap(remota)
    }
}
