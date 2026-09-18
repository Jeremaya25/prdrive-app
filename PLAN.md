# prdrive para Android — paso 1

Estado del plan: **refinado contra rclone v1.75.1 y contra prdrive 0.2.0**.
Lo que aquí se afirma sobre rclone está comprobado contra el código de la
versión que prdrive fija (`common/pins.py`: `RCLONE_VERSION = "v1.75.1"`), no
contra su documentación. Los sitios exactos se citan, como hace
`common/bisync.py` en el otro repo.

## De dónde viene esto

prdrive es hoy un programa que vive **en** un disco extraíble: un PC ejecuta el
Python del propio disco y un rclone empaquetado sincroniza pareja por pareja
contra un remoto de rclone. En Android no se salva nada de eso — no se ejecuta
un programa desde un volumen extraíble, no hay Tk, y no se llega a la raíz de
una SD o un USB sin un permiso especial.

Así que la versión de Android es una **app aparte y autónoma**: Kotlin, y su
«disco» es una carpeta que la app posee dentro de su almacenamiento privado (un
*volumen virtual*), que Android cifra por nosotros y que no necesita ningún
permiso. rclone viaja dentro como biblioteca.

**El paso 1 es pequeño a propósito:** crear el volumen, meter la conexión
leyendo el QR que enseña el PC, elegir parejas del catálogo y dar un botón
«Sincronizar» que funcione y que cuente qué está pasando. Sin servicio, sin
vigilante, sin editar el catálogo, sin contenedores cifrados.

Decisiones ya tomadas: reescritura entera en Kotlin · repositorio aparte ·
emparejamiento por QR · volumen visible en la app de Archivos del sistema.

## La mitad de Python ya está hecha

El plan original pedía trabajo en el repositorio de prdrive: un codificador de
QR y la ventana que lo enseña. **Está terminado y en `main`** (v0.2.0, rama
`qr-emparejamiento`, PR #22), y además salió mejor colocado de lo que el plan
suponía:

| Lo que hay | Dónde | Por qué importa aquí |
|---|---|---|
| `ui/qr.py` | codificador QR completo, ISO/IEC 18004, sin dependencias | ya no es trabajo pendiente |
| `common/pairing.py` | construye la carga útil **y la lee** (`leer()`) | `leer()` es la **definición ejecutable del formato**: es lo que este repo reproduce |
| `ui/tk_qr.py` | la ventana, colgada de Doctor, con el aviso en ámbar | la clave privada se enseña y se dice |
| `tests/test_qr.py` | la vuelta entera: dispositivo → carga → QR → decodificado → `profile.loads()` | impide que el formato del payload y el del perfil se separen |

Dos cosas que el plan original no había previsto y que hay que respetar:

- **`pairing.py` vive en `common/`, no en `install/`**, porque `install/` no
  viaja a un dispositivo provisionado y esto se ejecuta *en* uno. Por eso
  `parse_rclone_conf()` también se mudó allí y `install/profile.py` lo
  reexporta: **un solo lector de rclone.conf en todo el proyecto**.
- **La carga lleva tres claves de más** sobre `profile.dumps()` (la marca
  `prdrive`, `private_key_b64` y `known_hosts`), y le sobran a
  `profile.loads()`, así que el *mismo texto* se le pasa tal cual. **Todas las
  claves sueltas van antes de `[options]`**: en TOML, una línea traspapelada
  después de la cabecera de tabla metería la clave privada dentro de las
  opciones del backend, y de ahí al rclone.conf del móvil.
- El QR se pide con corrección **L**, no con la M por defecto del módulo: el
  medio es una pantalla (sin pliegues, sin tinta, sin roces) y lo que escasea es
  capacidad — una clave RSA-3072 no cabe en **ninguna** versión con M.

## Lo que NO se reescribe

rclone implementa bisync, así que la app no reimplementa sincronizar. A Kotlin
se pasa solo la parte que hace `sync.py`: **montar la orden correcta y contar
cómo ha ido**. Los ficheros de Python siguen siendo la especificación.

| Python (especificación) | Kotlin | Estado |
|---|---|---|
| `common/model.py` (`MODES`, `BASE_FLAGS`, `flags_to_args`, `_build_pair`, `_upstream`, `pen_environment`) | `engine/Model.kt` | **hecho** |
| `common/bisync.py` | `engine/Bisync.kt` | **hecho** |
| `common/config_file.py` | `engine/Toml.kt` | **hecho** |
| `common/pairing.py` (la mitad que lee) | `engine/Pairing.kt` | **hecho** |
| `common/catalog.py` | `engine/Catalog.kt` | pendiente — **solo lectura** en el paso 1, así que nada de la ceremonia de `push()` |
| `common/results.py`, `common/conflicts.py` | `engine/Results.kt` | pendiente — en el paso 1 los conflictos solo se **detectan y avisan** |
| `install/deploy.py` (`device_config`, `make_local_dirs`) | `setup/Volumen.kt` | pendiente |
| `sync.py` (`build_command`, `KNOWN_ERRORS`, `explain_failure`) | `rclone/Pasada.kt` | pendiente — y **no es una línea de órdenes**: ver más abajo |

### El riesgo que esto crea, y qué lo tapa

Esas constantes existen ahora **dos veces**. Copiarlas a un test de Kotlin solo
movería el problema: el test diría lo que creyó quien lo escribió, no lo que
hace prdrive hoy.

Así que **no se copian: se generan.** `herramientas/vectores.py` importa el
prdrive de verdad, le pregunta caso por caso y escribe las respuestas en
`engine/src/test/resources/vectores.json`, con la versión y el commit de prdrive
dentro. Los tests de Kotlin **no llevan ni un valor esperado escrito a mano**.

```bash
python herramientas/vectores.py ../prdrive   # regenerar
./gradlew :engine:test                       # 58 tests, sin SDK ni dispositivo
```

Cuando prdrive mueva una constante, falla el test que la nombra y dice cuál.

## Arquitectura

### rclone como biblioteca: lo que el plan original daba por hecho y es falso

El plan decía usar `librclone` y hablarle por `core/command` con
`_async: true`. **`core/command` no sirve desde una biblioteca, y no es un
detalle**, son dos razones independientes:

1. `core/command` está declarado con `NeedsRequest: true, NeedsResponse: true`
   (`fs/rc/internal.go:487`), y `librclone.RPC()` **rechaza de plano** cualquier
   método así. Lo dice su propio comentario: *«operations/uploadfile and
   core/command are not supported as they need request or response object»*
   (`librclone/librclone/librclone.go`).
2. Aunque lo aceptara, su implementación hace `os.Executable()` y
   `exec.CommandContext(ex, …)` (`fs/rc/internal.go`, `rcRunCommand`): **lanza
   el binario de rclone como proceso hijo**. Dentro de una app de Android,
   `os.Executable()` es el proceso de la app, no un rclone.

La ruta buena es el método RPC **`sync/bisync`** (`cmd/bisync/rc.go`), que no
solo existe: devuelve justo lo que hace falta.

```
parámetros que acepta   path1, path2, resync, dryRun, maxDelete, filtersFile,
                        workdir, conflictResolve, conflictLoser, conflictSuffix,
                        resilient, recover, maxLock, createEmptySrcDirs,
                        checkAccess, force, checkSync, resyncMode, compare,
                        ignoreListingChecksum, noCleanup, removeEmptyDirs,
                        backupDir1/2, checkFilename, noSlowHash,
                        slowHashSyncOnly, downloadHash
devuelve                output    <- la salida capturada de la pasada
                        session   <- bilib.SessionName(fs1, fs2)
                        workDir, basePath, listing1, listing2, logFile
```

Tres consecuencias que cambian el diseño:

- **`output` es el log.** Es lo que alimenta el `KNOWN_ERRORS` de `sync.py` y lo
  que se enseña cuando una pareja falla. No hace falta ni `--log-file` ni leer
  ficheros.
- **`session` la calcula rclone.** El motor sigue sabiendo calcular el prefijo
  por su cuenta (hay que saberlo **antes** de ejecutar, para decidir si el
  baseline sirve), pero ahora se puede **comprobar contra la verdad** después de
  cada pasada. Es una red que el escritorio no tiene.
- **`bilib.CaptureOutput` redirige la salida del proceso entero**, así que dos
  pasadas a la vez se robarían el log. **Las parejas se ejecutan de una en una**,
  igual que `sync.py`.

Lo que el plan sí tenía bien: `_async: true` y `_group` funcionan (los maneja
`jobs.NewJob`, que es por donde pasa todo `librclone.RPC`), así que el progreso
en vivo sale de `core/stats` con `{"group": "job/<id>"}`
(`fs/accounting/stats_groups.go:71`) — **progreso como datos**, y eso sí es
mejor que leer las líneas del log como hace `common/progress.py`.

### Hay que construir el `.aar`; el de serie no vale

`librclone/gomobile/gomobile.go` importa **solo** `backend/all` y `lib/plugin`.
No importa `cmd/bisync`, ni `fs/sync`, ni `fs/operations`, así que en el `.aar`
de serie **`sync/bisync` no está registrado** (los métodos rc se registran en el
`init()` de su paquete). Se construye uno propio: un paquete Go de diez líneas
que reexporte `librclone` y añada los *blank imports* que faltan.

```go
package prdrive
import (
    _ "github.com/rclone/rclone/cmd/bisync"    // registra sync/bisync
    _ "github.com/rclone/rclone/fs/sync"       // sync/copy, sync/move, sync/sync
    _ "github.com/rclone/rclone/fs/operations" // operations/*
    _ "github.com/rclone/rclone/backend/..."   // cuáles: ver abajo
)
```

**Pregunta abierta, a decidir midiendo:** qué backends entran. `backend/all` son
~50 y se lleva la mayor parte del tamaño del `.aar`; una lista corta
(local + combine + sftp + webdav + crypt) da un APK mucho menor a cambio de
rechazar con un mensaje un `[remote]` del catálogo de otro tipo. Se construye de
las dos formas, se mide y se elige con el número delante. `combine` y `local`
no son negociables.

**Otras dos cosas que exige el `.aar`** y que el plan no mencionaba:

- `_config` y `_filter` son parámetros genéricos del RPC (`fs/rc/context.go`):
  `_config` pisa el `fs.ConfigInfo` de esa llamada (`--transfers`, `--checkers`,
  `--dry-run`, y el `--max-delete` **como cuenta** de los modos `*-mirror`) y
  `_filter` los include/exclude de los modos que no son bisync. Es por ahí por
  donde pasan los flags de `BASE_FLAGS` que no son parámetros de `sync/bisync`.
- `librclone.Initialize()` llama a `configfile.Install()`, así que rclone lee su
  config del sitio de siempre: hay que apuntarlo al `rclone.conf` del volumen
  **antes** de la primera llamada.

### `max-delete` no significa lo mismo en los dos modos

Encontrado revisando el original, y hay que saberlo para no traducirlo mal:
**en un `bisync`, rclone lee el `--max-delete` global y lo reinterpreta como un
porcentaje.** `cmd/bisync/cmd.go` (`applyContext`) lo acota a 0..100 y después
pone `ci.MaxDelete = -1` para que `fs/operations` no lo aplique además como
cuenta; `cmd/bisync/deltas.go` (`excessDeletes`) compara
`borrados / listados_anteriores` contra ese porcentaje. En el RPC el parámetro
`maxDelete` **exige** estar entre 0 y 100.

Así que el `max-delete = 25` de `MODES["bisync"]` en prdrive significa «no más
del **25 %** de lo que había», y el `max-delete = 50` de los modos `*-mirror`
—que son `sync`— sí es «no más de **50 ficheros**». La protección de fondo
aguanta (un lado vacío es el 100 % borrado, y salta), pero los dos números de la
misma frase de `AGENTS.md` miden cosas distintas. **Es un hallazgo sobre
prdrive, no sobre esta app**, y se arregla allí; aquí queda anotado en
`engine/Model.kt` para que nadie «corrija» el 25 pensando que son ficheros.

### El volumen

`filesDir/volumen/` guarda las carpetas de las parejas, y
`filesDir/volumen/.prdrive/` el `rclone.conf`, `keys/`, `state/`, `filters/` y
`logs/` — la misma distribución que un disco de verdad, a propósito: los
ficheros de estado siguen siendo legibles por el programa de escritorio y no hay
que inventar nada.

### El remote `combine` va en el `rclone.conf`, no en el entorno

En el escritorio, `Config.pen_environment()` pone `RCLONE_CONFIG_DISP_*` en cada
ejecución. En una biblioteca no hay proceso hijo al que pasarle un entorno, así
que la app escribe una sección `[disp]` con su `upstreams`
(`Config.seccionCombine()`). **Las cadenas de los extremos salen idénticas**, y
con ellas el nombre de los listados de bisync.

Y hay una razón por la que esto sale incluso *mejor*: rclone añade un sufijo
`{hexstring}` al nombre del Fs cuando su configuración viene de flags, del
entorno o de una connection string — que es justo el caso del escritorio — y
`StripHexString` lo quita después (`cmd/bisync/bilib/canonical.go`). Definiendo
`[disp]` en el fichero no hay hexstring que quitar, y el nombre coincide con el
que ya calcula el escritorio. Comprobado: `expectedPrefix()` de
`engine/Bisync.kt` reproduce el de prdrive pareja por pareja
(`BisyncTest.el prefijo esperado de cada pareja coincide con el del escritorio`).

Por eso **`device_remote` es obligatorio en esta app** y no un valor por
defecto como allí: la ruta absoluta del volumen es `filesDir`, que lleva dentro
el id de usuario de Android y cambia si la app se reinstala o se mueve de
perfil. Un config sin `device_remote` no es un caso legado, es un config que
rompería los baselines más adelante, así que `parseConfig()` lo rechaza.

### La app de Archivos

Un `DocumentsProvider` publica la raíz del volumen como una ubicación «prdrive»
en la app de Archivos del sistema y en los diálogos de abrir/guardar de otras
apps, escondiendo `.prdrive/`. Sin permisos, y las fechas de modificación
funcionan porque esto es almacenamiento normal de la app — que es exactamente lo
que hace poco fiable a bisync en tarjetas SD y discos USB.

## El repositorio

Kotlin, Jetpack Compose, minSdk 26 (`java.nio.file` y, con él, las operaciones
atómicas de fichero que usan `store.py` y el editor de conflictos).
Comentarios y texto para el usuario en **español**, como el resto del proyecto.

```
engine/     Kotlin puro, sin Android: Model, Bisync, Toml, Pairing (+ Catalog,
            Results). Se prueba con `./gradlew :engine:test`, sin SDK ni
            dispositivo, y es donde viven las constantes copiadas de prdrive.
rclone/     el paquete Go que construye el .aar, y el envoltorio Kotlin del RPC
app/        Android: volumen + DocumentsProvider, setup (QR, perfil,
            rclone.conf, elegir parejas, resync inicial) y ui (Compose)
herramientas/ vectores.py — saca de prdrive lo que el motor debe reproducir
```

`engine/` es un módulo aparte y sin una línea de Android **a propósito**: es lo
que permite probar en CI, en segundos, la parte cuyo error sería caro, sin
emulador. La regla equivalente a la de `tk_*` en el otro repo: **`app/` solo
dibuja y llama; nada de `engine/` sabe que existe Android.**

### Pantallas del paso 1

1. *Primer arranque*: crear el volumen → «Escanear código» (el escáner de
   códigos de ML Kit, que no pide permiso de cámara; con pegar-texto como
   alternativa) → comprobar la conexión (`operations/about` o un `lsd`) → leer
   el catálogo → parejas con casillas, todas marcadas → crear carpetas →
   `--resync` inicial con progreso → listo.
2. *Principal*: un «Sincronizar» grande, una fila por pareja (última pasada,
   si pide resync, aviso de conflictos), la línea de progreso en vivo, y el log
   cuando una pareja falla — con las explicaciones de `KNOWN_ERRORS`.

## Cómo se comprueba

- **Este repositorio, sin dispositivo:** `./gradlew :engine:test`. Hoy 58 tests,
  todos contra los vectores generados desde prdrive: fusión de flags, la cadena
  `upstreams` (incluida la pareja de la raíz y el caso `raiz` y las comillas de
  Windows), el nombre de sesión de bisync, `fresh|ok|broken` sobre `.lst` de
  mentira, el round-trip del TOML y la vuelta del payload del QR.
- **Spike del `.aar`, antes de escribir la app:** construirlo con `gomobile
  bind`, llamar a `RcloneRPC("rc/noop", …)` para ver que arranca, y a
  `RcloneRPC("sync/bisync", …)` entre **dos carpetas locales** — sin red — para
  ver que el método está registrado y que devuelve `output` y `session`.
  Comparar ese `session` con el `expectedPrefix()` del motor: si no coinciden,
  el problema está aquí y se ve antes de tocar el remoto.
- **En un teléfono:** una pareja de verdad contra el remoto: sincronizar, editar
  un fichero en el móvil, sincronizar, y confirmarlo en el PC. Comprobar que el
  baseline sobrevive a una segunda pasada (un prefijo que se mueve aparece como
  «necesita resync» todas las veces).
- Confirmar que el volumen sale en la app de Archivos y que un fichero guardado
  ahí desde otra app se sincroniza en la pasada siguiente.

## Fuera del paso 1, explícitamente

Servicio en segundo plano y sincronización periódica, el vigilante de montaje,
editar el catálogo, las notas de flota, la pantalla de resolver conflictos,
la autoactualización, VeraCrypt/BitLocker, y usar una SD o un USB de verdad
como volumen (eso pide el permiso de «acceso a todos los archivos», así que se
queda fuera).
