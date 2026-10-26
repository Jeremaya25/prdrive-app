# Una pareja en una carpeta compartida

Fecha: 2026-10-26. Estado: diseño aprobado, sin implementar. Issue
[#10](https://github.com/Jeremaya25/prdrive-app/issues/10).

## Qué y para qué

Una pareja con un vault de Obsidian se sincroniza bien, pero Obsidian en
Android no puede abrir el vault: el volumen vive en el almacenamiento privado de la
app (`filesDir/volumen/`) y ninguna otra app lo alcanza por ruta. Lo que se
quiere:

- **Una pareja elegida vive en una carpeta compartida** (p. ej.
  `/storage/emulated/0/Documents/vault`), y Obsidian la abre como vault («Abrir
  carpeta como vault»). Sin copias a mano.
- **Todo lo demás se queda privado**: `.prdrive/` (la clave, el `rclone.conf`,
  `state/`), el llavero y las demás parejas.
- **Para el escritorio no cambia nada**: el catálogo, el `sync_config.toml`, la
  pareja, sus filtros y sus baselines son los mismos. Dónde está la carpeta en
  el teléfono es un hecho del teléfono, como `filesDir`.
- **Se entra y se sale**: «Mover a una carpeta compartida» y «Volver al
  volumen», desde la fila de la pareja.

Éxito: con la pareja movida, una nota editada en Obsidian en el teléfono llega
al remoto con «Sincronizar», y una editada en el PC aparece en Obsidian tras
«Sincronizar»; ningún paso del movimiento, cortado donde sea, puede borrar nada
en el remoto; y sin el permiso, la pareja se para con su explicación en vez de
sincronizar.

## Lo comprobado

- **Obsidian para Android guarda los vaults de «almacenamiento del
  dispositivo» en una carpeta compartida**, y pide «acceso a todos los
  archivos» para usarla ([ayuda de Obsidian, «Android»](https://obsidian.md/help/android)).
  **No acepta una carpeta del Storage Access Framework**: lo dicen hilos del
  foro de Obsidian, no su documentación, y la petición sigue abierta
  ([foro](https://forum.obsidian.md/t/android-support-the-storage-access-framework-store-vault-in-google-drive-etc/23234)).
  El `DocumentsProvider` actual no le sirve: la carpeta tiene que estar en el
  almacenamiento compartido de verdad.
- **Para que prdrive escriba ahí lo que crea Obsidian hace falta
  `MANAGE_EXTERNAL_STORAGE`.** Con el almacenamiento con ámbito (Android 11+),
  una app solo alcanza por ruta los ficheros que no son multimedia si los ha
  creado ella, y rclone escribe por ruta, con el backend `local`. Un árbol del
  SAF no vale: el backend `local` no habla con un `DocumentsProvider`.
- **El nombre de sesión de bisync no depende de adónde apunte el upstream.** El
  motor lo calcula del texto de los extremos (`Bisync.expectedPrefix`, con
  `disp:vault`), y rclone del nombre y la raíz del Fs `combine`, que son `disp`
  y `vault` apunte adonde apunte su upstream. Cambiar la ruta del upstream de
  una pareja no toca sus listados ni su `--filters-file`.
- **Lo que no se sabe es si las fechas de modificación se conservan** en
  `/storage/emulated/0/` (FUSE desde Android 11). Son lo que hace fiable a
  bisync en el volumen privado, y en el almacenamiento compartido Android tiene
  mala historia: Syncthing trata las fechas de una forma propia solo en Android,
  porque el sistema no le dejaba ponerlas
  ([foro de Syncthing](https://forum.syncthing.net/t/syncthing-keeps-attempting-to-resync-files-that-have-not-changed/17359)).
  De ahí el paso 0.

## Enfoque

**La pareja se queda con su `local = "vault"`, y lo que cambia en el teléfono
es adónde apunta el upstream `vault` del remote `combine`.** Un fichero propio
del teléfono, `.prdrive/compartidas.json`, dice «la pareja `vault` vive en
`/storage/emulated/0/Documents/vault`», y `[disp]` lo recoge:

```
[disp]
type = combine
upstreams = "notas=/data/user/0/prdrive.app/files/volumen/notas" "vault=/storage/emulated/0/Documents/vault"
```

`combine` es eso mismo: un nombre por carpeta de primer nivel, cada uno con su
ruta absoluta. Lo demás sale solo: los mismos listados, los mismos filtros y el
mismo catálogo.

Descartado:

- **Una clave nueva en el TOML con la ruta absoluta** (la opción A del issue).
  Mete en el `sync_config.toml` una clave que prdrive no conoce, en un config
  que sale de un catálogo compartido por todos los dispositivos, y obliga a
  tocar `Toml.kt`, `comprobarSeguridad()` y los vectores. Lo que dice no es de
  la pareja: es del teléfono.
- **Un enlace simbólico** `volumen/vault → /storage/…` (la opción B). Es la
  misma idea escondida en el sistema de ficheros: choca con las reglas de
  enlaces de `VolumenProvider` y de `problemaContencion()`, y que una pareja
  vive fuera dejaría de leerse en un fichero.
- **Darle el vault a Obsidian por el SAF**: Obsidian no lo acepta.
- **Todo el volumen en el almacenamiento compartido**: `.prdrive/` tiene que
  seguir privado.

## 0. Antes de nada: las fechas (sí o no)

Un spike en emuladores con API 30, 33, 35 y 37, con el permiso dado
(`adb shell appops set prdrive.app MANAGE_EXTERNAL_STORAGE allow`):

1. rclone (el del `.aar`) copia un fichero a una carpeta de
   `/storage/emulated/0/Documents/` y le pone la fecha: si falla (`EPERM`), y
   si la lee igual justo después y pasado un rato.
2. La precisión que rclone mide ahí para el backend `local` (`Precision()`),
   contra la del volumen privado.
3. Una bisync de una pareja apuntada ahí: `--resync`, un cambio en cada lado y
   dos pasadas normales; la segunda no copia nada.
4. Con el permiso retirado (`appops … deny`): qué sigue viendo prdrive de la
   carpeta y de lo que creó otra app. Es lo que justifica la sección 2.

Si las fechas no se pueden poner o no se conservan, esto no sigue: haría falta
comparar por tamaño y suma (`--compare size,checksum`) solo en estas parejas,
que es otro diseño y otro coste en cada pasada. Lo que salga se apunta en
`PLAN.md` y en «Ojo con Android» de `AGENTS.md`.

## 1. El motor (`engine/`)

Nada de Android: la ruta absoluta llega de la app, como la raíz del volumen. Y
no refleja ningún fichero de prdrive: es una diferencia del teléfono, como el
`device_remote` obligatorio, y el fichero nuevo lo dice en su cabecera.

**`Compartidas.kt`** (nuevo):

- El fichero, `.prdrive/compartidas.json` (`Volumen.compartidas`), escrito de
  forma atómica (`Store`):

  ```json
  {"vault": {"ruta": "/storage/emulated/0/Documents/vault", "estado": "entrando"}}
  ```

  `estado` es `entrando` (la carpeta compartida ya es la de la pareja, pero
  faltan el `--resync` y borrar la copia privada) o `lista`. Uno que no se
  puede leer es un error que se dice, no un «no hay ninguna»: sin las
  entradas, las parejas apuntarían a sus carpetas privadas.
- `problema(config, pareja, ruta, raiz, otras)`: por qué una pareja no puede
  vivir en una carpeta compartida, o `null`.
  - Existe y es bisync: es lo que se edita en los dos lados.
  - No es la del llavero.
  - Su `local` es un solo tramo (`vault`, no `notas/vault` ni `.`): el upstream
    es la carpeta entera.
  - Ninguna otra pareja tiene su mismo primer tramo.
  - El config no tiene una pareja de la raíz entera (`local = "."`): esa vería
    desaparecer la carpeta privada `vault/` y lo tomaría por un borrado.
  - La ruta es absoluta y normal (sin `.`, `..`, tramos vacíos ni barra final),
    no es la raíz del volumen, ni cae dentro, ni la contiene.
  - Ni es, ni contiene, ni cae dentro de la de otra entrada.

  Lo que es de Android (que caiga bajo `/storage/emulated/<n>/`) lo mira la
  app.

**`Model.kt`**:

- `Pareja.carpetaExterna: String?` y `Pareja.problemaCompartida: String?`,
  `null` por defecto. `carpetaEn(volumen)` devuelve la externa si la hay: así
  `Conflictos`, `Estado`, `Pasada` y `Llavero` la ven sin más cambios.
- `Config.conCompartidas(entradas, raiz)` pone las dos a cada pareja con
  entrada. Una entrada que no vale (`problema()`) no redirige nada y deja su
  motivo en `problemaCompartida`; la de una pareja que ya no está en el config
  se ignora.
- `upstreamsDeCombine()`: el upstream de una pareja con `carpetaExterna` es esa
  ruta. La comprobación de dos upstreams iguales sigue.

**`Volumen.kt`**:

- `leerConfig()` devuelve el config con las compartidas puestas.
- `escribirRemoto()` y `actualizarCombine()` se las ponen ellos al config que
  reciben, antes de escribir `[disp]`. Quien reescribe el `rclone.conf` sin
  pasar por `leerConfig()` (activar o quitar el llavero, que parten del TOML) no
  puede dejar caer la redirección: sin ella, la pareja apuntaría a la carpeta
  privada.

## 2. La pasada: el acceso, antes de llamar a rclone

**Sin el permiso, sincronizar podría borrar en el remoto.** Si se retira el
acceso a todos los archivos, Android le esconde a prdrive los ficheros que no
creó él: las notas nuevas de Obsidian (a confirmar en el paso 0). bisync las
vería borradas y, por debajo del 25 % de `max-delete`, las borraría del remoto.
La regla que ya tiene `preflight()` (hay baseline y no hay carpeta: abortar)
no lo para, porque la carpeta sigue ahí.

Así que, en `Pasada`:

- Una pareja con `problemaCompartida` aborta (`ABORTO`) con ese motivo, sin
  llamar a rclone.
- `Contexto.accesoCompartido: ((Pareja) -> String?)?`: la app dice si puede
  leer la carpeta de verdad (el permiso, el almacenamiento montado). Una pareja
  con `carpetaExterna` aborta con lo que diga, **y sin la función aborta
  también**: falla cerrado, como `Contexto.cifrado = false`, y quien lance
  pasadas sin saber de esto no se salta el freno.
- Lo demás de `preflight()` no cambia.

La app pasa la función en los cuatro sitios que crean un `Contexto`: dos en
`Modelo`, `Instalador.inicializar` y `LlaveroApp` (aunque el llavero nunca
sea compartido).

## 3. Mover a una carpeta compartida, y volver

Lo hace la app (`app/compartidas/Compartir.kt`), con la pareja libre y como un
trabajo (`Actualizador.empezarTrabajo()`): no se instala una versión a medio
mover. Las pasadas que lleva son las de siempre (`Pasada.pasarOFallar`), así
que se apuntan y se ven en la fila.

**Mover** («Mover a una carpeta compartida…»):

1. Lo que se mira antes de tocar nada: Android 11 o posterior, el permiso dado,
   `Compartidas.problema()` sin motivo, y una carpeta elegida que cae bajo
   `/storage/emulated/<n>/` y está **vacía o sin crear**. Decidido: nada que
   fusionar, nada que perder.
2. Las fechas: rclone copia un fichero de prueba a la carpeta, lo mira y lo
   borra. Si no conserva la fecha, no se sigue, y se dice por qué.
3. Una pasada normal de la pareja, que tiene que acabar bien: así el remoto
   tiene todo lo de la copia privada. Si no, se para con lo que haya dicho la
   pasada.
4. **Se apartan los listados** de la pareja (`.lst` → `.lst-err`, como
   `markFailed` de rclone y `Adaptadores.apartarListados`), **antes** de
   redirigir nada. Si el proceso muere a partir de aquí, la pasada siguiente
   pide `--resync`, que no borra nada. Sin esto, una pasada normal contra una
   carpeta a medio llenar vería borrados y los llevaría al remoto.
5. La carpeta, si no existe; la entrada, con `estado = entrando`;
   `actualizarCombine()`, y `Prdrive.recargarRclone()`, que vacía la `fscache`
   de rclone.
6. El `--resync`, aprobado: lo ha pedido la persona. La carpeta está vacía, así
   que solo baja lo del remoto.
7. Si acaba bien, se borra la copia privada `volumen/vault/` (sin seguir
   enlaces, como borra el proveedor) y la entrada pasa a `lista`: lo que tenía
   está en el remoto (3) y en la carpeta (6). Si falla, todo se queda como
   está, la fila dice «Falta terminar de mover» y su botón repite 6 y 7.

**Volver al volumen**:

1. Si hay permiso, una pasada normal que acabe bien, para que lo último de
   Obsidian llegue al remoto. Sin permiso esa pasada no se puede hacer, pero se
   puede volver igual, y se dice antes: lo que no se subió se queda en la
   carpeta compartida, que no se toca.
2. La carpeta privada tiene que estar vacía o sin crear: una con algo no se
   pisa.
3. Se apartan los listados, se quita la entrada, `actualizarCombine()` y se
   recarga rclone.
4. El `--resync`, que baja lo del remoto a la carpeta privada.
5. **La carpeta compartida se queda como está**: es de la persona y de
   Obsidian. Se dice «bórrala tú cuando ya no la quieras», como con la llave
   del llavero.

## 4. La app

- **El permiso**: `MANAGE_EXTERNAL_STORAGE` en el manifiesto, y se pide solo al
  mover (`Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`). La app se
  distribuye por GitHub: la política de permisos de Play no aplica. **Solo desde
  Android 11** (API 30, `Environment.isExternalStorageManager()`): antes sería
  otro permiso y otro modelo de almacenamiento, y la acción no se ofrece.
- **La exposición, aceptada al mover**: «Cualquier app con acceso a todos los
  archivos podrá leer y cambiar esta carpeta, y se verá al conectar el teléfono
  a un ordenador. La clave y el resto del volumen siguen privados.»
- **Elegir la carpeta**: el selector del sistema (`ACTION_OPEN_DOCUMENT_TREE`,
  que empieza en Documentos y deja crear la carpeta). Solo vale un árbol de
  `com.android.externalstorage.documents` con id `primary:<ruta>`, que se
  traduce a `Environment.getExternalStorageDirectory()/<ruta>`. Otro (una SD,
  Google Drive) se rechaza diciendo por qué. El permiso del árbol no se guarda:
  con `MANAGE_EXTERNAL_STORAGE` no hace falta.
- **`Prdrive.accesoCompartido(contexto, carpeta)`** es la función de la
  sección 2, con el texto de la app. Sin permiso: «Falta el permiso de acceso a
  todos los archivos: sin él, Android esconde las notas que no ha creado
  prdrive, y sincronizar las borraría del remoto.» Y el almacenamiento sin
  montar.
- **La fila de la pareja** dice dónde vive («En Documentos/vault») y ofrece
  «Mover a una carpeta compartida…» o «Volver al volumen». Con
  `estado = entrando`, «Terminar de mover» en lugar del botón de resync. Sin
  permiso, el botón para darlo, como el de la red local.
- **Ningún color escrito**, como en todas las pantallas.

## 5. El proveedor

`VolumenProvider` no publica la carpeta compartida: ya se ve en Archivos. Con
una entrada en `compartidas.json`, en cualquier estado, no escribe nada bajo su
`<primer tramo>/` de la raíz del volumen, ni crea nada con ese nombre. Mientras
se mueve, lo que se escribiera en la copia privada no llegaría a ningún sitio,
y después sería una carpeta fantasma que no se sincroniza. Leer, sí.

## 6. Riesgos, y cómo quedan

| Riesgo | Cómo queda |
|---|---|
| Las fechas de modificación | El paso 0 decide si se sigue, y el paso 2 de «Mover» lo comprueba en cada teléfono. |
| El permiso retirado | La pasada aborta antes de llamar a rclone (sección 2). |
| El almacenamiento sin montar | Igual, y la regla de siempre: con baseline y sin carpeta, abortar. |
| Un corte a medio mover | Los listados se apartan antes de redirigir: lo siguiente es un `--resync`, que no borra. |
| Obsidian escribe durante una pasada | Las pasadas de las parejas las lanza la persona con «Sincronizar», con Obsidian en segundo plano. Un conflicto deja las dos copias (`conflict-resolve = newer` y los sufijos) y la fila lo avisa (`Conflictos` mira `carpetaEn()`). Sin `versions`, la copia es `nota.md.conflicto-remoto1` y Obsidian no la indexa (se resuelve desde Archivos); con `versions` es `nota.conflicto-remoto1.md` y sale como una nota más. |
| El ruido de `.obsidian/` | Es cosa del catálogo, y la misma para el PC: se documenta excluir `.obsidian/workspace*.json`. Sin código. |
| Un vault pequeño y el 25 % de `max-delete` | Lo de siempre: aborta, no pierde nada, y está «Forzar». Se documenta. |
| Desinstalar | El vault sobrevive y `.prdrive/` no. Al volver a emparejar la carpeta ya no está vacía: se renombra la vieja, se mueve la pareja a una nueva, y lo que no se subió se pasa a mano. Se documenta. |
| `.prversions/` dentro del vault | Empieza por punto: Obsidian no lo enseña. |

## 7. Pruebas

**El motor** (`:engine:test`, sin dispositivo):

- `Compartidas`: leer y escribir; un fichero ausente y uno roto; cada motivo
  de `problema()`.
- `conCompartidas()` y `upstreamsDeCombine()`: con una entrada, el upstream de
  la pareja es el de su ruta y los demás no cambian; con una que no vale, no
  cambia ninguno y la pareja lleva su motivo. Comparado con el mismo config sin
  entradas, no con textos escritos a mano.
- El nombre de sesión y el `--filters-file` de una pareja son los mismos con
  entrada y sin ella.
- `preflight()`: aborta con `problemaCompartida`, con lo que diga
  `accesoCompartido` y sin la función; con una pareja sin carpeta externa, la
  función no se llama.
- `Volumen.actualizarCombine()`, con un config que no trae las compartidas
  puestas, conserva la redirección.

Nada de esto refleja a prdrive, así que no hay vectores que generar: el
formato de `upstream()` y el nombre de sesión ya los cubren los que hay.

**En el emulador** (`androidTest`, con el permiso dado por `appops` desde el
test):

- La pasada contra rclone de verdad: mover una pareja a
  `/sdcard/Documents/<temporal>`, un cambio en cada lado y una pasada normal;
  el `basePath` de los listados es el mismo antes y después; y volver.
- Un movimiento cortado tras el paso 5: la pasada siguiente queda saltada
  pidiendo `--resync`, y «Terminar de mover» lo acaba.
- Sin el permiso (`appops … deny`): aborta sin llamar a rclone.
- El proveedor: no escribe ni crea nada bajo el tramo de una pareja compartida.
- `PuntaAPuntaTest`: una tercera prueba que mueve la pareja tras emparejar y
  sincroniza contra el servidor SFTP.

**A mano, con Obsidian**: abrir la carpeta como vault, editar y sincronizar;
editar en el PC, sincronizar y que Obsidian lo vea; un conflicto. La versión de
Obsidian con que se pruebe se apunta en `PLAN.md`.

## Documentación

- `AGENTS.md`: una sección «Las carpetas compartidas» con las decisiones de
  aquí; «El volumen», en «Arquitectura», deja de decir que todo vive en
  `filesDir`; lo que salga del paso 0, en «Ojo con Android»; `Compartidas.kt` y
  `app/compartidas/` en la distribución.
- `PLAN.md`: la entrada nueva, y la de «Una SD o un USB como volumen», que
  comparte el permiso y el riesgo de las fechas.
- Los KDoc de `Model.kt` (`Pareja` y «no hay `local_abs`») y de `Volumen.kt`
  que dan por hecho que toda pareja cuelga de la raíz del volumen.

## Fuera de esta versión

- Adoptar una carpeta que ya tiene ficheros y fusionarla.
- Una SD, un USB o cualquier volumen que no sea `primary`.
- Android 8 a 10.
- Comparar por tamaño y suma si las fechas no valen: lo decide el paso 0.
- Elegir la carpeta compartida al emparejar, en vez de después.
- Los espejos, y las parejas anidadas (`notas/vault`) o de la raíz.
- Plantillas de filtros para vaults en la app.

## Lo que se da por supuesto

El issue dejaba preguntas sobre el uso. El diseño no depende de ellas, y se
supone:

- Obsidian usa el vault en «almacenamiento del dispositivo» y tiene acceso a
  todos los archivos.
- El vault es pequeño (cientos de ficheros, decenas de MB): volver a bajarlo
  entero al mover es aceptable.
- El PC puede editar el mismo vault con prdrive de escritorio: no cambia nada,
  es la misma pareja.
- Ningún otro sincronizador (Obsidian Sync, Git) escribe en la carpeta.
