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
- **El permiso de red local** en un Android 17 de verdad, contra un NAS en
  `192.168.…`, y en uno anterior, que no lo pide.
- **El llavero con una app de KeePass de verdad.** Probado el 23/10/2026 en el
  emulador API 37 con KeePassDX 4.5.6, contra el servidor de
  `herramientas/punta_a_punta.py` y una base de verdad hecha con `keepassxc-cli`
  2.7.12 (el «PC» se simuló con `keepassxc-cli add`/`merge` sobre el remoto).
  Lo que salió:
  - **Abrir y guardar: va.** «Abrir llavero» abre la base en KeePassDX, se
    desbloquea y se lee por el proveedor (`"r"`); un guardado escribe con
    **`"wt"`**, el guardado atómico lo pone, se encola el trabajo y la base está
    en el remoto en ~35 s, con la anterior en `.prversions/`. Un cambio del PC
    llega con «Sincronizar».
  - **Un conflicto: se avisa bien.** Dos guardados cruzados dejan
    `personal.conflicto-remoto1.kdbx` en los dos lados (la base viva es la del
    móvil y la copia la del PC), la notificación y la fila que lo explica.
  - **Combinar en el PC dejaba el llavero del teléfono atascado (arreglado).**
    Tras combinar y apartar la copia en el remoto, la pasada del teléfono paraba
    en el freno de borrados (1 de 3) y no se repetía sin él: la línea «File was
    deleted» del log llevaba colores ANSI, y `soloCopiasBorradas` no la
    reconocía. bisync los pone con `TerminalColorMode = AUTO` cuando el log no va
    a un fichero (`cmd/bisync/operations.go`, `!log.Redirected()`), que en la
    biblioteca es siempre. Ahora `Rclone.iniciar` pone `NEVER`, lo vigila
    `PasadaEnAndroidTest.combinar_en_el_pc_quita_la_copia_de_conflicto_sin_que_el_freno_pare_el_llavero`
    (falla sin el arreglo), y con KeePassDX la copia desaparece en la pasada
    siguiente y la base queda como la combinada. Las demás agujas del llavero
    (el remoto vacío, `Skipped delete` de la simulación) son de líneas sin
    colores, pero ya no dependen de ello.
  - **KeePassDX ve el cambio en disco, pero se podía pisar (arreglado).** Con
    la base abierta y una versión más nueva bajada por «Sincronizar», al volver
    avisa («modified outside the app») y ofrece combinar, guardar encima o
    recargar. Si se cerraba el aviso con «OK» y se guardaba, pisaba el cambio
    del otro dispositivo: quedaba solo en `.prversions/`, sin copia de conflicto
    ni aviso. Visto en su código (4.5.6), detecta el cambio sin leer la base y
    guardar no la lee. Ahora el proveedor sabe qué versión tiene cada app, y un
    guardado sobre una vieja va a una copia de conflicto avisada (AGENTS.md, «El
    llavero»). Falta verlo con KeePassDX en un teléfono: que `callingPackage` es
    el de KeePassDX al leer y al guardar, y que tras «OK» y guardar sale la copia
    y la notificación.
  - **Abrir desde los recientes de KeePassDX fallaba (arreglado).** El permiso
    de `ACTION_VIEW` no era persistible: KeePassDX lo intentaba tomar («No
    persistable permission grants») y Android le negaba el proveedor. Con
    `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` se lo queda (`persisted=0x3`), y tras
    cerrarlo del todo vuelve a abrir y leer la base desde sus recientes.
  - **«Abrir llavero» metía KeePassDX en la tarea de prdrive (arreglado).** Con
    `FLAG_ACTIVITY_NEW_TASK` va en la suya, y volver a prdrive enseña prdrive.
  - **El tipo MIME (arreglado).** Con `application/octet-stream` salía el
    selector «Abrir con» mezclando KeePassDX con Google Wallet y Google. Ahora
    va primero `application/x-kdbx`, que declaran las apps de KeePass, y abre
    KeePassDX sin selector; el genérico queda de reserva, por un selector y sin
    permiso persistible (una revisión de seguridad: el persistible es de
    escritura, dura y va en un `Intent` implícito, así que solo con el tipo de
    KeePass, y se retira al quitar el llavero). Falta ver qué declara
    Keepass2Android: si solo el genérico, se abre por el selector cada vez y no
    puede volver desde sus recientes.
  - **Las carpetas con punto.** En el selector de Archivos, `.keychain` no se ve
    hasta activar «Show hidden files»; entonces se ve con `.prversions/`,
    `LEEME.txt` y la base.
  - **Con `llave_interna`, KeePassDX no recibía la llave (arreglado en 0.3.1).**
    Visto al usarlo: «Abrir llavero» le daba solo la base, el «Archivo de
    clave» salía vacío y la llave estaba en `.keychain/`, oculta en el selector.
    Ahora va en el `Intent` como la lee KeePassDX, y solo a él (AGENTS.md, «El
    llavero»). Falta verlo en un teléfono. Y KeePassDX solo guarda el permiso de
    la base (`FileDatabaseInfo`), no el de una llave que le llega así: abrir
    desde sus recientes, más tarde, puede volver a pedirla.
  - **Falta todavía**, porque el emulador no lo da: `llave_interna` con la llave
    copiada; Keepass2Android; el congelador de Android 14+ con una pasada que
    sigue tras parar WorkManager su trabajo; y todo en un teléfono arm64.
  - **Para repetirlo:** el servidor de `punta_a_punta.py` va ahora con
    `--dir-cache-time 0s`, así que un cambio hecho en el disco del remoto por
    detrás (el «PC», con `keepassxc-cli`) se ve en seguida; con los 5 min de
    serie de `rclone serve`, no. La base de la prueba es sintética y no se
    puede abrir: para KeePassDX hay que poner en `keychain/` una de verdad.

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
