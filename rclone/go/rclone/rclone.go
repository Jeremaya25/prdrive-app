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
	"bytes"
	"log/slog"
	"os"
	"path/filepath"
	"sync"

	"github.com/rclone/rclone/fs"
	"github.com/rclone/rclone/fs/config"
	"github.com/rclone/rclone/fs/log"
	"github.com/rclone/rclone/librclone/librclone"

	_ "github.com/rclone/rclone/cmd/bisync"    // registra sync/bisync
	_ "github.com/rclone/rclone/fs/operations" // operations/*: stat, copyfile, about...
	_ "github.com/rclone/rclone/fs/rc/jobs"    // job/status, job/stop (_async)
	_ "github.com/rclone/rclone/fs/sync"       // sync/copy, sync/sync

	_ "golang.org/x/mobile/event/key" // para que go.mod lleve golang.org/x/mobile, que gomobile bind necesita
)

var (
	inicioMu sync.Mutex
	// Con qué se inició: rclone no se puede reiniciar dentro del mismo
	// proceso, así que una segunda llamada tiene que pedir lo mismo.
	iniciadoCon *[3]string
)

// Iniciar deja rclone listo para recibir llamadas.
//
//   - conf: la ruta absoluta del rclone.conf del volumen.
//   - trabajo: el directorio contra el que rclone resuelve las rutas relativas
//     del rclone.conf (la carpeta del programa del volumen, .prdrive/).
//   - temporal: una carpeta en la que la app puede escribir y que el sistema no
//     vacía por su cuenta (no la caché de Android).
//
// Se puede llamar antes de cada pasada, y es lo que hay que hacer: vuelve a
// crear la carpeta temporal y a entrar en el directorio de trabajo (el cwd del
// proceso es el inodo, no la ruta: si la carpeta se borra y se crea otra vez,
// las rutas relativas dejarían de encontrarse). rclone en sí solo se arranca
// la primera vez que todo va bien; un fallo no se recuerda, así que se puede
// reintentar. Unas rutas distintas de las del primer arranque son un error:
// rclone seguiría con las de antes.
//
// Devuelve "" si ha ido bien, o el motivo.
func Iniciar(conf, trabajo, temporal string) string {
	inicioMu.Lock()
	defer inicioMu.Unlock()
	pedido := [3]string{conf, trabajo, temporal}
	if iniciadoCon != nil && *iniciadoCon != pedido {
		return "rclone ya está iniciado con " + iniciadoCon[0] + " y " + iniciadoCon[1] +
			": no se reinicia dentro del mismo proceso"
	}
	if err := os.MkdirAll(temporal, 0o700); err != nil {
		return "no se puede crear la carpeta temporal: " + err.Error()
	}
	if err := os.Chdir(trabajo); err != nil {
		return "no se puede entrar en " + trabajo + ": " + err.Error()
	}
	if iniciadoCon != nil {
		return ""
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
	// La carpeta de caché de rclone se calcula al cargar el paquete, antes de
	// que nadie pueda poner HOME (fs/config/config.go), y en Android sale
	// /data/local/tmp/rclone, donde una app no escribe.
	if err := config.SetCacheDir(filepath.Join(temporal, "cache")); err != nil {
		return "no se puede usar la carpeta de caché: " + err.Error()
	}
	if err := config.SetConfigPath(conf); err != nil {
		return "no se puede usar el rclone.conf " + conf + ": " + err.Error()
	}
	librclone.Initialize()
	iniciadoCon = &pedido
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

var (
	capturaMu     sync.Mutex
	captura       *bytes.Buffer
	nivelAnterior slog.Level
)

// Capturar empieza a guardar todo lo que rclone escribe en su log, desde INFO,
// hasta que se llame a SoltarCaptura.
//
// Es lo que hace bilib.CaptureOutput dentro de sync/bisync, para las llamadas
// que no devuelven su salida (sync/copy, sync/sync). La salida del log de
// rclone es una pila (fs/log/slog.go: SetOutput apila, ResetOutput desapila),
// así que una captura de bisync dentro de esta no la pisa. Lo que no aguanta
// son dos capturas a la vez de dos pasadas: las pasadas van de una en una,
// como en sync.py. Si ya había una, la deja como estaba.
func Capturar() {
	capturaMu.Lock()
	defer capturaMu.Unlock()
	if captura != nil {
		return
	}
	buf := &bytes.Buffer{}
	captura = buf
	nivelAnterior = log.Handler.SetLevel(slog.LevelInfo)
	log.Handler.SetOutput(func(level slog.Level, text string) {
		capturaMu.Lock()
		defer capturaMu.Unlock()
		buf.WriteString(text)
	})
}

// SoltarCaptura deja de capturar y devuelve lo capturado ("" si no había
// captura).
func SoltarCaptura() string {
	capturaMu.Lock()
	buf := captura
	captura = nil
	capturaMu.Unlock()
	if buf == nil {
		return ""
	}
	log.Handler.ResetOutput()
	log.Handler.SetLevel(nivelAnterior)
	capturaMu.Lock()
	defer capturaMu.Unlock()
	return buf.String()
}
