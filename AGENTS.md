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

Por qué la arquitectura es la que es, comprobado contra el código de rclone,
está más abajo («Arquitectura»): léelo antes de tocarla. Lo que queda por hacer,
en [`PLAN.md`](PLAN.md).

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
├── Volumen.kt  espejo de install/: la carpeta del volumen, su .prdrive/ y lo
│               que se escribe al emparejar
└── Actualizacion.kt  espejo de common/update.py: la última release, la caché
                de un día, comparar versiones, y el APK de la release
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
├── Actualizador.kt   la app se pone al día sola: baja el APK de la release, lo
│                     comprueba y se lo da a PackageInstaller («La
│                     autoactualización»)
├── ui/               Modelo (el ViewModel), Pantallas (Compose), Tema (claro u
│                     oscuro, como diga el teléfono) y Escaner (el de códigos
│                     de Play services)
├── rclone/Rclone.kt  el envoltorio del RPC (rpc, trabajo con _async)
├── rclone/Adaptadores.kt  el `Rclone` de la pasada y el `cat` del catálogo,
│                     sobre el RPC
├── documentos/VolumenProvider.kt  el volumen en la app de Archivos, sin .prdrive/
└── androidTest/  el spike contra rclone de verdad, la pasada entera, el
                  proveedor, los vectores del motor otra vez en Android
                  (MotorEnAndroidTest), lo que el actualizador mira de un APK
                  (ActualizadorTest), el modo oscuro (TemaTest) y las
                  pantallas de punta a punta contra un servidor SFTP
                  (PuntaAPuntaTest)
herramientas/
├── vectores.py        genera los valores esperados desde el prdrive de verdad
├── vectores_paso3.py  su parte de la pasada y el volumen
├── vectores_python.py lo que el motor imita del propio Python (y de tomllib)
├── vectores_actualizacion.py  su parte de common/update.py, con el
│                      repositorio de la app y la respuesta de GitHub guardada
└── punta_a_punta.py   el servidor y la carga del QR de PuntaAPuntaTest
.github/
├── workflows/engine.yml   el motor, en cada push
├── workflows/apk.yml      el APK de release sin firmar, en los PR que lo tocan
├── workflows/release.yml  la release, cuando un push a main cambia VERSION:
│                          compila sin firmar, y firma y publica en el entorno
│                          `release`
└── actions/compilar-apk/  lo que comparten: el .aar, los tests del motor y el
                           APK sin firmar
VERSION       la versión de la app, y la única («Versiones y releases»)
```

Lo que queda por hacer está en [`PLAN.md`](PLAN.md).

## Arquitectura

Lo decidido y por qué. Lo que se afirma de rclone está comprobado contra el
código de la versión que fija prdrive (`common/pins.py`, hoy v1.75.2), no
contra su documentación, y se cita el sitio.

- **Decisiones de partida:** reescritura entera en Kotlin, repositorio aparte,
  emparejamiento por QR y volumen visible en la app de Archivos. El QR lo
  enseña un dispositivo de prdrive ya provisionado (`common/pairing.py`), y su
  `leer()` es la definición ejecutable del formato que reproduce `Pairing.kt`.
- **rclone como biblioteca, por `sync/bisync`.** `core/command` no sirve, por
  dos razones independientes:
  - Está declarado con `NeedsRequest` y `NeedsResponse`
    (`fs/rc/internal.go:487`), y `librclone.RPC()` rechaza esos métodos («core/command
    are not supported as they need request or response object»,
    `librclone/librclone/librclone.go`).
  - Su implementación (`rcRunCommand`) lanza rclone como proceso hijo con
    `os.Executable()`, que en Android es la app.

  `sync/bisync` (`cmd/bisync/rc.go`) devuelve `output`, que es el log de la
  pasada y lo que lee `KNOWN_ERRORS`, y `session` (`bilib.SessionName`), que se
  compara con el prefijo que calcula el motor antes de ejecutar.
  `bilib.CaptureOutput` redirige la salida del proceso entero: las parejas van
  de una en una, como en `sync.py`.
- **El `.aar` es propio** (`rclone/go/rclone`). El de serie
  (`librclone/gomobile`) solo importa `backend/all` y `lib/plugin`, así que
  `sync/bisync` no está registrado: los métodos rc se registran en el `init()`
  de su paquete. Los backends, medido con Go 1.27.3, NDK r28c y `-s -w`:

  | | `.aar` | `libgojni.so` arm64 | x86_64 |
  |---|---|---|---|
  | lista corta (local, combine, alias, sftp, webdav, crypt) | 15,6 MB | 19,9 MB | 21,5 MB |
  | `backend/all` | 54,0 MB | 77,5 MB | 83,9 MB |

  Entra la corta (`backends_corta.go`). Un `[remote]` de otro tipo se rechaza
  con el mensaje de rclone, y `rclone/construir.sh todos` construye la otra.
  `local` y `combine` no son negociables.
- **El volumen** es `filesDir/volumen/`: las carpetas de las parejas, y en
  `.prdrive/` el `rclone.conf`, `keys/`, `state/`, `filters/` y `logs/`. Es la
  distribución de un disco de prdrive, a propósito: el escritorio lee el estado
  que escribe el teléfono.
- **El remote `combine` va en el `rclone.conf`** (`[disp]`,
  `Config.seccionCombine()`), no en el entorno como en el escritorio
  (`pen_environment()`): una biblioteca no tiene proceso hijo al que pasarle
  variables. Y sale mejor: con la configuración en flags o en el entorno,
  rclone añade un `{hexstring}` al nombre del Fs que `StripHexString` quita
  después (`cmd/bisync/bilib/canonical.go`). Definido en el fichero, no hay nada
  que quitar, y el nombre de los listados es el del escritorio.
- **La app de Archivos**: un `DocumentsProvider` publica el volumen sin pedir
  permisos. Al ser almacenamiento de la app, las fechas de modificación se
  conservan, que es lo que hace poco fiable a bisync en una SD o un USB.

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
./gradlew :app:assembleRelease  # el APK de release («Versiones y releases»)
```

`connectedDebugAndroidTest` instala y ejecuta en **todos** los dispositivos
conectados, teléfonos incluidos. Con uno enchufado, se elige el emulador con
`ANDROID_SERIAL=emulator-5554`. La batería entera son unos 30 s con el APK ya
compilado; mientras se trabaja en algo, una clase sola (unos 18 s, casi todo
instalar):

```bash
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=prdrive.app.RcloneSpikeTest
```

Gradle guarda la configuración entre órdenes (`org.gradle.configuration-cache`
en `gradle.properties`): lo que se escriba en un `build.gradle.kts` dentro de
una acción de tarea (`doFirst`, `doLast`) no puede mirar variables del script,
sino una copia local (como el `.aar` de `preBuild`).

Si un test de una pasada falla, el informe está en
`app/build/reports/androidTests/connected/debug/`. Los que llevan el log de
bisync en el mensaje usan la regla `Banco.Legible`: el log trae colores, y un
carácter de control deja el XML del informe ilegible («Could not load test
results», sin decir qué test ha fallado). Y una pasada sin ningún test falla
(`app/build.gradle.kts`): a veces el emulador no encuentra la instrumentación
recién instalada, y AGP lo daba por bueno. Se vuelve a lanzar.

`PuntaAPuntaTest` se deja fuera si no le llega la carga de un QR (el motor de
tests de AGP cuenta un `assumeTrue` como fallo): necesita un servidor.
`herramientas/punta_a_punta.py` lo monta (remoto, catálogo, claves y carga) con
un `rclone` de la versión del `.aar`, y dice cómo lanzar la prueba:

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

## Versiones y releases

`VERSION`, en la raíz, es la única fuente de la versión, como en prdrive. De
ella salen el `versionName` y el `versionCode` del APK (`app/build.gradle.kts`)
y el tag de la release. El `versionCode` es mayor·1 000 000 + menor·1 000 +
parche, porque Android solo instala encima una versión con un código igual o
mayor; un sufijo (`1.2.0-rc1`) no cuenta. Con una `VERSION` que no cabe ahí,
Gradle no llega a configurar.

**Publicar es subir `VERSION` a main.** Un push a main que toca ese fichero
lanza `release.yml`, en dos trabajos:

- `compilar`, con el token de solo lectura: construye el `.aar`, pasa los tests
  del motor y compila el APK de release, que sale **sin firmar** (Gradle no
  firma nunca). Aquí corre todo lo que bajan Gradle y Go.
- `publicar`, en el entorno `release`: firma con `apksigner` en un solo paso
  (la clave existe lo que dura ese paso), comprueba la firma y la alineación y
  publica `v<VERSION>` con `prdrive-<VERSION>.apk`. No compila nada.

El tag queda en el commit que se ha compilado: el del push, o la punta de main
si se lanza a mano. Si el tag ya existe no hay versión nueva y sale en verde sin
publicar; lanzado a mano (para repetir una publicación que falló), eso es un
error. Si algo falla antes de publicar, no hay tag ni release; si falla al subir
el APK, puede quedar un borrador de release, que se borra a mano antes de
repetir. Y GitHub solo deja una pasada en espera: con una publicando y otra
esperando, una tercera subida de `VERSION` cancela la que esperaba, y esa
versión intermedia no se publica.

**La firma es una y para siempre.** Android solo instala una versión encima de
otra si las dos llevan la misma firma: perder la clave es que nadie pueda
actualizar sin desinstalar, y desinstalar borra el volumen. Vive en el
**entorno `release`** del repositorio, limitado a la rama main, y no en los
secretos del repositorio: esos los ve cualquier workflow lanzado desde
cualquier rama del repositorio (un PR de una rama propia, o una copia de
`release.yml` sin la comprobación de main), y los del entorno, no.

- `FIRMA_ALMACEN_B64` (secreto): un almacén PKCS12 con la clave `prdrive`, en
  base64.
- `FIRMA_CONTRASENA` (secreto): su contraseña (en un PKCS12, la misma para la
  clave).
- `FIRMA_SHA256` (variable, opcional pero recomendable): la huella del
  certificado. Con ella, una release firmada con otra clave no sale; sin ella,
  la primera release decide cuál es.

Se crean una vez, **antes de juntar el PR que trae `VERSION`**: ese push a main
ya es una release. El almacén se guarda fuera del repositorio y con copia (el
`.gitignore` no deja entrar `*.p12`, `*.jks` ni `*.keystore`):

```bash
keytool -genkeypair -storetype PKCS12 -keystore prdrive.p12 -alias prdrive \
    -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=prdrive"
gh api -X PUT repos/Jeremaya25/prdrive-app/environments/release --input - \
    <<< '{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}'
gh api -X POST repos/Jeremaya25/prdrive-app/environments/release/deployment-branch-policies \
    -f name=main -f type=branch
base64 -w0 prdrive.p12 | gh secret set FIRMA_ALMACEN_B64 --env release
gh secret set FIRMA_CONTRASENA --env release
keytool -list -v -keystore prdrive.p12 -alias prdrive   # la línea SHA256:
gh variable set FIRMA_SHA256 --env release --body "<esa huella>"
```

Para firmar en local, lo mismo que hace el CI:

```bash
./gradlew :app:assembleRelease
apksigner sign --ks prdrive.p12 --ks-key-alias prdrive --out prdrive.apk \
    app/build/outputs/apk/release/app-release-unsigned.apk
```

`apk.yml` compila ese mismo APK sin firmar en los PR que lo tocan, para saber
antes de juntar que la release saldrá. Las dos usan la acción
`.github/actions/compilar-apk`, con el Go del `toolchain` de
`rclone/go/go.mod` y el NDK r28c, los de los tamaños de «Arquitectura»; el
`.aar` sale de una caché (la de main: la de un PR solo la ve ese PR) mientras
no cambien `rclone/go` ni `construir.sh`.

El APK de depuración va firmado con la clave de depuración de cada máquina: no
se instala encima de una release ni al revés.

## La autoactualización

La app se pone al día sola desde esas releases (`Actualizador.kt`). Qué hay y
si es más nuevo lo dice el motor (`Actualizacion.kt`), que es el espejo de
`common/update.py` con el repositorio de la app: la misma caché de un día, la
misma comparación de versiones y las mismas frases. Lo que trae la versión
nueva no se parece: el escritorio baja el zip del código y se despliega a sí
mismo; aquí se baja el APK que cuelga `release.yml` (`prdrive-<VERSION>.apk`) y
lo instala Android.

- **Cuándo.** Al abrir la app se pregunta a GitHub, como mucho una vez al día. Si
  hay versión nueva se baja sola, salvo con datos móviles (son unos 50 MB) o en
  una compilación de depuración, que no la podría poner encima (otra firma): ahí
  hay un botón. Se instala **al salir de la app**, porque instalarse la cierra,
  y nunca con una pasada en marcha (`Modelo.trabajo` y
  `Actualizador.empezarTrabajo`): cortarla deja el lock de bisync puesto. Solo
  con el volumen sembrado: a medio emparejar, cerrar la app perdería la carga del
  QR, que no se guarda.
- **Sin preguntar** solo desde Android 12, y solo si la persona ha permitido una
  vez que prdrive instale apps (`REQUEST_INSTALL_PACKAGES`, con
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION` y `setRequireUserAction`). Antes de
  Android 12, o sin el permiso, Android pregunta, y la tarjeta de la principal
  tiene «Instalar ahora» y el botón para dar el permiso.
- **Play Protect** no deja instalar en segundo plano un APK que no ha visto:
  contesta `INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed` al medio
  minuto (veredicto 9 en el log de `Finsky`). Ese intento hace que lo mire, y el
  siguiente pasa (veredicto 15). Como cada release es un APK que no ha visto
  nunca, se intenta dos veces por proceso (`fallosSinPreguntar`); si Android pide
  confirmación aun con el permiso dado, se para a la primera. Con la app
  delante, «Instalar ahora» pasa a la primera.
- **Qué se comprueba antes**: el tamaño y la suma SHA-256 que da GitHub
  (`digest`), que el APK sea de esta app, de la versión de la release, más nuevo
  (`versionCode`) y con alguna firma en común. Android vuelve a mirar las dos
  últimas y es quien manda; esto es para decir por qué no antes de pedir nada, y
  una versión que no se puede poner encima se apunta (`rechazada.json`) para no
  bajarla cada día.
- **Dónde**: la caché y lo apuntado en `noBackupFilesDir/actualizacion/`, y el
  APK en `cacheDir/actualizacion/`. Fuera del volumen: no es del disco de
  prdrive, y el escritorio no tiene por qué leerlo.
- **La primera versión con esto se instala a mano**: las anteriores no saben
  ponerse al día.

Probarlo pide un teléfono o un emulador, porque instalarse cierra el proceso
del test: `ActualizadorTest` solo prueba lo que se mira de un APK. A mano, con
APK de depuración (todos llevan la clave de depuración de la máquina, así que
uno se pone encima de otro): se instala uno con `VERSION` vieja, se deja el de
la versión nueva en `cache/actualizacion/` y un `update.json` reciente que lo
describa en `no_backup/actualizacion/` (con `adb shell run-as prdrive.app`), y se
abre y se sale de la app. Para que se instale sola, el volumen tiene que estar
sembrado (`sync_config.toml` y `rclone.conf` en `.prdrive/`) y el permiso dado
(`adb shell appops set prdrive.app REQUEST_INSTALL_PACKAGES allow`).

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
- **En las pantallas no se escribe ningún color**: todo sale de
  `MaterialTheme.colorScheme`. Es lo que hace que el modo oscuro salga solo
  (`ui/Tema.kt`, que sigue al del teléfono); un `Color(0xFF…)` se vería igual
  en los dos modos. El único color escrito es el fondo de la ventana
  (`res/values*/tema.xml`), y `TemaTest` comprueba que es el de Compose.

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
- **Y una bisync parada puede decir que ha ido bien sin haberlo hecho.** `sync`
  se traga el `context.Canceled` de un `job/stop` si no hay una transferencia
  en vuelo (`fs/sync/sync.go`, `processError`: su `inCtx` cuelga del contexto
  cancelado, y lo toma por un corte suyo). bisync da por hechas las copias que
  le quedaban, dice «Bisync successful» y escribe unos listados con ellas: la
  pasada siguiente ve «borrados» en un lado los ficheros que no llegaron, y los
  borra del otro (comprobado con 3000 ficheros: solo lo para el 25 % de
  `max-delete`). Cortada al listar o a media copia, aborta como debe. Por eso,
  si hubo que pararla y rclone no dice que falló, la app aparta los listados
  como hace rclone con una pasada interrumpida (`.lst` → `.lst-err`,
  `markFailed` en `cmd/bisync/lockfile.go`; `Adaptadores.apartarListados`), y
  la siguiente pide `--resync`, que no borra nada. En el escritorio no pasa: un
  Ctrl+C va por `atexit`, no por el contexto.
- **`core/command` no sirve desde la biblioteca**, ni aunque lo veas en la
  documentación de rclone: `librclone.RPC()` rechaza los métodos que necesitan
  request/response, y su implementación lanza un proceso hijo. La ruta es
  `sync/bisync`. Las líneas exactas, en «Arquitectura».
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
