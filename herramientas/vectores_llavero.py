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
"""

from __future__ import annotations

import struct
import hashlib

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


def vectores(kdbx) -> dict:
    """La sección `llavero` del JSON. Los argumentos se irán añadiendo."""
    return {
        "kdbx": vectores_kdbx(kdbx),
        "kdbx_sin_fichero": vectores_comprobar(kdbx),
    }
