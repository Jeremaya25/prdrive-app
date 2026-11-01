"""
vectores_parejas_editor.py — Lo que dice `ui/pair_editor.py` de prdrive al usar
aquí una pareja del catálogo y al quitarla, para `engine/.../EditarParejas.kt`.

`EditarParejas.kt` refleja `plan_enable()` («Usar aquí») y `plan_remove()`
(«Quitar…»): el config que resulta, lo que se enseña antes de confirmar
(`consequences`, `warnings`), qué baseline se aparta (`shelve`), qué filtros
sobran (`borrar_filtros`) y lo que se niega, con su mensaje. Aquí no se escribe
ni un resultado: lo contesta `pair_editor.py`.

Lo importa `vectores.py` (que ya ha preparado prdrive) y mete lo que devuelve
[vectores] en el mismo JSON, en la sección `editar_parejas`.

**La carpeta elegida (prdrive#196).** El teléfono elige la carpeta de una pareja
al añadirla, y su plan es el de `plan_enable()` con la entrada del catálogo
llevando esa carpeta: los casos con `carpeta` son eso, la misma pareja con otro
`local` (el test cambia el de [CATALOGO] igual), y la que no trae `local` es
como quedará el catálogo con #196.

**Compacto.** El config del dispositivo, el catálogo y los configs de quitar van
una vez (`dispositivo`, `catalogo`, `configs`), y los casos los nombran; lo que
dice cada plan va entero, que es lo que se compara.

**Lo que mira el disco.** `plan_remove()` mira qué hay de la pareja en `state/`
y `filters/` (`bisync.pair_state_paths()`). Cada caso lo monta en una carpeta
temporal, con `model.STATE_DIR` y `model.FILTERS_DIR` reenganchados a ella, y
los ficheros de filtros que sobran van con su ruta relativa a esa carpeta, con
`/`, para que salga igual en cualquier sistema.
"""

from __future__ import annotations

import copy
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

# El catálogo: las del dispositivo y las que no usa, de cada modo, una con lo
# que se copia tal cual (filtros, flags, versiones) y otra sin `local`, como
# quedará el catálogo con #196.
CATALOGO = {
    "defaults": {"remote": "nas", "catalog_path": "/prdrive-catalog/remote.toml"},
    "pair": [
        {"name": "notas", "local": "notas", "remote_path": "/copia/notas"},
        {"name": "subida", "local": "salida", "remote_path": "/copia/salida", "mode": "up"},
        {"name": "fotos", "local": "sync-data/fotos", "remote_path": "/copia/fotos",
         "include": ["*.jpg"], "flags": {"max-delete": 5}, "versions": True},
        {"name": "musica", "local": "musica", "remote_path": "/copia/musica",
         "mode": "down"},
        {"name": "espejo-sube", "local": "web", "remote_path": "/copia/web",
         "mode": "up-mirror"},
        {"name": "espejo-baja", "local": "docs", "remote_path": "/copia/docs",
         "mode": "down-mirror"},
        {"name": "sin-local", "remote_path": "/copia/sin-local"},
        {"name": "rara", "local": "../fuera", "remote_path": "/copia/rara"},
        {"name": "programa", "local": ".prdrive/keys", "remote_path": "/copia/programa"},
        {"name": "peligrosa", "local": "peligrosa", "remote_path": "/copia/peligrosa",
         "extra_flags": ["--resync"]},
    ],
}


# Los configs de los casos, por nombre: el del dispositivo, uno con una pareja
# más, uno cuyo `[daemon]` solo nombra la que se quita, uno de una sola, uno con
# una que el catálogo no tiene, uno sin `pairs` en el `[daemon]`, uno sin
# ninguna pareja y dos con un `pair` que no es una lista de tablas (prdrive se
# cae con ellos: el motor tiene que decirlo con un `ConfigError`).
CONFIGS = {
    "dispositivo": DISPOSITIVO,
    "tres": {**DISPOSITIVO, "pair": DISPOSITIVO["pair"] + [
        {"name": "fotos", "local": "fotos", "remote_path": "/copia/fotos"}]},
    "sola_en_daemon": {**DISPOSITIVO, "daemon": {"pairs": ["notas"]}},
    "una": {**DISPOSITIVO, "pair": DISPOSITIVO["pair"][:1]},
    "con_una_de_aqui": {**DISPOSITIVO, "pair": DISPOSITIVO["pair"] + [
        {"name": "solo-aqui", "local": "aqui", "remote_path": "/copia/aqui"}]},
    "daemon_sin_pairs": {**DISPOSITIVO, "daemon": {"interval_minutes": 15}},
    "sin_parejas": {"defaults": dict(DEFAULTS)},
    "pair_tabla": {"defaults": dict(DEFAULTS), "pair": dict(DISPOSITIVO["pair"][0])},
    "pair_con_un_numero": {"defaults": dict(DEFAULTS), "pair": [1, *DISPOSITIVO["pair"]]},
}


def _catalogo(catalog, raw: dict | None):
    """Un `Catalog` como lo deja `catalog.pull()`, o `None`."""
    if raw is None:
        return None
    return catalog.Catalog(raw=raw, text="", source="remote", stamp="2026-11-01 10:00",
                           endpoint="nas:/prdrive-catalog/remote.toml")


def _con_carpeta(raw: dict, nombre: str, local: str) -> dict:
    """El catálogo con la carpeta de `nombre` elegida: la misma entrada, otro `local`."""
    nuevo = copy.deepcopy(raw)
    for p in nuevo["pair"]:
        if p.get("name") == nombre:
            p["local"] = local
    return nuevo


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


def vectores_enable(editor, catalog) -> list[dict]:
    """`plan_enable()`: usar aquí una pareja del catálogo.

    Cada caso dice el config del dispositivo ([CONFIGS]), la pareja y, si la
    carpeta se elige, cuál (`carpeta`): el catálogo es [CATALOGO] con el `local`
    de esa pareja cambiado por ella. Sin `catalogo`, no hay catálogo.
    """
    casos = [
        ("bisync con lo que se copia tal cual", "dispositivo", "fotos", None, True),
        ("bisync con la carpeta elegida", "dispositivo", "fotos", "Fotos/2026", True),
        ("bisync en la raíz", "dispositivo", "fotos", ".", True),
        ("de bajada", "dispositivo", "musica", None, True),
        ("espejo de subida", "dispositivo", "espejo-sube", None, True),
        ("espejo de bajada", "dispositivo", "espejo-baja", None, True),
        ("sin local en el catálogo", "dispositivo", "sin-local", None, True),
        ("sin local, con la carpeta elegida", "dispositivo", "sin-local", "otra", True),
        ("una carpeta que sale del dispositivo", "dispositivo", "rara", None, True),
        ("una carpeta en la del programa", "dispositivo", "programa", None, True),
        ("un flag que no se puede llevar", "dispositivo", "peligrosa", None, True),
        ("una que no está en el catálogo", "dispositivo", "inventada", None, True),
        ("una de aquí que el catálogo no tiene", "con_una_de_aqui", "solo-aqui", None, True),
        ("sin catálogo", "dispositivo", "fotos", None, False),
        ("una que ya está aquí", "dispositivo", "notas", None, True),
        ("un dispositivo sin parejas", "sin_parejas", "fotos", None, True),
        ("un pair que es una tabla", "pair_tabla", "fotos", None, True),
        ("un pair que es una tabla, y una que no está en el catálogo", "pair_tabla", "inventada",
         None, True),
        ("un pair con algo que no es una tabla", "pair_con_un_numero", "fotos", None, True),
    ]
    salida = []
    for caso, config, nombre, carpeta, con_catalogo in casos:
        cat = None
        if con_catalogo:
            cat = CATALOGO if carpeta is None else _con_carpeta(CATALOGO, nombre, carpeta)
        r = _o_error(editor.plan_enable, CONFIGS[config], _catalogo(catalog, cat), nombre)
        if "plan" in r:
            r = {"plan": _plan(r["plan"])}
        salida.append({"caso": caso, "config": config, "nombre": nombre, "carpeta": carpeta,
                       "catalogo": con_catalogo, **r})
    return salida


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
        "dispositivo": DISPOSITIVO,
        "catalogo": CATALOGO,
        "configs": CONFIGS,
        "mirror_modes": list(editor.MIRROR_MODES),
        "avisos_espejo": {m: editor.mirror_warning(m) for m in [*model.MODES, "inventado"]},
        "avisos_espejo_partidos": {m: editor.aviso_espejo(m) for m in [*model.MODES, "inventado"]},
        "enable": vectores_enable(editor, catalog),
        "remove": vectores_remove(editor, model),
    }
