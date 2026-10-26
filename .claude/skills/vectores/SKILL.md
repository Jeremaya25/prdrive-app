---
name: vectores
description: Usar cuando se toca algo de engine/ que refleja a prdrive (Python) o a rclone, cuando se añade o cambia un caso en herramientas/vectores*.py o en rclone/go/oraculo, cuando un test del motor falla contra vectores.json u oraculo_rclone.json, o cuando prdrive ha cambiado y el motor tiene que ponerse al día.
---

# Regenerar los valores esperados

Los tests del motor no llevan ni un valor esperado escrito a mano: leen
`engine/src/test/resources/vectores.json` (lo que contesta prdrive) y
`oraculo_rclone.json` (lo que contesta el código de rclone). Si el motor cambia
lo que refleja, primero cambia el generador y luego regenera. Los dos JSON no se
editan: un hook lo impide (AGENTS.md, «Los valores esperados no se escriben a
mano»).

| Fichero | Lo escribe | Necesita |
|---|---|---|
| `vectores.json` | `herramientas/vectores.py` (y sus `vectores_*.py`) | el checkout de prdrive |
| `oraculo_rclone.json` | `rclone/go/oraculo` | el Go del `toolchain` de `rclone/go/go.mod` |

## Pasos

1. **Dónde está prdrive.** `$PRDRIVE_DIR` si existe; si no, la carpeta `prdrive`
   junto a la copia principal. Desde un worktree, `../prdrive` no existe:

   ```bash
   principal="$(git worktree list --porcelain | sed -n '1s/^worktree //p')"
   prdrive="${PRDRIVE_DIR:-$(dirname "$principal")/prdrive}"
   test -f "$prdrive/common/model.py"
   ```

2. **Contra qué commit.** Compara `git -C "$prdrive" rev-parse HEAD` con
   `prdrive.commit` de `vectores.json`, y mira `git -C "$prdrive" status --short`.
   Si el commit es otro o hay cambios sin guardar, regenerar trae también lo que
   haya movido prdrive: dilo antes de seguir.

3. **Regenerar:** `python herramientas/vectores.py "$prdrive"`.

4. **El oráculo**, solo si el cambio toca algo donde el motor sigue a rclone (el
   nombre de sesión, la raíz de un remote `local`, los tipos y letras de
   `options/info`):

   ```bash
   cd rclone/go && go run ./oraculo ../../engine/src/test/resources/oraculo_rclone.json
   ```

   Sin `go` en el PATH, en Windows suele estar en `$USERPROFILE/sdk/go<versión>/bin`
   (la del `toolchain`). Si no hay Go, dilo y deja el oráculo como está.

5. **Los tests:** `./gradlew :engine:test` (una clase:
   `--tests 'prdrive.engine.BisyncTest'`). Si falla, el informe está en
   `engine/build/reports/tests/test/index.html`.

6. **Qué ha cambiado:** `git diff --stat engine/src/test/resources/`. Explica
   qué casos se han movido y por qué.

## Errores comunes

- **`git status` marca el JSON y `git diff` no enseña nada:** solo son finales de
  línea (el generador escribe LF y la copia tiene `core.autocrlf`). No hay
  cambio: `git checkout -- <fichero>`.
- **`ModuleNotFoundError: vectores_paso3`:** se lanzó con `python -I`, que quita
  la carpeta del script del `sys.path`. Sin `-I`.
- **Escribir en el test el número que sale:** el test diría lo que creíste, no lo
  que hace prdrive. El caso va al generador.
- **«Unable to establish loopback connection»** al arrancar Gradle en Windows:
  falta el `JAVA_TOOL_OPTIONS` de AGENTS.md, «Órdenes» (va en
  `.claude/settings.local.json`).
- **Cambiaste una regex o cómo se escriben números:** los vectores también se
  pasan en el teléfono (`MotorEnAndroidTest`). Usa la skill `emulador`.
