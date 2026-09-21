package prdrive.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Tema.kt — La paleta, en un solo sitio.
 *
 * Es el equivalente de `ui/theme.py` en prdrive, y por la misma razón: **ningún
 * otro fichero escribe un color**. La paleta es la de allí — papel cálido,
 * tinta casi negra, un azul de acento y el ámbar de los avisos—, para que las
 * dos mitades del proyecto se parezcan.
 */
private val TINTA = Color(0xFF1A1A18)
private val PAPEL = Color(0xFFFAF8F3)
private val PAPEL_OSCURO = Color(0xFF17181A)
private val AZUL = Color(0xFF1F5FA8)
private val AZUL_CLARO = Color(0xFF7FB2E8)

/** El ámbar de los avisos. Se expone porque hay bloques que lo usan. */
val AMBAR = Color(0xFF8A5A00)
val AMBAR_FONDO = Color(0xFFFFF4DB)

/** El rojo de un fallo. */
val ROJO = Color(0xFF9B2226)

@Composable
fun TemaPrdrive(contenido: @Composable () -> Unit) {
    val oscuro = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (oscuro) {
            darkColorScheme(primary = AZUL_CLARO, background = PAPEL_OSCURO, surface = PAPEL_OSCURO)
        } else {
            lightColorScheme(
                primary = AZUL,
                background = PAPEL,
                surface = PAPEL,
                onBackground = TINTA,
                onSurface = TINTA,
            )
        },
        content = contenido,
    )
}
