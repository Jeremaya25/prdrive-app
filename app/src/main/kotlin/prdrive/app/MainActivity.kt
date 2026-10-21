package prdrive.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import prdrive.app.ui.Modelo
import prdrive.app.ui.PrdriveApp

/** La única actividad: el primer arranque hasta que hay volumen, y la principal después. */
class MainActivity : ComponentActivity() {

    private val modelo: Modelo by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PrdriveApp(modelo) }
    }
}
