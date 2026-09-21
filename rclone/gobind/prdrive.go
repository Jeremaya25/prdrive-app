// Package prdrive es lo que `gomobile bind` empaqueta en el .aar: rclone como
// biblioteca, con los métodos RPC que esta app necesita registrados.
//
// Existe porque el .aar de serie no vale. `librclone/gomobile/gomobile.go`
// importa solo `backend/all` y `lib/plugin`, y los métodos rc se registran en
// el `init()` de su paquete: sin importar `cmd/bisync`, el método
// `sync/bisync` simplemente no está en el mapa y la llamada devuelve 404.
// Lo que falta son tres líneas de imports, y están en este fichero.
//
// Las funciones son las mismas que las del paquete de rclone, con los mismos
// nombres a propósito: así lo que se lea sobre `RcloneInitialize` /
// `RcloneRPC` en la documentación de rclone vale aquí tal cual, y el
// envoltorio de Kotlin no tiene que traducir nada. Las firmas están sujetas a
// las reglas de gobind (solo tipos que sabe cruzar; de ahí que el resultado
// viaje en un struct y no en dos valores de retorno):
// https://pkg.go.dev/golang.org/x/mobile/cmd/gobind#hdr-Type_restrictions
package prdrive

import (
	"context"
	"log/slog"
	"strings"
	"sync"

	"github.com/rclone/rclone/fs"
	"github.com/rclone/rclone/fs/config"
	fslog "github.com/rclone/rclone/fs/log"
	"github.com/rclone/rclone/librclone/librclone"

	// Cada import registra en el mapa del rc los métodos de su paquete. Sin
	// esto la biblioteca arranca y responde a `rc/noop`, pero no sabe
	// sincronizar.
	_ "github.com/rclone/rclone/cmd/bisync"    // sync/bisync
	_ "github.com/rclone/rclone/fs/operations" // operations/* (about, mkdir, list…)
	_ "github.com/rclone/rclone/fs/sync"       // sync/copy, sync/move, sync/sync

	// Los backends van en backends_curados.go / backends_todos.go, que se
	// eligen con la etiqueta de compilación `rclone_todos`.
	_ "github.com/rclone/rclone/lib/plugin"
)

// RcloneSetConfigPath dice de dónde leer el rclone.conf, y hay que llamarla
// ANTES de RcloneInitialize.
//
// No es una preferencia, es un orden obligatorio, y equivocarse no da error:
// `librclone.Initialize()` llama a `configfile.Install()`, que es
// `config.SetData(&Storage{})`, y esa función se va de vacío si el path está
// sin fijar —«If no config file, use in-memory config», `fs/config/config.go`,
// `SetData`—. Al revés del orden bueno, rclone arranca con una configuración
// en memoria y vacía, y el fallo no sale aquí: sale más tarde, y dice
// «unknown remote». En Android además no hay HOME donde buscar el sitio por
// defecto, así que sin esta llamada no hay configuración en absoluto.
//
// Fijarlo después SÍ tiene un uso, y es bueno: `Storage._check()` compara la
// fecha y el tamaño del fichero en cada lectura y lo recarga si cambió, así
// que la app puede reescribir el `rclone.conf` —añadir un upstream a `[disp]`
// al crear una pareja— y la siguiente llamada ya lo ve, sin reiniciar nada.
func RcloneSetConfigPath(path string) error {
	return config.SetConfigPath(path)
}

// RcloneConfigPath devuelve el rclone.conf que está en uso. Para diagnóstico:
// la cadena vacía significa «configuración en memoria», o sea que algo se
// llamó en el orden equivocado.
func RcloneConfigPath() string {
	return config.GetConfigPath()
}

// RcloneInitialize arranca rclone como biblioteca. Después de
// RcloneSetConfigPath, no antes.
//
// Y engancha el sumidero del log, que es de donde sale el log de una pasada:
// ver [RcloneLogTexto].
func RcloneInitialize() {
	librclone.Initialize()
	unaVez.Do(engancharElLog)

	// Nada de colores ANSI en el log. Detrás de esto no hay una terminal: el
	// log va a una ventana de la app, y ahí un `\x1b[31m` es basura en
	// pantalla.
	//
	// Y hay que apagarlo AQUÍ, en la configuración global, porque por la
	// llamada no se puede: `TerminalColorMode` es un `fs.Enum`, y aunque su
	// etiqueta sea `config:"color"`, el `_config` del RPC lo ignora sin
	// quejarse —un `transfers` inválido da 400, un `color` inválido pasa de
	// largo—. Y una vez encendido no se apaga: bisync guarda la decisión en
	// su `Colors`, una global de paquete que solo se pone a true
	// (`cmd/bisync/operations.go`). O sea que la primera pasada del proceso
	// decide, y esta línea es la que llega antes. Comprobado en rclone/spike.
	fs.GetConfig(context.Background()).TerminalColorMode = fs.TerminalColorModeNever
}

// ---------------------------------------------------------------------------
// El log de una pasada
//
// El RPC NO devuelve el log de una pasada fallida, y esto no es un detalle:
//
//   - `librclone.RPC` descarta el `out` de la llamada cuando esta devuelve
//     error (`writeError`, librclone/librclone/librclone.go), así que el campo
//     `output` de `sync/bisync` se pierde justo cuando hace falta. Con
//     `_async` sí sobrevive, porque `job.finish()` asigna `job.Output` antes
//     de mirar el error (fs/rc/jobs/job.go).
//   - Pero aun así ese `output` NO trae el diagnóstico. Es lo que bisync
//     imprime por su cuenta (sus «Tip: here are the filenames…»), y el error
//     del job es un lacónico «bisync aborted». La frase que explica qué pasó
//     —la que `sync.py` traduce en KNOWN_ERRORS— se va al log de rclone.
//
// En el escritorio eso es `--log-file` y después leer el fichero. Aquí no hace
// falta tocar el disco: `fs/log.Handler` acepta sumideros adicionales
// (`AddOutput`, fs/log/slog.go), así que el log se acumula en memoria y la app
// lo recoge cuando la pasada acaba. Además de ahorrar escrituras en el
// dispositivo —que es justo lo que `dispose_log()` cuida en prdrive—, evita
// tener que limpiar ficheros de log.
//
// Comprobado en rclone/spike.
// ---------------------------------------------------------------------------

var (
	unaVez sync.Once
	logMu  sync.Mutex
	logBuf strings.Builder
)

func engancharElLog() {
	fslog.Handler.AddOutput(false, func(_ slog.Level, texto string) {
		logMu.Lock()
		defer logMu.Unlock()
		logBuf.WriteString(texto)
		if !strings.HasSuffix(texto, "\n") {
			logBuf.WriteByte('\n')
		}
	})
}

// RcloneLogReiniciar vacía el log acumulado. Se llama antes de cada pasada: el
// log que se le enseña al usuario es el de ESA pareja, no el de la sesión.
func RcloneLogReiniciar() {
	logMu.Lock()
	defer logMu.Unlock()
	logBuf.Reset()
}

// RcloneLogTexto devuelve el log acumulado desde la última llamada a
// [RcloneLogReiniciar].
func RcloneLogTexto() string {
	logMu.Lock()
	defer logMu.Unlock()
	return logBuf.String()
}

// RcloneLogNivel fija hasta qué nivel se registra, con los mismos nombres que
// el `--log-level` de rclone: DEBUG, INFO, NOTICE, ERROR. El `verbose = true`
// de las BASE_FLAGS de prdrive es INFO.
//
// Va aquí y no en el `_config` de cada llamada porque por ahí no se puede: los
// `fs.Infof` y compañía se guardan contra `GetConfig(context.TODO())`
// (`fs/log.go`), o sea la configuración GLOBAL, no la del contexto de la
// llamada. Y hay que mover las dos cosas: el nivel global —que es el que
// decide si el mensaje se llega a emitir— y el del handler, que decide si se
// escribe. Con una sola no se ve nada, y no avisa.
//
// Que sea global no estorba: las parejas se ejecutan de una en una de todas
// formas, porque `bilib.CaptureOutput` redirige la salida del proceso entero.
func RcloneLogNivel(nombre string) error {
	var nivel fs.LogLevel
	if err := nivel.Set(nombre); err != nil {
		return err
	}
	fs.GetConfig(context.Background()).LogLevel = nivel
	fslog.Handler.SetLevel(fs.LogLevelToSlog(nivel))
	return nil
}

// RcloneFinalize cierra la biblioteca.
func RcloneFinalize() {
	librclone.Finalize()
}

// RcloneRPCResult es lo que devuelve una llamada: la salida como JSON
// serializado y un estado HTTP (200 = bien, cualquier otra cosa = fallo).
type RcloneRPCResult struct {
	Output string
	Status int
}

// RcloneRPC llama a un método del rc con su entrada en JSON.
//
// Los parámetros genéricos `_async`, `_group`, `_config` y `_filter` funcionan
// aquí igual que en el rc de verdad (los maneja `jobs.NewJob`, por donde pasa
// toda llamada de librclone), y son la vía para los flags de `BASE_FLAGS` que
// `sync/bisync` no acepta como parámetro propio.
func RcloneRPC(method string, input string) *RcloneRPCResult {
	output, status := librclone.RPC(method, input)
	return &RcloneRPCResult{Output: output, Status: status}
}
