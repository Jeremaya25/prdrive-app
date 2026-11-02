"""
vectores_parejas_editor.py — Lo que dice `ui/pair_editor.py` de prdrive al quitar
una pareja de este dispositivo, para `engine/.../EditarParejas.kt`.

`EditarParejas.kt` refleja `plan_remove()` («Quitar…»): el config que resulta,
lo que se enseña antes de confirmar (`consequences`), qué baseline se aparta
(`shelve`), qué filtros sobran (`borrar_filtros`) y lo que se niega, con su
mensaje; y el aviso de un modo espejo. Aquí no se escribe ni un resultado: lo
contesta `pair_editor.py`.

`plan_enable()` («Usar aquí») ya no sale de aquí: con prdrive#202 (#196) lleva la
carpeta que elige cada dispositivo, y sus casos están en `vectores_196.py`.

Lo importa `vectores.py` (que ya ha preparado prdrive) y mete lo que devuelve
[vectores] en el mismo JSON, en la sección `editar_parejas`.

**Compacto.** Los configs de quitar van una vez (`configs`), y los casos los
nombran; lo que dice cada plan va entero, que es lo que se compara.

**Lo que mira el disco.** `plan_remove()` mira qué hay de la pareja en `state/`
y `filters/` (`bisync.pair_state_paths()`). Cada caso lo monta en una carpeta
temporal, con `model.STATE_DIR` y `model.FILTERS_DIR` reenganchados a ella, y
los ficheros de filtros que sobran van con su ruta relativa a esa carpeta, con
`/`, para que salga igual en cualquier sistema.
"""

from __future__ import annotations

import tempfile
from pathlib import Path

DEFAULTS = {"device_remote": "disp", "remote": "nas",
            "catalog_path": "/prdrive-catalog/remote.toml"}

# El config de un dispositivo con dos parejas y un `[daemon]` que nombra una.
DISPOSITIVO = {
    "defaults": dict(DEFAULTS),
    "daemon": {"pairs": ["notas"], "interval_minutes": 15},
    "pair": [
        {"name": "notas", "local": "notas", "remote_path": "/copia/notas"},
        {"name": "subida", "local": "salida", "remote_path": "/copia/salida", "mode": "up"},
    ],
}

# Los configs de los casos, por nombre: el del dispositivo, uno con una pareja
# más, uno cuyo `[daemon]` solo nombra la que se quita, uno de una sola, uno sin
# `pairs` en el `[daemon]` y dos con un `pair` que no es una lista de tablas
# (prdrive se cae con ellos: el motor tiene que decirlo con un `ConfigError`).
CONFIGS = {
    "dispositivo": DISPOSITIVO,
    "tres": {**DISPOSITIVO, "pair": DISPOSITIVO["pair"] + [
        {"name": "fotos", "local": "fotos", "remote_path": "/copia/fotos"}]},
    "sola_en_daemon": {**DISPOSITIVO, "daemon": {"pairs": ["notas"]}},
    "una": {**DISPOSITIVO, "pair": DISPOSITIVO["pair"][:1]},
    "daemon_sin_pairs": {**DISPOSITIVO, "daemon": {"interval_minutes": 15}},
    "pair_tabla": {"defaults": dict(DEFAULTS), "pair": dict(DISPOSITIVO["pair"][0])},
    "pair_con_un_numero": {"defaults": dict(DEFAULTS), "pair": [1, *DISPOSITIVO["pair"]]},
}


def _plan(plan, raiz: Path | None = None) -> dict:
    """Lo que dice un `EditPlan`, con los filtros relativos a la carpeta del caso."""
    return {
        "raw": plan.raw,
        "consequences": list(plan.consequences),
        "warnings": list(plan.warnings),
        "shelve": list(plan.shelve),
        "borrar_filtros": [p.relative_to(raiz).as_posix() for p in plan.borrar_filtros] if raiz else [],
    }


def _o_error(funcion, *args, **kwargs) -> dict:
    """`{"plan": …}` si piensa el plan, `{"error": mensaje}` si se niega, o
    `{"rompe": "AttributeError"}` si prdrive se cae con otra excepción: ese no
    tiene mensaje que copiar, y el motor tiene que dar un `ConfigError` con el
    suyo."""
    from common.model import ConfigError   # noqa: PLC0415
    try:
        return {"plan": funcion(*args, **kwargs)}
    except ConfigError as e:
        return {"error": str(e)}
    except Exception as e:   # noqa: BLE001 — es justo lo que se quiere apuntar
        return {"rompe": type(e).__name__}


def _montar(model, carpeta: Path, ficheros: list[str]) -> None:
    """`state/` y `filters/` dentro de `carpeta`, con lo que diga el caso."""
    model.STATE_DIR = carpeta / "state"
    model.FILTERS_DIR = carpeta / "filters"
    model.STATE_DIR.mkdir()
    model.FILTERS_DIR.mkdir()
    for rel in ficheros:
        ruta = carpeta / rel
        if rel.endswith("/"):
            ruta.mkdir(parents=True)
        else:
            ruta.parent.mkdir(parents=True, exist_ok=True)
            ruta.write_text("x", encoding="utf-8")


def vectores_remove(editor, model) -> list[dict]:
    """`plan_remove()`: quitar una pareja de este dispositivo, con o sin su estado."""
    todo = ["state/notas/", "state/notas/x.path1.lst", "filters/notas.txt", "filters/notas.txt.md5"]
    casos = [
        ("sin nada en disco", "dispositivo", "subida", False, []),
        ("sin nada en disco, limpiando", "dispositivo", "subida", True, []),
        ("con su estado, sin limpiar", "dispositivo", "notas", False, todo),
        ("con su estado, limpiando", "dispositivo", "notas", True, todo),
        ("solo los filtros, limpiando", "dispositivo", "notas", True, todo[2:]),
        ("solo el workdir, limpiando", "dispositivo", "notas", True, todo[:2]),
        ("solo el workdir, sin limpiar", "dispositivo", "notas", False, todo[:1]),
        ("la que nombra el daemon", "tres", "notas", False, []),
        ("la única que nombra el daemon", "sola_en_daemon", "notas", False, []),
        ("una que el daemon no nombra", "tres", "fotos", False, []),
        ("un daemon sin pairs", "daemon_sin_pairs", "notas", False, []),
        ("un state que es un fichero, limpiando", "dispositivo", "notas", True,
         ["state/notas", *todo[2:]]),
        ("la última", "una", "notas", False, []),
        ("una que no está", "dispositivo", "inventada", False, []),
        ("un pair que es una tabla", "pair_tabla", "notas", False, []),
        ("un pair con algo que no es una tabla", "pair_con_un_numero", "notas", False, []),
    ]
    salida = []
    estado, filtros = model.STATE_DIR, model.FILTERS_DIR
    try:
        for caso, config, nombre, limpiar, ficheros in casos:
            with tempfile.TemporaryDirectory() as tmp:
                carpeta = Path(tmp).resolve()
                _montar(model, carpeta, ficheros)
                r = _o_error(editor.plan_remove, CONFIGS[config], nombre, clean_state=limpiar)
                if "plan" in r:
                    r = {"plan": _plan(r["plan"], carpeta)}
            salida.append({"caso": caso, "config": config, "nombre": nombre, "limpiar": limpiar,
                           "en_disco": ficheros, **r})
    finally:
        model.STATE_DIR, model.FILTERS_DIR = estado, filtros
    return salida


def vectores(editor, catalog, model) -> dict:
    """Todo lo de `pair_editor.py` que refleja `EditarParejas.kt`."""
    return {
        "configs": CONFIGS,
        "mirror_modes": list(editor.MIRROR_MODES),
        "avisos_espejo": {m: editor.mirror_warning(m) for m in [*model.MODES, "inventado"]},
        "avisos_espejo_partidos": {m: editor.aviso_espejo(m) for m in [*model.MODES, "inventado"]},
        "remove": vectores_remove(editor, model),
    }
