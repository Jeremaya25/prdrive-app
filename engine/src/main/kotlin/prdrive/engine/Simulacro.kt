package prdrive.engine

/**
 * Simulacro.kt — Lo que diría una pasada antes de hacerla: el log de una pasada
 * con `--dry-run` ([Pasada.Contexto.simulacro]) leído como un plan, qué se
 * copiaría y qué se borraría, para enseñarlo antes de que pase.
 *
 * No refleja a prdrive: el escritorio enseña el log del simulacro tal cual, y
 * aquí se cuenta. Lo que se borraría es [Pasada.borrarian] (`frenos.borrarian()`,
 * ese sí de prdrive); lo demás sale de las líneas de rclone v1.75.2:
 * - cada operación que `--dry-run` se salta escribe
 *   `NOTICE: <ruta>: Skipped <qué> as --dry-run is set`
 *   (fs/operations/operations.go, `SkipDestructive`), con la ruta relativa a la
 *   raíz de su lado;
 * - bisync, además, dice qué va a hacer con cada fichero antes de hacerlo
 *   (cmd/bisync/deltas.go, con `indent` de cmd/bisync/log.go:
 *   `- %-18s%-43s - %s`), y en un simulacro lo escribe como NOTICE: de ahí sale
 *   hacia qué lado va cada copia y en qué lado se borra. Path1 es el teléfono y
 *   Path2 el remoto (`Orden`: `local` primero).
 *
 * En un espejo o una copia el lado lo dice el modo; en bisync, la cola.
 */
object Simulacro {

    /**
     * El plan: [copias] y [borrados] por su ruta, sin repetir y en el orden de
     * Python (rclone los escribe en el orden en que llegan sus hilos). En bisync,
     * además, cuántas copias [subirian] (al remoto) y [bajarian] (al teléfono), y
     * cuántos borrados serían en el teléfono y en el remoto.
     */
    data class Plan(
        val copias: List<String> = emptyList(),
        val borrados: List<String> = emptyList(),
        val subirian: Int = 0,
        val bajarian: Int = 0,
        val borrariaTelefono: Int = 0,
        val borrariaRemoto: Int = 0,
    ) {
        /** Si no haría nada. */
        val vacio: Boolean get() = copias.isEmpty() && borrados.isEmpty() && subirian + bajarian == 0 &&
            borrariaTelefono + borrariaRemoto == 0
    }

    /** `Skipped copy`: lo que se copiaría (no las renombradas a versiones ni las horas). */
    private val COPIA = Regex("NOTICE: ([^\\n]*?): Skipped copy as --dry-run is set")

    /** Una línea de la cola de bisync: el lado y lo que va a hacer. */
    private val COLA = Regex("- (Path[12]) +Queue (copy to Path[12]|delete) +- ")

    fun leer(log: String): Plan {
        var subirian = 0
        var bajarian = 0
        var enTelefono = 0
        var enRemoto = 0
        for (m in COLA.findAll(log)) {
            val (lado, que) = m.destructured
            when {
                que == "copy to Path2" -> subirian++
                que == "copy to Path1" -> bajarian++
                lado == "Path1" -> enTelefono++
                else -> enRemoto++
            }
        }
        return Plan(
            copias = COPIA.findAll(log).map { it.groupValues[1] }.toSet().sortedWith(ORDEN_PY),
            borrados = Pasada.borrarian(log),
            subirian = subirian,
            bajarian = bajarian,
            borrariaTelefono = enTelefono,
            borrariaRemoto = enRemoto,
        )
    }
}
