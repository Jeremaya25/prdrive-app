package prdrive.app.ui

import android.content.Context
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Escaner.kt — Leer el QR de emparejamiento con el escáner de códigos de
 * Google Play services (PLAN.md, «Pantallas del paso 1»).
 *
 * Ese escáner es una pantalla de Play services: la app no pide permiso de
 * cámara ni ve la imagen, solo recibe el texto. Si el teléfono no lo tiene
 * (sin Play services, o sin descargar aún el módulo), queda pegar el texto.
 */
object Escaner {

    fun escanear(contexto: Context, alLeer: (String) -> Unit, alFallar: (String) -> Unit) {
        val opciones = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(contexto, opciones).startScan()
            .addOnSuccessListener { codigo ->
                val texto = codigo.rawValue
                if (texto.isNullOrEmpty()) alFallar("El código no lleva texto.") else alLeer(texto)
            }
            .addOnFailureListener { e ->
                val porque = if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) {
                    "el escáner de Google Play services aún no está en el teléfono (se descarga solo; " +
                        "prueba dentro de un momento)"
                } else {
                    e.message ?: e.toString()
                }
                alFallar("No se ha podido abrir el escáner: $porque. Puedes pegar el texto del código.")
            }
        // Cancelar no es un error: la persona ha vuelto atrás.
    }
}
