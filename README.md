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
