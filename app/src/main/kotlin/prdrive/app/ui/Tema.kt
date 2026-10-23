package prdrive.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Tema.kt — Los colores de las pantallas: claros u oscuros según el modo oscuro
 * de los ajustes del teléfono, que es quien manda.
 *
 * Son los de serie de Material 3 en los dos modos. Lo demás sigue al mismo
 * ajuste por su cuenta:
 * - la ventana, que se ve al abrir la app antes de que Compose dibuje
 *   (`res/values/tema.xml` y `res/values-night/tema.xml`): su fondo es el de
 *   estos colores, para que no cambie de color al arrancar, y `TemaTest`
 *   comprueba que no se separan;
 * - los iconos de las barras del sistema, que pone `MainActivity` con
 *   `enableEdgeToEdge()`.
 *
 * Las pantallas no llevan ningún color escrito: todo sale de
 * `MaterialTheme.colorScheme`, y por eso cada modo sale solo.
 */
@Composable
fun Tema(contenido: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = contenido,
    )
}
