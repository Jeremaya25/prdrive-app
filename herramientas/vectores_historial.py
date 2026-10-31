"""
vectores_historial.py — Lo que dice `common/historial.py` de prdrive, para
`engine/.../Historial.kt`.

`Historial.kt` es el espejo del diario de pasadas (`state/historial.jsonl`): la
línea JSON de cada pasada (`_linea`), leerla de vuelta con todos sus rechazos
(`_pasada`, `leer`), añadir una (`apuntar`), recortar el fichero (`_recortadas`,
`recortar_si_toca`), medir una pasada (`Reloj`, `pasada`) y contar la racha de
fallos de una pareja (`racha`, `rachas`). Lo esperado de cada caso lo contesta
`historial.py`; aquí no se escribe ni un resultado.

Lo importa `vectores.py` (que ya ha preparado prdrive) y mete lo que devuelve
[vectores] en el mismo JSON, en la sección `historial`.

**Lo que toca el disco** se monta en una carpeta temporal, con `model.STATE_DIR`
reenganchado a ella. Y para que salga igual en cualquier sistema:

- `store.write_text()` escribe en modo texto, y en Windows traduce `\\n` en
  `\\r\\n`: el recorte dejaría el diario con otros finales de línea y otro
  tamaño que en Android. Se envuelve para devolverlo a `\\n` (el contenido
  nunca lleva un `\\r` suelto: `json.dumps` lo escribe como `\\r`).
- `store.stamp()` y `time.monotonic()` se paran donde el caso lo necesita.
- Las fechas de los casos son de 2026: en Windows, `desde_sello()` lanza
  `OSError` (que no atrapa) con años anteriores a 1970 o posteriores a 3000.

**Los textos grandes** no van enteros en el JSON: donde un valor es un texto,
puede ser también una lista de partes (cadenas y `{"repetir": ..., "veces": n}`)
que el test de Kotlin concatena igual que [_armar].
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import time
from contextlib import contextmanager
from datetime import datetime, timedelta
from pathlib import Path

BASE = datetime(2026, 3, 1, 8, 0, 0)
FORMATO = "%Y-%m-%d %H:%M:%S"


def _sello(n: int) -> str:
    """Una fecha válida y distinta para cada `n` (un minuto de diferencia)."""
    return f"{BASE + timedelta(minutes=n):{FORMATO}}"


def _armar(partes) -> str:
    """El texto de un valor que puede venir en partes (ver arriba)."""
    if isinstance(partes, str):
        return partes
    assert isinstance(partes, list), f"unas partes son una lista, no {partes!r}"
    return "".join(p if isinstance(p, str) else p["repetir"] * p["veces"] for p in partes)


def _rep(texto: str, veces: int) -> dict:
    return {"repetir": texto, "veces": veces}


def _d(p, pareja=None) -> dict:
    """La `Pasada` como la lee el test: sus cinco campos, `pareja` quizá en partes."""
    datos = dict(p._asdict())
    if pareja is not None:
        datos["pareja"] = pareja
    return datos


def _texto_o_hex(datos: bytes) -> dict:
    """Los bytes de un fichero como `{"texto": ...}` o, si no son UTF-8, `{"hex": ...}`."""
    try:
        return {"texto": datos.decode("utf-8")}
    except UnicodeDecodeError:
        return {"hex": datos.hex()}


# --- el disco ----------------------------------------------------------------

def _poner(historial, datos: bytes | None) -> None:
    """Deja el diario con esos bytes (o sin fichero), y lo demás de `state/` vacío."""
    ruta = historial.ruta()
    ruta.parent.mkdir(parents=True, exist_ok=True)
    for resto in ruta.parent.iterdir():
        if resto.is_dir():
            resto.rmdir()
        else:
            resto.unlink()
    if datos is not None:
        ruta.write_bytes(datos)


def _bytes(historial) -> bytes | None:
    ruta = historial.ruta()
    return ruta.read_bytes() if ruta.is_file() else None


def _write_text_posix(real):
    """`store.write_text()` con los finales de línea de POSIX, en cualquier sistema."""
    def escribir(path, text):
        hecho = real(path, text)
        if hecho and os.linesep != "\n":
            datos = Path(path).read_bytes()
            Path(path).write_bytes(datos.replace(b"\r\n", b"\n"))
        return hecho
    return escribir


@contextmanager
def _sello_parado(store, sello: str):
    real = store.stamp
    store.stamp = lambda: sello
    try:
        yield
    finally:
        store.stamp = real


@contextmanager
def _monotono(valores: list[float]):
    """`time.monotonic()` devuelve esos valores, uno por llamada."""
    real, it = time.monotonic, iter(valores)
    time.monotonic = lambda: next(it)
    try:
        yield
    finally:
        time.monotonic = real


def _linea_cruda(**campos) -> str:
    """Una línea del diario con cada campo como TEXTO JSON ya escrito; `None` lo omite."""
    base = {"pareja": '"notas"', "inicio": '"2026-10-01 10:00:00"', "codigo": "0",
            "segundos": "1.5", "transferido": "2048"}
    base.update(campos)
    return "{" + ", ".join(f'"{k}": {v}' for k, v in base.items() if v is not None) + "}"


def _pasada(historial, pareja, n, codigo=0, segundos=None, transferido=None):
    return historial.Pasada(pareja, _sello(n), codigo, segundos, transferido)


# --- las líneas y su lectura -------------------------------------------------

def vectores_lineas(historial) -> list[dict]:
    """`_linea()`: la línea JSON de cada pasada, con ensure_ascii=False."""
    nombres = [
        "notas", "música ñandú", "日本語/写真", "📁 fotos 😀", 'con "comillas" y \\ barra',
        "tab\tsalto\nretorno\r", "ctl\x00\x01\x1f\x7f fin", "sep\u2028 \u2029 \u0085 fin",
        "  espacios  ", " ﻿​",
    ]
    pasadas = [_pasada(historial, n, i, 0, 0.5) for i, n in enumerate(nombres)]
    pasadas += [
        _pasada(historial, "numeros", 20, 0, 12.3, 1048576),
        _pasada(historial, "numeros", 21, 1, None, None),
        _pasada(historial, "numeros", 22, 2, 0.0, 0),
        _pasada(historial, "numeros", 23, 3, -0.0, None),
        _pasada(historial, "numeros", 24, -1, 0.1, None),
        _pasada(historial, "numeros", 25, 2 ** 80, 1e22, 2 ** 70),
        _pasada(historial, "numeros", 26, 0, 5e-324, 0),
        _pasada(historial, "numeros", 27, 255, 123456789.5, 123),
        _pasada(historial, "numeros", 28, 0, 1e16, None),
        _pasada(historial, "numeros", 29, 0, 100.0, None),
        _pasada(historial, "numeros", 30, 0, 1e-05, None),
        _pasada(historial, "numeros", 31, 0, 0.30000000000000004, None),
        _pasada(historial, "numeros", 32, 0, 1.7976931348623157e308, 2 ** 63),
    ]
    return [{"pasada": _d(p), "texto": historial._linea(p)} for p in pasadas]


def _casos_analizar(historial) -> list[tuple[str, object]]:
    """Las líneas que `_pasada()` puede encontrarse, buenas y malas: `(caso, texto)`."""
    anidada = "[" * 100 + "]" * 100
    codigo_4300 = _rep("9", 4300)
    c: list[tuple[str, object]] = [
        ("una línea de verdad", _linea_cruda()),
        ("sin duración ni bytes", _linea_cruda(segundos=None, transferido=None)),
        ("con null en lo accesorio", _linea_cruda(segundos="null", transferido="null")),
        ("con espacios y un retorno de carro alrededor", "  \t" + _linea_cruda() + "\r"),
        ("con saltos de línea dentro, que JSON da por espacio",
         '{\n"pareja": "notas",\r"inicio": "2026-10-01 10:00:00",\t"codigo": 0}'),
        ("campos de más", _linea_cruda(extra='{"a": [1, 2, {"b": null}]}', otro="true")),
        ("una clave repetida: manda la última", _linea_cruda().replace('"notas"', '"a", "pareja": "b"', 1)),
        ("anidada dentro de un campo que no se usa", _linea_cruda(extra=anidada)),
        ("anidada sin fin dentro de un campo que no se usa",
         ['{"pareja": "notas", "inicio": "2026-10-01 10:00:00", "codigo": 0, "extra": ',
          _rep("[", 200000), _rep("]", 200000), "}"]),
        ("nombre con escapes", _linea_cruda(pareja='"m\\u00fasica \\ud83d\\ude00 \\/ \\u2028 \\n"')),
        ("nombre de solo espacios", _linea_cruda(pareja='"   "')),
        ("nombre con un carácter fuera del plano básico", _linea_cruda(pareja='"📁 fotos"')),
        # lo que no es una línea
        ("vacía", ""),
        ("solo espacios", "   \t "),
        ("no es JSON", "esto no es JSON"),
        ("a medias", '{"pareja": "notas", "inicio": "2026-10-01 10:00:'),
        ("a medias en un número", '{"pareja": "notas", "inicio": "2026-10-01 10:00:00", "codigo": 0, "segundos": 1.'),
        ("una lista", "[]"),
        ("una lista con una pasada dentro", "[" + _linea_cruda() + "]"),
        ("una cadena", '"notas"'),
        ("un número", "5"),
        ("null", "null"),
        ("true", "true"),
        ("un objeto vacío", "{}"),
        ("dos objetos", _linea_cruda() + _linea_cruda()),
        ("basura detrás", _linea_cruda() + " x"),
        ("coma de más", _linea_cruda()[:-1] + ",}"),
        ("comillas simples", "{'pareja': 'notas', 'inicio': '2026-10-01 10:00:00', 'codigo': 0}"),
        ("con BOM", "﻿" + _linea_cruda()),
        ("espacio de no separación delante", " " + _linea_cruda()),
        ("salto de página delante, que no es espacio de JSON", "\x0c" + _linea_cruda()),
        ("tabulador sin escapar dentro de una cadena", _linea_cruda(pareja='"no\ttas"')),
        ("escape que no existe", _linea_cruda(pareja='"no\\xtas"')),
        ("escape unicode a medias", _linea_cruda(pareja='"no\\u12tas"')),
        ("anidada sin cerrar", "[" * 50),
        ("anidada sin fin", [_rep("[", 200000)]),
        # pareja
        ("sin pareja", _linea_cruda(pareja=None)),
        ("pareja vacía", _linea_cruda(pareja='""')),
        ("pareja que es un número", _linea_cruda(pareja="5")),
        ("pareja null", _linea_cruda(pareja="null")),
        ("pareja que es una lista", _linea_cruda(pareja='["notas"]')),
        ("pareja que es true", _linea_cruda(pareja="true")),
        # inicio
        ("sin inicio", _linea_cruda(inicio=None)),
        ("inicio que es un número", _linea_cruda(inicio="20261001")),
        ("inicio null", _linea_cruda(inicio="null")),
        ("inicio que no es una fecha", _linea_cruda(inicio='"ayer"')),
        ("inicio vacío", _linea_cruda(inicio='""')),
        ("inicio con mes 13", _linea_cruda(inicio='"2026-13-01 10:00:00"')),
        ("inicio con un 30 de febrero", _linea_cruda(inicio='"2026-02-30 10:00:00"')),
        ("inicio con la T de ISO", _linea_cruda(inicio='"2026-10-01T10:00:00"')),
        ("inicio sin segundos", _linea_cruda(inicio='"2026-10-01 10:00"')),
        ("inicio con un salto de línea detrás", _linea_cruda(inicio='"2026-10-01 10:00:00\\n"')),
        ("inicio con un espacio delante", _linea_cruda(inicio='" 2026-10-01 10:00:00"')),
        ("inicio con cifras sueltas, que strptime admite", _linea_cruda(inicio='"2026-10-1 3:4:5"')),
        ("inicio con dos espacios, que strptime admite", _linea_cruda(inicio='"2026-10-01  10:00:00"')),
        # codigo
        ("sin código", _linea_cruda(codigo=None)),
        ("código true", _linea_cruda(codigo="true")),
        ("código false", _linea_cruda(codigo="false")),
        ("código null", _linea_cruda(codigo="null")),
        ("código 1", _linea_cruda(codigo="1")),
        ("código 255", _linea_cruda(codigo="255")),
        ("código negativo", _linea_cruda(codigo="-1")),
        ("código menos cero", _linea_cruda(codigo="-0")),
        ("código decimal", _linea_cruda(codigo="1.0")),
        ("código con exponente", _linea_cruda(codigo="1e2")),
        ("código menos cero decimal", _linea_cruda(codigo="-0.0")),
        ("código texto", _linea_cruda(codigo='"1"')),
        ("código lista", _linea_cruda(codigo="[0]")),
        ("código mayor que un Long", _linea_cruda(codigo=str(2 ** 80))),
        ("código mayor que un Long, negativo", _linea_cruda(codigo=str(-2 ** 80))),
        ("código justo en el Long más grande", _linea_cruda(codigo=str(2 ** 63 - 1))),
        ("código justo en el Long más grande más uno", _linea_cruda(codigo=str(2 ** 63))),
        ("código justo en el Long más pequeño menos uno", _linea_cruda(codigo=str(-2 ** 63 - 1))),
        ("código de 4300 cifras", ['{"pareja": "notas", "inicio": "2026-10-01 10:00:00", "codigo": ',
                                   codigo_4300, "}"]),
        ("código de 4301 cifras, que Python no lee", ['{"pareja": "notas", "inicio": "2026-10-01 10:00:00", "codigo": ',
                                                      _rep("9", 4301), "}"]),
        ("código con cero a la izquierda", _linea_cruda(codigo="01")),
        ("código con más delante", _linea_cruda(codigo="+1")),
        # segundos
        ("segundos entero", _linea_cruda(segundos="3")),
        ("segundos cero", _linea_cruda(segundos="0")),
        ("segundos cero decimal", _linea_cruda(segundos="0.0")),
        ("segundos menos cero", _linea_cruda(segundos="-0.0")),
        ("segundos menos cero entero", _linea_cruda(segundos="-0")),
        ("segundos true", _linea_cruda(segundos="true")),
        ("segundos false", _linea_cruda(segundos="false")),
        ("segundos texto", _linea_cruda(segundos='"2"')),
        ("segundos negativos", _linea_cruda(segundos="-1")),
        ("segundos negativos decimales", _linea_cruda(segundos="-1.5")),
        ("segundos NaN", _linea_cruda(segundos="NaN")),
        ("segundos Infinity", _linea_cruda(segundos="Infinity")),
        ("segundos -Infinity", _linea_cruda(segundos="-Infinity")),
        ("segundos -NaN, que no es JSON", _linea_cruda(segundos="-NaN")),
        ("segundos que desbordan el float", _linea_cruda(segundos="1e999")),
        ("segundos que se quedan en cero", _linea_cruda(segundos="1e-999")),
        ("segundos con exponente", _linea_cruda(segundos="1E3")),
        ("segundos muy grandes", _linea_cruda(segundos="1e22")),
        ("segundos enteros muy grandes", _linea_cruda(segundos="12345678901234567890123")),
        ("segundos enteros de cuatrocientas cifras", _linea_cruda(segundos="9" * 400)),
        ("segundos justo en el mayor float", _linea_cruda(segundos=str(int(sys.float_info.max)))),
        ("segundos que redondean a infinito", _linea_cruda(segundos=str(int(sys.float_info.max) + 2 ** 970))),
        ("segundos que redondean al mayor float", _linea_cruda(segundos=str(int(sys.float_info.max) + 2 ** 970 - 1))),
        ("segundos lista", _linea_cruda(segundos="[1]")),
        ("segundos objeto", _linea_cruda(segundos='{"a": 1}')),
        ("segundos sin parte entera, que no es JSON", _linea_cruda(segundos=".5")),
        ("segundos con punto final, que no es JSON", _linea_cruda(segundos="1.")),
        # transferido
        ("transferido cero", _linea_cruda(transferido="0")),
        ("transferido menos cero", _linea_cruda(transferido="-0")),
        ("transferido negativo", _linea_cruda(transferido="-1")),
        ("transferido enorme", _linea_cruda(transferido=str(2 ** 70))),
        ("transferido decimal", _linea_cruda(transferido="1.0")),
        ("transferido true", _linea_cruda(transferido="true")),
        ("transferido texto", _linea_cruda(transferido='"5"')),
        ("transferido lista", _linea_cruda(transferido="[5]")),
        ("transferido mayor que un Long", _linea_cruda(transferido=str(2 ** 64))),
        ("transferido negativo mayor que un Long", _linea_cruda(transferido=str(-2 ** 64))),
    ]
    # Cada línea que escribe `_linea` se lee de vuelta tal cual.
    for p in [_pasada(historial, "notas", 1, 0, 1.2, 4096), _pasada(historial, "música", 2, 3),
              _pasada(historial, "x", 3, 2, 0.0, 0)]:
        c.append((f"la línea de {p.pareja!r} escrita por _linea", historial._linea(p)))
    return c


def vectores_analizar(historial) -> list[dict]:
    """`_pasada()`: qué sale de cada línea, o `None`."""
    salida = []
    for caso, partes in _casos_analizar(historial):
        p = historial._pasada(_armar(partes))
        salida.append({"caso": caso, "texto": partes, "pasada": None if p is None else _d(p)})
    return salida


def _ficheros_de_leer(historial) -> list[tuple[str, bytes]]:
    """Diarios con basura: `(caso, bytes)`."""
    def L(p) -> bytes:
        return historial._linea(p).encode("utf-8")

    a, b, c = (_pasada(historial, "notas", 1, 0, 1.0, 10), _pasada(historial, "fotos", 2, 1),
               _pasada(historial, "notas", 3, 2, 0.5))
    sep = _pasada(historial, "sep\u2028ara\u2029do\u0085 \x0b\x1c", 4, 0, 0.1)
    medio = L(c)[:30]
    return [
        ("tres pasadas", L(a) + L(b) + L(c)),
        ("sin salto de línea al final", (L(a) + L(b)).rstrip(b"\n")),
        ("con líneas en blanco y espacios", b"\n  \n" + L(a) + b"\n\t \n" + L(b) + b"\n\n"),
        ("con finales de línea de Windows", L(a).replace(b"\n", b"\r\n") + L(b).replace(b"\n", b"\r\n")),
        ("con una línea a medias en medio y otra al final", L(a) + medio + b"\n" + L(b) + medio),
        ("la línea a medias pegada a la siguiente se pierde con ella", L(a) + medio + L(b) + L(c)),
        ("con basura entre medias", L(a) + b"basura\n{}\n[1,2]\n" + b'"x"\n' + L(b) + b"null\n" + L(c)),
        ("con U+2028, U+2029 y NEL dentro del nombre, que no parten la línea", L(a) + L(sep) + L(b)),
        ("con un retorno de carro suelto entre campos", L(a) + b'{"pareja": "r",\r"inicio": "2026-10-01 10:00:00",\r"codigo": 0}\n' + L(b)),
        ("con U+000B entre dos pasadas: no parte, y se pierden las dos",
         L(a).rstrip(b"\n") + b"\x0b" + L(b)),
        ("con una línea de solo separadores de Unicode",
         L(a) + "\u2028\u0085\x0b\x1c\n".encode("utf-8") + L(b)),
        ("con BOM al principio, que echa a la primera línea", b"\xef\xbb\xbf" + L(a) + L(b)),
        ("con un byte que no es UTF-8 dentro de un nombre",
         L(a) + b'{"pareja": "no\xfftas", "inicio": "2026-10-01 10:00:00", "codigo": 0}\n' + L(b)),
        ("con un byte de continuación suelto dentro de un nombre",
         b'{"pareja": "a\x80b", "inicio": "2026-10-01 10:00:00", "codigo": 0}\n' + L(b)),
        ("con una letra cortada por la mitad al final", L(a) + "{\"pareja\": \"ñ".encode("utf-8")[:-1]),
        ("con ceros binarios", L(a) + b"\x00\x00\x00\x00\n" + L(b)),
        ("vacío", b""),
        ("solo un salto de línea", b"\n"),
        ("solo basura", ("basura\nmás basura\n").encode("utf-8")),
        ("repetidas, en su orden", L(c) + L(a) + L(c) + L(a)),
    ]


def vectores_leer(historial, estado: Path) -> list[dict]:
    """`leer()`: las pasadas de un diario con basura, y sin diario."""
    salida = []
    for caso, datos in _ficheros_de_leer(historial):
        _poner(historial, datos)
        salida.append({"caso": caso, **_texto_o_hex(datos),
                       "pasadas": [_d(p) for p in historial.leer()]})
    _poner(historial, None)
    salida.append({"caso": "sin fichero", "existe": False,
                   "pasadas": [_d(p) for p in historial.leer()]})
    _poner(historial, None)
    historial.ruta().mkdir()
    salida.append({"caso": "la ruta es una carpeta", "carpeta": True,
                   "pasadas": [_d(p) for p in historial.leer()]})
    _poner(historial, None)
    return salida


# --- apuntar -----------------------------------------------------------------

def vectores_apuntar(historial, model, estado: Path) -> list[dict]:
    """`apuntar()`: qué queda en el fichero según cómo estaba."""
    def L(p) -> bytes:
        return historial._linea(p).encode("utf-8")

    a = _pasada(historial, "notas", 1, 0, 1.0, 10)
    nueva = _pasada(historial, "música ñandú", 9, 2, 0.4, 0)
    media = L(a)[:25]
    antes = [
        ("sin fichero", None),
        ("fichero vacío", b""),
        ("acaba en salto de línea", L(a)),
        ("acaba en salto de línea de Windows", L(a).replace(b"\n", b"\r\n")),
        ("acaba a medias", L(a) + media),
        ("acaba en un retorno de carro", L(a).rstrip(b"\n") + b"\r"),
        ("acaba en un solo byte que no es salto", b"x"),
        ("acaba en un salto de línea tras basura", b"basura\n"),
        ("acaba en un byte que no es UTF-8", L(a) + b"\xff"),
    ]
    salida = []
    for caso, previo in antes:
        _poner(historial, previo)
        devuelve = historial.apuntar(nueva)
        despues = _bytes(historial)
        registro = {"caso": caso, "pasada": _d(nueva), "devuelve": devuelve,
                    **{"despues_" + k: v for k, v in _texto_o_hex(despues).items()}}
        if previo is not None:
            registro.update({"antes_" + k: v for k, v in _texto_o_hex(previo).items()})
        salida.append(registro)

    # Lo que no se puede escribir: se dice con `False`, sin lanzar.
    viejo = model.STATE_DIR
    try:
        model.STATE_DIR = estado / "no-existe"
        salida.append({"caso": "la carpeta de state no existe", "sin_carpeta": True,
                       "pasada": _d(nueva), "devuelve": historial.apuntar(nueva)})
    finally:
        model.STATE_DIR = viejo
    _poner(historial, None)
    historial.ruta().mkdir()
    salida.append({"caso": "la ruta del diario es una carpeta", "carpeta": True,
                   "pasada": _d(nueva), "devuelve": historial.apuntar(nueva)})
    _poner(historial, None)
    return salida


# --- recortar ----------------------------------------------------------------

def _linea_json(historial, p, partes=None):
    """La línea de una pasada como va al JSON de los casos.

    Es su texto (lo que `_linea()` escribe, sin el salto de línea), o una lista de
    partes cuando el nombre es enorme. El test de Kotlin la lee con `deLinea()`,
    que se prueba aparte contra `_pasada()`: así una pasada ocupa una línea del
    JSON y no siete.
    """
    linea = historial._linea(p).rstrip("\n")
    if partes is None:
        return linea
    nombre = [partes] if isinstance(partes, str) else list(partes)
    assert _armar(nombre) == p.pareja
    antes = '{"pareja": "'
    assert linea.startswith(antes + p.pareja + '"'), "el nombre lleva algo que json escribe distinto"
    forma = [antes, *nombre, linea[len(antes) + len(p.pareja):]]
    assert _armar(forma) == linea
    return forma


def _grande(historial, partes, n: int, codigo: int = 0):
    """Una pasada cuyo nombre es `partes` (enorme), ya como línea del JSON."""
    return _linea_json(historial, _pasada(historial, _armar(partes), n, codigo), partes)


def _pasadas_de(historial, formas) -> list:
    """Las pasadas de esas líneas, leídas con `_pasada()` de prdrive."""
    pasadas = [historial._pasada(_armar(f)) for f in formas]
    assert all(p is not None for p in pasadas)
    assert [historial._linea(p) for p in pasadas] == [_armar(f) + "\n" for f in formas]
    return pasadas


def _suma_exacta(historial, objetivo: int, n: int = 7, ancho: int = 40000) -> list:
    """`n` pasadas de parejas distintas cuyas líneas suman EXACTAMENTE `objetivo` bytes.

    Los nombres van en partes: `ancho` letras ñ (dos bytes cada una) y, en la
    última, las x que hagan falta.
    """
    partes = [[f"p{i:02d}", _rep("ñ", ancho)] for i in range(n - 1)]
    suma = sum(len(historial._linea(_pasada(historial, _armar(pt), i)).encode("utf-8"))
               for i, pt in enumerate(partes))
    corta = len(historial._linea(_pasada(historial, "z", n - 1)).encode("utf-8"))
    relleno = objetivo - suma - corta
    assert relleno >= 0
    partes.append(["z", _rep("x", relleno)])
    formas = [_grande(historial, pt, i) for i, pt in enumerate(partes)]
    assert sum(len((_armar(f) + "\n").encode("utf-8")) for f in formas) == objetivo
    return formas


def vectores_recortadas(historial) -> list[dict]:
    """`_recortadas()`: qué líneas quedan tras un recorte. Salen como índices de la entrada."""
    def pas(nombre: str, n: int):
        return _linea_json(historial, _pasada(historial, nombre, n, n % 4))

    def tanda(parejas: dict[str, int]) -> list:
        """Las pasadas de varias parejas, intercaladas, en orden de reloj."""
        restan = dict(parejas)
        salida, n = [], 0
        while any(restan.values()):
            for nombre in parejas:
                if restan[nombre] > 0:
                    restan[nombre] -= 1
                    n += 1
                    salida.append(pas(nombre, n))
        return salida

    casos = [
        ("sin pasadas", []),
        ("justo las que se guardan", tanda({"notas": 50})),
        ("una más de las que se guardan", tanda({"notas": 51})),
        ("tres parejas, dos pasadas del límite", tanda({"notas": 70, "fotos": 8, "música ñandú": 55})),
        ("las pasadas de una pareja que ya no existe se guardan igual", tanda({"vieja": 120})),
    ]
    for objetivo in (524287, 524288, 524289):
        casos.append((f"las líneas suman {objetivo} bytes", _suma_exacta(historial, objetivo)))
    # Más de 1 MiB de parejas distintas, con nombres de caracteres de uno a cuatro
    # bytes: se recorta por lo más viejo hasta la mitad.
    casos.append(("más de un mebibyte de parejas distintas",
                  [_grande(historial, [f"q{i:02d}", _rep("ñあ😀x", 10000)], i) for i in range(12)]))
    # Las dos reglas a la vez: una pareja con más de cincuenta pasadas, y muy larga.
    casos.append(("una sola pareja de nombre largo",
                  [_grande(historial, ["u", _rep("ñ", 9000)], i) for i in range(60)]))

    salida = []
    for caso, formas in casos:
        pasadas = _pasadas_de(historial, formas)
        lineas = [historial._linea(p) for p in pasadas]
        assert len(set(lineas)) == len(lineas)
        quedan = historial._recortadas(pasadas)
        indices = [lineas.index(q) for q in quedan]
        assert indices == sorted(indices)
        salida.append({"caso": caso, "pasadas": formas, "quedan": indices,
                       "bytes": sum(len(q.encode("utf-8")) for q in quedan)})
    return salida


def _tras_recortar(historial, formas: list, caso: str) -> dict:
    """Monta un diario con esas líneas, llama a `recortar_si_toca()` y apunta lo que queda."""
    lineas = [_armar(f) + "\n" for f in formas]
    assert len(set(lineas)) == len(lineas), "dos líneas iguales: no se sabría cuál queda"
    datos = "".join(lineas).encode("utf-8")
    _poner(historial, datos)
    devuelve = historial.recortar_si_toca()
    despues = _bytes(historial).decode("utf-8")
    assert "\r" not in despues
    partes = despues.split("\n")
    assert partes[-1] == ""
    quedan = [lineas.index(p + "\n") for p in partes[:-1]]
    return {"caso": caso, "lineas": formas, "bytes": len(datos), "devuelve": devuelve,
            "quedan": quedan, "bytes_despues": len(despues.encode("utf-8"))}


def vectores_recortar(historial) -> list[dict]:
    """`recortar_si_toca()`: cuándo reescribe el diario y qué deja."""
    def pas(nombre: str, n: int):
        return _linea_json(historial, _pasada(historial, nombre, n, n % 3))

    def tanda(nombre: str, cuantas: int, desde: int = 0) -> list:
        return [pas(nombre, desde + i) for i in range(cuantas)]

    casos = [
        ("sin nada que recortar", [pas("notas", 1), pas("fotos", 2)]),
        ("con basura y poco que recortar: se queda como está",
         [pas("notas", 1), "basura 1", pas("fotos", 2), "{}"]),
        ("justo en el recorte de una pareja", tanda("notas", 100)),
        ("una pasada más que el recorte de una pareja", tanda("notas", 101)),
        ("dos parejas, una pasada del recorte", tanda("notas", 101) + tanda("fotos", 10, 200)),
        ("una pareja pasada del recorte, con basura que se pierde",
         tanda("notas", 60) + ["basura 2"] + tanda("notas", 41, 100)),
        ("una pareja con el doble de pasadas y otra con pocas, intercaladas",
         [e for i in range(110) for e in ([pas("notas", i)] + ([pas("fotos", 1000 + i)] if i % 20 == 0 else []))]),
    ]
    salida = [_tras_recortar(historial, formas, caso) for caso, formas in casos]

    # El tope en bytes del fichero: cuenta el fichero entero, basura incluida.
    for objetivo in (1048575, 1048576, 1048577):
        validas = [pas("notas", 1), pas("fotos", 2), pas("notas", 3)]
        usado = sum(len((v + "\n").encode("utf-8")) for v in validas)
        relleno = objetivo - usado - 1       # el \n de la línea de basura
        formas = validas[:2] + [[_rep("j", relleno)]] + validas[2:]
        salida.append(_tras_recortar(historial, formas,
                                     f"el fichero pesa {objetivo} bytes, con una línea de basura enorme"))

    # Más de 1 MiB de parejas distintas: se recorta por lo más viejo hasta la mitad.
    salida.append(_tras_recortar(
        historial, [_grande(historial, [f"q{i:02d}", _rep("ñ", 50000)], i) for i in range(12)],
        "más de un mebibyte de parejas distintas"))
    return salida


def vectores_secuencias(historial) -> list[dict]:
    """`apuntar()` muchas veces: cuántas líneas quedan tras cada una, con el recorte solo."""
    def correr(caso: str, formas: list) -> dict:
        _poner(historial, None)
        pasadas = _pasadas_de(historial, formas)
        lineas = [historial._linea(p) for p in pasadas]
        assert len(set(lineas)) == len(lineas)
        cuenta = []
        for p in pasadas:
            assert historial.apuntar(p)
            cuenta.append(len(historial.leer()))
        despues = _bytes(historial).decode("utf-8")
        assert "\r" not in despues
        quedan = [lineas.index(q + "\n") for q in despues.split("\n")[:-1]]
        return {"caso": caso, "pasadas": formas, "cuenta": cuenta, "quedan": quedan,
                "bytes": len(despues.encode("utf-8"))}

    def pas(nombre: str, n: int):
        return _linea_json(historial, _pasada(historial, nombre, n, n % 3))

    una = [pas("notas", i) for i in range(160)]
    # Dos parejas que se alternan; la segunda no llega al recorte.
    dos = []
    for i in range(130):
        dos.append(pas("notas", 2 * i))
        if i % 40 == 0:
            dos.append(pas("fotos ñ", 2 * i + 1))
    # Más de 1 MiB de parejas distintas: el recorte por tamaño salta una sola vez
    # (y así sale igual con los finales de línea de Windows).
    largas = [_grande(historial, [f"q{i:02d}", _rep("ñ", 50000)], i) for i in range(14)]
    salida = [correr("una pareja con 160 pasadas", una),
              correr("dos parejas, una con 130 y otra con 4", dos),
              correr("14 parejas de nombre largo", largas)]
    _poner(historial, None)
    return salida


# --- el reloj ----------------------------------------------------------------

def vectores_reloj(historial, store) -> dict:
    """`Reloj` y `pasada()`: el inicio, la duración redondeada a una décima y el `int(codigo)`."""
    pares = [
        (1000.0, 1000.0), (1000.0, 1000.04), (1000.0, 1000.05), (1000.0, 1000.06),
        (1000.0, 1000.15), (1000.0, 1000.25), (1000.0, 1000.35), (1000.0, 1000.45),
        (1000.0, 1001.25), (1000.0, 1002.675), (1000.0, 1059.95), (1000.0, 1059.96),
        (1000.0, 4599.99), (1000.0, 87400.0), (1000.0, 1000.049999),
        (123456.789, 123458.039), (0.0, 0.049999999999999996), (5.5, 5.5),
        (98765.4321, 98765.4321 + 3600.0), (1000.0, 1e9), (0.0, 0.1), (0.0, 0.3),
        (0.0, 2.5), (0.0, 3.5), (0.0, 0.95), (0.0, 0.85),
        # un reloj que va hacia atrás no existe, pero round() daría -0.0
        (1000.0, 999.96), (1000.0, 999.0),
    ]
    transferidos = [None, 0, 5, 2 ** 40]
    con_reloj = []
    for i, (t0, t1) in enumerate(pares):
        sello, codigo, transferido = _sello(i), i % 3, transferidos[i % len(transferidos)]
        pareja = "música ñandú" if i % 2 else "notas"
        with _sello_parado(store, sello), _monotono([t0, t1]):
            reloj = historial.Reloj()
            p = reloj.pasada(pareja, codigo, transferido)
        assert reloj.inicio == sello
        con_reloj.append({"pareja": pareja, "codigo": codigo, "transferido": transferido,
                          "sello": sello, "t0": t0, "t1": t1, "pasada": _d(p)})

    sin_reloj = []
    for i, (codigo, transferido) in enumerate([(0, None), (1, 0), (2, 77), (255, 2 ** 40)]):
        sello = _sello(100 + i)
        with _sello_parado(store, sello):
            p = historial.pasada("notas", codigo, None, transferido)
        sin_reloj.append({"pareja": "notas", "codigo": codigo, "transferido": transferido,
                          "sello": sello, "pasada": _d(p)})

    # `pasada()` con reloj es la del reloj.
    via_pasada = []
    for i, (t0, t1) in enumerate(pares[:6]):
        sello = _sello(200 + i)
        with _sello_parado(store, sello), _monotono([t0, t1]):
            reloj = historial.Reloj()
            p = historial.pasada("fotos", 1, reloj, 123)
        via_pasada.append({"pareja": "fotos", "codigo": 1, "transferido": 123, "sello": sello,
                           "t0": t0, "t1": t1, "pasada": _d(p)})
    return {"con_reloj": con_reloj, "sin_reloj": sin_reloj, "con_reloj_por_pasada": via_pasada}


# --- las rachas --------------------------------------------------------------

def _tanda(historial, pareja: str, codigos: list[int], desde: int = 0) -> list:
    return [_pasada(historial, pareja, desde + i, c) for i, c in enumerate(codigos)]


def _inicio_y_codigo(pasadas: list) -> list[str]:
    """Lo único que mira `racha()` de una pasada, como `"<inicio> <código>"`."""
    return [f"{p.inicio} {p.codigo}" for p in pasadas]


def vectores_racha(historial) -> list[dict]:
    """`racha()`: la racha de fallos de las pasadas de UNA pareja ("notas")."""
    def r(x):
        return None if x is None else dict(x._asdict())

    secuencias = [
        ("sin pasadas", []),
        ("una buena", [0]),
        ("una mala", [1]),
        ("buena y mala", [0, 1]),
        ("mala y buena", [1, 0]),
        ("tres malas", [1, 1, 1]),
        ("una buena y dos malas", [0, 1, 1]),
        ("mala, buena y dos malas", [1, 0, 1, 1]),
        ("códigos distintos", [2, 0, 3, 3]),
        ("un código negativo", [0, -1]),
        ("un código enorme", [0, 2 ** 64]),
        ("buenas y una mala", [0, 0, 0, 1]),
        ("cincuenta buenas y una mala", [0] * 50 + [1]),
        ("cuarenta y nueve buenas y una mala", [0] * 49 + [1]),
        ("sesenta malas, que no se sabe desde cuándo", [1] * 60),
        ("una buena y sesenta malas", [0] + [1] * 60),
        ("cincuenta malas", [1] * 50),
        ("cuarenta y nueve malas", [1] * 49),
        ("una racha larga tras un tramo mezclado", [0, 1] * 30 + [1] * 55),
        ("un tramo mezclado que acaba bien", [1, 0] * 40),
        ("una racha corta dentro de una larga ventana", [0] * 70 + [1, 1, 1]),
        ("la ventana de cincuenta con buenas y malas", [0, 1, 0, 1] * 20 + [1, 1]),
    ]
    return [{"caso": caso, "pasadas": _inicio_y_codigo(_tanda(historial, "notas", codigos)),
             "racha": r(historial.racha(_tanda(historial, "notas", codigos)))}
            for caso, codigos in secuencias]


def vectores_rachas(historial) -> dict:
    """`rachas()`: lo mismo para varias parejas, leyendo el diario una vez."""
    def r(x):
        return {k: dict(v._asdict()) for k, v in x.items()}

    n = 0

    def tanda(pareja, codigos):
        nonlocal n
        salida = _tanda(historial, pareja, codigos, n)
        n += len(codigos)
        return salida

    # Intercaladas, para que el orden del diario no sea el de las parejas, y con
    # una línea de basura detrás de la primera.
    notas = tanda("notas", [0, 0, 1, 1])
    fotos = tanda("fotos", [1, 1, 0])
    musica = tanda("música ñandú", [0, 1, 1, 1, 1])
    otra = tanda("otra", [0, 0, 0])
    sola = tanda("sola", [2])
    mixto = []
    for i in range(5):
        for lista in (notas, fotos, musica, otra, sola):
            if i < len(lista):
                mixto.append(lista[i])
    diario = "".join(historial._linea(p) for p in mixto).encode("utf-8")
    corte = diario.index(b"\n") + 1
    diario = diario[:corte] + b"basura\n" + diario[corte:]

    consultas = [
        ("todas", ["notas", "fotos", "música ñandú", "otra", "sola"]),
        ("en otro orden", ["sola", "música ñandú", "notas"]),
        ("una que falla", ["notas"]),
        ("una que va bien", ["otra"]),
        ("una que ya no falla", ["fotos"]),
        ("una que no consta", ["nunca"]),
        ("con repetidas", ["notas", "notas", "sola"]),
        ("ninguna", []),
        ("mayúsculas, que son otro nombre", ["NOTAS", "Sola"]),
    ]
    _poner(historial, diario)
    casos = [{"caso": caso, "nombres": nombres, "rachas": r(historial.rachas(nombres))}
             for caso, nombres in consultas]

    # Más de las que se guardan: la racha se cuenta con las últimas cincuenta.
    largo = _tanda(historial, "larga", [0] * 20 + [1] * 70, 500)
    _poner(historial, "".join(historial._linea(p) for p in largo).encode("utf-8"))
    larga = {"pareja": "larga", "pasadas": _inicio_y_codigo(largo), "nombres": ["larga"],
             "rachas": r(historial.rachas(["larga"]))}

    _poner(historial, None)
    sin_diario = {"nombres": ["notas"], "rachas": r(historial.rachas(["notas"]))}
    return {"diario": _texto_o_hex(diario), "consultas": casos, "larga": larga,
            "sin_diario": sin_diario}


# --- todo ---------------------------------------------------------------------

def vectores(historial, model, store) -> dict:
    """La sección `historial` de `vectores.json`."""
    viejos = model.STATE_DIR, store.write_text, store.stamp
    try:
        with tempfile.TemporaryDirectory() as tmp:
            estado = Path(tmp) / "state"
            estado.mkdir()
            model.STATE_DIR = estado
            store.write_text = _write_text_posix(viejos[1])
            return {
                "constantes": {"POR_PAREJA": historial.POR_PAREJA, "RECORTE": historial.RECORTE,
                               "TOPE_BYTES": historial.TOPE_BYTES,
                               "fichero": historial.ruta().name},
                "lineas": vectores_lineas(historial),
                "analizar": vectores_analizar(historial),
                "leer": vectores_leer(historial, estado),
                "apuntar": vectores_apuntar(historial, model, estado),
                "recortadas": vectores_recortadas(historial),
                "recortar": vectores_recortar(historial),
                "secuencias": vectores_secuencias(historial),
                "reloj": vectores_reloj(historial, store),
                "racha": vectores_racha(historial),
                "rachas": vectores_rachas(historial),
            }
    finally:
        model.STATE_DIR, store.write_text, store.stamp = viejos
