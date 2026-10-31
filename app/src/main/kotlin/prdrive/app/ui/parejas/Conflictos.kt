package prdrive.app.ui.parejas

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.io.File
import prdrive.app.Estado
import prdrive.app.Prdrive
import prdrive.app.documentos.VolumenProvider
import prdrive.app.ui.Cabecera
import prdrive.app.ui.Letra
import prdrive.app.ui.Modelo
import prdrive.app.ui.Paleta
import prdrive.app.ui.piezas.Aviso
import prdrive.app.ui.piezas.Boton
import prdrive.app.ui.piezas.Hoja
import prdrive.app.ui.piezas.Icono
import prdrive.app.ui.piezas.Opcion
import prdrive.app.ui.piezas.Peso
import prdrive.app.ui.piezas.Rotulo
import prdrive.app.ui.piezas.Tamano
import prdrive.app.ui.piezas.Tarjeta
import prdrive.app.ui.piezas.TonoAviso
import prdrive.engine.Conflictos
import prdrive.engine.ResolverConflictos

/** Lo que se explica sobre qué es un conflicto: el del lienzo (`tk_conflicts.EXPLICACION` en el escritorio). */
private const val EXPLICACION = "Cambiaron en los dos lados entre dos pasadas. Hay dos versiones de cada uno y " +
    "no se ha perdido nada. Elige con cuál te quedas."

/**
 * El aviso de elegir en la base del llavero, del teléfono: el de prdrive
 * ([ResolverConflictos.PIERDE_LLAVERO]) manda a «Combinar», que aquí no existe.
 */
internal const val PIERDE_LLAVERO_TELEFONO = "Es la base del llavero: lo que se guardó en la versión que se " +
    "descarta se pierde. Para juntar las dos sin perder nada, combínalas en el PC («Abrir llavero» → «Combinar»)."

/**
 * Conflictos.kt — «Resolver conflictos» de una pareja: cada fichero en conflicto
 * con sus versiones (cuál es de cada lado, cuánto ocupa y de cuándo es), elegir
 * con cuál se queda, y «Aplicar y sincronizar». Es el «Resolver conflictos» del
 * lienzo y la sección de conflictos de «Reparación» del escritorio
 * (`ui/tk_conflicts.py`): qué es cada versión y qué pasa en disco lo deciden el
 * motor ([Conflictos], [ResolverConflictos]); aquí se pide el plan, se enseñan
 * sus consecuencias en una hoja y solo si se dice que sí se ejecuta.
 *
 * Se elige por versión, no por lado: «la de este teléfono» es la única versión de
 * este lado (`plan_lado()` es `plan_conservar()` de ella), y así también se
 * puede elegir cuando hay varias del mismo lado o no se sabe de cuál es.
 * «Quedarme con las dos», la de fábrica, es no tocarlo: la copia se queda al lado
 * con su nombre de conflicto, como hasta ahora.
 */
@Composable
fun ResolverLosConflictos(m: Modelo, nombre: String, volver: () -> Unit, conRed: (() -> Unit) -> Unit) {
    val c = Paleta.actual
    val contexto = LocalContext.current
    val f = m.filas.firstOrNull { it.nombre == nombre }
    val conflictos = f?.conflictos.orEmpty()
    // La versión elegida de cada conflicto (su original → la ruta de la versión),
    // guardada con la actividad: girar el teléfono no la pierde.
    val elegidas = rememberSaveable(saver = SALVADOR) { mutableStateMapOf<String, String>() }
    var confirmando by rememberSaveable { mutableStateOf(false) }
    val libre = m.ocupado == null
    val elegidos = conflictos.mapNotNull { k -> k.versiones.firstOrNull { it.ruta.path == elegidas[k.original.path] }?.let { k to it } }
    Column(Modifier.fillMaxSize().testTag("pantalla-conflictos-$nombre")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val n = conflictos.size
            Cabecera(
                if (n == 1) "1 fichero en conflicto" else "$n ficheros en conflicto",
                volver = volver,
                etiquetaVolver = nombre,
            )
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (f == null || n == 0) {
                    Aviso("No queda ningún conflicto", tono = TonoAviso.OK, cuerpo = "Las dos versiones ya son una.")
                    return@Column
                }
                Text(EXPLICACION, style = Letra.actual.texto, color = c.ink2)
                conflictos.forEach { k ->
                    UnConflicto(
                        m, f, k, elegidas[k.original.path],
                        { ruta -> if (ruta == null) elegidas.remove(k.original.path) else elegidas[k.original.path] = ruta },
                        abrir = { v -> abrirVersion(contexto, m, k, v) },
                        activo = libre,
                    )
                }
                Text(
                    if (f.guardaVersiones) {
                        "Lo que se descarta no se pierde del todo: al sincronizar, el remoto lo guarda en ${prdrive.engine.VERSIONS_DIR}/ de la pareja."
                    } else {
                        "Lo que se descarta se borra: esta pareja no guarda versiones."
                    },
                    style = Letra.actual.pista,
                )
            }
        }
        if (conflictos.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(c.paper)) {
                HorizontalDivider(thickness = 1.dp, color = c.line)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Boton(
                        "Aplicar y sincronizar",
                        { confirmando = true },
                        Modifier.testTag("aplicar-conflictos"),
                        peso = Peso.PRINCIPAL,
                        ancho = true,
                        activo = libre && elegidos.isNotEmpty(),
                    )
                    Boton("Ahora no", volver, peso = Peso.SILENCIOSO, ancho = true)
                }
            }
        }
    }
    if (confirmando && elegidos.isNotEmpty()) {
        Confirmar(m, nombre, elegidos, conRed, alCerrar = { confirmando = false }, alAplicar = {
            confirmando = false
            elegidas.clear()
            volver()
        })
    }
}

/** Un fichero en conflicto: su ruta y una opción por versión, más «Quedarme con las dos». */
@Composable
private fun UnConflicto(
    m: Modelo,
    f: Estado.Fila,
    k: Conflictos.Conflicto,
    elegida: String?,
    alElegir: (String?) -> Unit,
    abrir: (Conflictos.Version) -> Unit,
    activo: Boolean,
) {
    Column(Modifier.testTag("conflicto-${k.relativa}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Rotulo(k.relativa)
        ResolverConflictos.etiquetas(k).forEachIndexed { i, (v, etiqueta) ->
            Opcion(
                enElTelefono(etiqueta),
                elegida == v.ruta.path,
                { if (activo) alElegir(v.ruta.path) },
                Modifier.testTag("version-${k.relativa}-${v.lado ?: i}"),
                pista = ResolverConflictos.describir(v.ruta, m.entornoConflictos),
            )
            // Solo lo que publica el proveedor se abre con otra app: no una carpeta compartida.
            if (f.compartida == null) {
                Boton(
                    "Abrir esta versión…",
                    { abrir(v) },
                    Modifier.padding(start = 4.dp),
                    peso = Peso.SILENCIOSO,
                    tamano = Tamano.PEQUENO,
                    icono = Icono.EYE,
                )
            }
        }
        val copias = k.versiones.filter { !it.esOriginal }.map { it.ruta.name }
        Opcion(
            "Quedarme con las dos",
            elegida == null,
            { if (activo) alElegir(null) },
            Modifier.testTag("las-dos-${k.relativa}"),
            pista = if (copias.size == 1) "La otra se queda al lado, como ${copias.single()}." else "Las otras se quedan al lado, como copias.",
        )
    }
}

/**
 * La hoja que confirma: los planes de lo elegido, con sus consecuencias y avisos,
 * y lo que no se puede (un fichero que ya no está). Los planes se piensan al
 * abrirla, y otra vez si se recrea: lo que se confirma son los ficheros de ahora.
 */
@Composable
private fun Confirmar(
    m: Modelo,
    nombre: String,
    elegidos: List<Pair<Conflictos.Conflicto, Conflictos.Version>>,
    conRed: (() -> Unit) -> Unit,
    alCerrar: () -> Unit,
    alAplicar: () -> Unit,
) {
    val c = Paleta.actual
    val pensados = remember(elegidos) {
        elegidos.map { (k, v) ->
            try {
                k to Result.success(ResolverConflictos.planConservar(k, v, m.entornoConflictos))
            } catch (e: ResolverConflictos.ResolucionImposible) {
                k to Result.failure(e)
            }
        }
    }
    val planes = pensados.mapNotNull { it.second.getOrNull() }
    Hoja(if (elegidos.size == 1) "Resolver 1 conflicto" else "Resolver ${elegidos.size} conflictos", alCerrar) {
        Aviso("Esto es lo que va a pasar. Nada se ha tocado todavía.", tono = TonoAviso.INFO)
        pensados.forEach { (k, r) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Rotulo(k.relativa)
                val plan = r.getOrNull()
                if (plan == null) {
                    Text(r.exceptionOrNull()?.message.orEmpty(), style = Letra.actual.texto, color = c.danger)
                } else {
                    plan.consequences.forEach { Text("· $it", style = Letra.actual.texto, color = c.ink) }
                    plan.warnings.forEach {
                        Text(
                            if (it == ResolverConflictos.PIERDE_LLAVERO) PIERDE_LLAVERO_TELEFONO else it,
                            style = Letra.actual.texto,
                            color = c.warnInk,
                        )
                    }
                }
            }
        }
        Text(ResolverConflictos.NOTA_LOCAL, style = Letra.actual.pista)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Boton(
                "Aplicar y sincronizar",
                {
                    alAplicar()
                    conRed { m.resolver(nombre, planes) }
                },
                Modifier.testTag("confirmar-conflictos"),
                peso = Peso.PRINCIPAL,
                ancho = true,
                activo = m.ocupado == null && planes.isNotEmpty(),
            )
            Boton("Ahora no", alCerrar, peso = Peso.SILENCIOSO, ancho = true)
        }
    }
}

/**
 * La etiqueta del motor dicha como en el teléfono: «versión de este
 * dispositivo» es «La de este teléfono», y lo de detrás (« (2)», si hay varias)
 * se queda.
 */
internal fun enElTelefono(etiqueta: String): String {
    val nombres = listOf(
        ResolverConflictos.ETIQUETA_LADO.getValue(Conflictos.DISPOSITIVO) to "La de este teléfono",
        ResolverConflictos.ETIQUETA_LADO.getValue(Conflictos.REMOTO) to "La del remoto",
        ResolverConflictos.ETIQUETA_ORIGINAL to "La que tiene su nombre",
        ResolverConflictos.ETIQUETA_COPIA to "Otra versión",
    )
    for ((motor, telefono) in nombres) {
        if (etiqueta.startsWith(motor)) return telefono + etiqueta.removePrefix(motor)
    }
    return etiqueta.replaceFirstChar { it.uppercaseChar() }
}

/**
 * Abre una versión con la app que elija la persona, por el proveedor del volumen
 * y solo para leer. El tipo sale del nombre de verdad (`plan.md`), no del de la
 * copia (`plan.md.conflict1`).
 */
private fun abrirVersion(contexto: Context, m: Modelo, k: Conflictos.Conflicto, v: Conflictos.Version) {
    val raiz = Prdrive.volumen(contexto).raiz
    val relativa = try {
        v.ruta.relativeTo(raiz).invariantSeparatorsPath
    } catch (_: IllegalArgumentException) {
        return
    }
    val uri = DocumentsContract.buildDocumentUri(VolumenProvider.AUTORIDAD, "${VolumenProvider.ID_RAIZ}/$relativa")
    val tipo = MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(k.original.name).extension.lowercase())
        ?: "text/plain"
    val ver = Intent(Intent.ACTION_VIEW).setDataAndType(uri, tipo).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        contexto.startActivity(Intent.createChooser(ver, "Abrir ${k.original.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        m.avisar("Ninguna app de este teléfono abre «${k.original.name}».")
    }
}

/** Las elegidas, como una lista plana (original, versión, original, versión…). */
private val SALVADOR = listSaver<SnapshotStateMap<String, String>, String>(
    save = { mapa -> mapa.flatMap { listOf(it.key, it.value) } },
    restore = { lista -> mutableStateMapOf<String, String>().apply { lista.chunked(2).forEach { put(it[0], it[1]) } } },
)
