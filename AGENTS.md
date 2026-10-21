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
└── Python.kt   repr(), str(), bool(), int(), strip() y fnmatch de Python, para
                decir lo mismo que prdrive en sus mensajes
rclone/
├── go/rclone/  el paquete Go del .aar: librclone + sync/bisync + backends
├── construir.sh   gomobile bind -> rclone/build/rclone.aar (no se versiona)
app/          Android: solo dibuja y llama
├── rclone/Rclone.kt  el envoltorio del RPC (rpc, trabajo con _async)
└── androidTest/  el spike contra rclone de verdad, y los vectores del motor
                  otra vez en Android (MotorEnAndroidTest)
herramientas/
└── vectores.py genera los valores esperados desde el prdrive de verdad
```

Pendiente (ver `PLAN.md`): la pasada (`Pasada.kt`: de una pareja a los
parámetros de `sync/bisync`), el volumen, el `DocumentsProvider` y las pantallas.

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
- **Ni una dependencia**, como en prdrive, y eso incluye las de test: el lector
  de JSON de `Vectores.kt` está escrito a mano por esa razón, y solo se compila
  en los tests.
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

```bash
python herramientas/vectores.py ../prdrive
```

## Órdenes

```bash
./gradlew :engine:test     # todo el motor; no necesita SDK ni dispositivo
./gradlew :engine:test --tests 'prdrive.engine.BisyncTest'
python herramientas/vectores.py ../prdrive   # regenerar los vectores
rclone/construir.sh        # el .aar (Go, gomobile y un NDK)
./gradlew :app:connectedDebugAndroidTest     # en un emulador o un teléfono
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
  no qué función llama.

## Ojo con Android

- **Las expresiones regulares son las de ICU, no las de Java.** `\d`, `\w` y
  `\s` son Unicode, y `(?U)` no existe (el motor no llegaba a cargar en el
  emulador por eso). En `engine/` se escriben las clases a mano: `[0-9]`,
  `\p{L}\p{N}_`, `[\t\n\f\r ]`. `MotorEnAndroidTest` vuelve a pasar los
  vectores en el dispositivo para que no se cuele otra.
- **`String.format` usa las cifras del idioma del teléfono** (en árabe, `٠١`).
  Nada que acabe en un fichero o en un mensaje comparado pasa por él.

## Ojo con estas cosas de rclone

- **`max-delete` no mide lo mismo en los dos modos.** En un `bisync` rclone lee
  el `--max-delete` global y lo reinterpreta como **porcentaje**
  (`cmd/bisync/cmd.go`, `applyContext`; `cmd/bisync/deltas.go`,
  `excessDeletes`), y en el RPC el parámetro exige estar entre 0 y 100. En los
  modos `*-mirror`, que son `sync`, el mismo número es una **cuenta de
  ficheros**. Así que el 25 de bisync y el 50 de espejo no son comparables.
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
  prdrive no coincide con él (comprobado en el emulador): el `\s` de
  `CanonicalPath` es el de RE2 (`[\t\n\f\r ]`, no Unicode), y el `Root()` de un
  remote `local` codifica los caracteres de control (`\x0b` es `␋`).
