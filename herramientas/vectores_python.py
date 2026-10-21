"""
vectores_python.py — Lo que hace Python en lo que el motor imita de Python.

No sale de un fichero de prdrive sino del intérprete con el que corre prdrive:
`repr()` de un float, `fnmatch`, `json.loads`, `int()`, `datetime.strptime`,
`sorted()` de cadenas y `str.splitlines()`. El motor los reproduce a mano
(Python.kt, Json.kt, Store.kt) y por eso sus valores esperados salen de aquí,
como todos los demás. Donde prdrive usa una de esas piezas (`diff_keys`,
`parse_rclone_conf`, `header_of`), se le pregunta a prdrive.

Lo importa `vectores.py` y mete lo que devuelve [vectores] en el mismo JSON.
"""

from __future__ import annotations

import fnmatch
import json
import math
import random
import struct
from datetime import datetime


def _bits(x: float) -> int:
    """Los 64 bits del double, con signo: así llega exacto, sin pasar por texto."""
    return struct.unpack("<q", struct.pack("<d", x))[0]


def _floats() -> dict:
    """`repr()` de floats donde el más corto cuesta: todas las potencias de dos
    (en ellas el intervalo de abajo es la mitad) y unos cuantos al azar. En dos
    listas paralelas, que son unos miles."""
    valores = [math.ldexp(1.0, e) for e in range(-1074, 1024)]
    azar = random.Random(20261021)
    while len(valores) < 2098 + 300:
        x = struct.unpack("<d", struct.pack("<Q", azar.getrandbits(64)))[0]
        if math.isfinite(x):
            valores.append(x)
    valores += [0.1, 1 / 3, 5e-324, 1e22, 1e16, 9999999999999998.0, 2.0 ** 53 + 2, -0.0]
    return {"bits": [_bits(x) for x in valores], "repr": [repr(x) for x in valores]}


# Lo raro de los corchetes de fnmatch: rangos al revés, guiones sueltos, lo que
# en una regex de Java sería sintaxis (`&&`, `[`), `!` y `^`, y no-ASCII.
PATRONES = [
    "[z-a]", "[a&&b]", "[!]", "[!]]", "[]a]", "[!]a]", "[a-]", "[--a]", "[!b-a]",
    "[a-b-c]", "[!b-a-z]", "[^a]", "[\\]", "[\\a]", "*.[ch]", "[[]", "[a-c-b-a]",
    "[a~~b]", "[a||b]", "[à-ü]", "[😀-😂]", "[!😀]", "?", "a*b*c", "*", "**", "[",
    "[!", "a[", "[a", "[]", "[-]", "[!-]", "[a-c]x", "[b-a]x", "[-a]", "[a-c-]",
    "[!a-c-]", "[%-+]", "[+--]", ".*", "\\*", "[.]", "x[!.]y", "[!!]", "[a-a]",
    "[b-aa]", "[ab-ac]", "*[!a]", "[!a-]", "[\\-a]",
]
NOMBRES = [
    "", "a", "b", "c", "z", "-", "!", "^", "]", "[", "\\", "&", "~", "|", "😀",
    "😁", "😃", "é", "ñ", "\n", "x", "ab", "abc", "axbxc", "a.c", "a.h", "xay",
    "x.y", "ax", "bx", "+", "%", ",", "*", "a[", ".", "aa", "[!]", "[a", "[]",
    "[!", "ba",
]


def _fnmatch() -> dict:
    """Cada patrón contra cada nombre: por patrón, una tira de `1` (casa) y `0`
    en el orden de los nombres."""
    return {"patrones": PATRONES, "nombres": NOMBRES,
            "casa": ["".join("1" if fnmatch.fnmatchcase(n, p) else "0" for n in NOMBRES)
                     for p in PATRONES]}


# `json.loads`: lo que acepta y lo que no.
TEXTOS_JSON = [
    "01", "1.", "-", "1e", "1.5e+", "-0", '"a\x01b"', '"a\x1fb"', '"a\x7fb"',
    "1" + "0" * 25, "-" + "9" * 30, "1" + "0" * 4300, "1" + "0" * 4299,
    "[1,]", '{"a":1,}', "1E5", "-01", "1.5e3", "1e400", "-1e400", "NaN",
    "-Infinity", "Infinity", "nan", '[1, 2.0, true, null, "x"]',
    '{"a": {"b": [1e5, -0.0]}}', '"\\ud83d\\ude00"', '"\\u00e9"', " 1 ", "1 2",
    '{"a":1,"a":2}', '"\t"', "0.0", "-0.0", "0e0", "0.5E-3", "9223372036854775807",
    "9223372036854775808", "-9223372036854775808", "-9223372036854775809",
    '"\\x41"', '"\\/"', "[1e]", "[01]", "{\"a\" 1}", "", "  ", "tru", "[\n]",
]


def _json() -> list[dict]:
    salida = []
    for t in TEXTOS_JSON:
        try:
            normal = json.dumps(json.loads(t), ensure_ascii=False, indent=1)
        except ValueError:
            normal = None
        salida.append({"texto": t, "normal": normal})
    return salida


# `int()`: los valores pasan por JSON, así que son lo que puede salir de uno.
ENTEROS = [
    True, False, 0, -5, 2 ** 63, -2 ** 63 - 1, 10 ** 30, 1.9, -1.9, 1e20, 1e300,
    float("nan"), float("inf"), "12", " 12 ", "1_000", "1__0", "_1", "1_", "+7",
    "-7", "١٢", "１２", "12.0", "", " ", "0x10", "1e3", None, [1], "9" * 25,
    "　12　", "1" * 4300, "1" * 4301, {"a": 1}, "+-1", "--1",
]


def _int() -> list[dict]:
    salida = []
    for v in ENTEROS:
        try:
            r = str(int(v))
        except (TypeError, ValueError, OverflowError):
            r = None
        salida.append({"valor": v, "int": r})
    return salida


FORMATO_SELLO = "%Y-%m-%d %H:%M:%S"
SELLOS_LEER = [
    "2024-1-5 3:4:5", "2024-01-05  03:04:05", "２０２４-01-05 03:04:05",
    "2024-01-05\t03:04:05", "2024-01-05 03:04:60", "2024-01-05 03:04:61",
    "2024-02-30 01:01:01", "2024-02-29 01:01:01", "2023-02-29 01:01:01",
    " 2024-01-05 03:04:05", "2024-01-05 03:04:05 ", "2024-01- 5 03:04:05",
    "02024-01-05 03:04:05", "2024-01-05 24:00:00", "2024-01-05\n03:04:05",
    "٢٠٢٤-٠١-٠٥ ٠٣:٠٤:٠٥", "2024-01-05 03:04:5", "0000-01-01 00:00:00",
    "0001-01-01 00:00:00", "2024-01-05 　03:04:05", "2024-01-05\x8503:04:05",
    "2024-01-05  3:04:05", "2024-01-05 03: 4:05", "2024-01-05 1٣:04:05",
    "2024-1٢-05 03:04:05", "2026-01-02 03:04:05", "no es una fecha", "",
    "9999-12-31 23:59:59", "2024-01-05 03:04:05\n", "2024-13-01 00:00:00",
    "2024-01-05 03:04:65", "2024-01-05\x1c03:04:05", "2024-01-05​03:04:05",
    "2024-01-0503:04:05", "2024-1-31 3:04:05", "2024-01-3 03:04:05",
]


def _sellos() -> list[dict]:
    salida = []
    for s in SELLOS_LEER:
        try:
            d = datetime.strptime(s, FORMATO_SELLO)
            campos = [d.year, d.month, d.day, d.hour, d.minute, d.second]
        except ValueError:
            campos = None
        salida.append({"texto": s, "campos": campos})
    return salida


def _diff_keys(catalog) -> list[dict]:
    """`catalog.diff_keys`, que compara con el `==` de Python y ordena con su
    `sorted()`. Los valores también pasan por JSON."""
    nan = float("nan")
    casos = [
        ({"x": 2 ** 53 + 1}, {"x": 2.0 ** 53}),
        ({"x": [1]}, {"x": [1.0]}),
        ({"x": True}, {"x": 1}),
        ({"x": True}, {"x": 1.0}),
        ({"x": False}, {"x": 0}),
        ({"x": None}, {}),
        ({"x": float("inf")}, {"x": float("inf")}),
        ({"x": float("inf")}, {"x": float("-inf")}),
        ({"x": nan}, {"x": float("nan")}),
        ({"x": "1"}, {"x": 1}),
        ({"x": {"a": 1, "b": 2}}, {"x": {"b": 2.0, "a": 1}}),
        ({"x": {"a": 1}}, {"x": {"a": 1, "b": None}}),
        ({"x": [1, 2]}, {"x": [1]}),
        ({"😀": 1, "Ａ": 1, "x": 1, "a": 1}, {}),
        ({"x": 10 ** 20}, {"x": 1e20}),
        ({"x": 10 ** 20 + 1}, {"x": 1e20}),
        ({"x": "a", "y": [True, 0.5]}, {"x": "a", "y": [1, 0.5]}),
        ({"x": []}, {"x": {}}),
        ({"x": 0.0}, {"x": -0.0}),
    ]
    return [{"a": a, "b": b, "diff": list(catalog.diff_keys(a, b))} for a, b in casos]


ORDENAR = ["😀", "Ａ", "x", "a", "B", "b", "é", "é", "", "zz", "z",
           "\U0001f600a", "￿", "\U00010000", "~", "ア"]


def _splitlines(pairing, config_file) -> dict:
    """`parse_rclone_conf` y `header_of`, que parten con `splitlines()`."""
    confs = [
        "[a]\ntype = local\nkey = a [otro]\nx = 1\n",
        "[a]\rtype = local\r\n[b]\x0btype = sftp\x0chost = h\x1cuser = u\x85pass = p [c]\x1d\x1etype = x",
        "[a]\ntype = local\n\n",
        "  [ a ]  \n k = v = w \n;c\n#c\n[b]\nsin igual\n",
    ]
    cabeceras = [
        "# uno\n# dos\n\n[defaults]\n",
        "# uno # dos\u0085\n[defaults]\n",
        "　# sangrado\n \n[a]\n",
        "# sola\x0b# otra\x0c\n\x1c\nclave = 1\n",
        "\n\n",
        "# fin\u0085  \n",
        "",
    ]
    return {
        "rclone_conf": [{"texto": t, "remotes": pairing.parse_rclone_conf(t)} for t in confs],
        "cabeceras": [{"texto": t, "cabecera": config_file.header_of(t)} for t in cabeceras],
        "lineas": [{"texto": t, "lineas": t.splitlines()} for t in confs + cabeceras + ["a\r\n\r\nb\r", "\r\n", "x"]],
    }


# Lo que el lector de TOML del motor tiene que leer como tomllib, o rechazar
# como tomllib. El veredicto y el valor los da tomllib.
TOML_LEER = [
    'a = "\\u0041\\u00E9\\U0001F600\\b\\f"\n',
    "a = 1\nb = -2\nc = 1_000\nd = 1.5\ne = true\nf = false\n",
    'exclude = [\n    "*.tmp",\n    "*.bak",\n]\n',
    "a = []\n", 'a = ["x"]\n', 'a = ["x", "y",]\n',
    'a = "con # dentro"  # y aquí sí\n',
    "a = 'literal \\ sin escapes'\n",
    "a = inf\nb = -inf\nc = +1.5e3\nd = 1e-7\n",
    'a = "\\t\\n\\r\\\\\\""\n',
    '[defaults]\nremote = "nas"\n\n[[pair]]\nname = "x"\n\n[pair.flags]\nmax-delete = 9\n',
    "a = 0\nb = +0\nc = -0\n",
    "a = 9223372036854775807\nb = -9223372036854775808\n",
    '"clave con espacios" = 1\n',
    "# solo un comentario\n",
    "",
    # Lo que tomllib rechaza.
    "clave sin igual\n", "a = 1\na = 2\n", "[defaults\n", "[]\n", 'a = "abierta\n',
    "a = [1, 2\n", "a = 1\n[a]\nb = 2\n", 'a = "\\uZZZZ"\n', 'a = "\\u+041"\n',
    'a = "\\u12"\n', 'a = "\\/"\n', 'a = "a\x01b"\n', "a = 'a\x7fb'\n", "a = \u0661\u0662\n",
    "a = 01\n", "a = 1__0\n", 'a = "\\x41"\n', 'a = "\\uD800"\n', "a = 1\rb = 2\n",
    'a = "\x0b"\n', "a = _1\n", "a = 1_\n", "a = +01\n", "a = -01\n", "a = 1.\n",
    "a = .5\n", "a = 1._5\n", "a = 1e\n", "a = 1e_5\n", "a = 00.5\n", "a = 1.5.5\n",
    "a = 0_1\n", "a = 1__000.0\n", "a = 1_000.0_0e1_0\n", "a = 1e06\n", "a = 0e0\n",
    "a = -0.0\n", "a = 1E+2\n", "a = +inf\nb = +nan\n", "a = infinity\n", "a = True\n",
    "# coment\x01ario\n", "# con tab\t vale\na = 1\n", "a = 1 # \x7f\n", "a = 1\r\n",
    "a = 1\r\nb = 2\r\n", "a = 1\r\n\r\n", "a = 1", "\n\n\na = 1\n\n", "a = 1\r", "# c\r",
    "a = 1\n\r", "a = 1\r\r\n",
]

# Lo que tomllib acepta y el lector del motor no cubre, a propósito: tiene que
# rechazarlo en voz alta, que callar y seguir sería leer media pareja. Esto sí
# se escribe aquí, porque no es lo que dice Python sino lo que cubre el motor.
TOML_NO_SOPORTADO = [
    "a = 1979-05-27\n", "[pair.flags]\na = 1\n", "[[a.b]]\nc = 1\n",
    "a = 9223372036854775808\n", "a.b = 1\n", "a = {b = 1}\n",
    'a = """\nvarias\nlíneas"""\n', "a = 0x1F\n", "a = 0o17\n", "a = 0b101\n",
]


def _toml_leer() -> list[dict]:
    import tomllib  # noqa: PLC0415

    salida = []
    for t in TOML_LEER + TOML_NO_SOPORTADO:
        try:
            valor, acepta = tomllib.loads(t), True
        except tomllib.TOMLDecodeError:
            valor, acepta = None, False
        soportado = t not in TOML_NO_SOPORTADO
        salida.append({"texto": t, "tomllib_acepta": acepta, "soportado": soportado,
                       "valor": valor if acepta and soportado else None})
    return salida


def vectores(catalog, pairing, config_file) -> dict:
    return {
        "toml_leer": _toml_leer(),
        "repr_float": _floats(),
        "fnmatch": _fnmatch(),
        "json_leer": _json(),
        "int": _int(),
        "strptime": {"formato": FORMATO_SELLO, "casos": _sellos()},
        "diff_keys": _diff_keys(catalog),
        "sorted": {"entrada": ORDENAR, "salida": sorted(ORDENAR)},
        "splitlines": _splitlines(pairing, config_file),
    }
