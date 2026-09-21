#!/usr/bin/env python3
"""
vectores.py — Saca de prdrive los valores que el motor Kotlin tiene que reproducir.

**El problema que resuelve.** `engine/` es una traducción a Kotlin de
`common/model.py` y `common/bisync.py`: las mismas constantes, las mismas capas
de flags, el mismo nombre de sesión de bisync. Eso significa que esas constantes
existen ahora DOS veces, y dos copias se separan. Copiarlas a mano a un test
Kotlin solo mueve el problema: el test diría lo que creía el que lo escribió, no
lo que hace prdrive hoy.

Así que no se copian: se **generan**. Este script importa el prdrive de verdad,
le pregunta por cada caso y escribe las respuestas en
`engine/src/test/resources/vectores.json`. Los tests de Kotlin no llevan ni un
número escrito a mano: leen ese fichero. Cuando prdrive cambie una constante, se
vuelve a ejecutar esto y el test que falla dice exactamente qué se ha movido.

    python herramientas/vectores.py ../prdrive

El JSON lleva dentro la versión y el commit de prdrive de los que salió, para
que se sepa contra qué está escrito el motor — la misma regla con la que
`common/bisync.py` cita el fichero de rclone que imita.

No toca disco fuera del JSON: se usan solo las funciones puras (`filters_content`
y no `filters_file_for`, que escribiría en `filters/`).
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from pathlib import PureWindowsPath

DESTINO = Path(__file__).resolve().parent.parent / "engine/src/test/resources/vectores.json"

# La raíz de dispositivo que se le hace creer a prdrive. Fija, para que el JSON
# no cambie según dónde se ejecute esto.
RAIZ_FALSA = "/volumen"

# Un sync_config.toml completo, con los casos que importan: una pareja normal,
# una anidada, la de la RAÍZ del dispositivo (`local = "."`, la que necesita
# RAIZ_UPSTREAM), una que no es bisync y una con flags propios que pisan al modo.
CONFIG = {
    "defaults": {
        "remote": "nas",
        "device_remote": "disp",
        "catalog_path": "/prdrive-catalog/pairs.toml",
        "exclude": ["*.tmp"],
        "flags": {"transfers": 4},
    },
    "daemon": {"pairs": ["notas"], "interval_minutes": 15},
    "pair": [
        {"name": "notas", "local": "sync-data/notas",
         "remote_path": "/copia/notas"},
        {"name": "fotos", "local": "sync-data/media/fotos",
         "remote_path": "/copia/fotos",
         "include": ["*.jpg", "*.raw"],
         "flags": {"max-delete": 5, "checkers": 2}},
        {"name": "todo", "local": ".", "remote_path": "/copia/volumen"},
        {"name": "subida", "local": "sync-data/salida",
         "remote_path": "/copia/salida", "mode": "up"},
        {"name": "espejo", "local": "sync-data/espejo",
         "remote_path": "/copia/espejo", "mode": "up-mirror",
         "use_filters_file": False},
    ],
}

# Las opciones de un remote, para el QR. Ni host ni usuario de nadie: es un
# ejemplo, y la clave es basura con la longitud de una de verdad.
OPCIONES_REMOTE = {
    "type": "sftp",
    "host": "ejemplo.invalid",
    "user": "usuario",
    "port": "22",
}


def cargar(prdrive: Path):
    """Importa el prdrive de ese checkout y le fija la raíz del dispositivo."""
    sys.path.insert(0, str(prdrive))
    from common import bisync, config_file, model, pairing, progress   # noqa: PLC0415

    # `sync.py` está en la raíz y `flags_editor` en `ui/`; los dos se importan
    # sin ejecutar nada (el `main()` de sync va detrás de su guarda, y
    # flags_editor no toca Tk). De ahí salen KNOWN_ERRORS y RESERVED, que el
    # motor replica y que nadie debería volver a escribir a mano.
    import sync as sync_py                                   # noqa: PLC0415
    from ui import flags_editor                               # noqa: PLC0415
    from common import catalog, conflicts                     # noqa: PLC0415
    from install import deploy, remote as install_remote      # noqa: PLC0415

    # `model.DEVICE_ROOT` sale de `__file__`, así que apunta al checkout. Se
    # reengancha para que `local_abs` y `top_level_abs` —y con ellos el
    # `upstreams` del remote 'combine'— no dependan de dónde esté esto.
    model.DEVICE_ROOT = Path(RAIZ_FALSA)
    return (bisync, config_file, model, pairing, progress, sync_py, flags_editor,
            catalog, deploy, install_remote, conflicts)


def procedencia(prdrive: Path) -> dict:
    version = (prdrive / "VERSION").read_text(encoding="utf-8").strip()
    try:
        commit = subprocess.run(["git", "-C", str(prdrive), "rev-parse", "HEAD"],
                                capture_output=True, text=True, timeout=30,
                                check=True).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        commit = ""
    return {"version": version, "commit": commit}


def vectores_flags(model) -> list[dict]:
    """`flags_to_args`: cada regla de la traducción, con su caso."""
    casos = [
        {"verbose": True},                       # true -> flag pelado
        {"dry-run": False},                      # false -> se cae
        {"max-delete": None},                    # None -> se cae
        {"transfers": 4},                        # número
        {"max-lock": "2m"},                      # cadena
        {"stats_one_line": True},                # _ -> -
        {"exclude": ["*.tmp", "*.bak"]},         # lista -> flag repetido
        {"include": ("uno",)},                   # tupla, igual que lista
        {"a": True, "b": 1, "c": False, "d": "x"},   # el orden se conserva
        {},
    ]
    return [{"flags": {k: list(v) if isinstance(v, tuple) else v
                       for k, v in caso.items()},
             "args": model.flags_to_args(caso)} for caso in casos]


def vectores_modos(model) -> dict:
    """Cada modo con su verbo, su sentido y sus flags ya fundidos con la base."""
    return {
        nombre: {
            "verb": modo.verb,
            "source": modo.source,
            "dest": modo.dest,
            "flags_propios": dict(modo.flags),
            "flags_con_base": {**model.BASE_FLAGS, **modo.flags},
        }
        for nombre, modo in model.MODES.items()
    }


def vectores_canonical(bisync) -> dict:
    """Lo que replica `cmd/bisync/bilib/canonical.go`."""
    entradas = [
        "disp:sync-data/notas/",
        "/volumen/sync-data/notas/",
        "nas:/copia/notas/",
        "  con espacios  ",
        "raro:?*\\/x",
        "nas{deadbeef}:/copia/",
        "sin llaves",
        "{solo abre",
    ]
    return {
        "canonical_path": [{"entrada": e, "salida": bisync.canonical_path(e)}
                           for e in entradas],
        "strip_hex_string": [{"entrada": e, "salida": bisync.strip_hex_string(e)}
                             for e in entradas],
        "fs_path_remote": [{"entrada": e, "salida": bisync.fs_path_remote(e)}
                           for e in ("nas:/copia", "nas:/copia/", "disp:x")],
        "session_name": [
            {"path1": p1, "path2": p2, "salida": bisync.session_name(p1, p2)}
            for p1, p2 in (("disp:sync-data/notas/", "nas:/copia/notas/"),
                           ("nas{beef}:/a/", "disp:b/"))
        ],
    }


def vectores_upstream(model) -> list[dict]:
    """El `upstreams` del remote 'combine', campo a campo.

    Las rutas de Windows entran como `PureWindowsPath` para que salgan con su
    barra invertida sin necesitar un Windows: el caso de la raíz de una unidad
    (`F:\\`) es justo el que tumbaba TODAS las parejas del dispositivo, así que
    el motor Kotlin tiene que reproducirlo aunque nunca vaya a verlo."""
    casos = [
        ("raiz", Path("/volumen")),
        ("sync-data", Path("/volumen/sync-data")),
        ("con espacios", Path("/volumen/con espacios")),
        ("raiz", PureWindowsPath("F:/")),
        ("datos", PureWindowsPath("F:/datos")),
        ("comilla", PureWindowsPath('F:/di"cho')),
    ]
    return [{"nombre": n, "ruta": str(r), "salida": model._upstream(n, r)}
            for n, r in casos]


def vectores_parejas(bisync, model) -> dict:
    """La config de arriba, resuelta: cada pareja con todo lo que se deriva."""
    config = model.parse_config(CONFIG)
    parejas = []
    for p in config.pairs:
        parejas.append({
            "name": p.name,
            "mode": p.mode.name,
            "local": p.local,
            "remote_path": p.remote_path,
            "remote_name": p.remote_name,
            "includes": list(p.includes),
            "excludes": list(p.excludes),
            "flags": dict(p.flags),
            "extra_flags": list(p.extra_flags),
            "use_filters_file": p.use_filters_file,
            "wants_filters_file": p.wants_filters_file,
            "device_remote": p.device_remote,
            "is_bisync": p.is_bisync,
            "tramos_locales": list(p.tramos_locales),
            "top_level_dir": p.top_level_dir,
            "top_level_abs": str(p.top_level_abs),
            "ruta_en_combine": p.ruta_en_combine,
            "local_abs": str(p.local_abs),
            "local_endpoint": p.local_endpoint,
            "remote_endpoint": p.remote_endpoint,
            "source": p.source,
            "dest": p.dest,
            "workdir": p.workdir.name,
            "expected_prefix": bisync.expected_prefix(p),
            "filters_content": bisync.filters_content(p),
        })
    return {
        "config": CONFIG,
        "device_remote": config.device_remote,
        "keep_logs": config.keep_logs,
        "names": config.names,
        "pen_environment": config.pen_environment(),
        "parejas": parejas,
    }


def vectores_toml(config_file) -> dict:
    """Lo que escribe el serializador, que es lo que hay que reproducir letra a
    letra: el TOML del dispositivo se edita a mano y se relee."""
    cabecera = "# Generado para los tests.\n# Dos líneas, para probar que sobrevive.\n"
    catalogo = {
        "remote": {"name": "nas", "type": "sftp", "host": "ejemplo.invalid",
                   "port": "22", "user": "usuario"},
        **CONFIG,
    }
    return {
        "dumps": [
            {"raw": CONFIG, "head": "", "texto": config_file.dumps(CONFIG)},
            {"raw": CONFIG, "head": cabecera,
             "texto": config_file.dumps(CONFIG, cabecera)},
            {"raw": catalogo, "head": "", "texto": config_file.dumps(catalogo)},
        ],
        "dumps_table": [
            {"tabla": t, "texto": config_file.dumps_table(t)}
            for t in ({"max-delete": 25, "resilient": True, "max-lock": "2m"},
                      {"exclude": ["*.tmp"], "transfers": 4},
                      {"raro clave": "x"},
                      {})
        ],
        "header_of": [
            {"entrada": e, "salida": config_file.header_of(e)}
            for e in ("# uno\n# dos\nclave = 1\n",
                      "clave = 1\n",
                      "\n# tras un hueco\nclave = 1\n",
                      "")
        ],
        "pair_key_order": list(config_file.PAIR_KEY_ORDER),
    }


def vectores_pairing(pairing) -> dict:
    """La carga útil del QR: el texto exacto y lo que sale al leerlo.

    La clave no es una clave: son 400 bytes de relleno con la longitud de una
    ed25519 en PEM, que es lo que decide si el QR cabe."""
    clave = b"-----BEGIN OPENSSH PRIVATE KEY-----\n" + b"A" * 380 + \
            b"\n-----END OPENSSH PRIVATE KEY-----\n"
    conocidos = "ejemplo.invalid ssh-ed25519 AAAA...\n"
    texto = pairing.dumps("nas", OPCIONES_REMOTE, key_name="id_ed25519",
                          catalog_path="/prdrive-catalog/pairs.toml",
                          private_key=clave, known_hosts=conocidos)
    sin_clave = pairing.dumps("nas", OPCIONES_REMOTE, key_name="id_ed25519",
                              catalog_path="/prdrive-catalog/pairs.toml")
    leida = pairing.leer(texto)
    return {
        "marca": pairing.MARCA,
        "formato": pairing.FORMATO,
        "rutas_derivadas": list(pairing.RUTAS_DERIVADAS),
        "con_clave": {
            "texto": texto,
            "private_key_len": len(clave),
            "leido": {
                "remote_name": leida.remote_name,
                "known_hosts": leida.known_hosts,
                "private_key_len": len(leida.private_key or b""),
            },
        },
        "sin_clave": {"texto": sin_clave},
        "rechazos": _rechazos(pairing),
        "rclone_conf": _rclone_conf(pairing),
    }


def _rechazos(pairing) -> list[dict]:
    """Los códigos que hay que rechazar, y por qué. Un QR lee lo que le pongan
    delante, así que «esto no es de prdrive» es un mensaje que hay que dar."""
    casos = {
        "no es toml": "esto no { es toml",
        "sin marca": 'remote_name = "nas"\n',
        "otro formato": 'prdrive = 99\nremote_name = "nas"\n',
        "base64 malo": 'prdrive = 1\nprivate_key_b64 = "no es base64!!"\n',
    }
    salida = []
    for etiqueta, texto in casos.items():
        try:
            pairing.leer(texto)
        except pairing.PairingError:
            salida.append({"caso": etiqueta, "texto": texto, "rechaza": True})
        else:
            salida.append({"caso": etiqueta, "texto": texto, "rechaza": False})
    return salida


def _rclone_conf(pairing) -> list[dict]:
    """El lector de rclone.conf: hecho a mano porque configparser revienta con
    los `%` de las plantillas de nombre de rclone."""
    casos = [
        "[nas]\ntype = sftp\nhost = ejemplo.invalid\n",
        "[nas]\ntype = sftp\n\n[otro]\ntype = webdav\nurl = https://x/%y\n",
        "# comentario\n; otro\n[nas]\ntype = sftp\nsin_igual\n",
        "clave = suelta\n[nas]\ntype = sftp\n",
    ]
    return [{"texto": t, "remotes": pairing.parse_rclone_conf(t)} for t in casos]


def vectores_ejecucion(sync_py, flags_editor) -> dict:
    """Lo que `sync.py` sabe de una pasada y el motor tiene que repetir.

    Las **agujas** de `KNOWN_ERRORS` son lo que importa aquí: son las cadenas
    que rclone escribe, así que si prdrive corrige una y el motor se queda con
    la vieja, el diagnóstico desaparece sin que nada falle. Las explicaciones
    NO se comparan letra a letra —la app habla de «la carpeta del volumen»
    donde el PC habla de «la ruta local»—, pero el orden sí: las de arranque
    van al final a propósito.
    """
    return {
        "known_errors": [{"aguja": aguja, "explicacion": texto}
                         for aguja, texto in sync_py.KNOWN_ERRORS],
        "reservados": dict(flags_editor.RESERVED),
        "saltada": sync_py.SKIPPED,
        "lineas_de_cola": sync_py.LOG_TAIL_LINES,
        "conflictos_mostrados": sync_py.CONFLICTS_SHOWN,
        "direct_output_header": sync_py.DIRECT_OUTPUT_HEADER,
    }


# Tamaños elegidos para pillar los saltos de unidad y, sobre todo, los empates
# del redondeo: 1280 octetos son 1,25 KB exactos, y ahí Python redondea a la
# cifra par («1,2») mientras que un `String.format` de Java redondea hacia
# arriba («1,3»). Sin este caso, el móvil y el PC dirían números distintos de
# la misma pasada y nadie lo vería venir.
TAMANOS = [0, 1, 512, 1023, 1024, 1280, 1382, 1536, 1048576, 1100000,
           1073741824, 1610612736, 1099511627776, 1649267441664]

# Las líneas: una estadística de verdad, la misma cortada a media escritura
# —que es lo normal leyendo un log que se está escribiendo—, la cuenta de
# ficheros y la línea de un fichero suelto, que NO son progreso, y un error.
LINEAS_DE_LOG = [
    "2026/09/21 07:56:16 INFO  : Transferred:   \t  1.086 MiB / 2.500 MiB, 43%, 512 KiB/s, ETA 3s",
    "2026/09/21 07:56:16 INFO  : Transferred:   \t  1.086 MiB / 2.500 MiB, 43%, 512 Ki",
    "2026/09/21 07:56:16 INFO  : Transferred:   \t            0 / 3, 0%",
    "2026/09/21 07:56:16 INFO  : a.bin: 23% /1.431 MiB, 0 B/s, -",
    "2026/09/21 07:56:16 ERROR : Bisync critical error: cannot find prior Path1 or Path2 listings",
    "0 B / 0 B, -, 0 B/s, ETA -",
]


def vectores_progreso(progress) -> dict:
    """El progreso: el texto que lee el usuario y el lector de líneas.

    En la app el progreso sale de `core/stats` como datos, no de leer el log,
    pero el **texto** es el mismo y el lector se conserva para quitar las
    estadísticas de la cola del log que se enseña cuando algo falla.
    """
    casos = [
        (2200000, 3500000, 61, 1100000.0),
        (0, 0, None, 0.0),
        (1024, 2048, 50, 512.0),
        (1099511627776, 1099511627776, 100, 1048576.0),
        (512, 0, None, 1.0),
    ]
    return {
        "etiqueta": progress.ETIQUETA,
        "tamanos": [{"octetos": o, "texto": progress._tamano(o)} for o in TAMANOS],
        "textos": [
            {"hecho": h, "total": t, "porcentaje": p, "velocidad": v,
             "texto": progress.Progreso(h, t, p, v).texto()}
            for h, t, p, v in casos
        ],
        "lineas": [
            {"linea": linea,
             "leido": None if (leido := progress.leer(linea)) is None else {
                 "hecho": leido.hecho, "total": leido.total,
                 "porcentaje": leido.porcentaje, "velocidad": leido.velocidad}}
            for linea in LINEAS_DE_LOG
        ],
        "ultimo": {
            "texto": "\n".join(LINEAS_DE_LOG),
            "leido": None if (u := progress.ultimo("\n".join(LINEAS_DE_LOG))) is None else {
                "hecho": u.hecho, "total": u.total,
                "porcentaje": u.porcentaje, "velocidad": u.velocidad},
        },
    }


def vectores_catalogo(catalog, config_file, deploy, install_remote) -> dict:
    """El catálogo: dónde está, y el config que sale de elegir parejas de él.

    `device_config()` es de `install/deploy.py`, o sea de lo que en el
    escritorio hace el instalador: los `[defaults]` del catálogo, su `[daemon]`
    recortado a las parejas elegidas, y solo esas parejas. En la app lo hace el
    primer arranque, así que el valor esperado sale de aquí y no de lo que
    parezca razonable.
    """
    catalogo_raw = {
        "remote": {"name": "nas", "type": "sftp", "host": "ejemplo.invalid",
                   "port": "22", "user": "usuario"},
        **CONFIG,
    }
    texto = config_file.dumps(catalogo_raw)
    # OJO: son DOS clases distintas con el mismo nombre. `device_config()` es
    # del instalador y quiere `install/remote.Catalog` (el dict crudo y su
    # cabecera); la del dispositivo es `common/catalog.Catalog` (con de dónde
    # y de cuándo se leyó), y es la que refleja `engine/Catalog.kt`.
    cat = install_remote.Catalog(raw=catalogo_raw, head=config_file.header_of(texto))
    elegidas = ["notas", "fotos"]
    ruta = "/otro/sitio/pairs.toml"
    return {
        "default_catalog_path": catalog.DEFAULT_CATALOG_PATH,
        "net_flags": list(catalog.NET_FLAGS),
        "endpoint": [
            {"defaults": d, "salida": catalog.endpoint({"defaults": d})}
            for d in ({},
                      {"remote": "nas"},
                      {"remote": "nas", "catalog_path": "/otra/ruta.toml"},
                      {"remote": "nas", "catalog_remote": "otro"})
        ],
        "diff_keys": [
            {"a": a, "b": b, "salida": list(catalog.diff_keys(a, b))}
            for a, b in (({"x": 1}, {"x": 1}),
                         ({"x": 1}, {"x": 2}),
                         ({"x": 1}, {}),
                         ({}, None),
                         ({"a": 1, "b": 2}, {"b": 3, "c": 4}))
        ],
        "device_config": {
            "catalogo": catalogo_raw,
            "catalogo_texto": texto,
            "elegidas": elegidas,
            "catalog_path": ruta,
            "raw": deploy.device_config(cat, elegidas, ruta),
            "sin_ruta": deploy.device_config(cat, elegidas),
        },
    }


# Los nombres que hay que reconocer, y los que no. `plan.md.conflicto` (un
# sufijo sin número) NO lo escribe rclone, así que no es un conflicto; y
# `plan.md.conflict1` sí, porque es el sufijo de fábrica y los conflictos de
# antes de cambiarlo siguen ahí.
NOMBRES_CONFLICTO = [
    "plan.md",
    "plan.md.conflicto-dispositivo",
    "plan.md.conflicto-remoto1",
    "plan.md.conflicto1",
    "plan.md.conflicto2",
    "plan.md.conflicto",
    "plan.conflicto-dispositivo1.md",
    "plan.md.conflict1",
    "plan.md.conflict",
    "algo.tar.gz.conflicto-remoto3",
]


def vectores_conflictos(conflicts, model) -> dict:
    """El nombre que rclone le pone al perdedor de un conflicto.

    Réplica de `cmd/bisync/resolve.go` (`setResolveDefaults`, `resolve`,
    `SuffixName`) y de `lib/transform/transform.go` (`SuffixKeepExtension`),
    leída de los flags YA FUNDIDOS de la pareja. Los casos cubren lo que cambia
    el significado del número: dos sufijos (el sufijo ES el lado), uno con
    `--conflict-loser pathname` (1 es path1, 2 es path2) y uno con `num` (el
    número es el primero libre, así que el lado no se sabe).
    """
    import dataclasses                                        # noqa: PLC0415

    config = model.parse_config(CONFIG)
    base = {p.name: p for p in config.pairs}["notas"]

    variantes = {
        "dos sufijos": {},
        "un sufijo": {"conflict-suffix": "conflicto"},
        "un sufijo pathname": {"conflict-suffix": "conflicto",
                               "conflict-loser": "pathname"},
        "extension delante": {"suffix-keep-extension": True},
        "de fabrica": {"conflict-suffix": None},
    }

    casos = []
    for etiqueta, extra in variantes.items():
        flags = dict(base.flags)
        for clave, valor in extra.items():
            if valor is None:
                flags.pop(clave, None)
            else:
                flags[clave] = valor
        pareja = dataclasses.replace(base, flags=flags)
        esq = conflicts.esquema(pareja)
        casos.append({
            "caso": etiqueta,
            "flags": {k: v for k, v in flags.items()
                      if k in ("conflict-suffix", "conflict-loser",
                               "suffix-keep-extension")},
            "esquema": {"sufijo1": esq.sufijo1, "sufijo2": esq.sufijo2,
                        "perdedor": esq.perdedor,
                        "mantener_extension": esq.mantener_extension},
            "nombres": [
                {"nombre": n,
                 "leido": None if (r := conflicts.leer_nombre(n, esq)) is None else {
                     "original": r[0], "camino": r[1], "numero": r[2],
                     "lado": conflicts.lado(pareja, r[1])}}
                for n in NOMBRES_CONFLICTO
            ],
        })

    return {
        "dispositivo": conflicts.DISPOSITIVO,
        "remoto": conflicts.REMOTO,
        "sufijo_rclone": conflicts.SUFIJO_RCLONE,
        "perdedor_rclone": conflicts.PERDEDOR_RCLONE,
        "casos": casos,
        # A qué lado corresponde path1/path2 en cada modo: en bisync path1 es el
        # local, en un `down` es el remoto.
        "lados": [
            {"modo": nombre,
             "path1": conflicts.lado(dataclasses.replace(base, mode=modo), "path1"),
             "path2": conflicts.lado(dataclasses.replace(base, mode=modo), "path2")}
            for nombre, modo in model.MODES.items()
        ],
    }


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__.strip().splitlines()[0])
        print("\nUso: python herramientas/vectores.py <ruta al checkout de prdrive>")
        return 2
    prdrive = Path(sys.argv[1]).expanduser().resolve()
    if not (prdrive / "common" / "model.py").is_file():
        print(f"No parece un checkout de prdrive: {prdrive}")
        return 2

    (bisync, config_file, model, pairing, progress, sync_py, flags_editor,
     catalog, deploy, install_remote, conflicts) = cargar(prdrive)
    datos = {
        "_generado_por": "herramientas/vectores.py",
        "_no_editar": "Se regenera desde el prdrive citado en 'prdrive'.",
        "prdrive": procedencia(prdrive),
        "raiz_dispositivo": RAIZ_FALSA,
        "base_flags": dict(model.BASE_FLAGS),
        "constantes": {
            "DEFAULT_REMOTE": model.DEFAULT_REMOTE,
            "DEFAULT_MODE": model.DEFAULT_MODE,
            "DEFAULT_DEVICE_REMOTE": model.DEFAULT_DEVICE_REMOTE,
            "RAIZ_UPSTREAM": model.RAIZ_UPSTREAM,
            "PATH1_SUFFIX": bisync.PATH1_SUFFIX,
            "PATH2_SUFFIX": bisync.PATH2_SUFFIX,
            "ERR_SUFFIX": bisync.ERR_SUFFIX,
            "MISSING_LISTINGS": bisync.MISSING_LISTINGS,
            "FILTERS_HEADER": bisync.FILTERS_HEADER,
        },
        "flags_to_args": vectores_flags(model),
        "modos": vectores_modos(model),
        "canonical": vectores_canonical(bisync),
        "upstream": vectores_upstream(model),
        "resueltas": vectores_parejas(bisync, model),
        "toml": vectores_toml(config_file),
        "pairing": vectores_pairing(pairing),
        "ejecucion": vectores_ejecucion(sync_py, flags_editor),
        "progreso": vectores_progreso(progress),
        "catalogo": vectores_catalogo(catalog, config_file, deploy, install_remote),
        "conflictos": vectores_conflictos(conflicts, model),
    }

    DESTINO.parent.mkdir(parents=True, exist_ok=True)
    DESTINO.write_text(json.dumps(datos, indent=2, ensure_ascii=False,
                                  sort_keys=False) + "\n", encoding="utf-8")
    print(f"{DESTINO.relative_to(Path.cwd()) if DESTINO.is_relative_to(Path.cwd()) else DESTINO}: "
          f"prdrive {datos['prdrive']['version']} "
          f"({datos['prdrive']['commit'][:8] or 'sin commit'})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
