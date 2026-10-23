"""
vectores_llavero.py — Lo que el motor necesita de lo que prdrive hace con el
llavero (la base de KeePassXC): hoy, si una base está entera (`common/kdbx.py`).

Lo importa `vectores.py` (que ya ha preparado prdrive) y mete lo que devuelve
[vectores] en el mismo JSON, en la sección `llavero`. Va aparte solo para que
`vectores.py` no crezca sin fin.

Las bases se fabrican con la forma del formato, como en `tests/test_kdbx.py` de
prdrive (las firmas, la cabecera con sus campos, la suma de KDBX 4 y su cadena
de bloques): no hace falta ninguna base de verdad. Lo esperado de cada una lo
contesta `kdbx.mirar()` de prdrive; aquí no se escribe ni un resultado.

Las secciones que tocan el disco (`leeme`, `listado`) montan cada caso en una
carpeta temporal, con `model.DEVICE_ROOT` y `STATE_DIR` reenganchados a ella y
`llavero.dormir` sin espera, y apuntan lo que contesta `common/llavero.py`. Las
horas de los ficheros van en nanosegundos y siempre en múltiplos de 100: NTFS
las guarda en tics de 100 ns, y así sale igual en cualquier sistema.

La sección `repeticiones` ejecuta `sync.repetir_sin_freno()` sobre una pasada
parada por cada freno, con rclone, el `mkdir` del remoto y el destino de los
logs cambiados por grabadoras: qué órdenes manda, con qué pareja, y qué dice.
"""

from __future__ import annotations

import hashlib
import json
import os
import struct
import tempfile
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

AES = bytes.fromhex("31c1f2e6bf714350be5805216afc5aff")
TWOFISH = bytes.fromhex("ad68f29f576f4bb9a36ad47af965346c")
CHACHA = bytes.fromhex("d6038a2b8b6f4cb5a524339a31dbb59a")
SEMILLA = bytes(range(32))


def _cabecera(kdbx, mayor: int, campos: list[tuple[int, bytes]], menor: int = 1) -> bytes:
    """Las firmas, la versión y los campos, con su `EndOfHeader`."""
    ancho = "<BH" if mayor == 3 else "<BI"
    datos = struct.pack("<IIHH", kdbx.FIRMA_1, kdbx.FIRMA_2, menor, mayor)
    for campo, valor in campos + [(0, b"\r\n\r\n")]:
        datos += struct.pack(ancho, campo, len(valor)) + valor
    return datos


def _kdbx4(kdbx, bloques: list[bytes], semilla: bytes = SEMILLA) -> bytes:
    """Una KDBX 4 de mentira con esos bloques de datos."""
    cab = _cabecera(kdbx, 4, [(2, AES), (4, semilla), (7, b"\x05" * 16)])
    datos = cab + hashlib.sha256(cab).digest() + b"\x01" * 32
    for bloque in bloques + [b""]:
        datos += b"\x02" * 32 + struct.pack("<i", len(bloque)) + bloque
    return datos


def _kdbx3(kdbx, cifrado: bytes, cuerpo: int) -> bytes:
    """Una KDBX 3.1 de mentira con un cuerpo de ese largo."""
    return _cabecera(kdbx, 3, [(2, cifrado), (4, SEMILLA)]) + b"\xaa" * cuerpo


def _caso(kdbx, nombre: str, datos: bytes) -> dict:
    e = kdbx.mirar(datos)
    return {"caso": nombre, "hex": datos.hex(), "entera": e.entera,
            "version": e.version, "motivo": e.motivo}


def vectores_kdbx(kdbx) -> list[dict]:
    """`kdbx.mirar()`: la base entera, cada corte, y los defectos que se ven sin clave."""
    casos: list[dict] = []

    def poner(nombre: str, datos: bytes) -> None:
        casos.append(_caso(kdbx, nombre, datos))

    # KDBX 4: entera, cortada en cada byte, con un byte de más y con la cabecera tocada.
    base = _kdbx4(kdbx, [b"x" * 20, b"y" * 8])
    poner("KDBX 4 entera", base)
    for n in range(len(base)):
        poner(f"KDBX 4 cortada en {n}", base[:n])
    poner("KDBX 4 con un byte de más", base + b"x")
    for pos in (12, 20, 40):
        mala = bytearray(base)
        mala[pos] ^= 1                               # dentro de la cabecera
        poner(f"KDBX 4 con la cabecera tocada en {pos}", bytes(mala))
    poner("KDBX 4 sin bloques de datos", _kdbx4(kdbx, []))
    poner("KDBX 4 con otra semilla", _kdbx4(kdbx, [b"x" * 20, b"y" * 8], semilla=b"\x09" * 32))
    # un bloque con el tamaño negativo, y uno que se pasa del fichero
    cab = _cabecera(kdbx, 4, [(2, AES), (4, SEMILLA)])
    previo = cab + hashlib.sha256(cab).digest() + b"\x01" * 32
    poner("KDBX 4 con un bloque de tamaño negativo",
          previo + b"\x02" * 32 + struct.pack("<i", -1) + b"zz")
    poner("KDBX 4 con un bloque que se pasa del fichero",
          previo + b"\x02" * 32 + struct.pack("<i", 1000) + b"zz")
    # un campo de la cabecera de 4 GiB, que no cabe en el fichero
    enorme = struct.pack("<IIHH", kdbx.FIRMA_1, kdbx.FIRMA_2, 1, 4) \
        + struct.pack("<BI", 2, 0xFFFFFFFF) + AES
    poner("KDBX 4 con un campo de la cabecera de 4 GiB", enorme)

    # KDBX 3: con AES, Twofish y otro cifrado; el cuerpo, múltiplo de 16 o no.
    for nombre, cifrado in (("AES", AES), ("Twofish", TWOFISH), ("otro cifrado", CHACHA)):
        for cuerpo in (0, 1, 15, 16, 17, 1183, 1184):
            poner(f"KDBX 3 con {nombre} y el cuerpo de {cuerpo}", _kdbx3(kdbx, cifrado, cuerpo))
    cab3 = _kdbx3(kdbx, AES, 0)
    for n in range(len(cab3)):
        poner(f"KDBX 3 cortada en {n}", cab3[:n])
    # sin campo de cifrado: no es AES ni Twofish, solo se mira la cabecera
    poner("KDBX 3 sin campo de cifrado",
          _cabecera(kdbx, 3, [(4, SEMILLA)]) + b"\xaa" * 1183)
    # el último CipherID es el que vale
    poner("KDBX 3 con dos campos de cifrado",
          _cabecera(kdbx, 3, [(2, AES), (2, CHACHA)]) + b"\xaa" * 1183)

    # versiones que no se conocen, y lo que no es una base
    firmas = struct.pack("<IIHH", kdbx.FIRMA_1, kdbx.FIRMA_2, 0, 5)
    poner("una versión que no se conoce (5.0)", firmas + b"\0" * 50)
    poner("una versión que no se conoce (2.0)",
          struct.pack("<IIHH", kdbx.FIRMA_1, kdbx.FIRMA_2, 0, 2) + b"\0" * 50)
    poner("KeePass 1.x (otra segunda firma)",
          struct.pack("<IIHH", kdbx.FIRMA_1, 0xB54BFB65, 1, 3) + b"\0" * 50)
    poner("un zip", b"PK\x03\x04" + b"\0" * 40)
    poner("vacío", b"")
    return casos


def vectores_comprobar(kdbx) -> dict:
    """`kdbx.comprobar()` de un fichero que no se lee: el motivo lleva el texto
    del sistema operativo entre paréntesis, así que solo se apunta cómo empieza."""
    e = kdbx.comprobar("/no/existe/este.kdbx")
    return {"entera": e.entera, "version": e.version,
            "motivo_empieza": e.motivo.split(" (", 1)[0] + " ("}


# --- el LEEME, las bases rotas y lo pendiente (common/llavero.py) -------------

SEG = 1_000_000_000
"""Nanosegundos en un segundo."""
HORA = 1_790_000_000 * SEG
"""Una hora cualquiera (de 2026) para los ficheros de los casos, en ns."""
CASI_LA_VENTANA = 1_900_000_000
"""1,9 s en ns: dentro de la ventana de 2 s de rclone."""
OTRO_LEEME = "Esto no lo ha puesto prdrive.\r\nLo ha escrito una persona.\r\n"
"""Un `LEEME.txt` que dice otra cosa, con CRLF."""

CONFIG_LLAVERO = {
    "defaults": {"device_remote": "disp", "remote": "nas"},
    "keychain": {"base": "claves.kdbx"},
    "pair": [{"name": "notas", "local": "sync-data/notas", "remote_path": "/copia/notas"}],
}
"""El config mínimo con llavero: su pareja es la que tiene el workdir de los listados."""


@contextmanager
def _montado(model, llavero, esperas: list, durante=None):
    """Una raíz de dispositivo temporal, con prdrive enganchado a ella.

    Args:
        esperas: Aquí se apunta cada `llavero.dormir()` (en segundos), sin esperar.
        durante: Qué hacer en cada espera, con la carpeta del llavero (por ejemplo,
            acabar de guardar una base).
    """
    viejos = model.DEVICE_ROOT, model.STATE_DIR, llavero.dormir
    with tempfile.TemporaryDirectory() as tmp:
        raiz = Path(tmp) / "volumen"
        raiz.mkdir()
        model.DEVICE_ROOT = raiz
        model.STATE_DIR = raiz / ".prdrive" / "state"

        def dormir(segundos: float) -> None:
            esperas.append(segundos)
            if durante is not None:
                durante(raiz / model.LLAVERO_LOCAL)

        llavero.dormir = dormir
        try:
            yield raiz
        finally:
            model.DEVICE_ROOT, model.STATE_DIR, llavero.dormir = viejos


def _poner(donde: Path, ficheros: list[dict]) -> None:
    """Escribe los ficheros de un caso: `ruta`, `hex` (o `tamano` de `x`) y `mtime_ns`."""
    for f in ficheros:
        ruta = donde / f["ruta"]
        ruta.parent.mkdir(parents=True, exist_ok=True)
        ruta.write_bytes(bytes.fromhex(f["hex"]) if "hex" in f else b"x" * f["tamano"])
    for f in ficheros:
        if f.get("mtime_ns") is not None:
            os.utime(donde / f["ruta"], ns=(f["mtime_ns"], f["mtime_ns"]))


def _fichero(ruta: str, datos: bytes, mtime_ns: int | None = None) -> dict:
    return {"ruta": ruta, "hex": datos.hex(), "mtime_ns": mtime_ns}


def _leeme_despues(donde: Path, nombre: str) -> dict:
    ruta = donde / nombre
    if not ruta.is_file():
        return {"existe": False, "hex": None, "mtime_ns": None}
    return {"existe": True, "hex": ruta.read_bytes().hex(), "mtime_ns": ruta.stat().st_mtime_ns}


def _ficheros_despues(donde: Path) -> list[str]:
    return sorted(p.relative_to(donde).as_posix() for p in donde.rglob("*") if p.is_file())


def _bases(kdbx) -> dict[str, bytes]:
    """Bases de mentira: entera y rota de cada versión, y un formato que no se conoce."""
    entera4 = _kdbx4(kdbx, [b"x" * 20, b"y" * 8])
    return {
        "entera": entera4,
        "cortada": entera4[:-5],
        "entera3": _kdbx3(kdbx, AES, 1184),
        "rota3": _kdbx3(kdbx, AES, 1183),
        "vacia": b"",
        "desconocida": struct.pack("<IIHH", kdbx.FIRMA_1, kdbx.FIRMA_2, 0, 5) + b"\0" * 50,
    }


def _caso_preparar(llavero, model, config, nombre: str, ficheros: list[dict],
                   simulacro: bool = False, existe: bool = True,
                   durante: dict[str, str] | None = None) -> dict:
    """`llavero.preparar()` sobre una carpeta `.keychain/` con esos ficheros.

    Args:
        durante: `{ruta: hex}` de lo que se escribe en la carpeta en cada espera.
    """
    esperas: list = []
    hook = None
    if durante:
        def hook(donde: Path) -> None:
            for ruta, datos in durante.items():
                (donde / ruta).write_bytes(bytes.fromhex(datos))
    with _montado(model, llavero, esperas, hook) as raiz:
        donde = raiz / model.LLAVERO_LOCAL
        if existe:
            donde.mkdir()
            _poner(donde, ficheros)
        motivo = llavero.preparar(config.pareja_llavero, simulacro=simulacro)
        despues = {"leeme": _leeme_despues(donde, llavero.LEEME),
                   "ficheros": _ficheros_despues(donde) if existe else [],
                   "carpeta_existe": donde.is_dir()}
    return {"caso": nombre, "carpeta_existe": existe, "ficheros": ficheros,
            "simulacro": simulacro, "durante_la_espera": durante or {},
            "motivo": motivo, "esperas": esperas, "despues": despues}


def vectores_preparar(kdbx, llavero, model, config) -> list[dict]:
    b = _bases(kdbx)
    texto = llavero.LEEME_TEXTO.encode("utf-8")
    fija = llavero.LEEME_HORA * SEG
    otro = OTRO_LEEME.encode("utf-8")

    def leeme(mtime: int = fija, datos: bytes = texto) -> dict:
        return _fichero(llavero.LEEME, datos, mtime)

    def caso(*args, **kw) -> dict:
        return _caso_preparar(llavero, model, config, *args, **kw)

    return [
        caso("la carpeta no existe: no se crea", [], existe=False),
        caso("carpeta vacía: se crea el LEEME con su hora", []),
        caso("carpeta vacía en un simulacro: el LEEME nuevo también sale con su hora", [],
             simulacro=True),
        caso("LEEME de siempre con su hora: no se toca", [leeme()]),
        caso("LEEME con el texto y otra hora: se corrige", [leeme(HORA)]),
        caso("LEEME con el texto y otra hora, en un simulacro: no se corrige", [leeme(HORA)],
             simulacro=True),
        caso("LEEME con la hora 1,9 s después: es la misma", [leeme(fija + CASI_LA_VENTANA)]),
        caso("LEEME con la hora 1,9 s antes: es la misma", [leeme(fija - CASI_LA_VENTANA)]),
        caso("LEEME con la hora 2 s después: se corrige", [leeme(fija + 2 * SEG)]),
        caso("LEEME con la hora 2 s antes: se corrige", [leeme(fija - 2 * SEG)]),
        caso("LEEME con otro texto: ni el texto ni la hora", [leeme(HORA, otro)]),
        caso("LEEME con otro texto y la hora fija: tampoco", [leeme(fija, otro)]),
        caso("una base rota", [_fichero("claves.kdbx", b["cortada"], HORA)]),
        caso("una base rota y sin LEEME: se crea y se dice", [_fichero("claves.kdbx", b["cortada"])]),
        caso("una base entera", [_fichero("claves.kdbx", b["entera"], HORA)]),
        caso("una base KDBX 3 entera", [_fichero("claves.kdbx", b["entera3"])]),
        caso("una base KDBX 3 rota", [_fichero("claves.kdbx", b["rota3"])]),
        caso("una base vacía", [_fichero("claves.kdbx", b["vacia"])]),
        caso("una base de un formato que no se conoce: no cuenta como rota",
             [_fichero("claves.kdbx", b["desconocida"])]),
        caso("una claves.old.kdbx rota: no cuenta",
             [_fichero("claves.old.kdbx", b["cortada"]), _fichero("claves.kdbx", b["entera"])]),
        caso("una claves.OLD.kdbx rota: tampoco (se mira en minúsculas)",
             [_fichero("claves.OLD.kdbx", b["cortada"])]),
        caso("una rota dentro de .prversions: no cuenta",
             [_fichero(".prversions/claves.kdbx", b["cortada"]),
              _fichero("sub/.prversions/otra.kdbx", b["cortada"]),
              _fichero("claves.kdbx", b["entera"])]),
        caso("una rota en una subcarpeta", [_fichero("sub/hondo/claves.kdbx", b["cortada"])]),
        caso("varias rotas: salen en el orden de prdrive, de carpeta en carpeta",
             [_fichero("a.kdbx", b["cortada"]), _fichero("a/x.kdbx", b["rota3"]),
              _fichero("a-b.kdbx", b["vacia"]), _fichero("b.kdbx", b["entera"])]),
        caso("una rota que se acaba de guardar durante la espera: ya no cuenta",
             [_fichero("claves.kdbx", b["cortada"])],
             durante={"claves.kdbx": b["entera"].hex()}),
        caso("una rota que sigue rota tras la espera",
             [_fichero("claves.kdbx", b["cortada"])],
             durante={"otra.kdbx": b["entera"].hex()}),
        caso("dos rotas y una se arregla durante la espera",
             [_fichero("a.kdbx", b["cortada"]), _fichero("b.kdbx", b["rota3"])],
             durante={"a.kdbx": b["entera"].hex()}),
        caso("una rota en un simulacro: se dice igual",
             [leeme(HORA), _fichero("claves.kdbx", b["cortada"])], simulacro=True),
    ]


def vectores_asegurar(llavero, model) -> list[dict]:
    """`llavero.asegurar_leeme()`: qué devuelve y cómo deja el fichero."""
    texto = llavero.LEEME_TEXTO.encode("utf-8")
    fija = llavero.LEEME_HORA * SEG
    previos = {
        "no hay LEEME": None,
        "el de siempre con su hora": (texto, fija),
        "el de siempre con otra hora": (texto, HORA),
        "el de siempre 1,9 s más tarde": (texto, fija + CASI_LA_VENTANA),
        "el de siempre 2 s más tarde": (texto, fija + 2 * SEG),
        "otro texto con otra hora": (OTRO_LEEME.encode("utf-8"), HORA),
        "un LEEME vacío": (b"", HORA),
    }
    salida = []
    for nombre, previo in previos.items():
        for corregir in (True, False):
            with _montado(model, llavero, []) as raiz:
                donde = raiz / model.LLAVERO_LOCAL
                donde.mkdir()
                ficheros = []
                if previo is not None:
                    ficheros = [_fichero(llavero.LEEME, *previo)]
                    _poner(donde, ficheros)
                devuelve = llavero.asegurar_leeme(donde, corregir_hora=corregir)
                despues = _leeme_despues(donde, llavero.LEEME)
            salida.append({"caso": nombre, "carpeta_existe": True, "ficheros": ficheros,
                           "corregir_hora": corregir, "devuelve": devuelve, "despues": despues})
    # Sin carpeta no se puede escribir: no lanza, devuelve False.
    with _montado(model, llavero, []) as raiz:
        donde = raiz / model.LLAVERO_LOCAL
        salida.append({"caso": "la carpeta no existe", "carpeta_existe": False, "ficheros": [],
                       "corregir_hora": True, "devuelve": llavero.asegurar_leeme(donde),
                       "despues": _leeme_despues(donde, llavero.LEEME)})
    return salida


def vectores_bases(llavero, model) -> list[dict]:
    """`llavero.bases()`: qué bases viajan y en qué orden."""
    conjuntos = {
        "una carpeta sin nada": [],
        "una sola base": ["claves.kdbx"],
        "con las que no viajan": [
            "claves.kdbx", "claves.old.kdbx", "claves.OLD.kdbx", "LEEME.txt", "notas.txt",
            "claves.kdbx.bak", "claves.kdbx~1", ".prversions/viejas.kdbx",
            "sub/.prversions/otras.kdbx", ".prversions/hondo/mas.kdbx"],
        "con subcarpetas, ocultas y conflictos": [
            "personal.kdbx", "personal.conflicto-dispositivo1.kdbx", ".oculta.kdbx",
            "sub/uno.kdbx", "sub/hondo/dos.kdbx", "sub.kdbx"],
        "el orden es por partes de la ruta, no por el texto": [
            "a.kdbx", "a/x.kdbx", "a-b.kdbx", "a b.kdbx", "a/b/c.kdbx", "a/b.kdbx", "ab.kdbx"],
    }
    salida = []
    for nombre, rutas in conjuntos.items():
        with _montado(model, llavero, []) as raiz:
            donde = raiz / model.LLAVERO_LOCAL
            donde.mkdir()
            _poner(donde, [{"ruta": r, "tamano": 3} for r in rutas])
            hallado = [p.relative_to(donde).as_posix() for p in llavero.bases(donde)]
        salida.append({"caso": nombre, "carpeta_existe": True, "ficheros": rutas,
                       "bases": hallado})
    with _montado(model, llavero, []) as raiz:
        salida.append({"caso": "la carpeta no existe", "carpeta_existe": False, "ficheros": [],
                       "bases": [str(p) for p in llavero.bases(raiz / model.LLAVERO_LOCAL)]})
    return salida


def vectores_leeme(kdbx, llavero, model, config) -> dict:
    return {
        "nombre": llavero.LEEME,
        "texto": llavero.LEEME_TEXTO,
        "texto_hex": llavero.LEEME_TEXTO.encode("utf-8").hex(),
        "hora": llavero.LEEME_HORA,
        "espera_entera": llavero.ESPERA_ENTERA,
        "tolerancia_hora": llavero.TOLERANCIA_HORA,
        "asegurar": vectores_asegurar(llavero, model),
        "bases": vectores_bases(llavero, model),
        "preparar": vectores_preparar(kdbx, llavero, model, config),
    }


# --- el listado de bisync y lo pendiente ---------------------------------------

def _hora_listado(ns: int, zona: str = "+0000", fraccion: int = 9) -> str:
    """La hora de una línea de listado de rclone (`2006-01-02T15:04:05.000000000-0700`)."""
    signo = -1 if zona[0] == "-" else 1
    desplazo = signo * (int(zona[1:3]) * 3600 + int(zona[-2:]) * 60)
    seg, resto = divmod(ns, SEG)
    fecha = datetime.fromtimestamp(seg + desplazo, tz=timezone.utc)
    cuerpo = fecha.strftime("%Y-%m-%dT%H:%M:%S")
    if fraccion:
        cuerpo += "." + f"{resto:09d}"[:fraccion]
    return cuerpo + zona


def _linea(ruta: str, tamano: int, ns: int, zona: str = "+0000") -> str:
    """Una línea de listado como la escribe `lineFormat` de bisync («%s %8d %s %s %s %q»)."""
    return f"- {tamano:8d} - - {_hora_listado(ns, zona)} {json.dumps(ruta, ensure_ascii=False)}"


CABECERA_LISTADO = "# bisync listing v1 from 2026-10-05T06:44:29.752645891Z\n"
"""La primera línea de un listado: no casa con `_LINEA`, y no se lee."""


def _listado(lineas: list[str]) -> str:
    return CABECERA_LISTADO + "".join(linea + "\n" for linea in lineas)


def _en_bytes(contenido: str | bytes | None) -> bytes | None:
    if contenido is None or isinstance(contenido, bytes):
        return contenido
    return contenido.encode("utf-8")


def _escribir_listado(bisync, pareja, contenido: bytes | None) -> None:
    if contenido is None:
        return
    fichero = pareja.workdir / (bisync.expected_prefix(pareja) + bisync.PATH1_SUFFIX)
    fichero.parent.mkdir(parents=True)
    fichero.write_bytes(contenido)


def vectores_lineas(llavero, model, bisync, config) -> list[dict]:
    """`llavero.listado_local()`: líneas de `lineFormat` de rclone → lo que da prdrive."""
    t = 1_790_000_000_752_645_800
    ok = _linea("personal.kdbx", 5, t)
    casos: dict[str, str | bytes | None] = {
        "no hay listado": None,
        "listado vacío": "",
        "solo la cabecera": CABECERA_LISTADO,
        "la línea del docstring": _listado(
            ['-        5 - - 2026-10-05T06:44:29.752645891+0000 "personal.kdbx"']),
        "varias bases": _listado([ok, _linea("LEEME.txt", 479, 946_684_800 * SEG),
                                  _linea("sub/hondo/otra.kdbx", 12_345_678, t + 5 * SEG)]),
        "la zona +0200": _listado(['-       12 - - 2026-10-05T08:44:29.5+0200 "a.kdbx"']),
        "la zona -0700": _listado(['-       12 - - 2026-10-04T23:44:29.5-0700 "a.kdbx"']),
        "la zona con dos puntos": _listado(['-       12 - - 2026-10-05T08:44:29.5+02:00 "a.kdbx"']),
        "zonas de media hora": _listado(['-       12 - - 2026-10-05T12:14:29-0530 "a.kdbx"',
                                         '-       12 - - 2026-10-05T12:14:29+0530 "b.kdbx"']),
        "sin fracción": _listado(['-       12 - - 2026-10-05T06:44:29+0000 "a.kdbx"']),
        "fracción de 1 a 9 cifras": _listado([
            f'-       1 - - 2026-10-05T06:44:29.{"1234567890"[:n]}+0000 "f{n}.kdbx"'
            for n in range(1, 10)]),
        "fracción de 10 cifras: no es una hora": _listado(
            ['-       12 - - 2026-10-05T06:44:29.1234567890+0000 "a.kdbx"']),
        "sin zona: no es una hora": _listado(['-       12 - - 2026-10-05T06:44:29.5 "a.kdbx"']),
        "el 31 de febrero y la hora 25: timegm no valida": _listado([
            '-       1 - - 2026-02-31T00:00:00.000000000+0000 "feb.kdbx"',
            '-       1 - - 2026-10-05T25:61:61.000000000+0000 "hora.kdbx"']),
        "antes de 1970": _listado(['-       1 - - 1969-12-31T23:59:59.5+0000 "viejo.kdbx"']),
        "nombre con comillas escapadas": _listado([
            r'-       1 - - 2026-10-05T06:44:29.5+0000 "a \"b\" c.kdbx"']),
        "nombre con barra invertida y unicode escapado": _listado([
            r'-       1 - - 2026-10-05T06:44:29.5+0000 "a\\b.kdbx"',
            r'-       1 - - 2026-10-05T06:44:29.5+0000 "cláve.kdbx"']),
        "nombre con unicode sin escapar": _listado([_linea("clave ñandú 日本.kdbx", 7, t)]),
        r"nombre que el %q de Go escribe con \x: no es JSON": _listado([
            r'-       1 - - 2026-10-05T06:44:29.5+0000 "a\x00b.kdbx"', ok]),
        "nombre sin comillas": _listado(['-       1 - - 2026-10-05T06:44:29.5+0000 sin.kdbx']),
        "nombre con basura detrás de las comillas": _listado([
            '-       1 - - 2026-10-05T06:44:29.5+0000 "a.kdbx" y más']),
        "dos nombres entre comillas: json.loads se queja": _listado([
            '-       1 - - 2026-10-05T06:44:29.5+0000 "a.kdbx" "b.kdbx"']),
        "líneas que no casan en medio": _listado(["", "basura", "-", "- 1 - -", ok, "   ", "# otra"]),
        "la misma ruta dos veces: vale la última": _listado([_linea("a.kdbx", 1, t),
                                                              _linea("a.kdbx", 2, t + SEG)]),
        "tamaño negativo": _listado(['- -1 - - 2026-10-05T06:44:29.5+0000 "a.kdbx"']),
        r"tamaño con otras cifras (\d es Unicode)": _listado([
            '- ٥ - - 2026-10-05T06:44:29.5+0000 "a.kdbx"']),
        r"hora con otras cifras (\d es Unicode)": _listado([
            '- 5 - - ٢٠٢٦-10-05T06:44:29.5+0000 "a.kdbx"']),
        "campos separados por tabuladores y espacios duros": _listado([
            '-\t7\t-\t-\t2026-10-05T06:44:29.5+0000\t"tab.kdbx"',
            '-\u00a07\u00a0-\u00a0-\u00a02026-10-05T06:44:29.5+0000\u00a0"nbsp.kdbx"']),
        "banderas y hashes distintos": _listado([
            'd        0 - - 2026-10-05T06:44:29.5+0000 "dir"',
            '- 5 md5 1234 2026-10-05T06:44:29.5+0000 "con-hash.kdbx"']),
        "saltos de línea raros: se corta como splitlines()": (
            ok + "\u2028" + _linea("a.kdbx", 1, t) + "\x1c" + _linea("b.kdbx", 2, t)
            + "\x0b" + _linea("c.kdbx", 3, t) + "\x0c" + _linea("d.kdbx", 4, t)
            + "\u0085" + _linea("e.kdbx", 5, t) + "\r" + _linea("f.kdbx", 6, t) + "\n"),
        "un salto raro dentro del nombre rompe la línea": (
            CABECERA_LISTADO + '-       1 - - 2026-10-05T06:44:29.5+0000 "a\u2028b.kdbx"\n'
            + ok + "\n"),
        "con CRLF": _listado([ok, _linea("otra.kdbx", 9, t)]).replace("\n", "\r\n"),
        "sin salto final": _listado([ok]).rstrip("\n"),
        "con marca de orden de bytes delante de la primera línea": "\ufeff" + ok + "\n",
        "UTF-8 que no lo es": (b"# bisync listing v1\n"
                               b'- 5 - - 2026-10-05T06:44:29.5+0000 "a\xff.kdbx"\n'),
    }
    pareja = config.pareja_llavero
    salida = []
    for nombre, contenido in casos.items():
        datos = _en_bytes(contenido)
        with _montado(model, llavero, []):
            _escribir_listado(bisync, pareja, datos)
            leido = llavero.listado_local(pareja)
        salida.append({"caso": nombre, "hex": None if datos is None else datos.hex(),
                       "esperado": None if leido is None
                       else {k: list(v) for k, v in leido.items()}})
    return salida


def vectores_pendiente(llavero, model, bisync, config) -> list[dict]:
    """`llavero.pendiente()` y `llavero.sin_listar()`: bases y listados montados."""
    t = HORA
    pareja = config.pareja_llavero

    def base(ruta: str, tamano: int = 100, mtime: int = t) -> dict:
        return {"ruta": ruta, "tamano": tamano, "mtime_ns": mtime}

    def en(ruta: str, tamano: int = 100, ns: int = t, zona: str = "+0000") -> tuple:
        return (ruta, tamano, ns, zona)

    claves = base("claves.kdbx")
    casos: list[tuple[str, list[dict], list[tuple] | bytes | None]] = [
        ("todo igual", [claves], [en("claves.kdbx")]),
        ("la misma hora dicha en otra zona", [claves], [en("claves.kdbx", zona="+0200")]),
        ("tamaño distinto", [claves], [en("claves.kdbx", 99)]),
        ("el listado, 1,9 s más tarde", [claves], [en("claves.kdbx", ns=t + CASI_LA_VENTANA)]),
        ("el listado, 1,9 s más pronto", [claves], [en("claves.kdbx", ns=t - CASI_LA_VENTANA)]),
        ("el listado, 100 ns menos de 2 s más tarde", [claves],
         [en("claves.kdbx", ns=t + 2 * SEG - 100)]),
        ("el listado, 2 s más tarde", [claves], [en("claves.kdbx", ns=t + 2 * SEG)]),
        ("el listado, 2 s más pronto", [claves], [en("claves.kdbx", ns=t - 2 * SEG)]),
        ("el listado, un día más tarde", [claves], [en("claves.kdbx", ns=t + 86_400 * SEG)]),
        ("sin listado", [claves], None),
        ("un listado que no se lee", [claves], b"\xff\xfe\x00"),
        ("un listado sin ninguna base", [claves], []),
        ("una base que no está en el listado", [claves, base("otra.kdbx")], [en("claves.kdbx")]),
        ("varias sin listar, en el orden de prdrive",
         [base("a.kdbx"), base("a/x.kdbx"), base("a-b.kdbx"), claves],
         [en("claves.kdbx")]),
        ("sin bases y sin listado", [], None),
        ("sin bases y con listado", [], [en("claves.kdbx")]),
        ("solo el LEEME", [base("LEEME.txt", 479, 946_684_800 * SEG)], None),
        ("dos bases: una igual y otra con la hora cambiada",
         [claves, base("otra.kdbx", 7, t + 10 * SEG)],
         [en("claves.kdbx"), en("otra.kdbx", 7)]),
        ("una base de una subcarpeta, igual", [base("sub/hondo/claves.kdbx")],
         [en("sub/hondo/claves.kdbx")]),
        ("una base de una subcarpeta, con otro tamaño", [base("sub/hondo/claves.kdbx")],
         [en("sub/hondo/claves.kdbx", 5)]),
        ("lo que no viaja no cuenta",
         [claves, base("claves.old.kdbx", 5), base(".prversions/claves.kdbx", 6)],
         [en("claves.kdbx")]),
        ("el listado trae una base que ya no está", [claves],
         [en("claves.kdbx"), en("se-fue.kdbx")]),
        ("la base conflicto de este lado, sin listar",
         [claves, base("claves.conflicto-dispositivo1.kdbx")], [en("claves.kdbx")]),
    ]
    salida = []
    for nombre, ficheros, listado in casos:
        if isinstance(listado, list):
            datos = _listado([_linea(*e) for e in listado]).encode("utf-8")
        else:
            datos = listado
        with _montado(model, llavero, []) as raiz:
            donde = raiz / model.LLAVERO_LOCAL
            donde.mkdir()
            _poner(donde, ficheros)
            _escribir_listado(bisync, pareja, datos)
            pendiente = llavero.pendiente(pareja)
            sin_listar = llavero.sin_listar(pareja)
        salida.append({"caso": nombre, "ficheros": ficheros,
                       "listado_hex": None if datos is None else datos.hex(),
                       "pendiente": pendiente, "sin_listar": sin_listar})
    return salida


def vectores_listado(llavero, model, bisync, config) -> dict:
    pareja = config.pareja_llavero
    prefijo = bisync.expected_prefix(pareja)
    return {
        "config": CONFIG_LLAVERO,
        "prefijo": prefijo,
        "sufijo": bisync.PATH1_SUFFIX,
        # Dónde cae, bajo la raíz del volumen: `STATE_DIR / pareja / <prefijo>.path1.lst`.
        "ruta": (pareja.workdir / (prefijo + bisync.PATH1_SUFFIX))
        .relative_to(model.DEVICE_ROOT).as_posix(),
        "lineas": vectores_lineas(llavero, model, bisync, config),
        "pendiente": vectores_pendiente(llavero, model, bisync, config),
    }


# --- los frenos de rclone y el alta desde el remoto ------------------------------

CONFIG_FRENOS = {
    "defaults": {"device_remote": "disp", "remote": "nas"},
    "keychain": {"base": "personal.kdbx"},
    "pair": [{"name": "notas", "local": "sync-data/notas", "remote_path": "/copia/notas"}],
}
"""El config de los frenos: el llavero con `personal.kdbx`, como en los docstrings de prdrive."""

MARCA = "2026/10/16 08:52:39 INFO  : "
"""Lo que rclone pone delante de una línea `INFO` de bisync."""


def _borrado(lado: str, nombre: str, marca: str = MARCA) -> str:
    """Una línea de bisync que dice que algo ya no está en ese lado.

    Es el formato «- %-18s%-43s - %s» de `indent()` (`cmd/bisync/log.go`), que es el
    que `llavero._BORRADO` lee.
    """
    return f"{marca}- {lado:<18}{'File was deleted':<43} - {nombre}"


def _simulado(nombre: str, que: str = "delete", marca: str = "2026/10/15 09:44:30 NOTICE: ") -> str:
    """Una línea de rclone de una simulación (`--dry-run`) que habría borrado `nombre`."""
    return f"{marca}{nombre}: Skipped {que} as --dry-run is set (size 7)"


def _log(*lineas: str, fin: str = "\n") -> str:
    return fin.join(lineas) + fin


def vectores_frenos_log(llavero, pareja) -> list[dict]:
    """Logs de bisync y lo que `solo_copias_borradas()` lee de cada uno."""
    ruido = "2026/10/16 08:52:39 INFO  : Bisync successful"
    abortar = "2026/10/16 08:52:39 ERROR : Bisync aborted. Must run --resync to recover."
    registros = [
        ("el de _BORRADO, una copia de conflicto",
         _log("2026/10/16 08:52:39 INFO  : - Path1             File was deleted"
              "                            - Personal.conflicto-dispositivo1.kdbx")),
        ("una copia de conflicto del dispositivo",
         _log(ruido, _borrado("Path1", "personal.conflicto-dispositivo1.kdbx"), abortar)),
        ("una copia de conflicto del remoto",
         _log(_borrado("Path2", "personal.conflicto-remoto2.kdbx"))),
        ("copias en los dos lados",
         _log(_borrado("Path1", "personal.conflicto-dispositivo1.kdbx"),
              _borrado("Path2", "personal.conflicto-remoto1.kdbx"),
              _borrado("Path2", "personal.conflicto-remoto3.kdbx"))),
        ("la copia con el sufijo de fábrica de rclone",
         _log(_borrado("Path1", "personal.conflict1.kdbx"))),
        ("la copia de otra base",
         _log(_borrado("Path1", "otra.conflicto-remoto1.kdbx"))),
        ("una copia con otra extensión",
         _log(_borrado("Path1", "personal.conflicto-remoto1.txt"))),
        ("una copia con la extensión en mayúsculas",
         _log(_borrado("Path1", "personal.conflicto-remoto1.KDBX"))),
        ("una base borrada",
         _log(_borrado("Path1", "personal.kdbx"))),
        ("una base y una copia borradas",
         _log(_borrado("Path1", "personal.conflicto-remoto1.kdbx"),
              _borrado("Path1", "personal.kdbx"))),
        ("LEEME.txt borrado",
         _log(_borrado("Path2", "LEEME.txt"))),
        ("una copia y LEEME.txt borrados",
         _log(_borrado("Path2", "personal.conflicto-remoto1.kdbx"),
              _borrado("Path2", "LEEME.txt"))),
        ("un nombre entre comillas",
         _log(_borrado("Path1", '"personal.conflicto-remoto1.kdbx"'))),
        ("un nombre con algo no imprimible, entre comillas",
         _log(_borrado("Path1", '"personal.conflicto-remoto1\\x0b.kdbx"'))),
        ("una ruta con /",
         _log(_borrado("Path1", "sub/personal.conflicto-remoto1.kdbx"))),
        ("un nombre que acaba en espacios",
         _log(_borrado("Path1", "personal.conflicto-remoto1.kdbx  "))),
        ("un nombre con letras de fuera de ASCII",
         _log(_borrado("Path1", "contraseñas.conflicto-remoto1.kdbx"))),
        ("sin borrados", _log(ruido, "2026/10/16 08:52:39 INFO  : Synching Path1 with Path2")),
        ("un log vacío", ""),
        ("una línea de otra cosa que se parece",
         _log("2026/10/16 08:52:39 INFO  : - Path1             File is newer"
              "                              - personal.conflicto-remoto1.kdbx")),
        ("un lado que no existe",
         _log(_borrado("Path3", "personal.conflicto-remoto1.kdbx"))),
        ("con el fin de línea de Windows",
         _log(_borrado("Path1", "personal.conflicto-remoto1.kdbx"), ruido, fin="\r\n")),
        ("con un salto de línea raro tras el nombre",
         "2026/10/16 08:52:39 INFO  : - Path1             File was deleted"
         "                            - personal.conflicto-remoto1.kdbx\x0bsiguiente\n"),
        ("sin la marca de la hora, solo los dos puntos",
         _log(_borrado("Path1", "personal.conflicto-remoto1.kdbx", marca=": "))),
        ("dos veces la frase en la misma línea",
         _log("INFO  : ruta: - Path1  File was deleted  - x: - Path2   File was deleted    "
              "- personal.conflicto-remoto1.kdbx")),
    ]
    return [{"caso": caso, "log": log, "solo_copias": llavero.solo_copias_borradas(log, pareja)}
            for caso, log in registros]


def vectores_remoto_vacio(llavero) -> list[dict]:
    registros = [
        ("el de prdrive",
         "2026/10/16 08:52:39 ERROR : Empty current Path2 listing. Cannot sync to an "
         "empty directory: C:\\x\\y.path2.lst\n"),
        ("en medio de otras líneas",
         "INFO  : Synching\nERROR : Empty current Path2 listing. Cannot sync\nERROR : Bisync failed\n"),
        ("el del lado del dispositivo",
         "2026/10/16 08:52:39 ERROR : Empty current Path1 listing. Cannot sync to an "
         "empty directory: C:\\x\\y.path1.lst\n"),
        ("en minúsculas", "error : empty current path2 listing\n"),
        ("solo parte de la frase", "Empty current Path2\n"),
        ("sin nada", ""),
        ("otro fallo", "ERROR : Bisync aborted. Must run --resync to recover.\n"),
    ]
    return [{"caso": caso, "log": log, "vacio": llavero.remoto_vacio(log)}
            for caso, log in registros]


def vectores_borrarian(frenos) -> list[dict]:
    registros = [
        ("una simulación de dos ficheros",
         _log(_simulado("nota02.md"), _simulado("nota01.md"))),
        ("con versiones, va al directorio de versiones",
         _log(_simulado("nota01.md", "move into backup dir"))),
        ("sin repetir y en orden",
         _log(_simulado("b.md"), _simulado("a.md"), _simulado("b.md"),
              _simulado("a.md", "move into backup dir"))),
        ("lo que solo se sobrescribe no entra",
         _log(_simulado("a.md", "move to a.md~20261015"), _simulado("b.md", "copy"),
              _simulado("c.md"))),
        ("un nombre entre comillas",
         _log(_simulado('"a\\x0b.md"'))),
        ("una ruta con /",
         _log(_simulado("sub/dentro/a.md"), _simulado("sub/b.md"))),
        ("un nombre con dos puntos",
         _log(_simulado("hora 10:30: x.md"))),
        ("un nombre con ñ y con letras fuera del plano básico",
         _log(_simulado("ñandú.md"), _simulado("\U0001f600.md"), _simulado("\uff21.md"),
              _simulado("z.md"))),
        ("mayúsculas y minúsculas", _log(_simulado("b.md"), _simulado("B.md"), _simulado("a.md"))),
        ("con el fin de línea de Windows",
         _log(_simulado("a.md"), _simulado("b.md"), fin="\r\n")),
        ("sin la marca de la hora", "NOTICE: a.md: Skipped delete as --dry-run is set\n"),
        ("otro nivel de log, que no entra",
         "2026/10/15 09:44:30 INFO  : a.md: Skipped delete as --dry-run is set (size 7)\n"),
        ("una simulación sin borrados",
         _log("2026/10/15 09:44:30 NOTICE: Skipped copy as --dry-run is set")),
        ("un log vacío", ""),
    ]
    return [{"caso": caso, "log": log, "borrarian": frenos.borrarian(log)}
            for caso, log in registros]


def vectores_explicaciones(frenos) -> dict:
    """Las tres explicaciones de un freno y lo que se le dice al llavero en su lugar."""
    entradas = [explicacion for _, explicacion in frenos.ENTRADAS]
    return {
        "llavero": frenos.EXPLICACION_LLAVERO,
        "llavero_cambiados": frenos.EXPLICACION_LLAVERO_CAMBIADOS,
        "entradas": entradas,
        "explicacion_llavero": [
            {"explicacion": e, "dice": frenos.explicacion_llavero(e)} for e in entradas],
        # Lo que no es ninguna de las tres cae en la general.
        "otras": [{"explicacion": e, "dice": frenos.explicacion_llavero(e)}
                  for e in ("Otra cosa.", "", frenos.EXPLICACION_LLAVERO,
                            frenos.EXPLICACION_LLAVERO_CAMBIADOS)],
    }


def vectores_alta(llavero, cifrada) -> list[dict]:
    """`decidir_alta()` con `origen = None`: traer el llavero del remoto."""
    tablas = [
        ("sin remoto", None),
        ("una tabla con fichero llave",
         {"base": "personal.kdbx", "fichero_llave": True, "nombre_llave": "clave.keyx"}),
        ("una tabla sin llave", {"base": "personal.kdbx", "fichero_llave": False}),
        ("una tabla con llave interna",
         {"base": "personal.kdbx", "fichero_llave": True, "llave_interna": True}),
        ("una tabla con llave interna apagada",
         {"base": "personal.kdbx", "fichero_llave": False, "llave_interna": False}),
        ("una tabla vacía", {}),
        ("una tabla con claves de más", {"base": "x.kdbx", "otra": [1, 2, {"a": "b"}]}),
    ]
    casos = []
    for caso, remota in tablas:
        try:
            alta = llavero.decidir_alta(Path("/no/se/mira"), remota, None,
                                        cifrado=cifrada.Cifrado(True, "VERACRYPT"))
        except ValueError as e:
            casos.append({"caso": caso, "remota": remota, "error": str(e)})
        else:
            assert (alta.destino, alta.copia, alta.subir, alta.aviso_formato) == \
                (None, False, False, ""), alta
            casos.append({"caso": caso, "remota": remota, "tabla": alta.tabla})
    return casos


def vectores_frenos(llavero, model, frenos, cifrada) -> dict:
    config = model.parse_config(CONFIG_FRENOS)
    return {
        "config": CONFIG_FRENOS,
        "freno_sin_copias": llavero.FRENO_SIN_COPIAS,
        "logs": vectores_frenos_log(llavero, config.pareja_llavero),
        "remoto_vacio": vectores_remoto_vacio(llavero),
        "borrarian": vectores_borrarian(frenos),
        "explicaciones": vectores_explicaciones(frenos),
        "alta": vectores_alta(llavero, cifrada),
    }


MARCA_LOG = "<log guardado>"
"""Lo que devuelve la grabadora de `dispose_log()` cuando el log se guarda.

Es lo que sale en las líneas donde prdrive nombra el log («(Log: …)»): el
nombre de verdad lleva la hora, y el test lo cambia por esto antes de comparar.
"""

ABORTO_BORRADOS = (
    "2026/10/05 17:38:16 ERROR : Safety abort: too many deletes (>25%, 2 of 4) on "
    "Path1 \"disp{uR5dC}:.keychain/\". Run with --force if desired.\n"
    "2026/10/05 17:38:16 NOTICE: Failed to bisync: too many deletes\n")
"""El freno de borrados de bisync, como en `tests/test_llavero.py` de prdrive."""

TODOS_CAMBIADOS = (
    "2026/10/12 17:38:16 INFO  : - Path2             File changed: time (newer)          - LEEME.txt\n"
    "2026/10/12 17:38:16 INFO  : - Path2             File changed: time (newer)          - personal.kdbx\n"
    "2026/10/12 17:38:16 INFO  : Path2:    2 changes:    0 new,    2 modified,    0 deleted\n"
    "2026/10/12 17:38:16 ERROR : Safety abort: all files were changed on Path2 "
    "\"nas:prdrive-catalog/keychain/\". Run with --force if desired.\n"
    "2026/10/12 17:38:16 NOTICE: Bisync aborted. Please try again.\n"
    "2026/10/12 17:38:16 NOTICE: Failed to bisync: all files were changed\n")
"""«Han cambiado todos los ficheros» (#115 de prdrive), con los dos ficheros del llavero."""

REMOTO_VACIO = (
    "2026/10/16 08:52:39 ERROR : Empty current Path2 listing. Cannot sync to an empty directory: "
    "/volumen/.prdrive/state/keychain/disp_.keychain..nas_prdrive-catalog_keychain.path2.lst-new\n"
    "2026/10/16 08:52:39 ERROR : Bisync critical error: empty current Path2 listing: "
    "/volumen/.prdrive/state/keychain/disp_.keychain..nas_prdrive-catalog_keychain.path2.lst-new\n"
    "2026/10/16 08:52:39 ERROR : Bisync aborted. Error is retryable without --resync due to "
    "--resilient mode.\n"
    "2026/10/16 08:52:39 NOTICE: Failed to bisync: bisync aborted\n")
"""La carpeta del llavero borrada a mano en el remoto (rclone v1.75.2, `checkListing()`)."""

SIMULADA = ("2026/10/15 10:00:00 NOTICE: personal.kdbx: Skipped copy as --dry-run is set (size 100)\n"
            "2026/10/15 10:00:00 NOTICE: LEEME.txt: Skipped copy as --dry-run is set (size 200)\n")
"""Una simulación con `--force` que no borraría nada: solo copia."""

REPETIDA = "2026/10/16 08:52:40 INFO  : Bisync successful\n"
"""El log de una repetición que va bien."""


def _orden_de(cmd: list[str]) -> dict:
    """Lo que importa de una orden de bisync: si simula, fuerza o rehace, y su freno."""
    tope = None
    for i, arg in enumerate(cmd):
        if arg == "--max-delete" and i + 1 < len(cmd):
            tope = int(cmd[i + 1])
        elif arg.startswith("--max-delete="):
            tope = int(arg.split("=", 1)[1])
    return {"dry_run": "--dry-run" in cmd, "force": "--force" in cmd,
            "resync": "--resync" in cmd, "max_delete": tope}


def _caso_repeticion(sync, pair, nombre: str, log: str, simulacion: dict | None,
                     dry_run: bool) -> dict:
    """`sync.repetir_sin_freno()` sobre una pasada parada con ese log.

    `execute`, `crear_carpeta_remota`, `lo_que_borraria_forzando` y `dispose_log`
    se cambian por grabadoras, y `build_command` se envuelve para saber con qué
    pareja y si con `--resync` se monta cada orden. La de `lo_que_borraria_forzando`
    llama a la de verdad: lo que borraría lo dice prdrive (`frenos.borrarian()`),
    con el log de `simulacion` como el de la simulación.

    Args:
        simulacion: `{"rc", "log"}` de la simulación con `--force`, si la hay.
    """
    import contextlib  # noqa: PLC0415
    import io  # noqa: PLC0415
    rc_parada = 7
    ordenes: list[dict] = []
    montadas: list[dict] = []
    mkdir: list[dict] = []
    guardados: list[dict] = []
    simuladas: list = []
    temporales: list[Path] = []
    simulando = [False]
    originales = (sync.execute, sync.crear_carpeta_remota, sync.lo_que_borraria_forzando,
                  sync.dispose_log, sync.build_command)

    def execute(ctx, cmd, logfile=None):
        ordenes.append(_orden_de(cmd))
        if simulando[0]:
            rc, texto = simulacion["rc"], simulacion["log"]
        else:
            rc, texto = 0, REPETIDA
        if logfile is not None:
            logfile.write_text(texto, encoding="utf-8")
        return rc

    def crear_carpeta_remota(ctx, par):
        mkdir.append({"destino": par.dest, "dry_run": ctx.dry_run})
        return 0

    def lo_que_borraria_forzando(ctx, par, ffile):
        simulando[0] = True
        try:
            borraria = originales[2](ctx, par, ffile)
        finally:
            simulando[0] = False
        simuladas.append(borraria)
        return borraria

    def dispose_log(name, tmp, rc, keep_always):
        guardados.append({"rc": rc, "siempre": keep_always})
        if rc == 0 and not keep_always:
            return None
        return Path(MARCA_LOG)

    def build_command(ctx, par, ffile, need_resync):
        cmd, logfile = originales[4](ctx, par, ffile, need_resync)
        temporales.append(logfile)
        cambio = {k: v for k, v in par.flags.items() if pair.flags.get(k) != v}
        montadas.append({"cambio": cambio, "resync": bool(need_resync),
                         "dry_run": ctx.dry_run, "simulacion": simulando[0]})
        return cmd, logfile

    (sync.execute, sync.crear_carpeta_remota, sync.lo_que_borraria_forzando,
     sync.dispose_log, sync.build_command) = (execute, crear_carpeta_remota,
                                             lo_que_borraria_forzando, dispose_log,
                                             build_command)
    try:
        with tempfile.TemporaryDirectory() as tmp:
            parada = Path(tmp) / "parada.log"
            parada.write_text(log, encoding="utf-8")
            ctx = sync.RunContext(binary="rclone", env={}, dry_run=dry_run, sello="~20261016-085239")
            impreso = io.StringIO()
            with contextlib.redirect_stdout(impreso):
                rc, devuelto = sync.repetir_sin_freno(ctx, pair, Path("filtros.txt"), parada, rc_parada)
            de_la_parada = devuelto == parada
    finally:
        (sync.execute, sync.crear_carpeta_remota, sync.lo_que_borraria_forzando,
         sync.dispose_log, sync.build_command) = originales
        for t in temporales:
            t.unlink(missing_ok=True)
    repeticiones = [m for m in montadas if not m["simulacion"]]
    assert len(repeticiones) <= 1, (nombre, montadas)
    return {
        "caso": nombre, "dry_run": dry_run, "log": log, "rc_parada": rc_parada,
        "simulacion": simulacion if simuladas else None,
        "repetida": {"rc": 0, "log": REPETIDA} if repeticiones else None,
        "repite": bool(repeticiones),
        "cambio": repeticiones[0]["cambio"] if repeticiones else None,
        "resync": repeticiones[0]["resync"] if repeticiones else False,
        "mkdir": mkdir,
        "simulada": bool(simuladas),
        "borraria": simuladas[0] if simuladas else None,
        "ordenes": ordenes,
        "guardados": guardados,
        "rc": rc,
        "log_de_la_parada": de_la_parada,
        "lineas": impreso.getvalue().splitlines(),
    }


def vectores_repeticiones(sync, model, pair) -> dict:
    """Cada freno del llavero y si `repetir_sin_freno()` repite la pasada, en una de verdad y en un simulacro."""
    copia1, copia2 = "personal.conflicto-dispositivo1.kdbx", "personal.conflicto-remoto2.kdbx"
    borra = SIMULADA + "2026/10/15 10:00:00 NOTICE: X3.kdbx: Skipped move into backup dir as --dry-run is set\n"
    casos = [
        ("copias de conflicto borradas",
         _log(_borrado("Path1", copia1), _borrado("Path2", copia2)) + ABORTO_BORRADOS, None),
        ("una base borrada",
         _log(_borrado("Path1", copia1), _borrado("Path1", "personal.kdbx")) + ABORTO_BORRADOS, None),
        ("han cambiado todos, y forzar no borraría nada", TODOS_CAMBIADOS, {"rc": 0, "log": SIMULADA}),
        ("han cambiado todos, y forzar borraría X3.kdbx", TODOS_CAMBIADOS, {"rc": 0, "log": borra}),
        ("han cambiado todos, y la simulación falla", TODOS_CAMBIADOS,
         {"rc": 7, "log": "2026/10/15 10:00:00 NOTICE: Failed to bisync: no sé\n"}),
        ("el remoto vacío", REMOTO_VACIO, None),
        ("el lado del dispositivo vacío", REMOTO_VACIO.replace("Path2", "Path1"), None),
        ("un fallo de red",
         "2026/10/16 08:52:39 ERROR : Failed to create file system for \"nas:/prdrive-catalog/keychain\": "
         "couldn't connect SSH: dial tcp 127.0.0.1:2222: connect: connection refused\n"
         "2026/10/16 08:52:39 NOTICE: Failed to bisync: bisync aborted\n", None),
    ]
    return {
        "config": CONFIG_FRENOS,
        "llavero_roto": sync.LLAVERO_ROTO,
        "llavero_sin_cifrar": sync.LLAVERO_SIN_CIFRAR,
        "versions_dir": model.VERSIONS_DIR,
        "marca_log": MARCA_LOG,
        "casos": [_caso_repeticion(sync, pair, nombre, log, simulacion, dry_run)
                  for dry_run in (False, True) for nombre, log, simulacion in casos],
    }


def vectores(kdbx, llavero, model, bisync) -> dict:
    """La sección `llavero` del JSON."""
    import sync   # noqa: PLC0415 — prdrive ya está en el path
    from common import cifrada, frenos   # noqa: PLC0415 — prdrive ya está en el path
    config = model.parse_config(CONFIG_LLAVERO)
    return {
        "kdbx": vectores_kdbx(kdbx),
        "kdbx_sin_fichero": vectores_comprobar(kdbx),
        "leeme": vectores_leeme(kdbx, llavero, model, config),
        "listado": vectores_listado(llavero, model, bisync, config),
        "frenos": vectores_frenos(llavero, model, frenos, cifrada),
        "repeticiones": vectores_repeticiones(
            sync, model, model.parse_config(CONFIG_FRENOS).pareja_llavero),
    }
