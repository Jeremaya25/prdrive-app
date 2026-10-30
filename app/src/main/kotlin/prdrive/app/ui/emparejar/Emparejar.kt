package prdrive.app.ui.emparejar

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import prdrive.app.Prdrive
import prdrive.app.ui.CasillaDelLlavero
import prdrive.app.ui.Escaner
import prdrive.app.ui.Letra
import prdrive.app.ui.Modelo
import prdrive.app.ui.Paleta
import prdrive.app.ui.conAvisos
import prdrive.app.ui.conRedLocal
import prdrive.app.ui.flecha
import prdrive.app.ui.inicio.iconoDelModo
import prdrive.app.ui.parejas.palabraDelModo
import prdrive.app.ui.piezas.Aviso
import prdrive.app.ui.piezas.Boton
import prdrive.app.ui.piezas.Casilla
import prdrive.app.ui.piezas.Chip
import prdrive.app.ui.piezas.Glifo
import prdrive.app.ui.piezas.Icono
import prdrive.app.ui.piezas.Marca
import prdrive.app.ui.piezas.Peso
import prdrive.app.ui.piezas.Progreso
import prdrive.app.ui.piezas.Registro
import prdrive.app.ui.piezas.Rotulo
import prdrive.app.ui.piezas.Separador
import prdrive.app.ui.piezas.Tarjeta
import prdrive.app.ui.piezas.Tono
import prdrive.app.ui.piezas.TonoAviso
import prdrive.engine.Catalogo
import prdrive.engine.LLAVERO
import prdrive.engine.Pasada

/**
 * Emparejar.kt — El primer arranque, en cuatro pasos: la bienvenida (escanear o
 * pegar el código del ordenador), el código leído (qué conexión trae, antes de
 * guardar nada), elegir qué parejas sincroniza el teléfono y su primera pasada.
 * Los cuatro del lienzo, con lo que decide el [Modelo] (y, debajo, el instalador).
 *
 * El texto pegado lleva la clave privada del remoto: va en `remember`, no en
 * `rememberSaveable` (el estado guardado de la actividad puede acabar en disco),
 * como una contraseña y sin quedarse a la vista.
 */
@Composable
fun Emparejar(m: Modelo) {
    Scaffold(containerColor = Paleta.actual.paper) { margen ->
        Column(Modifier.padding(margen).fillMaxSize()) {
            when (m.paso) {
                Modelo.Paso.CODIGO -> if (m.carga == null) Bienvenida(m) else CodigoLeido(m)
                Modelo.Paso.PAREJAS -> ElegirParejas(m)
                Modelo.Paso.INICIANDO -> PrimeraPasada(m)
            }
        }
    }
}

/** «Paso N de 3»: tres segmentos de 4 dp, los hechos en `accent`. */
@Composable
private fun Indicador(paso: Int) {
    val c = Paleta.actual
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 1..3) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i <= paso) c.accent else c.line),
                )
            }
        }
        Text("Paso $paso de 3", style = Letra.actual.pista)
    }
}

/** El título y la explicación de un paso. */
@Composable
private fun Titulo(titulo: String, texto: String) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(titulo, style = Letra.actual.titulo, modifier = Modifier.semantics { heading() })
        Text(texto, style = Letra.actual.texto, color = Paleta.actual.ink2)
    }
}

/** Lo que se está haciendo (etiqueta `ocupado`) y el último error (etiqueta `error`). */
@Composable
private fun EstadoDelPaso(m: Modelo) {
    val ocupado = m.ocupado
    val error = m.error
    if (ocupado == null && error == null) return
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ocupado != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Paleta.actual.accent, strokeWidth = 2.dp)
                Text(ocupado, style = Letra.actual.texto, color = Paleta.actual.ink2, modifier = Modifier.testTag("ocupado"))
            }
        }
        if (error != null) {
            val titulo = error.substringBefore(PARRAFO)
            Aviso(
                titulo,
                Modifier.testTag("error"),
                tono = TonoAviso.PELIGRO,
                cuerpo = error.substringAfter(PARRAFO, "").takeIf { it.isNotBlank() },
            )
        }
    }
}

private const val PARRAFO = "\n\n"

/** Una pantalla de un paso: lo suyo se desplaza y [abajo] queda fijo, al alcance del pulgar. */
@Composable
private fun Paso(abajo: @Composable ColumnScope.() -> Unit = {}, contenido: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = contenido,
        )
        Column(
            Modifier.fillMaxWidth().background(Paleta.actual.paper),
        ) {
            HorizontalDivider(thickness = 1.dp, color = Paleta.actual.line)
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = abajo,
            )
        }
    }
}

// --- 1 · bienvenida ----------------------------------------------------------------

@Composable
private fun Bienvenida(m: Modelo) {
    val contexto = LocalContext.current
    val c = Paleta.actual
    var pegando by remember { mutableStateOf(false) }
    // `remember` y no `rememberSaveable`: lleva la clave privada.
    var texto by remember { mutableStateOf("") }
    val libre = m.ocupado == null
    Paso(abajo = {
        Boton(
            "Escanear el código",
            { Escaner.escanear(contexto, m::leerCodigo, m::avisar) },
            Modifier.testTag("escanear"),
            peso = Peso.PRINCIPAL,
            icono = Icono.EYE,
            ancho = true,
            activo = libre,
        )
        Boton("Pegar el texto del código", { pegando = !pegando }, peso = Peso.TONAL, ancho = true, activo = libre)
    }) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Marca(72.dp)
            Text("Empareja este teléfono", style = Letra.actual.titulo, modifier = Modifier.semantics { heading() })
            Text(
                "prdrive guarda aquí tus carpetas y las sincroniza con tu remoto, como un disco más. Para empezar, el " +
                    "ordenador te da un código.",
                style = Letra.actual.texto,
                color = c.ink2,
            )
        }
        Tarjeta(Modifier.padding(horizontal = 16.dp)) {
            Numerado(1, "En el ordenador, abre prdrive", "En una unidad que ya sincroniza con el remoto.")
            Separador()
            Numerado(2, "Enseña el código de emparejamiento", "Lleva la conexión al remoto y su clave.")
            Separador()
            Numerado(3, "Escanéalo con este teléfono", "Después eliges qué carpetas sincroniza.")
        }
        if (pegando) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Como una contraseña: sin autocorrección ni aprendizaje del teclado
                // (que se quedaría con la clave) y sin enseñar lo pegado.
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = it },
                    label = { Text("Texto del código") },
                    maxLines = 3,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("carga"),
                )
                Boton(
                    "Usar este texto",
                    {
                        m.leerCodigo(texto)
                        // Se lea o no, fuera de la pantalla: lleva la clave.
                        texto = ""
                    },
                    Modifier.testTag("usarTexto"),
                    peso = Peso.CONTORNO,
                    ancho = true,
                    activo = texto.isNotBlank() && libre,
                )
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides c.ink3) { Glifo(Icono.CANDADO) }
            Text(
                "El código lleva la clave del remoto: no lo compartas ni le hagas capturas. Pegado, no se queda a la vista.",
                style = Letra.actual.pista,
            )
        }
        EstadoDelPaso(m)
    }
}

/** Un paso numerado de la bienvenida. */
@Composable
private fun Numerado(n: Int, titulo: String, texto: String) {
    val c = Paleta.actual
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Text("$n", style = Letra.actual.nombre, color = c.accentStrong)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titulo, style = Letra.actual.nombre)
            Text(texto, style = Letra.actual.pista, color = c.ink2)
        }
    }
}

// --- 2 · código leído --------------------------------------------------------------

@Composable
private fun CodigoLeido(m: Modelo) {
    val c = m.carga ?: return
    val contexto = LocalContext.current
    val conRed = conRedLocal()
    val libre = m.ocupado == null
    Paso(abajo = {
        Boton(
            "Conectar y leer el catálogo",
            { conRed(m::conectar) },
            Modifier.testTag("conectar"),
            peso = Peso.PRINCIPAL,
            ancho = true,
            ocupado = m.ocupado != null,
        )
        Boton(
            "Escanear otro código",
            { Escaner.escanear(contexto, m::leerCodigo, m::avisar) },
            peso = Peso.SILENCIOSO,
            ancho = true,
            activo = libre,
        )
    }) {
        Boton("Volver", m::olvidarCodigo, Modifier.padding(start = 4.dp), peso = Peso.SILENCIOSO, icono = Icono.BACK, activo = libre)
        Indicador(1)
        Titulo("Código leído", "Esta es la conexión que trae. Nada se ha guardado todavía.")
        Tarjeta(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)).background(Paleta.actual.sunken),
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides Paleta.actual.ink2) { Glifo(Icono.NAS) }
                }
                Column(Modifier.weight(1f)) {
                    Text(c.remoteName.ifEmpty { "El remoto" }, style = Letra.actual.nombre)
                    Text(c.describe(), style = Letra.actual.mono, color = Paleta.actual.ink2)
                }
            }
            Separador()
            Dato("Catálogo de parejas", c.catalogPath)
            if (c.necesitaClave) {
                Separador()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Clave del remoto", style = Letra.actual.pista, modifier = Modifier.weight(1f))
                        Chip("incluida", tono = Tono.OK)
                    }
                    Text(
                        "Se guarda solo dentro de la app, en una carpeta que no se sincroniza ni se ve desde Archivos.",
                        style = Letra.actual.pista,
                        color = Paleta.actual.ink2,
                    )
                }
            }
        }
        if (Prdrive.faltaRedLocal(contexto)) {
            Aviso(
                "Te pedirá permiso para la red local",
                Modifier.padding(horizontal = 16.dp),
                tono = TonoAviso.INFO,
                cuerpo = "Si el remoto está en tu casa, el teléfono pregunta antes de dejar que una app llegue a otros " +
                    "aparatos de tu red. Sin él, la conexión se queda colgada.",
            )
        }
        EstadoDelPaso(m)
    }
}

@Composable
private fun Dato(nombre: String, valor: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(nombre, style = Letra.actual.pista)
        Text(valor, style = Letra.actual.mono, color = Paleta.actual.ink2)
    }
}

// --- 3 · elegir parejas ------------------------------------------------------------

@Composable
private fun ElegirParejas(m: Modelo) {
    val cat = m.catalogo ?: return
    val parejas = Catalogo.pairsByName(cat)
    val c = Paleta.actual
    val libre = m.ocupado == null
    // Con el llavero, las notificaciones se piden antes: avisan de lo suyo.
    val conPermiso = conAvisos()
    val n = m.elegidas.size
    Paso(abajo = {
        Boton(
            (if (n == 1) "Instalar 1 pareja" else "Instalar $n parejas") + if (m.conLlavero) " y el llavero" else "",
            { if (m.conLlavero) conPermiso(m::instalar) else m.instalar() },
            Modifier.testTag("instalar"),
            peso = Peso.PRINCIPAL,
            ancho = true,
            activo = n > 0,
            ocupado = m.ocupado != null,
        )
        Text("La primera pasada junta los dos lados sin borrar nada.", style = Letra.actual.pista)
    }) {
        Indicador(2)
        Titulo(
            "Elige qué sincroniza",
            "Cada pareja une una carpeta de este teléfono con una del remoto.",
        )
        // Lo que el catálogo cambia de la conexión del código: antes de instalar,
        // que es cuando todavía se puede no seguir.
        for (nota in m.notasDelCatalogo) {
            Aviso(nota, Modifier.padding(horizontal = 16.dp).testTag("nota-catalogo"), tono = TonoAviso.AVISO)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Rotulo("Del catálogo · ${cat.names.size} ${if (cat.names.size == 1) "pareja" else "parejas"}")
            Tarjeta {
                cat.names.forEachIndexed { i, nombre ->
                    if (i > 0) Separador()
                    val p = parejas[nombre].orEmpty()
                    val modo = (p["mode"] ?: "bisync").toString()
                    val espejo = modo.endsWith("-mirror")
                    Column(Modifier.fillMaxWidth().background(if (espejo) c.dangerSoft else c.surface)) {
                        Casilla(
                            nombre,
                            nombre in m.elegidas,
                            { m.elegir(nombre, it) },
                            Modifier.testTag("elegir-$nombre").padding(horizontal = 16.dp),
                            activa = libre,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (espejo) {
                                    Chip(palabraDelModo(modo).substringBefore(" ·"), tono = Tono.PELIGRO, icono = iconoDelModo(modo))
                                } else {
                                    Chip(palabraDelModo(modo), icono = iconoDelModo(modo))
                                }
                            }
                            Text(
                                "${p["local"] ?: ""} ${flecha(modo)} ${p["remote_path"] ?: ""}",
                                style = Letra.actual.mono,
                                color = if (espejo) c.danger else c.ink2,
                            )
                            if (espejo) {
                                Text(
                                    "Espejo: un lado manda y el otro se iguala a él, borrados incluidos. Su primera " +
                                        "pasada no se hace aquí.",
                                    style = Letra.actual.pista,
                                    color = c.ink,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (cat.raw["keychain"] is Map<*, *>) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Rotulo("Además")
                Tarjeta { CasillaDelLlavero(m) }
            }
        }
        EstadoDelPaso(m)
    }
}

// --- 4 · primera pasada ------------------------------------------------------------

@Composable
private fun PrimeraPasada(m: Modelo) {
    val contexto = LocalContext.current
    var verLog by remember { mutableStateOf(false) }
    val listo = m.inicioTerminado && m.ocupado == null
    Paso(abajo = {
        // Solo cuando no queda nada en marcha: con el modelo ocupado, el toque se perdería.
        if (listo) {
            Boton("Ir al inicio", m::empezar, Modifier.testTag("empezar"), peso = Peso.PRINCIPAL, ancho = true)
        } else {
            Text("«Ir al inicio» sale cuando acaben todas.", style = Letra.actual.pista)
        }
    }) {
        Indicador(3)
        Titulo(
            "Primera pasada",
            "Junta lo que hay en los dos lados sin borrar nada (--resync). Deja la app abierta hasta que acabe.",
        )
        m.instalado?.notas?.forEach { Text(it, style = Letra.actual.pista, modifier = Modifier.padding(horizontal = 16.dp)) }
        m.progreso?.let { p ->
            val tamano = { b: Long -> Formatter.formatShortFileSize(contexto, b) }
            Progreso(
                p.fraccion,
                Modifier.padding(horizontal = 16.dp),
                texto = "${p.pareja}: ${p.ficheros} de ${p.totalFicheros} ficheros · ${tamano(p.velocidad.toLong())}/s",
            )
        }
        if (m.resultados.isNotEmpty()) {
            Tarjeta(Modifier.padding(horizontal = 16.dp)) {
                m.resultados.entries.forEachIndexed { i, (nombre, r) ->
                    if (i > 0) Separador()
                    Resumen(nombre, r)
                }
            }
        }
        val espejos = m.instalado?.espejos.orEmpty()
        if (espejos.isNotEmpty()) {
            // El aviso del instalador (`_paso_inicializar`), con lo que aquí se hace
            // en vez de `--dry-run`, que el teléfono no tiene.
            Aviso(
                if (espejos.size == 1) "«${espejos.single()}» no se inicializa aquí" else "Los espejos no se inicializan aquí",
                Modifier.padding(horizontal = 16.dp).testTag("aviso-espejos"),
                tono = TonoAviso.AVISO,
                cuerpo = "No se inicializan aquí: ${espejos.joinToString(", ")}. Son espejos: borran en el otro lado lo " +
                    "que no esté en el origen, y lanzarlos con las carpetas locales recién creadas propagaría ese vacío. " +
                    "Por eso la carpeta de un espejo de subida no se crea: hasta que la crees tú con lo que quieres " +
                    "subir, sus pasadas se paran en vez de vaciar el remoto.",
            )
        }
        EstadoDelPaso(m)
        if (m.lineas.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Boton(
                    if (verLog) "Esconder el log" else "Ver el log",
                    { verLog = !verLog },
                    peso = Peso.SILENCIOSO,
                    icono = Icono.FILE,
                )
                if (verLog) Registro(m.lineas)
            }
        }
    }
}

/** Cómo ha ido la primera pasada de una pareja: su chip y, si la hay, la explicación. */
@Composable
private fun Resumen(nombre: String, r: Pasada.Resultado) {
    val (tono, palabra) = when {
        r.ok -> Tono.OK to "hecha"
        r.saltada -> Tono.NEUTRO to "saltada"
        else -> Tono.PELIGRO to "falló"
    }
    Column(
        Modifier.fillMaxWidth().testTag("resultado-$nombre").padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (nombre == LLAVERO) "El llavero" else nombre, style = Letra.actual.nombre, modifier = Modifier.weight(1f))
            Chip(palabra, tono = tono)
        }
        r.explicacion?.let { Text(it, style = Letra.actual.pista, color = Paleta.actual.ink2) }
    }
}
