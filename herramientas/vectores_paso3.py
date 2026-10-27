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
        # El tope de bisync donde la línea de órdenes y el RPC no coinciden: sin
        # él, negativo y por encima de 100 (cmd/bisync/cmd.go, applyContext).
        {"name": "sin-tope", "local": "sync-data/sin-tope", "remote_path": "/copia/sin-tope",
         "flags": {"max_delete": False}},
        {"name": "tope-negativo", "local": "sync-data/tope-negativo",
         "remote_path": "/copia/tope-negativo", "flags": {"max_delete": -1}},
        {"name": "tope-alto", "local": "sync-data/tope-alto", "remote_path": "/copia/tope-alto",
         "flags": {"max_delete": 200}},
        # Flags de una letra, y uno que se acumula con una coma dentro.
        {"name": "cortos", "local": "sync-data/cortos", "remote_path": "/copia/cortos",
         "mode": "down-mirror",
         "extra_flags": ["-uc", "--exclude-from", "a.txt", "--exclude-from", "b,c.txt"]},
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
        "cola": _colas(sync),
        "tope_bisync_de_fabrica": __import__("common.frenos", fromlist=["x"]).TOPE_BISYNC_DE_FABRICA,
        "logs_por_pareja": sync.LOGS_POR_PAREJA,
        "conflicts_shown": sync.CONFLICTS_SHOWN,
    }


LOGS_PARA_LA_COLA = {
    "corto": "2026/10/01 10:00:00 INFO  : a.txt: Copied (new)\n\nERROR : algo\n",
    "con estadísticas": "\n".join(
        ["2026/10/01 10:00:00 INFO  :        0 B / 1.086 MiB, 0%, 0 B/s, ETA -",
         "Transferred:   \t    1.086 MiB / 1.086 MiB, 100%, 512 KiB/s, ETA 0s",
         "Transferred:            0 / 3, 0%",
         "a.bin: 23% / 1.431 MiB, 0 B/s, -",
         "2026/10/01 10:00:01 ERROR : fallo de verdad",
         "x1.5 MiB / 2 MiB, 10%, 1 MiB/s, ETA 1s",
         "١٫٥ MiB / 2 MiB, 10%, 1 MiB/s, ETA 1s",
         "1 B / 2 B, 50%, 1 B/s, ETA　x"]),
    "largo": "\n".join(f"línea {n}" for n in range(40)),
    "saltos raros": "uno\r\ndos tres\x0ccuatro\r\n\r\ncinco",
    "vacío": "",
}


def _colas(sync) -> list[dict]:
    """Lo que imprime `print_log_tail()` de cada log, sin su cabecera ni su
    cierre: las líneas que el motor tiene que dar en su `cola()`."""
    import contextlib  # noqa: PLC0415
    import io  # noqa: PLC0415
    salida = []
    with tempfile.TemporaryDirectory() as tmp:
        for caso, texto in LOGS_PARA_LA_COLA.items():
            ruta = Path(tmp) / "x.log"
            ruta.write_bytes(texto.encode("utf-8"))
            impreso = io.StringIO()
            with contextlib.redirect_stdout(impreso):
                sync.print_log_tail(ruta)
            lineas = impreso.getvalue().split("\n")[1:-2]  # sin «--- últimas…» ni «---»
            salida.append({"caso": caso, "texto": texto, "cola": [x[2:] for x in lineas]})
    return salida


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


CARGAS_PARA_ALINEAR = {
    # Lo mínimo, para la regla del nombre.
    "sftp mínimo": ({"type": "sftp", "host": "h"}, None),
    # Con clave: la clave no es una opción, así que hereda el host y el usuario.
    "sftp con clave": ({"type": "sftp", "host": "h", "user": "u"}, b"CLAVE-DE-PRUEBA"),
    # Con un secreto propio: se queda como está.
    "webdav con contraseña": ({"type": "webdav", "url": "https://h/dav", "user": "u",
                               "pass": "ofuscada"}, None),
    # Con una orden propia: también. (El teléfono la rechaza después, al
    # escribir su rclone.conf; aquí solo se alinea.)
    "sftp con ssh": ({"type": "sftp", "ssh": "ssh -p 22 u@h"}, None),
}

CASOS_PARA_ALINEAR = [
    # La regla del nombre.
    ("mismo nombre", "sftp mínimo", {"defaults": {"remote": "nas"}}),
    ("otro en defaults", "sftp mínimo", {"defaults": {"remote": "casa"}}),
    ("otro en [remote]", "sftp mínimo",
     {"remote": {"name": "nube", "type": "sftp"}, "defaults": {"remote": "casa"}}),
    ("[remote] sin nombre", "sftp mínimo",
     {"remote": {"type": "sftp"}, "defaults": {"remote": "casa"}}),
    ("con espacios", "sftp mínimo", {"defaults": {"remote": "  casa  "}}),
    ("sin nombre en ningún sitio", "sftp mínimo", {"defaults": {}}),
    ("nombre vacío", "sftp mínimo", {"remote": {"name": ""}, "defaults": {"remote": ""}}),
    # Las opciones de un [remote] completo.
    ("sin [remote]", "sftp con clave", {"defaults": {"remote": "nas"}}),
    ("[remote] igual", "sftp con clave",
     {"remote": {"name": "nas", "type": "sftp", "host": "h", "user": "u"}}),
    ("[remote] igual en otro orden", "sftp con clave",
     {"remote": {"user": "u", "host": "h", "type": "sftp", "name": "nas"}}),
    ("[remote] con otro host", "sftp con clave",
     {"remote": {"name": "nas", "type": "sftp", "host": "otro", "user": "u", "port": 2222}}),
    ("[remote] sin type", "sftp con clave", {"remote": {"name": "nas", "host": "otro"}}),
    ("[remote] con type vacío", "sftp con clave", {"remote": {"type": "", "host": "otro"}}),
    ("[remote] con las rutas de la clave", "sftp con clave",
     {"remote": {"type": "sftp", "host": "h", "user": "u", "key_file": "/a/id",
                 "known_hosts_file": "/a/known_hosts"}}),
    ("[remote] con booleanos, números y listas", "sftp con clave",
     {"remote": {"type": "sftp", "host": "otro", "disable_hashcheck": True,
                 "set_modtime": False, "port": 22, "idle_timeout": 1.5,
                 "ciphers": ["aes128-ctr", "aes256-ctr"]}}),
    ("[remote] de un tipo sin obligatorias", "sftp con clave",
     {"remote": {"type": "s3", "provider": "AWS", "region": "eu-west-1"}}),
    # Otro tipo que el del código: prdrive lo adopta y el teléfono no
    # (`Pairing.alinearConCatalogo`, PLAN.md §2).
    ("[remote] de tipo local", "sftp con clave", {"remote": {"type": "local"}}),
    ("[remote] de tipo alias", "sftp con clave", {"remote": {"type": "alias", "remote": "disp:"}}),
    ("[remote] con el type entre espacios", "sftp con clave",
     {"remote": {"type": " sftp ", "host": "otro", "user": "u"}}),
    # Lo que no se hereda.
    ("[remote] con una orden", "sftp con clave",
     {"remote": {"type": "sftp", "host": "otro", "ssh": "ssh otro"}}),
    ("[remote] con dos órdenes", "sftp con clave",
     {"remote": {"type": "webdav", "url": "https://otro/dav",
                 "bearer_token_command": "token", "ssh": "ssh otro"}}),
    ("[remote] con un secreto", "sftp con clave",
     {"remote": {"type": "sftp", "host": "otro", "pass": "x"}}),
    ("[remote] con secretos", "sftp con clave",
     {"remote": {"type": "sftp", "host": "otro", "key_file_pass": "y", "PASS": "x",
                 "api_key": "z", "access_key_id": "no-es-secreto"}}),
    ("[remote] con órdenes y secretos", "sftp con clave",
     {"remote": {"type": "sftp", "host": "otro", "ssh": "ssh otro", "pass": "x"}}),
    # Lo que rclone exige.
    ("[remote] sin host", "sftp con clave", {"remote": {"type": "sftp", "user": "u"}}),
    ("[remote] con el host en blanco", "sftp con clave",
     {"remote": {"type": "sftp", "host": "  ", "user": "u"}}),
    ("[remote] que solo tenía ssh", "sftp con clave",
     {"remote": {"type": "sftp", "user": "u", "ssh": "ssh otro"}}),
    ("[remote] que solo tenía órdenes", "sftp con clave",
     {"remote": {"type": "sftp", "ssh": "ssh otro", "bearer_token_command": "t"}}),
    ("[remote] sin host y con una orden que no lo arregla", "sftp con clave",
     {"remote": {"type": "sftp", "user": "u", "bearer_token_command": "t"}}),
    ("[remote] webdav sin url", "sftp con clave",
     {"remote": {"type": "webdav", "user": "u", "bearer_token_command": "t"}}),
    # Una conexión con secretos u órdenes propios no se mezcla.
    ("con contraseña y el [remote] igual", "webdav con contraseña",
     {"remote": {"type": "webdav", "url": "https://h/dav", "user": "u"}}),
    ("con contraseña y otro [remote]", "webdav con contraseña",
     {"remote": {"type": "webdav", "url": "https://otro/dav", "user": "u"}}),
    ("con contraseña y un [remote] con órdenes y secretos", "webdav con contraseña",
     {"remote": {"type": "webdav", "url": "https://otro/dav", "pass": "x",
                 "bearer_token_command": "t"}}),
    ("con contraseña y un [remote] incompleto", "webdav con contraseña",
     {"remote": {"type": "webdav", "user": "u"}}),
    ("con contraseña y sin type", "webdav con contraseña", {"remote": {"url": "https://otro"}}),
    ("con ssh y otro [remote]", "sftp con ssh", {"remote": {"type": "sftp", "host": "otro"}}),
]

NOMBRES_SECRETOS = [
    "key", "pass", "password", "2fa", "user", "host", "type", "url", "PASS", " pass ",
    "key\xa0", "api_key ", "key_file_pass", "token", "client_secret",
    "service_account_credentials", "private_key_pem", "sas_url", "account_key",
    "account_keys", "account_key_id", "access_grant", "connection_string", "cookie", "authorization", "headers",
    "api_key", "sse_customer_key", "sse_customer_key_base64", "access_key_id",
    "sse_kms_key_id", "key_id", "keyboard", "monkey", "my_key", "my_key_id", "_key",
    "hostkey", "passive", "md5sum_command", "api_Key", "",
]
"""Nombres de opción para `es_secreta()`: los de su docstring, los que casi lo son
y los que dependen de `strip()` y `lower()` (`\\u212a` es el signo Kelvin, que
`lower()` da como una `k`). `account_keys` y `account_key_id` solo son secretos
por el trozo `account_key`, no por la regla de `_key`."""


def vectores_alinear(pairing) -> dict:
    """`profile.align_with_catalog()`: el remote que se escribe en el dispositivo
    (su nombre y sus opciones) y las notas que lo dicen, y `es_secreta()`.

    De cada caso va también lo que contesta `_opciones_del_catalogo()` por su
    cuenta (`del_catalogo`): el teléfono la refleja tal cual, y se separa de
    prdrive solo en `align_with_catalog()`, donde no adopta un `[remote]` de otro
    tipo que el del código."""
    from install import profile  # noqa: PLC0415
    cargas = {
        nombre: pairing.dumps("nas", opciones, key_name="id_ed25519",
                              catalog_path="/prdrive-catalog/remote.toml", private_key=clave)
        for nombre, (opciones, clave) in CARGAS_PARA_ALINEAR.items()
    }
    salida = []
    for caso, carga, raw in CASOS_PARA_ALINEAR:
        perfil = profile.loads(cargas[carga])
        ajustado, notas = profile.align_with_catalog(perfil, raw)
        heredadas, de_opciones = profile._opciones_del_catalogo(   # noqa: SLF001
            perfil.options, dict(raw.get("remote") or {}))
        salida.append({
            "caso": caso, "carga": carga, "catalogo": raw, "nombre": ajustado.remote_name,
            "opciones": dict(ajustado.options), "claves": list(ajustado.options),
            "notas": notas,
            "del_catalogo": {
                "opciones": heredadas, "claves": None if heredadas is None else list(heredadas),
                "notas": de_opciones,
            },
        })
    return {
        "cargas": cargas, "casos": salida,
        "secretas": [{"nombre": n, "secreta": profile.es_secreta(n)} for n in NOMBRES_SECRETOS],
        "obligatorias": {tipo: [clave for clave, _ in claves]
                         for tipo, claves in profile.OBLIGATORIAS.items()},
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
        "alinear": vectores_alinear(__import__("common.pairing", fromlist=["x"])),
    }
