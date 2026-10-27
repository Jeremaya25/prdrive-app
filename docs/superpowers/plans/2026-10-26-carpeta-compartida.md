# Una pareja en una carpeta compartida — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que una pareja bisync elegida viva en una carpeta del almacenamiento compartido, donde Obsidian para Android la abre como vault, sin que cambie nada para el escritorio ni se pueda borrar nada en el remoto por el camino.

**Architecture:** El upstream de la pareja en el remote `combine` (`[disp]`) apunta a la carpeta compartida, según un fichero propio del teléfono (`.prdrive/compartidas.json`); el nombre de sesión, los filtros y el TOML no cambian. El motor gana el fichero y sus reglas (`Compartidas.kt`), la redirección (`Model.kt`, `Volumen.kt`), los frenos de la pasada (`Pasada.kt`) y la secuencia de mover y volver (`Mudanza.kt`). La app pone el permiso, la carpeta, la prueba de fechas, la fila y la regla del proveedor.

**Tech Stack:** Kotlin 2.4 (motor sin dependencias, bytecode 17), Android con Compose, rclone como biblioteca (`.aar`). Ninguna dependencia nueva.

**Spec:** `docs/superpowers/specs/2026-10-26-carpeta-compartida-design.md` (issue #10).

## Global Constraints

- **La Task 1 decide si se sigue.** Si las fechas no se conservan en algún nivel de API probado, se para ahí, se apunta en `PLAN.md` y se avisa: no se empieza la Task 2.
- `engine/` no importa nada de Android ni tiene dependencias, tampoco de test (solo `kotlin-test`).
- `Compartidas.kt` y `Mudanza.kt` no reflejan ningún fichero de prdrive: su KDoc lo dice («diferencia del teléfono, como el `device_remote` obligatorio») y cita la spec.
- Nada de esto refleja a prdrive ni a rclone, así que no hay vectores que generar. Los tests comparan contra el mismo config sin entradas, contra constantes del propio código (`Mudanza.RESYNC_MODE`, `upstream()`) o comprueban `null`/no `null`; ningún texto esperado se copia a mano.
- Regex en `engine/` con clases a mano; nada que acabe en un fichero pasa por `String.format`.
- `:engine:test` corre en Linux (CI) y en Windows: las rutas de los tests del motor son temporales de la máquina, y las reglas de rutas no pueden depender de que empiecen por `/`.
- Comentarios, KDoc y textos para el usuario en español. Tests: frase entre acentos graves en `engine/`; con guiones bajos en `androidTest`.
- Los avisos del compilador son errores (`allWarningsAsErrors`). En las pantallas, ningún color escrito: todo de `MaterialTheme.colorScheme`.
- El fichero: `.prdrive/compartidas.json`, `{"<pareja>": {"ruta": "<absoluta>", "estado": "entrando" | "lista"}}`.
- Solo carpetas de destino vacías o sin crear. Solo desde Android 11 (API 30), con `MANAGE_EXTERNAL_STORAGE`. Solo el almacenamiento `primary`.
- Los dos `--resync` del movimiento llevan `resync-mode = path2` (`Mudanza.RESYNC_MODE`).
- Orden de escritura: al mover, la entrada antes que `[disp]`; al volver, `[disp]` antes que quitar la entrada. Los listados se apartan antes de las dos cosas.
- El movimiento es un trabajo (`Actualizador.empezarTrabajo()`/`acabarTrabajo()`, que ya hace `Modelo.trabajo`) y sus pasadas van por `Adaptadores.pasada`, de una en una.
- No se toca `VERSION`: publicar lo decide la persona.
- Órdenes: `./gradlew :engine:test` (sin SDK); `./gradlew :app:connectedDebugAndroidTest` con `ANDROID_SERIAL=emulator-55xx` si hay más de un dispositivo; una clase sola con `-Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.<Clase>`. En Windows, si Gradle no arranca: `export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'`.

## Review Focus

1. **Obsidian abre la carpeta antes de que acabe el `--resync`** y crea su `.obsidian/` de fábrica: gana lo del remoto (`resync-mode = path2`) y la `.obsidian/` del remoto no se pisa. Test en Task 5.
2. **El proceso muere entre escribir la entrada y reescribir `[disp]`**: la pasada siguiente aborta (el `rclone.conf` no lleva la carpeta) en vez de sincronizar la carpeta privada, y «Terminar de mover» lo arregla. Tests en Tasks 4 y 5.
3. **`compartidas.json` roto** (un corte de disco, una edición a mano): se dice, y nunca se lee como «no hay ninguna», que dejaría las parejas apuntando a la carpeta privada. Test en Task 2.
4. **Activar o quitar el llavero después de mover** (reescriben `[disp]` desde el TOML, sin pasar por `leerConfig()`): la redirección sigue. Test en Task 3.
5. **Un árbol que no es del almacenamiento principal** (una SD, Descargas, Google Drive) en el selector: se rechaza diciendo por qué, sin tocar nada. Test en Task 6.

---

### Task 1: El paso 0 — las fechas en el almacenamiento compartido (sí o no)

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/androidTest/kotlin/prdrive/app/FechasCompartidasTest.kt`
- Modify: `PLAN.md`, `AGENTS.md` («Ojo con Android»)

**Interfaces:**
- Produces: el permiso `android.permission.MANAGE_EXTERNAL_STORAGE` declarado (lo necesitan `appops` y todas las tareas de app). Nada más: esta tarea no deja código de producción.

- [ ] **Step 1: El permiso.** En el manifiesto, `xmlns:tools` en `<manifest>` y `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:ignore="ScopedStorage" />`, con un comentario: para que rclone escriba por ruta en una carpeta compartida lo que crea otra app (Obsidian); se pide solo al mover una pareja.
- [ ] **Step 2: Los tests.** `FechasCompartidasTest`, con `@get:Rule val legible = Banco.Legible()`. En `@Before`: `InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("appops set prdrive.app MANAGE_EXTERNAL_STORAGE allow").close()`, `assertTrue(Environment.isExternalStorageManager())`, y la carpeta `File(Environment.getExternalStorageDirectory(), "Documents/prdrive-fechas-${System.nanoTime()}")`, que `@After` borra.
  - `rclone_conserva_la_fecha_de_lo_que_copia_a_una_carpeta_compartida`: un fichero en `Prdrive.temporal(contexto)` con `setLastModified` a una hora pasada con milisegundos distintos de cero; `operations/copyfile` (`srcFs` su carpeta, `dstFs` la compartida); `operations/stat` de los dos (`item.ModTime`, `Instant.parse`); `operations/fsinfo` de la compartida (`Precision`, en ns). `assertTrue(|copiada − original| ≤ precision)`; tras `Thread.sleep(2000)`, otro `stat` da la misma hora. `Log.i("FechasCompartidas", …)` con el API, la precisión y las tres horas.
  - `una_bisync_contra_una_carpeta_compartida_no_repite_copias`: `Banco.volumen(Banco.pareja("notas", "notas"))`; el `rclone.conf` reescrito a mano con `[nas]` local y `[disp]` de tipo `combine` con `upstream("notas", carpeta.absolutePath)`, y `fscache/clear`. Un fichero en `Banco.remoto/notas` y otro en la carpeta; `--resync` (`Adaptadores.pasada.ejecutar(Banco.llamada(v, p, resync = true)) {}`); un cambio en cada lado; una bisync; otra bisync, cuyo log dice `No changes found`.
- [ ] **Step 3: Pasarlos en cuatro emuladores**, con API 30, 33, 35 y 37 (`sdkmanager "system-images;android-<n>;google_apis;x86_64"` y `avdmanager create avd` si faltan). Run: `ANDROID_SERIAL=<cada uno> ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.FechasCompartidasTest`. Expected: PASS en los cuatro; `adb logcat -d -s FechasCompartidas` da la precisión de cada uno.
- [ ] **Step 4: Lo que ve sin permiso, a mano**, en uno de ellos: `adb shell 'mkdir -p /sdcard/Documents/prdrive-ajena && echo x > /sdcard/Documents/prdrive-ajena/ajena.txt'` (lo crea otro dueño: el shell); `adb shell run-as prdrive.app ls -la /storage/emulated/0/Documents/prdrive-ajena` con el permiso dado, después de `adb shell appops set prdrive.app MANAGE_EXTERNAL_STORAGE deny`, y otra vez con `allow`. Apuntar si `ajena.txt` se ve sin permiso.
- [ ] **Step 5: Decidir.** Si algún test falla en algún API: apuntar en `PLAN.md` qué falló y dónde, commit, y **parar el plan** con ese informe. Si pasan todos: en `PLAN.md`, la precisión por API y lo del step 4; en «Ojo con Android» de `AGENTS.md`, que las fechas se conservan en `/storage/emulated/0/` con el permiso (con la precisión) y lo que se ve sin él.
- [ ] **Step 6: Commit.** `git add app/src/main/AndroidManifest.xml app/src/androidTest/kotlin/prdrive/app/FechasCompartidasTest.kt PLAN.md AGENTS.md && git commit -m "El paso 0: las fechas en el almacenamiento compartido"`

### Task 2: `Compartidas.kt` — el fichero y lo que puede vivir fuera

**Files:**
- Create: `engine/src/main/kotlin/prdrive/engine/Compartidas.kt`
- Modify: `engine/src/main/kotlin/prdrive/engine/Volumen.kt` (`compartidas`)
- Create: `engine/src/test/kotlin/prdrive/engine/CompartidasTest.kt`

**Interfaces:**
- Produces, en `object Compartidas`:
  - `const val FICHERO = "compartidas.json"`
  - `enum class Estado(val texto: String) { ENTRANDO("entrando"), LISTA("lista") }`
  - `data class Entrada(val ruta: String, val estado: Estado)`
  - `fun leer(fichero: File): Map<String, Entrada>`: sin fichero, vacío. Uno que no se lee, que no es JSON, que no es un objeto o con una entrada que no es `{"ruta": texto, "estado": "entrando"|"lista"}`: `ConfigError` que nombra el fichero y dice que sin él las parejas compartidas apuntarían a su carpeta privada. Con `leerJson` directamente: `Store.readJson` se traga los errores.
  - `fun escribir(fichero: File, entradas: Map<String, Entrada>)`: `Store.writeJson`, con `ruta` y `estado` en ese orden; `false` → `IOException`.
  - `fun problemaPareja(config: Config, nombre: String): String?`: existe; es bisync; no es la del llavero; `tramosLocales.size == 1`; ninguna otra pareja de `config.pairs` tiene su `topLevelDir`; ninguna pareja `esRaiz`.
  - `fun problemaRuta(ruta: String, raiz: File, otras: Map<String, Entrada>): String?`: absoluta (`Paths.get(ruta).isAbsolute` o que empiece por `/`); normal (`Paths.get(ruta).normalize().toString()` es `ruta`, comparadas las dos con `\` como `/`: eso descarta `.`, `..`, tramos vacíos y la `/` final); no es `raiz` (absoluta y canónica), ni cae dentro, ni la contiene; ni es, ni contiene, ni cae dentro de la `ruta` de otra entrada. Por tramos (`Path.startsWith`), no por texto. Así vale igual en Linux (el CI) y en Windows, donde también corre `:engine:test` y las carpetas temporales son `C:\…`.
  - `fun problema(config: Config, nombre: String, ruta: String, raiz: File, otras: Map<String, Entrada>): String? = problemaPareja(config, nombre) ?: problemaRuta(ruta, raiz, otras)`
- Produces: `Volumen.compartidas: File` = `File(programa, Compartidas.FICHERO)`.

- [ ] **Step 1: Los tests que fallan.** Un `config(vararg parejas)` local con `parseConfig(mapOf("defaults" to mapOf("device_remote" to "disp", "remote" to "nas"), "pair" to parejas.toList()))`.
  - `` `lo escrito se lee igual` ``: dos entradas, una en cada estado.
  - `` `sin fichero no hay ninguna` ``.
  - `` `un fichero roto no se lee como si no hubiera ninguna` `` (Review Focus 3): `"no es json"`, `"[]"`, `{"vault": {"ruta": 3, "estado": "lista"}}`, `{"vault": {"ruta": "/x", "estado": "otra"}}`, `{"vault": "/x"}` → `assertFailsWith<ConfigError>` en cada uno.
  - `` `una pareja bisync de un solo tramo y sola en su carpeta se puede mover` ``: `problemaPareja` de `vault` (`local = "vault"`) es `null`.
  - `` `cada regla de la pareja tiene su motivo` ``: no `null` con `mode = "up"`, `local = "notas/vault"`, otra pareja con `local = "."`, otra con `local = "vault/sub"`, un nombre que no existe, y la del llavero (config con `"keychain" to mapOf("base" to "x.kdbx")`, nombre `LLAVERO`).
  - `` `cada regla de la ruta tiene su motivo` ``: con `raiz` en un temporal, no `null` para `Documents/vault`, `/a/./b`, `/a/../b`, `/a//b`, `/a/b/`, la raíz, `raiz/vault`, el padre de la raíz, y la misma, una de dentro y una que contiene a la de otra entrada; `null` para `/storage/emulated/0/Documents/vault`.
- [ ] **Step 2: Comprobar que fallan.** Run: `./gradlew :engine:test --tests 'prdrive.engine.CompartidasTest'`. Expected: no compila (`Compartidas` no existe).
- [ ] **Step 3: Escribir `Compartidas.kt`** con lo de Interfaces, y `Volumen.compartidas`. Los motivos en español, cada uno diciendo qué hacer.
- [ ] **Step 4: Comprobar que pasan.** Mismo comando. Expected: PASS.
- [ ] **Step 5: Commit.** `git add engine/src/main/kotlin/prdrive/engine/Compartidas.kt engine/src/main/kotlin/prdrive/engine/Volumen.kt engine/src/test/kotlin/prdrive/engine/CompartidasTest.kt && git commit -m "Compartidas.kt: qué parejas pueden vivir en una carpeta compartida"`

### Task 3: La redirección en el `combine`

**Files:**
- Modify: `engine/src/main/kotlin/prdrive/engine/Model.kt` (`Pareja`, `Config`, y el KDoc de `Pareja` que dice que no hay `local_abs`)
- Modify: `engine/src/main/kotlin/prdrive/engine/Volumen.kt` (`leerConfig`, `escribirRemoto`, `actualizarCombine`, y el KDoc de la clase: no toda pareja cuelga de la raíz)
- Modify: `engine/src/test/kotlin/prdrive/engine/CompartidasTest.kt`

**Interfaces:**
- Consumes: Task 2.
- Produces:
  - `Pareja.carpetaExterna: String? = null` y `Pareja.problemaCompartida: String? = null`. `carpetaEn(volumen)` devuelve `File(carpetaExterna)` si no es `null`.
  - `Config.conCompartidas(entradas: Map<String, Compartidas.Entrada>, raiz: File): Config`: primero deja las dos a `null` en todas (idempotente); a cada pareja con entrada, `carpetaExterna = ruta` si `Compartidas.problema(this, nombre, ruta, raiz, entradas - nombre)` es `null`, o `problemaCompartida` = ese motivo; una entrada sin pareja se ignora.
  - `upstreamsDeCombine(raizDelVolumen)`: misma firma; la ruta de una pareja es `carpetaExterna ?: unir(raiz, tramos.take(1))`.
  - `Volumen.leerConfig()`: el config con `conCompartidas(Compartidas.leer(compartidas), raiz)`.
  - `Volumen.actualizarCombine(config: Config, entradas: Map<String, Compartidas.Entrada> = Compartidas.leer(compartidas))`: escribe `[disp]` de `config.conCompartidas(entradas, raiz)`.
  - `Volumen.escribirRemoto(carga, config)`: igual, con las entradas del fichero, cuando hay `config`.

- [ ] **Step 1: Los tests que fallan**, con un config de dos parejas (`notas`, `local = "notas"`, y `vault`, `local = "vault"`) y `ruta = "/storage/emulated/0/Documents/vault"`:
  - `` `con una entrada el upstream de la pareja es su carpeta y los demas no cambian` ``: con `sin`/`con` = `upstreamsDeCombine(raiz)` sin y con `conCompartidas`, los tramos de `con` (partidos por la comilla, el espacio y la comilla que los separan: una ruta puede llevar espacios) son los de `sin` cambiando el que empieza por `"vault=` por `upstream("vault", ruta)`, y `con != sin`.
  - `` `una entrada que no vale no redirige nada y deja su motivo` `` (`local = "a/vault"`): mismo `upstreamsDeCombine` que sin entradas; `carpetaExterna` `null`, `problemaCompartida` no `null`.
  - `` `una entrada de una pareja que no esta se ignora` ``.
  - `` `aplicar las compartidas dos veces da lo mismo` ``: `c.conCompartidas(e, r).conCompartidas(e, r) == c.conCompartidas(e, r)`.
  - `` `la pareja compartida conserva su nombre de sesion y sus filtros` ``: `Bisync.expectedPrefix(p, tipos)` y `Bisync.filtersContent(p)` iguales con y sin entrada (`tipos = Bisync.tiposDeRemote("[nas]\ntype = sftp\n")`).
  - `` `la carpeta de una pareja compartida es la suya` ``: `carpetaEn(raiz) == File(ruta)`.
  - `` `leerConfig trae las compartidas puestas` ``: con `Volumen` en un temporal, `escribirConfig` y `Compartidas.escribir(v.compartidas, …)`.
  - `` `reescribir el combine con un config sin compartidas conserva la redireccion` `` (Review Focus 4): `rcloneConf` = `"[nas]\ntype = sftp\n"`; `v.actualizarCombine(parseConfig(loadRaw(v.config.readText())))`; el `rclone.conf` contiene `upstream("vault", ruta)`.
- [ ] **Step 2: Comprobar que fallan.** Run: `./gradlew :engine:test --tests 'prdrive.engine.CompartidasTest'`. Expected: no compila.
- [ ] **Step 3: Implementar** lo de Interfaces en `Model.kt` y `Volumen.kt`, con los KDoc al día.
- [ ] **Step 4: Comprobar que pasan, y todo el motor.** Run: `./gradlew :engine:test`. Expected: PASS (los vectores del `combine` siguen igual: sin entradas no cambia nada).
- [ ] **Step 5: Commit.** `git commit -m "La carpeta compartida de una pareja, en el upstream del combine"` (con los ficheros de la tarea).

### Task 4: La pasada se para ante lo que no puede ver

**Files:**
- Modify: `engine/src/main/kotlin/prdrive/engine/Pasada.kt`, `engine/src/main/kotlin/prdrive/engine/Compartidas.kt`
- Create: `engine/src/test/kotlin/prdrive/engine/Ayudas.kt` (el `RcloneFalso` y `conBaseline` de `PasadaTest`, movidos tal cual y `internal`, para la Task 5)
- Modify: `engine/src/test/kotlin/prdrive/engine/PasadaTest.kt`

**Interfaces:**
- Consumes: Task 3.
- Produces:
  - `Pasada.Contexto(…, val accesoCompartido: ((Pareja) -> String?)? = null)`.
  - `const val Pasada.SIN_COMPROBAR_ACCESO` (la pareja vive en una carpeta compartida y nadie ha comprobado que se vea entera: sincronizar podría borrar en el remoto lo que no se ve) y `const val Pasada.COMBINE_VIEJO` (el `rclone.conf` no lleva su carpeta compartida: un movimiento cortado; se arregla con «Terminar de mover» o «Volver al volumen»).
  - `Compartidas.alDia(rcloneConf: String, pareja: Pareja): Boolean`: el `upstreams` de la sección `pareja.deviceRemote` (`Pairing.parseRcloneConf`) contiene `upstream(pareja.topLevelDir, pareja.carpetaExterna!!)`.
  - En `Pasada.pasar`, tras `problemaContencion()` y antes de `preflight()`, en este orden: `problemaCompartida`; y con `carpetaExterna`: sin `accesoCompartido` → `SIN_COMPROBAR_ACCESO`; lo que diga → eso; `!alDia(volumen.leerRcloneConf(), pareja)` → `COMBINE_VIEJO`. Cada uno: `cuenta("[${pareja.name}] ERROR: $motivo Se aborta.")` y `acabar(ABORTO, explicacion = motivo)`.

- [ ] **Step 1: Mover las ayudas.** `RcloneFalso` y `conBaseline` a `Ayudas.kt`. Run: `./gradlew :engine:test --tests 'prdrive.engine.PasadaTest'`. Expected: PASS, sin cambios.
- [ ] **Step 2: Los tests que fallan**, con un `volumenCompartido()` que escribe el config (`vault`, `local = "vault"`), la entrada (`ruta` en otro temporal, carpeta creada) y el `rclone.conf` con `"[nas]\ntype = sftp\n\n" + config.seccionCombine(raiz)` del config leído:
  - `` `una pareja con una entrada que no vale aborta sin llamar a rclone` ``: `ABORTO`, `explicacion == p.problemaCompartida`, ninguna llamada.
  - `` `una pareja compartida sin comprobar el acceso aborta` ``: `Contexto()` → `ABORTO`, `explicacion == SIN_COMPROBAR_ACCESO`.
  - `` `una pareja compartida aborta con lo que diga el acceso` ``: `accesoCompartido = { "sin permiso" }` → esa explicación.
  - `` `una pareja compartida con el rclone_conf viejo aborta` `` (Review Focus 2): el `rclone.conf` reescrito con el `seccionCombine` del config sin entradas → `COMBINE_VIEJO`.
  - `` `una pareja compartida con acceso y el combine al dia pasa por su carpeta` ``: con baseline (`conBaseline`) y `accesoCompartido = { null }` → código 0, una llamada.
  - `` `a una pareja sin carpeta compartida no se le pregunta el acceso` ``: la pareja `notas` de `PasadaTest` con `accesoCompartido = { error("no toca") }` pasa como siempre.
- [ ] **Step 3: Comprobar que fallan.** Run: `./gradlew :engine:test --tests 'prdrive.engine.PasadaTest'`. Expected: no compila (`accesoCompartido` no existe).
- [ ] **Step 4: Implementar** lo de Interfaces.
- [ ] **Step 5: Comprobar que pasan.** Run: `./gradlew :engine:test`. Expected: PASS.
- [ ] **Step 6: Commit.** `git commit -m "La pasada de una pareja compartida se para sin acceso o con el combine viejo"`.

### Task 5: `Mudanza.kt` — mover y volver, en el motor

**Files:**
- Create: `engine/src/main/kotlin/prdrive/engine/Mudanza.kt`, `engine/src/test/kotlin/prdrive/engine/MudanzaTest.kt`
- Modify: `engine/src/main/kotlin/prdrive/engine/Orden.kt`, `engine/src/main/kotlin/prdrive/engine/Pasada.kt` (`Contexto.resyncMode`), `engine/src/main/kotlin/prdrive/engine/Bisync.kt` (`apartarListados`)
- Modify: `app/src/main/kotlin/prdrive/app/rclone/Adaptadores.kt` (la rama sin `basePath` de `apartarListados` llama a `Bisync.apartarListados`)
- Modify: `engine/src/test/kotlin/prdrive/engine/OrdenTest.kt`

**Interfaces:**
- Consumes: Tasks 2–4; `Ayudas.kt`.
- Produces:
  - `Orden.tramos(…, resyncMode: String? = null)` y `Orden.flagsDeLaPasada(…, resyncMode: String? = null)`: con `resync` y un modo, se quitan todos los `resync-mode` de la pareja (flags y `extra`) y se pone `resync-mode = resyncMode` (`ORIGEN_PRDRIVE`). Sin `resync`, no hace nada.
  - `Pasada.Contexto(…, val resyncMode: String? = null)`, que `pasar` le da a `Orden.tramos`.
  - `Bisync.apartarListados(workdir: File): List<File>`: cada `*.path1.lst`/`*.path2.lst` a `<nombre>-err` (borrando el `-err` que hubiera), como `markFailed`; devuelve los nuevos.
  - En `object Mudanza`:
    - `const val RESYNC_MODE = "path2"`
    - `data class Entorno(val rclone: Pasada.Rclone, val opciones: Pasada.Opciones, val ctx: Pasada.Contexto, val probarFechas: (File) -> String?, val recargar: () -> Unit, val alProgreso: (Map<String, Any?>) -> Unit = {}, val decir: (String) -> Unit = {})`
    - `data class Hecho(val motivo: String?, val pasadas: List<Pasada.Resultado>) { val ok: Boolean get() = motivo == null }`
    - `fun carpetaPrivada(volumen: Volumen, pareja: Pareja): File` = `File(volumen.raiz, pareja.tramosLocales.single())`
    - `fun entrar(volumen: Volumen, nombre: String, carpeta: File, e: Entorno): Hecho`
    - `fun terminar(volumen: Volumen, nombre: String, e: Entorno): Hecho`
    - `fun salir(volumen: Volumen, nombre: String, conPasada: Boolean, e: Entorno): Hecho`
  - Las pasadas: normales con `e.ctx.copy(resyncAprobado = false, forzarResync = false)`; los `--resync` con `e.ctx.copy(resyncAprobado = true, resyncMode = RESYNC_MODE)`; siempre `Pasada.pasarOFallar`.

La secuencia, que las firmas no deciden (la de la spec, sección 3):

`entrar`: (1) sin entrada para `nombre` y `Compartidas.problema()` `null`, si no, parar; (2) la carpeta vacía o sin crear; si no existe se crea, y se recuerda; (3) `probarFechas(carpeta)`; (4) una pasada normal que acabe con código 0; (5) `Bisync.apartarListados(volumen.workdir(pareja))`; (6) la entrada `ENTRANDO`; (7) `volumen.actualizarCombine(volumen.leerConfig())` y `recargar()`; (8) `terminar()`. Si se para en 1–4, la carpeta creada en 2 se borra (está vacía) y no se ha escrito nada.

`terminar`: (1) hay entrada y es `ENTRANDO`; (2) `actualizarCombine()` y `recargar()` otra vez (arregla un corte entre 6 y 7); (3) el `--resync`; si falla, parar y dejarlo todo; (4) borrar `carpetaPrivada()` con `Files.walkFileTree` sin `FOLLOW_LINKS` (un enlace se borra, no lo que señala); (5) la entrada a `LISTA`.

`salir`: (1) hay entrada; (2) si `conPasada`, una pasada normal con código 0; (3) `carpetaPrivada()` vacía o sin crear, salvo con la entrada en `ENTRANDO`; (4) apartar los listados; (5) `actualizarCombine(config, entradas - nombre)` y `recargar()`; (6) escribir las entradas sin `nombre`; (7) el `--resync`. La carpeta compartida no se toca nunca.

- [ ] **Step 1: El test del `resync-mode` que falla**, en `OrdenTest`: `` `el resync-mode de la pasada manda en un resync y no sale en una pasada normal` ``: una pareja con `flags` `resync-mode = newer` y `extra_flags = ["--resync-mode=older"]`; con `resync = true, resyncMode = Mudanza.RESYNC_MODE`, un solo `--resync-mode` en los tramos y con ese valor; con `resync = false`, ninguno.
- [ ] **Step 2: Los tests de la mudanza que fallan**, en `MudanzaTest`, con `RcloneFalso`, un volumen como el de la Task 4 pero sin entrada (`vault` con baseline y un fichero en su carpeta privada), un destino vacío en un temporal, `probarFechas = { null }` y un `recargar` que cuenta:
  - `` `mover hace la pasada, aparta los listados, redirige y solo tras el resync borra la copia privada` ``: dos llamadas, la primera sin `resync` y la segunda con `resync = true` y `resyncMode == Mudanza.RESYNC_MODE`; el `rclone.conf` lleva `upstream("vault", destino)`; la entrada en `LISTA`; `carpetaPrivada()` no existe; el destino sí.
  - `` `una carpeta con algo no se usa y no se toca nada` ``: motivo, ninguna llamada, sin `compartidas.json`, los listados como estaban.
  - `` `si las fechas no valen se para y quita la carpeta que creo` ``: destino sin crear y `probarFechas = { "no" }` → motivo, el destino no existe, ninguna llamada.
  - `` `si la pasada de antes falla no se redirige nada` ``: `RcloneFalso(Final(false, …))` → motivo, sin `compartidas.json`, listados intactos.
  - `` `si el resync falla queda a medio mover con la copia privada y los listados apartados` ``: la segunda llamada falla → motivo; entrada `ENTRANDO`; `carpetaPrivada()` sigue; en el workdir solo hay `-err`.
  - `` `un corte entre la entrada y el combine deja una pasada que aborta, y terminar lo arregla` `` (Review Focus 2): entrada `ENTRANDO` y listados apartados a mano, `rclone.conf` sin redirección → `Pasada.pasar` da `ABORTO` con `COMBINE_VIEJO`; `terminar()` → ok, `LISTA`.
  - `` `si la carpeta ya no esta vacia al hacer el resync gana el remoto` `` (Review Focus 1): un `.obsidian/app.json` en el destino tras la pasada de antes (desde el `recargar` del entorno) → el `--resync` lleva `resyncMode == Mudanza.RESYNC_MODE` y la mudanza sigue.
  - `` `volver sin permiso no pasa antes y no toca la carpeta compartida` ``: desde `LISTA`, `conPasada = false` → una sola llamada (`resync`); el `rclone.conf` sin la redirección; sin entrada; el destino con sus ficheros.
  - `` `volver escribe el combine antes de quitar la entrada` ``: con un `recargar` que mira `compartidas.json`, en ese momento la entrada sigue.
  - `` `volver con algo en la carpeta privada se para` ``: desde `LISTA`, un fichero en `carpetaPrivada()` → motivo, ninguna llamada, la entrada sigue.
- [ ] **Step 3: Comprobar que fallan.** Run: `./gradlew :engine:test --tests 'prdrive.engine.MudanzaTest' --tests 'prdrive.engine.OrdenTest'`. Expected: no compila.
- [ ] **Step 4: Implementar** lo de Interfaces y la secuencia. `Adaptadores.apartarListados` usa `Bisync.apartarListados(File(workdir))` en su rama sin `basePath`.
- [ ] **Step 5: Comprobar que pasan.** Run: `./gradlew :engine:test`. Expected: PASS.
- [ ] **Step 6: Commit.** `git commit -m "Mudanza.kt: mover una pareja a una carpeta compartida y volver, sin que un corte borre nada"`.

### Task 6: La app — el permiso, la carpeta y la prueba de fechas

**Files:**
- Modify: `app/src/main/kotlin/prdrive/app/Prdrive.kt`
- Create: `app/src/main/kotlin/prdrive/app/compartidas/Compartir.kt`
- Modify: `app/src/main/kotlin/prdrive/app/ui/Modelo.kt`, `app/src/main/kotlin/prdrive/app/Instalador.kt`, `app/src/main/kotlin/prdrive/app/llavero/LlaveroApp.kt` (los cuatro `Pasada.Contexto(…)`)
- Modify: `engine/src/main/kotlin/prdrive/engine/Compartidas.kt` (`problemaFechas`), `engine/src/test/kotlin/prdrive/engine/CompartidasTest.kt`
- Create: `app/src/androidTest/kotlin/prdrive/app/CompartirTest.kt`

**Interfaces:**
- Consumes: Task 5.
- Produces:
  - `Compartidas.problemaFechas(original: Instant, copiada: Instant, releida: Instant, precision: Duration): String?`: `null` si `|copiada − original| ≤ precision`, `releida == copiada` y `precision` menor de un día (el «no hay fechas» de rclone, `fs.ModTimeNotSupported`, es de 100 años).
  - `Prdrive.permisoCompartido(): Boolean` = `Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()`.
  - `Prdrive.accesoCompartido(tienePermiso: () -> Boolean = ::permisoCompartido): (Pareja) -> String?`: sin permiso, `SIN_PERMISO_COMPARTIDO`; con `Environment.getExternalStorageState(File(carpetaExterna)) != MEDIA_MOUNTED`, `SIN_MONTAR`.
  - `const val Prdrive.SIN_PERMISO_COMPARTIDO = "Falta el permiso de acceso a todos los archivos: sin él, Android esconde las notas que no ha creado prdrive, y sincronizar las borraría del remoto."` y `const val Prdrive.SIN_MONTAR` (el almacenamiento compartido no está disponible).
  - En `object Compartir`:
    - `const val AUTORIDAD = "com.android.externalstorage.documents"`
    - `const val AVISO_EXPOSICION = "Cualquier app con acceso a todos los archivos podrá leer y cambiar esta carpeta, y se verá al conectar el teléfono a un ordenador. La clave y el resto del volumen siguen privados."`
    - `fun rutaDeArbol(arbol: Uri): File?`: solo `AUTORIDAD` y un id de árbol (`DocumentsContract.getTreeDocumentId`) `primary:<ruta>` → `File(Environment.getExternalStorageDirectory(), ruta)`; lo demás, `null`.
    - `fun probarFechas(carpeta: File, temporal: File): String?`: el fichero de prueba en `temporal`, `operations/copyfile` a la carpeta, `operations/stat` de los dos, `operations/fsinfo` (`Precision`), 2 s, otro `stat`, `operations/deletefile`; decide `Compartidas.problemaFechas`. Un `Rclone.RcloneError` es un motivo, con su mensaje.
    - `fun pedirPermiso(contexto: Context): Intent` = `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` con `package:prdrive.app`.
    - `fun entorno(contexto: Context, keepLogs: Boolean, alProgreso: (Map<String, Any?>) -> Unit, decir: (String) -> Unit): Mudanza.Entorno`: `Adaptadores.pasada`, `Adaptadores.opciones`, `Pasada.Contexto(keepLogs = keepLogs, accesoCompartido = Prdrive.accesoCompartido())`, `probarFechas(it, Prdrive.temporal(contexto))`, `Prdrive.recargarRclone(contexto)`.
  - Los cuatro `Pasada.Contexto(…)` de la app llevan `accesoCompartido = Prdrive.accesoCompartido()`.

- [ ] **Step 1: Los tests del motor que fallan**, en `CompartidasTest`: `` `las fechas valen con la misma hora dentro de la precision y que no cambia` `` (`null`), `` `una hora que cambia al releerla no vale` `` y `` `una carpeta sin fechas no vale` `` (precisión de 100 años) (no `null`).
- [ ] **Step 2: Comprobar que fallan e implementar `problemaFechas`.** Run: `./gradlew :engine:test --tests 'prdrive.engine.CompartidasTest'`. Expected: no compila; tras implementarla, PASS.
- [ ] **Step 3: Los tests de la app que fallan**, en `CompartirTest` (permiso dado con `appops … allow` en `@Before`, como la Task 1):
  - `un_arbol_del_almacenamiento_principal_se_traduce_a_su_ruta`: `DocumentsContract.buildTreeDocumentUri(Compartir.AUTORIDAD, "primary:Documents/vault")` → `File(Environment.getExternalStorageDirectory(), "Documents/vault")`.
  - `un_arbol_de_otro_sitio_no_vale` (Review Focus 5): `"1234-5678:vault"` con `AUTORIDAD`; un árbol de `com.android.providers.downloads.documents`; un `buildDocumentUri` (no de árbol) → `null`.
  - `con_permiso_las_fechas_de_una_carpeta_compartida_valen`: `probarFechas` en `Documents/prdrive-fechas-<n>` → `null`; no queda el fichero de prueba.
  - `sin_permiso_el_acceso_lo_dice`: `Prdrive.accesoCompartido(tienePermiso = { false })(pareja)` es `SIN_PERMISO_COMPARTIDO`; con `{ true }` y una pareja en `Documents/`, `null`.
- [ ] **Step 4: Comprobar que fallan.** Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.CompartirTest`. Expected: no compila.
- [ ] **Step 5: Implementar** `Prdrive`, `Compartir` y los cuatro `Contexto`.
- [ ] **Step 6: Comprobar que pasan, y la batería entera.** Run: la clase sola y después `./gradlew :app:connectedDebugAndroidTest`. Expected: PASS (las pasadas de siempre no notan el acceso: no tienen carpeta compartida).
- [ ] **Step 7: Commit.** `git commit -m "El permiso, la carpeta elegida y la prueba de fechas de una carpeta compartida"`.

### Task 7: La fila y los botones

**Files:**
- Modify: `app/src/main/kotlin/prdrive/app/Estado.kt`, `app/src/main/kotlin/prdrive/app/ui/Modelo.kt`, `app/src/main/kotlin/prdrive/app/ui/Pantallas.kt`
- Create: `app/src/androidTest/kotlin/prdrive/app/PantallaCompartidaTest.kt`

**Interfaces:**
- Consumes: `Mudanza`, `Compartir`, `Prdrive.accesoCompartido()`, `Prdrive.permisoCompartido()`, `Compartidas.problemaPareja`.
- Produces:
  - `Estado.Fila(…, val compartida: String? = null, val entrando: Boolean = false, val sinAcceso: String? = null, val problemaCompartida: String? = null, val sePuedeCompartir: Boolean = false)`.
  - `Estado.filas(v: Volumen, config: Config, acceso: (Pareja) -> String? = { null }, conCompartir: Boolean = false)`: `compartida` = la `ruta` de su entrada; `entrando` si está `ENTRANDO`; `sinAcceso = acceso(p)` solo con `carpetaExterna`; `problemaCompartida = p.problemaCompartida`; `sePuedeCompartir = conCompartir && compartida == null && Compartidas.problemaPareja(config, p.name) == null`.
  - `Modelo.moverACompartida(nombre: String, arbol: Uri)`, `Modelo.terminarDeMover(nombre: String)`, `Modelo.volverAlVolumen(nombre: String)`: con `trabajo(…)` y `Compartir.entorno(…)`; un `Hecho` con motivo va a `error`; `filas` se relee en `finally`. `moverACompartida` y `terminarDeMover`, sin `Prdrive.permisoCompartido()`, son un `error` con `SIN_PERMISO_COMPARTIDO` antes de llamar a `Mudanza`; un árbol que `rutaDeArbol` no traduce, otro que lo dice. `volverAlVolumen` pasa `conPasada = Prdrive.permisoCompartido()`. Las llamadas a `Estado.filas` pasan `acceso = Prdrive.accesoCompartido()` y `conCompartir = Build.VERSION.SDK_INT >= 30`. `alVolver()` relee también las filas (sin red): el permiso puede venir de los ajustes.
  - Etiquetas de test: `donde-<n>`, `mover-<n>`, `terminar-<n>`, `volver-<n>`, `permiso-<n>`.
- La fila: «En <ruta relativa a `getExternalStorageDirectory()`>» (`donde-<n>`). `sePuedeCompartir` → «Mover a una carpeta compartida…», que abre un diálogo con `AVISO_EXPOSICION` y, sin permiso, «Dar permiso» (`Compartir.pedirPermiso`), o con él, «Elegir la carpeta» (`ActivityResultContracts.OpenDocumentTree`, empezando en `DocumentsContract.buildDocumentUri(AUTORIDAD, "primary:Documents")`). Con `compartida` y no `entrando` → «Volver al volumen», con la frase «La carpeta compartida se queda como está: bórrala tú cuando ya no la quieras.». Con `entrando` → «Terminar de mover», en lugar del botón de `--resync`. Con `sinAcceso` → el texto en `colorScheme.error` y «Dar permiso» (`permiso-<n>`). Con `problemaCompartida` → el texto.

- [ ] **Step 1: Los tests que fallan**, en `PantallaCompartidaTest`, como `PrincipalTest` (`createEmptyComposeRule()`, `ActivityScenario.launch(MainActivity::class.java)`, esperar a que no haya `ocupado`), con `Banco.volumen(…)` y `compartidas.json` escrito a mano (más `actualizarCombine` y `fscache/clear`):
  - `una_pareja_compartida_dice_donde_vive_y_ofrece_volver`: `donde-vault` y `volver-vault` existen; `mover-vault`, no.
  - `una_pareja_a_medio_mover_ofrece_terminar_y_no_el_resync`: `terminar-vault` existe; `resync-vault`, no.
  - `una_pareja_que_se_puede_mover_lo_ofrece_y_una_anidada_no`: `mover-vault` existe para `local = "vault"`; `mover-notas` no para `local = "sync-data/notas"`.
  - `sin_acceso_la_fila_lleva_el_motivo`: `Estado.filas(v, config, acceso = { "sin" }).single { it.nombre == "vault" }.sinAcceso == "sin"`.
- [ ] **Step 2: Comprobar que fallan.** Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.PantallaCompartidaTest`. Expected: no compila.
- [ ] **Step 3: Implementar** `Estado`, `Modelo` y la fila, sin colores escritos.
- [ ] **Step 4: Comprobar que pasan, y `PrincipalTest`.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "La fila de una pareja: moverla a una carpeta compartida, terminar y volver"`.

### Task 8: El proveedor no escribe en la copia privada de una pareja compartida

**Files:**
- Modify: `engine/src/main/kotlin/prdrive/engine/Compartidas.kt`, `engine/src/test/kotlin/prdrive/engine/CompartidasTest.kt`
- Modify: `app/src/main/kotlin/prdrive/app/documentos/VolumenProvider.kt`, `app/src/androidTest/kotlin/prdrive/app/VolumenProviderTest.kt`

**Interfaces:**
- Consumes: Task 2.
- Produces:
  - `Compartidas.tramosReservados(config: Config, entradas: Map<String, Entrada>): Set<String>`: el `topLevelDir` de cada pareja con entrada, valga o no y en cualquier estado; un nombre que no está se ignora.
  - En `VolumenProvider`, `private fun reservado(t: List<String>): Boolean` = `t.isNotEmpty() && t[0] in tramosReservados(…)`, leído del volumen en cada llamada (como `llaveroActivo()`); si el config o las entradas no se pueden leer, ninguno. Se niega con `FileNotFoundException` en: `openDocument` con `'w'`, `createDocument` (`tramosPadre + displayName`), `deleteDocument`, y `renameDocument` (el de origen y el de destino). Leer y listar, sí. El KDoc de la clase lo dice.

- [ ] **Step 1: Los tests que fallan.** En el motor, `` `los tramos reservados son los de las parejas con entrada` `` (una válida, una que no vale, un nombre que no está). En `VolumenProviderTest`, `una_pareja_compartida_no_deja_escribir_ni_crear_su_carpeta_privada` (escribir un fichero de dentro, crear dentro, crear `vault` en la raíz, renombrar otra carpeta a `vault`, borrar: todos fallan) y `una_pareja_compartida_deja_leer_su_carpeta_privada`.
- [ ] **Step 2: Comprobar que fallan.** Run: `./gradlew :engine:test --tests 'prdrive.engine.CompartidasTest'` y la clase `prdrive.app.VolumenProviderTest`. Expected: no compilan.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Comprobar que pasan, con `ProveedorLlaveroTest`.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "El proveedor no escribe en la carpeta privada de una pareja compartida"`.

### Task 9: De punta a punta, y la documentación

**Files:**
- Create: `app/src/androidTest/kotlin/prdrive/app/MudanzaEnAndroidTest.kt`
- Modify: `app/src/androidTest/kotlin/prdrive/app/PuntaAPuntaTest.kt`, `herramientas/punta_a_punta.py` (una pareja `vault` con `local = "vault"` y un fichero, junto a `notas` y `fotos`)
- Modify: `AGENTS.md`, `PLAN.md`, `README.md`

**Interfaces:**
- Consumes: todo lo anterior.
- Produces: nada que use otra tarea.

- [ ] **Step 1: Los tests contra rclone de verdad**, en `MudanzaEnAndroidTest` (permiso dado en `@Before`; destino en `Documents/prdrive-mudanza-<n>`, borrado en `@After`; `Banco.volumen(Banco.pareja("vault", "vault"))` inicializada con su `--resync`; `Compartir.entorno(…)`):
  - `una_pareja_se_muda_a_una_carpeta_compartida_y_vuelve`: `entrar` → ok; lo del remoto está en el destino; la copia privada no está; los listados del workdir se llaman como antes (mismo prefijo). Un cambio en el destino y otro en el remoto y una pasada normal → código 0 y los dos lados al día. `salir(conPasada = true)` → ok; los ficheros en la carpeta privada; el destino sigue con los suyos.
  - `una_mudanza_cortada_tras_la_entrada_se_termina`: la entrada `ENTRANDO` y los listados apartados a mano, el `rclone.conf` sin tocar → la pasada aborta con `COMBINE_VIEJO`; `terminar` → ok.
  - `sin_permiso_una_pareja_compartida_aborta_sin_llamar_a_rclone`: tras mover, una pasada con `accesoCompartido = Prdrive.accesoCompartido(tienePermiso = { false })` → `ABORTO`, y el remoto como estaba.
- [ ] **Step 2: Pasarlos.** Run: la clase sola. Expected: PASS.
- [ ] **Step 3: La de punta a punta.** En `punta_a_punta.py`, la pareja `vault`. En `PuntaAPuntaTest`, `la_pareja_se_muda_a_una_carpeta_compartida_y_sincroniza_con_el_servidor`: emparejar por la pantalla (como la primera prueba, marcando `vault`), `Mudanza.entrar` con `Compartir.entorno`, un fichero nuevo en el destino, «Sincronizar», y el fichero está en el servidor. Run: `python herramientas/punta_a_punta.py ../prdrive /ruta/a/rclone /tmp/e2e` y la orden que imprime. Expected: las tres pruebas en verde (las dos de antes no notan la pareja nueva).
- [ ] **Step 4: Con Obsidian, a mano**, en un emulador o un teléfono: mover `vault`, abrirla en Obsidian («Abrir carpeta como vault»), editar una nota y sincronizar; editar en el remoto, sincronizar y verla en Obsidian; editar la misma nota en los dos lados y ver el conflicto en la fila. Apuntar la versión de Obsidian y lo que salga en `PLAN.md`.
- [ ] **Step 5: La documentación.**
  - `AGENTS.md`: una sección «Las carpetas compartidas» con las decisiones de la spec (la redirección en el `combine` y por qué no una clave del TOML ni un enlace; el fichero del teléfono; el acceso que falla cerrado; el `rclone.conf` al día; el orden de escritura en los dos sentidos; `resync-mode = path2`; solo carpetas vacías; la copia privada solo se borra tras el `--resync`; la carpeta compartida nunca se borra; el proveedor; Android 11 y `primary`; la exposición); en «Arquitectura», «El volumen» ya no dice que todo vive en `filesDir`; en la distribución, `Compartidas.kt`, `Mudanza.kt`, `app/compartidas/` y los tests nuevos de `androidTest`.
  - `PLAN.md`: la entrada, hecha, con lo que falta (adoptar una carpeta con ficheros, SD/USB, Android 8–10, comparar por suma, elegir la carpeta al emparejar), y una línea en «Una SD o un USB como volumen» que remite a ella.
  - `README.md`: una sección corta para quien use Obsidian, con lo que la spec manda documentar: mover la pareja y el permiso; excluir `.obsidian/workspace*.json` en el catálogo (vale también para el PC); que en un vault pequeño borrar dos notas puede pasar del 25 % y se usa «Forzar»; las copias de conflicto, que Obsidian no indexa sin `versions`; y que al desinstalar el vault se queda, y al volver a emparejar se mueve a una carpeta nueva.
- [ ] **Step 6: Todo en verde.** Run: `./gradlew :engine:test` y `./gradlew :app:connectedDebugAndroidTest`. Expected: PASS.
- [ ] **Step 7: Commit.** `git commit -m "Las carpetas compartidas, de punta a punta y documentadas"`.
