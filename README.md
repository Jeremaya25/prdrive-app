# prdrive-app

**prdrive para Android.** Sincroniza en los dos sentidos un remoto de
[rclone](https://rclone.org) con tu teléfono, gobernada por el mismo catálogo de
parejas que usan tus dispositivos de escritorio con
[prdrive](https://github.com/Jeremaya25/prdrive).

- **Sin teclear la conexión.** Se empareja leyendo un código QR que enseña un
  dispositivo de prdrive que ya funciona.
- **Sin permisos para lo básico.** Tus carpetas viven en el almacenamiento
  privado de la app, que Android cifra con el bloqueo de pantalla, y se ven
  desde la app de Archivos.
- **Sin reinventar la sincronización.** La hace rclone, que viaja dentro de la
  app como biblioteca. Lo que esta app traduce de prdrive es montar la orden
  correcta y contar cómo ha ido.
- **Un dispositivo más de la flota.** El escritorio puede leer el estado que
  escribe el teléfono (`state/`), porque el volumen tiene la misma distribución
  que un disco de prdrive.

## Qué hace

| | |
|---|---|
| **Sincronizar** | Pasa las parejas del catálogo (bisync y espejos), cuenta qué se ha copiado, qué se ha borrado y qué ha fallado, y guarda el log de cada pasada. |
| **Sincronizar sola** | Una pasada periódica en segundo plano (por defecto cada hora, nunca menos de 15 min; solo con wifi si no dices otra cosa). El intervalo y las parejas salen del `[daemon]` del config. |
| **Simulacro** | Un `--dry-run` desde la ficha de la pareja: enseña qué se copiaría, hacia dónde y qué se borraría, sin tocar nada. |
| **Conflictos** | Si editas el mismo fichero en dos sitios, quedan las dos versiones y la app lo avisa. Se resuelve desde la ficha, viendo antes las consecuencias. |
| **Llavero** | La base KeePass (`.kdbx`) del `[keychain]` de prdrive, sincronizada en segundo plano y abierta en tu app de KeePass ([KeePassDX](https://github.com/Kunzisoft/KeePassDX) está probada). Ver [El llavero](#el-llavero). |
| **Carpeta compartida** | Mueve una pareja a una carpeta del almacenamiento del teléfono para que otra app la abra. Ver [`AGENTS.md`](AGENTS.md) («Las carpetas compartidas»). |
| **Autoactualización** | La app se pone al día sola desde las releases de GitHub. Ver [Instalar y actualizar](#instalar-y-actualizar). |

La app tiene cuatro secciones: **Inicio** (cómo está todo en una frase y lo que
hay que revisar), **Parejas**, **Llavero** y **Ajustes** (el remoto, los
permisos y las actualizaciones).

## Instalar y actualizar

**Requisitos:** Android 8 o posterior (API 26). Para escanear el QR, Google Play
services. La carpeta compartida pide además Android 11 o posterior.

1. Descarga `prdrive-<versión>.apk` de las
   [releases](https://github.com/Jeremaya25/prdrive-app/releases) e instálalo
   (Android te pedirá permitir instalar desde esa fuente).
2. Ábrela y empareja (ver abajo).

Desde ahí **se actualiza sola**: al abrirla pregunta a GitHub (una vez al día) y
baja la versión nueva con wifi. Se instala al salir de la app, nunca con una
pasada en marcha. Sin preguntar solo desde Android 12 y si permites una vez que
prdrive instale apps; si no, Android pregunta, y la tarjeta de Inicio tiene
«Instalar ahora». Las versiones anteriores a la autoactualización hay que
instalarlas a mano. Los detalles, en [`AGENTS.md`](AGENTS.md) («La
autoactualización»).

> **Desinstalar borra el volumen**, y con él lo que no hubiera subido. Android
> solo instala una versión encima de otra con la misma firma: no te saltes la
> actualización por instalar un APK de otra procedencia.

## Emparejar

En un dispositivo de prdrive que ya esté provisionado: **Doctor → «Emparejar un
móvil…»**. En el teléfono, abre prdrive y sigue los cuatro pasos: escanear (o
pegar) el código, revisar la conexión que trae —nada se guarda todavía—, elegir
qué parejas del catálogo sincroniza el teléfono y la primera pasada
(`--resync`). Si quieres el llavero, marca «El llavero» al elegir las parejas.

> **El QR lleva la clave privada del remoto.** Enséñalo solo a tu propio
> teléfono, y no lo fotografíes ni lo compartas. La app no lo guarda.

La app incluye los backends `local`, `combine`, `alias`, `sftp`, `webdav` y
`crypt`. Un remoto de otro tipo se rechaza con el mensaje de rclone (hay que
construir el `.aar` con `rclone/construir.sh todos`).

## El llavero

El `[keychain]` de prdrive es una base KeePass que viaja en `.keychain/` y se
sincroniza contra `keychain/` en el remoto. El teléfono se **une** a un llavero
que ya existe (no lo crea) y no combina copias de conflicto: eso se hace en el
PC («Abrir llavero» → «Combinar») o a mano en la app de KeePass.

- **Abrir llavero** abre la base en tu app de KeePass; lo que guardes allí se
  sube solo, unos 20 s después, y solo si la base está entera.
- Si tu app de KeePass guarda sobre una versión vieja de la base, tu guardado
  va a una copia de conflicto avisada en lugar de pisar el cambio del otro
  dispositivo.
- Lo último del remoto lo trae «Abrir llavero» antes de abrir.

Las decisiones y sus porqués, en [`AGENTS.md`](AGENTS.md) («El llavero»).

## Estado

La app está en la **versión 1.0.0** y se ha probado en emuladores, no en un
teléfono real: [`PLAN.md`](PLAN.md) §1 lista lo que falta comprobar (una pareja
contra el remoto de casa, el permiso de red local en Android 17, Keepass2Android,
todo en arm64).

El motor está alineado con **prdrive 0.7.7**, que fija **rclone v1.75.2**. Lo
que aún no tiene la app (editar el catálogo, la reparación con `--force`, usar
una SD o un USB como volumen, iPhone) está en [`PLAN.md`](PLAN.md).

## Cómo está hecho

```
engine/       Kotlin puro (JVM): el espejo de prdrive. Sin una línea de Android
app/          Android (Compose): solo dibuja y llama
rclone/       el paquete Go que se compila al .aar con gomobile
herramientas/ generadores de los valores esperados y utilidades de prueba
```

- **`engine/` traduce a Kotlin lo que hace prdrive** —el nombre de sesión de
  bisync, las órdenes, los filtros, los resultados, el llavero, el catálogo—, y
  **los ficheros de Python de prdrive son la especificación**. No depende de
  Android ni de ninguna biblioteca, así que se prueba en segundos y sin
  emulador.
- **`app/` solo dibuja y llama**: pantallas, permisos, WorkManager, el
  proveedor de documentos (el volumen en la app de Archivos) y el envoltorio del
  RPC de rclone.
- **rclone va dentro como biblioteca**, llamado por `sync/bisync` del RPC (no
  por `core/command`, que no funciona desde la biblioteca). El `.aar` es propio
  porque el de serie no registra bisync.
- **El volumen** es `filesDir/volumen/`: las carpetas de las parejas y, en
  `.prdrive/`, el `rclone.conf`, las claves, el estado, los filtros y los logs.

El porqué de cada decisión, comprobado contra el código de rclone, está en
[`AGENTS.md`](AGENTS.md).

### Por qué el motor se prueba contra prdrive y no contra sí mismo

Las constantes de prdrive existen ahora dos veces, y dos copias se separan. De
ellas depende el nombre con el que bisync guarda su baseline: si el móvil lo
calcula distinto, bisync no lo encuentra y lo que falta en un lado se lee como
borrado.

Por eso los valores esperados **no se escriben a mano**: `herramientas/vectores.py`
importa el prdrive de verdad, le pregunta caso por caso y escribe las respuestas
en un JSON que los tests leen, con la versión y el commit de prdrive dentro.
Cuando prdrive mueve una constante, falla el test que la nombra. Lo que sigue a
rclone y no a prdrive lo contesta el código de rclone
(`rclone/go/oraculo`).

## Desarrollar

**Solo el motor** (JDK 17 o posterior; no necesita SDK, emulador ni teléfono):

```bash
./gradlew :engine:test
./gradlew :engine:test --tests 'prdrive.engine.BisyncTest'   # una clase
```

**Regenerar los valores esperados**, con prdrive junto a este repositorio:

```bash
python herramientas/vectores.py ../prdrive
cd rclone/go && go run ./oraculo ../../engine/src/test/resources/oraculo_rclone.json
```

**La app** necesita un SDK de Android (`ANDROID_HOME` o `sdk.dir` en
`local.properties`) y el `.aar` de rclone, que no se versiona (Go, `gomobile` y
un NDK; `rclone/construir.sh` explica los requisitos):

```bash
rclone/construir.sh                       # rclone/build/rclone.aar
./gradlew :app:assembleDebug              # APK de depuración
./gradlew :app:connectedDebugAndroidTest  # tests en un emulador o teléfono
```

> `connectedDebugAndroidTest` instala y ejecuta en **todos** los dispositivos
> conectados y **borra la app**, con lo que un emparejamiento hecho a mano se
> pierde. Con un teléfono enchufado, elige el emulador con
> `ANDROID_SERIAL=emulator-5554`.

`PuntaAPuntaTest` (las pantallas de punta a punta contra un servidor SFTP) lo
monta `herramientas/punta_a_punta.py`; su cabecera dice cómo lanzarlo.

### Publicar una versión

`VERSION`, en la raíz, es la única fuente de la versión. **Publicar es subir
`VERSION` a main**: `release.yml` compila el APK sin firmar, lo firma en el
entorno `release` y publica `v<VERSION>` con `prdrive-<VERSION>.apk`. La firma es
una y para siempre; cómo se crea y se guarda, en [`AGENTS.md`](AGENTS.md)
(«Versiones y releases»).

### Convenios

Comentarios, KDoc y textos para el usuario, en **español**; los comentarios
explican el *porqué* y citan el código de rclone cuando lo hay. En las pantallas
no se escribe ningún color: salen del sistema de diseño.

## Documentación

| | |
|---|---|
| [`AGENTS.md`](AGENTS.md) | La referencia técnica: arquitectura, reglas que no se cruzan, y las decisiones del llavero, las carpetas compartidas y la autoactualización, con sus porqués. Escrita para quien trabaje en el repositorio, personas o agentes. |
| [`PLAN.md`](PLAN.md) | Lo que queda por hacer, en el orden en que tiene sentido. |
| [`docs/superpowers/`](docs/superpowers) | Los diseños y planes de las funciones grandes. |

## Licencia

Apache 2.0, igual que prdrive. Ver [LICENSE](LICENSE).
