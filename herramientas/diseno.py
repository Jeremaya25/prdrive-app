"""
diseno.py — Escribe los colores, el fondo de la ventana y los glifos de la app
desde el sistema de diseño «prdrive», que vive en `app/diseno/`.

De dónde sale `app/diseno/`:

- `tokens.json` es `project/tokens.json` del sistema de diseño «prdrive»
  (https://claude.ai/code/artifact/bd675973-d734-4d4c-94cf-ea42ce5332b9,
  versión `1793185577-cb49`), byte a byte. Es el que copia el lienzo del
  rediseño (`project/ds/prdrive/tokens.json`), con la misma suma SHA-256.
- `iconos.json` es el mapa `ICONS` de `project/components/bundle.js` de esa
  misma versión: `{"<nombre>": "<interior del svg>"}`, los 30 glifos.

Para ponerlos al día con una versión nueva del sistema, se copian otra vez los
dos y se ejecuta esto. Lo que escribe no se edita a mano
(`.claude/hooks/generados.py` no deja), y el CI lo comprueba
(`.github/actions/compilar-apk`):

    python herramientas/diseno.py              # escribe
    python herramientas/diseno.py --comprobar  # no escribe; 1 si algo no está al día

Lo que escribe:

- `app/src/main/kotlin/prdrive/app/tema/Tokens.kt`: `Colores` (un color por
  token, en camelCase) y `Tokens` (`claro`, `oscuro`, los espacios y los radios).
- `app/src/main/res/values/fondo.xml` y `values-night/fondo.xml`: el `paper` de
  cada modo como `@color/fondo`, el de la ventana antes de que Compose dibuje.
- `app/src/main/res/drawable/pd_<nombre>.xml`: un vector por glifo, con su
  trazo y su relleno en negro; el color lo pone después el `tint` del icono.

Solo la biblioteca estándar. Escribe con finales de línea LF y, al comparar, no
mira los finales de línea: la copia de Windows tiene `core.autocrlf`.
"""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
DISENO = RAIZ / "app" / "diseno"
TOKENS = DISENO / "tokens.json"
ICONOS = DISENO / "iconos.json"
KOTLIN = RAIZ / "app" / "src" / "main" / "kotlin" / "prdrive" / "app" / "tema" / "Tokens.kt"
RES = RAIZ / "app" / "src" / "main" / "res"
DRAWABLE = RES / "drawable"
PREFIJO_GLIFO = "pd_"


def aviso(origen: str) -> str:
    return f"Generado por herramientas/diseno.py desde app/diseno/{origen}: no lo edites a mano."


# --- colores --------------------------------------------------------------------


def camel(nombre: str) -> str:
    """`warn-line-strong` -> `warnLineStrong`, `ink-2` -> `ink2`."""
    partes = nombre.split("-")
    return partes[0] + "".join(p[:1].upper() + p[1:] for p in partes[1:])


HEX = re.compile(r"#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?")


def resolver(tokens: dict[str, dict[str, str]], nombre: str, tema: str, vistos: tuple[str, ...] = ()) -> str:
    """El `#RRGGBB[AA]` de un token en un tema, siguiendo los `{otro}`."""
    if nombre in vistos:
        raise SystemExit(f"tokens.json: «{nombre}» se refiere a sí mismo ({' -> '.join(vistos + (nombre,))}).")
    valor = tokens[nombre][tema]
    referencia = re.fullmatch(r"\{([a-z0-9-]+)\}", valor)
    if referencia:
        otro = referencia.group(1)
        if otro not in tokens:
            raise SystemExit(f"tokens.json: «{nombre}» se refiere a «{otro}», que no existe.")
        return resolver(tokens, otro, tema, vistos + (nombre,))
    if not HEX.fullmatch(valor):
        raise SystemExit(f"tokens.json: «{nombre}» ({tema}) no es #RRGGBB ni #RRGGBBAA: {valor!r}.")
    return valor


def argb(valor: str) -> str:
    """`#RRGGBB` -> `FFRRGGBB`; `#RRGGBBAA` -> `AARRGGBB`, en mayúsculas."""
    m = HEX.fullmatch(valor)
    assert m
    return ((m.group(2) or "FF") + m.group(1)).upper()


def colores(datos: dict) -> tuple[list[str], dict[str, dict[str, str]]]:
    """Los nombres en su orden, y su valor por tema (`light`, `dark`)."""
    lista = datos["color"]["tokens"]
    tokens = {t["name"]: t["value"] for t in lista}
    orden = [t["name"] for t in lista]
    return orden, {n: {tema: resolver(tokens, n, tema) for tema in ("light", "dark")} for n in orden}


def medidas(datos: dict, grupo: str, prefijo: str, nuevo: str) -> list[tuple[str, str]]:
    """`space-3` -> (`espacio3`, `12`). Solo lo que va en px, y nunca 999px (la píldora)."""
    salida = []
    for t in datos[grupo]["tokens"]:
        valor = t["value"]
        if not isinstance(valor, str) or not valor.endswith("px") or valor == "999px":
            continue
        sufijo = t["name"].removeprefix(prefijo)
        nombre = nuevo + (sufijo if sufijo.isdigit() else sufijo[:1].upper() + sufijo[1:])
        salida.append((nombre, numero(float(valor[:-2]))))
    return salida


def kotlin(datos: dict) -> str:
    orden, valores = colores(datos)
    lineas = [
        f"// {aviso('tokens.json')}",
        "package prdrive.app.tema",
        "",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.unit.Dp",
        "import androidx.compose.ui.unit.dp",
        "",
        "/**",
        " * Los colores del sistema de diseño «prdrive», uno por token, en un tema. Las",
        " * pantallas los leen de `Paleta.actual` o de `MaterialTheme.colorScheme`, nunca",
        " * de aquí: así cada modo sale solo.",
        " */",
        "data class Colores(",
    ]
    lineas += [f"    val {camel(n)}: Color," for n in orden]
    lineas += [")", "", "/** Los valores del sistema de diseño: los dos temas, los espacios y los radios. */", "object Tokens {"]
    for nombre, tema in (("claro", "light"), ("oscuro", "dark")):
        lineas.append(f"    val {nombre} = Colores(")
        lineas += [f"        {camel(n)} = Color(0x{argb(valores[n][tema])}),"
                   for n in orden]
        lineas.append("    )")
        lineas.append("")
    for nombre, valor in medidas(datos, "spacing", "space-", "espacio") + medidas(datos, "radius", "radius-", "radio"):
        lineas.append(f"    val {nombre}: Dp = {valor}.dp")
    lineas.append("}")
    return "\n".join(lineas) + "\n"


def fondo(datos: dict, tema: str) -> str:
    _, valores = colores(datos)
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {aviso('tokens.json')}\n"
        "     El «paper» del sistema de diseño: el fondo de la ventana, que se ve antes\n"
        "     de que Compose dibuje (tema.xml). TemaTest comprueba que es el de Compose. -->\n"
        "<resources>\n"
        f'    <color name="fondo">#{argb(valores["paper"][tema])}</color>\n'
        "</resources>\n"
    )


# --- glifos ---------------------------------------------------------------------


def numero(n: float) -> str:
    """Un número como lo escribiría una persona: `5`, `0.75`, `-1.5`."""
    texto = f"{n:.4f}".rstrip("0").rstrip(".")
    return "0" if texto in ("-0", "") else texto


def rect(a: dict[str, str]) -> str:
    x, y = float(a.get("x", 0)), float(a.get("y", 0))
    w, h = float(a["width"]), float(a["height"])
    r = min(float(a.get("rx", a.get("ry", 0))), w / 2, h / 2)
    n = numero
    if r <= 0:
        return f"M{n(x)},{n(y)}h{n(w)}v{n(h)}h{n(-w)}z"
    return (
        f"M{n(x + r)},{n(y)}h{n(w - 2 * r)}a{n(r)},{n(r)} 0 0 1 {n(r)},{n(r)}"
        f"v{n(h - 2 * r)}a{n(r)},{n(r)} 0 0 1 {n(-r)},{n(r)}"
        f"h{n(-(w - 2 * r))}a{n(r)},{n(r)} 0 0 1 {n(-r)},{n(-r)}"
        f"v{n(-(h - 2 * r))}a{n(r)},{n(r)} 0 0 1 {n(r)},{n(-r)}z"
    )


def circulo(a: dict[str, str]) -> str:
    cx, cy, r = float(a["cx"]), float(a["cy"]), float(a["r"])
    n = numero
    return f"M{n(cx - r)},{n(cy)}a{n(r)},{n(r)} 0 1 0 {n(2 * r)},0a{n(r)},{n(r)} 0 1 0 {n(-2 * r)},0z"


def figuras(interior: str, nombre: str) -> list[tuple[str, str]]:
    """Las figuras de un glifo: (datos del path, clase), en su orden."""
    raiz = ET.fromstring(f"<g>{interior}</g>")
    salida = []
    for f in raiz:
        clase = f.get("class", "")
        if f.tag == "path":
            datos = f.get("d", "")
        elif f.tag == "rect":
            datos = rect(f.attrib)
        elif f.tag == "circle":
            datos = circulo(f.attrib)
        else:
            raise SystemExit(f"iconos.json: «{nombre}» lleva un <{f.tag}>, que no sé dibujar.")
        if clase not in ("", "pd-dot", "pd-fill"):
            raise SystemExit(f"iconos.json: «{nombre}» lleva la clase {clase!r}, que no conozco.")
        salida.append((datos, clase))
    return salida


def vector(nombre: str, interior: str) -> str:
    lineas = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- {aviso('iconos.json')}",
        f"     El glifo «{nombre}»: trazo de 1,5 sobre la rejilla de 16. El color lo pone el tint. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="16dp"',
        '    android:height="16dp"',
        '    android:viewportWidth="16"',
        '    android:viewportHeight="16">',
    ]
    for datos, clase in figuras(interior, nombre):
        lineas.append("    <path")
        lineas.append(f'        android:pathData="{datos}"')
        if clase == "pd-dot":
            lineas.append('        android:fillColor="#FF000000" />')
            continue
        lineas.append('        android:fillColor="#FF000000"' if clase == "pd-fill" else '        android:fillColor="#00000000"')
        lineas.append('        android:strokeColor="#FF000000"')
        lineas.append('        android:strokeWidth="1.5"')
        lineas.append('        android:strokeLineCap="round"')
        lineas.append('        android:strokeLineJoin="round" />')
    lineas.append("</vector>")
    return "\n".join(lineas) + "\n"


def glifo(nombre: str) -> Path:
    return DRAWABLE / f"{PREFIJO_GLIFO}{nombre.replace('-', '_')}.xml"


# --- todo junto -----------------------------------------------------------------


def ficheros() -> dict[Path, str]:
    datos = json.loads(TOKENS.read_text(encoding="utf-8"))
    iconos = json.loads(ICONOS.read_text(encoding="utf-8"))
    salida = {
        KOTLIN: kotlin(datos),
        RES / "values" / "fondo.xml": fondo(datos, "light"),
        RES / "values-night" / "fondo.xml": fondo(datos, "dark"),
    }
    for nombre, interior in iconos.items():
        salida[glifo(nombre)] = vector(nombre, interior)
    return salida


def leer(ruta: Path) -> str | None:
    if not ruta.is_file():
        return None
    return ruta.read_text(encoding="utf-8").replace("\r\n", "\n")


def main(argv: list[str]) -> int:
    comprobar = "--comprobar" in argv[1:]
    otros = [a for a in argv[1:] if a != "--comprobar"]
    if otros:
        print(f"No conozco {' '.join(otros)}. Uso: python herramientas/diseno.py [--comprobar]", file=sys.stderr)
        return 2
    esperados = ficheros()
    # Un glifo que ya no está en iconos.json sobra: se quita (o se dice).
    sobran = [p for p in sorted(DRAWABLE.glob(f"{PREFIJO_GLIFO}*.xml")) if p not in esperados]
    distintos = [p for p, texto in esperados.items() if leer(p) != texto]
    if comprobar:
        for p in distintos:
            print(f"No está al día: {p.relative_to(RAIZ).as_posix()}", file=sys.stderr)
        for p in sobran:
            print(f"Sobra (no sale de app/diseno/): {p.relative_to(RAIZ).as_posix()}", file=sys.stderr)
        if distintos or sobran:
            print("Ejecuta `python herramientas/diseno.py`.", file=sys.stderr)
            return 1
        return 0
    for p in distintos:
        p.parent.mkdir(parents=True, exist_ok=True)
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(esperados[p])
        print(f"Escrito: {p.relative_to(RAIZ).as_posix()}")
    for p in sobran:
        p.unlink()
        print(f"Quitado: {p.relative_to(RAIZ).as_posix()}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
