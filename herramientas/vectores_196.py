"""
vectores_196.py — Lo que dice prdrive de la carpeta que elige cada dispositivo
(prdrive#196, PR Jeremaya25/prdrive#202, que sale en la 0.8.4), para el motor.

El resto del motor está alineado con prdrive 0.7.7 (`vectores.py`), y su
generador no arranca contra la 0.8: entre las dos, prdrive ha cambiado otras
funciones que el motor todavía no refleja. Así que lo de #196 va aparte, en
`engine/src/test/resources/vectores_196.json`, con su procedencia, generado
desde la rama de #202. Cuando el motor pase a la 0.8, esto vuelve a
`vectores.py` y el fichero desaparece.

Lo que se pregunta, y a quién:

- `str.casefold()`, que `common/carpetas.py` usa para comparar carpetas.
- `common/carpetas.py`: `tramos()`, `solapadas()`, `riesgo_espejo()` y `avisos()`
  (`Carpetas.kt`).
- `common/catalog.py`: `DEL_DISPOSITIVO`, `sin_local()`, `con_local()` y
  `diff_keys()` sin `local` (`Catalogo.kt`).
- `ui/pair_editor.py`: `plan_enable()` con la carpeta elegida, con lo que haya
  en el disco (la carpeta y un baseline de otra vez) (`EditarParejas.kt`).
- `install/deploy.py`: `device_config()` y `local_dirs()` con las carpetas,
  `carpeta_sugerida()`, `problema_carpeta()` y `avisos_carpetas()` (`Volumen.kt`).

**El disco.** Cada caso que mira el disco se monta en una carpeta temporal, con
`model.DEVICE_ROOT`, `APP_DIR`, `STATE_DIR`, `FILTERS_DIR` y `RCLONE_CONF`
reenganchados a ella (sin `rclone.conf`: los remotes son corrientes). El
baseline de otra vez se describe por lo que es, no por su nombre: `esta` (los
listados con el prefijo que espera la pareja con esa carpeta), `otra` (los de
otra carpeta) o `rota` (solo `.lst-err`). Así vale aunque el prefijo del
escritorio lleve el `{hash}` del `combine` del entorno y el del teléfono no: cada
lado nombra los listados con el suyo.

Uso: `python herramientas/vectores_196.py <checkout de prdrive en la rama de #202>`.
"""

from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import vectores  # noqa: E402 — vive junto a este fichero: su `cargar()`

DESTINO = Path(__file__).resolve().parent.parent / "engine/src/test/resources/vectores_196.json"

SALE_EN = "0.8.4"
"""La versión de prdrive que publica #202. En la rama, `VERSION` dice todavía la de antes."""

DEFAULTS = {"device_remote": "disp", "remote": "nas",
            "catalog_path": "/prdrive-catalog/remote.toml"}

# El dispositivo de los casos de «Usar aquí»: una bisync en `notas` y una subida
# en `datos/salida`.
DISPOSITIVO = {
    "defaults": dict(DEFAULTS),
    "pair": [
        {"name": "notas", "local": "notas", "remote_path": "/copia/notas"},
        {"name": "subida", "local": "datos/salida", "remote_path": "/copia/salida", "mode": "up"},
    ],
}

# El catálogo: sin `local`, como queda con #196, salvo `vieja`, de un catálogo de
# antes, que lo trae y se ignora.
CATALOGO = {
    "defaults": {"remote": "nas", "catalog_path": "/prdrive-catalog/remote.toml"},
    "daemon": {"pairs": ["notas", "fotos", "web"], "interval_minutes": 15},
    "pair": [
        {"name": "notas", "remote_path": "/copia/notas"},
        {"name": "subida", "remote_path": "/copia/salida", "mode": "up"},
        {"name": "fotos", "remote_path": "/copia/fotos", "include": ["*.jpg"],
         "flags": {"max-delete": 5}, "versions": True},
        {"name": "musica", "remote_path": "/copia/musica", "mode": "down"},
        {"name": "web", "remote_path": "/copia/web", "mode": "up-mirror"},
        {"name": "docs", "remote_path": "/copia/docs", "mode": "down-mirror"},
        {"name": "vieja", "local": "sync-data/vieja", "remote_path": "/copia/vieja"},
    ],
}


# Otros configs del dispositivo para «Usar aquí»: uno con una pareja que el
# catálogo no tiene, uno sin ninguna y dos con un `pair` que no es una lista de
# tablas (prdrive se cae con ellos: el motor tiene que decirlo con un `ConfigError`).
OTROS = {
    "con_una_de_aqui": {**DISPOSITIVO, "pair": DISPOSITIVO["pair"] + [
        {"name": "solo-aqui", "local": "aqui", "remote_path": "/copia/aqui"}]},
    "sin_parejas": {"defaults": dict(DEFAULTS)},
    "pair_tabla": {"defaults": dict(DEFAULTS), "pair": dict(DISPOSITIVO["pair"][0])},
    "pair_con_un_numero": {"defaults": dict(DEFAULTS), "pair": [1, *DISPOSITIVO["pair"]]},
}


def procedencia(prdrive: Path) -> dict:
    version = (prdrive / "VERSION").read_text(encoding="utf-8").strip()
    try:
        commit = subprocess.run(["git", "-C", str(prdrive), "rev-parse", "HEAD"],
                                capture_output=True, text=True, timeout=30,
                                check=True).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        commit = ""
    return {"version_en_la_rama": version, "sale_en": SALE_EN, "pr": 202, "commit": commit}


def _o_error(funcion, *args, **kwargs) -> dict:
    """`{"salida": …}`, o `{"error": mensaje}` si se niega con `ConfigError` o `InstallError`."""
    from common.model import ConfigError   # noqa: PLC0415
    from install import InstallError       # noqa: PLC0415
    try:
        return {"salida": funcion(*args, **kwargs)}
    except (ConfigError, InstallError) as e:
        return {"error": str(e)}


def _o_error_o_rompe(funcion, *args) -> dict:
    """Como [_o_error], y `{"rompe": "AttributeError"}` si prdrive se cae con otra excepción."""
    try:
        return _o_error(funcion, *args)
    except Exception as e:   # noqa: BLE001 — es justo lo que se quiere apuntar
        return {"rompe": type(e).__name__}


class _Disco:
    """Una raíz de dispositivo temporal, con prdrive reenganchado a ella."""

    def __init__(self, model):
        self.model = model

    def __enter__(self) -> Path:
        m = self.model
        self.antes = (m.DEVICE_ROOT, m.APP_DIR, m.STATE_DIR, m.FILTERS_DIR, m.RCLONE_CONF)
        self.tmp = tempfile.TemporaryDirectory()
        raiz = Path(self.tmp.name).resolve() / "volumen"
        raiz.mkdir()
        m.DEVICE_ROOT = raiz
        m.APP_DIR = raiz / ".prdrive"
        m.STATE_DIR = m.APP_DIR / "state"
        m.FILTERS_DIR = m.APP_DIR / "filters"
        m.RCLONE_CONF = m.APP_DIR / "rclone.conf"
        m.STATE_DIR.mkdir(parents=True)
        return raiz

    def __exit__(self, *_):
        m = self.model
        m.DEVICE_ROOT, m.APP_DIR, m.STATE_DIR, m.FILTERS_DIR, m.RCLONE_CONF = self.antes
        self.tmp.cleanup()


def _montar(raiz: Path, ficheros: list[str]) -> None:
    """Crea en `raiz` lo que dice el caso: `x/` es una carpeta, lo demás un fichero."""
    for rel in ficheros:
        ruta = raiz / rel
        if rel.endswith("/"):
            ruta.mkdir(parents=True, exist_ok=True)
        else:
            ruta.parent.mkdir(parents=True, exist_ok=True)
            ruta.write_text("x", encoding="utf-8")


# --- str.casefold() -------------------------------------------------------------

def vectores_casefold() -> list[dict]:
    textos = ["Fotos", "FOTOS/2026", "Árbol", "Straße", "ΣΑΣ", "ﬁcheros", "Ǆ", "mañana", "ÉÈÊ",
              "ẞ", "ı", "İ", "STRAẞE", "Fıles", "İndirilenler"]
    return [{"entrada": t, "salida": t.casefold()} for t in textos]


# --- common/carpetas.py ---------------------------------------------------------

def vectores_carpetas(carpetas, model) -> dict:
    textos = ["notas", " Notas/ ", "a\\b", "./a/./b/", ".", "", "A/B", "a /b", "a/ /b"]
    otras = {"notas": "notas", "datos": "datos/sub", "raiz": ".", "Mayus": "FOTOS"}
    locales = ["notas", "notas/dentro", "datos", "datos/sub/más", "fotos", "otra", ".", "NOTAS"]
    # Las letras que `casefold()` pliega distinto de la mayúscula y la minúscula.
    raras = {"a": "STRAẞE", "b": "Fıles", "c": "İndirilenler"}
    # Cada escenario de espejo: lo que hay en el disco y la carpeta que se mira.
    escenarios = [
        ("sin carpeta", [], "web"),
        ("vacía", ["web/"], "web"),
        ("solo una subcarpeta", ["web/sub/"], "web"),
        ("con un fichero", ["web/index.html"], "web"),
        ("un fichero, no una carpeta", ["web"], "web"),
        # La raíz entera: siempre tiene `.prdrive/` (el `_Disco` la crea).
        ("la raíz", [], "."),
    ]
    riesgos, avisos = [], []
    for caso, disco, local in escenarios:
        with _Disco(model) as raiz:
            _montar(raiz, disco)
            for modo in [*model.MODES, "inventado"]:
                riesgos.append({"caso": caso, "disco": disco, "local": local, "modo": modo,
                                "salida": carpetas.riesgo_espejo(modo, raiz / local)})
                avisos.append({"caso": caso, "disco": disco, "nombre": "web", "modo": modo,
                               "local": local, "otras": otras,
                               "salida": carpetas.avisos("web", modo, local, raiz, otras)})
    with _Disco(model) as raiz:
        for local in locales:
            avisos.append({"caso": "solo las otras", "disco": [], "nombre": "nueva",
                           "modo": "bisync", "local": local, "otras": otras,
                           "salida": carpetas.avisos("nueva", "bisync", local, raiz, otras)})
    return {
        "tramos": [{"entrada": t, "salida": list(carpetas.tramos(t))} for t in textos],
        "solapadas": [{"otras": o, "local": loc, "salida": carpetas.solapadas(o, loc)}
                      for o, locs in ((otras, locales), (raras, ["Straße", "files", "indirilenler", "fıles"]))
                      for loc in locs],
        "riesgo_espejo": riesgos,
        "avisos": avisos,
    }


# --- common/catalog.py ----------------------------------------------------------

def vectores_catalogo(catalog) -> dict:
    import tomllib   # noqa: PLC0415
    texto = vectores.CATALOGO_TEXTO
    raw = tomllib.loads(texto)
    raros = [{"pair": "no es una lista"}, {"pair": [1, {"name": "a", "local": "b"}]}, {}]
    parejas = CATALOGO["pair"]
    con_local = {**parejas[0], "local": "aquí"}
    return {
        "del_dispositivo": list(catalog.DEL_DISPOSITIVO),
        # El catálogo de los vectores de 0.7.7, que trae `local`: lo que queda al leerlo.
        "texto": texto,
        "sin_local": catalog.sin_local(raw),
        "con_local": list(catalog.con_local(raw)),
        "raros": [{"raw": r, "sin_local": catalog.sin_local(r), "con_local": list(catalog.con_local(r))}
                  for r in raros],
        "diff_keys": [{"a": a, "b": b, "salida": list(catalog.diff_keys(a, b))}
                      for a, b in ((parejas[0], parejas[2]), (parejas[0], parejas[0]),
                                   (parejas[0], con_local), (None, con_local), (con_local, None),
                                   ({"x": 1}, {"x": 1.0}), ({"local": "a"}, {"local": "b"}))],
    }


# --- ui/pair_editor.py: plan_enable() -------------------------------------------

def _plan(plan) -> dict:
    return {"raw": plan.raw, "consequences": list(plan.consequences),
            "warnings": list(plan.warnings), "shelve": list(plan.shelve)}


def _baseline(bisync, model, raiz: Path, raw: dict, nombre: str, tipo: str) -> None:
    """Deja en `state/<nombre>/` el baseline de otra vez que dice `tipo` (ver la cabecera)."""
    workdir = model.STATE_DIR / nombre
    workdir.mkdir(parents=True, exist_ok=True)
    if tipo == "vacío":
        return
    if tipo == "rota":
        (workdir / "x.path1.lst-err").write_text("x", encoding="utf-8")
        return
    if tipo == "otra":
        prefijo = "disp_otra_carpeta..nas_copia_otra"
    else:
        config = model.parse_config(raw)
        prefijo = bisync.expected_prefix(next(p for p in config.pairs if p.name == nombre))
    for sufijo in (bisync.PATH1_SUFFIX, bisync.PATH2_SUFFIX):
        (workdir / f"{prefijo}{sufijo}").write_text("x", encoding="utf-8")


def vectores_enable(editor, catalog, bisync, model) -> list[dict]:
    """`plan_enable()` con la carpeta elegida y lo que haya en el disco.

    `disco` es lo que hay en la raíz antes de pensar el plan; `baseline`, el de
    otra vez en `state/<pareja>/` (`esta`, `otra`, `rota`, `vacío` o nada).
    """
    casos = [
        ("bisync en una carpeta nueva", "fotos", "Fotos/2026", [], None),
        ("bisync en una carpeta vacía", "fotos", "fotos", ["fotos/"], None),
        ("bisync en una carpeta con ficheros", "fotos", "fotos", ["fotos/a.jpg"], None),
        ("bisync en la raíz", "fotos", ".", [], None),
        ("con espacios alrededor", "fotos", " fotos ", [], None),
        ("espejo de subida en la raíz", "web", ".", [], None),
        ("de bajada", "musica", "musica", [], None),
        ("espejo de subida sin carpeta", "web", "web", [], None),
        ("espejo de subida vacío", "web", "web", ["web/"], None),
        ("espejo de subida con ficheros", "web", "web", ["web/index.html"], None),
        ("espejo de bajada con ficheros", "docs", "docs", ["docs/a.txt"], None),
        ("espejo de bajada nuevo", "docs", "docs", [], None),
        ("en la carpeta de otra", "fotos", "notas", [], None),
        ("dentro de otra", "fotos", "datos/salida/fotos", [], None),
        ("alrededor de otra", "fotos", "datos", [], None),
        ("un catálogo de antes, con local", "vieja", "vieja", [], None),
        ("la referencia de antes, de esta carpeta", "fotos", "fotos", ["fotos/a.jpg"], "esta"),
        ("la referencia de antes, sin su carpeta", "fotos", "fotos", [], "esta"),
        ("la referencia de antes, con su carpeta vacía", "fotos", "fotos", ["fotos/"], "esta"),
        ("la referencia de antes, de otra carpeta", "fotos", "fotos", [], "otra"),
        ("la referencia de antes, rota", "fotos", "fotos", [], "rota"),
        ("un state vacío", "fotos", "fotos", [], "vacío"),
        ("una que no es bisync, con state", "musica", "musica", [], "otra"),
        ("una carpeta que sale del dispositivo", "fotos", "../fuera", [], None),
        ("sin carpeta", "fotos", "  ", [], None),
        ("una que no está en el catálogo", "inventada", "x", [], None),
        ("una que ya está aquí", "notas", "notas2", [], None),
    ]
    cat = catalog.Catalog(raw=catalog.sin_local(CATALOGO), text="", source="remote",
                          stamp="2026-11-02 10:00", endpoint="nas:/prdrive-catalog/remote.toml",
                          con_local=catalog.con_local(CATALOGO))
    salida = []
    for caso, nombre, local, disco, baseline in casos:
        with _Disco(model) as raiz:
            _montar(raiz, disco)
            if baseline is not None:
                # Con la pareja ya en el config, que es como la dejó la vez anterior.
                entrada = catalog.find_pair(cat, nombre) or {}
                antes = copy.deepcopy(DISPOSITIVO)
                antes["pair"].append({"name": nombre, "local": local,
                                      **{k: v for k, v in entrada.items() if k != "name"}})
                _baseline(bisync, model, raiz, antes, nombre, baseline)
            r = _o_error(editor.plan_enable, DISPOSITIVO, cat, nombre, local)
            if "salida" in r:
                r = {"plan": _plan(r["salida"])}
        salida.append({"caso": caso, "nombre": nombre, "local": local, "disco": disco,
                       "baseline": baseline, **r})
    salida.append({"caso": "sin catálogo", "nombre": "fotos", "local": "fotos", "disco": [],
                   "baseline": None, "sin_catalogo": True,
                   **_o_error(editor.plan_enable, DISPOSITIVO, None, "fotos", "fotos")})
    # Con otro config del dispositivo ([OTROS]): `config` lo nombra.
    otros = [
        ("una de aquí que el catálogo no tiene", "con_una_de_aqui", "solo-aqui"),
        ("un dispositivo sin parejas", "sin_parejas", "fotos"),
        ("un pair que es una tabla", "pair_tabla", "fotos"),
        ("un pair que es una tabla, y una que no está en el catálogo", "pair_tabla", "inventada"),
        ("un pair con algo que no es una tabla", "pair_con_un_numero", "fotos"),
    ]
    for caso, config, nombre in otros:
        with _Disco(model):
            r = _o_error_o_rompe(editor.plan_enable, OTROS[config], cat, nombre, nombre)
            if "salida" in r:
                r = {"plan": _plan(r["salida"])}
        salida.append({"caso": caso, "config": config, "nombre": nombre, "local": nombre,
                       "disco": [], "baseline": None, **r})
    return salida


# --- install/deploy.py ----------------------------------------------------------

def vectores_deploy(deploy, remote, model) -> dict:
    cat = remote.Catalog(CATALOGO, "# El manual del catálogo.\n", "nas:/prdrive-catalog/remote.toml")
    casos = {
        "dos parejas": (["notas", "fotos"], {"notas": "notas", "fotos": " Fotos/2026 "}, ""),
        "con la ruta del catálogo": (["fotos"], {"fotos": "fotos"}, "/otro/remote.toml"),
        "todas": (list(cat.names), {n: n for n in cat.names}, ""),
        "un catálogo de antes": (["vieja"], {"vieja": "otra"}, ""),
        "rutas sin normalizar": (["notas", "fotos"], {"notas": "./Fotos//2026", "fotos": "a/./b/"}, ""),
        "la raíz": (["notas"], {"notas": "."}, ""),
        "sin daemon": (["musica"], {"musica": "musica"}, ""),
    }
    salida = []
    for caso, (elegidas, locales, ruta) in casos.items():
        salida.append({
            "caso": caso, "elegidas": elegidas, "locales": locales, "catalog_path": ruta,
            **_o_error(deploy.device_config, cat, elegidas, ruta, locales),
            "local_dirs": [p.as_posix() for p in deploy.local_dirs(cat, elegidas, locales)],
        })
    errores = {
        "ninguna": ([], {}),
        "una que no existe": (["notas", "inventada"], {"notas": "n", "inventada": "i"}),
        "sin carpeta": (["notas", "fotos", "web"], {"notas": "notas", "fotos": "  "}),
        "sin locales": (["notas"], None),
    }
    for caso, (elegidas, locales) in errores.items():
        salida.append({"caso": caso, "elegidas": elegidas, "locales": locales, "catalog_path": "",
                       **_o_error(deploy.device_config, cat, elegidas, "", locales)})
    textos = ["notas", "", "   ", None, "../fuera", ".prdrive", ".keychain/x", "C:/x", " a/b "]
    with _Disco(model) as raiz:
        _montar(raiz, ["web/", "docs/a.txt"])
        locales = {"notas": "notas", "web": "web", "docs": "docs", "fotos": "notas/fotos",
                   "musica": "."}
        avisos = deploy.avisos_carpetas(raiz, cat, ["notas", "web", "docs", "fotos", "musica"], locales)
    return {
        "catalogo": CATALOGO,
        "device_config": salida,
        "carpeta_sugerida": [{"pareja": p, "salida": deploy.carpeta_sugerida(p)}
                             for p in [*CATALOGO["pair"][:2], {"remote_path": "/x"}, {"name": None}]],
        "problema_carpeta": [{"entrada": t, "salida": deploy.problema_carpeta(t)} for t in textos],
        "avisos_carpetas": {"disco": ["web/", "docs/a.txt"],
                            "elegidas": ["notas", "web", "docs", "fotos", "musica"],
                            "locales": locales, "salida": avisos},
    }


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__.strip().splitlines()[0])
        print("\nUso: python herramientas/vectores_196.py <checkout de prdrive en la rama de #202>")
        return 2
    prdrive = Path(sys.argv[1]).expanduser().resolve()
    if not (prdrive / "common" / "carpetas.py").is_file():
        print(f"No parece un checkout de prdrive con #202 (falta common/carpetas.py): {prdrive}")
        return 2
    bisync, _config_file, model, _pairing = vectores.cargar(prdrive)
    from common import carpetas, catalog   # noqa: PLC0415
    from install import deploy, remote     # noqa: PLC0415
    from ui import pair_editor             # noqa: PLC0415
    datos = {
        "_generado_por": "herramientas/vectores_196.py",
        "_no_editar": "Se regenera desde la rama de prdrive#202 citada en 'prdrive'.",
        "prdrive": procedencia(prdrive),
        "casefold": vectores_casefold(),
        "carpetas": vectores_carpetas(carpetas, model),
        "catalogo": vectores_catalogo(catalog),
        "dispositivo": DISPOSITIVO,
        "otros": OTROS,
        "catalogo_196": CATALOGO,
        "enable": vectores_enable(pair_editor, catalog, bisync, model),
        "deploy": vectores_deploy(deploy, remote, model),
    }
    DESTINO.parent.mkdir(parents=True, exist_ok=True)
    DESTINO.write_text(json.dumps(datos, indent=2, ensure_ascii=False) + "\n", encoding="utf-8",
                       newline="\n")
    print(f"{DESTINO.name}: prdrive#202 ({datos['prdrive']['commit'][:8] or 'sin commit'}), "
          f"sale en {SALE_EN}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
