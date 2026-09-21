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
│               Pareja/Config, el `upstreams` del remote 'combine'
├── Bisync.kt   espejo de common/bisync.py: nombre de sesión, filtros, estado
│               del baseline, apartar y renombrar
├── Toml.kt     espejo de common/config_file.py: leer Y escribir, con el
│               round-trip verificado
├── Pairing.kt  la mitad que LEE de common/pairing.py: la carga del QR
├── Json.kt     leer y escribir el JSON del RPC; escribe como lo hace Go
├── Pasada.kt   espejo de sync.py: cómo viaja cada flag hasta el RPC, el
│               interfaz Rclone y la traducción de KNOWN_ERRORS
├── Progreso.kt espejo de common/progress.py: la misma frase, sacada de
│               core/stats en vez de del log
├── Results.kt  espejo de common/results.py: state/last_run.json y qué log
│               se guarda
├── Sincronizacion.kt  la otra mitad de sync.py (run_pair, run_all): el orden,
│               lo que se salta, lo que se aborta y lo que se avisa
├── Conflictos.kt espejo de common/conflicts.py: el nombre que rclone le pone
│               al perdedor, y de qué lado viene
├── Catalog.kt  espejo de common/catalog.py, SIN la mitad de escribir: el
│               catálogo del remoto, su copia local y el aviso de un backend
│               que no viaja en el .aar
└── Volumen.kt  la parte de install/deploy.py que un dispositivo necesita: la
                distribución del volumen, el rclone.conf (que es DERIVADO) y
                el sync_config.toml de las parejas elegidas
rclone/       módulo Go: rclone como biblioteca
├── gobind/     lo que empaqueta `gomobile bind` en el .aar: librclone con los
│               métodos que hacen falta registrados, el sumidero del log y el
│               orden del rclone.conf
└── spike/      arnés que EJECUTA rclone y comprueba las afirmaciones del PLAN;
                genera engine/src/test/resources/sesiones.json
herramientas/
└── vectores.py genera los valores esperados desde el prdrive de verdad
```

Pendiente (ver `PLAN.md`): el módulo `app/`, que solo dibuja y llama.

El envoltorio del RPC está en `engine/` y no en `rclone/` como decía el plan,
porque cabe entero ahí: la superficie de `librclone` es
`rpc(método, entrada) -> (salida, estado)`, así que lo que decide *qué* se
manda se prueba sin JNI contra un doble de tres funciones (`Rclone`). Lo que
queda para `app/` son esas tres líneas sobre el `.aar`.

## Reglas que no se cruzan

- **`engine/` no sabe que existe Android.** Ni `android.*`, ni `Context`, ni
  rutas absolutas del dispositivo. Es lo que permite probar en segundos, en CI y
  sin emulador, la parte cuyo error sería caro. Es la regla equivalente a la de
  `tk_*` en prdrive: ahí solo se dibuja, y aquí `app/` solo dibuja y llama.
- **El motor habla de rutas relativas a la raíz del volumen**, que es lo que
  dice el TOML. La absoluta la sabe la app (`filesDir`), y no entra aquí: por
  eso no hay `local_abs` como en `model.py`. `Volumen` es la excepción y la
  regla a la vez: recibe la raíz por parámetro y resuelve contra ella, así que
  sigue sin saber dónde está.
- **El `rclone.conf` se reescribe en cada arranque.** Lleva rutas absolutas
  —aquí no hay cwd que fijarle a rclone, que es una biblioteca dentro de la
  app— y `filesDir` cambia al reinstalar. No se edita: se genera.
- **Cada fichero de `engine/` nombra el fichero de Python que refleja.** Y los
  que replican a rclone conservan las citas a su código fuente
  (`cmd/bisync/bilib/canonical.go`, `fs/types.go`, `cmd/bisync/resolve.go`…),
  igual que `common/bisync.py`. Si se toca algo de ahí, es contra esas fuentes
  contra lo que se contrasta, no contra lo que parezca razonable.
- **Ni una dependencia**, como en prdrive, y eso incluye las de test: el lector
  de JSON está escrito a mano por esa razón (`Json.kt`), y es **uno solo** para
  el RPC y para los vectores — la misma regla que el único lector de
  rclone.conf de prdrive.
- **Todo lo que toca rclone pasa por el interfaz `Rclone`** (`Pasada.kt`), que
  es el punto de indirección que en prdrive es `catalog.run()`: ningún test
  necesita un rclone, y la app implementa tres funciones sobre el `.aar`.
- **`device_remote` es obligatorio** (diferencia deliberada con prdrive, donde es
  un valor por defecto): la ruta absoluta del volumen es `filesDir`, que cambia
  al reinstalar la app, así que un config sin él rompería los baselines de
  bisync más adelante.

## Dos fuentes de verdad, y no son intercambiables

`vectores.json` sale del **Python de prdrive** y responde «¿la traducción es
fiel?». `sesiones.json` sale de **rclone ejecutándose** (`rclone/spike`) y
responde la que la otra deja abierta: «¿y si los dos se equivocan igual?». El
nombre de los listados de bisync lo decide rclone, así que hay que
preguntárselo a rclone.

Si tocas algo del prefijo, de los extremos o del fichero de filtros, se
regeneran **los dos** y los dos tienen que seguir cuadrando.

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
cd rclone && go run ./spike -json ../engine/src/test/resources/sesiones.json
```

## Órdenes

```bash
./gradlew :engine:test     # todo el motor; no necesita SDK ni dispositivo
./gradlew :engine:test --tests 'prdrive.engine.BisyncTest'
python herramientas/vectores.py ../prdrive   # regenerar los vectores de prdrive

cd rclone
go run ./spike                               # comprobar rclone, sin escribir
go run ./spike -json ../engine/src/test/resources/sesiones.json
go build ./gobind/ && go build -tags rclone_todos ./gobind/   # los dos juegos

# y medir el .aar, que es lo que decidió qué backends entran. Pide NDK y SDK;
# lo que no es evidente está comentado dentro. En cada push solo se comprueba
# que los dos juegos COMPILAN, que para eso no hace falta NDK.
ANDROID_HOME=... ANDROID_NDK_HOME=... sh rclone/aar.sh
```

El spike no necesita NDK, emulador ni red: `gomobile bind` solo añade el JNI
encima de `librclone.RPC`, y el nombre de sesión sale de `FsPath`, que no mira
el tipo de backend. Falla con un mensaje concreto si alguna afirmación del
`PLAN.md` sobre rclone deja de ser cierta.

`engine/` compila con cualquier JDK 17 o posterior y genera bytecode 17, que es
lo que consume AGP 8.x. Los avisos del compilador son errores
(`allWarningsAsErrors`). No hay linter aparte.

## Convenios

- Comentarios, KDoc y texto para el usuario en **español**. Igual que prdrive.
- Los comentarios explican **por qué**, contra el comportamiento real de rclone,
  y citan su fuente cuando la hay.
- Los nombres del dominio se quedan en español (`Pareja`, `Carga`,
  `EstadoPareja`) porque son los del TOML y los de prdrive; lo que es API de
  Kotlin/Java se escribe como se escribe en Kotlin.
- Los tests se llaman con una frase entre acentos graves que diga qué garantiza,
  no qué función llama.

## Ojo con estas dos cosas de rclone

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
- Y una que es al revés, de las que la documentación **no** dice: el parámetro
  `maxDelete` de `sync/bisync` funciona —`rcBisync` lo lee y valida que esté
  entre 0 y 100— pero **no está en la ayuda del método**, porque
  `--max-delete` es un flag global. Leyendo `rc.md` se concluiría que el rc de
  bisync no puede limitar los borrados.

## Y con estas seis, que no avisan

Las seis están comprobadas en `rclone/spike` y explicadas con sus líneas en
`PLAN.md`. Lo que tienen en común es que equivocarse **no da error**: el
síntoma aparece después y en otro sitio.

- **Los flags van SUELTOS, no dentro de `_config` ni de `_filter`.** Es la
  peor de las seis. `rc.ParseOptions` lee los sueltos con `configstruct` (los
  nombres son las etiquetas `config:`, en snake_case) y los de dentro de
  `_config` con un `json.Unmarshal` sobre una estructura **sin etiquetas
  `json`**, así que ahí solo casa el nombre del CAMPO de Go. Medido:
  `{"_config":{"dry_run":true}}` copió los dos ficheros; o sea que un
  «Simular» escrito así **sincroniza de verdad**.
- **Y los tipos también.** Los cuatro enumerados de bisync (`checkSync`,
  `resyncMode`, `conflictResolve`, `conflictLoser`) pasan por `setEnum`, que
  trata «no es una cadena» igual que «no está»: un `check-sync = false` como
  booleano se ignora. Por eso `Pasada.PARAMETROS_BISYNC` lleva el tipo de cada
  uno y `SesionesTest` lo compara con el que rclone declara en su ayuda.
- **`RcloneSetConfigPath` va ANTES de `RcloneInitialize`.** Al revés, rclone
  arranca con una configuración vacía en memoria y el fallo sale más tarde
  diciendo «unknown remote».
- **`sync/bisync` necesita `_async`.** Sin él, una pasada fallida pierde su
  log, que es justo cuando se necesita.
- **El nivel de log no se pide por llamada.** Es global y son dos sitios
  (`RcloneLogNivel`). Con uno solo no se ve nada.
- **Los colores ANSI tampoco.** El `_config` ignora `color` —por lo de la
  primera viñeta: la etiqueta es `color` y el campo se llama
  `TerminalColorMode`— y bisync guarda la decisión en una global que solo se
  enciende. Se apagan en `RcloneInitialize`.
