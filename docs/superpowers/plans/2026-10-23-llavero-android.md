# El llavero en el teléfono — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el teléfono sea un dispositivo más del llavero de prdrive: sincroniza `[keychain]` como el escritorio, una app de KeePass lo abre y lo guarda a través de `VolumenProvider`, y lo guardado sube solo con WorkManager.

**Architecture:** El motor (`engine/`) gana el espejo de la parte pura de `common/llavero.py`, de `common/kdbx.py` y de lo que `sync.run_pair()` hace con el llavero; `Pasada` deja de saltarlo. La app activa el llavero desde el catálogo, guarda las bases de forma atómica en el proveedor, encola las pasadas con WorkManager y lo enseña en la principal.

**Tech Stack:** Kotlin 2.4 (motor sin dependencias, bytecode 17), Android con Compose, `androidx.work` (nuevo), rclone como biblioteca (`.aar`), Python para los vectores.

**Spec:** `docs/superpowers/specs/2026-10-22-llavero-android-design.md`. Para el escritorio: `../prdrive/docs/agents/reference/llavero.md`, `../prdrive/common/llavero.py`, `../prdrive/common/kdbx.py`, `../prdrive/common/frenos.py` y `../prdrive/sync.py` (`_pasar_pareja`, `_bisync_preflight`, `crear_carpeta_remota`, `lo_que_borraria_forzando`, `repetir_sin_freno`, `reapuntar_llavero`).

## Global Constraints

- `engine/` no importa nada de Android ni tiene dependencias, tampoco de test (solo `kotlin-test`).
- Cada fichero nuevo de `engine/` nombra en su KDoc el fichero de Python que refleja y conserva sus citas al código de rclone.
- Ningún test lleva un valor esperado escrito a mano: sale de `vectores.json`, generado desde prdrive (`python herramientas/vectores.py ../prdrive`).
- Regex en `engine/` con clases a mano (`[0-9]`, `[\t\n\f\r ]`), nunca `\d`, `\s` ni `\w`. Nada que acabe en un fichero pasa por `String.format`.
- Comentarios, KDoc y textos para el usuario en español. Tests: frase entre acentos graves en `engine/`, con guiones bajos en `androidTest`.
- Los avisos del compilador son errores (`allWarningsAsErrors`).
- `LEEME_HORA = 946684800`; `ESPERA_ENTERA` = 2 s; `TOLERANCIA_HORA` = `VENTANA_HORA` (2 s) en ns; `FRENO_SIN_COPIAS = 100`; `LLAVERO_ROTO = 2`; `LLAVERO_SIN_CIFRAR = 3`; `DESDE_LA_ULTIMA` = 2 min; `calma` = 20 s; `separacion` = 2 min.
- Android cuenta como cifrado: la app pasa siempre `cifrado = true`; el motor, por defecto, `false` (falla cerrado, como prdrive).
- Dependencia nueva de `app/`: solo `androidx.work:work-runtime-ktx` (y `androidx.work:work-testing` en `androidTestImplementation`), la última estable que publique `maven.google.com`.
- Las pasadas, de una en una en todo el proceso; la del llavero, además, con su propio cerrojo (Task 7). Nada se instala con una pasada en marcha (`Actualizador.empezarTrabajo()`/`acabarTrabajo()`).
- Órdenes: `./gradlew :engine:test` (sin SDK); `./gradlew :app:connectedDebugAndroidTest` con `ANDROID_SERIAL=emulator-5554` si hay un teléfono enchufado. En Windows, si Gradle no arranca: `export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'`.

## Review Focus

1. **Se quita el llavero (o el volumen aún no está sembrado) con un trabajo encolado**: el worker no hace nada y acaba con éxito, sin reintentar. Test en Task 7.
2. **«Abrir llavero» y el worker a la vez**: la segunda pasada espera a la primera; nunca dos bisync del llavero solapadas (el lock de bisync se quedaría puesto). Test en Task 7.
3. **Qué reintenta el worker**: un fallo de red sí; una base rota (`LLAVERO_ROTO`) y un freno que no se pudo repetir, no. Test en Task 7.
4. **`llave_interna` sin `.keychain/llave.keyx`** (borrada a mano): la fila lo dice en ámbar y «Abrir llavero» avisa antes de abrir; la pasada sigue, porque no la usa. Test en Task 9.
5. **Escribir una `.kdbx` que aún no existe en `.keychain/`** (crear con `createDocument` y luego `"w"`): pasa por el guardado atómico igual, y una escritura cortada no deja un fichero roto que viaje. Test en Task 8.

---

### Task 1: `Kdbx.kt`, la base entera sin clave

**Files:**
- Create: `herramientas/vectores_llavero.py`
- Modify: `herramientas/vectores.py` (importarlo y añadir la sección `"llavero"` en `main()`, junto a `"paso3"`)
- Create: `engine/src/main/kotlin/prdrive/engine/Kdbx.kt`
- Create: `engine/src/test/kotlin/prdrive/engine/LlaveroTest.kt`
- Modify: `engine/src/test/resources/vectores.json` (regenerado)

**Interfaces:**
- Produces: `object Kdbx { data class Estado(val entera: Boolean?, val version: String = "", val motivo: String = ""); fun mirar(datos: ByteArray): Estado; fun comprobar(fichero: File): Estado }`, el mismo `Estado` y los mismos `motivo` que `kdbx.Estado`. `comprobar` de un fichero que no se lee: lo que hace `kdbx.comprobar()`.
- Produces: `vectores_llavero.vectores(llavero, kdbx, frenos, sync, model, bisync, conflicts) -> dict` (los argumentos se van añadiendo en las tareas siguientes) y la sección `"llavero"` de `vectores.json`, con `"kdbx": [{"caso", "hex", "entera", "version", "motivo"}]`.

- [ ] **Step 1: Generar los casos de `kdbx`.** En `vectores_llavero.py`, construir las bases como `../prdrive/tests/test_kdbx.py` (KDBX 4 con su cadena HMAC y KDBX 3 con AES), y por cada caso guardar los bytes en hex y lo que dice `kdbx.mirar()`: la base entera; cada corte (`base[:n]` para todo `n`); un byte de más; la cabecera tocada; KDBX 4 sin bloques de datos; KDBX 3 de largo múltiplo de 16 y no múltiplo; una versión que no se conoce; un zip (`PK\x03\x04…`); vacío.
- [ ] **Step 2: Regenerar.** Run: `python herramientas/vectores.py ../prdrive`. Expected: `vectores.json` con `llavero.kdbx`, y el resto sin cambios (`git diff --stat` solo toca ese apartado).
- [ ] **Step 3: El test que falla.** En `LlaveroTest.kt`:

```kotlin
@Test
fun `una base se da por entera o rota como la mira prdrive`() {
    for (caso in Vectores.objetos(Vectores.seccion("llavero"), "kdbx")) {
        val e = Kdbx.mirar(hex(Vectores.texto(caso, "hex")))
        assertEquals(caso["entera"], e.entera, "«${caso["caso"]}»")
        assertEquals(caso["version"], e.version, "«${caso["caso"]}»")
        assertEquals(caso["motivo"], e.motivo, "«${caso["caso"]}»")
    }
}
```

- [ ] **Step 4: Comprobar que falla.** Run: `./gradlew :engine:test --tests 'prdrive.engine.LlaveroTest'`. Expected: no compila (`Kdbx` no existe).
- [ ] **Step 5: Escribir `Kdbx.kt`**, espejo de `common/kdbx.py` (firmas, TLV hasta `EndOfHeader`, SHA-256 de la cabecera y bloques HMAC en KDBX 4; múltiplo de 16 en KDBX 3 con AES/Twofish), con `java.security.MessageDigest`.
- [ ] **Step 6: Comprobar que pasa.** Mismo comando. Expected: PASS.
- [ ] **Step 7: Commit.** `git add herramientas/vectores_llavero.py herramientas/vectores.py engine/src/main/kotlin/prdrive/engine/Kdbx.kt engine/src/test/kotlin/prdrive/engine/LlaveroTest.kt engine/src/test/resources/vectores.json && git commit -m "Kdbx.kt: si una base del llavero está entera, como kdbx.py"`

### Task 2: `Llavero.kt`, la carpeta, el `LEEME` y lo pendiente

**Files:**
- Create: `engine/src/main/kotlin/prdrive/engine/Llavero.kt`
- Modify: `herramientas/vectores_llavero.py`, `engine/src/test/kotlin/prdrive/engine/LlaveroTest.kt`, `engine/src/test/resources/vectores.json`

**Interfaces:**
- Consumes: `Kdbx.comprobar(File)`; `Bisync.expectedPrefix(pareja, tipos)`, `Bisync.PATH1_SUFFIX`, `Volumen.workdir(pareja)`, `Pareja.carpetaEn(raiz)`, `VENTANA_HORA`, `VERSIONS_DIR`, `LLAVERO_LOCAL`.
- Produces, en `object Llavero`:
  - `const val LEEME = "LEEME.txt"`, `val LEEME_TEXTO: String` (de los vectores, byte a byte, con `\n`), `const val LEEME_HORA = 946684800L`, `const val ESPERA_ENTERA_MS = 2000L`, `const val TOLERANCIA_HORA: Long` (ns).
  - `data class Rota(val fichero: File, val motivo: String)`
  - `fun bases(donde: File): List<File>`: las `*.kdbx` de la carpeta y subcarpetas, sin `.prversions/` ni `*.old.kdbx`, en orden.
  - `fun rotas(donde: File, dormir: (Long) -> Unit = Thread::sleep): List<Rota>`
  - `fun asegurarLeeme(donde: File, corregirHora: Boolean = true): Boolean`: crea con `CREATE_NEW`; pone la hora con `Files.setLastModifiedTime` solo si el texto es el nuestro y difiere en `TOLERANCIA_HORA` o más.
  - `fun preparar(carpeta: File, simulacro: Boolean = false, dormir: (Long) -> Unit = Thread::sleep): String?`: el motivo de `preparar()` con su texto exacto, o `null`.
  - `fun prepararCarpeta(raiz: File): File`
  - `fun listadoLocal(listado: File): Map<String, kotlin.Pair<Long, Long>>?`: `{ruta: (tamaño, mtime_ns)}`, como `listado_local()` (regex `_LINEA` y `_HORA` con clases a mano; el nombre se lee con `leerJson`).
  - `fun listadoDe(volumen: Volumen, pareja: Pareja, tipos: Map<String, Bisync.TipoRemote>): File`: `workdir/<expectedPrefix>.path1.lst`.
  - `fun pendiente(carpeta: File, listado: File): Boolean` y `fun sinListar(carpeta: File, listado: File): List<String>`; la hora del fichero en ns con `Files.getLastModifiedTime(...).to(TimeUnit.NANOSECONDS)`.

- [ ] **Step 1: Generar los casos.** En `vectores_llavero.py`, sección `"leeme"`: `LEEME_TEXTO`, `LEEME_HORA`, `TOLERANCIA_HORA`, y `preparar` contra carpetas montadas en un temporal (`model.DEVICE_ROOT` reenganchado, `llavero.dormir` sin espera): carpeta vacía (crea `LEEME`, hora fija), `LEEME` con el texto y otra hora (se corrige; con `simulacro`, no), `LEEME` con otro texto (ni texto ni hora), una base rota (el motivo exacto), una entera, una `x.old.kdbx` rota (no cuenta), una rota dentro de `.prversions/` (no cuenta). Sección `"listado"`: líneas de `lineFormat` de rclone (las del docstring de `_LINEA`, con zona `+0000`, `-0700`, sin fracción, nombre con comillas escapadas, línea que no casa) → lo que da `listado_local()`; y `pendiente`/`sin_listar` con bases y listados montados (igual, tamaño distinto, hora a 1,9 s, hora a 2 s, sin listado, base que no está en el listado). Regenerar.
- [ ] **Step 2: Los tests que fallan.** `` `el LEEME es el mismo byte a byte y con la misma hora en todos los dispositivos` ``, `` `preparar dice lo mismo que prdrive en cada carpeta` ``, `` `el listado de bisync se lee como lo lee prdrive` ``, `` `lo pendiente y lo sin listar salen como en prdrive` ``: cada uno monta el caso del vector en `temporal()` y compara con sus valores (bytes, `mtime` en ns, motivo o `null`, mapa, booleano, lista).
- [ ] **Step 3: Comprobar que fallan.** Run: `./gradlew :engine:test --tests 'prdrive.engine.LlaveroTest'`. Expected: no compila.
- [ ] **Step 4: Escribir `Llavero.kt`** con lo de Interfaces. KDoc: espejo de la parte pura de `common/llavero.py`; lo que no entra (procesos, vigilante, KeePassXC, lanzadores, «Combinar», expulsar) y por qué.
- [ ] **Step 5: Comprobar que pasan.** Mismo comando. Expected: PASS.
- [ ] **Step 6: Commit.** `git commit -m "Llavero.kt: el LEEME, las bases rotas y lo pendiente, como llavero.py"` (con los ficheros de la tarea).

### Task 3: Los frenos del llavero y el alta «Traer el del remoto»

**Files:**
- Modify: `engine/src/main/kotlin/prdrive/engine/Llavero.kt`, `engine/src/main/kotlin/prdrive/engine/Pasada.kt` (la parte de `frenos`)
- Modify: `herramientas/vectores_llavero.py`, `engine/src/test/kotlin/prdrive/engine/LlaveroTest.kt`, `engine/src/test/resources/vectores.json`

**Interfaces:**
- Consumes: `Conflictos.esquema(pareja)`, `Conflictos.leerNombre(nombre, esq)`.
- Produces:
  - En `Llavero`: `const val FRENO_SIN_COPIAS = 100L`; `fun remotoVacio(log: String): Boolean`; `fun soloCopiasBorradas(log: String, pareja: Pareja): List<String>?`; `fun decidirAlta(remota: Map<String, Any?>?): Map<String, Any?>` (solo la rama `origen = None` de `decidir_alta()`: devuelve una copia del `[keychain]` remoto; sin él, `ConfigError` con el texto de prdrive «El remoto no tiene llavero que traer…»; el chequeo de cifrado no se hace aquí, Android cuenta como cifrado).
  - En `Pasada`: `fun borrarian(log: String): List<String>` (`frenos.borrarian()`, ordenado y sin repetir); `const val EXPLICACION_LLAVERO`, `const val EXPLICACION_LLAVERO_CAMBIADOS`; `fun explicacionLlavero(explicacion: String): String`; `val EXPLICACIONES: List<String>` (las de `frenos.ENTRADAS`).

- [ ] **Step 1: Generar los casos.** Sección `"frenos"`: logs de rclone (los de los docstrings de `_BORRADO`, `_REMOTO_VACIO` y `_SIMULADO`, más uno con una base borrada, uno con `LEEME.txt` borrado, uno con un nombre entre comillas, uno con una ruta con `/`, uno sin borrados) → `remoto_vacio()`, `solo_copias_borradas()` con la pareja del llavero, `frenos.borrarian()`; las tres explicaciones → `explicacion_llavero()`; y `decidir_alta(donde, remota, None, cifrado=Cifrado(True, …))` con remota `None` (el error) y con dos tablas (con y sin `llave_interna`). Regenerar.
- [ ] **Step 2: Los tests que fallan.** `` `los frenos del llavero se leen del log como en prdrive` ``, `` `al llavero se le explica el freno sin decirle que lo fuerce` ``, `` `traer el llavero del remoto lleva su tabla tal cual` ``.
- [ ] **Step 3: Comprobar que fallan.** Run: `./gradlew :engine:test --tests 'prdrive.engine.LlaveroTest'`. Expected: no compila.
- [ ] **Step 4: Implementar** lo de Interfaces, con las regex de prdrive escritas con clases a mano.
- [ ] **Step 5: Comprobar que pasan.** Expected: PASS, y `./gradlew :engine:test` entero en verde.
- [ ] **Step 6: Commit.** `git commit -m "Los frenos del llavero y su alta desde el remoto"`.

### Task 4: La pasada del llavero en el motor

**Files:**
- Create: `engine/src/main/kotlin/prdrive/engine/PasadaLlavero.kt` (espejo de la parte del llavero de `sync.py`)
- Modify: `engine/src/main/kotlin/prdrive/engine/Pasada.kt` (quitar el salto, `Rclone.mkdir`, `Contexto.cifrado`, los ganchos)
- Modify: `engine/src/test/kotlin/prdrive/engine/PasadaTest.kt`, `herramientas/vectores_llavero.py`, `engine/src/test/resources/vectores.json`
- Modify: `app/src/main/kotlin/prdrive/app/rclone/Adaptadores.kt` (solo lo justo para que compile: `mkdir`)

**Interfaces:**
- Consumes: todo lo de Tasks 1–3.
- Produces:
  - `Pasada.Rclone` gana `fun mkdir(destino: String): Final` (un `operations/mkdir` con `fs = destino`, `remote = ""`).
  - `Pasada.Contexto` gana `val cifrado: Boolean = false`.
  - `const val LLAVERO_ROTO = 2`, `const val LLAVERO_SIN_CIFRAR = 3` en `Pasada`.
  - `internal object PasadaLlavero` con `crearCarpetaRemota(...)`, `repetirSinFreno(...)` y `reapuntar(...)`; firma interna libre, pero la llamada de cada repetición sale de `Orden.tramos` + `llamadaDe` con la pareja cambiada (`pareja.copy(flags = pareja.flags + cambio)`), como `replace(pair, flags=…)`.
  - Comportamiento de `Pasada.pasar` con `pareja.llavero`: el de `_pasar_pareja()` (orden: contención → preflight sin pedir aprobación con razones → carpeta local → `llave_interna` sin `cifrado` = 3 → `Llavero.preparar` = 2 → `mkdir` si hay resync → la llamada → `repetirSinFreno` si falla y no era resync → log y explicación (`explicacionLlavero` si la explicación es de `EXPLICACIONES`) → apuntar → conflictos → `reapuntar` si fue bien y no es simulacro). Las líneas que se dicen son las de `sync.py`.

- [ ] **Step 1: Generar los casos de las repeticiones.** Sección `"repeticiones"`: por cada log de Task 3 (copias borradas, base borrada, «all files were changed» con simulación que no borra / que borra `X3.kdbx` / que falla, `Empty current Path2 listing`, `Empty current Path1 listing`, un fallo de red), ejecutar `sync.repetir_sin_freno()` con `sync.execute`, `sync.crear_carpeta_remota`, `sync.lo_que_borraria_forzando` y `sync.dispose_log` sustituidos por grabadoras, y guardar `{"caso", "log", "simulacion", "repite", "cambio", "resync", "mkdir", "simulada", "lineas"}`; uno más de cada en `dry_run`. Regenerar.
- [ ] **Step 2: Los tests que fallan**, en `PasadaTest.kt`, con un `RcloneFalso` que conteste de una cola de `Final` y apunte también los `mkdir`, y un volumen con `[keychain]` (`base = "personal.kdbx"`) y una base entera de los vectores de Task 1 en `.keychain/`:
  - `` `el llavero sin baseline se resincroniza solo y antes crea su carpeta en el remoto` ``: código 0; `mkdir` con `nas:/<carpeta del catálogo>/keychain`; la llamada lleva `resync = true` y `resyncMode = "newer"`; `.keychain/LEEME.txt` existe.
  - `` `una base rota para la pasada sin llamar a rclone y se apunta` ``: código `LLAVERO_ROTO`, sin llamadas, apuntado en `state/`.
  - `` `sin contraseña y sin cifrar no se sincroniza` ``: `llave_interna = true`, `fichero_llave = true`, `Contexto(cifrado = false)` → 3; con `cifrado = true`, pasa.
  - `` `cada freno del llavero se repite una vez como en prdrive` ``: por cada vector de `"repeticiones"`, el número de llamadas, el `maxDelete`/`force`/`resync`/`dryRun` de cada una y el `mkdir` son los del vector.
  - `` `una base que rclone deja sin listar se apunta con otra pasada` ``: tras la buena, un `.path1.lst` sin la base → otra llamada sin `resync`.
  - `` `al llavero se le explica el freno sin decirle que lo fuerce` ``: la explicación del resultado es `EXPLICACION_LLAVERO_CAMBIADOS` cuando la repetida vuelve a pararse.
  - Y que el test de antes (el llavero se salta) desaparece.
- [ ] **Step 3: Comprobar que fallan.** Run: `./gradlew :engine:test --tests 'prdrive.engine.PasadaTest'`. Expected: FAIL o no compila.
- [ ] **Step 4: Implementar** lo de Interfaces. `Adaptadores.pasada.mkdir` con `Rclone.rpc("operations/mkdir", mapOf("fs" to destino, "remote" to ""))` dentro de `synchronized(unaPasada)`, convirtiendo `RcloneError` en `Final(false, …)`. Quitar del KDoc de `Pasada.kt` «el llavero (la app no lo sincroniza todavía)».
- [ ] **Step 5: Comprobar que pasan.** Run: `./gradlew :engine:test`. Expected: PASS entero.
- [ ] **Step 6: Commit.** `git commit -m "La pasada del llavero: se resincroniza sola, repite sus frenos y se reapunta"`.

### Task 5: La pasada del llavero contra el rclone de verdad

**Files:**
- Modify: `app/src/androidTest/kotlin/prdrive/app/PasadaEnAndroidTest.kt`, `app/src/androidTest/kotlin/prdrive/app/MotorEnAndroidTest.kt`

**Interfaces:**
- Consumes: `Pasada.pasar` de Task 4, `Adaptadores.pasada`, el remote `alias` que ya usan estas pruebas.

- [ ] **Step 1: Los tests**, en `PasadaEnAndroidTest`:
  - `la_primera_pasada_del_llavero_trae_la_base_y_deja_el_leeme_con_su_hora`: remoto `alias` con `keychain/personal.kdbx` (una base entera de los vectores) y sin baseline → código 0, la base en `.keychain/`, `LEEME.txt` con `mtime` = `LEEME_HORA` en ms.
  - `un_remoto_del_llavero_vaciado_a_mano_se_vuelve_a_subir`: tras una pasada buena, borrar la carpeta `keychain/` del remoto → la siguiente pasada acaba en 0 y la base vuelve a estar en el remoto.
  
  Y en `MotorEnAndroidTest`, los vectores de `llavero` (Tasks 1–3) otra vez en el dispositivo: `los_vectores_del_llavero_valen_tambien_en_android`.
- [ ] **Step 2: Ejecutarlos.** Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.PasadaEnAndroidTest,prdrive.app.MotorEnAndroidTest`. Expected: PASS. Si algo falla por rclone y no por el motor, se apunta con la línea de su código fuente, como pide AGENTS.md.
- [ ] **Step 3: Commit.** `git commit -m "El llavero contra el rclone de verdad, y sus vectores en Android"`.

### Task 6: Activar y quitar el llavero

**Files:**
- Modify: `engine/src/main/kotlin/prdrive/engine/Volumen.kt` (lo puro de activar y quitar)
- Modify: `app/src/main/kotlin/prdrive/app/Instalador.kt`
- Test: `engine/src/test/kotlin/prdrive/engine/VolumenTest.kt`; Create: `app/src/androidTest/kotlin/prdrive/app/LlaveroAltaTest.kt`

**Interfaces:**
- Consumes: `Llavero.decidirAlta`, `Llavero.prepararCarpeta`, `Volumen.actualizarCombine`, `headerOf`, `loadRaw`, `dumpsChecked`.
- Produces:
  - `Volumen.conLlavero(rawActual: Map<String, Any?>, remota: Map<String, Any?>?): Map<String, Any?>`: el raw con `keychain` = `decidirAlta(remota)`, comprobado con `parseConfig`.
  - `Volumen.sinLlavero(rawActual: Map<String, Any?>): Map<String, Any?>`
  - `Volumen.avisosAlta(config: Config): List<String>` y `Volumen.avisosBaja(config: Config): List<String>`: los textos de `plan_activar()`/`plan_desactivar()` de prdrive que aplican aquí (la pareja de la raíz que pedirá `--resync`; con `llave_interna`, que la llave se queda), desde los vectores.
  - `Instalador.activarLlavero(contexto: Context, remota: Map<String, Any?>, llave: Uri?): Config`: escribe el config con su cabecera (`headerOf` del texto actual), `prepararCarpeta`, copia la llave a `.keychain/llave.keyx` si `llave_interna` (sin ella, `ConfigError("Este llavero va sin contraseña: hace falta su fichero llave.")`), `actualizarCombine`, `Prdrive.recargarRclone`. No hace la pasada: la lanza quien llama (Task 9).
  - `Instalador.quitarLlavero(contexto: Context): Config`: config sin `[keychain]`, `actualizarCombine`, `Disparos.cancelar` (Task 7). `.keychain/` se queda.

- [ ] **Step 1: Generar los avisos.** En `vectores_llavero.py`, sección `"alta"`: con un catálogo con una pareja de la raíz bisync y otro sin ella, lo que dicen `plan_activar()` y `plan_desactivar()` en sus `consecuencias`/avisos para «Traer el del remoto», quedándose con las frases que aplican al teléfono (la de la raíz y la de la llave). Regenerar.
- [ ] **Step 2: Los tests del motor que fallan.** `` `activar el llavero añade la tabla del remoto y el config se sigue leyendo` `` (y `Config.parejaLlavero` existe); `` `sin llavero en el remoto no hay nada que traer` ``; `` `quitar el llavero deja el resto del config igual` ``; `` `los avisos de activar y quitar son los de prdrive` ``.
- [ ] **Step 3: Comprobar que fallan, implementar y comprobar que pasan.** Run: `./gradlew :engine:test --tests 'prdrive.engine.VolumenTest'`.
- [ ] **Step 4: El test de Android.** `LlaveroAltaTest`: `activar_con_llave_interna_copia_la_llave_y_sin_ella_no_activa` (una llave de un `content://` de prueba → `.keychain/llave.keyx` con sus bytes; sin `Uri`, `ConfigError` y el config sin tocar); `activar_y_quitar_rehacen_el_combine` (el `upstreams` de `[disp]` lleva `.keychain` y luego no). Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.LlaveroAltaTest`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "Activar el llavero desde el remoto y quitarlo del teléfono"`.

### Task 7: Las pasadas del llavero con WorkManager

**Files:**
- Modify: `app/build.gradle.kts` (`work-runtime-ktx`, `work-testing`)
- Create: `app/src/main/kotlin/prdrive/app/llavero/LlaveroApp.kt` (la pasada del llavero en la app, con su cerrojo)
- Create: `app/src/main/kotlin/prdrive/app/llavero/TrabajoLlavero.kt` (el `CoroutineWorker`)
- Create: `app/src/main/kotlin/prdrive/app/llavero/Disparos.kt` (encolar)
- Modify: `app/src/main/kotlin/prdrive/app/MainActivity.kt` (al arrancar, `Disparos.siPendiente`)
- Test: `app/src/androidTest/kotlin/prdrive/app/TrabajoLlaveroTest.kt`

**Interfaces:**
- Consumes: `Pasada.pasarOFallar`, `Adaptadores`, `Prdrive.iniciarRclone`, `Actualizador.empezarTrabajo()`/`acabarTrabajo(contexto)`, `Llavero.pendiente`, `Llavero.listadoDe`, `Resultados.ultimasBuenas`.
- Produces:
  - `object LlaveroApp { fun pasar(contexto: Context, alProgreso: (Map<String, Any?>) -> Unit = {}, decir: (String) -> Unit = {}): Pasada.Resultado?; fun pendiente(contexto: Context): Boolean; fun reciente(contexto: Context): Boolean }`. `pasar` va con un `ReentrantLock` propio (una pasada del llavero a la vez, la espera bloquea), `Contexto(cifrado = true, keepLogs = config.keepLogs)`, y devuelve `null` si no hay volumen sembrado o el config no tiene llavero. Al acabar, llama a `Avisos.trasPasada` (Task 9; hasta entonces, nada) y, si `pendiente`, a `Disparos.siPendiente`. `reciente` = la última buena de `keychain` es de hace menos de 2 min.
  - `class TrabajoLlavero(contexto: Context, params: WorkerParameters) : CoroutineWorker`. `doWork`: si `!Actualizador.empezarTrabajo()` → `retry`; `LlaveroApp.pasar` en `Dispatchers.IO`; `null` → `success`; código 0, `LLAVERO_ROTO` o un log de freno (`Pasada.esBorradosBisync`/`esCambiadosBisync`) → `success`; cualquier otro fallo → `retry`; en `finally`, `Actualizador.acabarTrabajo`.
  - `object Disparos { const val NOMBRE = "llavero"; fun alGuardar(c: Context); fun alLeer(c: Context); fun siPendiente(c: Context); fun cancelar(c: Context) }`: `OneTimeWorkRequest` con `NetworkType.CONNECTED` y `setBackoffCriteria(EXPONENTIAL, 2, MINUTES)`; `alGuardar` = `REPLACE` con retraso de 20 s; `alLeer` = `KEEP` sin retraso, y nada si `LlaveroApp.reciente`; `siPendiente` = `KEEP` sin retraso si `LlaveroApp.pendiente`.

- [ ] **Step 1: Los tests que fallan** (`work-testing`, con `WorkManagerTestInitHelper` y `TestListenableWorkerBuilder`; el rclone de verdad con un remoto `alias`, como en Task 5):
  - `sin_llavero_o_sin_volumen_el_trabajo_acaba_bien_sin_hacer_nada` (Review Focus 1).
  - `una_base_rota_no_se_reintenta_y_un_fallo_de_red_si` (Review Focus 3): base rota → `success` y `state/` apunta 2; remoto `alias` a una carpeta inexistente de un backend que falle como sin red → `retry`.
  - `dos_pasadas_del_llavero_a_la_vez_van_una_detras_de_otra` (Review Focus 2): dos hilos llaman a `LlaveroApp.pasar`; con un `decir` que apunte la hora de «=== keychain» y del «OK», no se solapan.
  - `cada_guardado_aplaza_la_subida_y_leer_no_encola_si_es_reciente`: dos `alGuardar` → un solo trabajo `ENQUEUED` con su retraso; `alLeer` tras una pasada buena → ningún trabajo.
- [ ] **Step 2: Comprobar que fallan.** Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.TrabajoLlaveroTest`. Expected: no compila.
- [ ] **Step 3: Implementar** lo de Interfaces y llamar a `Disparos.siPendiente(this)` en el `onCreate` de `MainActivity`.
- [ ] **Step 4: Comprobar que pasan.** Mismo comando. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "Las pasadas del llavero en segundo plano, con WorkManager"`.

### Task 8: El guardado atómico en el proveedor

**Files:**
- Create: `app/src/main/kotlin/prdrive/app/documentos/GuardadoAtomico.kt`
- Modify: `app/src/main/kotlin/prdrive/app/documentos/VolumenProvider.kt` (`openDocument`, `fila`, `deleteDocument`, `renameDocument`, `onCreate`)
- Test: `app/src/androidTest/kotlin/prdrive/app/ProveedorLlaveroTest.kt`

**Interfaces:**
- Consumes: `Kdbx.comprobar`, `Disparos.alGuardar`/`alLeer`, `LLAVERO_LOCAL`, `Volumen.programa`, `Volumen.state`.
- Produces:
  - `internal object GuardadoAtomico { fun esBase(tramos: List<String>): Boolean; fun esLlave(tramos: List<String>): Boolean; fun abrir(base: File, modo: String, temporales: File, handler: Handler, alPoner: () -> Unit, alCortar: (String) -> Unit): ParcelFileDescriptor; fun limpiar(temporales: File, ahora: Long = System.currentTimeMillis()) }`.
  - `esBase`: `tramos.size == 2 && tramos[0] == LLAVERO_LOCAL && tramos[1]` acaba en `.kdbx` y no en `.old.kdbx`. `esLlave`: `[LLAVERO_LOCAL, "llave.keyx"]`.
  - `abrir`: temporal en `temporales` (`.prdrive/tmp/`), vacío con `"w"`/`"wt"`, copia de la base con `"rw"`/`"rwt"`/`"wa"`; `ParcelFileDescriptor.open(tmp, parseMode(modo), handler, listener)`. En el listener: con `IOException` → borrar; sin él → `FileChannel.force(true)`, `Kdbx.comprobar`: `entera != false` → `Files.move(tmp, base, ATOMIC_MOVE, REPLACE_EXISTING)` y `alPoner()`; `false` → borrar y `alCortar(motivo)`.
  - El aviso de guardado cortado: `state/llavero-cortado.json` = `{"cuando": Store.stamp(), "fichero": <nombre>, "motivo": <motivo>}`; un guardado bueno lo borra. Lo lee Task 9.
  - En el proveedor: `"r"` de una base → abrir tal cual y `Disparos.alLeer`; modo con `w` → `GuardadoAtomico.abrir` con `alPoner = { Disparos.alGuardar(context) }`; `llave.keyx`: flags sin `WRITE`/`DELETE`/`RENAME`, y `openDocument` en escritura, `deleteDocument` y `renameDocument` lanzan `FileNotFoundException`. `onCreate`: un `HandlerThread("guardado-atomico")` y `GuardadoAtomico.limpiar` de lo que tenga más de un día.

- [ ] **Step 1: Los tests que fallan**, por `ContentResolver` contra `DocumentsContract.buildDocumentUri(AUTORIDAD, "volumen/.keychain/personal.kdbx")`, con un volumen sembrado con llavero (Task 6):
  - `escribir_una_base_entera_la_cambia_y_encola_la_subida` (con `"wt"` y con `"rw"`): bytes nuevos en la base y un trabajo `llavero` encolado.
  - `una_escritura_cortada_o_con_error_deja_la_base_como_estaba`: medio fichero con `"wt"` → base igual, `state/llavero-cortado.json` con el motivo; `closeWithError` → base igual y sin aviso.
  - `una_base_nueva_en_el_llavero_tambien_se_guarda_entera_o_no_se_pone` (Review Focus 5): `createDocument` de `nueva.kdbx` en `.keychain/` y una escritura cortada → `nueva.kdbx` no queda con bytes rotos.
  - `la_llave_se_ve_pero_no_se_escribe_ni_se_borra`: `llave.keyx` en el listado, sin `FLAG_SUPPORTS_WRITE`; abrirla en `"w"`, borrarla y renombrarla fallan.
  - `fuera_del_llavero_todo_sigue_como_antes`: un `.kdbx` en `sync-data/` se escribe directo, sin temporal.
- [ ] **Step 2: Comprobar que fallan.** Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.ProveedorLlaveroTest`. Expected: FAIL.
- [ ] **Step 3: Implementar** lo de Interfaces. KDoc de `GuardadoAtomico`: por qué (`UseAtomicSaves`, H-11 en prdrive; el `"w"` de Android que no trunca).
- [ ] **Step 4: Comprobar que pasan**, y que `VolumenProviderTest` sigue en verde. Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.ProveedorLlaveroTest,prdrive.app.VolumenProviderTest`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "El proveedor guarda las bases del llavero de forma atómica"`.

### Task 9: El llavero en la pantalla, «Abrir llavero» y los avisos

**Files:**
- Modify: `app/src/main/kotlin/prdrive/app/Estado.kt` (la fila del llavero)
- Create: `app/src/main/kotlin/prdrive/app/llavero/Avisos.kt` (notificaciones)
- Modify: `app/src/main/kotlin/prdrive/app/ui/Modelo.kt`, `app/src/main/kotlin/prdrive/app/ui/Pantallas.kt`, `app/src/main/AndroidManifest.xml` (`POST_NOTIFICATIONS`)
- Modify: `herramientas/vectores_llavero.py`, `engine/src/test/resources/vectores.json`
- Test: `app/src/androidTest/kotlin/prdrive/app/PantallaLlaveroTest.kt`

**Interfaces:**
- Consumes: `LlaveroApp.pasar`/`reciente`, `Instalador.activarLlavero`/`quitarLlavero`, `Volumen.avisosAlta`/`avisosBaja`, `Catalogo.cached(state)`, `Conflictos.cargar`, `Resultados`, `Llavero.pendiente`, `Pasada.explicacionLlavero`, `state/llavero-cortado.json`.
- Produces:
  - `Estado.FilaLlavero(val activo: Boolean, val traer: Boolean, val ultimaBuena: Long?, val fallo: Resultados.Fallo?, val explicacion: String?, val pendiente: Boolean, val conflictos: List<Conflictos.Conflicto>, val cortado: String?, val faltaLlave: Boolean)` y `fun Estado.llavero(v: Volumen, config: Config): FilaLlavero?` (`null` sin llavero y sin `[keychain]` en la copia del catálogo; `traer` = sin llavero activo pero con él en el catálogo; `faltaLlave` = `llaveInterna` sin `.keychain/llave.keyx`).
  - `object Avisos { fun trasPasada(contexto: Context, conflictos: List<Conflictos.Conflicto>); fun guardadoCortado(contexto: Context, fichero: String) }`: canal `llavero`; una notificación por copia nueva, una sola vez, con lo avisado en `noBackupFilesDir/llavero/avisados.json`, que se vacía cuando no queda ninguna. El texto: `LLAVERO_EN_CONFLICTO` de `agente.py` (de los vectores) seguido de una frase del teléfono, `COMBINAR_FUERA = "Combínalas en el ordenador con «Abrir llavero», o en tu app de KeePass."` (`COMBINAR_A_MANO` habla de KeePassXC y no vale aquí).
  - La explicación de un fallo de red en la fila lleva detrás `Prdrive.SIN_RED_LOCAL` si `Prdrive.faltaRedLocal`, como hace `Modelo.trabajo` con sus errores.
  - `Modelo`: `var llavero by mutableStateOf<Estado.FilaLlavero?>`; `fun abrirLlavero(abrir: (Uri) -> Unit)` (sección 3 de la spec: pasada si no es `reciente`, sin base → error, copias o `faltaLlave` → `avisoLlavero` que se cierra antes de abrir, y `abrir(uri)`); `fun traerLlavero(llave: Uri?)` (relee el catálogo, `activarLlavero`, la primera pasada con progreso); `fun quitarLlavero()`; `sincronizar()` pasa además el llavero, el último, con `LlaveroApp.pasar`.
  - `Pantallas`: la fila (ámbar con su explicación; si no, «con cambios que aún no han subido» o «al día» con `haceCuanto`), el botón «Abrir llavero» (`ACTION_VIEW` con `FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION`; sin app que lo abra, un aviso que nombra KeePassDX y Keepass2Android), «Traer el llavero» con su confirmación (`avisosAlta`) y el selector de la llave si `llave_interna`, «Quitar el llavero de este teléfono» en un menú con `avisosBaja`, y en `PasoParejas` la casilla «El llavero» (desmarcada) cuando el catálogo trae `[keychain]`, que tras instalar llama a `activarLlavero` y hace la primera pasada. `POST_NOTIFICATIONS` se pide al activar.

- [ ] **Step 0: El texto del aviso.** En `vectores_llavero.py`, `"avisos": {"LLAVERO_EN_CONFLICTO": agente.LLAVERO_EN_CONFLICTO}` (importar `agente` del checkout de prdrive como los demás módulos); regenerar, y que `Avisos` lo use desde una constante comprobada contra el vector en `MotorEnAndroidTest`.
- [ ] **Step 1: Los tests que fallan** (Compose, `junit4.v2`, con un volumen sembrado y el remoto `alias` de Task 5):
  - `la_fila_del_llavero_dice_al_dia_pendiente_o_por_que_esta_en_ambar` (al día; base tocada → pendiente; `llavero-cortado.json` → ámbar con el motivo; una copia `personal.conflicto-remoto1.kdbx` → ámbar).
  - `sin_la_llave_interna_la_fila_lo_dice_y_abrir_avisa_antes` (Review Focus 4).
  - `traer_el_llavero_lo_activa_y_lo_baja` (la casilla en `PasoParejas` y el botón de la principal).
  - `abrir_el_llavero_pide_la_base_por_el_proveedor` (con `Intents` de Espresso: un `ACTION_VIEW` con el `content://` de la base y los dos permisos).
  - `una_copia_de_conflicto_se_notifica_una_sola_vez` (dos `trasPasada` con la misma copia → una notificación; sin copias → se olvida).
- [ ] **Step 2: Comprobar que fallan.** Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.PantallaLlaveroTest`. Expected: FAIL.
- [ ] **Step 3: Implementar** lo de Interfaces, y conectar `Avisos.trasPasada` en `LlaveroApp.pasar` y `Avisos.guardadoCortado` en el `alCortar` del proveedor.
- [ ] **Step 4: Comprobar que pasan**, con las pruebas de las otras pantallas. Run: `./gradlew :app:connectedDebugAndroidTest`. Expected: PASS (salvo `PuntaAPuntaTest`, que se salta sin servidor).
- [ ] **Step 5: Commit.** `git commit -m "El llavero en la principal: abrir, traer, quitar y avisar"`.

### Task 10: De punta a punta y la documentación

**Files:**
- Modify: `herramientas/punta_a_punta.py` (catálogo con `[keychain]`, `base = "personal.kdbx"`, y la base en el remoto)
- Modify: `app/src/androidTest/kotlin/prdrive/app/PuntaAPuntaTest.kt`
- Modify: `AGENTS.md`, `PLAN.md`

- [ ] **Step 1: El test.** En `PuntaAPuntaTest`, `el_llavero_se_trae_al_emparejar_y_la_base_llega`: marcar «El llavero» en `PasoParejas`, instalar, y comprobar que `.keychain/personal.kdbx` tiene los bytes del remoto y que la fila dice «al día». La base del remoto la escribe `punta_a_punta.py` con los bytes de una base entera de los vectores.
- [ ] **Step 2: Ejecutarlo** con el servidor: `python herramientas/punta_a_punta.py ../prdrive <rclone> <carpeta>` y la orden que imprime. Expected: PASS.
- [ ] **Step 3: AGENTS.md.** En la distribución: `Kdbx.kt`, `Llavero.kt`, `PasadaLlavero.kt`, `app/llavero/` (`LlaveroApp`, `TrabajoLlavero`, `Disparos`, `Avisos`) y `documentos/GuardadoAtomico.kt`. Una sección «El llavero» con: Android cuenta como cifrado (y por qué); WorkManager y el tope de 5 h de los reintentos frente a los 30 min del escritorio; el guardado atómico solo en `.keychain/`; la llave visible y de solo lectura; nada de «Combinar» ni de crear un llavero; la lista de lo que queda por comprobar en un teléfono.
- [ ] **Step 4: PLAN.md.** Quitar «El llavero» de «Lo siguiente» y añadir a «Comprobarlo en un teléfono de verdad»: KeePassDX abriendo con el botón y guardando, verlo en el PC; un conflicto avisado y combinado en el PC; `llave_interna` con la llave copiada; el modo real de escritura de KeePassDX; las carpetas con punto en la app de Archivos; el tipo MIME que aceptan KeePassDX y Keepass2Android en `ACTION_VIEW`.
- [ ] **Step 5: Todo en verde.** Run: `./gradlew :engine:test` y `./gradlew :app:connectedDebugAndroidTest`. Expected: PASS.
- [ ] **Step 6: Commit.** `git commit -m "El llavero de punta a punta, y su documentación"`.
