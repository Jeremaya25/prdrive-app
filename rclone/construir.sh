#!/usr/bin/env bash
# construir.sh — Construye rclone/build/rclone.aar, rclone como biblioteca para la app.
#
#   rclone/construir.sh            # la lista corta de backends (la que usa la app)
#   rclone/construir.sh todos      # con backend/all, para medir la diferencia
#
# Necesita Go, gomobile y gobind de la versión de golang.org/x/mobile que fija
# go.mod (desde rclone/go: `go install golang.org/x/mobile/cmd/gomobile
# golang.org/x/mobile/cmd/gobind`), el SDK de Android con un NDK, y un JDK. Lee:
#
#   ANDROID_HOME        el SDK (por defecto, el de Android Studio)
#   ANDROID_NDK_HOME    el NDK (por defecto, el más nuevo del SDK)
#   JAVA_HOME           un JDK 17 o posterior (por defecto, el JBR de Android Studio)
#
# Las arquitecturas: arm64 para los teléfonos y x86_64 para el emulador. La
# versión mínima de Android es la de la app (minSdk 26).
set -euo pipefail

aqui="$(cd "$(dirname "$0")" && pwd)"
variante="${1:-corta}"

if [[ -z "${ANDROID_HOME:-}" ]]; then
    for candidato in "${LOCALAPPDATA:-}/Android/Sdk" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
        [[ -d "$candidato" ]] && export ANDROID_HOME="$candidato" && break
    done
fi
[[ -d "${ANDROID_HOME:-}" ]] || { echo "No encuentro el SDK de Android: pon ANDROID_HOME." >&2; exit 1; }

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    ndk="$(ls -d "$ANDROID_HOME"/ndk/*/ 2>/dev/null | sort -V | tail -1 || true)"
    [[ -n "$ndk" ]] || { echo "No hay ningún NDK en $ANDROID_HOME/ndk: instálalo." >&2; exit 1; }
    export ANDROID_NDK_HOME="${ndk%/}"
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
    for candidato in "/c/Program Files/Android/Android Studio/jbr" "/opt/android-studio/jbr" \
                     "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
        [[ -d "$candidato" ]] && export JAVA_HOME="$candidato" && break
    done
fi
# En Git Bash, `go env GOPATH` y un JAVA_HOME de Windows llevan `C:\…`, y los
# dos puntos partirían el PATH: se pasan a la forma de la shell.
posix() { if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s' "$1"; fi; }
gopath="$(go env GOPATH 2>/dev/null || true)"
export PATH="${JAVA_HOME:+$(posix "$JAVA_HOME")/bin:}${gopath:+$(posix "$gopath")/bin:}$PATH"

etiquetas=""
salida="$aqui/build/rclone.aar"
if [[ "$variante" == "todos" ]]; then
    etiquetas="todos"
    salida="$aqui/build/rclone-todos.aar"
fi
mkdir -p "$aqui/build"

cd "$aqui/go"
version="$(go list -m -f '{{.Version}}' github.com/rclone/rclone)"
echo "rclone $version, $(go version | cut -d' ' -f3), NDK $(basename "$ANDROID_NDK_HOME"), variante $variante"
# -s -w: sin tabla de símbolos ni DWARF, que son la mitad del tamaño y en el
# teléfono no sirven de nada. -trimpath: que las rutas de esta máquina no
# acaben dentro del .aar. -X fs.Version: compilado desde el módulo, rclone se
# llama a sí mismo «v1.75.2-DEV»; los binarios publicados llevan la versión
# así, y es la que se enseña y la que se compara con la que fija prdrive.
gomobile bind \
    -target=android/arm64,android/amd64 \
    -androidapi 26 \
    -javapkg prdrive.lib \
    ${etiquetas:+-tags "$etiquetas"} \
    -trimpath \
    -ldflags "-s -w -X github.com/rclone/rclone/fs.Version=$version" \
    -o "$salida" \
    ./rclone
ls -l "$salida"
