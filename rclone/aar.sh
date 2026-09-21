#!/bin/sh
# Construye el .aar y mide lo que pesa cada juego de backends.
#
# Es lo que decidió qué backends entran (ver PLAN.md): `backend/all` son ~50 y
# se lleva la mayor parte del tamaño, así que la pregunta no se contesta
# leyendo, se contesta midiendo las dos.
#
#   ANDROID_HOME=... ANDROID_NDK_HOME=... sh rclone/aar.sh [salida]
#
# Hacen falta las dos variables: el NDK para compilar rclone para Android, y el
# SDK porque `gomobile bind` compila con javac los stubs de Java y necesita un
# android.jar. Con un `platforms;android-35` y `build-tools;35.0.0` basta.
#
# Tres cosas que no son evidentes:
#
#   * `-androidapi 26` no es opcional. gomobile va por defecto a la API 16 y
#     falla con «unsupported API version 16 (not in 21..35)». El 26 es el
#     minSdk del plan, y lo es por `java.nio.file`.
#   * `gomobile bind` exige golang.org/x/mobile en el módulo. Está como
#     directiva `tool` en go.mod (`go get -tool golang.org/x/mobile/cmd/gobind`),
#     que es lo único que sobrevive a un `go mod tidy`.
#   * Se mide arm64 y nada más: es el ABI de prácticamente cualquier móvil de
#     hoy, y lo que se compara es la diferencia entre los dos juegos, que no
#     depende del ABI.
set -e
cd "$(dirname "$0")"
salida="${1:-/tmp}"
mkdir -p "$salida"

: "${ANDROID_NDK_HOME:?hace falta el NDK de Android}"
: "${ANDROID_HOME:?hace falta el SDK de Android (por el android.jar)}"

command -v gomobile >/dev/null || go install golang.org/x/mobile/cmd/gomobile@latest
command -v gobind >/dev/null || go install golang.org/x/mobile/cmd/gobind@latest

for variante in curados todos; do
    etiquetas=""
    [ "$variante" = todos ] && etiquetas="-tags rclone_todos"
    echo "=== $variante ==="
    # shellcheck disable=SC2086
    gomobile bind $etiquetas -target=android/arm64 -androidapi 26 \
        -javapkg com.prdrive.rclone \
        -o "$salida/prdrive-$variante.aar" ./gobind/
done

echo
echo "=== el .aar, para android/arm64 ==="
for variante in curados todos; do
    f="$salida/prdrive-$variante.aar"
    aar=$(awk -v b="$(wc -c <"$f")" 'BEGIN{printf "%.1f", b/1048576}')
    so=$(unzip -l "$f" | awk '/\.so$/{s+=$1} END{printf "%.1f", s/1048576}')
    printf '%-8s  aar %6s MB   .so sin comprimir %6s MB\n' "$variante" "$aar" "$so"
done
