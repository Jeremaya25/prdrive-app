package prdrive.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import prdrive.app.Actualizador
import prdrive.app.ui.emparejar.Emparejar
import prdrive.app.Estado
import prdrive.app.Prdrive
import prdrive.app.compartidas.Compartir
import prdrive.app.rclone.Rclone
import prdrive.engine.MODES
import prdrive.engine.Pasada

/**
 * Pantallas.kt — Las dos pantallas: el primer arranque y
 * la principal. Solo dibujan y llaman al [Modelo]; lo que deciden lo deciden el
 * motor y el instalador. Lo del llavero está en `PantallaLlavero.kt`.
 *
 * Los `testTag` son los asideros de la prueba de punta a punta
 * (`PuntaAPuntaTest`), que maneja estas mismas pantallas.
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
