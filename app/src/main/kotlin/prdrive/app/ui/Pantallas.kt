package prdrive.app.ui

import android.os.Environment
import androidx.compose.runtime.Composable
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import prdrive.app.ui.emparejar.Emparejar
import prdrive.engine.MODES

/**
 * Pantallas.kt — La raíz de las pantallas y lo que dicen varias de ellas.
 *
 * Sin volumen sembrado, emparejar el teléfono (`emparejar/Emparejar.kt`); con
 * él, las cuatro secciones (`Navegacion.kt`): Inicio (`inicio/`), Parejas
 * (`parejas/`), Llavero (`llavero/`) y Ajustes (`ajustes/`), hechas con las
 * piezas del sistema de diseño (`piezas/`). Solo dibujan y llaman al [Modelo];
 * lo que deciden lo deciden el motor y el instalador.
 *
 * Los `testTag` son los asideros de los tests de `androidTest`, y de la prueba
 * de punta a punta (`PuntaAPuntaTest`), que maneja estas mismas pantallas.
 */
@Composable
fun PrdriveApp(modelo: Modelo) {
    Tema {
        if (modelo.sembrado) Secciones(modelo) else Emparejar(modelo)
    }
}

/**
 * Hace cuánto, en español: la app no tiene otro idioma, y el
 * `DateUtils` de Android lo diría en el del teléfono, en mitad de una frase.
 */
internal fun haceCuanto(ms: Long, ahora: Long = System.currentTimeMillis()): String {
    val minutos = (ahora - ms) / 60_000
    return when {
        minutos < 1 -> "hace un momento"
        minutos < 60 -> "hace $minutos min"
        minutos < 24 * 60 -> "hace ${minutos / 60} h"
        minutos < 48 * 60 -> "ayer"
        else -> "el " + DateTimeFormatter.ofPattern("d/M/yyyy").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    }
}

/** Hacia dónde va lo que se copia en ese modo: los dos sentidos, de bajada o de subida. */
internal fun flecha(modo: String): String {
    val m = MODES[modo] ?: return "↔"
    return when {
        m.isBisync -> "↔"
        m.origenLocal -> "→"
        else -> "←"
    }
}

/** La ruta de una carpeta compartida como la ve la persona: desde la raíz del almacenamiento (`Documents/vault`). */
internal fun rutaCorta(ruta: String): String =
    File(ruta).relativeToOrNull(Environment.getExternalStorageDirectory())?.path?.takeIf { it.isNotEmpty() } ?: ruta
