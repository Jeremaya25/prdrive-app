"""
vectores_conflictos_editor.py — Lo que dice `ui/conflict_editor.py` de prdrive,
para `engine/.../ResolverConflictos.kt`.

`ResolverConflictos.kt` es el espejo de la parte de `conflict_editor.py` que
decide y ejecuta, sin «Combinar» (que necesita `keepassxc-cli`, y el teléfono no
lo tiene): cómo se llama cada versión de un conflicto (`etiquetas`, `etiqueta`),
si hay una versión de cada lado que conservar (`puede`), el plan de quedarse con
una (`plan_lado`, `plan_conservar`) y lo que le pasa al disco al ejecutarlo
(`ResolvePlan.execute`), más los textos de un tamaño y una fecha (`tamano`,
`fecha`, `describir`). Lo esperado de cada caso lo contesta `conflict_editor.py`;
aquí no se escribe ni un resultado.

Lo importa `vectores.py` (que ya ha preparado prdrive) y mete lo que devuelve
[vectores] en el mismo JSON, en la sección `resolver_conflictos`.

**Lo que toca el disco** se monta en una carpeta temporal por caso, con
`model.DEVICE_ROOT` reenganchado a ella. Cada escenario es un volumen con una
pareja y unos ficheros (con su contenido y su fecha en ns, puesta con
`os.utime`); `conflicts.escanear()` encuentra los conflictos como los
encontraría la app, y a partir de ahí todo lo contesta el editor de verdad: las
etiquetas, `puede`, los planes y, ejecutando cada uno **en un volumen recién
montado**, qué dice `execute()` y cómo queda el árbol. Las rutas van relativas a
la carpeta de la pareja.

**Compacto.** El JSON es grande y se lee a ratos: lo que tiene varios campos va
en una sola cadena. Un fichero, `"<ruta>\t<ns>\t<contenido>"`; una operación
sobre el disco, `"escribir\t<ruta>\t<ns>\t<contenido>"`, `"tocar\t<ruta>\t<ns>"`
o `"borrar\t<ruta>"`; una huella, `"<tamaño> <ns>"`; un fichero del árbol,
`"<ns> <contenido>"`. De lo que cambia al ejecutar solo se apunta la
diferencia: `cambios` dice, por ruta, cómo queda lo que ha cambiado entre
justo antes de `execute()` y justo después (`null` si ya no está).

**Las perturbaciones.** Un plan se piensa en un momento y se ejecuta en otro, y
lo que importa de `execute()` es qué hace cuando entre medias algo ha cambiado.
Cada ejecución lleva, además de lo que cambia en el disco (`despues`), los fallos
que se le ponen a `mover()` y a `borrar()`: son funciones de módulo precisamente
para que un test las haga fallar. Se sustituyen por unas que apuntan la llamada
(`llamadas`: el orden en que el editor toca el disco es parte de lo que se
refleja) y lanzan un `OSError` con un mensaje sin ruta ni número de error, para
que el texto sea el mismo en cualquier sistema.

**Sale lo mismo en cualquier sistema:**

- La zona horaria. `fecha()` es `datetime.fromtimestamp(ns / 1e9)`, en la hora
  local de quien la ejecute, y un vector que dependiera de la zona del PC donde se
  generó fallaría en cualquier otro. Se cambia `conflict_editor.datetime` por una
  subclase cuyo `fromtimestamp` lleva una zona de desplazamiento fijo: es la
  misma función con otra zona, la que tendría con `TZ=...` en un sistema que lo
  admita. La zona va en cada vector y el test de Kotlin se la pasa.
- Nada de fechas anteriores a 1970: `datetime.fromtimestamp()` lanza `OSError` en
  Windows con una negativa. Un `Long` de ns no llega más allá de 1677-2262.
- Las fechas de los ficheros son múltiplos de 100 ns, que es lo que guarda
  NTFS; los nombres, válidos en Windows y que se ordenan igual con `/` que con
  `\\`; los contenidos no llevan saltos de línea.
"""

from __future__ import annotations

import os
import tempfile
from contextlib import contextmanager
from datetime import datetime as _datetime
from datetime import timedelta, timezone
from itertools import product
from pathlib import Path, PurePosixPath, PureWindowsPath

DEFAULTS = {"device_remote": "disp", "remote": "nas",
            "catalog_path": "/prdrive-catalog/remote.toml"}

T0 = 1_788_000_000
"""Segundos de época del cero de los escenarios (finales de agosto de 2026)."""

ZONA_ESCENARIOS = 19_800
"""La zona (+05:30, en segundos) en la que se escriben las fechas de los escenarios."""


def _ns(minutos: int = 0, extra: int = 0) -> int:
    """Una hora de fichero en ns: `minutos` después de [T0], y `extra` ns más."""
    return (T0 + minutos * 60) * 10 ** 9 + extra


def _f(ruta: str, contenido: str, minutos: int = 0, extra: int = 0) -> dict:
    """Un fichero del escenario: su ruta, lo que lleva y su hora."""
    return {"ruta": ruta, "contenido": contenido, "ns": _ns(minutos, extra)}


# --- la zona horaria ------------------------------------------------------------

@contextmanager
def _zona(editor, segundos: int):
    """`conflict_editor.fecha()` con una zona fija en vez de la del sistema."""
    zona = timezone(timedelta(seconds=segundos))

    class _Datetime(_datetime):
        @classmethod
        def fromtimestamp(cls, t, tz=None):
            return _datetime.fromtimestamp(t, tz=zona)

    real = editor.datetime
    editor.datetime = _Datetime
    try:
        yield
    finally:
        editor.datetime = real


# --- el disco ---------------------------------------------------------------------

def _rel(ruta: Path, raiz: Path) -> str:
    return ruta.relative_to(raiz).as_posix()


def _texto_fichero(f: dict) -> str:
    return f"{f['ruta']}\t{f['ns']}\t{f['contenido']}"


def _texto_op(op: dict) -> str:
    if op["op"] == "escribir":
        return f"escribir\t{op['ruta']}\t{op['ns']}\t{op['contenido']}"
    if op["op"] == "tocar":
        return f"tocar\t{op['ruta']}\t{op['ns']}"
    return f"borrar\t{op['ruta']}"


def _escribir(ruta: Path, contenido: str, ns: int) -> None:
    ruta.parent.mkdir(parents=True, exist_ok=True)
    ruta.write_bytes(contenido.encode("utf-8"))
    os.utime(ruta, ns=(ns, ns))


def _aplicar(raiz: Path, ops: list[dict]) -> None:
    """Hace en el disco lo que dicen esas operaciones (rutas relativas a `raiz`)."""
    for op in ops:
        ruta = raiz / op["ruta"]
        if op["op"] == "escribir":
            _escribir(ruta, op["contenido"], op["ns"])
        elif op["op"] == "tocar":
            os.utime(ruta, ns=(op["ns"], op["ns"]))
        elif op["op"] == "borrar":
            ruta.unlink()
        else:
            raise AssertionError(f"operación desconocida: {op['op']!r}")


def _arbol(raiz: Path) -> dict:
    """Los ficheros que hay bajo `raiz`: ruta -> `"<ns> <contenido>"`, por ruta."""
    salida = {}
    for carpeta, _, ficheros in os.walk(raiz):
        for nombre in ficheros:
            ruta = Path(carpeta) / nombre
            contenido = ruta.read_bytes().decode("utf-8")
            salida[_rel(ruta, raiz)] = f"{ruta.stat().st_mtime_ns} {contenido}"
    return dict(sorted(salida.items()))


def _cambios(antes: dict, despues: dict) -> dict:
    """Cómo queda, por ruta, lo que es distinto entre dos árboles (`None`: ya no está)."""
    salida = {r: despues[r] for r in despues if antes.get(r) != despues[r]}
    salida.update({r: None for r in antes if r not in despues})
    return dict(sorted(salida.items()))


class _Montaje:
    """Un volumen recién montado con un escenario: su pareja, su carpeta y sus conflictos."""

    def __init__(self, pareja, raiz: Path, conflictos: list):
        self.pareja = pareja
        self.raiz = raiz
        self.conflictos = conflictos


@contextmanager
def _montar(conflicts, model, esc: dict):
    """Monta el escenario en una carpeta temporal y busca sus conflictos.

    Las operaciones `ops_antes` van DESPUÉS de buscarlos: es el orden de la
    pantalla, que enseña los conflictos que encontró y luego alguien (la
    sincronización, otra app) toca el disco antes de que se pulse un botón.
    """
    viejo = model.DEVICE_ROOT
    with tempfile.TemporaryDirectory() as tmp:
        # `resolve()`: en Windows la carpeta temporal puede tener un nombre
        # corto (8.3) y `local_abs` sale con el largo.
        volumen = Path(tmp).resolve() / "volumen"
        volumen.mkdir()
        model.DEVICE_ROOT = volumen
        try:
            config = model.parse_config(esc["config"])
            pareja = next(p for p in config.pairs if p.name == esc["pareja"])
            raiz = pareja.local_abs
            for fichero in esc["ficheros"]:
                _escribir(raiz / fichero["ruta"], fichero["contenido"], fichero["ns"])
            conflictos = conflicts.escanear(pareja)
            # Este script corre en Windows, donde una ruta se ordena sin
            # distinguir mayúsculas y con `\`, y en el teléfono es POSIX: el
            # orden que sale solo vale de vector si es el mismo de las dos
            # maneras. Un caso nuevo que lo rompa tiene que pararse aquí.
            relativas = [_rel(c.original, raiz) for c in conflictos]
            assert sorted(relativas, key=PurePosixPath) == sorted(relativas, key=PureWindowsPath), \
                f"«{esc['caso']}»: estos nombres no se ordenan igual en Windows que en Android: {relativas}"
            _aplicar(raiz, esc.get("ops_antes", []))
            yield _Montaje(pareja, raiz, conflictos)
        finally:
            model.DEVICE_ROOT = viejo


@contextmanager
def _ganchos(editor, raiz: Path, perturbacion: dict):
    """`mover()` y `borrar()` del editor, apuntando lo que les piden y fallando donde se diga.

    Devuelve la lista donde se apuntan las llamadas, en orden.
    """
    llamadas: list[str] = []
    mover_real, borrar_real = editor.mover, editor.borrar

    def mover(origen, destino):
        llamadas.append(f"mover {_rel(origen, raiz)} -> {_rel(destino, raiz)}")
        if perturbacion.get("falla_mover") is not None:
            raise OSError(perturbacion["falla_mover"])
        mover_real(origen, destino)

    def borrar(ruta):
        r = _rel(ruta, raiz)
        llamadas.append(f"borrar {r}")
        for fallo in perturbacion.get("falla_borrar", []):
            if fallo["ruta"] == r:
                raise OSError(fallo["mensaje"])
        if r in perturbacion.get("ya_no_estaba", []):
            # Alguien lo borró justo antes: el fichero se va y `unlink` se queja.
            borrar_real(ruta)
            raise FileNotFoundError(2, "No such file")
        borrar_real(ruta)

    editor.mover, editor.borrar = mover, borrar
    try:
        yield llamadas
    finally:
        editor.mover, editor.borrar = mover_real, borrar_real


# --- los planes ----------------------------------------------------------------------

def _huella(h) -> str | None:
    return None if h is None else f"{h[0]} {h[1]}"


def _plan(editor, plan, raiz: Path) -> dict:
    """Lo que un `ResolvePlan` dice de sí mismo, con las rutas relativas a la pareja."""
    return {"conserva": _rel(plan.conserva.ruta, raiz),
            "descartar": [_rel(r, raiz) for r in plan.descartar],
            "huellas": {_rel(r, raiz): _huella(h) for r, h in plan.huellas.items()},
            "consequences": list(plan.consequences),
            "warnings": list(plan.warnings)}


def _planear(editor, m: _Montaje, ci: int, como: dict):
    """Piensa el plan de `como` para el conflicto `ci`: `(registro, plan o None)`.

    `como` es `{"lado": ...}` (quedarse con la versión de un lado),
    `{"version": i}` (con la i-ésima) o `{"ajena": [conflicto, versión]}` (con
    una versión que no es de este conflicto).
    """
    c = m.conflictos[ci]
    try:
        if "lado" in como:
            plan = editor.plan_lado(c, como["lado"])
        elif "version" in como:
            plan = editor.plan_conservar(c, c.versiones[como["version"]])
        else:
            otro, version = como["ajena"]
            plan = editor.plan_conservar(c, m.conflictos[otro].versiones[version])
    except editor.ResolucionImposible as e:
        return {"como": como, "error": str(e)}, None
    except Exception as e:  # noqa: BLE001 — lo que se cae es el dato
        return {"como": como, "rompe": type(e).__name__}, None
    return {"como": como, "plan": _plan(editor, plan, m.raiz)}, plan


def _perturbaciones(plan, raiz: Path) -> list[dict]:
    """Lo que puede pasar entre pensar el plan y ejecutarlo, o al ejecutarlo."""
    elegida = plan.conserva.ruta
    original = plan.conflicto.original
    tam, ns = plan.huellas[elegida]
    cambiado = elegida.read_bytes().decode("utf-8") + " (cambiado)"
    p: list[dict] = [{"caso": "nada"}]
    p.append({"caso": "la elegida cambia de tamaño",
              "despues": [{"op": "escribir", "ruta": _rel(elegida, raiz), "contenido": cambiado, "ns": ns}]})
    p.append({"caso": "la elegida cambia solo de fecha",
              "despues": [{"op": "tocar", "ruta": _rel(elegida, raiz), "ns": ns + 5 * 10 ** 9}]})
    p.append({"caso": "la elegida desaparece",
              "despues": [{"op": "borrar", "ruta": _rel(elegida, raiz)}]})
    otras = [v.ruta for v in plan.conflicto.versiones
             if v.ruta != elegida and plan.huellas[v.ruta] is not None]
    if otras:
        otra = otras[0]
        p.append({"caso": "otra versión desaparece",
                  "despues": [{"op": "borrar", "ruta": _rel(otra, raiz)}]})
    p.append({"caso": "aparece otra copia que el plan no conoce",
              "despues": [{"op": "escribir", "ruta": _rel(original, raiz) + ".conflicto-remoto9",
                           "contenido": "una copia nueva", "ns": _ns(99)}]})
    if elegida != original:
        p.append({"caso": "mover falla", "falla_mover": "disco lleno"})
    if plan.descartar:
        primera = _rel(plan.descartar[0], raiz)
        p.append({"caso": "borrar falla en la primera descartada",
                  "falla_borrar": [{"ruta": primera, "mensaje": "sin permiso"}]})
        p.append({"caso": "la primera descartada ya no estaba al borrar", "ya_no_estaba": [primera]})
        if len(plan.descartar) > 1:
            p.append({"caso": "borrar falla en todas las descartadas",
                      "falla_borrar": [{"ruta": _rel(d, raiz), "mensaje": f"sin permiso ({i})"}
                                       for i, d in enumerate(plan.descartar, 1)]})
            ultima = _rel(plan.descartar[-1], raiz)
            p.append({"caso": "borrar falla en la última descartada",
                      "falla_borrar": [{"ruta": ultima, "mensaje": "sin permiso"}]})
    return p


def _ejecutar(editor, conflicts, model, esc: dict, ci: int, como: dict, plan_visto: dict,
              perturbacion: dict) -> dict:
    """Ejecuta ese plan en un volumen recién montado y cuenta qué pasó."""
    with _montar(conflicts, model, esc) as m:
        registro, plan = _planear(editor, m, ci, como)
        # El generador tiene que ser determinista: el plan que se ejecuta es el que se enseña.
        assert registro["plan"] == plan_visto, f"«{esc['caso']}»: el plan cambia entre dos montajes"
        _aplicar(m.raiz, perturbacion.get("despues", []))
        antes = _arbol(m.raiz)
        with _ganchos(editor, m.raiz, perturbacion) as llamadas:
            try:
                resultado = {"hechos": plan.execute()}
            except editor.ResolucionImposible as e:
                resultado = {"error": str(e)}
        salida = {"caso": perturbacion["caso"]}
        if "despues" in perturbacion:
            salida["despues"] = [_texto_op(op) for op in perturbacion["despues"]]
        if perturbacion.get("falla_mover") is not None:
            salida["falla_mover"] = perturbacion["falla_mover"]
        if "falla_borrar" in perturbacion:
            salida["falla_borrar"] = [f"{f['ruta']}\t{f['mensaje']}" for f in perturbacion["falla_borrar"]]
        if "ya_no_estaba" in perturbacion:
            salida["ya_no_estaba"] = perturbacion["ya_no_estaba"]
        salida.update(resultado)
        salida["llamadas"] = llamadas
        salida["cambios"] = _cambios(antes, _arbol(m.raiz))
        return salida


def _conflicto(editor, conflicts, model, esc: dict, m: _Montaje, ci: int, primero: bool) -> dict:
    c = m.conflictos[ci]
    raiz = m.raiz
    etiquetas = [nombre for _, nombre in editor.etiquetas(c)]
    # `etiqueta()` y `etiquetas()` dicen lo mismo: se anota una sola vez.
    assert etiquetas == [editor.etiqueta(c, v) for v in c.versiones]
    registro = {
        "original": _rel(c.original, raiz),
        "relativa": c.relativa,
        "versiones": [{"ruta": _rel(v.ruta, raiz), "lado": v.lado, "es_original": v.es_original,
                       "numero": v.numero} for v in c.versiones],
        "es_del_llavero": editor.es_del_llavero(c),
        "nombre_pareja": editor.nombre_pareja(c.pareja),
        "etiquetas": etiquetas,
        "describir": [editor.describir(v.ruta) for v in c.versiones],
        "huellas": {_rel(v.ruta, raiz): _huella(editor.huella(v.ruta)) for v in c.versiones},
        "puede": {lado: editor.puede(c, lado) for lado in ("dispositivo", "remoto", "ninguno")},
    }

    comos = [{"lado": "dispositivo"}, {"lado": "remoto"}]
    comos += [{"version": i} for i in range(len(c.versiones))]
    if primero:
        comos.append({"lado": "inexistente"})
    otros = [j for j in range(len(m.conflictos)) if j != ci]
    if otros and c.versiones and m.conflictos[otros[0]].versiones:
        comos.append({"ajena": [otros[0], 0]})

    # Todas las perturbaciones, para el primer conflicto de cada escenario y, de
    # él, la primera copia y el original; con lo demás, solo la ejecución normal.
    elegidas = []
    if ci == 0:
        copias = [i for i, v in enumerate(c.versiones) if not v.es_original]
        if copias:
            elegidas.append(copias[0])
        if c.versiones and c.versiones[0].es_original:
            elegidas.append(0)

    planes = []
    for como in comos:
        reg, plan = _planear(editor, m, ci, como)
        if plan is not None:
            if "version" in como and como["version"] in elegidas:
                perturbaciones = _perturbaciones(plan, raiz)
            else:
                perturbaciones = [{"caso": "nada"}]
            reg["ejecuciones"] = [
                _ejecutar(editor, conflicts, model, esc, ci, como, reg["plan"], pert)
                for pert in perturbaciones]
        planes.append(reg)
    registro["planes"] = planes
    return registro


def _escenario(editor, conflicts, model, esc: dict) -> dict:
    with _montar(conflicts, model, esc) as m:
        inicial = _arbol(m.raiz)
        conflictos = [_conflicto(editor, conflicts, model, esc, m, ci, ci == 0)
                      for ci in range(len(m.conflictos))]
    return {"caso": esc["caso"], "config": esc["config"], "pareja": esc["pareja"],
            "ficheros": [_texto_fichero(f) for f in esc["ficheros"]],
            "ops_antes": [_texto_op(op) for op in esc.get("ops_antes", [])],
            "arbol_inicial": inicial, "conflictos": conflictos}


# --- los escenarios -----------------------------------------------------------------------

def _config(nombre="notas", local="sync-data/notas", llavero=False, **extra) -> dict:
    """El config con una pareja `nombre` (más lo que se diga de ella) y quizá el llavero."""
    pareja = {"name": nombre, "local": local, "remote_path": "/copia/" + nombre, **extra}
    config = {"defaults": dict(DEFAULTS), "pair": [pareja]}
    if llavero:
        config["keychain"] = {"base": "personal.kdbx"}
    return config


def _escenarios() -> list[dict]:
    de_cada_lado = [
        _f("e.txt", "e, el original", 60),
        _f("e.txt.conflicto-dispositivo1", "e del dispositivo", 30),
        _f("e.txt.conflicto-remoto1", "e del remoto", 45),
    ]
    # Justo antes de un minuto: con el redondeo a microsegundos de Python, la
    # fecha que se enseña es la del minuto siguiente.
    antes_del_minuto = (T0 // 60 + 1) * 60 * 10 ** 9 - 400

    def de(caso, ficheros, config=None, pareja="notas", **resto):
        return {"caso": caso, "config": config or _config(), "pareja": pareja,
                "ficheros": ficheros, **resto}

    return [
        de("ganó el dispositivo: la copia es la del remoto", [
            _f("plan.md", "plan del dispositivo", 30),
            _f("plan.md.conflicto-remoto1", "plan del remoto", 10),
            _f("notas.txt", "otra cosa", 5)]),
        de("ganó el remoto, que es más reciente: la copia es la del dispositivo", [
            _f("plan.md", "plan del remoto, más reciente", 40),
            _f("plan.md.conflicto-dispositivo1", "plan del dispositivo", 20)]),
        de("las dos versiones con la misma fecha", [
            _f("hora.md", "uno", 15), _f("hora.md.conflicto-remoto1", "dos y algo más", 15)]),
        de("la descartada es más reciente por un microsegundo", [
            _f("hora.md", "uno", 15), _f("hora.md.conflicto-remoto1", "dos", 15, 1000)]),
        de("la fecha cae justo antes de un minuto", [
            {"ruta": "hora.md", "contenido": "uno", "ns": antes_del_minuto},
            {"ruta": "hora.md.conflicto-remoto1", "contenido": "dos", "ns": antes_del_minuto - 100}]),
        de("dos copias del mismo lado", [
            _f("c.txt", "c, el original", 50),
            _f("c.txt.conflicto-dispositivo1", "c del dispositivo, la primera", 30),
            _f("c.txt.conflicto-dispositivo2", "c del dispositivo, la segunda", 40)]),
        de("una copia de cada lado y el original sin lado", de_cada_lado),
        de("sin el original", [
            _f("solo.txt.conflicto-remoto1", "solo, del remoto", 10),
            _f("dos.txt.conflicto-dispositivo1", "dos, del dispositivo", 10),
            _f("dos.txt.conflicto-remoto1", "dos, del remoto", 20),
            _f("tres.txt.conflicto-remoto1", "tres, la primera del remoto", 10),
            _f("tres.txt.conflicto-remoto2", "tres, la segunda del remoto", 20)]),
        de("rutas anidadas, con espacios y con tildes", [
            _f("música/ñandú «uno».md", "el original", 30),
            _f("música/ñandú «uno».md.conflicto-remoto1", "del remoto", 10),
            _f("a b/c d.txt", "con espacios", 30),
            _f("a b/c d.txt.conflicto-dispositivo1", "del dispositivo", 10),
            _f("a.b.c.txt", "con puntos", 30),
            _f("a.b.c.txt.conflicto-remoto3", "del remoto, la tercera", 10)]),
        de("un sufijo para los dos lados: no se sabe de cuál viene", [
            _f("x.md", "x, el original", 20),
            _f("x.md.c1", "x, la uno", 10),
            _f("x.md.c2", "x, la dos", 15),
            _f("y.md", "y, el original", 20),
            _f("y.md.c1", "y, la copia", 10),
            _f("z.md.c1", "z, sin original", 10)],
           config=_config(flags={"conflict-suffix": "c"})),
        de("un sufijo y pathname: el número dice el lado", [
            _f("p.md", "p, el original", 30), _f("p.md.c1", "p, de path1", 10),
            _f("q.md", "q, el original", 30), _f("q.md.c2", "q, de path2", 10)],
           config=_config(flags={"conflict-suffix": "c", "conflict-loser": "pathname"})),
        de("con versiones: el sufijo va delante de la extensión", [
            _f("v.md", "v, el original", 30), _f("v.conflicto-remoto1.md", "v, del remoto", 10),
            _f("sub/w.tar.gz", "w, el original", 30),
            _f("sub/w.conflicto-dispositivo2.tar.gz", "w, del dispositivo", 10)],
           config=_config(versions=True)),
        de("la raíz entera, sin lo que no es suyo", [
            _f("doc.txt", "doc, el original", 20),
            _f("doc.txt.conflicto-remoto1", "doc, del remoto", 10),
            _f("carpeta/hoja.csv", "hoja, el original", 20),
            _f("carpeta/hoja.csv.conflicto-dispositivo1", "hoja, del dispositivo", 10),
            _f(".prdrive/k.txt.conflicto-remoto1", "del programa", 10),
            _f(".keychain/x.kdbx.conflicto-remoto1", "del llavero", 10),
            _f(".prversions/v.txt.conflicto-remoto1", "de las versiones", 10)],
           config=_config(nombre="todo", local="."), pareja="todo"),
        de("el llavero: una copia del remoto", [
            _f(".keychain/personal.kdbx", "base del dispositivo", 30),
            _f(".keychain/personal.conflicto-remoto1.kdbx", "base del remoto", 10)],
           config=_config(llavero=True), pareja="keychain"),
        de("el llavero: varias bases y un fichero que no es una base", [
            _f(".keychain/personal.kdbx", "base buena", 60),
            _f(".keychain/personal.conflicto-dispositivo20261005123000.kdbx", "base del dispositivo", 30),
            _f(".keychain/personal.conflicto-remoto1.kdbx", "base del remoto", 45),
            _f(".keychain/LEEME.txt", "leeme", 60),
            _f(".keychain/LEEME.conflicto-remoto1.txt", "leeme del remoto", 10),
            _f(".keychain/otra.KDBX", "otra base", 60),
            _f(".keychain/otra.conflicto-dispositivo1.KDBX", "otra base del dispositivo", 10)],
           config=_config(llavero=True), pareja="keychain"),
        de("la copia desaparece tras escanear", de_cada_lado,
           ops_antes=[{"op": "borrar", "ruta": "e.txt.conflicto-remoto1"}]),
        de("el original desaparece tras escanear", de_cada_lado,
           ops_antes=[{"op": "borrar", "ruta": "e.txt"}]),
        de("todo desaparece tras escanear", de_cada_lado,
           ops_antes=[{"op": "borrar", "ruta": "e.txt"},
                      {"op": "borrar", "ruta": "e.txt.conflicto-dispositivo1"},
                      {"op": "borrar", "ruta": "e.txt.conflicto-remoto1"}]),
        de("el original cambia tras escanear", de_cada_lado,
           ops_antes=[{"op": "escribir", "ruta": "e.txt", "contenido": "e, ya con otras cosas",
                       "ns": _ns(70)}]),
    ]


# --- lo que no necesita disco ------------------------------------------------------------------

def _lcg(n: int, semilla: int = 20261031) -> list[int]:
    """`n` enteros sin patrón a la vista, pero los mismos en cualquier sitio."""
    x, salida = semilla, []
    for _ in range(n):
        x = (x * 6364136223846793005 + 1442695040888963407) % 2 ** 64
        salida.append(x)
    return salida


def _octetos() -> list[int]:
    v = [0, 1, 2, 9, 10, 99, 100, 512, 1000, 1023, 1024, 1025, 1229, 2047, 2048, 12595, 999999]
    for k in (1, 2, 3, 4):
        # Justo en cada unidad y a ambos lados...
        for d in (-1, 0, 1):
            v.append(1024 ** k + d)
        # ...y empates exactos de `.1f` (x,25 y x,75), que se redondean al par.
        for m in (1, 2, 3, 10, 100):
            v += [1024 ** k * m + 1024 ** (k - 1) * 256, 1024 ** k * m + 1024 ** (k - 1) * 768]
    # Los que casi llegan a la unidad siguiente: ¿«1023,9 KB» o «1024,0 KB»?
    for k in (1, 2, 3):
        for d in (1, 2, 52, 53, 54, 100):
            v.append(1024 ** (k + 1) - d * 1024 ** (k - 1))
    v += [-1, -1023, -1024, -2048, -10 ** 12, 2 ** 40, 2 ** 40 + 1, 2 ** 50, 2 ** 53, 2 ** 53 + 1,
          2 ** 60, 2 ** 62, 2 ** 63 - 1, -2 ** 63]
    for i, x in enumerate(_lcg(80)):
        v.append(x % 10 ** (i % 19 + 1))
    # Un tamaño de fichero es un `st_size` de 64 bits con signo: el `Long` de Kotlin.
    return [n for n in dict.fromkeys(v) if -2 ** 63 <= n < 2 ** 63]


def _ns_fecha() -> list[int]:
    minuto = (T0 // 60 + 1) * 60 * 10 ** 9
    v = [0, 1, 999, 10 ** 6, 10 ** 9 - 1, 10 ** 9, 59 * 10 ** 9, 59_999_999_500, 60 * 10 ** 9 - 1,
         60 * 10 ** 9, 61 * 10 ** 9, 86400 * 10 ** 9 - 1, 86400 * 10 ** 9]
    # Alrededor de un minuto de hoy: el redondeo a microsegundos decide si la
    # fecha es la de este minuto o la del siguiente.
    for d in (1, 10, 100, 250, 400, 499, 500, 501, 600, 900, 999, 1000, 1500, 100_000):
        v += [minuto - d, minuto + d]
    v += [minuto, _ns(0), _ns(0, 123_456_789), _ns(1440), _ns(60 * 24 * 31, 999_999_999),
          _ns(0, 999_999_500), _ns(0, 999_999_600), _ns(0, 999_999_400)]
    # Cambios de día, de mes, de año y de siglo; el 29 de febrero.
    for fecha in ((2026, 12, 31, 23, 59, 59), (2027, 1, 1, 0, 0, 0), (2028, 2, 29, 12, 0, 0),
                  (2028, 3, 1, 0, 0, 0), (2038, 1, 19, 3, 14, 7), (2100, 1, 1, 0, 0, 0),
                  (2106, 2, 7, 6, 28, 15), (2200, 6, 15, 8, 30, 0)):
        seg = int(_datetime(*fecha, tzinfo=timezone.utc).timestamp())
        v += [seg * 10 ** 9, seg * 10 ** 9 + 999_999_999, seg * 10 ** 9 - 1]
    v += [2 ** 53, 2 ** 53 + 1, 2 ** 62, 2 ** 63 - 1]
    v += [x % 2 ** 62 for x in _lcg(40, 7)]
    return list(dict.fromkeys(v))


def _vectores_etiquetas(editor, conflicts) -> list[dict]:
    """`etiquetas()` sobre conflictos que no hacen falta en disco: ninguna ruta se toca.

    Todas las combinaciones de un original (sin él, o con cada lado) y hasta tres
    copias (con cada lado). Cada versión se escribe como su lado y, si es el
    original, con un `*` delante; el que no tiene lado es `ninguno`.
    """
    raiz = Path("sin") / "disco"
    lados = [None, "dispositivo", "remoto"]
    nombre = {None: "ninguno", "dispositivo": "dispositivo", "remoto": "remoto", "otro": "otro"}

    def conflicto(versiones):
        vs = []
        for i, (lado, original) in enumerate(versiones):
            ruta = raiz / ("original.txt" if original else f"original.txt.copia{i}")
            vs.append(conflicts.Version(ruta, lado, original, i))
        return conflicts.Conflicto("notas", raiz, raiz / "original.txt", tuple(vs))

    combinaciones = []
    for originales in ([[]] + [[(lado, True)] for lado in lados]):
        for n in range(4):
            for copias in product(lados, repeat=n):
                combinaciones.append(originales + [(lado, False) for lado in copias])
    # Un lado que no es de ninguno de los dos: se trata como uno que no se sabe.
    combinaciones += [[("otro", False)], [("otro", True), ("otro", False)],
                      [("otro", True), ("dispositivo", False), ("otro", False), ("otro", False)]]

    registros = []
    for versiones in combinaciones:
        c = conflicto(versiones)
        etiquetas = [e for _, e in editor.etiquetas(c)]
        assert etiquetas == [editor.etiqueta(c, v) for v in c.versiones]
        registros.append({
            "versiones": " ".join(("*" if original else "") + nombre[lado] for lado, original in versiones),
            "etiquetas": " | ".join(etiquetas)})
    return registros


# --- todo ---------------------------------------------------------------------------------------

def vectores(editor, conflicts, model) -> dict:
    """La sección `resolver_conflictos` de `vectores.json`."""
    zonas = [0, ZONA_ESCENARIOS, -12_600, 46_800]
    fechas = []
    for zona in zonas:
        with _zona(editor, zona):
            fechas += [f"{ns} {zona} {editor.fecha(ns)}" for ns in _ns_fecha()]
    with _zona(editor, ZONA_ESCENARIOS):
        escenarios = [_escenario(editor, conflicts, model, e) for e in _escenarios()]
    return {
        "constantes": {
            "ETIQUETA_LADO": dict(editor.ETIQUETA_LADO),
            "ETIQUETA_ORIGINAL": editor.ETIQUETA_ORIGINAL,
            "ETIQUETA_COPIA": editor.ETIQUETA_COPIA,
            "NOTA_LOCAL": editor.NOTA_LOCAL,
            "PIERDE_LLAVERO": editor.PIERDE_LLAVERO,
            "LLAVERO": model.LLAVERO,
        },
        "nombre_pareja": [{"nombre": n, "texto": editor.nombre_pareja(n)}
                          for n in ["keychain", "notas", "(el llavero)", "", "Keychain", "keychain ", "todo"]],
        "tamano": [f"{n} {editor.tamano(n)}" for n in _octetos()],
        "fecha": fechas,
        "etiquetas": _vectores_etiquetas(editor, conflicts),
        "zona_escenarios": ZONA_ESCENARIOS,
        "escenarios": escenarios,
    }
