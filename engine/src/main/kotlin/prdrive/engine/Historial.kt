package prdrive.engine

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Historial.kt — El diario de pasadas: cómo fueron las últimas de cada pareja.
 *
 * Espejo de `common/historial.py` de prdrive. [Resultados] guarda UNA pasada por
 * pareja, la última, y cada una pisa a la anterior: se sabe que una pareja
 * falla, pero no desde cuándo ni si falló una vez o falla siempre. Esto añade
 * una línea por pasada en `state/historial.jsonl`, con las últimas [POR_PAREJA]
 * de cada pareja, que dice desde cuándo falla ([racha]).
 *
 * Lo escribe [Pasada] junto a `Resultados.apuntar()` y con su mismo criterio: ni
 * los simulacros ni las parejas saltadas son un resultado. Y con la regla de
 * todo lo de `state/`: leer nunca es un error (una línea a medias por un corte,
 * o basura, se salta) y escribir nunca tumba una pasada.
 *
 * El fichero es el mismo `state/historial.jsonl` del escritorio, con el mismo
 * formato (una línea de `json.dumps(..., ensure_ascii=False)` por pasada), y las
 * fechas son [Store.stamp], como en el resto de `state/`. La carpeta se pasa: el
 * motor no sabe dónde está el volumen.
 *
 * Lo que no es de Python, y por qué:
 *
 * - `codigo` y `transferido` son [BigInteger]: en Python son enteros sin tope y
 *   salen de un JSON que puede traer cualquiera. Un `Long` los truncaría, y un
 *   código de cuarenta cifras es un fallo igual que un 1.
 * - Apuntar y recortar van con un cerrojo ([cerrojo]). El escritorio avisa de que
 *   «no protege una pasada que apunte entre la lectura y la reescritura desde
 *   otro proceso»; aquí las pasadas del llavero en segundo plano y las de la
 *   pantalla corren en hilos del mismo proceso, y perder una línea por eso no
 *   hace falta.
 */
object Historial {

    /**
     * Cuántas pasadas de cada pareja se guardan.
     *
     * Con el servicio cada media hora son un día; con pasadas a mano o al
     * enchufar, semanas. Lo que se pregunta es si una pareja falla de vez en
     * cuando o cada vez, y desde cuándo; para eso cincuenta bastan, y una racha
     * más larga se cuenta igual con «al menos».
     */
    const val POR_PAREJA = 50

    /**
     * Pasadas de una pareja a partir de las cuales se recorta el diario.
     *
     * Lo normal es AÑADIR una línea (abrir en modo `append` escribe al final y
     * nada más). Recortar es reescribirlo entero, y en una memoria flash lo que
     * se paga es lo que se escribe. Por eso no se recorta en cada pasada: se deja
     * crecer hasta que alguna pareja tiene el DOBLE de las que se guardan y
     * entonces cada una se deja en [POR_PAREJA]. Leer no gasta el dispositivo, y
     * por eso contar sí se hace en cada pasada.
     */
    const val RECORTE = 2 * POR_PAREJA

    /**
     * Tamaño del fichero a partir del cual se recorta, y se deja en la mitad.
     *
     * Es la red de seguridad de [RECORTE], que solo cuenta parejas: las que ya no
     * existen (renombradas, borradas) conservan sus [POR_PAREJA] para siempre y
     * una línea que no se entiende no es de ninguna. Recortar hasta la mitad
     * evita que el siguiente recorte llegue enseguida.
     */
    const val TOPE_BYTES = 1024 * 1024

    /** `state/historial.jsonl`. */
    fun ruta(stateDir: File): File = File(stateDir, "historial.jsonl")

    /**
     * Una pasada apuntada en el diario.
     *
     * @property pareja Nombre de la pareja.
     * @property inicio [Store.stamp] de cuando empezó.
     * @property codigo Lo que devolvió la pasada; 0 es bien.
     * @property segundos Lo que duró, o `null` si no consta.
     * @property transferido Bytes que movió rclone, o `null` si no se sabe. No
     *   hay ficheros movidos ni borrados: no son un número que esté siempre.
     */
    data class Pasada(
        val pareja: String,
        val inicio: String,
        val codigo: BigInteger,
        val segundos: Double? = null,
        val transferido: BigInteger? = null,
    ) {
        /** Si la pasada acabó bien. */
        val ok: Boolean get() = codigo.signum() == 0
    }

    /**
     * Mide cuándo empezó una pasada, para apuntar su inicio y su duración.
     *
     * El inicio es la hora de pared, como todo lo de `state/`; la duración sale
     * de un reloj monótono, que no salta si el teléfono cambia la hora a mitad
     * de una pasada (horario de verano, sincronización de la hora). Los dos
     * relojes se pasan, para que los tests no dependan de la hora que sea.
     *
     * @param sello Cómo se saca la fecha de inicio.
     * @param monotono Segundos de un reloj que solo avanza (`time.monotonic()`).
     */
    class Reloj(
        sello: () -> String = Store::stamp,
        private val monotono: () -> Double = ::monotonoReal,
    ) {
        /** El [Store.stamp] de cuando empezó. */
        val inicio: String = sello()
        private val t0: Double = monotono()

        /** La [Pasada] de esa pareja con la duración medida hasta ahora. */
        fun pasada(pareja: String, codigo: Int, transferido: Long? = null): Pasada =
            Pasada(
                pareja, inicio, BigInteger.valueOf(codigo.toLong()),
                redondeado(monotono() - t0), transferido?.let { BigInteger.valueOf(it) },
            )
    }

    private fun monotonoReal(): Double = System.nanoTime() / 1e9

    /**
     * `round(x, 1)` de Python: el decimal más cercano al valor EXACTO del double,
     * y a igual distancia el par. Se redondea el `BigDecimal` exacto y no
     * `Math.round(x * 10) / 10.0`, que multiplica antes y se equivoca en lo que
     * `0.15` o `2.675` guardan de más o de menos.
     */
    internal fun redondeado(x: Double): Double {
        if (x.isNaN() || x.isInfinite()) return x
        val r = BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toDouble()
        // BigDecimal no tiene cero negativo y round() sí: -0.04 sale -0.0.
        return if (r == 0.0) Math.copySign(0.0, x) else r
    }

    /**
     * La pasada que se apunta, con su [reloj] si lo hay.
     *
     * Sin reloj (una pareja que se cortó antes de llegar a rclone, cuando un
     * error de E/S a mitad de una pasada de varias deja a las de detrás sin
     * hacerse) consta con la hora de ahora ([sello]) y sin duración: un cero
     * sería inventado, y la pareja tiene que constar igual porque ese fallo
     * también es de la racha.
     */
    fun pasada(
        pareja: String,
        codigo: Int,
        reloj: Reloj? = null,
        transferido: Long? = null,
        sello: () -> String = Store::stamp,
    ): Pasada =
        if (reloj == null) {
            Pasada(pareja, sello(), BigInteger.valueOf(codigo.toLong()), null, transferido?.let { BigInteger.valueOf(it) })
        } else {
            reloj.pasada(pareja, codigo, transferido)
        }

    /**
     * La línea JSON de una pasada, con su salto de línea: lo que escribe
     * `json.dumps({...}, ensure_ascii=False)` (separadores `", "` y `": "`, sin
     * sangría; la de [escribirJson] es la de `indent=1`, que no es esta). Son
     * cinco valores sueltos, y cada uno se escribe como lo haría `json`.
     */
    fun linea(p: Pasada): String =
        "{\"pareja\": " + escribirJson(p.pareja) +
            ", \"inicio\": " + escribirJson(p.inicio) +
            ", \"codigo\": " + escribirJson(p.codigo) +
            ", \"segundos\": " + escribirJson(p.segundos) +
            ", \"transferido\": " + escribirJson(p.transferido) + "}\n"

    /**
     * Un entero de verdad de un JSON leído, o `null`. En Python `true` llega como
     * un `int`; aquí un booleano no es ni `Long` ni [BigInteger], y un `1.0` o un
     * `1e2` (que son `float` en Python) son `Double`: ni uno ni otro valen.
     */
    private fun entero(valor: Any?): BigInteger? = when (valor) {
        is Long -> BigInteger.valueOf(valor)
        is BigInteger -> valor
        else -> null
    }

    /**
     * Una duración legible, o `null`. `json` acepta `NaN` e `Infinity`, que no son
     * una duración. Un entero de cuatrocientas cifras no cabe en un double
     * (`float()` lanza `OverflowError` y aquí sale infinito): tampoco.
     */
    private fun segundos(valor: Any?): Double? {
        val s: Double = when (valor) {
            is Long -> valor.toDouble()
            is BigInteger -> valor.toDouble()
            is Double -> valor
            else -> return null
        }
        // `s >= 0.0` con double primitivo: el -0.0 pasa, como `-0.0 >= 0` en Python.
        return if (!s.isNaN() && !s.isInfinite() && s >= 0.0) s else null
    }

    /**
     * Convierte una línea del diario en su [Pasada].
     *
     * Se salta lo que no es JSON (la línea que un corte dejó a medias) y lo que
     * lo es pero no trae lo que hace falta: sin pareja, sin fecha legible o sin
     * código no dice nada de ninguna pasada. Lo accesorio (duración, bytes) que
     * no encaje se lee como «no consta».
     *
     * @return La pasada, o `null` si la línea no lo es entera.
     */
    fun deLinea(texto: String): Pasada? {
        val dato = try {
            leerJson(texto)
        } catch (_: JsonError) {
            return null
        } catch (_: StackOverflowError) { // el RecursionError de Python: «[[[[…» anidado sin fin
            return null
        }
        if (dato !is Map<*, *>) return null
        val pareja = dato["pareja"] as? String
        if (pareja.isNullOrEmpty()) return null
        val inicio = dato["inicio"] as? String
        if (inicio == null || Store.desdeSello(inicio) == null) return null
        val codigo = entero(dato["codigo"]) ?: return null
        val transferido = entero(dato["transferido"])?.takeIf { it.signum() >= 0 }
        return Pasada(pareja, inicio, codigo, segundos(dato["segundos"]), transferido)
    }

    /**
     * Todas las pasadas apuntadas, en el orden en que se apuntaron.
     *
     * Nunca falla: sin fichero no hay pasadas y una línea que no se entiende se
     * salta sin arrastrar a las demás. Se parte por `\n` y no con
     * [splitlinesPy], que también corta por separadores de Unicode que un nombre
     * podría llevar (y `lines()` de Kotlin, por `\r`).
     */
    fun leer(stateDir: File): List<Pasada> {
        val crudo = try {
            ruta(stateDir).readBytes()
        } catch (_: IOException) {
            return emptyList()
        }
        return String(crudo, Charsets.UTF_8).split('\n')
            .filter { stripPy(it).isNotEmpty() }
            .mapNotNull { deLinea(it) }
    }

    /**
     * Apuntar y recortar van de una vez: dos hilos que apuntan a la vez no se
     * pisarían (cada línea es una sola escritura al final), pero un recorte que
     * lee y reescribe sí se llevaría la línea que otro añadió entre medias.
     */
    private val cerrojo = Any()

    /**
     * Añade una pasada al diario.
     *
     * Nunca lanza: si el dispositivo no deja escribir (lleno, de solo lectura o
     * ausente) la línea se pierde, pero una sincronización no puede caerse por su
     * propio diario. Si la última línea quedó a medias (un corte a mitad de
     * escribirla), la nueva empieza en una línea propia: pegada detrás se
     * perderían las dos.
     *
     * @return `true` si ha quedado escrita.
     */
    fun apuntar(stateDir: File, pasada: Pasada): Boolean = synchronized(cerrojo) {
        val fichero = ruta(stateDir)
        try {
            val prefijo = if (terminaEnSalto(fichero)) "" else "\n"
            // Una sola escritura (el prefijo y la línea juntos) y en modo `append`.
            FileOutputStream(fichero, true).use {
                it.write((prefijo + linea(pasada)).toByteArray(Charsets.UTF_8))
            }
        } catch (_: IOException) {
            return@synchronized false
        }
        recortarSiToca(stateDir)
        true
    }

    /** Si el fichero está vacío, no existe o acaba en `\n`. Lanza si es una carpeta o no se deja leer. */
    private fun terminaEnSalto(fichero: File): Boolean {
        if (!fichero.exists()) return true
        RandomAccessFile(fichero, "r").use {
            val largo = it.length()
            if (largo == 0L) return true
            it.seek(largo - 1)
            return it.read() == '\n'.code
        }
    }

    /**
     * Las líneas que quedan tras un recorte.
     *
     * Son las últimas [POR_PAREJA] de cada pareja, en su orden, y como mucho la
     * mitad de [TOPE_BYTES], quitando por lo más viejo.
     */
    fun recortadas(pasadas: List<Pasada>): List<String> {
        val vistas = HashMap<String, Int>()
        val quedan = ArrayList<String>()
        for (p in pasadas.asReversed()) {
            val n = vistas[p.pareja] ?: 0
            if (n < POR_PAREJA) {
                vistas[p.pareja] = n + 1
                quedan.add(linea(p))
            }
        }
        quedan.reverse()
        var total = 0L
        for (l in quedan) total += bytesDe(l)
        var inicio = 0
        while (inicio < quedan.size && total > TOPE_BYTES / 2) {
            total -= bytesDe(quedan[inicio])
            inicio++
        }
        return quedan.subList(inicio, quedan.size).toList()
    }

    private fun bytesDe(linea: String): Long = linea.toByteArray(Charsets.UTF_8).size.toLong()

    /**
     * Reescribe el diario si una pareja o el fichero pasan de su tope.
     *
     * La reescritura es atómica ([Store.writeText]): un corte a mitad deja el
     * fichero de antes, no uno a medias.
     *
     * @return `true` si lo ha reescrito.
     */
    fun recortarSiToca(stateDir: File): Boolean = synchronized(cerrojo) {
        val fichero = ruta(stateDir)
        if (!fichero.exists()) return@synchronized false
        val tamano = fichero.length()
        val pasadas = leer(stateDir)
        val porPareja = pasadas.groupingBy { it.pareja }.eachCount()
        if (tamano <= TOPE_BYTES && porPareja.values.all { it <= RECORTE }) return@synchronized false
        Store.writeText(fichero, recortadas(pasadas).joinToString(""))
    }

    /**
     * La racha de fallos en la que está una pareja, según el diario.
     *
     * @property desde Inicio de la primera pasada fallida de la racha.
     * @property exacta `true` si antes de esa pasada consta una buena: la racha
     *   empezó ahí. Si no, empezó ahí O ANTES (el diario no llega más atrás, o se
     *   estrenó con la pareja ya fallando) y eso no se sabe.
     * @property buenas De las [pasadas] últimas, cuántas fueron bien.
     * @property pasadas Cuántas se cuentan, hasta [POR_PAREJA].
     */
    data class Racha(
        val desde: String,
        val exacta: Boolean,
        val buenas: Int,
        val pasadas: Int,
    )

    /**
     * La racha de las pasadas de UNA pareja, en orden.
     *
     * @return La racha, o `null` si la última pasada que consta fue bien o no
     *   consta ninguna.
     */
    fun racha(pasadas: List<Pasada>): Racha? {
        if (pasadas.isEmpty() || pasadas.last().ok) return null
        var primera = pasadas.size - 1
        while (primera > 0 && !pasadas[primera - 1].ok) primera--
        val ultimas = pasadas.takeLast(POR_PAREJA)
        return Racha(pasadas[primera].inicio, primera > 0, ultimas.count { it.ok }, ultimas.size)
    }

    /**
     * Las rachas de esas parejas, leyendo el diario una sola vez. Las parejas que
     * no están fallando no salen; salen en el orden en que aparecen en el diario.
     */
    fun rachas(stateDir: File, nombres: Iterable<String>): Map<String, Racha> {
        val buscadas = nombres.toSet()
        val porPareja = LinkedHashMap<String, MutableList<Pasada>>()
        for (p in leer(stateDir)) {
            if (p.pareja in buscadas) porPareja.getOrPut(p.pareja) { ArrayList() }.add(p)
        }
        val salida = LinkedHashMap<String, Racha>()
        for ((nombre, pasadas) in porPareja) {
            racha(pasadas)?.let { salida[nombre] = it }
        }
        return salida
    }
}
