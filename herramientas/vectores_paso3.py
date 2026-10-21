"""
vectores_paso3.py — Lo que el motor necesita de `sync.py`, `common/orden.py` e
`install/` para hacer una pasada y montar el volumen.

Lo importa `vectores.py` (que ya ha preparado prdrive: raíz POSIX falsa, carpeta
del programa `.prdrive`) y mete lo que devuelve [vectores] en el mismo JSON.
Va aparte solo para que `vectores.py` no crezca sin fin.
"""

from __future__ import annotations

import tempfile
from pathlib import Path, PurePosixPath

SELLO_VERSIONES = "~20261001-110101"
"""El sufijo de las versiones de una pasada (`sync.py`: `~%Y%m%d-%H%M%S`)."""

CONF = "/volumen/.prdrive/rclone.conf"
LOG = "/tmp/rclone-sync-x.log"


def _config(base: dict) -> dict:
    """La config de `vectores.py` con dos parejas más: una que pisa en sus flags y
    en sus `extra_flags` lo que la pasada quita o pone, y un espejo de bajada."""
    return {**base, "pair": base["pair"] + [
        {"name": "extras", "local": "sync-data/extras", "remote_path": "/copia/extras",
         "flags": {"resync_mode": "older", "max_delete": 9, "conflict_loser": "pathname"},
         "extra_flags": ["--max-delete=7", "--resync-mode", "newer", "--fast-list"],
         "versions": True},
        {"name": "bajada", "local": "sync-data/bajada", "remote_path": "/copia/bajada",
         "mode": "down-mirror", "extra_flags": ["--max-delete", "3", "--checksum"]},
    ]}


def vectores_orden(model, bisync, orden, base: dict) -> dict:
    """`orden.tramos()`: la orden de rclone de cada pareja, trozo a trozo y con
    quién pone cada uno, en las pasadas que importan."""
    config = model.parse_config(_config(base))
    pasadas = {
        "normal": {},
        "simulacro": {"simulacro": True},
        "resync": {"resync": True},
        "forzada": {"forzar": True},
        "simulacro forzado": {"simulacro": True, "forzar": True},
        "resync forzado": {"resync": True, "forzar": True},
    }
    salida = []
    for p in config.pairs:
        filtros = bisync.ruta_filtros(p)
        for nombre, kw in pasadas.items():
            tramos = orden.tramos(p, binario="rclone", filtros=filtros, config=CONF,
                                  logfile=LOG, sello=SELLO_VERSIONES, **kw)
            salida.append({
                "pareja": p.name, "pasada": nombre, "kw": kw,
                "filtros": str(filtros) if filtros else None,
                "workdir": str(p.workdir),
                "tramos": [{"args": list(t.args), "origen": t.origen, "nombre": t.nombre}
                           for t in tramos],
                "pasada_forzada": orden.pasada_forzada(p, bool(kw.get("forzar")),
                                                       bool(kw.get("resync"))),
                "max_delete_efectivo": orden.max_delete_efectivo(p),
            })
    return {"config": _config(base), "conf": CONF, "log": LOG, "sello": SELLO_VERSIONES,
            "origenes": {"base": model.ORIGEN_BASE, "modo": model.ORIGEN_MODO,
                         "defaults": model.ORIGEN_DEFAULTS, "pareja": model.ORIGEN_PAREJA,
                         "prdrive": model.ORIGEN_PRDRIVE, "patrones": model.ORIGEN_PATRONES,
                         "extra": model.ORIGEN_EXTRA},
            "pasadas": salida}


TEXTOS_DE_FALLO = {
    "sin baseline": "2026/10/01 ERROR : Bisync critical error: cannot find prior Path1 or "
                    "Path2 listings, likely due to critical error on prior run",
    "filtros cambiados": "ERROR : Bisync critical error: filters file has changed",
    "filtros sin md5": "ERROR : filters file md5 hash not found (must run --resync)",
    "hay que hacer resync": "Bisync aborted. Must run --resync to recover.",
    "freno de borrados": "ERROR : Safety abort: too many deletes (>25%, 5 of 8) on Path1 "
                         "\"x\". Run with --force if desired.\nFailed to bisync: too many "
                         "deletes",
    "todos cambiados": "ERROR : Safety abort: all files were changed on Path2 \"y\".",
    "freno de espejo": "ERROR : x: Couldn't delete: --max-delete threshold reached",
    "bloqueado": "ERROR : a.txt: Access is denied.",
    "lock": "ERROR : prior lock file found: /x/y.lck",
    "known_hosts": "ERROR : couldn't parse known_hosts_file: open keys/known_hosts",
    "sin red": "Failed to create file system: dial tcp: lookup nas: no such host",
    "sin red en mayúsculas": "ERROR: Connection REFUSED by peer",
    "fs sin red": "Failed to create file system for \"nas:/x\": couldn't connect SSH: "
                  "ssh: handshake failed",
    "fs a secas": "Failed to create file system for \"nas:/x\": didn't find section in "
                  "config file",
    "flag desconocido": "Error: unknown flag: --inventado",
    "valor inválido": "Error: invalid argument \"new\" for \"--conflict-resolve\" flag",
    "nada que ver": "algo que no casa con ninguna aguja",
    "dos a la vez": "prior lock file found\ncannot find prior Path1 or Path2 listings",
}


def vectores_errores(sync) -> dict:
    """`KNOWN_ERRORS`: qué explicación le toca a cada log. Las agujas pueden ser
    funciones (los frenos, la red), así que se apunta lo que elige `sync.py` —
    gana la primera que casa— sobre textos de ejemplo, más las agujas que son
    texto, en su orden."""
    def explicar(texto: str) -> str | None:
        for aguja, explicacion in sync.KNOWN_ERRORS:
            if aguja(texto) if callable(aguja) else aguja in texto:
                return explicacion
        return None
    return {
        "agujas_de_texto": [[a, e] for a, e in sync.KNOWN_ERRORS if not callable(a)],
        "explicaciones": [e for _, e in sync.KNOWN_ERRORS],
        "casos": [{"caso": c, "texto": t, "explicacion": explicar(t)}
                  for c, t in TEXTOS_DE_FALLO.items()],
        "log_tail_lines": sync.LOG_TAIL_LINES,
        "logs_por_pareja": sync.LOGS_POR_PAREJA,
        "conflicts_shown": sync.CONFLICTS_SHOWN,
    }


def vectores_logs(sync, model) -> dict:
    """`podar_logs()`: qué logs de una pareja sobreviven, por el sello del nombre."""
    nombres = ([f"notas_202610{d:02d}_120000.log" for d in range(1, 24)]
               + ["notas_20261001_120000_2.log", "notas_20261001_120000_10.log",
                  "notas_b_20261001_120000.log", "notas-20261001_120000.log",
                  "otra_20261001_120000.log", "notas_2026101_120000.log"])
    conservar = "notas_20250101_000000.log"
    viejo = model.LOG_DIR
    try:
        with tempfile.TemporaryDirectory() as tmp:
            model.LOG_DIR = Path(tmp)
            for n in nombres + [conservar]:
                (Path(tmp) / n).write_text("x", encoding="utf-8")
            sync.podar_logs("notas", Path(tmp) / conservar)
            quedan = sorted(p.name for p in Path(tmp).iterdir())
    finally:
        model.LOG_DIR = viejo
    return {"nombres": nombres, "conservar": conservar, "quedan": quedan}


def vectores_volumen(deploy, remote, model, base: dict) -> dict:
    """`deploy.device_config()`: el config del dispositivo que sale del catálogo y
    de las parejas elegidas, y las carpetas que hay que crear."""
    catalogo_raw = {**base, "daemon": {"pairs": ["notas", "fotos", "subida"],
                                       "interval_minutes": 15}}
    catalogo_raw["defaults"] = {k: v for k, v in base["defaults"].items()
                                if k != "device_remote"}
    cat = remote.Catalog(catalogo_raw, "# El manual del catálogo.\n",
                         "nas:/prdrive-catalog/remote.toml")
    casos = {
        "dos parejas": (["notas", "todo"], ""),
        "con la ruta del catálogo": (["fotos"], "/otro/remote.toml"),
        "ninguna del daemon": (["espejo"], ""),
        "todas": (list(cat.names), ""),
    }
    salida = []
    for caso, (elegidas, ruta) in casos.items():
        salida.append({
            "caso": caso, "elegidas": elegidas, "catalog_path": ruta,
            "config": deploy.device_config(cat, elegidas, ruta),
            "local_dirs": [p.as_posix() for p in deploy.local_dirs(cat, elegidas)],
            "resync_targets": deploy.resync_targets(cat, elegidas),
            "mirror_pairs": deploy.mirror_pairs(cat, elegidas),
        })
    errores = {}
    for caso, elegidas in {"ninguna": [], "una que no existe": ["notas", "inventada"]}.items():
        try:
            deploy.device_config(cat, elegidas)
            errores[caso] = None
        except Exception as e:   # noqa: BLE001 — InstallError
            errores[caso] = str(e)
    return {"catalogo": catalogo_raw, "casos": salida, "errores": errores,
            "app_subdir": deploy.APP_SUBDIR}


def vectores_rclone_conf(profile) -> dict:
    """`render_conf()`: lo que no se puede escribir en un `rclone.conf`."""
    nombres = ["nas", "mi nas", "nas,x", "-nas", "nas ", "a[b", "n\na", "ñas", "C"]
    lineas = [("type", "sftp"), ("host", "ejemplo.invalid"), ("ho st", "x"),
              ("host", "a\nb"), ("host", "a\rb"), ("host", "a\x0bb"), ("host", "a\x85b"),
              ("host", "a b"), ("clave-con-guion", "x"), ("pass", "con = igual")]
    def linea(clave, valor):
        try:
            return {"salida": profile._linea(clave, valor)}
        except Exception as e:   # noqa: BLE001 — InstallError
            return {"error": str(e)}
    return {
        "nombres": [{"nombre": n, "vale": bool(profile.NOMBRE_RCLONE.fullmatch(n))}
                    for n in nombres],
        "lineas": [{"clave": c, "valor": v, **linea(c, v)} for c, v in lineas],
        "opciones_que_ejecutan": sorted(profile.OPCIONES_QUE_EJECUTAN),
    }


def vectores(model, bisync, base: dict) -> dict:
    import sync   # noqa: PLC0415
    from common import orden   # noqa: PLC0415
    from install import deploy, profile, remote   # noqa: PLC0415
    return {
        "orden": vectores_orden(model, bisync, orden, base),
        "errores": vectores_errores(sync),
        "logs": vectores_logs(sync, model),
        "volumen": vectores_volumen(deploy, remote, model, base),
        "rclone_conf": vectores_rclone_conf(profile),
    }
