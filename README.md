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

En construcción. El plan del paso 1 —con todo lo que se ha comprobado contra el
código de rclone v1.75.1, que es la versión que prdrive fija— está en
[`PLAN.md`](PLAN.md).

Hecho:

- El **motor** (`engine/`), Kotlin puro y sin nada de Android — las capas de
  flags, el `upstreams` del remote `combine`, el nombre de sesión de bisync, el
  estado del baseline, el TOML, la lectura del payload del QR, cómo viaja cada
  flag hasta el RPC de rclone, la traducción de un fallo a algo accionable y la
  línea de progreso.
- El paquete **Go** que construye el `.aar` (`rclone/gobind/`): rclone como
  biblioteca, con los métodos que la app necesita registrados, y con la lista
  de backends ya decidida midiendo las dos (`rclone/aar.sh`): 12,6 MB con los
  cinco que hacen falta contra 43,9 MB con los cincuenta de `backend/all`.
- Un **spike** (`rclone/spike/`) que ejecuta rclone de verdad y comprueba, una
  por una, las afirmaciones sobre rclone en las que se apoya el diseño.

```bash
./gradlew :engine:test            # el motor
cd rclone && go run ./spike       # rclone de verdad
```

Ninguno de los dos necesita SDK de Android, ni NDK, ni emulador, ni teléfono,
ni red. Hoy son **111 tests** y ninguno lleva un valor esperado escrito a
mano.

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

Eso deja una pregunta abierta: **¿y si las dos copias se equivocan igual?** El
nombre de los listados lo decide rclone, no prdrive. Así que el spike arranca
rclone como biblioteca, sincroniza tres parejas —una normal, la de la raíz del
volumen y una con un espacio en la ruta—, y apunta lo que rclone devolvió.
`SesionesTest` compara eso con lo que calcula el motor.

```bash
cd rclone && go run ./spike -json ../engine/src/test/resources/sesiones.json
```

Y corre en CI, así que subir la versión de rclone no puede romper el diseño en
silencio.

## Licencia

Apache 2.0, igual que prdrive. Ver [LICENSE](LICENSE).
