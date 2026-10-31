// Generado por herramientas/diseno.py desde app/diseno/tokens.json: no lo edites a mano.
package prdrive.app.tema

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Los colores del sistema de diseño «prdrive», uno por token, en un tema. Las
 * pantallas los leen de `Paleta.actual` o de `MaterialTheme.colorScheme`, nunca
 * de aquí: así cada modo sale solo.
 */
data class Colores(
    val paper: Color,
    val surface: Color,
    val sunken: Color,
    val disabledBg: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val disabled: Color,
    val line: Color,
    val lineSoft: Color,
    val border: Color,
    val accent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    val accentLine: Color,
    val onAccent: Color,
    val focus: Color,
    val ok: Color,
    val okSoft: Color,
    val okLine: Color,
    val warn: Color,
    val warnInk: Color,
    val warnSoft: Color,
    val warnLine: Color,
    val warnLineStrong: Color,
    val danger: Color,
    val dangerSoft: Color,
    val dangerLine: Color,
    val onOk: Color,
    val onWarn: Color,
    val onDanger: Color,
    val markEdge: Color,
    val markBlue: Color,
    val markGreen: Color,
    val markGarnet: Color,
    val markViolet: Color,
    val markGraphite: Color,
    val markPaper: Color,
    val markInk: Color,
    val markAmber: Color,
    val traySync: Color,
)

/** Los valores del sistema de diseño: los dos temas, los espacios y los radios. */
object Tokens {
    val claro = Colores(
        paper = Color(0xFFFAF9F7),
        surface = Color(0xFFFFFFFF),
        sunken = Color(0xFFF4F2EE),
        disabledBg = Color(0xFFF6F4F0),
        ink = Color(0xFF1C1A17),
        ink2 = Color(0xFF5C564C),
        ink3 = Color(0xFF6B655A),
        disabled = Color(0xFFA9A398),
        line = Color(0xFFE4E0D8),
        lineSoft = Color(0xFFEFEBE4),
        border = Color(0xFF8C8678),
        accent = Color(0xFF3D5A80),
        accentStrong = Color(0xFF33506F),
        accentSoft = Color(0xFFEDF1F6),
        accentLine = Color(0xFFC6D3E2),
        onAccent = Color(0xFFFFFFFF),
        focus = Color(0xFF3D5A80),
        ok = Color(0xFF2D6A5A),
        okSoft = Color(0xFFE7F0EE),
        okLine = Color(0xFFC3DAD4),
        warn = Color(0xFF8A5A00),
        warnInk = Color(0xFF7A4F00),
        warnSoft = Color(0xFFFBF2E2),
        warnLine = Color(0xFFE8D6AE),
        warnLineStrong = Color(0xFFDCCBA6),
        danger = Color(0xFFA0392E),
        dangerSoft = Color(0xFFF9EBE8),
        dangerLine = Color(0xFFE4C6C0),
        onOk = Color(0xFFFFFFFF),
        onWarn = Color(0xFFFFFFFF),
        onDanger = Color(0xFFFFFFFF),
        markEdge = Color(0x141C1A17),
        markBlue = Color(0xFF2E4763),
        markGreen = Color(0xFF2F5A48),
        markGarnet = Color(0xFF6B2C34),
        markViolet = Color(0xFF4B3D6E),
        markGraphite = Color(0xFF3B362F),
        markPaper = Color(0xFFFAF9F7),
        markInk = Color(0xFF1C1A17),
        markAmber = Color(0xFFE0A34A),
        traySync = Color(0xFF6F9BD1),
    )

    val oscuro = Colores(
        paper = Color(0xFF171512),
        surface = Color(0xFF201D19),
        sunken = Color(0xFF121110),
        disabledBg = Color(0xFF1B1915),
        ink = Color(0xFFF2EEE7),
        ink2 = Color(0xFFCBC4B7),
        ink3 = Color(0xFFA59E90),
        disabled = Color(0xFF6A645A),
        line = Color(0xFF34302A),
        lineSoft = Color(0xFF292621),
        border = Color(0xFF7D776B),
        accent = Color(0xFF8DB0DE),
        accentStrong = Color(0xFFA9C5EA),
        accentSoft = Color(0xFF222D3C),
        accentLine = Color(0xFF3C4D66),
        onAccent = Color(0xFF0F1826),
        focus = Color(0xFF8DB0DE),
        ok = Color(0xFF7CC5AE),
        okSoft = Color(0xFF17302A),
        okLine = Color(0xFF2C5246),
        warn = Color(0xFFE6B45E),
        warnInk = Color(0xFFEDC27A),
        warnSoft = Color(0xFF2A2214),
        warnLine = Color(0xFF5A4524),
        warnLineStrong = Color(0xFF6B5530),
        danger = Color(0xFFF0897D),
        dangerSoft = Color(0xFF301D1A),
        dangerLine = Color(0xFF5E322C),
        onOk = Color(0xFF0B1F1A),
        onWarn = Color(0xFF261A06),
        onDanger = Color(0xFF2B0F0C),
        markEdge = Color(0x3DFFFFFF),
        markBlue = Color(0xFF2E4763),
        markGreen = Color(0xFF2F5A48),
        markGarnet = Color(0xFF6B2C34),
        markViolet = Color(0xFF4B3D6E),
        markGraphite = Color(0xFF3B362F),
        markPaper = Color(0xFFFAF9F7),
        markInk = Color(0xFF1C1A17),
        markAmber = Color(0xFFE0A34A),
        traySync = Color(0xFF6F9BD1),
    )

    val espacio0: Dp = 2.dp
    val espacio1: Dp = 4.dp
    val espacio2: Dp = 8.dp
    val espacio3: Dp = 12.dp
    val espacio4: Dp = 16.dp
    val espacio5: Dp = 24.dp
    val espacio6: Dp = 32.dp
    val espacio7: Dp = 48.dp
    val radioSm: Dp = 2.dp
    val radioMd: Dp = 4.dp
    val radioLg: Dp = 8.dp
}
