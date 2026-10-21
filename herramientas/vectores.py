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


def _error(funcion, *args) -> str | None:
    """El mensaje de `ConfigError` que lanza `funcion(*args)`, o `None` si no lanza."""
    from common.model import ConfigError   # noqa: PLC0415
    try:
        funcion(*args)
    except ConfigError as e:
        return str(e)
    return None


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
             "sftp-ssh=sh -c id", " max-delete ", "bearer-token-command", "checksum"]
    extras = [[], ["--fast-list"], ["--sftp-ssh=sh -c id"], ["-v", "--config", "x"],
              ["8M", "sh -c id"], ["--checkers", "8", "--rc-addr=:5572"],
              ["--max-delete=10", "--memprofile=/tmp/x"]]
    valores = ["8M", 25, True, "--sftp-ssh=sh -c id", ["a", "--config=x"], ["a", 3],
               None, "-1", ["--fast-list"]]
    remotes = ["nas", "mi nas", "nas,ssh='sh -c id'", ":sftp,host=x", "C", "c", "ñ",
               "-nas", "nas ", "a.b+c@d-e_f", "", 5, None, "dos  espacios", "año",
               "nas:x", "nas=1", 'n"a', "ab", "1"]
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
    }
    return [{"caso": caso, "config": crudo, "error": _error(model.parse_config, crudo)}
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
    return {
        "config": CONFIG,
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
    datos = {
        "_generado_por": "herramientas/vectores.py",
        "_no_editar": "Se regenera desde el prdrive citado en 'prdrive'.",
        "prdrive": procedencia(prdrive),
        "raiz_dispositivo": RAIZ_FALSA,
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
