package prdrive.app.ui.ajustes

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import prdrive.app.Actualizador
import prdrive.app.Prdrive
import prdrive.app.documentos.VolumenProvider
import prdrive.app.rclone.Rclone
import prdrive.app.segundoplano.SegundoPlano
import prdrive.app.ui.abrirAjustesDeLaApp
import prdrive.app.ui.Preguntas
import prdrive.app.ui.Cabecera
import prdrive.app.ui.Letra
import prdrive.app.ui.Modelo
import prdrive.app.ui.Paleta
import prdrive.app.ui.conAvisos
import prdrive.app.ui.conRedLocal
import prdrive.app.ui.darPermisoCompartido
import prdrive.app.ui.piezas.Boton
import prdrive.app.ui.piezas.Casilla
import prdrive.app.ui.piezas.Chip
import prdrive.app.ui.piezas.Fila
import prdrive.app.ui.piezas.Icono
import prdrive.app.ui.piezas.Peso
import prdrive.app.ui.piezas.Rotulo
import prdrive.app.ui.piezas.Separador
import prdrive.app.ui.piezas.Tamano
import prdrive.app.ui.piezas.Tarjeta
import prdrive.app.ui.piezas.Tono

/**
 * Ajustes.kt — El remoto con el que está emparejado el teléfono, los permisos
 * que usa la app (cada uno dado o con su botón) y las actualizaciones. Es
 * «Ajustes» del lienzo; «Sincronizar sola», «Emparejar de nuevo», «Ver en
 * Archivos» y «Reparación» llegan con la fase 5.
 *
 * Los permisos se dan en pantallas de Android: al volver a la app se vuelven a
 * mirar todos (`LifecycleResumeEffect`). [faltaRedLocal] es
 * [Prdrive.faltaRedLocal]; un test pasa otro, porque quitar el permiso de verdad
 * mata el proceso.
 */
@Composable
fun Ajustes(
    m: Modelo,
    faltaRedLocal: (Context) -> Boolean = Prdrive::faltaRedLocal,
    yaNoPregunta: (Context, String) -> Boolean = Preguntas::yaNoPregunta,
) {
    val contexto = LocalContext.current
    // Cada vuelta a la app relee los permisos.
    var vueltas by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        vueltas++
        onPauseOrDispose {}
    }
    val conRed = conRedLocal { vueltas++ }
    val conPermiso = conAvisos()
    Column(
        Modifier
            .fillMaxSize()
            .testTag("pantalla-ajustes")
            .verticalScroll(rememberScrollState()),
    ) {
        Cabecera("Ajustes")
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Bloque("Sincronizar sola") {
                val a = m.sola
                Tarjeta {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Casilla(
                            "En segundo plano",
                            a.activo,
                            { m.cambiarSola(a.copy(activo = it)) },
                            Modifier.testTag("segundo-plano"),
                            pista = "Las parejas y el llavero, sin abrir la app, ${SegundoPlano.cadaCuanto(m.intervaloSola)} " +
                                "(lo dice el [daemon] del config).",
                        )
                        Casilla(
                            "Solo con wifi",
                            a.soloWifi,
                            { m.cambiarSola(a.copy(soloWifi = it)) },
                            Modifier.testTag("solo-wifi"),
                            activa = a.activo,
                        )
                        Casilla(
                            "Solo mientras carga",
                            a.soloCargando,
                            { m.cambiarSola(a.copy(soloCargando = it)) },
                            Modifier.testTag("solo-cargando"),
                            activa = a.activo,
                        )
                        Text(
                            "El teléfono elige el momento exacto para ahorrar batería. «Sincronizar ahora» va siempre al momento.",
                            style = Letra.actual.pista,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
            }
            Bloque("Remoto") {
                Tarjeta {
                    Dato("Remoto", m.remoto ?: "—")
                    m.rutaCatalogo?.let {
                        Separador()
                        Dato("Catálogo de parejas", it)
                    }
                }
            }
            Bloque("Permisos") {
                Tarjeta {
                    val filas = mutableListOf<@Composable () -> Unit>()
                    if (Build.VERSION.SDK_INT >= 37) {
                        val falta = remember(vueltas) { faltaRedLocal(contexto) }
                        val enAjustes = remember(vueltas) { falta && yaNoPregunta(contexto, Prdrive.PERMISO_RED_LOCAL) }
                        filas += {
                            Permiso(
                                "Red local",
                                "Para llegar a un remoto de tu casa (un NAS, otro ordenador).",
                                dado = !falta,
                                boton = if (enAjustes) "Abrir ajustes" else "Dar",
                                etiqueta = "permiso-red-local",
                            ) { if (enAjustes) abrirAjustesDeLaApp(contexto) else conRed {} }
                        }
                    }
                    val usaCompartidas = m.filas.any { it.compartida != null || it.sePuedeCompartir }
                    if (Build.VERSION.SDK_INT >= 30 && usaCompartidas) {
                        val dado = remember(vueltas) { Prdrive.permisoCompartido() }
                        val quien = m.filas.filter { it.compartida != null }.map { "«${it.nombre}»" }
                        filas += {
                            Permiso(
                                "Acceso a todos los archivos",
                                if (quien.isEmpty()) {
                                    "Para mover una pareja a una carpeta compartida, donde otra app la abra."
                                } else {
                                    "Lo usa ${quien.joinToString(", ")}, en una carpeta compartida."
                                },
                                dado = dado,
                                boton = "Abrir ajustes",
                                etiqueta = "permiso-archivos",
                            ) { darPermisoCompartido(contexto, m) }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        val dado = remember(vueltas) {
                            ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) ==
                                PackageManager.PERMISSION_GRANTED
                        }
                        val enAjustes = remember(vueltas) {
                            !dado && yaNoPregunta(contexto, Manifest.permission.POST_NOTIFICATIONS)
                        }
                        filas += {
                            Permiso(
                                "Notificaciones",
                                "Avisan de un conflicto del llavero o de un guardado cortado.",
                                dado = dado,
                                boton = if (enAjustes) "Abrir ajustes" else "Dar",
                                etiqueta = "permiso-notificaciones",
                            ) { if (enAjustes) abrirAjustesDeLaApp(contexto) else conPermiso { vueltas++ } }
                        }
                    }
                    val faltaInstalar = remember(vueltas) { Actualizador.faltaPermiso(contexto) }
                    filas += {
                        Permiso(
                            "Instalar apps",
                            "Para que las versiones nuevas se pongan solas, sin preguntar.",
                            dado = !faltaInstalar,
                            boton = "Permitir",
                            etiqueta = "permiso-instalar",
                        ) {
                            contexto.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${contexto.packageName}")),
                            )
                        }
                    }
                    filas.forEachIndexed { i, fila ->
                        if (i > 0) Separador()
                        fila()
                    }
                }
            }
            Bloque("Actualizaciones") { Actualizaciones() }
            Bloque("Volumen") {
                Tarjeta {
                    Fila(
                        "Ver en Archivos",
                        Modifier.testTag("ver-en-archivos"),
                        alPulsar = { verEnArchivos(contexto, m) },
                        icono = Icono.CARPETA,
                        pista = "Las carpetas de las parejas, en la app de Archivos del teléfono.",
                    )
                }
            }
        }
    }
}

/**
 * Abre el volumen en la app de Archivos: la raíz que publica
 * [VolumenProvider] (sin `.prdrive/`), con el tipo de una raíz, que es lo que
 * abre la app de documentos del sistema. Si ningún teléfono la abre así, se dice
 * dónde está.
 */
private fun verEnArchivos(contexto: Context, m: Modelo) {
    val raiz = DocumentsContract.buildRootUri(VolumenProvider.AUTORIDAD, VolumenProvider.ID_RAIZ)
    val ver = Intent(Intent.ACTION_VIEW)
        .setDataAndType(raiz, DocumentsContract.Root.MIME_TYPE_ITEM)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        contexto.startActivity(ver)
    } catch (_: ActivityNotFoundException) {
        m.avisar("No encuentro la app de Archivos. Ábrela tú: el volumen sale como «prdrive» en su menú.")
    }
}

/** Un bloque de Ajustes: su rótulo y, debajo, lo suyo. */
@Composable
private fun Bloque(titulo: String, contenido: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Rotulo(titulo)
        contenido()
    }
}

@Composable
private fun Dato(nombre: String, valor: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(nombre, style = Letra.actual.pista)
        Text(valor, style = Letra.actual.mono, color = Paleta.actual.ink2)
    }
}

/** Un permiso: para qué es y, a la derecha, «dado» o el botón para darlo. */
@Composable
private fun Permiso(titulo: String, para: String, dado: Boolean, boton: String, etiqueta: String, dar: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(etiqueta)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titulo, style = Letra.actual.nombre)
            Text(para, style = Letra.actual.pista, color = Paleta.actual.ink2)
        }
        if (dado) Chip("dado", tono = Tono.OK) else Boton(boton, dar, tamano = Tamano.PEQUENO)
    }
}

/** Las versiones de la app y de rclone, y buscar una nueva sin esperar al día siguiente. */
@Composable
private fun Actualizaciones() {
    val contexto = LocalContext.current
    val version = remember { Actualizador.versionInstalada(contexto) }
    val ocupado = Actualizador.fase in setOf(
        Actualizador.Fase.BUSCANDO, Actualizador.Fase.DESCARGANDO, Actualizador.Fase.INSTALANDO,
    )
    Tarjeta {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("prdrive $version", style = Letra.actual.nombre)
                Text("rclone ${Rclone.version()}", style = Letra.actual.mono, color = Paleta.actual.ink3)
            }
            Boton(
                "Buscar ahora",
                { Actualizador.buscarAhora(contexto) },
                Modifier.testTag("buscar-version"),
                peso = Peso.TONAL,
                tamano = Tamano.NORMAL,
                icono = Icono.RELOAD,
                ocupado = Actualizador.fase == Actualizador.Fase.BUSCANDO,
                activo = !ocupado,
            )
            // Con una versión nueva, el aviso va en su tarjeta de Inicio.
            if (Actualizador.nueva == null) {
                Actualizador.aviso?.let {
                    Text(it, style = Letra.actual.pista, modifier = Modifier.testTag("aviso-version"))
                }
            } else {
                Text("Hay una versión nueva: la tarjeta de Inicio dice cómo va.", style = Letra.actual.pista)
            }
        }
    }
}
