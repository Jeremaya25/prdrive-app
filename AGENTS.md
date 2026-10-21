# AGENTS.md

Guía para agentes que trabajen en este repositorio.

## Qué es esto

**prdrive-app**: la versión Android de [prdrive](https://github.com/Jeremaya25/prdrive),
app autónoma en Kotlin. Donde prdrive vive *en* un disco extraíble y lo ejecuta
el Python del propio disco, aquí el «disco» es una carpeta que la app posee
dentro de su almacenamiento privado (un *volumen virtual*) y rclone viaja dentro
como biblioteca, no como binario.

**No reimplementa sincronizar**: eso lo hace rclone. Lo que se traduce a Kotlin
es lo que hace `sync.py` — montar la orden correcta y contar cómo ha ido.
**Los ficheros de Python de prdrive son la especificación.**

El plan del paso 1, con todo lo comprobado contra el código de rclone, está en
[`PLAN.md`](PLAN.md). Léelo antes de tocar arquitectura.

## Distribución

```
engine/       Kotlin puro (JVM), SIN una línea de Android
├── Model.kt    espejo de common/model.py: MODES, BASE_FLAGS, capas de flags,
│               lo que el config no puede llevar (comprobar_seguridad),
│               Pareja/Config, el llavero, el `upstreams` del remote 'combine'
├── Bisync.kt   espejo de common/bisync.py: nombre de sesión (con alias y
│               remotes locales), filtros, estado del baseline, referencia
│               vacía, apartar y renombrar
├── Toml.kt     espejo de common/config_file.py: leer Y escribir, con el
│               round-trip verificado
├── Pairing.kt  la mitad que LEE de common/pairing.py: la carga del QR
├── Catalogo.kt la mitad que LEE de common/catalog.py: remote.toml o pairs.toml,
│               la copia sin red y la carpeta que no es un fichero
├── Resultados.kt  espejo de common/results.py: state/last_run.json
├── Conflictos.kt  espejo de common/conflicts.py: los perdedores de bisync
├── Store.kt    lo que hace falta de common/store.py: fechas y escritura atómica
├── Json.kt     json.dumps/loads de Python, byte a byte (state/ lo lee el PC)
├── Python.kt   repr(), str(), bool(), int(), strip(), splitlines(), sorted() y
│               fnmatch de Python, para decir y ordenar lo mismo que prdrive
├── Orden.kt    espejo de common/orden.py: la orden de rclone de una pareja
├── Pasada.kt   espejo de sync.py: una pasada entera (preflight, la llamada,
│               el log, los resultados y los conflictos) contra un `Rclone` que
│               se pasa, y la traducción de la orden a parámetros del RPC
└── Volumen.kt  espejo de install/: la carpeta del volumen, su .prdrive/ y lo
                que se escribe al emparejar
rclone/
├── go/rclone/  el paquete Go del .aar: librclone + sync/bisync + backends
├── go/oraculo/ lo que dice el código de rclone donde el motor sigue a rclone
│               y no a prdrive (escribe oraculo_rclone.json)
├── construir.sh   gomobile bind -> rclone/build/rclone.aar (no se versiona)
app/          Android: solo dibuja y llama
├── Prdrive.kt        dónde vive el volumen, cómo arranca rclone y el permiso
│                     de red local
├── Instalador.kt     el primer arranque sin pantallas (espejo del orden de
│                     install/): conectar, leer el catálogo, sembrar, --resync
├── Estado.kt         lo que la principal enseña de cada pareja, leído de state/
├── ui/               Modelo (el ViewModel), Pantallas (Compose) y Escaner (el
│                     de códigos de Play services)
├── rclone/Rclone.kt  el envoltorio del RPC (rpc, trabajo con _async)
├── rclone/Adaptadores.kt  el `Rclone` de la pasada y el `cat` del catálogo,
│                     sobre el RPC
├── documentos/VolumenProvider.kt  el volumen en la app de Archivos, sin .prdrive/
└── androidTest/  el spike contra rclone de verdad, la pasada entera, el
                  proveedor, los vectores del motor otra vez en Android
                  (MotorEnAndroidTest) y las pantallas de punta a punta
                  contra un servidor SFTP (PuntaAPuntaTest)
herramientas/
├── vectores.py        genera los valores esperados desde el prdrive de verdad
├── vectores_paso3.py  su parte de la pasada y el volumen
├── vectores_python.py lo que el motor imita del propio Python (y de tomllib)
└── punta_a_punta.py   el servidor y la carga del QR de PuntaAPuntaTest
```

El paso 1 de `PLAN.md` está hecho. Lo que queda fuera está en su último
apartado («Fuera del paso 1»).

## Reglas que no se cruzan

- **`engine/` no sabe que existe Android.** Ni `android.*`, ni `Context`, ni
  rutas absolutas del dispositivo. Es lo que permite probar en segundos, en CI y
  sin emulador, la parte cuyo error sería caro. Es la regla equivalente a la de
  `tk_*` en prdrive: ahí solo se dibuja, y aquí `app/` solo dibuja y llama.
- **El motor habla de rutas relativas a la raíz del volumen**, que es lo que
  dice el TOML. La absoluta la sabe la app (`filesDir`), y no entra aquí: por
  eso no hay `local_abs` como en `model.py`.
- **Cada fichero de `engine/` nombra el fichero de Python que refleja.** Y los
  que replican a rclone conservan las citas a su código fuente
  (`cmd/bisync/bilib/canonical.go`, `fs/types.go`, `cmd/bisync/resolve.go`…),
  igual que `common/bisync.py`. Si se toca algo de ahí, es contra esas fuentes
  contra lo que se contrasta, no contra lo que parezca razonable.
- **`engine/` no tiene ni una dependencia**, como prdrive, y eso incluye las de
  test (solo `kotlin-test`): el JSON y el TOML están escritos a mano por esa
  razón. `app/` usa lo justo de AndroidX y Compose, y el `.aar` de rclone.
- **`device_remote` es obligatorio** (diferencia deliberada con prdrive, donde es
  un valor por defecto): la ruta absoluta del volumen es `filesDir`, que cambia
  al reinstalar la app, así que un config sin él rompería los baselines de
  bisync más adelante.

## Los valores esperados no se escriben a mano

Las constantes de prdrive existen ahora **dos veces**, y dos copias se separan.
Copiarlas a un test solo mueve el problema: el test diría lo que creyó quien lo
escribió, no lo que hace prdrive hoy.

Así que se **generan**. `herramientas/vectores.py` importa el prdrive de verdad,
le pregunta caso por caso y escribe `engine/src/test/resources/vectores.json`,
con la versión y el commit de prdrive dentro. **Ningún test lleva un valor
esperado escrito a mano.** Si añades algo al motor que refleje a Python, añade su
caso al generador y regenera; no escribas el número.

Lo que el motor imita del propio Python (`repr()` de un float, `fnmatch`,
`json.loads`, `strptime`, `tomllib`…) también lo contesta Python, desde
`herramientas/vectores_python.py`. Y donde el motor sigue a rclone y no a
prdrive (el nombre de sesión con espacios raros, la raíz de un remote `local`),
lo contesta el código de rclone: `rclone/go/oraculo` escribe
`engine/src/test/resources/oraculo_rclone.json`.

```bash
python herramientas/vectores.py ../prdrive
cd rclone/go && go run ./oraculo ../../engine/src/test/resources/oraculo_rclone.json
```

## Órdenes

```bash
./gradlew :engine:test     # todo el motor; no necesita SDK ni dispositivo
./gradlew :engine:test --tests 'prdrive.engine.BisyncTest'
python herramientas/vectores.py ../prdrive   # regenerar los vectores
rclone/construir.sh        # el .aar (Go, gomobile y un NDK)
./gradlew :app:connectedDebugAndroidTest     # en un emulador o un teléfono
```

`connectedDebugAndroidTest` instala y ejecuta en **todos** los dispositivos
conectados, teléfonos incluidos. Con uno enchufado, se elige el emulador con
`ANDROID_SERIAL=emulator-5554`.

`PuntaAPuntaTest` se salta si no le llega la carga de un QR: necesita un
servidor. `herramientas/punta_a_punta.py` lo monta (remoto, catálogo, claves y
carga) con un `rclone` de la versión del `.aar`, y dice cómo lanzar la prueba:

```bash
python herramientas/punta_a_punta.py ../prdrive /ruta/a/rclone /tmp/e2e
```

El módulo `app` solo existe si hay un SDK de Android (`ANDROID_HOME`,
`ANDROID_SDK_ROOT` o `sdk.dir` en `local.properties`): el CI del motor no lo
tiene, y `:engine:test` no lo necesita.

Gradle 9.6 y Kotlin 2.4. `engine/` se construye con cualquier JDK 17 o
posterior (el JBR 25 de Android Studio vale) y genera bytecode 17. Los avisos
del compilador son errores (`allWarningsAsErrors`). No hay linter aparte.

El generador de vectores sale igual en Windows que en Linux: le hace creer a
prdrive que corre en un dispositivo POSIX con la raíz en `/volumen`.

En Windows, si Gradle se cae al arrancar con «Unable to establish loopback
connection», es el JDK que no puede crear su socket local en la carpeta
temporal (pasa con rutas cortas 8.3 como `PCERQU~1`). Se arregla apuntándolo a
otra:

```bash
export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
```

## Convenios

- Comentarios, KDoc y texto para el usuario en **español**. Igual que prdrive.
- Los comentarios explican **por qué**, contra el comportamiento real de rclone,
  y citan su fuente cuando la hay.
- Los nombres del dominio se quedan en español (`Pareja`, `Carga`,
  `EstadoPareja`) porque son los del TOML y los de prdrive; lo que es API de
  Kotlin/Java se escribe como se escribe en Kotlin.
- Los tests se llaman con una frase entre acentos graves que diga qué garantiza,
  no qué función llama. En `androidTest` va con guiones bajos
  (`una_pasada_que_falla_guarda_el_log_y_lo_explica`): el `.dex` de minSdk 26 no
  admite espacios en los nombres de método.

## Ojo con Android

- **Las expresiones regulares son las de ICU, no las de Java.** `\d`, `\w` y
  `\s` son Unicode, y `(?U)` no existe (el motor no llegaba a cargar en el
  emulador por eso). En `engine/` se escriben las clases a mano: `[0-9]`,
  `\p{L}\p{N}_`, `[\t\n\f\r ]`. `MotorEnAndroidTest` vuelve a pasar los
  vectores en el dispositivo para que no se cuele otra.
- **`String.format` usa las cifras del idioma del teléfono** (en árabe, `٠١`).
  Nada que acabe en un fichero o en un mensaje comparado pasa por él.
- **Ni el `.` ni el `$` son los de Python.** El `.` de Java tampoco casa con
  `\r`, `\u0085`, ` ` ni ` ` (y el de ICU, ni con `\u000b` ni con
  `\u000c`); el `$` de Python casa también delante de un `\n` final. Donde
  prdrive dice `^(.+)…$`, aquí va `([^\n]+)…\n?` con `matchEntire`. Y dentro de
  unos corchetes, `&&` o `[` son sintaxis de Java: `fnmatch` escribe cada
  carácter como `\x{…}`.
- **Desde API 37, la red local pide permiso** (`ACCESS_LOCAL_NETWORK`, de los
  que se piden al usarlo). Sin él, conectar con una dirección privada (un NAS
  en `192.168.…`, el PC del emulador en `10.0.2.2`) no falla: se queda colgado
  hasta el timeout. La app lo pide al conectar y al sincronizar, y lo explica
  si falta. `adb shell` sí llega, así que no sirve para descartarlo.
- **La carga del QR lleva la clave privada.** No se guarda con
  `rememberSaveable` (el estado guardado de la actividad puede acabar en disco)
  ni se deja a la vista en el campo de texto.
- **Los tests de Compose en API 37** necesitan Espresso 3.7 (el que trae
  `ui-test` busca `InputManager.getInstance`, que ya no existe) y las reglas
  `junit4.v2` (las otras están obsoletas, y los avisos son errores).

## Ojo con estas cosas de rclone

- **`max-delete` no mide lo mismo en los dos modos.** En un `bisync` rclone lee
  el `--max-delete` global y lo reinterpreta como **porcentaje**
  (`cmd/bisync/cmd.go`, `applyContext`; `cmd/bisync/deltas.go`,
  `excessDeletes`), y en el RPC el parámetro exige estar entre 0 y 100. En los
  modos `*-mirror`, que son `sync`, el mismo número es una **cuenta de
  ficheros**. Así que el 25 de bisync y el 50 de espejo no son comparables.
- **Y el `maxDelete` del RPC no es el de la línea de órdenes.** Sin él, la
  línea de órdenes usa el de fábrica (50, `DefaultMaxDelete`) y el RPC usa 0:
  ningún borrado pasa. Negativo o por encima de 100, la línea de órdenes lo
  ajusta y el RPC se niega. La pasada lo manda siempre, ya resuelto
  (`Pasada.maxDeleteBisync`).
- **La línea de órdenes no es el RPC**, y `llamadaDe` traduce como lee pflag:
  - Las opciones que se repiten (`stringArray`: `--exclude-from`,
    `--files-from`…) van como lista. Un texto plano rclone lo partiría por las
    comas.
  - Las letras (`-u`, `-uc`) salen del `ShortOpt` de `options/info`, y `-1`
    de bisync es `--resync`.
  - `--max_delete` no existe: pflag no normaliza `_`.
  - Algunos flags no son opciones (`--delete-after`, `--header`, los de un
    backend como `--sftp-…`): por el RPC no se pueden pasar, y se explica.
  - Los tipos y las letras de las pruebas salen del oráculo, que llama al
    `options/info` de verdad.
- **Cancelar una pasada es parar el trabajo y esperar a que acabe**
  (`job/stop` y `job/status` hasta `finished`): si no, el lock de bisync se
  queda puesto y la captura del log se cruza con la de la pasada siguiente.
  Una pasada cancelada no se apunta, como un Ctrl+C en el escritorio.
- **`core/command` no sirve desde la biblioteca**, ni aunque lo veas en la
  documentación de rclone: `librclone.RPC()` rechaza los métodos que necesitan
  request/response, y su implementación lanza un proceso hijo. La ruta es
  `sync/bisync`. Los detalles y las líneas exactas están en `PLAN.md`.
- **Una pasada se pide como trabajo (`_async`) y se sigue con `job/status`.**
  La llamada síncrona, si falla, devuelve solo el error; el resultado del
  trabajo conserva el log de la pasada (`output`) también cuando falla.
- **El nivel de log es el global**: `fs.Infof` mira `GetConfig(context.TODO())`
  (`fs/log.go`), no el de la llamada. El `--verbose` de `BASE_FLAGS` se pone una
  vez al arrancar con `options/set` (`{"main": {"LogLevel": "INFO"}}`); sin él,
  una pasada buena no deja nada en `output`. Las demás opciones globales sí se
  pueden pasar con la llamada, como parámetros planos con su nombre de opción y
  el formato del flag (`"modify_window": "2s"`; `fs/rc/context.go`,
  `ParseOptions`).
- **El nombre de sesión lo manda rclone, no prdrive**, en dos sitios donde
  prdrive no coincide con él (comprobado en el emulador y con el oráculo): el
  `\s` de `CanonicalPath` es el de RE2 (`[\t\n\f\r ]`, no Unicode), y el
  `Root()` de un remote `local` lee la ruta como ya codificada y la vuelve a
  codificar (`Standard.Encode(Standard.Decode(tramo))`: `\x0b` sale `␋`, y una
  `‛` suelta sale doble).
- **`--stats` es solo de la línea de órdenes** (`cmd/cmd.go`), no una opción:
  en el RPC no existe, y la pasada no lo manda. El progreso sale de
  `core/stats` con el grupo `job/<id>`.
- **`sync/copy` y `sync/sync` no devuelven su log**; `sync/bisync`, sí. Para los
  primeros, `Capturar`/`SoltarCaptura` del paquete Go apilan una salida del log
  de rclone mientras dura la llamada (`fs/log/slog.go`: la salida es una pila),
  y por eso las pasadas van de una en una.
- **rclone olvida un trabajo al minuto de acabar** (`JobExpireDuration`): el
  resultado se recoge en cuanto termina.
- **Cuando bisync aborta dice «Must run --resync to recover.», con mayúscula**
  (`cmd/bisync/operations.go`), y la aguja de `KNOWN_ERRORS` de prdrive es «must
  run --resync»: solo casa con los avisos de los filtros (`cmd/bisync/cmd.go`),
  que van en minúscula. Se replica tal cual, porque es de prdrive; `PLAN.md` lo
  apunta como hallazgo.
- **El lock de bisync es JSON** (`cmd/bisync/lockfile.go`): con `--max-lock`
  puesto, uno que no se puede leer cuenta como caducado y se pasa por encima.
- **bisync se para si cambian todos los ficheros de un lado** («Safety abort:
  all files were changed»). En un test con un solo fichero, cambiarlo es
  cambiarlos todos: hace falta otro que no se toque.
