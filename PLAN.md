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
| `sync.py` (`build_command`, `KNOWN_ERRORS`, `explain_failure`) | `engine/Pasada.kt` | **hecho** — y **no es una línea de órdenes**: ver más abajo |
| `common/progress.py` | `engine/Progreso.kt` | **hecho** — el canal cambia, el texto no |
| `common/results.py` | `engine/Results.kt` | **hecho** |
| (nuevo aquí) | `engine/Json.kt` | **hecho** — leer y escribir el JSON del RPC, sin dependencias |
| `common/catalog.py` | `engine/Catalog.kt` | **hecho** — **solo lectura**, así que nada de la ceremonia de `push()` |
| `install/deploy.py` (`device_config`, `write_device_remote`, `make_local_dirs`) | `engine/Volumen.kt` | **hecho** |
| `common/conflicts.py` | `engine/Conflictos.kt` | pendiente — en el paso 1 los conflictos solo se **detectan y avisan** |

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
./gradlew :engine:test                       # 136 tests, sin SDK ni dispositivo
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

- **`session` la calcula rclone.** El motor sigue sabiendo calcular el prefijo
  por su cuenta (hay que saberlo **antes** de ejecutar, para decidir si el
  baseline sirve), pero ahora se puede **comprobar contra la verdad** después de
  cada pasada. Es una red que el escritorio no tiene, y es lo que hace
  `SesionesTest`.
- **`bilib.CaptureOutput` redirige la salida del proceso entero**, así que dos
  pasadas a la vez se robarían el log. **Las parejas se ejecutan de una en una**,
  igual que `sync.py`.
- **`_async: true` es obligatorio**, no una opción para el progreso. Ver abajo.

Lo que el plan sí tenía bien: `_async: true` y `_group` funcionan (los maneja
`jobs.NewJob`, que es por donde pasa todo `librclone.RPC`), así que el progreso
en vivo sale de `core/stats` con `{"group": "job/<id>"}`
(`fs/accounting/stats_groups.go:71`) — **progreso como datos**, y eso sí es
mejor que leer las líneas del log como hace `common/progress.py`.

### El log de una pasada: cuatro cosas comprobadas ejecutándolas

El plan decía «`output` es el log, no hace falta ni `--log-file` ni leer
ficheros». Es verdad a medias, y las medias verdades aquí se pagan cuando algo
falla, que es justo cuando hace falta el log. Todo esto lo comprueba
`rclone/spike` ejecutándolo, no leyéndolo:

1. **`_async` es obligatorio.** `librclone.RPC` **descarta el `out` de la
   llamada cuando esta devuelve error** (`writeError`,
   `librclone/librclone/librclone.go`): una pasada fallida por la vía síncrona
   devuelve `{"error": …}` y ningún `output`. Con `_async` sobrevive, porque
   `job.finish()` asigna `job.Output` **antes** de mirar el error
   (`fs/rc/jobs/job.go`), así que `job/status` trae las dos cosas.
2. **El error del job no explica nada.** Es un «bisync aborted». El
   diagnóstico —«cannot find prior Path1 or Path2 listings…», el que
   `KNOWN_ERRORS` traduce— está en `output`, que **es el log entero de la
   pasada**: `bilib.CaptureOutput` instala un `SetOutput` en el handler del log
   y le sube el nivel a INFO mientras dura (`cmd/bisync/bilib/output.go`).
3. **Pero eso solo vale para bisync.** Los otros cuatro modos de prdrive son
   `copy` y `sync`, y `rcSyncCopyMove` devuelve `nil` (`fs/sync/rc.go`): ni log
   ni nada. Así que el log sale de un **sumidero en memoria**
   (`fs/log.Handler.AddOutput`, `fs/log/slog.go`), que vale para los cinco
   modos y no cuesta un fichero — ni los ciclos de escritura que
   `dispose_log()` cuida en prdrive. Es `RcloneLogTexto()`.
4. **Y hay dos cosas que NO se pueden pedir por llamada**, aunque lo parezca:
   - **El nivel de log.** `fs.Infof` y compañía se guardan contra
     `GetConfig(context.TODO())` (`fs/log.go`), o sea la configuración
     **global**, no la del contexto de la llamada. Y hay que mover dos: el
     nivel global, que decide si el mensaje se emite, y el del handler, que
     decide si se escribe. Con una sola no se ve nada y no avisa. Es
     `RcloneLogNivel()`, y el `verbose = true` de `BASE_FLAGS` es `INFO`.
   - **Los colores ANSI.** `TerminalColorMode` es un `fs.Enum` y el `_config`
     del RPC **lo ignora sin quejarse** (un `transfers` inválido da 400; un
     `color` inválido pasa de largo). Y una vez encendidos no se apagan:
     bisync guarda la decisión en `Colors`, una global de paquete que solo se
     pone a `true` (`cmd/bisync/operations.go`), así que la primera pasada del
     proceso decide para toda la vida de la app. Se apagan en la
     configuración global, dentro de `RcloneInitialize`.

Y una quinta, del mismo orden: **`RcloneSetConfigPath` va antes de
`RcloneInitialize`**. `librclone.Initialize()` llama a `configfile.Install()`,
que es `config.SetData()`, y esa función **se va de vacío si el path está sin
fijar** (*«If no config file, use in-memory config»*, `fs/config/config.go`).
Al revés del orden bueno rclone arranca con una configuración vacía en memoria
y el fallo no sale ahí: sale después, diciendo «unknown remote».

### Cómo viaja un flag, que no es como parecía

`jobs.NewJob` llama a `rc.AddConfig(ctx, in)` y a `rc.AddFilter(ctx, in)`
(`fs/rc/jobs/job.go`), así que los parámetros genéricos funcionan también por
librclone: eso el plan lo tenía bien. Lo que no tenía bien es **por dónde**.
Las dos acaban en `rc.ParseOptions` (`fs/rc/context.go`), que admite los
valores por dos caminos que **no son equivalentes**:

- **Sueltos, en el primer nivel de la llamada.** Los recoge
  `configstruct.SetAny`, y los nombres son las etiquetas `config:"…"` de
  `fs.ConfigInfo` y de `filter.Options`, o sea snake_case: `dry_run`,
  `max_delete`, `transfers`, `include`, `exclude`.
- **Dentro de `_config` / `_filter`.** Los recoge `GetStructMissingOK` →
  `rc.Reshape`, que es `json.Marshal` + `json.Unmarshal`. Y esas estructuras
  **no llevan etiquetas `json`**, así que lo que casa es el nombre del **campo
  de Go** (`DryRun`, `MaxDelete`, `IncludeRule`), no el de la etiqueta.
  `encoding/json` ignora el guión bajo y **descarta sin decir nada** lo que no
  encuentra.

O sea que `{"_config":{"dry_run":true}}` —la forma que este plan daba por
hecha— no pone ningún dry-run: **sincroniza de verdad**. Medido en el spike,
copiando dos ficheros:

| cómo se manda | ficheros copiados | |
|---|---|---|
| `"dry_run":true` suelto | 0 | funciona |
| `"_config":{"dry_run":true}` | **2** | se descarta en silencio |
| `"_config":{"DryRun":true}` | 0 | funciona, con el nombre del campo |
| `"include":["*.txt"]` suelto | 1 | funciona |
| `"_filter":{"IncludeRule":["*.txt"]}` | 1 | funciona |
| `"_filter":{"include":["*.txt"]}` | **2** | se descarta en silencio |

Un «Simular» que sincroniza es el peor fallo que puede tener este proyecto, y
esta es la línea que lo separaba de ocurrir. De paso explica mejor lo del
`color` de la sección anterior: no es que rclone ignore los `fs.Enum`, es que
`TerminalColorMode` tiene la etiqueta `color` y el campo se llama de otra
manera.

**Y los tipos, por lo mismo.** Los parámetros propios de `sync/bisync` sí se
leen uno a uno, pero los cuatro enumerados (`checkSync`, `resyncMode`,
`conflictResolve`, `conflictLoser`) pasan por `setEnum`, que trata «no es una
cadena» igual que «no está»: un `check-sync = false` escrito como booleano en
el TOML **se ignora**. Solo una cadena con un valor inválido se queja — y ni
siquiera con un 400, porque `setEnum` devuelve un error pelado y el 400 es de
`rc.NewErrParamInvalid`. Comprobado: `"tonteria"` → 500 con el mensaje bueno;
`5` → 200, pasada hecha, parámetro olvidado.

Por eso `Pasada.kt` lleva una tabla con el **tipo** de cada parámetro y
convierte antes de escribir el JSON, en vez de confiar en que rclone avise. Y
por eso el spike apunta en `sesiones.json` los nombres y los tipos que rclone
**declara** en la ayuda que registra con el método (`cmd/bisync/rc.md`, que
genera su propio `go generate`): así la tabla se compara contra rclone y no
contra lo que leyó quien la escribió.

Un detalle que salió de ahí: **`maxDelete` no está en esa ayuda.** `rcBisync`
lo lee, y encima valida que esté entre 0 y 100 porque en bisync es un
porcentaje, pero `--max-delete` es un flag global y no uno de bisync, así que
`rc.md` no lo lista. Quien lea la documentación concluirá que el rc de bisync
no puede limitar los borrados. Sí puede.

### Hay que construir el `.aar`; el de serie no vale

`librclone/gomobile/gomobile.go` importa **solo** `backend/all` y `lib/plugin`.
No importa `cmd/bisync`, ni `fs/sync`, ni `fs/operations`, así que en el `.aar`
de serie **`sync/bisync` no está registrado** (los métodos rc se registran en el
`init()` de su paquete). Se construye uno propio, y ya está: `rclone/gobind/`, que
reexporta `librclone` y añade los *blank imports* que faltan
(`cmd/bisync`, `fs/sync`, `fs/operations`) más lo que se ha visto que hace
falta encima — el log y el orden del `rclone.conf`. Comprobado registrándose:
`rc/list` los lista.

**Qué backends entran: medido, y decidido.** La pregunta era si merecía la pena
`backend/all` (~50 backends) o una lista corta que rechace con un mensaje un
`[remote]` del catálogo de otro tipo. Las dos están, elegidas con una etiqueta
de compilación (`backends_curados.go` por defecto, `-tags rclone_todos` la
otra), y las dos compilan en CI. Construidas con `gomobile bind` para
`android/arm64` y la API 26:

| juego de backends | el `.aar` | el `.so` sin comprimir |
|---|---|---|
| curados (local · combine · crypt · sftp · webdav) | **12,6 MB** | 26,1 MB |
| `backend/all` | **43,9 MB** | 103,0 MB |

**Entran los curados.** `backend/all` cuesta 31 MB más de descarga y **cuatro
veces** el tamaño instalado, porque el `.so` va sin comprimir en el APK. No es
una diferencia de matiz que se pueda dejar para más adelante: es la diferencia
entre una app de 13 MB y una de 44 MB, y lo que compra son backends que este
proyecto no usa —el catálogo dice de qué tipo es el remoto, y en prdrive es
sftp o webdav—. Un `[remote]` de otro tipo se rechaza con una frase que dice
qué pasa, que es mucho mejor que 31 MB por si acaso. `combine` y `local` no son
negociables: sin ellos no hay lado del dispositivo.

Se vuelve a medir con `sh rclone/aar.sh` (pide un NDK y un SDK; los detalles
que no son evidentes están comentados dentro), o a mano desde la pestaña
Actions con el flujo `aar`.

**Otras dos cosas que exige el `.aar`** y que el plan no mencionaba:

- Los flags que no son parámetros del método van **sueltos en el primer nivel
  de la llamada, y NO dentro de `_config` ni de `_filter`**. Esto es un
  hallazgo, y de los caros: la sección siguiente lo explica con los números
  medidos.
- `librclone.Initialize()` llama a `configfile.Install()`, así que hay que
  apuntar rclone al `rclone.conf` del volumen **antes** de esa llamada, no
  después. Los detalles, en la sección del log: equivocarse no da error.

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

**Y el `rclone.conf` de la app es derivado, no guardado.** En el escritorio
lleva rutas relativas (`key_file = keys/id_ed25519`) y eso es justo lo que hace
que el disco funcione con cualquier letra de unidad, porque `sync.py` ejecuta
rclone con `cwd = APP_DIR`. Aquí no hay proceso al que fijarle un cwd: rclone
es una biblioteca dentro de la app, y el directorio de trabajo del proceso no
es nuestro para cambiarlo. Así que las rutas van **absolutas** y el fichero se
**reescribe en cada arranque**.

No es una complicación añadida, es una regla que ya hacía falta: el `upstreams`
del remote `combine` también es absoluto, así que ya había que regenerarlo
cuando `filesDir` cambia —al reinstalar la app o al moverla de perfil—. Ahora
la regla es una sola y vale para las dos cosas. rclone lo relee él solo, porque
`Storage._check()` compara la fecha y el tamaño en cada lectura
(`fs/config/config.go`). Lo único que no se regenera es la clave privada: eso
llega una vez, con el QR.

### Leer el catálogo sin línea de órdenes

En el escritorio el catálogo se lee con `rclone cat nas:/prdrive-catalog/pairs.toml`.
Aquí no hay `cat`: `core/command` no sirve (arriba), y el rc **no tiene ningún
método que devuelva el contenido de un fichero**. Lo que sí hay es
`operations/copyfile` (`fs/operations/rc.go`), que copia de un `Fs` a otro — y
el backend `local` está compilado dentro, así que el destino es un fichero del
volumen y después se lee con `File.readText()`.

Sale gratis: ese fichero es justo la copia local que había que guardar de todas
formas (`state/catalog.toml`), o sea lo que permite abrir la pantalla de
parejas sin red. Y aquí `_async` **no** hace falta, al contrario que en una
pasada: un error de `operations/copyfile` no trae ninguna salida que se pueda
perder, solo su mensaje, y ese sí viaja en el JSON aunque el estado no sea 200.

Los flags con los que se le habla son los de `catalog.NET_FLAGS` —10 s de
conexión, 20 s de datos, un reintento—, y no son una optimización: con los
valores de rclone por defecto (5 min de timeout, 3 reintentos) una wifi mala
deja la pantalla colgada varios minutos en vez de caer a la copia.

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
engine/     Kotlin puro, sin Android: Model, Bisync, Toml, Pairing, Json,
            Pasada, Progreso, Results (+ Catalog, Conflictos, Volumen). Se
            prueba con `./gradlew :engine:test`, sin SDK ni dispositivo, y es
            donde viven las constantes copiadas de prdrive.
rclone/     el paquete Go que construye el .aar, y el spike que lo comprueba
app/        Android: volumen + DocumentsProvider, setup (QR, perfil,
            rclone.conf, elegir parejas, resync inicial) y ui (Compose)
herramientas/ vectores.py — saca de prdrive lo que el motor debe reproducir
```

**Una corrección sobre el reparto:** el plan ponía el envoltorio del RPC en
`rclone/`, junto al paquete Go. Está en `engine/` porque **cabe entero ahí**:
la superficie de `librclone` es `rpc(método, entrada) -> (salida, estado)`, así
que la parte que decide *qué* se le manda —que es toda la traducción de
`sync.py`, y la que se puede equivocar en silencio— se prueba sin JNI, sin NDK
y sin dispositivo, contra un doble de tres funciones ([Rclone]). Lo que queda
para `app/` es el doble de verdad, que son tres líneas sobre el `.aar`.

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

Dos niveles, y la diferencia entre ellos importa: uno comprueba que la
traducción es fiel, el otro que el original acertaba.

- **El motor contra prdrive:** `./gradlew :engine:test`, sin SDK ni
  dispositivo. Hoy **136 tests**, ninguno con un valor esperado escrito a
  mano. Contra `vectores.json`, generado del Python de prdrive: fusión de
  flags, la cadena `upstreams` (con la pareja de la raíz, el caso `raiz` y las
  comillas de Windows), el nombre de sesión de bisync, `fresh|ok|broken` sobre
  `.lst` de mentira, el round-trip del TOML, la vuelta del payload del QR, las
  agujas de `KNOWN_ERRORS`, los flags que el motor se reserva y la frase del
  progreso con sus redondeos.
- **El motor contra rclone:** `cd rclone && go run ./spike`. Esto es lo que
  responde la pregunta que lo anterior deja abierta —*¿y si los dos se
  equivocan igual?*—, porque el nombre de los listados lo decide rclone y
  hasta aquí nadie se lo había preguntado a rclone.

  El spike arranca rclone **como biblioteca**, con el mismo paquete que
  empaqueta el `.aar`, monta un volumen de mentira con la distribución de
  verdad y sincroniza tres parejas: una normal, la de la **raíz** (`local =
  "."`, la que necesita el upstream con nombre) y una con un **espacio** en la
  ruta. De cada una hace el `--resync` y después una segunda pasada, que es la
  que prueba que el baseline sirve.

  No necesita NDK, ni emulador, ni red: `gomobile bind` solo añade el JNI
  encima de `librclone.RPC`, y `FsPath` —de donde sale el nombre de sesión—
  solo mira el nombre y la raíz del `Fs`, **no el tipo de backend**
  (`cmd/bisync/bilib/canonical.go`). Así que un remoto de tipo `local` llamado
  `nas` recorre el mismo código que un sftp llamado `nas`.

  Escribe `engine/src/test/resources/sesiones.json`, y `SesionesTest` compara
  eso con lo que calcula el motor: el prefijo pareja por pareja, los extremos,
  la sección `[disp]` con su entrecomillado, dónde caen los `.lst`, el md5 que
  bisync escribió junto al fichero de filtros, y —lo más fuerte— **el JSON
  exacto de cada llamada**: el que sale de `Pasada.kt` contra el que el spike
  le pasó a `librclone.RPC` y del que salieron esas sesiones. Si coinciden
  carácter por carácter, los nombres y los tipos de los parámetros son los que
  rclone acepta de verdad. Es la única forma de comprobarlo, porque
  equivocarse ahí no da error. Y **falla** si alguna
  afirmación de este documento sobre rclone deja de ser cierta, así que subir
  la versión de rclone no puede romper el diseño en silencio. Corre en CI
  (`.github/workflows/rclone.yml`), que además comprueba que el JSON del
  repositorio es el que el spike genera hoy.

Lo que sigue pendiente, y por qué:

- **En un teléfono:** una pareja de verdad contra el remoto: sincronizar,
  editar un fichero en el móvil, sincronizar, y confirmarlo en el PC.
  Comprobar que el baseline sobrevive a una segunda pasada (un prefijo que se
  mueve aparece como «necesita resync» todas las veces).
- Confirmar que el volumen sale en la app de Archivos y que un fichero
  guardado ahí desde otra app se sincroniza en la pasada siguiente.

## Fuera del paso 1, explícitamente

Servicio en segundo plano y sincronización periódica, el vigilante de montaje,
editar el catálogo, las notas de flota, la pantalla de resolver conflictos,
la autoactualización, VeraCrypt/BitLocker, y usar una SD o un USB de verdad
como volumen (eso pide el permiso de «acceso a todos los archivos», así que se
queda fuera).
