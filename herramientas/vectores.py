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

**Sale lo mismo en cualquier sistema.** La app corre en Android, que es POSIX,
así que a prdrive se le hace creer que también: la raíz del dispositivo es una
ruta POSIX que no se resuelve contra el disco ([_RutaFalsa]) y `bisync.IS_WIN`
se apaga. Sin eso, ejecutado en Windows, `/volumen` salía `D:\\volumen`.

No toca el disco de prdrive: se usan las funciones puras (`filters_content` y no
`filters_file_for`, que escribiría en `filters/`). Lo único que se lee del disco
—el estado del baseline y la referencia vacía— se monta en una carpeta temporal
que se borra al acabar.
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path, PurePosixPath, PureWindowsPath

DESTINO = Path(__file__).resolve().parent.parent / "engine/src/test/resources/vectores.json"

import vectores_actualizacion  # noqa: E402 — vive junto a este fichero
import vectores_historial  # noqa: E402 — vive junto a este fichero
import vectores_llavero  # noqa: E402 — vive junto a este fichero
import vectores_paso3  # noqa: E402 — vive junto a este fichero
import vectores_python  # noqa: E402 — vive junto a este fichero

# La raíz de dispositivo que se le hace creer a prdrive. Fija, para que el JSON
# no cambie según dónde se ejecute esto.
RAIZ_FALSA = "/volumen"

# La carpeta del programa dentro de esa raíz. En un dispositivo de escritorio es
# `.prdrive/` y en la app también (`filesDir/volumen/.prdrive/`): de su nombre
# salen `REGLA_SIN_PROGRAMA` y lo que `problema_local()` rechaza. En el checkout
# de prdrive sería `prdrive` (el nombre de la carpeta del repositorio), que no es
# el de ningún dispositivo.
CARPETA_PROGRAMA = ".prdrive"


class _RutaFalsa(PurePosixPath):
    """Una ruta POSIX que `resolve()` deja como está.

    `Pair.local_abs` y `top_level_abs` resuelven contra el disco; aquí no hay
    disco que valga y la ruta tiene que salir igual en Windows que en Linux.
    """

    def resolve(self, strict: bool = False) -> "_RutaFalsa":
        return self


# Un sync_config.toml completo, con los casos que importan: una pareja normal
# con versiones y `watch`, una anidada, la de la RAÍZ del dispositivo
# (`local = "."`, la que necesita RAIZ_UPSTREAM y recibe las reglas del programa
# y del llavero), una que no es bisync, una con flags propios que pisan al modo,
# y un `[keychain]`, que añade su pareja al final.
CONFIG = {
    "defaults": {
        "remote": "nas",
        "device_remote": "disp",
        "catalog_path": "/prdrive-catalog/remote.toml",
        "exclude": ["*.tmp"],
        "flags": {"transfers": 4},
    },
    "daemon": {"pairs": ["notas"], "interval_minutes": 15},
    "keychain": {"base": "claves.kdbx"},
    "pair": [
        {"name": "notas", "local": "sync-data/notas",
         "remote_path": "/copia/notas", "versions": True, "watch": True},
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
    """Importa el prdrive de ese checkout y le hace creer que corre en un dispositivo POSIX."""
    sys.path.insert(0, str(prdrive))
    from common import bisync, config_file, model, pairing   # noqa: PLC0415

    # `model.DEVICE_ROOT` y `APP_DIR` salen de `__file__`, así que apuntan al
    # checkout. Se reenganchan para que `local_abs`, `top_level_abs` —y con ellos
    # el `upstreams` del remote 'combine'— y las reglas que dependen del nombre
    # de la carpeta del programa no dependan de dónde esté esto.
    model.DEVICE_ROOT = _RutaFalsa(RAIZ_FALSA)
    model.APP_DIR = model.DEVICE_ROOT / CARPETA_PROGRAMA
    model.STATE_DIR = model.APP_DIR / "state"
    model.FILTERS_DIR = model.APP_DIR / "filters"
    model.REGLA_SIN_PROGRAMA = f"- /{model.APP_DIR.name}/**"
    bisync.IS_WIN = False
    return bisync, config_file, model, pairing


def procedencia(prdrive: Path) -> dict:
    version = (prdrive / "VERSION").read_text(encoding="utf-8").strip()
    try:
        commit = subprocess.run(["git", "-C", str(prdrive), "rev-parse", "HEAD"],
                                capture_output=True, text=True, timeout=30,
                                check=True).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        commit = ""
    return {"version": version, "commit": commit}


def _error(funcion, *args) -> dict:
    """Lo que pasa al llamar a `funcion(*args)`.

    `{"error": None}` si no lanza; `{"error": mensaje}` si lanza `ConfigError`;
    y `{"error": None, "rompe": "TypeError"}` si prdrive se cae con otra
    excepción. Ese último caso no tiene mensaje que copiar: el motor no puede
    caerse con un catálogo mal escrito, así que tiene que dar un `ConfigError`
    con el suyo, y el test lo comprueba."""
    from common.model import ConfigError   # noqa: PLC0415
    try:
        funcion(*args)
    except ConfigError as e:
        return {"error": str(e)}
    except Exception as e:   # noqa: BLE001 — es justo lo que se quiere apuntar
        return {"error": None, "rompe": type(e).__name__}
    return {"error": None}


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
            "origen_local": modo.origen_local,
            "borra_en_remoto": modo.borra_en_remoto,
            "flags_propios": dict(modo.flags),
            "flags_con_base": {**model.BASE_FLAGS, **modo.flags},
        }
        for nombre, modo in model.MODES.items()
    }


def vectores_seguridad(model) -> dict:
    """Lo que el config no puede llevar, y con qué palabras se dice.

    El catálogo llega del remoto y lo escriben otros: lo que prdrive rechaza
    porque acabaría en la línea de órdenes de rclone, la app lo rechaza igual.
    Los mensajes se comparan letra a letra porque son lo que lee el usuario."""
    flags = ["transfers", "config", "log-file", "Log_File", "dry-run", "workdir",
             "resync", "force", "filters-file", "filter", "filter-from", "include",
             "exclude", "backup-dir", "backup-dir1", "backup-dir2", "suffix",
             "suffix-keep-extension", "cpuprofile", "memprofile", "password-command",
             "sftp-ssh", "SFTP_SSH", "metadata-mapper", "rc", "rc-addr", "rcd",
             "sftp-ssh=sh -c id", " max-delete ", "bearer-token-command", "checksum",
             # Lo que `str.strip()` quita y `trim()` de Kotlin no: el NEL (U+0085).
             "config", " config ", "　rc"]
    extras = [[], ["--fast-list"], ["--sftp-ssh=sh -c id"], ["-v", "--config", "x"],
              ["8M", "sh -c id"], ["--checkers", "8", "--rc-addr=:5572"],
              ["--max-delete=10", "--memprofile=/tmp/x"]]
    valores = ["8M", 25, True, "--sftp-ssh=sh -c id", ["a", "--config=x"], ["a", 3],
               None, "-1", ["--fast-list"]]
    remotes = ["nas", "mi nas", "nas,ssh='sh -c id'", ":sftp,host=x", "C", "c", "ñ",
               "-nas", "nas ", "a.b+c@d-e_f", "", 5, None, "dos  espacios", "año",
               "nas:x", "nas=1", 'n"a', "ab", "1",
               # El `\w` de Python, que no es ninguno de los de Java: rechaza el
               # ZWJ, el ZWNJ, una marca combinante y un conector, y acepta ½ y ².
               "a‍b", "a‌b", "ńas", "a‿b", "nas½", "x²",
               "١٢", "日本"]
    nombres = ["notas", "-x", ".", "..", "a/b", "a\\b", "a:b", "tab\tx", 5, "",
               "ñandú", "--resync", "a.b"]
    locales = ["sync-data/notas", ".", "/sync-data", "..", "a/../b", "C:/x", "c:",
               "...", ". .", "a/...", "x:y", ".prdrive", ".prdrive./x", ".PRDRIVE ",
               ".keychain/x", "sync-data\\notas", "", "sync-data/.prdrive"]
    return {
        "flags_reservados": dict(model.FLAGS_RESERVADOS),
        "flags_escriben": dict(model.FLAGS_ESCRIBEN),
        "quitalo": model.QUITALO,
        "problema_flag": [{"entrada": f, "salida": model.problema_flag(f)} for f in flags],
        "problema_extra": [{"entrada": a, "salida": model.problema_extra(a)} for a in extras],
        "problema_valor_flag": [{"entrada": v, "salida": model.problema_valor_flag(v)}
                                for v in valores],
        "problema_remote": [{"entrada": r, "salida": model.problema_remote(r)}
                            for r in remotes]
        + [{"entrada": "C", "clave": "catalog_remote",
            "salida": model.problema_remote("C", "catalog_remote")}],
        "problema_nombre": [{"entrada": n, "salida": model.problema_nombre(n)}
                            for n in nombres],
        "problema_local": [{"entrada": loc, "salida": model.problema_local(loc)}
                           for loc in locales],
        "normalizar_local": [{"entrada": loc, "salida": model.normalizar_local(loc)}
                             for loc in locales],
        "configs": _configs_rechazados(model),
    }


def _configs_rechazados(model) -> list[dict]:
    """Configs enteros y lo que dice `parse_config()` de cada uno.

    Todos llevan `device_remote`, que la app exige y el escritorio no: así el
    mensaje que se compara es el de prdrive y no el de esa diferencia."""
    def base(**pareja) -> dict:
        return {"defaults": {"device_remote": "disp", "remote": "nas"},
                "pair": [{"name": "a", "local": "a", "remote_path": "/a", **pareja}]}

    def con_defaults(**defaults) -> dict:
        crudo = base()
        crudo["defaults"].update(defaults)
        return crudo

    casos = {
        "vale": base(),
        "flag reservado en defaults": con_defaults(flags={"config": "x"}),
        "flag que lanza un programa": base(flags={"sftp-ssh": "sh"}),
        "flag escondido en un valor": base(flags={"checksum": "--sftp-ssh=sh -c id"}),
        "extra_flags con rc": base(extra_flags=["--rc-addr=:1"]),
        "extra_flags que no es lista": base(extra_flags=5),
        "extra_flags tabla": base(extra_flags={"--rc": True}),
        "remote de una letra en defaults": con_defaults(remote="C"),
        "catalog_remote con opciones": con_defaults(catalog_remote="nas,x=y"),
        "remote al vuelo en la pareja": base(remote=":sftp"),
        "remote vacío en la pareja": base(remote=""),
        "local que no es texto": base(local=5),
        "local que sale": base(local="../x"),
        "local en la carpeta del programa": base(local=".prdrive/keys"),
        "include con salto de línea": base(include=["x\n!\n+ **"]),
        "exclude de defaults con retorno": con_defaults(exclude=["a\rb"]),
        "watch en defaults": con_defaults(watch=True),
        "watch que no es booleano": base(watch="si"),
        "watch en un modo de bajada": base(watch=True, mode="down"),
        "versions fuera de bisync": base(versions=True, mode="up"),
        "nombre con guion delante": base(name="-x"),
        "nombre que no es texto": base(name=5),
        "nombre vacío": base(name=""),
        "modo inventado": base(mode="sync"),
        "falta local": {"defaults": {"device_remote": "disp"},
                        "pair": [{"name": "a", "remote_path": "/a"}]},
        "sin parejas": {"defaults": {"device_remote": "disp"}, "pair": []},
        "pareja que se llama como el llavero": {
            **base(name="keychain"), "keychain": {"base": "x.kdbx"}},
        "llavero sin base": {**base(), "keychain": {}},
        "llavero en mayúsculas": {**base(), "keychain": {"base": "x.KDBX"}},
        "llavero con ruta": {**base(), "keychain": {"base": "a/x.kdbx"}},
        "llavero oculto": {**base(), "keychain": {"base": ".x.kdbx"}},
        "llave_interna que no es booleana": {
            **base(), "keychain": {"base": "x.kdbx", "llave_interna": "si"}},
        "llave_interna sin fichero llave": {
            **base(), "keychain": {"base": "x.kdbx", "llave_interna": True}},
        "llavero que vale": {
            **base(), "keychain": {"base": "x.kdbx", "llave_interna": True,
                                   "fichero_llave": True}},
        # Los que tumban a prdrive en vez de rechazarse: ver `_error()`.
        "flags que es una lista": base(flags=["--max-delete=5"]),
        "flags que es un texto": base(flags="x"),
        "flags de defaults que es un texto": con_defaults(flags="x"),
        "device_remote que es un booleano": con_defaults(device_remote=True),
        "device_remote que es un número": con_defaults(device_remote=5),
        "include de defaults que es un número": con_defaults(include=5),
        "exclude de la pareja que es un número": base(exclude=5),
    }
    return [{"caso": caso, "config": crudo, **_error(model.parse_config, crudo)}
            for caso, crudo in casos.items()]


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
        "fs_path_remote": [{"entrada": e, "salida": bisync.fs_path_remote(e, {})}
                           for e in ("nas:/copia", "nas:/copia/", "disp:x")],
        "session_name": [
            {"path1": p1, "path2": p2, "salida": bisync.session_name(p1, p2)}
            for p1, p2 in (("disp:sync-data/notas/", "nas:/copia/notas/"),
                           ("nas{beef}:/a/", "disp:b/"))
        ],
    }


# Un rclone.conf con los tipos de remote que cambian cómo se nombra un extremo:
# un `local` (su `Root()` es la ruta limpia), un `alias` a una ruta suelta, uno a
# otro remote, una cadena de alias, uno a un alias roto, uno sin destino, un
# ciclo y uno que es una cadena de conexión. Con él se calcula el extremo que
# `FsPath` escribiría.
RCLONE_CONF_TIPOS = """\
[nas]
type = sftp
host = ejemplo.invalid

[disco]
type = local

[crudo]
type = local
nounc = true

[atajo]
type = alias
remote = /datos/compartidos

[sobre_nas]
type = alias
remote = nas:/copia

[sobre_disco]
type = alias
remote = disco:/srv/x

[doble]
type = alias
remote = sobre_nas:sub

[roto]
type = alias
remote = no_existe:/x

[vacio]
type = alias

[bucle_a]
type = alias
remote = bucle_b:

[bucle_b]
type = alias
remote = bucle_a:

[conexion]
type = alias
remote = :sftp,host=x:/y

[raiz]
type = alias
remote = nas:
"""


def vectores_extremos(bisync, pairing) -> dict:
    """`fs_path_remote()` con los tipos de remote del `rclone.conf`: lo que hace
    que el prefijo de una pareja sobre un `alias` o un `local` sea el que rclone
    escribe y no una suposición."""
    tipos = {nombre: bisync.TipoRemote(op.get("type"), op.get("nounc", "").lower() == "true",
                                       op.get("remote", ""))
             for nombre, op in pairing.parse_rclone_conf(RCLONE_CONF_TIPOS).items()}
    extremos = ["nas:/copia/notas", "disco:/srv//x/./y", "disco:rel/../z", "disco:",
                "crudo:/a/b", "atajo:", "atajo:sub/dir", "atajo:/abs/../x",
                "sobre_nas:", "sobre_nas:notas", "sobre_disco:a", "doble:x",
                "roto:x", "vacio:x", "bucle_a:x", "conexion:x", "raiz:",
                "raiz:a", "desconocido:/x", "sin_dos_puntos", "atajo:../../etc"]
    raices = ["", ".", "/", "a//b", "a/./b", "a/../b", "/a/b/", "//a", "///a",
              "a/..", "..", "./x", "x/.", "a/../.."]
    return {
        "rclone_conf": RCLONE_CONF_TIPOS,
        "fs_path_remote": [{"entrada": e, "salida": bisync.fs_path_remote(e, tipos)}
                           for e in extremos],
        "resolver_extremo": [{"entrada": e, "salida": bisync.resolver_extremo(e, tipos)}
                             for e in extremos],
        "raiz_local": [{"entrada": r, "salida": bisync.raiz_local(r)} for r in raices],
    }


def vectores_upstream(model) -> list[dict]:
    """El `upstreams` del remote 'combine', campo a campo.

    Las rutas de Windows entran como `PureWindowsPath` para que salgan con su
    barra invertida sin necesitar un Windows: el caso de la raíz de una unidad
    (`F:\\`) es justo el que tumbaba TODAS las parejas del dispositivo, así que
    el motor Kotlin tiene que reproducirlo aunque nunca vaya a verlo."""
    casos = [
        ("raiz", PurePosixPath("/volumen")),
        ("sync-data", PurePosixPath("/volumen/sync-data")),
        ("con espacios", PurePosixPath("/volumen/con espacios")),
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
            "versions": p.versions,
            "watch": p.watch,
            "reglas": list(p.reglas),
            "llavero": p.llavero,
            "llave_interna": p.llave_interna,
            "es_raiz": p.es_raiz,
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
            "versions_path1": p.versions_path1,
            "versions_path2": p.versions_path2,
            "workdir": p.workdir.name,
            "expected_prefix": bisync.expected_prefix(p, {}),
            "prefijo_comprobable": bisync.prefijo_comprobable(p, {}),
            "filters_content": bisync.filters_content(p),
        })
    from common.model import ConfigError   # noqa: PLC0415
    try:
        config.select(["notas", "inventada"])
        select_error = None
    except ConfigError as e:
        select_error = str(e)
    choque = model.parse_config({
        "defaults": {"device_remote": "disp", "remote": "nas"},
        "pair": [{"name": "todo", "local": ".", "remote_path": "/a"},
                 {"name": "choque", "local": model.RAIZ_UPSTREAM, "remote_path": "/b"}]})
    return {
        "config": CONFIG,
        "select": {"pedidas": ["todo", "notas"],
                   "salida": [p.name for p in config.select(["todo", "notas"])],
                   "error_con": ["notas", "inventada"], "error": select_error},
        "choque_de_upstreams": {"pair": [{"name": p.name, "local": p.local,
                                          "remote_path": p.remote_path}
                                         for p in choque.pairs],
                                "error": _error(choque.pen_environment)["error"]},
        "device_remote": config.device_remote,
        "keep_logs": config.keep_logs,
        "names": config.names,
        "pareja_llavero": config.pareja_llavero.name if config.pareja_llavero else None,
        "carpeta_del_catalogo": list(model.carpeta_del_catalogo(CONFIG["defaults"])),
        "pen_environment": config.pen_environment(),
        "parejas": parejas,
    }


def vectores_estado(bisync, model) -> dict:
    """`pair_state()` y `referencia_vacia()` sobre workdirs de verdad.

    Se leen del disco, así que se montan en una carpeta temporal: cada caso dice
    qué ficheros hay (y con qué contenido) y lo que contesta prdrive. El test de
    Kotlin monta los mismos ficheros y tiene que contestar lo mismo, detalle
    incluido, porque ese detalle es lo que lee el usuario."""
    pareja = model.parse_config(
        {"defaults": {"device_remote": "disp", "remote": "nas"},
         "pair": [{"name": "notas", "local": "sync-data/notas",
                   "remote_path": "/copia/notas"}]}).pairs[0]
    prefijo = bisync.expected_prefix(pareja, {})
    vacio = "# bisync listing v1 from 2026-01-01\n\n"
    lleno = vacio + '-  5 - - 2026-01-01T00:00:00.000000000+0000 "a.txt"\n'
    p1, p2, err = bisync.PATH1_SUFFIX, bisync.PATH2_SUFFIX, bisync.ERR_SUFFIX

    estados = {
        "sin workdir": None,
        "workdir vacío": {},
        "los dos listados": {prefijo + p1: lleno, prefijo + p2: lleno},
        "con residuo": {prefijo + p1: lleno, prefijo + p2: lleno, prefijo + err: lleno},
        "solo residuo": {"algo" + err: lleno, "otro" + err: lleno},
        "falta uno": {prefijo + p1: lleno},
        "dos juegos": {"uno" + p1: lleno, "uno" + p2: lleno, "otro" + p1: lleno,
                       "otro" + p2: lleno},
    }
    referencias = {
        "los dos vacíos": ({prefijo + p1: vacio, prefijo + p2: vacio}, True),
        "uno lleno": ({prefijo + p1: vacio, prefijo + p2: lleno}, True),
        "los dos llenos": ({prefijo + p1: lleno, prefijo + p2: lleno}, True),
        "vacíos sin carpeta local": ({prefijo + p1: vacio, prefijo + p2: vacio}, False),
        "vacíos de otra pareja": ({"otro" + p1: vacio, "otro" + p2: vacio}, True),
        "solo cabecera y blancos": ({prefijo + p1: "# x\n\n\n", prefijo + p2: "\n"}, True),
        "sin baseline": ({}, True),
    }

    salida: dict = {"pareja": {"name": pareja.name, "local": pareja.local,
                               "remote_path": pareja.remote_path,
                               "remote": pareja.remote_name},
                    "prefijo": prefijo, "pair_state": [], "referencia_vacia": []}
    viejos = model.STATE_DIR, model.DEVICE_ROOT
    try:
        for caso, ficheros in estados.items():
            with tempfile.TemporaryDirectory() as tmp:
                model.STATE_DIR = Path(tmp)
                if ficheros is not None:
                    _montar(Path(tmp) / pareja.name, ficheros)
                estado = bisync.pair_state(pareja)
                salida["pair_state"].append({
                    "caso": caso, "ficheros": ficheros, "status": estado.status,
                    "detail": estado.detail, "prefix": estado.prefix})
        for caso, (ficheros, local) in referencias.items():
            with tempfile.TemporaryDirectory() as tmp:
                model.STATE_DIR = Path(tmp) / "state"
                model.DEVICE_ROOT = Path(tmp) / "volumen"
                _montar(model.STATE_DIR / pareja.name, ficheros)
                if local:
                    (model.DEVICE_ROOT / pareja.local).mkdir(parents=True)
                salida["referencia_vacia"].append({
                    "caso": caso, "ficheros": ficheros, "local_existe": local,
                    "vacia": bisync.referencia_vacia(pareja, tipos={})})
    finally:
        model.STATE_DIR, model.DEVICE_ROOT = viejos
    salida.update(_filtros_y_movimientos(bisync, model, pareja))
    return salida


def _filtros_y_movimientos(bisync, model, pareja) -> dict:
    """El estado de los filtros, por qué pide resync una pareja, y apartar y
    renombrar su estado: lo que el usuario lee cuando le piden un resync."""
    import hashlib   # noqa: PLC0415
    from datetime import datetime as _dt   # noqa: PLC0415

    contenido = bisync.filters_content(pareja)
    md5 = hashlib.md5(contenido.encode("utf-8")).hexdigest()
    filtros = {
        "sin md5": None,
        "md5 bueno": md5,
        "md5 bueno con salto de línea": md5 + "\n",
        "md5 de otro contenido": "0" * 32,
    }
    salida: dict = {"filtros": [], "resync_reasons": [], "apartar": [], "renombrar": []}
    viejos = model.STATE_DIR, model.FILTERS_DIR, bisync.datetime
    try:
        with tempfile.TemporaryDirectory() as tmp:
            fichero = Path(tmp) / f"{pareja.name}.txt"
            fichero.write_text(contenido, encoding="utf-8", newline="\n")
            for caso, hash_previo in filtros.items():
                marca = Path(str(fichero) + ".md5")
                if hash_previo is None:
                    marca.unlink(missing_ok=True)
                else:
                    marca.write_text(hash_previo, encoding="utf-8", newline="\n")
                estado = bisync.filters_state(fichero)
                salida["filtros"].append({"caso": caso, "contenido": contenido,
                                          "md5": hash_previo, "status": estado.status,
                                          "detail": estado.detail})
            estado = bisync.filters_state(None)
            salida["sin_fichero"] = {"status": estado.status, "detail": estado.detail}

        prefijo = bisync.expected_prefix(pareja, {})
        lleno = '# x\n-  5 - - 2026-01-01T00:00:00.000000000+0000 "a.txt"\n'
        for caso, listados, con_md5 in (
                ("fresca y filtros nuevos", {}, False),
                ("con baseline y filtros nuevos", {prefijo + bisync.PATH1_SUFFIX: lleno,
                                                   prefijo + bisync.PATH2_SUFFIX: lleno}, False),
                ("con baseline y filtros al día", {prefijo + bisync.PATH1_SUFFIX: lleno,
                                                   prefijo + bisync.PATH2_SUFFIX: lleno}, True)):
            with tempfile.TemporaryDirectory() as tmp:
                model.STATE_DIR = Path(tmp) / "state"
                model.FILTERS_DIR = Path(tmp) / "filters"
                if listados:
                    _montar(model.STATE_DIR / pareja.name, listados)
                if con_md5:
                    ffile = bisync.filters_file_for(pareja)
                    Path(str(ffile) + ".md5").write_text(md5, encoding="utf-8")
                salida["resync_reasons"].append({
                    "caso": caso, "ficheros": listados, "md5": con_md5,
                    "razones": bisync.resync_reasons(pareja)})

        class _Fijo(_dt):
            @classmethod
            def now(cls, tz=None):
                return cls(2026, 10, 1, 11, 1, 1)
        bisync.datetime = _Fijo
        salida["fecha_apartado"] = [2026, 10, 1, 11, 1, 1]
        salida["sello_apartado"] = f"{_Fijo.now():%Y%m%d_%H%M%S}"
        with tempfile.TemporaryDirectory() as tmp:
            model.STATE_DIR = Path(tmp)
            sin = bisync.shelve_baseline("notas")
            (Path(tmp) / "notas").mkdir()
            primera = bisync.shelve_baseline("notas")
            (Path(tmp) / "notas").mkdir()
            segunda = bisync.shelve_baseline("notas")
            salida["apartar"] = [None if sin is None else sin.name, primera.name, segunda.name]

        with tempfile.TemporaryDirectory() as tmp:
            model.STATE_DIR = Path(tmp) / "state"
            model.FILTERS_DIR = Path(tmp) / "filters"
            (model.STATE_DIR / "viejo").mkdir(parents=True)
            model.FILTERS_DIR.mkdir()
            (model.FILTERS_DIR / "viejo.txt").write_text("x", encoding="utf-8")
            (model.FILTERS_DIR / "viejo.txt.md5").write_text("y", encoding="utf-8")
            (model.STATE_DIR / "ocupado").mkdir()
            movidos = bisync.rename_pair_state("viejo", "nuevo")
            salida["renombrar"] = [[o.parent.name + "/" + o.name, d.parent.name + "/" + d.name]
                                   for o, d in movidos]
            salida["renombrar_a_uno_que_existe"] = [
                [o.name, d.name] for o, d in bisync.rename_pair_state("nuevo", "ocupado")]
    finally:
        model.STATE_DIR, model.FILTERS_DIR, bisync.datetime = viejos
    return salida


def _montar(workdir: Path, ficheros: dict[str, str]) -> None:
    workdir.mkdir(parents=True, exist_ok=True)
    for nombre, contenido in ficheros.items():
        (workdir / nombre).write_text(contenido, encoding="utf-8", newline="\n")


def vectores_toml(config_file) -> dict:
    """Lo que escribe el serializador, que es lo que hay que reproducir letra a
    letra: el TOML del dispositivo se edita a mano y se relee."""
    cabecera = "# Generado para los tests.\n# Dos líneas, para probar que sobrevive.\n"
    catalogo = {
        "remote": {"name": "nas", "type": "sftp", "host": "ejemplo.invalid",
                   "port": "22", "user": "usuario"},
        **CONFIG,
    }
    # Dos tablas que este código no conoce, y en desorden: van al final, en
    # orden alfabético, y la vacía se escribe también (es una tabla).
    con_desconocidas = {**CONFIG, "zeta": {"b": 1, "a": "x"}, "alfa": {}}
    return {
        "dumps": [
            {"raw": CONFIG, "head": "", "texto": config_file.dumps(CONFIG)},
            {"raw": CONFIG, "head": cabecera,
             "texto": config_file.dumps(CONFIG, cabecera)},
            {"raw": catalogo, "head": "", "texto": config_file.dumps(catalogo)},
            {"raw": con_desconocidas, "head": "",
             "texto": config_file.dumps(con_desconocidas)},
        ],
        "dumps_table": [
            {"tabla": t, "texto": config_file.dumps_table(t)}
            for t in ({"max-delete": 25, "resilient": True, "max-lock": "2m"},
                      {"exclude": ["*.tmp"], "transfers": 4},
                      {"raro clave": "x"},
                      {"a": 5e-324, "b": 1e22, "c": 0.1, "d": float("inf"), "e": -1e-7},
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
        "tablas_conocidas": list(config_file.TABLAS_CONOCIDAS),
    }


def vectores_pairing(pairing) -> dict:
    """La carga útil del QR: el texto exacto y lo que sale al leerlo.

    La clave no es una clave: son 400 bytes de relleno con la longitud de una
    ed25519 en PEM, que es lo que decide si el QR cabe."""
    clave = b"-----BEGIN OPENSSH PRIVATE KEY-----\n" + b"A" * 380 + \
            b"\n-----END OPENSSH PRIVATE KEY-----\n"
    conocidos = "ejemplo.invalid ssh-ed25519 AAAA...\n"
    texto = pairing.dumps("nas", OPCIONES_REMOTE, key_name="id_ed25519",
                          catalog_path="/prdrive-catalog/remote.toml",
                          private_key=clave, known_hosts=conocidos)
    sin_clave = pairing.dumps("nas", OPCIONES_REMOTE, key_name="id_ed25519",
                              catalog_path="/prdrive-catalog/remote.toml")
    leida = pairing.leer(texto)
    return {
        "marca": pairing.MARCA,
        "formato": pairing.FORMATO,
        "rutas_derivadas": list(pairing.RUTAS_DERIVADAS),
        "default_catalog_path": pairing.DEFAULT_CATALOG_PATH,
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
        # Basura que una cámara puede leer: tiene que ser un «no se puede leer»,
        # nunca una excepción que tumbe la app.
        "escape u roto": 'prdrive = 1\nremote_name = "\\uZZZZ"\n',
        "carácter de control": 'prdrive = 1\nremote_name = "a\x01b"\n',
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


# ---------------------------------------------------------------------------
# Paso 1: el catálogo, los resultados y los conflictos
#
# Los tres leen y escriben en `state/` y hablan con rclone. Se les dan una
# carpeta temporal por `state/` y un rclone de mentira hecho de respuestas
# fijas; lo que se apunta en el JSON es la tabla de respuestas, para que el
# test de Kotlin le ponga delante al motor exactamente el mismo rclone.
# ---------------------------------------------------------------------------

SELLOS = [f"2026-10-0{n} 1{n}:0{n}:0{n}" for n in range(1, 10)]
"""Las fechas que se le hace devolver a `store.stamp()`, una por llamada."""


class _Reloj:
    """Sustituye a `store.stamp()`: devuelve `SELLOS` en orden."""

    def __init__(self, store):
        self.store, self.n = store, 0
        self.original = store.stamp

    def __enter__(self):
        def sello() -> str:
            valor = SELLOS[self.n % len(SELLOS)]
            self.n += 1
            return valor
        self.store.stamp = sello
        return self

    def __exit__(self, *_):
        self.store.stamp = self.original


def vectores_json() -> list[dict]:
    """`json.dumps(…, ensure_ascii=False, indent=1)`, que es como `store.write_json`
    escribe todo lo de `state/`. El escritorio lee lo que escribe el teléfono."""
    casos = [
        {},
        {"a": 1},
        {"parejas": {"notas": {"cuando": "2026-10-01 11:01:01", "codigo": 0, "log": None,
                               "buena": "2026-10-01 11:01:01"}}},
        {"s": 'con "comillas", \\ barra, \n salto, \t tab, ñ, \x01 control y  '},
        {"l": [1, 2.5, True, False, None, [], {}], "vacia": []},
        {"f": 1e20, "g": -0.0, "h": 1.5e-7, "i": 100.0},
        # Los dígitos mínimos de `repr()`, también donde `Double.toString()` no
        # los da (los subnormales) y en el cambio a notación científica.
        {"sub": 5e-324, "sub2": 1e-323, "grande": 1e22, "decima": 0.1, "tercio": 1 / 3,
         "largo": 123456789012345678.0, "borde": 1e16, "casi": 9999999999999998.0,
         "pequeno": 0.0001, "menor": 0.00001, "neg": -2.5e-5},
        [1, "dos", {"tres": 3}],
    ]
    return [{"valor": c, "texto": json.dumps(c, ensure_ascii=False, indent=1)} for c in casos]


def vectores_store(store) -> dict:
    from datetime import datetime   # noqa: PLC0415
    return {"formato": store.FORMATO,
            "ejemplo": f"{datetime(2026, 1, 2, 3, 4, 5):{store.FORMATO}}"}


CATALOGO_TEXTO = """\
# El catálogo de los tests.

[remote]
name = "nas"
type = "sftp"

[defaults]
remote = "nas"
device_remote = "disp"

[[pair]]
name = "notas"
local = "sync-data/notas"
remote_path = "/copia/notas"

[[pair]]
name = "fotos"
local = "sync-data/fotos"
remote_path = "/copia/fotos"
mode = "down"
"""


def _rclone_falso(ficheros: dict[str, str], carpetas: dict[str, str], *,
                  sin_red: bool = False, cubetas: bool = False) -> dict:
    """La tabla de respuestas de un rclone de mentira.

    `ficheros` son `endpoint: contenido`; `carpetas`, `endpoint: lo que da cat`
    (rclone concatena los ficheros de dentro). Lo que no está no existe: `cat`
    sale con 3, salvo en un remoto de cubetas, donde sale con 0 y vacío."""
    respuestas = {}
    for donde, texto in ficheros.items():
        respuestas[f"cat {donde}"] = [0, texto, ""]
        respuestas[f"lsjson --stat {donde}"] = [
            0, json.dumps({"Path": donde.rsplit("/", 1)[-1], "IsDir": False}), ""]
    for donde, texto in carpetas.items():
        respuestas[f"cat {donde}"] = [0, texto, ""]
        respuestas[f"lsjson --stat {donde}"] = [
            0, json.dumps({"Path": donde.rsplit("/", 1)[-1], "IsDir": True}), ""]
    if sin_red:
        defecto = [1, "", "Failed to cat: dial tcp: lookup nas: no such host"]
        respuestas = {}
    elif cubetas:
        defecto = [0, "", ""]
    else:
        defecto = [3, "", "error: directory not found"]
    return {"respuestas": respuestas, "por_defecto": defecto}


def _ejecutar_falso(tabla: dict):
    import subprocess as sp   # noqa: PLC0415

    def ejecutar(args: list[str]) -> sp.CompletedProcess:
        codigo, salida, error = tabla["respuestas"].get(" ".join(args), tabla["por_defecto"])
        return sp.CompletedProcess(args, codigo, salida, error)
    return ejecutar


def vectores_catalogo(catalog, model, store) -> dict:
    """El catálogo: dónde se busca, cómo se lee y qué se dice cuando no se puede."""
    from common.model import ConfigError   # noqa: PLC0415
    endpoints = [None, {"defaults": {}}, {"defaults": {"remote": "nas"}},
                 {"defaults": {"remote": "nas", "catalog_remote": "cat",
                               "catalog_path": "/x/pairs.toml"}},
                 {"defaults": {"remote": "nas", "catalog_path": ""}}]
    rutas = ["nas:/prdrive-catalog/remote.toml", "nas:remote.toml", "nas:",
             "nas:/a/b/", "/local/x.toml", "remote.toml", "nas:/c/pairs.toml",
             "nas:/c/otro.toml", "nas:pairs.toml", "nas:/c/REMOTE.toml"]
    dentro = [("",), ("nas:",), ("nas:/c",), ("/c/",), ("nas:/c", "pairs.toml"), ("c",)]
    problemas = ["", "  ", "/prdrive-catalog", "/prdrive-catalog/remote.toml",
                 "/x/Y.TOML", "nas:/c", "/c/"]

    defecto = {"defaults": {"remote": "nas"}}
    nuevo = "nas:/prdrive-catalog/remote.toml"
    viejo = "nas:/prdrive-catalog/pairs.toml"
    carpeta = "nas:/prdrive-catalog"
    escenarios = {
        "remote.toml normal": (defecto, _rclone_falso({nuevo: CATALOGO_TEXTO}, {})),
        "un remoto de antes conserva pairs.toml": (
            defecto, _rclone_falso({viejo: CATALOGO_TEXTO}, {})),
        "un dispositivo de antes encuentra remote.toml": (
            {"defaults": {"remote": "nas", "catalog_path": "/prdrive-catalog/pairs.toml"}},
            _rclone_falso({nuevo: CATALOGO_TEXTO}, {})),
        "con los dos manda remote.toml": (
            defecto, _rclone_falso({nuevo: CATALOGO_TEXTO, viejo: "[defaults]\n"}, {})),
        "no hay ninguno": (defecto, _rclone_falso({}, {})),
        "sin red": (defecto, _rclone_falso({}, {}, sin_red=True)),
        "remoto de cubetas sin catálogo": (defecto, _rclone_falso({}, {}, cubetas=True)),
        "remoto de cubetas con pairs.toml": (
            defecto, _rclone_falso({viejo: CATALOGO_TEXTO}, {}, cubetas=True)),
        "la ruta es la carpeta": (
            {"defaults": {"remote": "nas", "catalog_path": "/prdrive-catalog"}},
            _rclone_falso({nuevo: CATALOGO_TEXTO},
                          {carpeta: CATALOGO_TEXTO + "\n" + CATALOGO_TEXTO})),
        "la carpeta solo tiene el catálogo": (
            {"defaults": {"remote": "nas", "catalog_path": "/prdrive-catalog"}},
            _rclone_falso({viejo: CATALOGO_TEXTO}, {carpeta: CATALOGO_TEXTO})),
        "la carpeta vacía": (
            {"defaults": {"remote": "nas", "catalog_path": "/prdrive-catalog"}},
            _rclone_falso({}, {carpeta: ""})),
        "TOML roto": (defecto, _rclone_falso({nuevo: "esto no [ es toml\n"}, {})),
        "catalog_remote propio": (
            {"defaults": {"remote": "nas", "catalog_remote": "cat"}},
            _rclone_falso({"cat:/prdrive-catalog/remote.toml": CATALOGO_TEXTO}, {})),
    }
    salida_escenarios = []
    viejos = catalog.run, model.STATE_DIR
    try:
        for caso, (raw_local, tabla) in escenarios.items():
            with tempfile.TemporaryDirectory() as tmp, _Reloj(store):
                model.STATE_DIR = Path(tmp)
                catalog.run = _ejecutar_falso(tabla)
                where = catalog.endpoint(raw_local)
                res, cual = catalog.leer(catalog.run, where)
                registro = {"caso": caso, "raw_local": raw_local, "rclone": tabla,
                            "endpoint": where,
                            "leer": {"codigo": res.returncode, "salida": res.stdout,
                                     "error": res.stderr, "cual": cual}}
                try:
                    cat = catalog.pull(raw_local)
                except ConfigError as e:
                    mensaje = str(e)
                    registro["pull"] = {"error": mensaje}
                    # El detalle de un TOML que no se lee es el de tomllib, que el
                    # lector de Kotlin no dice con las mismas palabras.
                    marca = " no es TOML válido: "
                    if marca in mensaje:
                        registro["pull"]["error"] = mensaje.split(marca)[0] + marca
                        registro["pull"]["detalle_del_parser"] = True
                else:
                    registro["pull"] = {"text": cat.text, "source": cat.source,
                                        "stamp": cat.stamp, "endpoint": cat.endpoint,
                                        "names": cat.names, "defaults": cat.defaults}
                copia = Path(tmp) / "catalog.toml"
                registro["copia"] = {
                    "toml": copia.read_text(encoding="utf-8") if copia.exists() else None,
                    "json": ((Path(tmp) / "catalog.json").read_text(encoding="utf-8")
                             if (Path(tmp) / "catalog.json").exists() else None)}
                # Y lo que dice `load()` si después se va la red: la copia, o nada.
                catalog.run = _ejecutar_falso(_rclone_falso({}, {}, sin_red=True))
                cat, aviso = catalog.load(raw_local)
                registro["load_sin_red"] = {
                    "source": cat.source if cat else None,
                    "stamp": cat.stamp if cat else None,
                    "endpoint": cat.endpoint if cat else None,
                    "aviso": aviso}
                salida_escenarios.append(registro)
    finally:
        catalog.run, model.STATE_DIR = viejos

    raw = model.parse_config  # noqa: F841 (solo para que se vea de dónde sale el texto)
    leido = catalog.Catalog(raw=__import__("tomllib").loads(CATALOGO_TEXTO),
                            text=CATALOGO_TEXTO, source="remote", stamp=SELLOS[0],
                            endpoint=nuevo)
    parejas = [{"name": "a", "local": "x"}, {"name": "b", "mode": "up"}]
    return {
        "fichero": catalog.FICHERO,
        "fichero_anterior": catalog.FICHERO_ANTERIOR,
        "nombres": list(catalog.NOMBRES),
        "rc_no_existe": list(catalog.RC_NO_EXISTE),
        "net_flags": list(catalog.NET_FLAGS),
        "sin_fecha": catalog.SIN_FECHA,
        # Lo que contesta el rclone de mentira sin red, para que el test no lo copie.
        "rclone_sin_red": _rclone_falso({}, {}, sin_red=True)["por_defecto"],
        "endpoint": [{"raw_local": r, "salida": catalog.endpoint(r)} for r in endpoints],
        "partir": [{"entrada": r, "salida": list(catalog.partir(r))} for r in rutas],
        "candidatos": [{"entrada": r, "salida": list(catalog.candidatos(r))} for r in rutas],
        "dentro": [{"entrada": list(d), "salida": catalog._dentro(*d)} for d in dentro],
        "problema_de_ruta": [{"entrada": p, "salida": catalog.problema_de_ruta(p)}
                             for p in problemas],
        "escenarios": salida_escenarios,
        "pairs_by_name": catalog.pairs_by_name(leido),
        "diff_keys": [{"a": a, "b": b, "salida": list(catalog.diff_keys(a, b))}
                      for a, b in ((parejas[0], parejas[1]), (parejas[0], parejas[0]),
                                   (None, parejas[1]), ({"x": 1}, {"x": 1.0}))],
    }


def vectores_resultados(results, model, store) -> dict:
    """`state/last_run.json`: lo que apunta cada pasada y lo que se lee de él."""
    from pathlib import Path as P   # noqa: PLC0415
    viejos = model.STATE_DIR, model.LOG_DIR
    pasos = [("notas", 0, None), ("fotos", 2, "fotos-1.log"), ("notas", 1, "notas-1.log"),
             ("fotos", 0, None), ("notas", 1, "notas-2.log")]
    salida: dict = {"pasos": [], "fallos": [], "buenas": {}}
    try:
        with tempfile.TemporaryDirectory() as tmp, _Reloj(store):
            model.STATE_DIR = P(tmp) / "state"
            model.LOG_DIR = P(tmp) / "logs"
            model.STATE_DIR.mkdir()
            model.LOG_DIR.mkdir()
            (model.LOG_DIR / "notas-2.log").write_text("x", encoding="utf-8")
            for nombre, codigo, log in pasos:
                results.apuntar(nombre, codigo, model.LOG_DIR / log if log else None)
                salida["pasos"].append({
                    "nombre": nombre, "codigo": codigo, "log": log,
                    "texto": results.ruta_estado().read_text(encoding="utf-8")})
            nombres = ["notas", "fotos", "nunca"]
            salida["fallos"] = [_fallo(f) for f in results.fallos_de(nombres)]
            datos = json.loads(results.ruta_estado().read_text(encoding="utf-8"))["parejas"]
            salida["buenas"] = {n: results._buena(datos[n]) for n in ("notas", "fotos")}

            # Registros de antes de la clave `buena`, y basura.
            legado = {"parejas": {
                "vieja_buena": {"cuando": SELLOS[0], "codigo": 0},
                "vieja_mala": {"cuando": SELLOS[1], "codigo": 3, "log": "no-esta.log"},
                "codigo_texto": {"cuando": SELLOS[2], "codigo": "4"},
                "codigo_roto": {"cuando": SELLOS[3], "codigo": "x"},
                "no_es_tabla": "hola",
                "log_con_ruta": {"cuando": SELLOS[4], "codigo": 1,
                                 "log": "../../fuera/notas-2.log"},
            }}
            texto_legado = json.dumps(legado, ensure_ascii=False, indent=1)
            results.ruta_estado().write_text(texto_legado, encoding="utf-8")
            todos = list(legado["parejas"]) + ["nunca"]
            salida["legado"] = {
                "texto": texto_legado,
                "fallos": [_fallo(f) for f in results.fallos_de(todos)],
                "buenas": {n: results._buena(d) for n, d in legado["parejas"].items()
                           if isinstance(d, dict)},
                "ultimas_buenas": sorted(results.ultimas_buenas(todos)),
            }
    finally:
        model.STATE_DIR, model.LOG_DIR = viejos
    return salida


def _fallo(f) -> dict:
    return {"pareja": f.pareja, "cuando": f.cuando, "codigo": f.codigo,
            "log": f.log.name if f.log else None, "buena": f.buena}


def vectores_conflictos(conflicts, model, store) -> dict:
    """Los perdedores de bisync: cómo se llaman según los flags, de qué lado
    vienen y cómo se agrupan con su original."""
    from pathlib import Path as P   # noqa: PLC0415

    def pareja(**extra):
        crudo = {"defaults": {"device_remote": "disp", "remote": "nas"},
                 "pair": [{"name": "notas", "local": "sync-data/notas",
                           "remote_path": "/copia/notas", **extra}]}
        return model.parse_config(crudo).pairs[0]

    extras = {
        "de fábrica": {},
        "un sufijo": {"flags": {"conflict_suffix": "uno"}},
        "tres sufijos": {"flags": {"conflict-suffix": "a,b,c"}},
        "sufijo vacío": {"flags": {"conflict-suffix": ""}},
        "pathname": {"flags": {"conflict-suffix": "c", "conflict-loser": "pathname"}},
        "num con un sufijo": {"flags": {"conflict-suffix": "c", "conflict-loser": "num"}},
        # `suffix-keep-extension` no se puede escribir en el config (lo pone la
        # pasada): una pareja lo lleva por tener versiones.
        "con versiones": {"versions": True},
        "con versiones y un sufijo": {"versions": True, "flags": {"conflict-suffix": "c"}},
        "de bajada": {"mode": "down"},
    }
    parejas = {n: pareja(**e) for n, e in extras.items()}
    nombres = ["plan.md", "plan.md.conflicto-dispositivo1", "plan.md.conflicto-remoto2",
               "plan.md.conflicto-remoto", "plan.conflicto-remoto1.md",
               "plan.md.conflict1", "plan.md.conflict", "a.tar.gz.c1", "a.c2.tar.gz",
               "x.uno3", "x.uno", "y.a1", "y.b2", "y.c3", "z.c1", "z.c2", "z.c3",
               ".conflicto-remoto1", "datos.conflicto-dispositivo12",
               "plan.conflict1.md", "uno.conflict1.dos.txt",
               # Lo que separa el `.` y el `$` de Python de los de Java y Android,
               # y números que no caben en un Int ni en un Long.
               "a b.txt.conflict1", "a\rb.conflict2", "a\x85b.conflict3",
               "a\x0bb.conflict4", "a\x0cb.conflict5", "a b.conflict6",
               "plan.md.conflict1\n", "plan.conflict1.md\n", "plan.md.conflict1\r",
               "a\nb.conflict1", "foo.conflict99999999999", "foo.conflict" + "9" * 25,
               "x.conflict١٢", "x.c١", "IMG.c20240101123456.jpg"]
    esquemas = {n: list(conflicts.esquema(p)) for n, p in parejas.items()}
    lecturas = []
    for n, p in parejas.items():
        esq = conflicts.esquema(p)
        for nombre in nombres:
            leido = conflicts.leer_nombre(nombre, esq)
            lecturas.append({"esquema": n, "nombre": nombre,
                             "salida": list(leido) if leido else None})

    ficheros_notas = [
        "plan.md", "plan.md.conflicto-dispositivo1",
        "a/b.txt.conflicto-remoto1",
        "c.txt", "c.txt.conflicto-dispositivo1", "c.txt.conflicto-dispositivo2",
        "d.txt", "d.txt.conflict1",
        "e.txt", "e.txt.conflicto-remoto1", "e.txt.conflicto-dispositivo1",
        "solo.txt.conflict",
        ".prversions/v.txt.conflicto-remoto1",
        "sub/.prversions/w.txt.conflicto-remoto1",
    ]
    ficheros_raiz = [
        "doc.txt", "doc.txt.conflicto-remoto1",
        ".prdrive/k.txt.conflicto-remoto1",
        ".keychain/x.kdbx.conflicto-remoto1",
        "System Volume Information/s.conflicto-remoto1",
        ".Trash-1000/t.conflicto-remoto1",
        "carpeta/.prdrive/p.conflicto-remoto1",
    ]
    escenarios = []
    viejos = model.DEVICE_ROOT, model.STATE_DIR
    try:
        for caso, crudo, ficheros in (
                ("una pareja", {"name": "notas", "local": "sync-data/notas",
                                "remote_path": "/copia/notas"}, ficheros_notas),
                ("la raíz entera", {"name": "todo", "local": ".",
                                    "remote_path": "/copia/todo"}, ficheros_raiz)):
            with tempfile.TemporaryDirectory() as tmp, _Reloj(store):
                # `resolve()`: en Windows la carpeta temporal puede tener un nombre
                # corto (8.3) y `local_abs` sale con el largo.
                model.DEVICE_ROOT = P(tmp).resolve() / "volumen"
                model.STATE_DIR = P(tmp).resolve() / "state"
                model.STATE_DIR.mkdir()
                p = model.parse_config({"defaults": {"device_remote": "disp", "remote": "nas"},
                                        "pair": [crudo]}).pairs[0]
                for f in ficheros:
                    ruta = p.local_abs / f
                    ruta.parent.mkdir(parents=True, exist_ok=True)
                    ruta.write_text("x", encoding="utf-8")
                encontrados = conflicts.actualizar_pareja(p)
                # Este script corre en Windows, donde una ruta se ordena sin
                # distinguir mayúsculas y con `\`, y en el teléfono es POSIX: el
                # orden que sale solo vale de vector si es el mismo de las dos
                # maneras. Un caso nuevo que lo rompa tiene que pararse aquí.
                relativas = [c.original.relative_to(model.DEVICE_ROOT).as_posix() for c in encontrados]
                assert sorted(relativas, key=PurePosixPath) == sorted(relativas, key=PureWindowsPath), \
                    f"«{caso}»: estos nombres no se ordenan igual en Windows que en Android: {relativas}"
                registro = {"caso": caso, "pareja": crudo, "ficheros": ficheros,
                            "conflictos": [_conflicto(c, model.DEVICE_ROOT) for c in encontrados],
                            "estado": conflicts.ruta_estado().read_text(encoding="utf-8"),
                            "sin_apuntar": conflicts.sin_apuntar(p.name),
                            "contar": conflicts.contar({p.name: encontrados})}
                # Se borra una copia y se carga sin recorrer: el aviso se va solo.
                if encontrados:
                    borrada = encontrados[0].copias[0]
                    borrada.unlink()
                    registro["borrada"] = borrada.relative_to(model.DEVICE_ROOT).as_posix()
                    config = model.parse_config({"defaults": {"device_remote": "disp",
                                                              "remote": "nas"},
                                                 "pair": [crudo]})
                    cargados = conflicts.cargar(config)
                    registro["cargar"] = {n: [_conflicto(c, model.DEVICE_ROOT) for c in lista]
                                          for n, lista in cargados.items()}
                escenarios.append(registro)
    finally:
        model.DEVICE_ROOT, model.STATE_DIR = viejos
    return {"dispositivo": conflicts.DISPOSITIVO, "remoto": conflicts.REMOTO,
            "sufijo_rclone": conflicts.SUFIJO_RCLONE,
            "perdedor_rclone": conflicts.PERDEDOR_RCLONE,
            "base": {"defaults": {"device_remote": "disp", "remote": "nas"},
                     "pair": {"name": "notas", "local": "sync-data/notas",
                              "remote_path": "/copia/notas"}},
            "extras": extras, "esquemas": esquemas, "leer_nombre": lecturas,
            "escenarios": escenarios}


def _conflicto(c, raiz) -> dict:
    return {"pareja": c.pareja, "relativa": c.relativa,
            "original": c.original.relative_to(raiz).as_posix(),
            "versiones": [{"ruta": v.ruta.relative_to(raiz).as_posix(), "lado": v.lado,
                           "es_original": v.es_original, "numero": v.numero}
                          for v in c.versiones]}


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__.strip().splitlines()[0])
        print("\nUso: python herramientas/vectores.py <ruta al checkout de prdrive>")
        return 2
    prdrive = Path(sys.argv[1]).expanduser().resolve()
    if not (prdrive / "common" / "model.py").is_file():
        print(f"No parece un checkout de prdrive: {prdrive}")
        return 2

    bisync, config_file, model, pairing = cargar(prdrive)
    from common import catalog, conflicts, historial, kdbx, llavero, results, store, update   # noqa: PLC0415
    datos = {
        "_generado_por": "herramientas/vectores.py",
        "_no_editar": "Se regenera desde el prdrive citado en 'prdrive'.",
        "prdrive": procedencia(prdrive),
        "raiz_dispositivo": RAIZ_FALSA,
        # La versión de rclone que fija prdrive, que es la que lleva el .aar.
        "rclone_version": __import__("common.pins", fromlist=["RCLONE_VERSION"]).RCLONE_VERSION,
        "carpeta_programa": CARPETA_PROGRAMA,
        "base_flags": dict(model.BASE_FLAGS),
        "constantes": {
            "DEFAULT_REMOTE": model.DEFAULT_REMOTE,
            "DEFAULT_MODE": model.DEFAULT_MODE,
            "DEFAULT_DEVICE_REMOTE": model.DEFAULT_DEVICE_REMOTE,
            "DEFAULT_CATALOG_PATH": model.DEFAULT_CATALOG_PATH,
            "RAIZ_UPSTREAM": model.RAIZ_UPSTREAM,
            "VENTANA_HORA": model.VENTANA_HORA,
            "VERSIONS_DIR": model.VERSIONS_DIR,
            "LLAVERO": model.LLAVERO,
            "LLAVERO_LOCAL": model.LLAVERO_LOCAL,
            "LLAVERO_REMOTO": model.LLAVERO_REMOTO,
            "LLAVERO_REGLAS": list(model.LLAVERO_REGLAS),
            "LLAVERO_FLAGS": dict(model.LLAVERO_FLAGS),
            "REGLA_SIN_LLAVERO": model.REGLA_SIN_LLAVERO,
            "REGLA_SIN_PROGRAMA": model.REGLA_SIN_PROGRAMA,
            "PATH1_SUFFIX": bisync.PATH1_SUFFIX,
            "PATH2_SUFFIX": bisync.PATH2_SUFFIX,
            "ERR_SUFFIX": bisync.ERR_SUFFIX,
            "MISSING_LISTINGS": bisync.MISSING_LISTINGS,
            "FILTERS_HEADER": bisync.FILTERS_HEADER,
            "TOPE_ALIAS": bisync.TOPE_ALIAS,
        },
        "flags_to_args": vectores_flags(model),
        "modos": vectores_modos(model),
        "seguridad": vectores_seguridad(model),
        "canonical": vectores_canonical(bisync),
        "extremos": vectores_extremos(bisync, pairing),
        "upstream": vectores_upstream(model),
        "resueltas": vectores_parejas(bisync, model),
        "estado": vectores_estado(bisync, model),
        "toml": vectores_toml(config_file),
        "pairing": vectores_pairing(pairing),
        "json": vectores_json(),
        "store": vectores_store(store),
        "sellos": SELLOS,
        "ruido_del_sistema": list(model.RUIDO_DEL_SISTEMA),
        "catalogo": vectores_catalogo(catalog, model, store),
        "resultados": vectores_resultados(results, model, store),
        "conflictos": vectores_conflictos(conflicts, model, store),
        # La pasada y el volumen (paso 3), en su propio fichero.
        "paso3": vectores_paso3.vectores(model, bisync, CONFIG),
        # El llavero (la base de KeePassXC), también en su fichero.
        "llavero": vectores_llavero.vectores(kdbx, llavero, model, bisync),
        # Lo que el motor imita del propio Python, también en su fichero.
        "python": vectores_python.vectores(catalog, pairing, config_file),
        # El diario de pasadas (common/historial.py), también en su fichero.
        "historial": vectores_historial.vectores(historial, model, store),
        # Si hay versión nueva (common/update.py), con el repositorio de la app.
        "actualizacion": vectores_actualizacion.vectores(update, store),
    }

    DESTINO.parent.mkdir(parents=True, exist_ok=True)
    DESTINO.write_text(json.dumps(datos, indent=2, ensure_ascii=False,
                                  sort_keys=False) + "\n", encoding="utf-8",
                       newline="\n")
    print(f"{DESTINO.relative_to(Path.cwd()) if DESTINO.is_relative_to(Path.cwd()) else DESTINO}: "
          f"prdrive {datos['prdrive']['version']} "
          f"({datos['prdrive']['commit'][:8] or 'sin commit'})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
