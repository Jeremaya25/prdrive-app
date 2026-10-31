package prdrive.engine

import java.io.File

/**
 * Ayudas.kt — Lo que comparten los tests de la pasada: el rclone de mentira y
 * el baseline que deja una pareja lista para una pasada normal.
 */

/**
 * Un rclone de mentira: apunta lo que le piden (también los `mkdir`) y
 * contesta, por orden, lo que haya en [cola]; acabada la cola, [final]. En cada
 * llamada da por el progreso lo que haya en [progreso] (lo que `core/stats`
 * daría mientras corre).
 */
internal class RcloneFalso(
    var final: Pasada.Final = Pasada.Final(true, "", "INFO : Bisync successful\n"),
    vararg cola: Pasada.Final,
) : Pasada.Rclone {
    val llamadas = ArrayList<Pasada.Llamada>()
    val mkdirs = ArrayList<String>()
    val progreso = ArrayList<Map<String, Any?>>()
    private val pendientes = ArrayDeque(cola.toList())
    override fun ejecutar(llamada: Pasada.Llamada, alProgreso: (Map<String, Any?>) -> Unit): Pasada.Final {
        llamadas.add(llamada)
        progreso.forEach(alProgreso)
        return pendientes.removeFirstOrNull() ?: final
    }

    override fun mkdir(destino: String): Pasada.Final {
        mkdirs.add(destino)
        return Pasada.Final(true, "", "")
    }
}

/**
 * Deja la pareja con baseline: los dos listados con [nombres] y el hash de
 * sus filtros, para que la pasada sea normal y no un resync.
 */
internal fun conBaseline(v: Volumen, p: Pareja, nombres: List<String>) {
    val prefijo = Bisync.expectedPrefix(p, Bisync.tiposDeRemote(v.leerRcloneConf()))
    val workdir = v.workdir(p).apply { mkdirs() }
    // El `lineFormat` de cmd/bisync/listing.go, que es lo que lee `listado_local()`.
    val lineas = nombres.joinToString("") { "-        5 - - 2026-10-05T06:44:29.752645891+0000 \"$it\"\n" }
    File(workdir, prefijo + Bisync.PATH1_SUFFIX).writeText("# bisync listing v1\n$lineas")
    File(workdir, prefijo + Bisync.PATH2_SUFFIX).writeText("# bisync listing v1\n$lineas")
    Bisync.filtersFileFor(p, v.filtros)!!.let { f ->
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(f.readBytes())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        File(f.path + ".md5").writeText(md5)
    }
}
