package prdrive.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * OrdenTest.kt — La orden de cada pareja, trozo a trozo, como la monta prdrive.
 *
 * La app no ejecuta esta orden: la traduce a una llamada rc. Pero se monta
 * igual y se compara con la del escritorio, porque es ahí donde se decide qué
 * flag gana, qué se quita en una pasada normal (`--resync-mode`, que la
 * convertiría en un resync) y qué se pone en una forzada o con versiones.
 */
class OrdenTest {

    private val orden get() = Vectores.objeto(Vectores.seccion("paso3"), "orden")

    @Test
    fun `las capas de los flags se llaman igual`() {
        val o = Vectores.objeto(orden, "origenes")
        assertEquals(
            listOf(o["base"], o["modo"], o["defaults"], o["pareja"], o["prdrive"], o["patrones"], o["extra"]),
            listOf(ORIGEN_BASE, ORIGEN_MODO, ORIGEN_DEFAULTS, ORIGEN_PAREJA, ORIGEN_PRDRIVE, ORIGEN_PATRONES, ORIGEN_EXTRA),
        )
    }

    @Test
    fun `cada pareja monta la misma orden que en el escritorio, en cada pasada`() {
        val config = parseConfig(Vectores.objeto(orden, "config"))
        val casos = Vectores.objetos(orden, "pasadas")
        // Que no se cuele un caso sin comprobar si cambian las parejas.
        assertEquals(config.pairs.size * casos.map { it["pasada"] }.distinct().size, casos.size)
        for (caso in casos) {
            val p = config.pairs.first { it.name == caso["pareja"] }
            val kw = Vectores.objeto(caso, "kw")
            val donde = "«${p.name}», pasada ${caso["pasada"]}"
            val tramos = Orden.tramos(
                p, binario = "rclone", filtros = caso["filtros"]?.toString(),
                config = Vectores.texto(orden, "conf"), logfile = Vectores.texto(orden, "log"),
                workdir = Vectores.texto(caso, "workdir"),
                simulacro = kw["simulacro"] == true, resync = kw["resync"] == true,
                sello = Vectores.texto(orden, "sello"), forzar = kw["forzar"] == true,
            )
            assertEquals(
                Vectores.lista(caso, "tramos"),
                tramos.map { linkedMapOf("args" to it.args, "origen" to it.origen, "nombre" to it.nombre) },
                donde,
            )
            assertEquals(
                caso["pasada_forzada"],
                Orden.pasadaForzada(p, kw["forzar"] == true, kw["resync"] == true),
                "$donde: forzada",
            )
            assertEquals(caso["max_delete_efectivo"], Orden.maxDeleteEfectivo(p), "$donde: max-delete")
        }
    }

    @Test
    fun `el resync-mode de la pasada manda en un resync y no sale en una pasada normal`() {
        val p = parseConfig(
            mapOf(
                "defaults" to mapOf("device_remote" to "disp", "remote" to "nas"),
                "pair" to listOf(
                    mapOf(
                        "name" to "vault", "local" to "vault", "remote_path" to "/copia/vault",
                        "flags" to mapOf("resync-mode" to "newer"),
                        "extra_flags" to listOf("--resync-mode=older"),
                    ),
                ),
            ),
        ).pairs.single()

        fun modos(resync: Boolean): List<String> {
            val args = Orden.ordenRclone(
                Orden.tramos(
                    p, binario = "rclone", filtros = null, config = "rclone.conf", logfile = "",
                    workdir = "state/vault", resync = resync, resyncMode = Mudanza.RESYNC_MODE,
                ),
            )
            val salida = ArrayList<String>()
            for ((i, arg) in args.withIndex()) {
                when {
                    arg.startsWith("--resync-mode=") -> salida.add(arg.substringAfter('='))
                    arg == "--resync-mode" -> salida.add(args[i + 1])
                }
            }
            return salida
        }

        assertEquals(listOf(Mudanza.RESYNC_MODE), modos(resync = true))
        assertEquals(emptyList(), modos(resync = false))
    }
}
