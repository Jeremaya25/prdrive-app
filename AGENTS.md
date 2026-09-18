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
└── Pairing.kt  la mitad que LEE de common/pairing.py: la carga del QR
herramientas/
└── vectores.py genera los valores esperados desde el prdrive de verdad
```

Pendiente (ver `PLAN.md`): `engine/Catalog.kt`, `engine/Results.kt`, el paquete
Go que construye el `.aar`, su envoltorio Kotlin, y el módulo `app/`.

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
```

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
