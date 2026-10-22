"""
vectores_actualizacion.py — Lo que dice `common/update.py` de prdrive, para
`engine/.../Actualizacion.kt`.

`Actualizacion.kt` es el espejo de la parte de `update.py` que mira y compara:
`parse_version`, `is_newer`, `pagina_segura`, `_parse_release`, la caché
(`_leer_cache`, `_escribir_cache`), `check`, `pending` y `veredicto`. Lo que trae
la versión nueva no se parece (el escritorio baja el zip del código; el teléfono,
el APK firmado), así que eso no sale de aquí.

La única constante distinta es el repositorio: el de la app. Para preguntarle a
`update.py` con él, se le cambia `REPO` (y lo que sale de él) mientras dura esto,
y se le devuelve el suyo al acabar.

La respuesta de GitHub de los casos es la de verdad, guardada en
`engine/src/test/resources/release_github.json` (la de la v0.1.0, sin los datos
del autor):

    gh api repos/Jeremaya25/prdrive-app/releases/latest

El reloj se para: `datetime.now()` de `update.py` y `store.stamp()` dan siempre
[AHORA] y [SELLO], para que la edad de la caché salga igual en cualquier momento.
"""

from __future__ import annotations

import json
import tempfile
from datetime import datetime, timedelta
from pathlib import Path

REPO_APP = "Jeremaya25/prdrive-app"
RESPUESTA = Path(__file__).resolve().parent.parent / "engine/src/test/resources/release_github.json"

AHORA = datetime(2026, 10, 22, 12, 0, 0)
FORMATO = "%Y-%m-%d %H:%M:%S"
SELLO = f"{AHORA - timedelta(hours=1):{FORMATO}}"

# Lo que `parse_version` tiene que leer igual. Con las cifras de otros alfabetos
# (las lee `int()`), una de fuera del plano básico (dos unidades UTF-16 en
# Kotlin) y un `²`, que `isdigit()` da por cifra y `int()` no sabe leer.
VERSIONES = [
    "", "0.1.0", "v0.1.0", "V0.1.0", "vv1.2", "vV1.2", " v1.2.3 \n", " 1.0 ",
    "0.0.10", "0.0.9", "1.2.0-rc1", "1.2.0", "1.2", "1", "1.2.3.4", "1..2", ".", "..",
    "abc", "v", "1a.2b", "a1.2", "007.010", "99999999999999999999999.1", "1.2 .3",
    "١٢.٣", "１.２", "\U0001d7cf.\U0001d7d0", "1.²", "²",
    "1.2²",
]

PARES = [
    ("0.0.10", "0.0.9"), ("0.0.9", "0.0.10"), ("1.2", "1.2.0"), ("1.2.0", "1.2"),
    ("1.2.1", "1.2"), ("", "1.0"), ("1.0", ""), ("", ""), ("v0.2.0", "0.1.0"),
    ("0.1.0", "0.1.0"), ("1.2.0", "1.2.0-rc1"), ("1.2.0-rc2", "1.2.0-rc1"),
    ("abc", "0.0.1"), ("0.0.1", "abc"), ("١.٠", "0.9"), ("2", "10"),
    ("10", "9.99"), ("0.10.0", "0.9.9"), ("1.0.0.1", "1"), ("v1", "V1.0"),
    ("1.²", "1.0"),
]

URLS = [
    "", "https://github.com/Jeremaya25/prdrive-app/releases/tag/v0.1.0",
    "https://github.com/Jeremaya25/prdrive-app/", "https://github.com/Jeremaya25/prdrive-app",
    "https://github.com/Jeremaya25/prdrive/releases/tag/v0.7.7",
    "https://github.com/Jeremaya25/prdrive-appx/releases", "http://github.com/Jeremaya25/prdrive-app/x",
    "https://evil.example/https://github.com/Jeremaya25/prdrive-app/", "file:///etc/passwd",
]


def _respuesta() -> dict:
    return json.loads(RESPUESTA.read_text(encoding="utf-8"))


def _rompe(funcion, *args):
    """`(valor, None)` si no lanza; `(None, nombre de la excepción)` si lanza."""
    try:
        return funcion(*args), None
    except Exception as e:   # noqa: BLE001 — es justo lo que se quiere apuntar
        return None, type(e).__name__


def _release(rel) -> dict | None:
    return None if rel is None else {
        "tag": rel.tag, "version": rel.version, "name": rel.name, "url": rel.url,
        "published": rel.published, "notes": rel.notes}


def _releases_crudas() -> list:
    """Lo que puede contestar GitHub: la respuesta de verdad, y lo raro."""
    real = _respuesta()
    sin_tag = dict(real)
    del sin_tag["tag_name"]
    return [
        real,
        sin_tag,
        {},
        {"tag_name": "  "},
        {"tag_name": None, "name": "v1"},
        {"tag_name": " v0.2.0 ", "name": "", "body": "  notas \n", "html_url": "https://evil.example/x",
         "published_at": None},
        {"tag_name": "V1.0", "name": "  Título  ", "body": "\U0001f600" * 4001,
         "html_url": "https://github.com/Jeremaya25/prdrive-app/releases/tag/V1.0",
         "published_at": "2026-10-22T09:48:59Z"},
        {"tag_name": "vv2", "body": "x" * 3999 + "éé"},
        {"tag_name": 2, "name": ["a", 1], "body": {"x": 1.5}, "html_url": 7},
        {"tag_name": True, "name": False, "body": 0},
        {"tag_name": 1.0, "name": 0.1},
        [],
        "texto",
        None,
    ]


def _textos_de_cache(update) -> list[str]:
    """Cachés que puede encontrarse `_leer_cache`: buenas, viejas, del futuro y rotas."""
    def texto(**datos) -> str:
        return json.dumps(datos, ensure_ascii=False, indent=1)

    ayer = f"{AHORA - timedelta(days=1):{FORMATO}}"
    manana = f"{AHORA + timedelta(minutes=5):{FORMATO}}"
    return [
        texto(checked=SELLO, tag="v0.2.0", version="0.2.0", name="v0.2.0",
              url="https://github.com/Jeremaya25/prdrive-app/releases/tag/v0.2.0",
              published="2026-10-22T09:48:59Z", notes="Notas"),
        texto(tag="v0.2.0", version="0.2.0"),
        texto(checked="ayer", tag="v0.2.0"),
        texto(checked=None, tag="v0.2.0"),
        texto(checked=20261022, tag="v0.2.0"),
        texto(checked=manana, tag="v0.2.0", version=" 0.2.0 "),
        texto(checked=ayer, tag="v0.2.0", url="https://evil.example/"),
        texto(checked="2026-10-22 3:4:5", tag="v0.2.0"),
        texto(checked=SELLO, tag="", version="0.2.0"),
        texto(checked=SELLO, tag=1, version=2.5, name=None, notes=["a"], published=False),
        texto(checked=SELLO, tag=" v1 ", name="", version=None),
        "[1, 2]",
        "no es json",
        "",
    ]


def _check(update, store, escenarios: list[dict]) -> list[dict]:
    """`check()` en cada escenario, con una red de mentira."""
    salida = []
    for esc in escenarios:
        llamadas: list[str] = []

        def fetch(url: str, timeout: int, esc=esc, llamadas=llamadas) -> bytes:
            llamadas.append(url)
            if "error" in esc:
                raise OSError(esc["error"])
            return esc["respuesta"].encode("utf-8")

        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp) / "update.json"
            if esc.get("cache") is not None:
                cache.write_text(esc["cache"], encoding="utf-8")
            viejo = update.fetch
            update.fetch = fetch
            try:
                rel, motivo = update.check(force=esc.get("force", False), cache=cache)
            finally:
                update.fetch = viejo
            despues = cache.read_text(encoding="utf-8") if cache.exists() else None
        caso = {k: v for k, v in esc.items() if k != "nombre"}
        caso.update({"nombre": esc["nombre"], "release": _release(rel), "motivo": motivo,
                     "llamadas": llamadas, "cache_despues": despues})
        salida.append(caso)
    return salida


def _escenarios_check(update) -> list[dict]:
    real = RESPUESTA.read_text(encoding="utf-8")
    fresca = _textos_de_cache(update)[0]
    vieja = json.dumps({"checked": f"{AHORA - timedelta(days=2):{FORMATO}}", "tag": "v0.0.9",
                        "version": "0.0.9"}, ensure_ascii=False, indent=1)
    futura = json.dumps({"checked": f"{AHORA + timedelta(hours=1):{FORMATO}}", "tag": "v0.0.9",
                         "version": "0.0.9"}, ensure_ascii=False, indent=1)
    return [
        {"nombre": "caché fresca: no sale a la red", "cache": fresca, "respuesta": real},
        {"nombre": "caché fresca y forzado: sale", "cache": fresca, "force": True, "respuesta": real},
        {"nombre": "caché vieja y sin red: lo último que se supo", "cache": vieja, "error": "sin red"},
        {"nombre": "sin caché y sin red", "error": "sin red"},
        {"nombre": "sin caché: pregunta y guarda", "respuesta": real},
        {"nombre": "caché del futuro: pregunta", "cache": futura, "respuesta": real},
        {"nombre": "respuesta sin tag_name", "respuesta": '{"name": "x"}'},
        {"nombre": "caché vieja y respuesta sin tag_name", "cache": vieja, "respuesta": "{}"},
    ]


def vectores(update, store) -> dict:
    """Todo lo de `update.py` que reproduce `Actualizacion.kt`, con el repositorio de la app."""
    viejos = (update.REPO, update.API_LATEST, update.PAGINA, update.USER_AGENT,
              update.datetime, store.stamp)

    class _Parado(datetime):
        @classmethod
        def now(cls, tz=None):   # noqa: ARG003 — la firma es la de datetime
            return AHORA

    update.REPO = REPO_APP
    update.API_LATEST = viejos[1].replace(viejos[0], REPO_APP)
    update.PAGINA = viejos[2].replace(viejos[0], REPO_APP)
    update.USER_AGENT = viejos[3].replace(viejos[0], REPO_APP)
    update.datetime = _Parado
    store.stamp = lambda: SELLO
    try:
        version = []
        for texto in VERSIONES:
            tupla, rompe = _rompe(update.parse_version, texto)
            version.append({"texto": texto, "tupla": None if tupla is None else [str(n) for n in tupla],
                            "rompe": rompe})
        nueva = []
        for a, b in PARES:
            valor, rompe = _rompe(update.is_newer, a, b)
            nueva.append({"nueva": a, "actual": b, "es": valor, "rompe": rompe})

        releases = []
        for crudo in _releases_crudas():
            try:
                releases.append({"crudo": crudo, "release": _release(update._parse_release(crudo)),
                                 "error": None, "rompe": None})
            except ValueError as e:
                releases.append({"crudo": crudo, "release": None, "error": str(e), "rompe": None})
            except Exception as e:   # noqa: BLE001
                releases.append({"crudo": crudo, "release": None, "error": None,
                                 "rompe": type(e).__name__})

        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp) / "update.json"
            real = update._parse_release(_respuesta())
            update._escribir_cache(real, cache)
            escrita = {"release": _release(real), "texto": cache.read_text(encoding="utf-8")}
            leidas = []
            for texto in _textos_de_cache(update):
                cache.write_text(texto, encoding="utf-8")
                rel, edad = update._leer_cache(cache)
                pendiente = {v: _release(update.pending(cache=cache, root=_raiz(tmp, v)))
                             for v in ("", "0.1.0", "0.2.0", "1.0")}
                leidas.append({"texto": texto, "release": _release(rel), "edad": edad,
                               "pending": pendiente})

        veredictos = []
        rel_nueva = update.Release(tag="v0.2.0", version="0.2.0", name="v0.2.0", url=update.PAGINA,
                                   published="")
        for rel, motivo, actual in [
            (None, None, "0.1.0"), (rel_nueva, None, "0.1.0"), (rel_nueva, None, "0.2.0"),
            (rel_nueva, None, ""), (rel_nueva, None, "0.3.0"),
            (rel_nueva, "No he podido: sin red\nSe enseña lo último que se supo.", "0.1.0"),
            (None, "No he podido: sin red", "0.1.0"), (rel_nueva, "", "0.1.0"),
            (rel_nueva, "\n", "0.1.0"),
        ]:
            veredictos.append({"release": _release(rel), "motivo": motivo, "actual": actual,
                               "dice": update.veredicto(rel, motivo, actual)})

        return {
            "constantes": {
                "REPO": update.REPO, "API_LATEST": update.API_LATEST, "PAGINA": update.PAGINA,
                "USER_AGENT": update.USER_AGENT, "TIMEOUT_API": update.TIMEOUT_API,
                "TIMEOUT_ZIP": update.TIMEOUT_ZIP, "CACHE_HORAS": update.CACHE_HORAS,
                "NOTAS_MAX": update.NOTAS_MAX,
            },
            "ahora": f"{AHORA:{FORMATO}}",
            "sello": SELLO,
            "parse_version": version,
            "is_newer": nueva,
            "pagina_segura": [{"url": u, "segura": update.pagina_segura(u)} for u in URLS],
            "parse_release": releases,
            "cache_escrita": escrita,
            "cache_leida": leidas,
            "check": _check(update, store, _escenarios_check(update)),
            "veredicto": veredictos,
        }
    finally:
        (update.REPO, update.API_LATEST, update.PAGINA, update.USER_AGENT,
         update.datetime, store.stamp) = viejos


def _raiz(tmp: str, version: str) -> Path:
    """Una carpeta con un `VERSION` que dice `version`, para `pending(root=…)`."""
    raiz = Path(tmp) / f"raiz-{version or 'sin'}"
    raiz.mkdir(exist_ok=True)
    if version:
        (raiz / "VERSION").write_text(version, encoding="utf-8")
    return raiz
