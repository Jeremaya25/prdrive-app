# prdrive para Android — lo que queda

El paso 1 está hecho: el volumen, el emparejamiento por QR, elegir parejas del
catálogo, un «Sincronizar» que cuenta qué pasa y el llavero (`[keychain]`). Cómo
está y por qué, en
[`AGENTS.md`](AGENTS.md). Aquí solo lo que falta, en el orden en que tiene
sentido hacerlo.

Valen las reglas de siempre: lo que se afirme de rclone se comprueba contra el
código de la versión que fija prdrive (`common/pins.py`), y lo que se traduzca
de prdrive entra con su caso en el generador de vectores.

## 1. Comprobarlo en un teléfono de verdad

Todo se ha probado en un emulador API 37 x86_64, con un servidor SFTP en el PC.
Falta:

- **Una pareja de verdad contra el remoto de casa.** Sincronizar, editar un
  fichero en el móvil, sincronizar y verlo en el PC. Y que el baseline
  sobreviva a una segunda pasada: un prefijo que se mueve sale como «pide
  --resync» cada vez.
- **La app de Archivos.** Un fichero guardado desde otra app se sincroniza en la
  pasada siguiente.
- **El escáner con la cámara**, delante de la ventana de Doctor (la prueba pega
  el texto). Con una clave RSA-3072 el QR es de los más densos: prdrive lo
  genera con corrección L porque con M no cabe en ninguna versión.
- **El permiso de red local** en un Android 17 de verdad, contra un NAS en
  `192.168.…`, y en uno anterior, que no lo pide.
- **arm64**, que es lo que llevan los teléfonos. En un Galaxy A35 (Android 16)
  se instala y abre el emparejamiento sin fallos; falta una pasada, que es
  cuando se carga el rclone de arm64.
- **El llavero con una app de KeePass de verdad** (KeePassDX, Keepass2Android).
  En el emulador se ha probado con el proveedor, los trabajos, la pantalla y el
  alta por el QR contra un servidor SFTP, pero no con una de esas apps al otro
  lado. Falta:
  - **Abrir con el botón y guardar.** «Abrir llavero» en KeePassDX, cambiar una
    entrada, guardar, y verlo en el KeePassXC del PC tras su siguiente pasada; y
    al revés, un cambio del PC que llega con «Abrir llavero».
  - **El modo real de escritura de KeePassDX.** El guardado atómico supone que
    escribe por el `ParcelFileDescriptor` del proveedor con `"w"`/`"wt"` o
    `"rw"`/`"rwt"`/`"wa"`; qué pide de verdad, y si cierra el descriptor al
    acabar, solo se sabe mirándolo. Y con el proceso de prdrive muerto mientras
    hay un temporal abierto, ese guardado se pierde sin aviso.
  - **Un conflicto avisado y combinado en el PC.** Dos guardados cruzados dejan
    una copia de conflicto, la notificación y la fila en ámbar; en el PC,
    «Abrir llavero» → «Combinar» la junta y la fila se apaga.
  - **`llave_interna` con la llave copiada.** Activar con la llave elegida en
    Archivos y abrir la base en la app de KeePass sin contraseña: la app no puede
    comprobarlo antes, así que si la llave no vale solo lo dice la app de KeePass.
  - **Las carpetas con punto en la app de Archivos.** «Abrir llavero» no depende
    de ello, pero elegir a mano la base o la llave sí: hay que ver si `.keychain/`
    se enseña.
  - **El tipo MIME de `ACTION_VIEW`.** Qué tipo aceptan KeePassDX y
    Keepass2Android para abrir la base desde el `content://` del proveedor; si no
    abre ninguna, la app lo dice y nombra cuáles valen.
- **La autoactualización con la clave de verdad.** En el emulador se ha probado
  con APK de depuración (bajar de GitHub, la suma, la firma que no cuadra,
  instalar preguntando y sin preguntar, y Play Protect parando el primer intento
  en segundo plano). Falta de una release a la siguiente en un teléfono: la
  primera que la trae se instala a mano, y la siguiente tendría que llegar sola.

## 2. Hallazgos que se arreglan en prdrive

La app refleja a prdrive, así que estos se arreglan allí. Después se regeneran
los vectores, y donde el teléfono se separa de prdrive a propósito, deja de
hacerlo.

- **`max-delete` mide dos cosas.** En bisync es un porcentaje
  (`cmd/bisync/cmd.go`, `applyContext`; `deltas.go`, `excessDeletes`) y en los
  espejos una cuenta de ficheros: el 25 de `MODES["bisync"]` y el 50 de los
  `*-mirror` no son comparables. Hay que decirlo donde se configura.
- **El nombre de sesión no es el de rclone** con un espacio de ancho completo o
  un carácter de control en el nombre. El `\s` de `CanonicalPath` es el de RE2
  (`[\t\n\f\r ]`), no el Unicode de Python, y el `Root()` de un remote `local`
  es `Standard.Encode(Standard.Decode(tramo))` (`backend/local/local.go`,
  `cleanRootPath` y `Root`). El teléfono sigue a rclone, vigilado por
  `rclone/go/oraculo`.
- **«Must run --resync» se queda sin explicación.** bisync lo escribe con
  mayúscula al abortar (`cmd/bisync/operations.go`), y la aguja de
  `KNOWN_ERRORS` es «must run --resync». El teléfono lo replica tal cual.
- **`problema_extra` deja pasar los flags de una letra**, y rclone sí los lee:
  un `-1` en `extra_flags` es un `--resync` en cada pasada, saltándose
  `FLAGS_RESERVADOS`. El teléfono traduce cada letra a su flag largo y le pasa
  `problemaFlag`.
- **`llavero.listado_local()` se cae con una hora imposible.** Una línea del
  listado con el año 0000 o el mes 13 hace que `calendar.timegm` lance
  `ValueError` (usa `datetime.date`), y la excepción no se recoge: se cae la
  función entera, no solo esa línea. rclone no escribe eso nunca, así que es
  latente. El teléfono no lee la línea (`Llavero.listadoLocal`) y los vectores no
  llevan el caso, porque en Python lanza.
- **`llavero.bases()` distingue mayúsculas solo en algunos sistemas.** `rglob`
  de `*.kdbx` no las distingue en Windows y sí en POSIX, y el filtro de rclone
  `+ *.kdbx` sí las distingue en todos: una `X.KDBX` es base en un PC con Windows
  y no entra en la pareja. El teléfono sigue a rclone (las distingue), y los
  vectores evitan el caso para no depender del sistema.
- **`update.parse_version` lanza con un `²`**, aunque dice que nunca lanza:
  `str.isdigit()` lo da por cifra y `int()` no lo lee (`ValueError`). Y
  `update.check()` se cae con un `AttributeError` si GitHub contesta algo que no
  es un objeto (`_parse_release` llama a `.get()` sin mirar), que no está entre
  lo que recoge. El teléfono corta el número en el `²` y lo cuenta como una
  respuesta rara (`Actualizacion.kt`); los vectores apuntan los dos casos.

## 3. Lo que el paso 1 dejó a medias

- **Heredar las opciones del `[remote]` del catálogo.** De
  `profile.align_with_catalog()` solo se porta la regla del nombre; falta
  `_opciones_del_catalogo()`, con lo que no se hereda (lo que ejecuta algo o es
  secreto).
- **Un simulacro desde la app** (`--dry-run`), que es lo que el instalador
  recomienda para probar los espejos. Con él, la carpeta de un espejo de subida
  se podría crear al instalar, como hace `make_local_dirs()`; hoy no se crea,
  para que «Sincronizar» no vacíe el remoto.
- **Flags que el escritorio acepta y el RPC no lleva.** Hoy la pareja falla con
  una explicación:
  - Los de un backend (`--sftp-…`) podrían ir en una cadena de conexión, pero
    cambian el nombre del Fs, y con él el de los listados de bisync.
  - Los que no son opciones (`--delete-after`, `--header`…) no tienen
    equivalente por el RPC.
- **El permiso de red local tras recrear la actividad.** Si la actividad se
  recrea con la pregunta en pantalla, la acción que esperaba se pierde y hay
  que volver a tocar.
- **Una carpeta que es un enlace se ve vacía** en la app de Archivos: el
  proveedor no deja pasar por un enlace en mitad de un id, porque podría
  volver a la raíz del volumen.
- **Lógica pura que vive en `app/`.** `Estado.filas` e `Instalador.aInicializar`
  irían mejor en el motor, con sus pruebas.
- **CI.** El motor se prueba en cada push (`engine.yml`), y el `.aar` y el APK
  se construyen en los PR que los tocan (`apk.yml`). Las pruebas del emulador
  (`connectedDebugAndroidTest`, `PuntaAPuntaTest` con su servidor) se pasan a
  mano.

## 4. Lo siguiente

- **Sincronizar sin abrir la app:** servicio en segundo plano y pasadas
  periódicas, y lo que diga el `[daemon]` del config (que el instalador ya
  escribe). Para empezar ya están la cancelación limpia de una pasada
  (`job/stop` y esperar a que acabe), el cerrojo que las pone en fila y
  WorkManager, que el llavero ya usa para sus pasadas (AGENTS.md, «El llavero»);
  falta lo mismo para las parejas. Con él,
  la autoactualización también puede mirar y bajar sin que se abra la app; hoy
  solo lo hace al abrirla, que es cuando la app sirve de algo.
- **Resolver conflictos:** hoy se detectan y se avisan (`Conflictos.kt`); falta
  la pantalla que elige una versión.
- **Editar el catálogo:** hoy solo se lee. Escribirlo es la ceremonia de
  `catalog.push()`, y renombrar un `pairs.toml` antiguo.
- **Lo que el escritorio apunta y el teléfono todavía no:** el parte de la flota
  (`publish_fleet_note`), el diario de pasadas (`historial.py`), la medida de si
  una pasada movió algo, `--force` desde «Reparación» y `watch`.
- **Una SD o un USB como volumen.** Pide el permiso de «acceso a todos los
  archivos», y ahí bisync no se puede fiar de las fechas de modificación.

## Fuera, salvo que cambie algo

VeraCrypt y BitLocker; y el vigilante de montaje, que no tiene disco que
vigilar.
