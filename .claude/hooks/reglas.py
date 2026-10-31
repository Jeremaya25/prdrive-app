"""
reglas.py — Hook PostToolUse de Claude Code: avisa en cuanto se escribe algo que
cruza una regla de AGENTS.md que el compilador no ve.

En `engine/src/main` (AGENTS.md, «Reglas que no se cruzan» y «Ojo con Android»):

- nada de Android: ni `import android…`/`androidx…` ni nombres `android.…`;
- ni `\\d`, `\\w`, `\\s` (ni sus mayúsculas) ni `(?U)` en una expresión regular:
  en el teléfono son las de ICU, Unicode, y `(?U)` no existe;
- ni `String.format` ni `"…".format(…)`: usa las cifras del idioma del teléfono.
- nada de la biblioteca de Java posterior a Java 8 que Android no tenga en la
  API 26 (`BigInteger.TWO`, `readAllBytes`, `List.of`…): compila contra el JDK
  17 y la clase no carga en el teléfono.

En las pantallas (`app/src/main/…/ui/`, AGENTS.md, «Convenios»): ningún color
escrito, todo de `MaterialTheme.colorScheme` o de `Paleta.actual`.

Solo mira las líneas que la herramienta acaba de introducir: en un Edit, las de
`new_string` que no estaban en `old_string`; en un Write, las que no están en la
versión de HEAD. Lo que ya estaba (y se dejó así a propósito) no vuelve a salir.
Se saltan los comentarios, que hablan de estas cosas para explicarlas.

Si encuentra algo, contesta `decision: block` con el porqué: el fichero ya está
escrito, pero Claude lo lee y lo arregla (o explica por qué no es lo que parece).
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys

ANDROID = re.compile(r"^\s*import\s+androidx?\.|\bandroidx?\.[a-z]\w*\.")
FORMAT = re.compile(r"\bString\.format\s*\(|\"\s*\.format\s*\(")
# Lo de la biblioteca de Java posterior a Java 8 que Android no tiene en la API 26
# (minSdk): el motor compila contra el JDK 17, que sí lo tiene, y el fallo solo
# sale en el teléfono (`BigInteger.TWO` tumbaba toda pasada en Android 8 a 12).
JAVA_NUEVO = re.compile(
    r"\bBigInteger\.(TWO|sqrt)\b|\.readAllBytes\s*\(|\.transferTo\s*\(|\bFiles\.(readString|writeString)\b"
    r"|\bPath\.of\s*\(|\b(List|Set|Map)\.(of|copyOf|entry)\s*\(|\.orElseThrow\s*\(\s*\)"
    r"|\bCharacter\.toString\s*\(\s*[a-z]|\bnullInputStream\b|\brequireNonNullElse\b|\bto(Seconds|Millis|Nanos)Part\b"
)
CLASES = re.compile(r"\\{1,2}[dDwWsS]|\(\?[a-zA-Z]*U[a-zA-Z]*\)")
COLOR = re.compile(
    r"\bColor\(\s*(0x|[0-9]|red\s*=|alpha\s*=)"
    r"|\bColor\.(Black|White|Red|Green|Blue|Yellow|Cyan|Magenta|Gray|DarkGray|LightGray)\b"
)

REGLAS_MOTOR = [
    (
        ANDROID,
        "engine/ no sabe que existe Android (AGENTS.md, «Reglas que no se cruzan»): "
        "lo de Android va en app/.",
    ),
    (
        CLASES,
        "En el teléfono las regex son las de ICU: \\d, \\w y \\s son Unicode y (?U) no "
        "existe (AGENTS.md, «Ojo con Android»). Escribe la clase a mano: [0-9], "
        "\\p{L}\\p{N}_, [\\t\\n\\f\\r ]. Si no es una regex, dilo y sigue.",
    ),
    (
        FORMAT,
        "String.format usa las cifras del idioma del teléfono (AGENTS.md, «Ojo con "
        "Android»): nada que acabe en un fichero o en un mensaje comparado pasa por él.",
    ),
    (
        JAVA_NUEVO,
        "Eso es de la biblioteca de Java posterior a Java 8, y Android no lo tiene en "
        "la API 26 (minSdk): compila, pero en el teléfono la clase no carga (AGENTS.md, "
        "«Ojo con Android»). Usa lo de Kotlin o lo de Java 8 (BigInteger.valueOf(2), "
        "readBytes(), listOf…).",
    ),
]
REGLAS_PANTALLA = [
    (
        COLOR,
        "En las pantallas no se escribe ningún color: todo sale de "
        "MaterialTheme.colorScheme o de Paleta.actual (AGENTS.md, «Convenios»).",
    ),
]


def relativa(ruta: str, raiz: str) -> str | None:
    absoluta = os.path.abspath(os.path.join(raiz, ruta))
    try:
        rel = os.path.relpath(absoluta, raiz)
    except ValueError:  # otra unidad, en Windows
        return None
    rel = rel.replace(os.sep, "/")
    if rel == ".." or rel.startswith("../"):
        return None
    return rel


def reglas_de(rel: str) -> list:
    if not rel.endswith(".kt"):
        return []
    if rel.startswith("engine/src/main/"):
        return REGLAS_MOTOR
    if rel.startswith("app/src/main/") and "/ui/" in rel:
        return REGLAS_PANTALLA
    return []


def en_head(rel: str, raiz: str) -> str:
    """El fichero en HEAD, o "" si no está (nuevo, o fuera de git)."""
    try:
        r = subprocess.run(
            ["git", "show", f"HEAD:{rel}"],
            cwd=raiz,
            capture_output=True,
            timeout=10,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    return r.stdout.decode("utf-8", "replace") if r.returncode == 0 else ""


def nuevas(antes: str, despues: str) -> list[str]:
    """Las líneas de `despues` que no estaban en `antes` (sin mirar la sangría)."""
    vistas = {linea.strip() for linea in antes.splitlines()}
    return [linea for linea in despues.splitlines() if linea.strip() not in vistas]


def sin_comentario(linea: str) -> str:
    """El código de la línea: nada si es de un comentario, y sin el `//` del final.

    Un `//` es comentario si delante tiene un número par de comillas (así no se
    corta "http://…" ni un `//` dentro de un texto). No es un analizador de
    Kotlin, pero basta para lo que se busca aquí.
    """
    limpia = linea.strip()
    if limpia.startswith(("//", "*", "/*")):
        return ""
    desde = 0
    while True:
        i = linea.find("//", desde)
        if i < 0:
            return linea
        if linea.count('"', 0, i) % 2 == 0:
            return linea[:i]
        desde = i + 2


def lineas_introducidas(herramienta: str, datos: dict, rel: str, raiz: str) -> list[str]:
    if herramienta == "Edit":
        return nuevas(datos.get("old_string") or "", datos.get("new_string") or "")
    if herramienta == "MultiEdit":
        salida: list[str] = []
        for e in datos.get("edits") or []:
            salida += nuevas(e.get("old_string") or "", e.get("new_string") or "")
        return salida
    if herramienta == "Write":
        return nuevas(en_head(rel, raiz), datos.get("content") or "")
    return []


def main() -> int:
    entrada = json.loads(sys.stdin.buffer.read().decode("utf-8") or "{}")
    herramienta = entrada.get("tool_name") or ""
    datos = entrada.get("tool_input") or {}
    ruta = datos.get("file_path")
    if not ruta:
        return 0
    raiz = os.environ.get("CLAUDE_PROJECT_DIR") or entrada.get("cwd") or os.getcwd()
    rel = relativa(ruta, raiz)
    if rel is None:
        return 0
    reglas = reglas_de(rel)
    if not reglas:
        return 0

    hallazgos: list[str] = []
    for linea in lineas_introducidas(herramienta, datos, rel, raiz):
        codigo = sin_comentario(linea)
        if not codigo.strip():
            continue
        for patron, porque in reglas:
            if patron.search(codigo):
                hallazgos.append(f"  {linea.strip()}\n    -> {porque}")
    if not hallazgos:
        return 0

    respuesta = {
        "decision": "block",
        "reason": f"{rel} cruza una regla de AGENTS.md:\n" + "\n".join(hallazgos),
    }
    # ensure_ascii (por defecto): la consola de Windows no siempre es UTF-8.
    sys.stdout.write(json.dumps(respuesta))
    return 0


if __name__ == "__main__":
    sys.exit(main())
