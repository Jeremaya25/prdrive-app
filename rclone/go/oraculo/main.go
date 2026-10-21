// Oráculo: lo que dice el rclone fijado en go.mod de los casos en que el motor
// se separa de prdrive a propósito (porque ahí manda rclone).
//
// Los vectores de herramientas/vectores.py salen de prdrive, así que solo
// cubren lo que el motor hace igual que prdrive. Donde el motor sigue a rclone
// y no a prdrive —el `\s` de CanonicalPath y el Root() del backend local—, el
// valor esperado sale de aquí, del código de rclone de verdad: se llama a sus
// funciones, no se copian.
//
// Se ejecuta en el PC (el de Windows incluido), así que no se puede abrir un
// backend local como lo abriría Android: se compone lo que hacen
// cleanRootPath y Root() (backend/local/local.go) con el codificador de
// Android (encoder.OS es encoder.Base fuera de Windows y macOS,
// lib/encoder/os_other.go) y path.Clean, que es el filepath.Clean de POSIX.
//
//	cd rclone/go && go run ./oraculo ../../engine/src/test/resources/oraculo_rclone.json
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path"
	"runtime/debug"
	"strings"

	"github.com/rclone/rclone/cmd/bisync/bilib"
	"github.com/rclone/rclone/lib/encoder"
)

// android es encoder.OS en Android (lib/encoder/os_other.go).
const android = encoder.Base

// raizLocal es el Root() de un backend local abierto en Android en `ruta`.
func raizLocal(ruta string) string {
	// cleanRootPath, rama no-Windows: cada tramo (menos `.` y `..`) pasa por
	// FromStandardName, y luego filepath.Clean.
	partes := strings.Split(ruta, "/")
	for i, p := range partes {
		if p == "." || p == ".." {
			continue
		}
		partes[i] = android.FromStandardName(p)
	}
	limpia := path.Clean(strings.Join(partes, "/"))
	// Root(): ToStandardPath de lo que ha quedado.
	return android.ToStandardPath(limpia)
}

// Las entradas sí se escriben a mano: son las preguntas, no las respuestas.
var rutas = []string{
	"/data/user/0/prdrive.app/files/remoto-de-prueba/notas",
	"/r/AC／DC", "/r/．", "/r/．．", "/r/‛．", "/r/‛．‛．", "/r/‛‛．",
	"/r/a␀b", "/r/a‛‛b", "/r/a␋b", "/r/a␡b", "/r/a‛／b", "/r/it‛s",
	"/r/a\x0bb", "/r/a\x7fb", "/r/a\x00b", "/r/a\x1fb", "/r/a␟b", "/r/a␠b",
	"/r/a‛", "/r/‛", "/r/‛‛", "/r/😀／x", "/r/‛😀", "/r/a　b\x0bc d",
	"a//b/./c/", "../x", "x/../y", ".", "..", "/", "/a/b/../../..",
	"//servidor/x", "sync-data/notas", "sync-data/．oculta/x",
}

var remotes = []string{
	"disp:sync-data/notas/",
	"nas:/srv/datos/notas/",
	"disp:sync-data/a　b\x0bc d/",
	"nas:x\x85y z w　v/",
	"nas:a\tb\nc\fd\re f\\g/h:i?j*k/",
	"//nas:x//",
	"\\\\nas\\x\\",
	"nas{AbC12}:x/",
	"nas:x{1}y{2}/",
	"nas:x}y{z/",
}

type salida struct {
	Rclone     string              `json:"rclone"`
	Explica    string              `json:"explica"`
	RaizLocal  []map[string]string `json:"raiz_local"`
	Canonical  []map[string]string `json:"canonical"`
	SessionAll []map[string]string `json:"session_name"`
}

func versionRclone() string {
	if info, ok := debug.ReadBuildInfo(); ok {
		for _, d := range info.Deps {
			if d.Path == "github.com/rclone/rclone" {
				return d.Version
			}
		}
	}
	return "?"
}

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "uso: go run ./oraculo <fichero.json>")
		os.Exit(2)
	}
	s := salida{
		Rclone:  versionRclone(),
		Explica: "Generado por rclone/go/oraculo desde el código de rclone. No editar a mano.",
	}
	for _, r := range rutas {
		s.RaizLocal = append(s.RaizLocal, map[string]string{"ruta": r, "raiz": raizLocal(r)})
	}
	for _, r := range remotes {
		s.Canonical = append(s.Canonical, map[string]string{
			"remote":    r,
			"canonical": bilib.CanonicalPath(r),
			"sin_hex":   bilib.StripHexString(r),
		})
	}
	for i, a := range remotes {
		b := remotes[(i+1)%len(remotes)]
		// SessionName (bilib/canonical.go) con FsPath ya calculado: es la
		// forma en que el motor lo llama.
		s.SessionAll = append(s.SessionAll, map[string]string{
			"path1":   a,
			"path2":   b,
			"session": bilib.StripHexString(bilib.CanonicalPath(a)) + ".." + bilib.StripHexString(bilib.CanonicalPath(b)),
		})
	}
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", " ")
	if err := enc.Encode(s); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if err := os.WriteFile(os.Args[1], buf.Bytes(), 0o644); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
