package prdrive.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import prdrive.app.ui.Cargando
import prdrive.app.ui.Emparejar
import prdrive.app.ui.TemaPrdrive
import prdrive.engine.Pasada
import prdrive.engine.Progresos

/**
 * Principal.kt — La única Activity: elige pantalla y conecta los botones con el
 * [Modelo]. **No decide nada.**
 *
 * El escáner es el de ML Kit, y se elige por una razón concreta: lo ejecuta
 * Google Play Services en su propio proceso, así que la app **no pide permiso
 * de cámara**. Si no está disponible —un móvil sin Play Services—, la pantalla
 * ofrece pegar el texto del código, que es el mismo formato y el mismo lector.
 */
class Principal : ComponentActivity() {

    private val modelo: Modelo by viewModels { Modelo.Factoria(application as Aplicacion) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TemaPrdrive {
                val estado by modelo.estado.collectAsState()
                when {
                    estado.cargando -> Cargando()
                    estado.listo -> prdrive.app.ui.Principal(
                        config = estado.config!!,
                        estado = estado,
                        lineaDeProgreso = estado.progreso?.let { Progresos.linea(it) },
                        alSincronizar = { parejas, dryRun, resync ->
                            modelo.sincronizar(parejas, dryRun, resync)
                        },
                        alVerLog = { fallo ->
                            // El log entero no cabe en un diálogo cómodo, y lo
                            // que hace falta casi siempre son sus últimas
                            // líneas: es lo que enseña `print_log_tail`.
                            Toast.makeText(
                                this,
                                Pasada.colaDelLog(modelo.logDe(fallo), 6).joinToString("\n"),
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                        alDescartarAviso = modelo::descartarAviso,
                    )
                    else -> Emparejar(
                        estado = estado,
                        alEscanear = ::escanear,
                        alPegar = modelo::emparejar,
                        alLeerCatalogo = modelo::leerCatalogo,
                        alElegir = modelo::elegirParejas,
                        alDescartarAviso = modelo::descartarAviso,
                    )
                }
            }
        }
    }

    private fun escanear() {
        val opciones = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(this, opciones).startScan()
            .addOnSuccessListener { codigo -> codigo.rawValue?.let(modelo::emparejar) }
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    "No se ha podido abrir el escáner: pega el texto del código.",
                    Toast.LENGTH_LONG,
                ).show()
            }
    }
}
