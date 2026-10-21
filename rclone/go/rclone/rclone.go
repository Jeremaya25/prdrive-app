// Package rclone es rclone como biblioteca para la app de prdrive.
//
// El .aar de serie (librclone/gomobile) solo importa backend/all y lib/plugin:
// ni cmd/bisync, ni fs/sync, ni fs/operations, así que en él "sync/bisync" no
// está registrado (los métodos rc se registran en el init() de su paquete).
// Este paquete reexporta librclone y añade lo que falta.
//
// Tres cosas que no hace librclone y aquí sí:
//
//   - Apuntar rclone a su config ANTES de arrancar. librclone.Initialize()
//     llama a configfile.Install(), que lee el rclone.conf del sitio de
//     siempre; el de la app está en el volumen.
//   - Poner el directorio de trabajo. El rclone.conf de la app lleva las rutas
//     de la clave relativas (key_file = keys/...), igual que un dispositivo de
//     escritorio, y rclone las resuelve contra el cwd del proceso. En Android el
//     cwd de una app es "/", y desde Java no se puede cambiar: desde Go sí.
//   - La carpeta temporal. os.TempDir() en Android es /data/local/tmp, donde
//     una app no puede escribir; rclone la usa (por ejemplo, para medir la
//     precisión de las fechas del backend local).
//
// Las firmas cumplen las reglas de gobind: solo string, int y structs con
// campos exportados de esos tipos.
package rclone

import (
	"os"
	"path/filepath"
	"sync"

	"github.com/rclone/rclone/fs"
	"github.com/rclone/rclone/fs/config"
	"github.com/rclone/rclone/librclone/librclone"

	_ "github.com/rclone/rclone/cmd/bisync"    // registra sync/bisync
	_ "github.com/rclone/rclone/fs/operations" // operations/*: stat, copyfile, about...
	_ "github.com/rclone/rclone/fs/rc/jobs"    // job/status, job/stop (_async)
	_ "github.com/rclone/rclone/fs/sync"       // sync/copy, sync/sync

	_ "golang.org/x/mobile/event/key" // para que go.mod lleve golang.org/x/mobile, que gomobile bind necesita
)

var (
	una     sync.Once
	errorDe string
)

// Iniciar deja rclone listo para recibir llamadas. Solo hace algo la primera
// vez: rclone no se puede reiniciar dentro del mismo proceso.
//
//   - conf: la ruta absoluta del rclone.conf del volumen.
//   - trabajo: el directorio contra el que rclone resuelve las rutas relativas
//     del rclone.conf (la carpeta del programa del volumen, .prdrive/).
//   - temporal: una carpeta en la que la app puede escribir.
//
// Devuelve "" si ha ido bien, o el motivo.
func Iniciar(conf, trabajo, temporal string) string {
	una.Do(func() {
		errorDe = iniciar(conf, trabajo, temporal)
	})
	return errorDe
}

func iniciar(conf, trabajo, temporal string) string {
	if err := os.MkdirAll(temporal, 0o700); err != nil {
		return "no se puede crear la carpeta temporal: " + err.Error()
	}
	for clave, valor := range map[string]string{
		"TMPDIR":          temporal,
		"HOME":            temporal,
		"XDG_CACHE_HOME":  filepath.Join(temporal, "cache"),
		"XDG_CONFIG_HOME": filepath.Join(temporal, "config"),
	} {
		if err := os.Setenv(clave, valor); err != nil {
			return "no se puede poner " + clave + ": " + err.Error()
		}
	}
	if err := os.Chdir(trabajo); err != nil {
		return "no se puede entrar en " + trabajo + ": " + err.Error()
	}
	if err := config.SetConfigPath(conf); err != nil {
		return "no se puede usar el rclone.conf " + conf + ": " + err.Error()
	}
	librclone.Initialize()
	return ""
}

// Resultado es lo que devuelve una llamada: el JSON de salida y el código
// HTTP con el que la contestaría el servidor rc (200 es que ha ido bien).
type Resultado struct {
	Salida string
	Estado int
}

// RPC llama a un método rc de rclone con sus parámetros en JSON.
//
// Es librclone.RPC tal cual. Para una pasada larga se pide con "_async": true
// y se sigue con "job/status": así el resultado del trabajo conserva el log
// de la pasada aunque falle (la llamada síncrona devuelve solo el error).
func RPC(metodo, entrada string) *Resultado {
	salida, estado := librclone.RPC(metodo, entrada)
	return &Resultado{Salida: salida, Estado: estado}
}

// Version es la versión de rclone que lleva dentro.
func Version() string {
	return fs.Version
}
