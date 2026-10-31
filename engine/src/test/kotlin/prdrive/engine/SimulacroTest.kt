package prdrive.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SimulacroTest.kt — Lo que se lee del log de una pasada con `--dry-run`: qué
 * se copiaría y qué se borraría, y en bisync, hacia qué lado. Las líneas son las
 * que escribe rclone v1.75.2: `Skipped <qué> as --dry-run is set`
 * (fs/operations/operations.go, `SkipDestructive`) y las de la cola de bisync
 * (cmd/bisync/log.go, `indent`: `- %-18s%-43s - %s`).
 */
class SimulacroTest {

    @Test
    fun `un espejo cuenta lo que copiaria y lo que borraria, sin repetir y por orden`() {
        val log = """
            2026/10/31 01:00:00 NOTICE: discos/02-norte.flac: Skipped copy as --dry-run is set (size 4.120Mi)
            2026/10/31 01:00:00 NOTICE: discos/01-intro.flac: Skipped copy as --dry-run is set (size 3.200Mi)
            2026/10/31 01:00:00 NOTICE: sueltas/demo-01.mp3: Skipped delete as --dry-run is set (size 1.1Mi)
            2026/10/31 01:00:00 NOTICE: lista.m3u: Skipped delete as --dry-run is set (size 120)
            2026/10/31 01:00:00 NOTICE: discos/01-intro.flac: Skipped copy as --dry-run is set (size 3.200Mi)
            2026/10/31 01:00:00 NOTICE:
            Transferred:   	          0 B / 0 B, -, 0 B/s, ETA -
        """.trimIndent()
        val plan = Simulacro.leer(log)
        assertEquals(listOf("discos/01-intro.flac", "discos/02-norte.flac"), plan.copias)
        assertEquals(listOf("lista.m3u", "sueltas/demo-01.mp3"), plan.borrados)
        assertEquals(0, plan.subirian + plan.bajarian + plan.borrariaTelefono + plan.borrariaRemoto)
    }

    @Test
    fun `una bisync dice de su cola hacia que lado va cada cosa`() {
        val log = listOf(
            "2026/10/31 01:00:00 NOTICE: - Path1             Queue copy to Path2                         - disp:vault/nota.md",
            "2026/10/31 01:00:00 NOTICE: - Path1             Queue copy to Path2                         - disp:vault/otra.md",
            "2026/10/31 01:00:00 NOTICE: - Path2             Queue copy to Path1                         - nas:/copia/vault/del-pc.md",
            "2026/10/31 01:00:00 NOTICE: - Path2             Queue delete                                - nas:/copia/vault/vieja.md",
            "2026/10/31 01:00:00 NOTICE: - Path1             Queue delete                                - disp:vault/borrada.md",
            "2026/10/31 01:00:00 NOTICE: - Path1             Queue delete                                - disp:vault/otra-borrada.md",
            "2026/10/31 01:00:00 NOTICE: vieja.md: Skipped delete as --dry-run is set (size 10)",
        ).joinToString("\n")
        val plan = Simulacro.leer(log)
        assertEquals(2, plan.subirian)
        assertEquals(1, plan.bajarian)
        assertEquals(1, plan.borrariaRemoto)
        assertEquals(2, plan.borrariaTelefono)
        assertEquals(listOf("vieja.md"), plan.borrados)
    }

    @Test
    fun `un log sin nada que hacer es un plan vacio`() {
        val plan = Simulacro.leer("2026/10/31 01:00:00 NOTICE: Bisync successful\n")
        assertEquals(Simulacro.Plan(), plan)
        assertEquals(true, plan.vacio)
    }
}
