# El rediseño móvil, en Compose — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que la app se vea y se use como el lienzo del rediseño: el sistema de diseño de prdrive adaptado al móvil, cuatro secciones abajo, un Inicio que dice en una frase cómo está todo, y en claro y en oscuro según el teléfono, con lo que la app ya hace hoy.

**Architecture:** Los tokens del sistema entran en el repositorio (`app/diseno/`) y un generador (`herramientas/diseno.py`) escribe con ellos los colores (`tema/Tokens.kt`), el fondo de la ventana y los iconos. `ui/Tema.kt` los pone en `MaterialTheme` y en una `Paleta` propia. Encima, unas piezas comunes (`ui/piezas/`), una navegación propia de cuatro secciones (`ui/Navegacion.kt`) y las pantallas, que solo dibujan y llaman al `Modelo` de siempre. El motor no cambia en las fases 0 a 4.

**Tech Stack:** Kotlin 2.4, Jetpack Compose con Material 3 (BOM 2026.09.00), Python 3 para el generador. Ninguna dependencia nueva.

**Spec:** el lienzo «prdrive móvil · rediseño» (https://claude.ai/artifact/QefvmSp6RWpTVTHA2DJMVe: 19 pantallas y 6 en oscuro) y el sistema de diseño «prdrive» (https://claude.ai/code/artifact/bd675973-d734-4d4c-94cf-ea42ce5332b9, versión `1793185577-cb49`; su `README.md` dice cómo se escribe y se dibuja). Cada tarea nombra las pantallas del lienzo que implementa.

## Global Constraints

- Textos para la persona en español, con tuteo y frases cortas. Botones en infinitivo, con mayúscula solo al empezar («Sincronizar ahora»), y con «…» si abren otra pantalla. Nombres de pantallas, botones y parejas entre «», y rutas, flags y modos en monoespaciada. Sin emoji ni exclamaciones; «Ojo:» solo para lo grave. Un aviso que borra dice qué borra y su tope.
- En `app/src/main/**/ui/` no se escribe ningún color (`.claude/hooks/reglas.py` lo bloquea): todo sale de `MaterialTheme.colorScheme` o de `Paleta.actual`. Los valores solo están en `app/src/main/kotlin/prdrive/app/tema/Tokens.kt`, que genera `herramientas/diseno.py` desde `app/diseno/tokens.json` y no se edita a mano.
- El estado nunca se dice solo con el color: cada chip lleva palabra y glifo, y cada aviso, título.
- El tema sigue al modo oscuro del teléfono (`isSystemInDarkTheme()`). El fondo de la ventana es `paper` en los dos modos.
- Toque de 48 dp como mínimo en todo lo que se pulsa (`Modifier.minimumInteractiveComponentSize()`), aunque el dibujo sea menor. Filas de lista de 56 dp o más, tocables enteras. Márgenes de 16 dp y bloques a 20 o 24 dp.
- Escala de texto del móvil: título 28/34 sp (600), barra y bloque 17/22 (600), texto 15/22, nombre 15/20 (600), pista 13/18 (`ink-3`), rótulo 12/16 (700, en mayúsculas, espaciado 0,06 em), mono 12/16 y mono pequeña 11/16. Rótulos de botón: 14,5 sp en `GRANDE`, 13,5 en `NORMAL` y 12,5 en `PEQUENO`, todos 600. Chip: 12 sp, 500. Letra del sistema (`FontFamily.Default`) y `FontFamily.Monospace`: no se empaqueta ninguna fuente.
- Iconos: los 30 glifos del sistema, con trazo de 1,5 sobre rejilla de 16. Van a 16 dp, a 12 dp dentro de un chip y a 20 dp en la barra de secciones. Nunca emoji ni caracteres como ✓ o ⚠. Un icono solo, sin texto, lleva `contentDescription`.
- Bordes de 1 dp, no sombras. Esquinas de 4 dp en botones, tarjetas, avisos y campos, 2 dp en casillas y 12 dp arriba en la hoja inferior; el chip es una píldora. Solo la hoja inferior lleva sombra.
- `engine/` no se toca en las fases 0 a 4. `Modelo.kt` y `Estado.kt`, sí, solo donde la tarea lo dice.
- Ninguna dependencia nueva: la navegación es propia y la hoja es el `ModalBottomSheet` de material3.
- Las etiquetas (`testTag`) que usan los tests se conservan. Si alguna cambia de pantalla, la tarea lo dice y actualiza esos tests en el mismo commit.
- Tests de `androidTest` con nombres en guiones bajos (`una_fila_con_letra_grande_no_esconde_su_estado`). Los avisos del compilador son errores (`allWarningsAsErrors`).
- Órdenes: `./gradlew :app:connectedDebugAndroidTest`, con `ANDROID_SERIAL=emulator-55xx` si hay más de un dispositivo (borra la app del dispositivo; la skill `emulador` dice cómo elegir uno libre). Una clase sola: `-Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.<Clase>`. En Windows, si Gradle no arranca: `export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'`.
- No se toca `VERSION`: publicar lo decide la persona. Commits en español.

## Review Focus

1. **Letra grande del sistema** (Ajustes → Pantalla, escala de 1,3 a 2): filas, chips y botones crecen en vez de cortarse, y la barra de secciones no tapa lo último de la lista. Test en la Task 6.
2. **Android recrea la actividad** (girar el teléfono, cambiar el modo oscuro, volver de los ajustes con poca memoria) con una ficha o una hoja abierta: se vuelve al mismo sitio. Test en la Task 7.
3. **El botón atrás del sistema:** de una ficha a su lista, de una sección a Inicio, y desde Inicio se sale de la app. Test en la Task 7.
4. **Tocar otra vez con un trabajo en marcha:** los botones que cambian algo se apagan en todas las pantallas, también en las fichas y las hojas, y no parece que vayan a hacer algo. `Modelo.trabajo` ya ignora el segundo toque, pero la pantalla no debe engañar. Test en la Task 10.
5. **Muchas parejas (30) o nombres largos:** Inicio y Parejas se desplazan, la barra de secciones queda fija y el chip de estado no se sale de la fila. Test en la Task 8.

---

## Fase 0 · La base: tokens, iconos, marca y tema

### Task 1: El sistema de diseño en el repositorio, y su generador

**Files:**
- Create: `app/diseno/tokens.json`, `app/diseno/iconos.json`
- Create: `herramientas/diseno.py`
- Create (generados): `app/src/main/kotlin/prdrive/app/tema/Tokens.kt`, `app/src/main/res/values/fondo.xml`, `app/src/main/res/values-night/fondo.xml`, `app/src/main/res/drawable/pd_<nombre>.xml` (uno por glifo)
- Modify: `app/src/main/res/values/tema.xml`, `app/src/main/res/values-night/tema.xml` (se quita `<color name="fondo">`, que ahora está en `fondo.xml`)
- Modify: `.claude/hooks/generados.py`, `.github/actions/compilar-apk/action.yml`

**Interfaces:**
- Produces, en `package prdrive.app.tema` (generado):
  - `data class Colores(...)` con un `val <token>: Color` por cada token de color de `tokens.json`, en camelCase (`paper`, `surface`, `sunken`, `disabledBg`, `ink`, `ink2`, `ink3`, `disabled`, `line`, `lineSoft`, `border`, `accent`, `accentStrong`, `accentSoft`, `accentLine`, `onAccent`, `focus`, `ok`, `okSoft`, `okLine`, `warn`, `warnInk`, `warnSoft`, `warnLine`, `warnLineStrong`, `danger`, `dangerSoft`, `dangerLine`, `onOk`, `onWarn`, `onDanger`, `markEdge`, `markBlue`, `markGreen`, `markGarnet`, `markViolet`, `markGraphite`, `markPaper`, `markInk`, `markAmber`, `traySync`).
  - `object Tokens { val claro: Colores; val oscuro: Colores; val espacio0..espacio7: Dp; val radioSm: Dp; val radioMd: Dp; val radioLg: Dp }`, con los valores de `spacing` y `radius` de `tokens.json`.
  - `R.color.fondo`: `paper` claro en `values/` y oscuro en `values-night/`.
  - `R.drawable.pd_<nombre>`, con el nombre del glifo y `-` cambiado por `_` (`pd_candado_abierto`).

- [ ] **Step 1: Copiar el sistema.** `app/diseno/tokens.json` es `project/tokens.json` del sistema «prdrive», versión `1793185577-cb49`, byte a byte. `app/diseno/iconos.json` es `{"<nombre>": "<interior del svg>"}`, con los 30 glifos del mapa `ICONS` de `project/components/bundle.js` de la misma versión (`sync`, `dispositivo`, `nas`, `both`, `up`, `down`, `ok`, `warn`, `alert`, `clock`, `gear`, `parejas`, `arranque`, `doctor`, `flag`, `eye`, `reload`, `edit`, `trash`, `plus`, `close`, `back`, `file`, `expulsar`, `carpeta`, `pausa`, `play`, `candado`, `candado_abierto`, `llave`). Un comentario en la cabecera de `diseno.py` dice de dónde salen y cómo se actualizan.
- [ ] **Step 2: El generador.** `python herramientas/diseno.py [--comprobar]`, solo con la biblioteca estándar. Sin argumentos, escribe los ficheros generados. Con `--comprobar`, no escribe nada y termina con 1, nombrando el fichero, si alguno no es lo que escribiría. Reglas:
  - Un color `#RRGGBB` es `Color(0xFFRRGGBB)` y uno `#RRGGBBAA` es `Color(0xAARRGGBB)`; un valor `{otro}` toma el de ese token.
  - Las medidas en `px` son `.dp`, y `999px` no se escribe (la píldora la hace `CircleShape`).
  - Cada `.kt` y `.xml` lleva arriba `Generado por herramientas/diseno.py desde app/diseno/…: no lo edites a mano.`
  - Cada glifo es un `<vector>` de 16 dp con `viewportWidth`/`viewportHeight` de 16, y un `<path>` por figura. `rect` (con `rx`) y `circle` se pasan a datos de path. Las figuras sin clase llevan `strokeColor="#FF000000"`, `strokeWidth="1.5"`, `strokeLineCap="round"`, `strokeLineJoin="round"` y `fillColor="#00000000"`; las de clase `pd-dot`, solo relleno; las de `pd-fill`, relleno y trazo. El color lo pone después el `tint` del icono.
- [ ] **Step 3: Ejecutarlo dos veces.** Run: `python herramientas/diseno.py && python herramientas/diseno.py --comprobar`. Expected: la segunda termina con 0 y `git status` enseña solo los ficheros de esta tarea.
- [ ] **Step 4: Protegerlos.** En `generados.py`, un mensaje `DISENO` («Se genera desde el sistema de diseño: cambia `app/diseno/` y ejecuta `python herramientas/diseno.py`»). Se usa para `app/src/main/kotlin/prdrive/app/tema/Tokens.kt` y los dos `fondo.xml` (en `EXACTOS`) y para todo lo que empiece por `app/src/main/res/drawable/pd_` (un prefijo nuevo en `motivo()`). En `compilar-apk/action.yml`, antes de compilar, un paso `python herramientas/diseno.py --comprobar`.
- [ ] **Step 5: Que compile.** Run: `./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL (aún no se usa nada de lo generado).
- [ ] **Step 6: Commit.** `git add app/diseno herramientas/diseno.py app/src/main/kotlin/prdrive/app/tema app/src/main/res .claude/hooks/generados.py .github/actions/compilar-apk/action.yml && git commit -m "El sistema de diseño en el repositorio, y su generador"`

### Task 2: El tema: paleta, letra y formas

**Files:**
- Modify: `app/src/main/kotlin/prdrive/app/ui/Tema.kt`
- Modify: `app/src/androidTest/kotlin/prdrive/app/TemaTest.kt`
- Modify: `AGENTS.md` («Convenios» y el árbol de «Distribución»)

**Interfaces:**
- Consumes: `Colores` y `Tokens` (Task 1).
- Produces, en `package prdrive.app.ui`:
  - `object Paleta { val actual: Colores @Composable @ReadOnlyComposable get() }` (de `LocalColores`, un `staticCompositionLocalOf { Tokens.claro }`).
  - `data class Tipos(val titulo: TextStyle, val barra: TextStyle, val texto: TextStyle, val nombre: TextStyle, val pista: TextStyle, val rotulo: TextStyle, val mono: TextStyle, val monoPequena: TextStyle)` y `object Letra { val actual: Tipos @Composable @ReadOnlyComposable get() }`, con los valores de «Escala de texto del móvil».
  - `fun esquemaDe(c: Colores, oscuro: Boolean): ColorScheme`, con `background`/`onBackground` = `paper`/`ink`; `surface` y todos los `surfaceContainer*` = `surface`; `surfaceVariant`/`onSurfaceVariant` = `sunken`/`ink2`; `onSurface` = `ink`; `primary`/`onPrimary` = `accent`/`onAccent`; `primaryContainer`/`onPrimaryContainer` = `accentSoft`/`accentStrong`; `secondary*` igual que `primary*`; `tertiary`/`onTertiary` = `warn`/`onWarn`; `tertiaryContainer`/`onTertiaryContainer` = `warnSoft`/`warnInk`; `error`/`onError` = `danger`/`onDanger`; `errorContainer`/`onErrorContainer` = `dangerSoft`/`ink`; `outline` = `border`; `outlineVariant` = `line`; `scrim` = `markInk`.
  - `@Composable fun Tema(contenido: @Composable () -> Unit)` con la misma firma de ahora. Pone `MaterialTheme(colorScheme = esquemaDe(…), typography, shapes)` y los `CompositionLocal` de `Paleta` y `Letra`. En la tipografía, `headlineSmall` = título, `titleMedium` = barra, `titleSmall` = nombre, `bodyLarge` y `bodyMedium` = texto, `bodySmall` = pista y `labelSmall` = rótulo. En las formas, `extraSmall` = 2 dp, `small` y `medium` = 4 dp, `large` = 8 dp y `extraLarge` = 12 dp.

- [ ] **Step 1: Los tests.** En `TemaTest`, que sigue comparando con la ventana y con `LocalConfiguration`:
  - `el_fondo_de_la_ventana_es_el_papel_en_los_dos_modos`: `assertEquals(Tokens.claro.paper.toArgb(), fondoDeLaVentana(false))` y `assertEquals(Tokens.oscuro.paper.toArgb(), fondoDeLaVentana(true))`.
  - `las_pantallas_siguen_al_modo_oscuro_del_telefono`: dentro de `Tema`, `MaterialTheme.colorScheme.background` es `Tokens.claro.paper` en claro y `Tokens.oscuro.paper` en oscuro, y `Paleta.actual` es `Tokens.claro` y `Tokens.oscuro`.
- [ ] **Step 2: Comprobar que fallan.** Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.TemaTest`. Expected: FAIL (el esquema aún es el de serie de Material).
- [ ] **Step 3: Escribir `Tema.kt`** con las interfaces de arriba. El KDoc dice de dónde salen los colores y por qué no se escriben aquí.
- [ ] **Step 4: Comprobar que pasan.** El mismo Run. Expected: PASS.
- [ ] **Step 5: AGENTS.md.** En «Convenios», que los colores salen de `MaterialTheme.colorScheme` o de `Paleta.actual`, y que los valores son los del sistema de diseño, generados en `tema/Tokens.kt` (`app/diseno/`, `herramientas/diseno.py`). En «Distribución», las líneas de `app/diseno/`, `tema/` y `herramientas/diseno.py`.
- [ ] **Step 6: Commit.** `git commit -am "El tema de prdrive: paleta, letra y formas del sistema de diseño"`

### Task 3: Glifos y la marca

**Files:**
- Create: `app/src/main/kotlin/prdrive/app/ui/piezas/Iconos.kt`
- Create: `app/src/main/kotlin/prdrive/app/ui/piezas/Marca.kt`
- Create: `app/src/androidTest/kotlin/prdrive/app/PiezasTest.kt`

**Interfaces:**
- Consumes: `R.drawable.pd_*` (Task 1), `Paleta` (Task 2).
- Produces, en `package prdrive.app.ui.piezas`:
  - `enum class Icono(@DrawableRes val res: Int)`, con una entrada por glifo en mayúsculas (`SYNC`, `DISPOSITIVO`, …, `CANDADO_ABIERTO`, `LLAVE`).
  - `@Composable fun Glifo(icono: Icono, modifier: Modifier = Modifier, tamano: Dp = 16.dp, descripcion: String? = null)`: un `Icon(painterResource(icono.res), descripcion, Modifier.size(tamano), tint = LocalContentColor.current)`.
  - `enum class Insignia { NINGUNA, SINCRONIZANDO, AVISO }`
  - `@Composable fun Marca(tamano: Dp, modifier: Modifier = Modifier, insignia: Insignia = Insignia.NINGUNA)`: la marca dibujada en un `Canvas`, con un `contentDescription` de «prdrive», «prdrive, sincronizando» o «prdrive, con avisos».

- [ ] **Step 1: El test.** En `PiezasTest` (`createComposeRule`, todo dentro de `Tema`), `la_marca_dice_su_estado`: con `Insignia.AVISO`, `onNodeWithContentDescription("prdrive, con avisos").assertExists()`; y con `NINGUNA`, `onNodeWithContentDescription("prdrive").assertExists()`.
- [ ] **Step 2: Comprobar que falla.** Run: `… -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.PiezasTest`. Expected: FAIL (no compila: `Marca` no existe).
- [ ] **Step 3: Escribir `Marca`** con la geometría de `DriveMark` en `bundle.js`, en un lienzo de 64 unidades escalado a `tamano`:
  - Fondo: campo `markBlue` con esquina 14.
  - Arcos de radio 21 y trazo 7 con extremos redondos: de −78° a 78° en `markPaper` y de 102° a 258° en `markAmber`.
  - El cuerpo es el path `STICK` en `markPaper` (a 20 dp o menos, el rectángulo 27,21,10×22 con esquina 2).
  - El filete es un rectángulo en 0,5 de 63×63, con esquina 13,5, trazo `markEdge`.
  - Con insignia, el dibujo va en una capa (`graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }`): un círculo en 47,47 de radio 20 con `BlendMode.Clear` recorta la pastilla, y encima va el disco de radio 16 (`traySync` o `markAmber`) con su glifo en `markInk` y trazo 2,6, en las coordenadas de `bundle.js`.

  `Glifo` va en `Iconos.kt`.
- [ ] **Step 4: Comprobar que pasa.** El mismo Run. Expected: PASS.
- [ ] **Step 5: Commit.** `git add app/src/main/kotlin/prdrive/app/ui/piezas app/src/androidTest/kotlin/prdrive/app/PiezasTest.kt && git commit -m "Los glifos y la marca de prdrive en Compose"`

## Fase 1 · Las piezas comunes

### Task 4: Botones

**Files:**
- Create: `app/src/main/kotlin/prdrive/app/ui/piezas/Botones.kt`
- Modify: `app/src/androidTest/kotlin/prdrive/app/PiezasTest.kt`

**Interfaces:**
- Consumes: `Icono`, `Glifo` (Task 3), `Paleta`, `Letra` (Task 2).
- Produces:
  - `enum class Peso { PRINCIPAL, TONAL, CONTORNO, SILENCIOSO, PELIGRO, PELIGRO_SOLIDO }`
  - `enum class Tamano(val alto: Dp) { PEQUENO(28.dp), NORMAL(34.dp), GRANDE(42.dp) }`
  - `@Composable fun Boton(texto: String, alPulsar: () -> Unit, modifier: Modifier = Modifier, peso: Peso = Peso.CONTORNO, tamano: Tamano = Tamano.GRANDE, icono: Icono? = null, ancho: Boolean = false, ocupado: Boolean = false, activo: Boolean = true, sobreAmbar: Boolean = false)`

- [ ] **Step 1: Los tests** en `PiezasTest`:
  - `todo_boton_se_toca_en_48_dp`: para cada `Tamano`, `onNodeWithTag("b-$t").assertTouchHeightIsEqualTo(48.dp)`.
  - `un_boton_ocupado_no_vuelve_a_disparar`: con `ocupado = true`, dos `performClick()` dejan el contador en 0, y `assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "ocupado"))`.
  - `un_boton_desactivado_lo_dice`: con `activo = false`, `assertIsNotEnabled()`.
- [ ] **Step 2: Comprobar que fallan.** Run: el de `PiezasTest`. Expected: FAIL (no compila).
- [ ] **Step 3: Escribir `Boton`** con los colores de `bundle.css`:

  | `Peso` | fondo | letra | borde |
  |---|---|---|---|
  | `PRINCIPAL` | `accent` | `onAccent` | — |
  | `TONAL` | `accentSoft` | `accentStrong` | — |
  | `CONTORNO` | `surface` | `ink` | `border` (`warnLineStrong` con `sobreAmbar`) |
  | `SILENCIOSO` | — | `accent` (`warnInk` con `sobreAmbar`) | — |
  | `PELIGRO` | `surface` | `danger` | `danger` |
  | `PELIGRO_SOLIDO` | `danger` | `onDanger` | — |
  | desactivado | `disabledBg` | `disabled` | `line` |

  El alto del dibujo es `tamano.alto` y el toque, 48 dp (`minimumInteractiveComponentSize()`), con esquina de 4 dp. Con `ocupado`, un `CircularProgressIndicator` de 14 dp y trazo 2 sustituye al icono, no se puede pulsar y `stateDescription` es «ocupado». Con `ancho`, `fillMaxWidth()`.
- [ ] **Step 4: Comprobar que pasan.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -am "Los botones del sistema, con toque de 48 dp"`

### Task 5: Estado: chip, aviso, línea de estado, progreso y registro

**Files:**
- Create: `app/src/main/kotlin/prdrive/app/ui/piezas/Estados.kt`
- Modify: `app/src/androidTest/kotlin/prdrive/app/PiezasTest.kt`

**Interfaces:**
- Consumes: Tasks 2 a 4.
- Produces:
  - `enum class Tono { OK, EN_MARCHA, AVISO, PELIGRO, APAGADO, NEUTRO }` y `enum class TonoAviso { AVISO, PELIGRO, INFO, OK }`
  - `@Composable fun Chip(texto: String, modifier: Modifier = Modifier, tono: Tono = Tono.NEUTRO, icono: Icono? = null)`: una píldora de 24 dp con un disco de 18 dp y la palabra en tinta. El glifo del disco es `OK`, `SYNC`, `ALERT`, `CLOSE` o `CLOCK` según el tono, o `icono` si se da (`WARN` pasa a `ALERT`). `APAGADO` es hueco, con borde de trazos, y `NEUTRO` va sin disco y con el icono a 12 dp.
  - `@Composable fun Aviso(titulo: String, modifier: Modifier = Modifier, tono: TonoAviso = TonoAviso.AVISO, cuerpo: String? = null, acciones: @Composable RowScope.() -> Unit = {})`: la baldosa de 32 dp (`ALERT`, `CLOSE`, `DOCTOR`, `OK`) con el título y el cuerpo en tinta, sobre el fondo suave del tono y su borde. Con `PELIGRO`, `liveRegion = LiveRegionMode.Assertive`.
  - `@Composable fun LineaDeEstado(icono: Icono, texto: String, modifier: Modifier = Modifier, accion: String? = null, alPulsar: () -> Unit = {}, aviso: Boolean = false, etiquetaAccion: String? = null)`
  - `@Composable fun Progreso(fraccion: Float?, modifier: Modifier = Modifier, texto: String? = null)`: barra de 6 dp sobre `sunken` (`null`: `LinearProgressIndicator` sin fin) y el texto en mono `ink2`.
  - `enum class TonoLinea { CABECERA, ORDEN, BIEN, AVISO, ERROR, NORMAL }`, `fun tonoDeLinea(linea: String): TonoLinea` y `@Composable fun Registro(lineas: List<String>, modifier: Modifier = Modifier)`: mono 12/18, sin ajuste de línea y con desplazamiento horizontal, dentro de un `SelectionContainer`.

- [ ] **Step 1: Los tests** en `PiezasTest`:
  - `un_chip_se_lee_por_su_palabra`: `Chip("al día", tono = Tono.OK)` da `onNodeWithText("al día").assertExists()`, y el disco no se lee (no hay otro nodo con texto ni descripción).
  - `un_aviso_de_peligro_se_anuncia`: el nodo del `Aviso` de `PELIGRO` tiene `SemanticsProperties.LiveRegion` = `LiveRegionMode.Assertive`.
  - `las_lineas_del_log_se_colorean_por_lo_que_dicen`: `tonoDeLinea("=== vault ===")` es `CABECERA`; `tonoDeLinea("  ejecutando: rclone bisync …")` es `ORDEN`; `tonoDeLinea("[vault] OK.")` es `BIEN`; una línea con `ERROR :` es `ERROR`; una con `NOTICE:` es `AVISO`; `"Applying changes"` es `NORMAL`.
- [ ] **Step 2: Comprobar que fallan.** Expected: FAIL (no compila).
- [ ] **Step 3: Escribir `Estados.kt`.** Colores de `bundle.css` (`.pd-chip`, `.pd-pip`, `.pd-notice`, `.pd-tile`, `.pd-statusline`, `.pd-bar`, `.pd-log`). En el registro, la cabecera va en negrita, la orden en `ink3`, bien en `ok`, aviso en `warn` y error en `danger` y negrita.
- [ ] **Step 4: Comprobar que pasan.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -am "Chip, aviso, línea de estado, progreso y registro"`

### Task 6: Listas, casillas y la hoja inferior

**Files:**
- Create: `app/src/main/kotlin/prdrive/app/ui/piezas/Listas.kt`, `app/src/main/kotlin/prdrive/app/ui/piezas/Hoja.kt`
- Modify: `app/src/androidTest/kotlin/prdrive/app/PiezasTest.kt`

**Interfaces:**
- Consumes: Tasks 2 a 5.
- Produces:
  - `@Composable fun Tarjeta(modifier: Modifier = Modifier, contenido: @Composable ColumnScope.() -> Unit)` (`surface`, borde `line`, esquina de 4 dp) y `@Composable fun Separador()` (`lineSoft`).
  - `@Composable fun Rotulo(texto: String, modifier: Modifier = Modifier)`: en mayúsculas y `ink3`, marcado como `heading()`.
  - `@Composable fun Fila(titulo: String, modifier: Modifier = Modifier, alPulsar: (() -> Unit)? = null, icono: Icono? = null, detalle: String? = null, detalleMono: Boolean = false, pista: String? = null, cursiva: Boolean = false, extremo: @Composable (() -> Unit)? = null)`: de 56 dp como mínimo, tocable entera, con la baldosa de 32 dp sobre `sunken` y un chevrón si se pulsa. Es un nodo semántico combinado (`mergeDescendants`), y su `testTag` lo pone quien la usa.
  - `@Composable fun FilaDeAviso(titulo: String, cuerpo: String, tono: TonoAviso, alPulsar: () -> Unit, modifier: Modifier = Modifier)`: de 68 dp, con baldosa y chevrón.
  - `@Composable fun Casilla(texto: String, marcada: Boolean, alCambiar: (Boolean) -> Unit, modifier: Modifier = Modifier, pista: String? = null, activa: Boolean = true)`: la fila entera es `toggleable` (`Role.Checkbox`), con una caja de 18 dp, esquina de 2 dp y el glifo `OK`.
  - `@Composable fun Opcion(titulo: String, elegida: Boolean, alElegir: () -> Unit, modifier: Modifier = Modifier, pista: String? = null)`: una tarjeta `selectable` (`Role.RadioButton`) que, elegida, toma `accentSoft` y un borde `accent` de 2 dp.
  - `@Composable fun Hoja(titulo: String, alCerrar: () -> Unit, contenido: @Composable ColumnScope.() -> Unit)`: un `ModalBottomSheet` (`skipPartiallyExpanded = true`, contenedor `surface`, velo `markInk` a 0,55) con su asa, el título en estilo `barra`, un botón de cerrar solo con icono (`contentDescription` «Cerrar», etiqueta `cerrar-hoja`) y el contenido desplazable.

- [ ] **Step 1: Los tests** en `PiezasTest`:
  - `una_fila_se_toca_entera`: un toque en el texto del `detalle` llama a `alPulsar`.
  - `una_casilla_se_marca_tocando_su_texto`: `onNodeWithText("El llavero").performClick()` cambia `marcada`, y `onNodeWithTag("c").assertIsOn()`.
  - `una_fila_con_letra_grande_no_esconde_su_estado` (Review Focus 1): con `LocalDensity` de `fontScale = 2f`, una `Fila` en 360 dp de ancho con `extremo = { Chip("al día", tono = Tono.OK) }` y un título de 60 caracteres. El chip `assertIsDisplayed()`, y su borde derecho queda dentro de la fila.
  - `la_hoja_se_cierra_con_su_boton`: `onNodeWithTag("cerrar-hoja").performClick()` llama a `alCerrar`.
- [ ] **Step 2: Comprobar que fallan.** Expected: FAIL (no compila).
- [ ] **Step 3: Escribir `Listas.kt` y `Hoja.kt`.**
- [ ] **Step 4: Comprobar que pasan.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -am "Filas, casillas, opciones y la hoja inferior"`

## Fase 2 · Navegación e Inicio

### Task 7: Cuatro secciones y la pila de pantallas

Lienzo: «Pieza · barra de secciones», «Android frente a iPhone».

**Files:**
- Create: `app/src/main/kotlin/prdrive/app/ui/Navegacion.kt`
- Modify: `app/src/main/kotlin/prdrive/app/ui/Pantallas.kt` (`PrdriveApp`)
- Create: `app/src/androidTest/kotlin/prdrive/app/NavegacionTest.kt`

**Interfaces:**
- Consumes: Tasks 2 a 6.
- Produces, en `package prdrive.app.ui`:
  - `enum class Seccion(val titulo: String, val icono: Icono, val etiqueta: String)`: `INICIO("Inicio", DISPOSITIVO, "seccion-inicio")`, `PAREJAS("Parejas", PAREJAS, "seccion-parejas")`, `LLAVERO("Llavero", LLAVE, "seccion-llavero")` y `AJUSTES("Ajustes", GEAR, "seccion-ajustes")`.
  - `sealed interface Destino { data class Ficha(val pareja: String) : Destino; data class Log(val pareja: String) : Destino; data class Mover(val pareja: String) : Destino }`
  - `class Pila`, con `seccion: Seccion` y `cima: Destino?` como estado de Compose; `fun ir(d: Destino)`; `fun cambiar(s: Seccion)` (vacía la pila); `fun atras(): Boolean` (saca la cima; sin cima y fuera de Inicio, vuelve a Inicio; en Inicio sin nada encima, `false`); y `companion object { val Saver: Saver<Pila, List<String>> }`, que la guarda como `["PAREJAS", "ficha:vault", …]`.
  - `@Composable fun rememberPila(): Pila` (`rememberSaveable(saver = Pila.Saver)`).
  - `@Composable fun BarraSecciones(actual: Seccion, alElegir: (Seccion) -> Unit)`: el lienzo «Pieza · barra de secciones». La elegida lleva la pastilla `accentSoft`, el texto `accentStrong` y peso 600, y `selected = true` en su semántica; cada una se etiqueta con su `etiqueta`.
  - `@Composable fun Cabecera(titulo: String, modifier: Modifier = Modifier, volver: (() -> Unit)? = null, etiquetaVolver: String? = null, pie: String? = null, acciones: @Composable RowScope.() -> Unit = {})`: sin `volver`, título grande (estilo `titulo`); con él, un botón de volver de 48 dp (glifo `BACK` y la `etiquetaVolver`) y el título en estilo `barra`.
  - `@Composable fun Secciones(m: Modelo)`: un `Scaffold` con `bottomBar = BarraSecciones`, un `BackHandler(enabled = puede volver) { pila.atras() }` y la pantalla de la cima o de la sección. Cada pantalla raíz se etiqueta `pantalla-inicio`, `pantalla-parejas`, `pantalla-llavero` o `pantalla-ajustes`, y cada ficha, `pantalla-pareja-<nombre>`.
  - `PrdriveApp(modelo)` queda: `Tema { if (modelo.sembrado) Secciones(modelo) else PrimerArranque(modelo) }`.

- [ ] **Step 1: Los tests** en `NavegacionTest` (`createEmptyComposeRule`, `Banco.volumen(Banco.pareja("uno"))` y `ActivityScenario.launch(MainActivity::class.java)`):
  - `las_cuatro_secciones_se_eligen_desde_abajo`: tocar `seccion-<s>` deja visible `pantalla-<s>`, para las cuatro.
  - `atras_desde_una_ficha_vuelve_a_su_lista_y_desde_una_seccion_a_inicio` (Review Focus 3): `seccion-parejas`, `fila-uno`, `Espresso.pressBack()` da `pantalla-parejas`; otro `pressBack()` da `pantalla-inicio`; y otro, con `pressBackUnconditionally()`, deja la actividad en `Lifecycle.State.DESTROYED`.
  - `recrear_la_actividad_no_pierde_la_ficha` (Review Focus 2): con `pantalla-pareja-uno` delante, `escena.recreate()` deja `pantalla-pareja-uno` y `seccion-parejas` marcada (`assertIsSelected()`).
- [ ] **Step 2: Comprobar que fallan.** Run: `… class=prdrive.app.NavegacionTest`. Expected: FAIL (no compila).
- [ ] **Step 3: Escribir `Navegacion.kt`.** Mientras no estén las Tasks 8, 10, 12 y 14, cada sección enseña la pantalla principal de ahora (`Principal` de `Pantallas.kt`) o una vacía con su etiqueta, y la ficha, una vacía con `pantalla-pareja-<nombre>`. Así esta tarea compila y pasa sola, sin romper `PrincipalTest`.
- [ ] **Step 4: Comprobar que pasan**, `NavegacionTest` y `PrincipalTest`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -am "Las cuatro secciones, la pila de pantallas y el botón atrás"`

### Task 8: Inicio

Lienzo: «Inicio · todo al día», «Inicio · hay cosas que revisar», «Inicio · sincronizando» y sus versiones en oscuro.

**Files:**
- Create: `app/src/main/kotlin/prdrive/app/ui/inicio/Revisar.kt`, `app/src/main/kotlin/prdrive/app/ui/inicio/Inicio.kt`
- Create: `app/src/main/kotlin/prdrive/app/ui/Permisos.kt` (se lleva `conRedLocal` y `AvisoRedLocal` de `Pantallas.kt`, sin cambios)
- Modify: `app/src/main/kotlin/prdrive/app/ui/Navegacion.kt` (la rama de `INICIO`, y lo que va arriba de toda sección)
- Modify: `app/src/main/kotlin/prdrive/app/ui/Modelo.kt` (`remoto`)
- Modify: `app/src/main/kotlin/prdrive/app/ui/PantallaLlavero.kt` (`TextosLlavero.linea`)
- Create: `app/src/androidTest/kotlin/prdrive/app/RevisarTest.kt`
- Modify: `app/src/androidTest/kotlin/prdrive/app/PuntaAPuntaTest.kt`, `PantallaLlaveroTest.kt`

**Interfaces:**
- Consumes: `Estado.Fila`, `Estado.FilaLlavero`, `Pasada.Resultado`, `Destino`, `Seccion` y las piezas.
- Produces, en `package prdrive.app.ui.inicio`:
  - `data class Revisar(val tono: TonoAviso, val titulo: String, val cuerpo: String, val destino: Destino?, val seccion: Seccion?)`
  - `fun revisar(filas: List<Estado.Fila>, llavero: Estado.FilaLlavero?, resultados: Map<String, Pasada.Resultado>): List<Revisar>`. Primero los de `PELIGRO` y después los de `AVISO`, y en cada grupo, en el orden de las filas. Por cada fila:
    - con `fallo`: `PELIGRO`, ««X» falló», cuerpo `explicacion` o «La última pasada falló el {cuando}.», hacia `Ficha`;
    - con `sinAcceso`: `PELIGRO`, ««X» no ve su carpeta», cuerpo `sinAcceso`, hacia `Ficha`;
    - con `problemaCompartida`: `AVISO`, ««X» ha vuelto a su carpeta del volumen», cuerpo `problemaCompartida`, hacia `Ficha`;
    - `entrando`: `AVISO`, ««X» está a medio mover», «Termínalo desde su ficha. Hasta entonces no se sincroniza.», hacia `Ficha`;
    - `pideResync` no vacío, sin `entrando` y sin `resultados[X]?.ok == true`: `AVISO`, ««X» pide rehacer la referencia», «No borra nada. Revisa antes lo que va a pasar.», hacia `Ficha`;
    - con conflictos: `AVISO`, «1 fichero en conflicto en «X»» o «N ficheros en conflicto en «X»», «Cambiaron en los dos lados.», hacia `Ficha`.

    Y del llavero: si `ambar`, `AVISO`, «El llavero tiene algo que mirar», cuerpo `TextosLlavero.linea(f)`, hacia la sección `LLAVERO`.
  - `fun estadoDe(f: Estado.Fila, r: Pasada.Resultado?): Pair<Tono, String>`: `PELIGRO` «falló»; `AVISO` «a medio mover», «conflictos» o «revisar»; `OK` «al día»; o `APAGADO` «sin pasada».
  - `@Composable fun Inicio(m: Modelo, ir: (Destino) -> Unit, aSeccion: (Seccion) -> Unit)`
  - En `Modelo`, `var remoto by mutableStateOf<String?>(null)` («nas · sftp»: el `remoteName` de las parejas y su tipo en `Bisync.tiposDeRemote(v.leerRcloneConf())`), que se rellena en `refrescar()`.
  - En `TextosLlavero`, `fun linea(f: Estado.FilaLlavero): String`, con las frases de `EstadoDelLlavero` («…, al día · última pasada …», «… con cambios que aún no han subido», «… la última pasada falló …», «… todavía no está en el teléfono: …»).

- [ ] **Step 1: Los tests.**
  - `RevisarTest`, con filas hechas a mano:
    - `una_pareja_que_fallo_va_la_primera_y_lleva_a_su_ficha`: con una que pide `--resync` y otra que falló, el primero es `PELIGRO`, con `Destino.Ficha` de la que falló.
    - `un_resync_aprobado_en_esta_sesion_no_se_pide_otra_vez`: `resultados` con `ok = true` para esa pareja da una lista vacía.
    - `uno_o_varios_conflictos_se_cuentan_bien`: con 1 y con 3 conflictos, los títulos «1 fichero en conflicto en «vault»» y «3 ficheros en conflicto en «vault»».
    - `sin_nada_que_revisar_la_lista_esta_vacia`.
  - En `NavegacionTest`, `con_treinta_parejas_inicio_se_desplaza_y_la_barra_queda` (Review Focus 5): con `Banco.volumen` de `p0` a `p29`, `onNodeWithTag("fila-p29").performScrollTo().assertIsDisplayed()` y `seccion-ajustes` `assertIsDisplayed()`.
- [ ] **Step 2: Comprobar que fallan.** Expected: FAIL (no compila).
- [ ] **Step 3: Escribir `Revisar.kt` e `Inicio.kt`** como los tres Inicio del lienzo:
  - **Cabecera:** `Marca(40.dp, insignia)` (`SINCRONIZANDO` con `m.ocupado`; `AVISO` si `revisar` no está vacía), «Este teléfono» y `m.remoto` en mono.
  - **Cabecera de estado:**
    - con `m.ocupado`: chip `EN_MARCHA` «en marcha», el título es `m.ocupado` y debajo va `Progreso(m.progreso?.fraccion, texto)`;
    - con cosas que revisar: chip `AVISO` «N cosas» y «Hay N cosas que revisar» («Hay 1 cosa que revisar» con una);
    - si no: chip `OK` «al día», «Todo al día» y «Última pasada {haceCuanto}» (o «Todavía sin ninguna pasada»).
  - **Acción principal:** `Boton("Sincronizar ahora", peso = PRINCIPAL, icono = SYNC, ancho = true, ocupado = m.ocupado != null)` con la etiqueta `sincronizar` y `conRedLocal`.
  - **Arriba de toda sección,** en `Secciones` y no en `Inicio`: el texto de `m.ocupado` (etiqueta `ocupado`), el error en un `Aviso(PELIGRO)` (etiqueta `error`) y `AvisoRedLocal` si falta el permiso.
  - **Cosas que revisar:** las de `revisar`, en `FilaDeAviso` con la etiqueta `revisar-<i>`.
  - **Llavero:** `LineaDeEstado(LLAVE, TextosLlavero.linea(f))`, con la etiqueta `fila-llavero`. Si está activo, con la acción «Abrir llavero» (etiqueta `abrir-llavero`, `conRed(m::abrirLlavero)`); si se puede traer, con «Traer…» (etiqueta `ir-llavero`), que va a la sección.
  - **Parejas:** un `Rotulo`, «Ver todas» y una `Tarjeta` con una `Fila` por pareja (etiqueta `fila-<nombre>`, glifo `BOTH`, `UP` o `DOWN` según el modo, `<local> ↔ <remoto>` en mono y `Chip` de `estadoDe`), que va a `Ficha`.
  - **Versión nueva:** la tarjeta de ahora, `VersionNueva`, como un `Aviso(INFO)` con sus botones y las mismas etiquetas (`version-nueva`, `version-lista`, `instalar-version`).

  `Pie` deja de estar en Inicio (pasa a Ajustes en la Task 14).
- [ ] **Step 4: Actualizar los tests que leían la principal.** En `PuntaAPuntaTest`, el «Al día» y el «Falló» dentro de `fila-<n>` pasan a ser los textos del chip («al día» y «falló»). La ausencia de `resync-notas` se mira en la ficha (Task 10): hasta entonces, la de `revisar-*` con «pide rehacer la referencia». En `PantallaLlaveroTest`, el texto que se espera en `fila-llavero` es el de `TextosLlavero.linea`, que conserva las frases de ahora.
- [ ] **Step 5: Comprobar que pasan** `RevisarTest`, `NavegacionTest`, `PrincipalTest` y `PantallaLlaveroTest`. Expected: PASS.
- [ ] **Step 6: Commit.** `git commit -am "Inicio: cómo está todo en una frase, lo que hay que revisar y las parejas"`

## Fase 3 · Parejas y sus arreglos

### Task 9: Lo que la fila sabe de más

Lienzo: «Una pareja · detalle», variante «no-se-puede» (issue #24, la parte pequeña).

**Files:**
- Modify: `app/src/main/kotlin/prdrive/app/Estado.kt`
- Modify: `app/src/main/kotlin/prdrive/app/ui/Modelo.kt`
- Modify: `app/src/androidTest/kotlin/prdrive/app/PantallaCompartidaTest.kt`

**Interfaces:**
- Produces:
  - En `Estado.Fila`: `val remoto: String = ""` (`p.remotePath`) y `val porQueNoSeComparte: String? = null`. Este último es `Compartidas.problemaPareja(config, p.name)` cuando `conCompartir`, la pareja no tiene entrada y el problema no es `null`; si no, `null`.
  - En `Modelo`, `var sinUsar by mutableStateOf<List<String>>(emptyList())`: los nombres de `Catalogo.cached(v.state)?.names` que no están en `config.names`, en `refrescar()`.
  - En `Modelo`, `fun sincronizar(solo: String? = null)`: el mismo bucle de ahora, solo para la pareja `solo` cuando se da, y sin el llavero en ese caso.

- [ ] **Step 1: Los tests** en `PantallaCompartidaTest`:
  - `una_pareja_anidada_dice_por_que_no_se_mueve`: con `Banco.pareja("notas")` (`sync-data/notas`) y `conCompartir = true`, `porQueNoSeComparte` no es `null` y `sePuedeCompartir` es `false`. Con `local = "vault"`, es `null`.
  - `sincronizar_una_pareja_no_pasa_las_demas`: con las parejas `uno` y `dos`, `modelo.sincronizar(solo = "uno")` deja un resultado de `uno` y ninguno de `dos`.
- [ ] **Step 2: Comprobar que fallan.** Expected: FAIL (no compila).
- [ ] **Step 3: Escribirlo.**
- [ ] **Step 4: Comprobar que pasan.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -am "La fila dice por qué una pareja no se puede mover (#24), y una pareja se sincroniza sola"`

### Task 10: Parejas y la ficha de una pareja

Lienzo: «Parejas», «Una pareja · detalle» (sus tres variantes) y «Oscuro · una pareja».

**Files:**
- Create: `app/src/main/kotlin/prdrive/app/ui/parejas/Parejas.kt`, `app/src/main/kotlin/prdrive/app/ui/parejas/Ficha.kt`, `app/src/main/kotlin/prdrive/app/ui/parejas/Log.kt`
- Modify: `app/src/main/kotlin/prdrive/app/ui/Navegacion.kt` (las ramas de `PAREJAS`, `Ficha` y `Log`)
- Modify: `app/src/main/kotlin/prdrive/app/ui/Pantallas.kt` (se quitan `FilaDePareja` y `DialogoLog`)
- Modify: `app/src/androidTest/kotlin/prdrive/app/PantallaCompartidaTest.kt`, `PuntaAPuntaTest.kt`, `Banco.kt`

**Interfaces:**
- Consumes: Tasks 7 a 9.
- Produces:
  - `@Composable fun Parejas(m: Modelo, ir: (Destino) -> Unit)`: el título «Parejas», «N de M del catálogo se usan aquí» y una `Fila` por pareja (etiqueta `fila-<nombre>`). Debajo, «Del catálogo, sin usar aquí», con `m.sinUsar` en cursiva y el chip `APAGADO` «no se usa aquí». Aún no hay «Añadir…» (fase 5).
  - `@Composable fun Ficha(m: Modelo, nombre: String, ir: (Destino) -> Unit, volver: () -> Unit)`. Sus bloques:
    - **Cabecera:** volver a «Parejas», el chip de `estadoDe` y «Última pasada buena {haceCuanto}».
    - **Sincronizar:** `Boton("Sincronizar esta pareja", PRINCIPAL)`, con la etiqueta `sincronizar-<nombre>`, `conRed { m.sincronizar(solo = nombre) }` y `activo = m.ocupado == null && !f.entrando`.
    - **Cómo se sincroniza:** el modo en palabras (bisync: «En los dos sentidos: lo que cambias en un lado cambia en el otro, también los borrados.»; subida, bajada y los espejos, con la frase del espejo de ahora), «En el teléfono» (`compartida` o `local`) y «En el remoto» (`remoto`), en mono.
    - **Dónde vive en este teléfono:** las tres variantes del lienzo, con las etiquetas de ahora: `donde-<n>`, `volver-<n>`, `terminar-<n>`, `permiso-<n>` y `mover-<n>`, que va a `Destino.Mover`. Además, `no-compartir-<n>`: un `Aviso(AVISO)` «No se puede mover a una carpeta compartida» con `porQueNoSeComparte`.
    - **Arreglos:** `pideResync` es una `FilaDeAviso` con la etiqueta `resync-<n>`, que abre la hoja de la Task 11. Los conflictos son una `Tarjeta` con los ficheros en mono y el texto de ahora.
    - **Log:** «Ver el log» (etiqueta `ver-log-<n>`, solo si hay log), que va a `Destino.Log`.
  - `@Composable fun LogDePareja(m: Modelo, nombre: String, volver: () -> Unit)`: la explicación en un `Aviso(PELIGRO)` y `Registro(Pasada.cola(texto, 200))`, leído fuera del hilo de la pantalla como `DialogoLog`.
  - En `Banco` (`androidTest`), `fun abrirFicha(compose: ComposeTestRule, nombre: String)`: toca `seccion-parejas` y `fila-<nombre>`, y espera a `pantalla-pareja-<nombre>`.

- [ ] **Step 1: Los tests.**
  - En `PantallaCompartidaTest`, cada test abre la ficha con `Banco.abrirFicha` antes de buscar `donde-`, `volver-`, `mover-`, `terminar-` o `resync-`. Se añade `una_pareja_anidada_lo_dice_en_su_ficha`: `no-compartir-notas` existe y `mover-notas` no.
  - En `PuntaAPuntaTest`, lo mismo para `donde-vault` y `resync-notas`.
  - En `NavegacionTest`, `con_un_trabajo_en_marcha_la_ficha_apaga_sus_botones` (Review Focus 4). Las parejas `uno` y `dos`, con 3000 ficheros pequeños en `Banco.remoto` para `dos`, para que su pasada dure segundos. Se abre la ficha de `uno` y se pulsa «Sincronizar» de Inicio; con `ocupado` en pantalla, `sincronizar-uno` `assertIsNotEnabled()`, y al acabar, `assertIsEnabled()`.
- [ ] **Step 2: Comprobar que fallan.** Expected: FAIL.
- [ ] **Step 3: Escribir las tres pantallas** y conectarlas en `Secciones`.
- [ ] **Step 4: Comprobar que pasan** `PantallaCompartidaTest`, `NavegacionTest` y `PrincipalTest`, y `PuntaAPuntaTest` con su servidor (`python herramientas/punta_a_punta.py ../prdrive <rclone> <dir>`, como dice AGENTS.md). Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -am "Parejas y la ficha de cada pareja"`

### Task 11: Rehacer la referencia y mover a una carpeta compartida

Lienzo: «Rehacer la referencia (--resync)», «Mover a una carpeta compartida» y «Moviendo la pareja».

**Files:**
- Create: `app/src/main/kotlin/prdrive/app/ui/parejas/RehacerReferencia.kt`, `app/src/main/kotlin/prdrive/app/ui/parejas/Mover.kt`
- Modify: `app/src/main/kotlin/prdrive/app/ui/parejas/Ficha.kt`, `app/src/main/kotlin/prdrive/app/ui/Navegacion.kt`, `app/src/main/kotlin/prdrive/app/ui/Pantallas.kt` (se quita `DialogoMover`)
- Modify: `app/src/androidTest/kotlin/prdrive/app/PantallaCompartidaTest.kt`

**Interfaces:**
- Consumes: `Hoja`, `Aviso`, `Modelo.aprobarResync`, `Modelo.moverACompartida`, `Compartir.AVISO_EXPOSICION`, `Prdrive.permisoCompartido()` y `darPermisoCompartido`.
- Produces:
  - `@Composable fun RehacerReferencia(m: Modelo, f: Estado.Fila, alCerrar: () -> Unit)`, una `Hoja`:
    - el título «Rehacer la referencia de «X»» y `Aviso(INFO, "Esto es lo que va a pasar. Nada se ha escrito todavía.")`;
    - tres líneas: «Lo que solo está en un lado se copia al otro.», «No se borra nada, en ningún lado.» y «Después, las pasadas vuelven a ser normales.»;
    - «Por qué lo pide», con `f.pideResync` sobre `sunken`;
    - los botones «Rehacer la referencia» (`PRINCIPAL`, etiqueta `confirmar-resync`), que cierra la hoja y hace `conRed { m.aprobarResync(f.nombre) }`, y «Ahora no».
  - `@Composable fun Mover(m: Modelo, nombre: String, volver: () -> Unit)`: la pantalla del lienzo con lo que la app sabe hoy.
    - El `Aviso(AVISO)` «Ojo: fuera de la app deja de ser privada», con `Compartir.AVISO_EXPOSICION`.
    - «Antes de mover»:
      1. el permiso: chip `OK` «dado», o el botón «Dar permiso» (etiqueta `dar-permiso`) y el texto de `Prdrive.SIN_PERMISO_COMPARTIDO`;
      2. la carpeta: «Elegir la carpeta» (etiqueta `elegir-carpeta`), activo solo con el permiso, que abre el `OpenDocumentTree` en `primary:Documents` y guarda el árbol elegido (`rememberSaveable`, un `Uri`), y la ruta en mono;
      3. «Las fechas se comprueban al empezar: si no se conservan, no se mueve nada.».
    - «Qué va a pasar», con las cuatro frases del lienzo.
    - `Boton("Mover «X»", PRINCIPAL)` (etiqueta `mover-ya`), activo con el permiso y una carpeta elegida. Hace `conRed { m.moverACompartida(nombre, arbol) }` y vuelve a la ficha.
    - Mientras se mueve, la ficha enseña `Progreso` y `Registro(m.lineas)`: el lienzo «Moviendo la pareja», sin los pasos marcados (fase 5).

- [ ] **Step 1: Los tests** en `PantallaCompartidaTest`:
  - `el_resync_se_confirma_en_una_hoja`: una pareja bisync sin listados pide `--resync`; en su ficha, `resync-<n>` abre la hoja (`confirmar-resync` existe), y «Ahora no» la cierra sin pasada (sin resultado de la pareja).
  - `mover_pide_el_permiso_antes_de_elegir_la_carpeta`: con `appops set prdrive.app MANAGE_EXTERNAL_STORAGE deny`, en `Destino.Mover`, `dar-permiso` existe y `elegir-carpeta` `assertIsNotEnabled()`. Con `allow` y al volver a la app (`escena.moveToState(RESUMED)`), `elegir-carpeta` `assertIsEnabled()`.
- [ ] **Step 2: Comprobar que fallan.** Expected: FAIL.
- [ ] **Step 3: Escribirlo.**
- [ ] **Step 4: Comprobar que pasan**, y `MudanzaEnAndroidTest` y `PuntaAPuntaTest` siguen en verde. `PuntaAPuntaTest` llama a `Mudanza.entrar` directamente y no pasa por el selector. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -am "Rehacer la referencia en una hoja y mover a una carpeta compartida en su pantalla"`

## Fase 4 · Llavero, emparejar y ajustes

### Task 12: La sección Llavero

Lienzo: «Llavero» (sus tres estados) y «Oscuro · llavero con un guardado aparte».

**Files:**
- Create: `app/src/main/kotlin/prdrive/app/ui/llavero/SeccionLlavero.kt`
- Modify: `app/src/main/kotlin/prdrive/app/ui/PantallaLlavero.kt`. Se quedan `TextosLlavero`, `conAvisos`, `AbrirLaBase`, `intentDeAbrir`, `conLaLlave`, `AvisoAntesDeAbrir` y `CasillaDelLlavero`, que los tests importan de `prdrive.app.ui`. Se quitan `FilaDelLlavero` y `EstadoDelLlavero`; `ConfirmarTraer` y `ConfirmarQuitar` pasan a `SeccionLlavero.kt`.
- Modify: `app/src/androidTest/kotlin/prdrive/app/PantallaLlaveroTest.kt`, `PuntaAPuntaTest.kt`

**Interfaces:**
- Produces: `@Composable fun SeccionLlavero(m: Modelo, conRed: (() -> Unit) -> Unit, conPermiso: (() -> Unit) -> Unit)`.
  - **Activo:** el chip (`OK` «al día», o `AVISO` con lo que haya: «un guardado aparte», «cambios sin subir», «falló») y la línea `TextosLlavero.linea` (etiqueta `llavero-estado`). Debajo, `Boton("Abrir llavero", PRINCIPAL, icono = CANDADO_ABIERTO, ancho = true)` (etiqueta `abrir-llavero`) y la pista «Se abre en tu app de KeePass. Lo que guardes sube solo, unos 20 s después, con conexión.».
  - **Con `ambar`:** los motivos de ahora, en `Aviso`s y con la etiqueta `llavero-ambar` en su contenedor. Con copias de conflicto, «Copias de conflicto · N» con los nombres en mono.
  - **«Cómo funciona»:** las tres filas del lienzo (sube al guardar, trae lo último al abrir y el fichero llave, que solo sale con `llaveInterna`).
  - **Quitar:** `Boton("Quitar el llavero de este teléfono", PELIGRO)` (etiqueta `quitar-llavero`), que abre `ConfirmarQuitar` (etiqueta `confirmar-quitar`). Ya no hay menú: `menu-llavero` desaparece.
  - **Sin activar y con `traer`:** chip `APAGADO` «no está en este teléfono», `TextosLlavero.QUE_ES` y «Traer el llavero…» (etiqueta `traer-llavero`), que abre `ConfirmarTraer` (`confirmar-traer`, `elegir-llave`).
  - **Sin llavero en ningún sitio** (`m.llavero == null`): el texto de `TextosLlavero.SIN_LLAVERO_REMOTO`.

- [ ] **Step 1: Los tests.** En `PantallaLlaveroTest` y `PuntaAPuntaTest`, un ayudante `irAlLlavero()` (toca `seccion-llavero`) antes de buscar `llavero-ambar`, `traer-llavero`, `quitar-llavero` o `confirmar-*`. `menu-llavero` deja de tocarse. `abrir-llavero` sigue funcionando desde Inicio y desde la sección: se prueba en las dos, con un test nuevo, `abrir_llavero_desde_su_seccion_abre_la_base`, que intercepta el `ACTION_VIEW` como los de ahora.
- [ ] **Step 2: Comprobar que fallan.** Expected: FAIL.
- [ ] **Step 3: Escribir `SeccionLlavero`** y conectarla en `Secciones`.
- [ ] **Step 4: Comprobar que pasan** `PantallaLlaveroTest`, `LlaveroAltaTest` y `PuntaAPuntaTest`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -am "El llavero, en su sección"`

### Task 13: Emparejar en cuatro pasos

Lienzo: «1 · Bienvenida», «2 · Código leído», «3 · Elegir parejas» y «4 · Primera pasada».

**Files:**
- Create: `app/src/main/kotlin/prdrive/app/ui/emparejar/Emparejar.kt`
- Modify: `app/src/main/kotlin/prdrive/app/ui/Pantallas.kt` (`PrimerArranque` pasa a `Emparejar.kt`: quedan `PrdriveApp`, `haceCuanto`, `flecha` y lo que use otra pantalla)
- Modify: `app/src/androidTest/kotlin/prdrive/app/PuntaAPuntaTest.kt`, `PantallaLlaveroTest.kt`

**Interfaces:**
- Produces: `@Composable fun Emparejar(m: Modelo)`, con el indicador «Paso N de 3» (tres segmentos de 4 dp; la bienvenida no lo lleva). Los cuatro pasos:
  1. **Bienvenida** (`Paso.CODIGO` sin `carga`): `Marca(72.dp)`, «Empareja este teléfono», los tres pasos numerados del lienzo, «Escanear el código» (`PRINCIPAL`, etiqueta `escanear`) y «Pegar el texto del código» (`TONAL`). Este último despliega el campo de ahora, con la etiqueta `carga`: con contraseña, sin autocorrección y en `remember`, no en `rememberSaveable`. También despliega «Usar este texto» (`usarTexto`).
  2. **Código leído** (`Paso.CODIGO` con `carga`): una `Tarjeta` con el remote (`c.describe()`), el catálogo (`c.catalogPath`) y la clave (chip `OK` «incluida» con la frase de ahora) y el `Aviso(INFO)` de la red local. Debajo, «Conectar y leer el catálogo» (`conectar`) y «Escanear otro código».
  3. **Elegir parejas** (`Paso.PAREJAS`): `Casilla` por pareja (`elegir-<n>`, con el modo en chip y las rutas en mono). Los espejos van sobre `dangerSoft`, con el chip `PELIGRO` «espejo de …» y la frase de ahora. También van las notas del catálogo (`nota-catalogo`) y «El llavero» como `Casilla` (`elegir-llavero`, la misma lógica que `CasillaDelLlavero`). Abajo, fijo, el botón «Instalar N parejas» o «Instalar 1 pareja», con « y el llavero» si va (`instalar`).
  4. **Primera pasada** (`Paso.INICIANDO`):
     - la frase del `--resync`, `Progreso` y una fila por resultado (`resultado-<n>`), con el chip `OK` «hecha», `NEUTRO` «saltada» o `PELIGRO` «falló» y la explicación debajo;
     - el `Aviso(AVISO)` de los espejos (`aviso-espejos`);
     - «Ver el log», que despliega `Registro(m.lineas)`;
     - «Ir al inicio» (`empezar`), que sale, como ahora, solo cuando `inicioTerminado && ocupado == null`.

- [ ] **Step 1: Los tests.** En `PuntaAPuntaTest` y `PantallaLlaveroTest`, antes de `carga`, un toque en «Pegar el texto del código» (`onNodeWithText`). Lo que se esperaba en `resultado-<n>` («<n>: OK») pasa a ser el chip «hecha» dentro de `resultado-<n>`. Las etiquetas de los cuatro pasos se conservan.
- [ ] **Step 2: Comprobar que fallan.** Expected: FAIL.
- [ ] **Step 3: Escribir `Emparejar.kt`.**
- [ ] **Step 4: Comprobar que pasan** `PantallaLlaveroTest` (emparejar con el llavero) y `PuntaAPuntaTest` con su servidor. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -am "Emparejar en cuatro pasos"`

### Task 14: Ajustes

Lienzo: «Ajustes», sin «Sincronizar sola» (fase 5).

**Files:**
- Create: `app/src/main/kotlin/prdrive/app/ui/ajustes/Ajustes.kt`
- Modify: `app/src/main/kotlin/prdrive/app/ui/Pantallas.kt` (se quita `Pie`), `app/src/main/kotlin/prdrive/app/ui/Navegacion.kt`
- Create: `app/src/androidTest/kotlin/prdrive/app/AjustesTest.kt`

**Interfaces:**
- Produces: `@Composable fun Ajustes(m: Modelo)`. Los permisos se vuelven a mirar al volver a la app (`LifecycleResumeEffect`). Sus bloques:
  - **Remoto:** `m.remoto` y el catálogo en mono.
  - **Permisos:** una fila por permiso, con su chip `OK` «dado» o su botón.
    - «Red local», solo desde API 37: `Prdrive.faltaRedLocal`, y el botón «Dar» por `conRedLocal`.
    - «Acceso a todos los archivos», desde API 30 y solo si alguna pareja lo usa o se puede mover: `Prdrive.permisoCompartido()` y el botón «Abrir ajustes» con `darPermisoCompartido`.
    - «Notificaciones», desde API 33: `POST_NOTIFICATIONS`, con el botón «Dar» por `conAvisos`.
    - «Instalar apps»: `Actualizador.faltaPermiso`, con el botón «Permitir» hacia `ACTION_MANAGE_UNKNOWN_APP_SOURCES`.
  - **Actualizaciones:** «prdrive X · rclone Y», «Buscar ahora» (`buscar-version`) y `Actualizador.aviso` (`aviso-version`), lo que hoy hace `Pie`.

- [ ] **Step 1: Los tests** en `AjustesTest`:
  - `buscar_actualizaciones_esta_en_ajustes`: después de `seccion-ajustes`, existe `buscar-version`, y en Inicio no.
  - `el_permiso_de_red_local_se_ve_dado_o_por_dar` (solo con API 37, si no `assumeTrue`): se quita con `revokeRuntimePermission`, se ve «Dar»; se da con `grantRuntimePermission`, se vuelve a la app y se ve «dado».
- [ ] **Step 2: Comprobar que fallan.** Expected: FAIL.
- [ ] **Step 3: Escribir `Ajustes.kt`.**
- [ ] **Step 4: Comprobar que pasan**, y `ActualizadorTest`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -am "Ajustes: el remoto, los permisos y las actualizaciones"`

### Task 15: Cierre de las fases 0 a 4

**Files:**
- Modify: `app/src/main/kotlin/prdrive/app/ui/Pantallas.kt` (lo que quede sin usar fuera; si solo queda `PrdriveApp`, se lleva a `Navegacion.kt` y el fichero se borra)
- Modify: `AGENTS.md` («Distribución»: `ui/piezas/`, `ui/inicio/`, `ui/parejas/`, `ui/llavero/`, `ui/emparejar/`, `ui/ajustes/`, `Navegacion.kt`, `Permisos.kt`, `diseno/`, `tema/`; `androidTest/`: `PiezasTest`, `NavegacionTest`, `RevisarTest`, `AjustesTest`)
- Modify: `PLAN.md` (una sección «El rediseño», con lo hecho y la fase 5)

- [ ] **Step 1: Toda la batería.** Run: `./gradlew :app:connectedDebugAndroidTest` en un emulador API 37 y en uno API 30. Además, `PuntaAPuntaTest` con su servidor, y `./gradlew :engine:test`. Expected: todo en verde.
- [ ] **Step 2: A mano, en oscuro y con letra grande.** `adb shell cmd uimode night yes` y `adb shell settings put system font_scale 1.3`. Se recorren Inicio, una ficha, la hoja de resync, el llavero y ajustes, y se compara con la fila «Modo oscuro» del lienzo. Lo que no se parezca se apunta en `PLAN.md`. Después, `night no` y `font_scale 1.0`.
- [ ] **Step 3: Documentación.** `AGENTS.md` y `PLAN.md` como dice arriba.
- [ ] **Step 4: Commit.** `git commit -am "El rediseño, de punta a punta y documentado"`

---

## Fase 5 · Lo nuevo (cada uno, su plan)

Estas pantallas del lienzo piden algo que la app o el motor no hacen todavía. Cada una lleva su propio plan, con su spec, cuando se decida hacerla. Lo que necesita cada una:

- **Parar la pasada** (lienzo «Inicio · sincronizando»): `Modelo.parar()`, que cancela el trabajo en marcha. Al interrumpirse, `Rclone.trabajo` ya hace `job/stop`, espera y aparta los listados. Falta comprobar contra rclone de verdad (en `PasadaEnAndroidTest`) que la siguiente pide `--resync` y no borra.
- **Simulacro** (lienzo «Simulacro de un espejo»; `PLAN.md` §3): una pasada con `--dry-run` que no se apunte como pasada. El motor tendría que leer de su log lo que se copiaría y lo que se borraría, con el tope (`--max-delete`, en ficheros en los espejos). Con eso, la carpeta de un espejo de subida podría crearse al emparejar.
- **Resolver conflictos** (lienzo «Resolver conflictos»; `PLAN.md` §4): en `Conflictos`, quedarse con una versión, con la otra o con las dos, y que la pasada siguiente lo suba.
- **Sincronizar sola** (lienzo «Ajustes», «Sincronizar sola»; `PLAN.md` §4): un trabajo periódico de WorkManager con el `[daemon]` del config, solo con wifi o mientras carga si se pide, con la línea «La próxima, en …» de Inicio.
- **Historial de pasadas** (ficha, «Últimas pasadas»): el diario de `historial.py`, en `state/`.
- **Elegir y cambiar la carpeta de una pareja, añadirla desde el catálogo y quitarla de este teléfono** (issues #25 y #24, la parte grande, después de Jeremaya25/prdrive#196): «Añadir…», «Cambiar la carpeta…» y «Quitar de este teléfono».
- **Los pasos de la mudanza** (lienzo «Moviendo la pareja»): que `Mudanza.Entorno` diga en qué paso va, para marcarlos.
- **Reparación** (`--force`), **Ver en Archivos** y **Emparejar de nuevo** (lienzo «Ajustes»).
- **iPhone** (lienzo «Android frente a iPhone»): decidir entre SwiftUI y Compose Multiplatform, y si `engine/` pasa a Kotlin Multiplatform, que ya lo permite al no saber nada de Android. La tabla del lienzo dice lo que cambia: Archivos con un File Provider, la carpeta para Obsidian con permiso por carpeta, el llavero desde Archivos, el segundo plano y las versiones nuevas por App Store o TestFlight.
