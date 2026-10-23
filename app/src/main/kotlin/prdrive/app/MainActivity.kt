package prdrive.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import prdrive.app.llavero.Disparos
import prdrive.app.ui.Modelo
import prdrive.app.ui.PrdriveApp

/** La única actividad: el primer arranque hasta que hay volumen, y la principal después. */
class MainActivity : ComponentActivity() {

    private val modelo: Modelo by viewModels()

    /**
     * Al arrancar, si algo del llavero quedó sin subir (un guardado cuyo
     * trabajo se perdió), se encola su pasada ([Disparos.siPendiente]).
     *
     * El modelo se crea aquí y no dentro de `setContent`: creado en mitad de
     * la primera composición, su estado nace dentro de la instantánea de esa
     * composición, y lo que su primer trabajo escribe desde otro hilo antes
     * de que esa instantánea se aplique se pierde (la principal se quedaba
     * sin filas si leer `state/` iba más deprisa que el primer dibujo).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val m = modelo
        setContent { PrdriveApp(m) }
        Disparos.siPendiente(this)
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
