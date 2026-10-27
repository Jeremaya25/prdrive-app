# prdrive-app

La versión Android de [prdrive](https://github.com/Jeremaya25/prdrive):
sincronización en dos sentidos entre un remoto de rclone y el teléfono,
gobernada por el mismo catálogo de parejas que los dispositivos de escritorio.

prdrive vive **en** un disco extraíble y lo ejecuta el Python del propio disco.
En Android eso no se puede: no se ejecuta un programa desde un volumen
extraíble, no hay Tk, y no se llega a la raíz de una SD sin un permiso especial.
Así que esto es una app autónoma cuyo «disco» es una carpeta que ella misma
posee dentro de su almacenamiento privado —que Android cifra y que no necesita
ningún permiso—, y rclone viaja dentro como biblioteca.

La conexión con el remoto **no se teclea**: se lee de un código QR que enseña un
dispositivo de prdrive ya provisionado (Doctor → «Emparejar un móvil…»). Ese QR
lleva la clave privada dentro, y quien lo enseña avisa.

## Estado

El paso 1 está hecho y probado en un emulador: el volumen, el emparejamiento por
QR, elegir parejas del catálogo y un «Sincronizar» que cuenta qué pasa, con el
motor alineado con prdrive 0.7.7, que fija rclone v1.75.2. Cómo está y por qué,
en [`AGENTS.md`](AGENTS.md); lo que queda, en [`PLAN.md`](PLAN.md).

Cada versión se publica como un APK firmado en las
[releases](https://github.com/Jeremaya25/prdrive-app/releases): basta con
cambiar `VERSION` en main (AGENTS.md, «Versiones y releases»).

La app **se pone al día sola** desde esas releases: al abrirla pregunta a
GitHub (una vez al día), baja la versión nueva con wifi y, desde Android 12, la
instala al salir de la app sin preguntar, si se le ha permitido una vez instalar
apps. Antes de Android 12, o sin ese permiso, se instala con un toque. La primera
versión que lo hace hay que instalarla a mano (AGENTS.md, «La
autoactualización»).

## Para abrir un vault de Obsidian

Obsidian no alcanza el almacenamiento privado de la app, así que una pareja se
puede **mover a una carpeta compartida** del teléfono, donde la abre como vault
(«Abrir carpeta como vault», en el almacenamiento del dispositivo). Solo esa
pareja sale; la clave, el llavero y las demás siguen privados.

- **Cómo.** En la fila de la pareja, «Mover a una carpeta compartida…». Hace
  falta Android 11 o posterior y el permiso de acceso a todos los archivos (la
  app te lleva a darlo). Eliges una carpeta **vacía o nueva** del almacenamiento
  interno (por ejemplo `Documents/vault`; no una SD ni una nube). «Volver al
  volumen» la devuelve; la carpeta compartida no se borra, es tuya. Cualquier
  app con ese permiso podrá leerla y cambiarla, y se verá al conectar el
  teléfono a un ordenador.
- **Ruido de Obsidian.** `.obsidian/workspace*.json` cambia cada vez que mueves
  una pestaña y acabaría en conflictos. Se excluye en el catálogo (vale también
  para el PC): `exclude = [".obsidian/workspace*.json"]` en la pareja.
- **Un vault pequeño y el freno de borrados.** Bisync no borra más de un 25 %
  de golpe: con pocas notas, borrar dos puede pasarlo. La pasada se para sin
  perder nada, y se salta con «Forzar» desde prdrive en el PC (la app todavía no
  lo tiene).
- **Copias de conflicto.** Si editas la misma nota en los dos lados, quedan las
  dos y la fila lo avisa. Sin `versions = true` en la pareja la copia es
  `nota.md.conflicto-remoto1`, que Obsidian no indexa (se resuelve desde la app
  de Archivos); con él es `nota.conflicto-remoto1.md` y sale como una nota más.
- **Desinstalar.** El vault se queda en el teléfono y `.prdrive/` no. Al volver a
  emparejar esa carpeta ya no está vacía y no se pisa: hay que mover la pareja a
  otra nueva (o vaciar la vieja, tras guardar lo que no hubiera subido).

Cómo está hecho, en [`AGENTS.md`](AGENTS.md) («Las carpetas compartidas»). Está
probado en un emulador y con un servidor SFTP; con Obsidian de verdad, todavía
no ([`PLAN.md`](PLAN.md) §5).

El **motor** (`engine/`) es Kotlin puro, sin nada de Android, y se prueba sin SDK,
sin emulador y sin teléfono:

```bash
./gradlew :engine:test
```

## Por qué el motor se prueba contra prdrive y no contra sí mismo

`engine/` traduce a Kotlin las constantes de `common/model.py` y
`common/bisync.py`, así que esas constantes existen ahora dos veces — y dos
copias se separan. De ellas depende el nombre con el que bisync guarda su
baseline: si el móvil lo calcula distinto, bisync no lo encuentra, y lo que
falta en un lado se lee como borrado.

Por eso los valores esperados no se escriben a mano. `herramientas/vectores.py`
importa el prdrive de verdad, le pregunta caso por caso y escribe las respuestas
en un JSON que los tests leen, con la versión y el commit de prdrive dentro.
Cuando prdrive mueva una constante, falla el test que la nombra.

```bash
python herramientas/vectores.py ../prdrive
```

## Licencia

Apache 2.0, igual que prdrive. Ver [LICENSE](LICENSE).
