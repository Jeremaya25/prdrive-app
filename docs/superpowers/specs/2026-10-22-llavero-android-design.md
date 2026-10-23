# El llavero en el teléfono

Fecha: 2026-10-22. Estado: implementado (plan `2026-10-23-llavero-android.md`).
Revisado el 2026-10-23: leer la base ya no lanza una pasada (sección 3).

## Qué y para qué

El llavero de prdrive (`[keychain]`) es una base KeePass (`.kdbx`) que viaja
en `.keychain/` y se sincroniza sola contra `keychain/`, junto al catálogo, en
el remoto. En el escritorio la abre KeePassXC, que viaja dentro del disco
(`docs/agents/reference/llavero.md` de prdrive).

Hoy el motor de la app construye la pareja `keychain`, pero la pasada la salta
(«El llavero no se sincroniza en el teléfono todavía», `Pasada.kt`). Lo que se
quiere:

- **Leer y guardar desde el teléfono.** Las contraseñas y las passkeys se usan
  con una app de KeePass de Android (KeePassDX, Keepass2Android), y lo que se
  guarda en el móvil llega al remoto como lo que se guarda en el PC.
- **La subida es sola, al guardar.** No hace falta abrir prdrive.
- **La bajada, solo al abrir**: con «Abrir llavero», que trae lo último antes
  de abrir la base. Ni al leerla la app de KeePass ni con pasadas periódicas.
- **Los conflictos se avisan y se combinan fuera**: en el PC («Abrir llavero»
  ofrece «Combinar») o a mano en la app de KeePass.
- **El modo «sin contraseña»** (`llave_interna = true`) se admite: Android
  cuenta como cifrado.

El teléfono **se une** a un llavero que ya tiene el remoto; no lo crea, porque
aquí el catálogo solo se lee.

Éxito: con el llavero activo en el PC y en el teléfono, un cambio guardado en
KeePassDX aparece en el KeePassXC del PC tras su siguiente pasada, y al revés
tras «Abrir llavero» en el teléfono; dos guardados cruzados dejan una copia de
conflicto avisada y no se pierde nada.

## Enfoque

El teléfono es **un dispositivo más del llavero**: la misma pareja bisync, los
mismos filtros, flags y baselines que el escritorio, con lo que hace
`sync.run_pair()` con el llavero traducido al motor. Las pasadas que no lanza
la persona las programa WorkManager desde el `DocumentsProvider`, que es por
donde la app de KeePass lee y escribe la base.

Descartado: subir y bajar con copias sueltas (`copyto` y sumas). Es más simple
en el teléfono, pero no deja baseline, el escritorio no vería al teléfono como
un lado de la pareja y los conflictos no saldrían con el nombre que «Combinar»
espera.

## 1. El motor (`engine/`)

Lo nuevo refleja a prdrive función por función, y sus valores esperados salen
de prdrive (`herramientas/vectores.py`). Nada de Android.

**`Llavero.kt`**, espejo de `common/llavero.py`, solo la parte pura:

- `LEEME`, `LEEME_TEXTO` y `LEEME_HORA`; `asegurarLeeme()` y `preparar()`
  (el `LEEME.txt` con su texto y su hora fijos, y que toda base esté entera,
  mirada otra vez pasado `ESPERA_ENTERA`).
- `bases()` y `rotas()`.
- `listadoLocal()`, `pendiente()` y `sinListar()`, con `TOLERANCIA_HORA`.
- `remotoVacio()`, `soloCopiasBorradas()` y `FRENO_SIN_COPIAS`.
- `prepararCarpeta()` (`.keychain/` y su `LEEME.txt`; no hay nada que
  ocultar).
- `decidirAlta()`, solo la rama «Traer el del remoto».

Fuera, por ser del escritorio: los procesos, el vigilante, KeePassXC, los
lanzadores, «Combinar» y expulsar.

**`Kdbx.kt`**, espejo de `common/kdbx.py`: `mirar()` y `comprobar()` (firmas,
cabecera TLV hasta `EndOfHeader`, y en KDBX 4 el SHA-256 de la cabecera y la
cadena de bloques HMAC hasta un bloque vacío justo al final; en KDBX 3, el
múltiplo de 16 con AES o Twofish). Sin clave. Un formato desconocido es
`null`, no roto.

**`Pasada.kt`**, lo que `sync.run_pair()` hace con el llavero:

- El llavero ya no se salta.
- `LLAVERO_ROTO` (2): una base que sigue rota tras la espera para la pasada,
  sin llamar a rclone, y se apunta.
- `LLAVERO_SIN_CIFRAR` (3) no se da nunca: la pasada recibe `cifrado = true`
  de la app (el motor no sabe de Android). **Diferencia deliberada con
  prdrive**, donde se pregunta a VeraCrypt y BitLocker: aquí el volumen vive en
  el almacenamiento privado de la app, que Android cifra con el bloqueo de
  pantalla.
- Se resincroniza solo, sin aprobación, con o sin baseline nuevo
  (`_bisync_preflight()`), y antes crea la carpeta remota
  (`crear_carpeta_remota()`). La interfaz `Rclone` de la pasada gana la
  llamada que hace falta (`operations/mkdir`).
- `repetirSinFreno()`, una vez por pasada y nunca tras un `--resync`:
  - borrados que son todos copias de conflicto: otra vez con
    `max-delete = FRENO_SIN_COPIAS`;
  - «all files were changed»: antes, la simulación `--dry-run --force`
    (`lo_que_borraria_forzando()`), y solo si no borraría nada, otra vez con
    `--force`;
  - remoto vacío (`Empty current Path2 listing`): `mkdir` y otra vez como
    `--resync`.
  
  El log de la pasada parada se guarda y una línea lo nombra, como en prdrive.
- `reapuntarLlavero()` tras una pasada buena: si una base no está en el
  listado de path1 (el fallo de `modifyListing()` de rclone), otra pasada en
  seguida.
- El texto de los fallos es el de `frenos.explicacion_llavero()`; al llavero
  no se le fuerza (`Orden.sePuedeForzar` ya lo dice).

**Vectores**: casos nuevos en `vectores.py` (los bytes y la hora del `LEEME`,
bases KDBX enteras y cortadas por cada sitio, logs de rclone con cada freno, la
orden de cada repetición). Las bases se construyen en el generador como las
construye `tests/test_kdbx.py` de prdrive (KDBX 4 y 3 sintéticas), y la
respuesta la da `kdbx.mirar()` de verdad.
`MotorEnAndroidTest` los vuelve a pasar en el dispositivo, por las expresiones
regulares de ICU.

## 2. Activarlo y quitarlo

**Dónde se ofrece:**

- Al emparejar, en `PasoParejas`, si el catálogo trae `[keychain]`: una
  casilla «El llavero», desmarcada. Es el paso opcional «Llavero» del
  instalador de prdrive.
- En la principal, la fila del llavero (sección 5). Sin llavero activo,
  «Traer el llavero» relee el catálogo y, si tiene `[keychain]`, lo activa.

**Activar** (en `Instalador`, con la decisión en el motor):

1. El `[keychain]` del remoto, tal cual, al `sync_config.toml` del teléfono,
   comprobado antes con `parseConfig`.
2. `prepararCarpeta()`.
3. El `upstreams` del `combine` otra vez (`actualizarCombine`).
4. La primera pasada del llavero, que se resincroniza sola y trae la base. Si
   falla, el llavero queda activo y se reintenta (sección 3).

La confirmación avisa, como prdrive, de que una pareja bisync de la raíz
entera gana `- /.keychain/**` y pedirá `--resync`.

**Con `llave_interna = true`**: antes de activar, `ACTION_OPEN_DOCUMENT` para
elegir la llave, que se **copia** a `.keychain/llave.keyx` (`poner_llave()`).
Sin llave no se activa. No se puede comprobar que abre la base (no hay
`keepassxc-cli ls` en Android): si no vale, lo dice la app de KeePass al
abrir. Límite apuntado.

**Con `fichero_llave = true`** (contraseña y fichero llave): el teléfono no
apunta nada. La app de KeePass pide el fichero llave en su diálogo y lo
recuerda.

**Quitarlo** («Quitar el llavero de este teléfono», `plan_desactivar()`): fuera
el `[keychain]` del `sync_config.toml` y los trabajos del llavero de
WorkManager. `.keychain/` se queda; el remoto y los demás dispositivos, igual.
Avisa si una pareja de la raíz pasaría a llevar `.keychain/`, llave incluida.

## 3. «Abrir llavero» y las pasadas en segundo plano

**«Abrir llavero»**, el botón de la fila, en el orden de
`llavero_editor.abrir()`:

1. Traer lo último si la última pasada buena es de hace más de
   `DESDE_LA_ULTIMA` (2 min), con el progreso de siempre. Si falla, se abre
   igual y se dice.
2. Sin base tras la pasada: se dice por qué y se para.
3. Con copias de conflicto de la base: un aviso de que no se pierde nada y de
   cómo combinarlas (en el PC, o en la app de KeePass). Se cierra y se abre.
4. `ACTION_VIEW` con el `content://` de la base en `VolumenProvider` y permiso
   de lectura y escritura. Si ninguna app lo abre, se dice cuáles valen.

**Los trabajos** (WorkManager, `androidx.work`, dependencia nueva de `app/`):
un `CoroutineWorker`, `PasadaLlavero`, que pasa la pareja del llavero con la
`Pasada` del motor. Restricción de red `CONNECTED`: en el escritorio, una red
de pago no retiene al llavero (`pl.retiene_al_llavero()`). Se encola con un
nombre único (`llavero`), así que nunca hay dos:

- **Al guardar** (el cambio atómico de la sección 4): `REPLACE` con un retraso
  de `calma` (20 s). Cada guardado nuevo lo aplaza, como `Vigilancia`.
- **Al arrancar la app y al acabar una pasada**, si `pendiente()` dice que hay
  algo sin subir: un guardado hecho durante una pasada, o uno cuyo trabajo se
  perdió.

**Leer la base no lanza ninguna pasada.** La primera versión de este diseño
encolaba una al leer la base por el proveedor, para que la próxima vez estuviera
al día, y la revisión final la quitó: esa pasada puede traer una versión más
nueva del remoto mientras la app de KeePass tiene la vieja en memoria, y si la
app no ve el cambio en disco, su guardado siguiente sube la vieja como un cambio
normal. El del otro dispositivo queda entonces solo en `.prversions/`, sin copia
de conflicto ni aviso. Sin esa pasada, el mismo caso sale como una copia de
conflicto avisada, y no se pierde nada. Lo último del remoto lo trae «Abrir
llavero», antes de abrir; abrir la base desde los recientes de la app de KeePass
enseña la de la última pasada.

**Una pasada a la vez en todo el proceso**: el worker va con el cerrojo de las
pasadas (`unaPasada`, `rclone/Adaptadores.kt`) y avisa a
`Actualizador.empezarTrabajo()`/`acabarTrabajo()`, para que la
autoactualización no se instale con una pasada en marcha. El rclone se arranca
como en la app (`Prdrive.kt`), con el `LogLevel` global.

**Reintentos**: una pasada que falla devuelve `Result.retry()`, con espera
exponencial desde 2 min (`separacion`). WorkManager no deja topar en 30 min
(`TOPE_REINTENTO`); su tope es de 5 h. Diferencia aceptada y escrita.

No se reintentan: una base rota (`LLAVERO_ROTO`) y un freno que no se ha podido
repetir. Esperan al siguiente guardado o a «Abrir llavero».

**La red local** (API 37): el worker no puede pedir el permiso. Sin él la
pasada falla con la explicación que ya da la app, y la fila lo enseña.

## 4. El proveedor: guardado atómico

En el escritorio prdrive obliga a KeePassXC a guardar de forma atómica
(`UseAtomicSaves`, H-11). Aquí la app de KeePass escribe a través de
`VolumenProvider`, y un guardado cortado dejaría la base rota. Así que, **solo
para los `.kdbx` que cuelgan directamente de `.keychain/`** (la base y las
copias de conflicto), el proveedor hace el guardado atómico. El resto del
volumen no cambia.

- **Leer** (`"r"`): el fichero tal cual, sin encolar nada (sección 3).
- **Escribir** (cualquier modo con `w`): el cliente recibe un temporal en
  `.prdrive/tmp/` (el mismo sistema de ficheros, y fuera de `.keychain/`, donde
  ninguna pasada lo ve), nunca la base.
  - `"w"` y `"wt"`: vacío. El `"w"` de Android no trunca, y un guardado más
    corto dejaría basura al final de la base.
  - `"rw"`, `"rwt"` y `"wa"`: con una copia de la base.
- **Al cerrar** (`ParcelFileDescriptor.open(…, handler, OnCloseListener)`, con
  un `HandlerThread` del proveedor):
  - con error o con el cliente muerto (el `IOException` del listener): fuera el
    temporal, la base no se toca;
  - limpio: `fsync` y `Kdbx.comprobar()`. Entera o de formato desconocido →
    `Files.move(…, ATOMIC_MOVE)` encima de la base y se encola la pasada de
    guardado. Rota → fuera, la base no se toca, y se apunta en `state/` para el
    aviso (sección 5).
- Dos escritores a la vez: cada uno con su temporal; gana el último que
  cierra.
- Un cambio que cae en mitad de una pasada es como un guardado atómico de
  KeePassXC durante una: `pendiente()`, al acabar, encola otra.
- Los temporales huérfanos (el proceso murió con uno abierto) se borran al
  arrancar el proveedor si tienen más de un día.
- **`llave.keyx`** se ve, porque la app de KeePass la necesita para abrir la
  base, pero es de solo lectura: sin `FLAG_SUPPORTS_WRITE`, `DELETE` ni
  `RENAME`, y `openDocument` en modo escritura se niega.

Por comprobar en un teléfono: qué modo usa de verdad KeePassDX al guardar, y si
la app de Archivos enseña las carpetas que empiezan por punto («Abrir llavero»
no depende de ello; elegir a mano la base o la llave, sí).

## 5. Avisos

**La fila del llavero en la principal** (`Estado.kt`, leída de `state/`, como
`llavero_editor.linea()`):

- Sin `[keychain]`, no hay fila, salvo «Traer el llavero» cuando el catálogo lo
  tiene.
- En ámbar, con su explicación: la última pasada falló, una base rota, un
  guardado que llegó cortado, copias de conflicto.
- Si no: «con cambios que aún no han subido» (`pendiente()`) o «al día» con la
  hora de la última pasada.
- Botones: «Abrir llavero» y, en un menú, «Quitar el llavero de este teléfono».

El llavero no sale entre las parejas (`Config.delUsuario`), pero «Sincronizar»
lo pasa, el último, como `sync.py` sin nombres.

**Notificaciones**, solo para lo que pide hacer algo fuera de prdrive:

- Una copia de conflicto nueva: una por copia y una sola vez, como
  `Agente._copias_del_llavero()`. Dice que no se pierde nada y cómo
  combinarla. Cuando no queda ninguna, se olvida lo avisado. Lo avisado se
  apunta en `noBackupFilesDir` (es del teléfono, no del volumen).
- Un guardado que llegó cortado.

`POST_NOTIFICATIONS` se pide al activar el llavero; sin él, queda la fila.

## 6. Pruebas

- **Motor** (`:engine:test`): los vectores de `Llavero`, `Kdbx` y las órdenes de
  cada repetición; con la `Rclone` de mentira de las pruebas de `Pasada`, la
  resincronización sola con su `mkdir`, cada freno repetido una sola vez, la
  simulación que se niega a forzar si borraría algo, y `reapuntarLlavero`.
- **`androidTest`**:
  - `ProveedorLlaveroTest`: escribir con `ContentResolver` en `"wt"` y en
    `"rw"` una base entera la cambia y encola el trabajo (`work-testing`, solo
    de test); una cortada o un cierre con error la dejan como estaba;
    `llave.keyx` no se deja escribir.
  - El spike contra el rclone de verdad, con un remoto `alias`: la primera
    pasada trae la base y deja el `LEEME` con su hora, y un remoto vaciado se
    recupera.
  - `MotorEnAndroidTest`, con los vectores nuevos.
  - `PuntaAPuntaTest`: `punta_a_punta.py` monta un catálogo con `[keychain]` y
    una base; la prueba marca «El llavero» al emparejar y comprueba que la base
    llega.
- **A mano, en un teléfono** (apuntado en PLAN.md): KeePassDX abriendo con el
  botón, guardar y verlo en el PC; un conflicto avisado y combinado en el PC;
  `llave_interna` con la llave copiada; el modo real de escritura de KeePassDX;
  las carpetas con punto en Archivos.

## Documentación

- AGENTS.md: una sección «El llavero», con las diferencias deliberadas con
  prdrive (Android cuenta como cifrado, el tope de los reintentos, nada de
  «Combinar» ni de crear un llavero), y `Llavero.kt` y `Kdbx.kt` en la
  distribución.
- PLAN.md: el llavero sale de «Lo siguiente», y entran las pruebas a mano.
- Cada fichero nuevo del motor nombra el de Python que refleja y conserva sus
  citas a rclone.

## Fuera de esta versión

- Crear un llavero desde el teléfono, o activarlo con una base propia.
- Combinar copias de conflicto en el teléfono (haría falta leer y escribir
  KDBX 4 con su cifrado).
- Una pasada al leer la base: quitada a propósito (sección 3).
- Pasadas periódicas del llavero, y el servicio en segundo plano general
  (PLAN.md §4), del que esto es la primera pieza.
- Comprobar que la llave de `llave_interna` abre la base.
