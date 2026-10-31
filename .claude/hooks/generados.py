"""
generados.py — Hook PreToolUse de Claude Code: no deja editar a mano lo que se
genera.

Los valores esperados del motor salen de prdrive y de rclone, no de quien
escribe el test (AGENTS.md, «Los valores esperados no se escriben a mano»), el
wrapper de Gradle se cambia con su tarea porque fija la suma SHA-256 de la
distribución (AGENTS.md, «Órdenes»), los colores y los glifos salen del sistema
de diseño (`herramientas/diseno.py`), y la clave de la firma no entra nunca en el
repositorio (AGENTS.md, «Versiones y releases»).

Lee de la entrada estándar el JSON del hook y, si la herramienta va a escribir
uno de esos ficheros, contesta con `permissionDecision: deny` y el porqué. Solo
mira Edit, Write, MultiEdit y NotebookEdit: lo que escribe un generador por la
línea de órdenes (que es como deben cambiar) no pasa por aquí.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import PurePosixPath

VECTORES = (
    "Se genera desde el prdrive de verdad: añade el caso al generador "
    "(herramientas/vectores*.py) y regenera con "
    "`python herramientas/vectores.py <ruta de prdrive>`. AGENTS.md, «Los valores "
    "esperados no se escriben a mano»."
)
ORACULO = (
    "Lo escribe el código de rclone: "
    "`cd rclone/go && go run ./oraculo ../../engine/src/test/resources/oraculo_rclone.json`. "
    "AGENTS.md, «Los valores esperados no se escriben a mano»."
)
WRAPPER = (
    "El wrapper fija la suma SHA-256 de la distribución: se cambia con "
    "`./gradlew :wrapper --gradle-version=<versión> --distribution-type=bin "
    "--gradle-distribution-sha256-sum=<la suma>` y otra vez `./gradlew :wrapper`. "
    "AGENTS.md, «Órdenes»."
)
DISENO = (
    "Se genera desde el sistema de diseño: cambia `app/diseno/` y ejecuta "
    "`python herramientas/diseno.py`."
)
CLAVE = (
    "La clave de la firma vive en el entorno `release` y en una copia fuera del "
    "repositorio, nunca aquí. AGENTS.md, «Versiones y releases»."
)

# Rutas relativas a la raíz del proyecto, con `/`.
EXACTOS = {
    "engine/src/test/resources/vectores.json": VECTORES,
    "engine/src/test/resources/oraculo_rclone.json": ORACULO,
    "gradle/wrapper/gradle-wrapper.properties": WRAPPER,
    "gradle/wrapper/gradle-wrapper.jar": WRAPPER,
    "gradlew": WRAPPER,
    "gradlew.bat": WRAPPER,
    "app/src/main/kotlin/prdrive/app/tema/Tokens.kt": DISENO,
    "app/src/main/res/values/fondo.xml": DISENO,
    "app/src/main/res/values-night/fondo.xml": DISENO,
}
# Lo que empieza así se genera: los glifos del sistema de diseño.
PREFIJOS = {
    "app/src/main/res/drawable/pd_": DISENO,
}
EXTENSIONES_CLAVE = (".p12", ".jks", ".keystore")


def relativa(ruta: str, raiz: str) -> str | None:
    """La ruta relativa a la raíz, con `/`, o None si queda fuera."""
    absoluta = os.path.abspath(os.path.join(raiz, ruta))
    try:
        rel = os.path.relpath(absoluta, raiz)
    except ValueError:  # otra unidad, en Windows
        return None
    rel = rel.replace(os.sep, "/")
    if rel == ".." or rel.startswith("../"):
        return None
    return rel


def motivo(rel: str) -> str | None:
    # Windows no distingue mayúsculas en las rutas: se compara en minúsculas.
    clave = rel.lower() if os.name == "nt" else rel
    for exacto, porque in EXACTOS.items():
        if clave == (exacto.lower() if os.name == "nt" else exacto):
            return porque
    for prefijo, porque in PREFIJOS.items():
        if clave.startswith(prefijo.lower() if os.name == "nt" else prefijo):
            return porque
    if PurePosixPath(clave).suffix.lower() in EXTENSIONES_CLAVE:
        return CLAVE
    return None


def main() -> int:
    entrada = json.loads(sys.stdin.buffer.read().decode("utf-8") or "{}")
    datos = entrada.get("tool_input") or {}
    ruta = datos.get("file_path") or datos.get("notebook_path")
    if not ruta:
        return 0
    raiz = os.environ.get("CLAUDE_PROJECT_DIR") or entrada.get("cwd") or os.getcwd()
    rel = relativa(ruta, raiz)
    if rel is None:
        return 0
    porque = motivo(rel)
    if porque is None:
        return 0
    respuesta = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": f"{rel} no se edita a mano. {porque}",
        }
    }
    # ensure_ascii (por defecto): la consola de Windows no siempre es UTF-8.
    sys.stdout.write(json.dumps(respuesta))
    return 0


if __name__ == "__main__":
    sys.exit(main())
