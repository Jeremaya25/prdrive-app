package prdrive.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import prdrive.app.tema.Colores
import prdrive.app.tema.Tokens

/**
 * Tema.kt — Los colores, la letra y las formas de las pantallas: los del sistema
 * de diseño «prdrive», claros u oscuros según el modo oscuro de los ajustes del
 * teléfono, que es quien manda.
 *
 * Los colores no se escriben aquí ni en ninguna pantalla: son los tokens del
 * sistema (`app/diseno/tokens.json`), que `herramientas/diseno.py` escribe en
 * `tema/Tokens.kt`. Las pantallas los leen de `MaterialTheme.colorScheme` (lo
 * que Material ya sabe nombrar) o de [Paleta.actual] (lo demás: `ink-3`, los
 * fondos suaves de cada estado, la marca). Así cada modo sale solo.
 *
 * Lo demás sigue al mismo ajuste por su cuenta:
 * - la ventana, que se ve al abrir la app antes de que Compose dibuje
 *   (`res/values/tema.xml` y `res/values-night/tema.xml`): su fondo es el
 *   `paper` (`@color/fondo`, que genera el mismo `diseno.py`), y `TemaTest`
 *   comprueba que no se separa del de Compose;
 * - los iconos de las barras del sistema, que pone `MainActivity` con
 *   `enableEdgeToEdge()`.
 */
@Composable
fun Tema(contenido: @Composable () -> Unit) {
    val oscuro = isSystemInDarkTheme()
    val colores = if (oscuro) Tokens.oscuro else Tokens.claro
    val tipos = tiposDe(colores)
    CompositionLocalProvider(LocalColores provides colores, LocalTipos provides tipos) {
        MaterialTheme(
            colorScheme = esquemaDe(colores, oscuro),
            typography = tipografiaDe(tipos),
            shapes = FORMAS,
            content = contenido,
        )
    }
}

/** Los colores del sistema en el modo de ahora: los que `MaterialTheme.colorScheme` no sabe nombrar. */
object Paleta {
    val actual: Colores
        @Composable @ReadOnlyComposable
        get() = LocalColores.current
}

private val LocalColores = staticCompositionLocalOf { Tokens.claro }

/**
 * La escala de texto del móvil. Es la del sistema de diseño crecida para el
 * teléfono (se lee a más distancia que una ventana del escritorio): el título de
 * 22 pasa a 28, el texto de 13,5 a 15.
 */
data class Tipos(
    /** El título de una pantalla raíz: uno por pantalla. */
    val titulo: TextStyle,
    /** La barra de una pantalla que vuelve atrás, y el título de un bloque o de una hoja. */
    val barra: TextStyle,
    /** El texto corriente. */
    val texto: TextStyle,
    /** El nombre de una pareja o de una opción. */
    val nombre: TextStyle,
    /** Pistas y explicaciones, en `ink-3`. */
    val pista: TextStyle,
    /** El rótulo de una sección, en `ink-3`: se escribe en mayúsculas al dibujarlo. */
    val rotulo: TextStyle,
    /** Rutas, flags, el log y los números del progreso. */
    val mono: TextStyle,
    /** Rutas secundarias de una fila. */
    val monoPequena: TextStyle,
)

/** La escala de texto del modo de ahora. */
object Letra {
    val actual: Tipos
        @Composable @ReadOnlyComposable
        get() = LocalTipos.current
}

private val LocalTipos = staticCompositionLocalOf { tiposDe(Tokens.claro) }

/**
 * La escala con los colores de [c]: solo la pista y el rótulo llevan el suyo
 * (`ink-3`), porque siempre van en él; lo demás toma el de donde se dibuja. La
 * letra es la del sistema: no se empaqueta ninguna fuente.
 */
private fun tiposDe(c: Colores) = Tipos(
    titulo = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    barra = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    texto = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    nombre = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    pista = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal, color = c.ink3),
    rotulo = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.06.em, color = c.ink3,
    ),
    mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
    monoPequena = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp),
)

/**
 * La tipografía de Material con la escala de [t], para lo que dibuja Material
 * por su cuenta (un campo de texto, un diálogo). Sin colores: los pone quien la
 * usa, como en el resto de Material.
 */
private fun tipografiaDe(t: Tipos): Typography {
    val sinColor = { s: TextStyle -> s.copy(color = Color.Unspecified) }
    return Typography(
        headlineSmall = t.titulo,
        titleMedium = t.barra,
        titleSmall = t.nombre,
        bodyLarge = t.texto,
        bodyMedium = t.texto,
        bodySmall = sinColor(t.pista),
        labelSmall = sinColor(t.rotulo),
    )
}

/**
 * Las esquinas del sistema: 2 dp en casillas, 4 en botones, tarjetas, avisos y
 * campos, y 12 arriba en la hoja inferior. Los 8 son los de la marca a 40 dp.
 */
private val FORMAS = Shapes(
    extraSmall = RoundedCornerShape(Tokens.radioSm),
    small = RoundedCornerShape(Tokens.radioMd),
    medium = RoundedCornerShape(Tokens.radioMd),
    large = RoundedCornerShape(Tokens.radioLg),
    extraLarge = RoundedCornerShape(12.dp),
)

/**
 * Los colores del sistema con los nombres de Material, para lo que Material
 * dibuja por su cuenta (un campo de texto, un diálogo, la hoja inferior).
 *
 * Todos los `surfaceContainer*` son `surface`, y el tinte de la elevación
 * también: el sistema separa por borde, no por sombra ni por tono, y una hoja o
 * un diálogo de Material saldrían si no teñidos del acento.
 */
fun esquemaDe(c: Colores, oscuro: Boolean): ColorScheme {
    val base = if (oscuro) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.accentSoft,
        onPrimaryContainer = c.accentStrong,
        secondary = c.accent,
        onSecondary = c.onAccent,
        secondaryContainer = c.accentSoft,
        onSecondaryContainer = c.accentStrong,
        tertiary = c.warn,
        onTertiary = c.onWarn,
        tertiaryContainer = c.warnSoft,
        onTertiaryContainer = c.warnInk,
        error = c.danger,
        onError = c.onDanger,
        errorContainer = c.dangerSoft,
        onErrorContainer = c.ink,
        background = c.paper,
        onBackground = c.ink,
        surface = c.surface,
        onSurface = c.ink,
        surfaceVariant = c.sunken,
        onSurfaceVariant = c.ink2,
        surfaceTint = c.surface,
        surfaceBright = c.surface,
        surfaceDim = c.surface,
        surfaceContainerLowest = c.surface,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.surface,
        outline = c.border,
        outlineVariant = c.line,
        scrim = c.markInk,
    )
}
