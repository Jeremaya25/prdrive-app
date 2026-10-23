package prdrive.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import prdrive.app.ui.Modelo
import prdrive.app.ui.PrdriveApp

/** La única actividad: el primer arranque hasta que hay volumen, y la principal después. */
class MainActivity : ComponentActivity() {

    private val modelo: Modelo by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Los iconos de las barras del sistema, oscuros sobre el tema claro y
        // claros sobre el oscuro: los elige según el modo del teléfono, como
        // Tema.kt los colores. Desde Android 15 las pantallas ya se dibujan
        // debajo de las barras (Scaffold deja el margen); con esto, también
        // antes, y se ven igual en todas las versiones.
        enableEdgeToEdge()
        // El modelo se crea aquí y no dentro de `setContent`: sus estados
        // (`mutableStateOf`) nacen en la instantánea de quien los crea, y la de
        // la primera composición no se aplica hasta que esta acaba. Al
        // aplicarse, el valor con el que nacieron pisa lo que otro hilo haya
        // escrito entretanto, sin error: en frío, con la composición lenta, las
        // filas que lee el `init` llegaban antes y la principal salía sin
        // parejas (`PrincipalTest`).
        val modelo = modelo
        setContent { PrdriveApp(modelo) }
    }

    /** Al abrirse, la app pregunta si hay versión nueva ([Actualizador]). */
    override fun onStart() {
        super.onStart()
        Actualizador.alAbrir(this)
    }

    /**
     * Al salir, es cuando la versión nueva se instala sola: instalarse cierra
     * la app. Un giro de pantalla también para la actividad, y no es salir.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) Actualizador.alSalir(this)
    }
}
