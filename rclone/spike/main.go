// Command spike ejecuta rclone-como-biblioteca de verdad y comprueba, una por
// una, las afirmaciones del PLAN.md sobre las que se apoya el diseño.
//
// # Por qué existe, y por qué corre en Linux
//
// El plan decía hacer este spike con `gomobile bind` y un teléfono. Pero el
// .aar no aporta nada a lo que hay que comprobar: `RcloneRPC` es un envoltorio
// de tres líneas sobre `librclone.RPC` (`librclone/gomobile/gomobile.go`), y
// el nombre de sesión de bisync sale de `FsPath`, que solo mira `f.Name()` y
// `f.Root()` — **no el tipo de backend** (`cmd/bisync/bilib/canonical.go`).
// Así que un remoto de tipo `local` llamado `nas` recorre exactamente el mismo
// código que un sftp llamado `nas`, y todo esto se puede comprobar aquí, sin
// NDK, sin emulador y sin red. Lo que el .aar añade es el empaquetado y el
// tamaño; eso se mide aparte (`herramientas/medir_backends.sh`).
//
// # Qué hace
//
// Monta un volumen de mentira con la misma distribución que tendrá el de la
// app, escribe su `rclone.conf` con la sección `[disp]`, y sincroniza tres
// parejas: una normal, la de la raíz (`local = "."`, la que necesita el
// upstream con nombre) y una con un espacio en la ruta. De cada una guarda el
// `session` que devuelve rclone en `engine/src/test/resources/sesiones.json`,
// que es lo que después compara `SesionesTest` con el `expectedPrefix()` del
// motor. Ese fichero se genera, no se escribe a mano, igual que `vectores.json`.
//
// Y de paso comprueba las afirmaciones que sostienen el diseño: que
// `core/command` está fuera de la biblioteca, que `sync/bisync` sí está
// registrado, que `maxDelete` es un porcentaje, y —lo que cambió el diseño—
// que una pasada fallida solo conserva su log si se lanzó con `_async`.
//
//	go run ./spike            # y escribe el JSON donde lo leen los tests
//	go run ./spike -base /otro/sitio -json ''   # sin escribir nada
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"

	"runtime/debug"

	// El spike habla con el MISMO paquete que empaqueta el .aar, no con una
	// copia de sus imports: si a `gobind` le faltara un blank import, aquí se
	// vería. Lo único que el .aar añade encima de esto es el JNI.
	prdrive "github.com/Jeremaya25/prdrive-app/rclone/gobind"

	// Solo para leer `bisync.Colors`, que es la evidencia de que los colores
	// se deciden una vez por proceso. El paquete del .aar no lo necesita.
	"github.com/rclone/rclone/cmd/bisync"
	"github.com/rclone/rclone/fs"
	"github.com/rclone/rclone/fs/config/configstruct"
	"github.com/rclone/rclone/fs/filter"
)

// pareja es lo que necesita una entrada del sync_config.toml para este spike.
type pareja struct {
	nombre   string
	local    string   // relativa a la raíz del volumen, como en el TOML
	includes []string // reglas `+`; si hay alguna, todo lo demás queda fuera
	excludes []string // reglas `-`
}

// Las tres parejas cubren los tres casos que pueden romper el nombre de sesión.
var parejas = []pareja{
	{nombre: "documentos", local: "Documentos"},
	// La raíz: `local = "."` no tiene primer tramo, así que su upstream se
	// llama `raiz` (model.RAIZ_UPSTREAM). Excluye `.prdrive` porque si no
	// sincronizaría el propio estado de bisync mientras bisync lo escribe.
	{nombre: "todo", local: ".", excludes: []string{".prdrive/**"}},
	// Un espacio en la ruta: es lo que obliga a entrecomillar el par entero
	// del `upstreams` (fs.SpaceSepList, `fs/types.go`).
	{nombre: "espacios", local: "Con espacios"},
}

type caso struct {
	Pareja   string `json:"pareja"`
	Path1    string `json:"path1"`
	Path2    string `json:"path2"`
	Session  string `json:"session"`
	WorkDir  string `json:"workDir"`
	BasePath string `json:"basePath"`
	Listing1 string `json:"listing1"`
	Listing2 string `json:"listing2"`
	// El JSON EXACTO que se le pasó a rclone en cada una de las dos pasadas,
	// que es lo que `SesionesTest` compara con el que monta `Pasada.kt`. Que
	// rclone lo aceptara y saliera de ahí esta sesión es la garantía de que
	// los nombres y los tipos de los parámetros son los buenos.
	RpcResync string `json:"rpc_resync"`
	RpcPasada string `json:"rpc_pasada"`
	// Y las dos rutas que la app le da a rclone, para que el test las pueda
	// poner en las Opciones sin tener que saberse la distribución del volumen.
	FiltersFile string `json:"filters_file"`
}

// salida es lo que se escribe en sesiones.json. A propósito NO lleva las
// observaciones: traen fechas, duraciones y tamaños, así que el fichero
// cambiaría en cada regeneración y su diff no querría decir nada. Las
// observaciones son la salida por pantalla del spike, y lo que las garantiza
// es que el spike falla si alguna deja de cumplirse.
type salida struct {
	Rclone      string   `json:"rclone"`
	Generado    string   `json:"generado_por"`
	Base        string   `json:"base"`
	RaizVolumen string   `json:"raiz_volumen"`
	ConfigToml  string   `json:"config_toml"`
	RcloneConf  string   `json:"rclone_conf"`
	SeccionDisp string   `json:"seccion_disp"`
	Casos       []caso   `json:"casos"`
	Filtros     []filtro `json:"filtros"`
	// Lo que rclone declara, para que la tabla de traducción de `Pasada.kt` se
	// compruebe contra rclone y no contra lo que leyó quien la escribió.
	ParametrosBisync []parametro `json:"parametros_bisync"`
	OpcionesSueltas  []string    `json:"opciones_sueltas"`
}

// parametro es un parámetro de `sync/bisync` tal y como lo declara la ayuda
// que rclone registra con el método (`cmd/bisync/rc.md`, generada de su
// propio código con `go generate`).
type parametro struct {
	Nombre string `json:"nombre"`
	Tipo   string `json:"tipo"`
}

type nota struct {
	Que      string `json:"que"`
	Esperado string `json:"esperado"`
	Visto    string `json:"visto"`
}

// filtro guarda el md5 que bisync escribió junto al fichero de filtros, que es
// con lo que el motor decide si una pareja necesita --resync.
type filtro struct {
	Pareja    string `json:"pareja"`
	Contenido string `json:"contenido"`
	Md5       string `json:"md5"`
}

var notas []nota

func main() {
	base := flag.String("base", "/tmp/prdrive-spike", "dónde montar el volumen de mentira")
	destino := flag.String("json", "", "dónde escribir el JSON de sesiones (vacío: no escribir)")
	flag.Parse()

	if err := correr(*base, *destino); err != nil {
		fmt.Fprintf(os.Stderr, "\nSPIKE FALLIDO: %v\n", err)
		os.Exit(1)
	}
	fmt.Println("\nSPIKE OK")
}

func correr(base, destino string) error {
	base, err := filepath.Abs(base)
	if err != nil {
		return err
	}
	raizVolumen := filepath.Join(base, "volumen")
	prdriveDir := filepath.Join(raizVolumen, ".prdrive")
	remotoDir := filepath.Join(base, "remoto")
	confPath := filepath.Join(prdriveDir, "rclone.conf")

	// Desde cero en cada pasada: un baseline de una ejecución anterior haría
	// que el --resync no fuera un --resync.
	if err := os.RemoveAll(base); err != nil {
		return err
	}
	for _, p := range parejas {
		if err := os.MkdirAll(filepath.Join(raizVolumen, filepath.FromSlash(p.local)), 0o755); err != nil {
			return err
		}
		if err := os.MkdirAll(filepath.Join(remotoDir, p.nombre), 0o755); err != nil {
			return err
		}
	}
	for _, d := range []string{"state", "filters"} {
		if err := os.MkdirAll(filepath.Join(prdriveDir, d), 0o755); err != nil {
			return err
		}
	}
	// Un fichero en cada lado, para que la pasada mueva algo de verdad.
	for _, p := range parejas {
		local := filepath.Join(raizVolumen, filepath.FromSlash(p.local), "desde-el-movil.txt")
		if err := os.WriteFile(local, []byte("escrito en el dispositivo\n"), 0o644); err != nil {
			return err
		}
		remoto := filepath.Join(remotoDir, p.nombre, "desde-el-pc.txt")
		if err := os.WriteFile(remoto, []byte("escrito en el PC\n"), 0o644); err != nil {
			return err
		}
	}

	confTexto := rcloneConf(raizVolumen)
	if err := os.WriteFile(confPath, []byte(confTexto), 0o600); err != nil {
		return err
	}
	tomlTexto := syncConfigToml(remotoDir)
	if err := os.WriteFile(filepath.Join(prdriveDir, "sync_config.toml"), []byte(tomlTexto), 0o644); err != nil {
		return err
	}

	// ---------------------------------------------------------------
	// El orden importa, y equivocarse no da error: ver
	// gobind/prdrive.go, RcloneSetConfigPath.
	// ---------------------------------------------------------------
	if err := prdrive.RcloneSetConfigPath(confPath); err != nil {
		return err
	}
	prdrive.RcloneInitialize()
	defer prdrive.RcloneFinalize()
	apuntar("el rclone.conf en uso es el del volumen", confPath, prdrive.RcloneConfigPath())
	if prdrive.RcloneConfigPath() != confPath {
		return errors.New("rclone no está leyendo el rclone.conf del volumen")
	}
	// `verbose = true` de las BASE_FLAGS de prdrive.
	if err := prdrive.RcloneLogNivel("INFO"); err != nil {
		return err
	}

	fmt.Printf("rclone %s, config en %s\n\n", versionDeRclone(), prdrive.RcloneConfigPath())

	if err := comprobarLaBiblioteca(); err != nil {
		return err
	}

	out := salida{
		Rclone:      versionDeRclone(),
		Generado:    "rclone/spike — no editar a mano",
		Base:        base,
		RaizVolumen: raizVolumen,
		ConfigToml:  tomlTexto,
		RcloneConf:  confTexto,
		SeccionDisp: seccionDisp(raizVolumen),
	}
	if out.ParametrosBisync, err = parametrosDeBisync(); err != nil {
		return err
	}
	if out.OpcionesSueltas, err = opcionesSueltas(); err != nil {
		return err
	}
	apuntar("parámetros que declara sync/bisync", "los de rc.md",
		fmt.Sprintf("%d", len(out.ParametrosBisync)))
	apuntar("opciones que rclone acepta sueltas", "ConfigInfo + filter.Options",
		fmt.Sprintf("%d", len(out.OpcionesSueltas)))

	if err := comprobarElLog(prdriveDir, remotoDir); err != nil {
		return err
	}

	if err := comprobarLosGenericos(base); err != nil {
		return err
	}

	for _, p := range parejas {
		c, f, err := pasadaCompleta(p, raizVolumen, remotoDir, prdriveDir)
		if err != nil {
			return fmt.Errorf("pareja %q: %w", p.nombre, err)
		}
		out.Casos = append(out.Casos, c)
		if f != nil {
			out.Filtros = append(out.Filtros, *f)
		}
	}

	if destino == "" {
		return nil
	}
	datos, err := json.MarshalIndent(out, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(destino, append(datos, '\n'), 0o644); err != nil {
		return err
	}
	fmt.Printf("\nescrito %s (%d casos)\n", destino, len(out.Casos))
	return nil
}

// ---------------------------------------------------------------------------
// Lo que el plan afirmaba, comprobado aquí
// ---------------------------------------------------------------------------

func comprobarLaBiblioteca() error {
	// 1. sync/bisync está registrado. Es la razón de existir del paquete de
	//    gobind: en el .aar de serie esto devolvería 404.
	lista, _, err := llamar("rc/list", "{}")
	if err != nil {
		return err
	}
	registrados := map[string]bool{}
	if commands, ok := lista["commands"].([]any); ok {
		for _, c := range commands {
			m, ok := c.(map[string]any)
			if !ok {
				continue
			}
			// «Path» con mayúscula: `rc.Call` no lleva etiquetas json
			// (`fs/rc/registry.go`), así que se serializa con el nombre del
			// campo de Go. Cuesta una hora de desconcierto.
			if path, ok := m["Path"].(string); ok {
				registrados[path] = true
			}
		}
	}
	for _, metodo := range []string{"sync/bisync", "sync/copy", "sync/sync", "operations/about", "core/stats", "job/status"} {
		apuntar("método registrado: "+metodo, "sí", fmt.Sprintf("%v", registrados[metodo]))
		if !registrados[metodo] {
			return fmt.Errorf("el método %q no está registrado: falta su blank import", metodo)
		}
	}

	// 2. core/command NO sirve desde la biblioteca. Era el eje del plan
	//    original y está rechazado de plano en librclone.RPC.
	for _, metodo := range []string{"core/command", "operations/uploadfile"} {
		_, status, err := llamar(metodo, "{}")
		visto := fmt.Sprintf("%d %v", status, err)
		apuntar("rechazado por librclone: "+metodo, "404 needs request/response", visto)
		if status != 404 || err == nil || !strings.Contains(err.Error(), "not supported") {
			return fmt.Errorf("%s no fue rechazado como se esperaba: %s", metodo, visto)
		}
	}

	// 3. maxDelete es un porcentaje, y el RPC lo exige. El 25 de
	//    MODES["bisync"] de prdrive significa 25 %, no 25 ficheros.
	_, status, err := llamar("sync/bisync", `{"path1":"disp:Documentos","path2":"disp:Documentos","maxDelete":101}`)
	visto := fmt.Sprintf("%d %v", status, err)
	apuntar("maxDelete fuera de 0..100", "rechazado como porcentaje", visto)
	if err == nil || !strings.Contains(err.Error(), "percentage between 0 and 100") {
		return fmt.Errorf("maxDelete=101 tenía que rechazarse como porcentaje, y dio: %s", visto)
	}
	return nil
}

// pasadaCompleta hace el --resync de una pareja y después una pasada normal, y
// devuelve lo que rclone dice de ella.
func pasadaCompleta(p pareja, raizVolumen, remotoDir, prdriveDir string) (caso, *filtro, error) {
	workdir := filepath.Join(prdriveDir, "state", p.nombre)
	if err := os.MkdirAll(workdir, 0o755); err != nil {
		return caso{}, nil, err
	}

	path1 := "disp:" + rutaEnCombine(p)
	path2 := "nas:" + filepath.ToSlash(filepath.Join(remotoDir, p.nombre))

	params := map[string]any{
		"path1":   path1,
		"path2":   path2,
		"workdir": workdir,
		"resync":  true,
		// Los mismos de MODES["bisync"] + BASE_FLAGS de prdrive.
		"createEmptySrcDirs": true,
		"maxDelete":          25,
		"conflictResolve":    "newer",
		"conflictSuffix":     "conflicto-dispositivo,conflicto-remoto",
		"resilient":          true,
		"recover":            true,
		"maxLock":            "2m",
	}

	// El fichero de filtros va en TODA pareja de bisync, también en las que no
	// tienen ningún patrón: `filters_file_for()` de prdrive solo mira
	// `wants_filters_file` (o sea, bisync + `use_filters_file`), así que
	// escribe un fichero con la cabecera y nada más y pasa `--filters-file`
	// igual. Hacerlo solo cuando hay patrones —como hacía este spike— dejaba
	// sin comprobar justo el caso más común.
	ffile := filepath.Join(prdriveDir, "filters", p.nombre+".txt")
	contenido := contenidoFiltros(p)
	if err := os.WriteFile(ffile, []byte(contenido), 0o644); err != nil {
		return caso{}, nil, err
	}
	params["filtersFile"] = ffile
	f := &filtro{Pareja: p.nombre, Contenido: contenido}
	defer func() {
		// bisync escribe el md5 JUNTO al fichero, y solo durante el --resync
		// (cmd/bisync/cmd.go, applyFilters). El motor lo compara antes de
		// ejecutar para no toparse con un error crítico.
		if datos, err := os.ReadFile(ffile + ".md5"); err == nil {
			f.Md5 = strings.TrimSpace(string(datos))
		}
	}()

	res, err := bisyncAsync(params)
	if err != nil {
		return caso{}, nil, fmt.Errorf("el --resync falló: %w\n%s", err, res.output)
	}
	fmt.Printf("  [%s] resync ok, session=%s\n", p.nombre, res.session)

	// El nombre no puede traer {hexstring}: es el motivo por el que `[disp]`
	// va en el rclone.conf y no en el entorno (cmd/bisync/bilib/canonical.go,
	// StripHexString).
	apuntar("session sin {hexstring}: "+p.nombre, "sin llaves", res.session)
	if strings.ContainsAny(res.session, "{}") {
		return caso{}, nil, fmt.Errorf("la sesión de %q trae un hexstring: %s", p.nombre, res.session)
	}
	// Y el basePath tiene que ser el workdir del motor más ese nombre.
	if quiero := filepath.Join(workdir, res.session); res.basePath != quiero {
		return caso{}, nil, fmt.Errorf("basePath %q no es %q", res.basePath, quiero)
	}
	for _, lst := range []string{res.listing1, res.listing2} {
		if _, err := os.Stat(lst); err != nil {
			return caso{}, nil, fmt.Errorf("no quedó el listado %q: %w", lst, err)
		}
	}

	// Segunda pasada, sin resync: es la que prueba que el baseline sirve. Si
	// el prefijo se moviera, aquí saldría «likely due to critical error or
	// first run» y la pareja pediría resync en cada pasada.
	delete(params, "resync")
	res2, err := bisyncAsync(params)
	if err != nil {
		return caso{}, nil, fmt.Errorf("la segunda pasada falló: %w\n%s", err, res2.output)
	}
	if res2.session != res.session {
		return caso{}, nil, fmt.Errorf("la sesión cambió entre pasadas: %q → %q", res.session, res2.session)
	}
	fmt.Printf("  [%s] segunda pasada ok, baseline reutilizado\n", p.nombre)

	return caso{
		Pareja: p.nombre, Path1: path1, Path2: path2,
		Session: res.session, WorkDir: res.workDir, BasePath: res.basePath,
		Listing1: res.listing1, Listing2: res.listing2,
		RpcResync: res.entrada, RpcPasada: res2.entrada,
		FiltersFile: ffile,
	}, f, nil
}

// comprobarElLog resuelve de dónde sale el log de una pasada, que es lo que
// alimenta el KNOWN_ERRORS de `sync.py`. El plan decía «`output` es el log, no
// hace falta fichero», y es verdad a medias: hay una condición y una
// excepción, y las dos cambian el diseño.
//
//  1. **Hace falta `_async`.** `librclone.RPC` descarta el `out` de la llamada
//     cuando esta devuelve error (`writeError`,
//     librclone/librclone/librclone.go), así que en la vía síncrona el campo
//     `output` se pierde justo cuando hace falta. Con `_async` sobrevive,
//     porque `job.finish()` asigna `job.Output` ANTES de mirar el error
//     (fs/rc/jobs/job.go). El error del job, además, es un lacónico «bisync
//     aborted»: el diagnóstico está en `output`, que es el log entero de la
//     pasada porque `bilib.CaptureOutput` instala un `SetOutput` en el handler
//     del log mientras dura (cmd/bisync/bilib/output.go).
//  2. **Y eso solo vale para bisync.** Los otros cuatro modos de prdrive son
//     `copy` y `sync`, y `rcSyncCopyMove` devuelve `nil` (`fs/sync/rc.go`):
//     ni log ni nada. Para ellos el único canal es el sumidero de
//     `RcloneLogTexto`, que vale para los cinco y no cuesta un fichero en el
//     dispositivo.
func comprobarElLog(prdriveDir, remotoDir string) error {
	workdir := filepath.Join(prdriveDir, "state", "sin-baseline")
	if err := os.MkdirAll(workdir, 0o755); err != nil {
		return err
	}
	// Una pareja sin baseline y sin --resync: el fallo más parecido al que se
	// da de verdad, y justo el que KNOWN_ERRORS explica.
	params := map[string]any{
		"path1":   "disp:Documentos",
		"path2":   "nas:" + filepath.ToSlash(filepath.Join(remotoDir, "documentos")),
		"workdir": workdir,
	}

	// (1) Con _async: el log llega, y trae el diagnóstico que el error no da.
	res, err := bisyncAsync(params)
	if err == nil {
		return errors.New("la pasada sin baseline tenía que fallar")
	}
	aguja := "cannot find prior Path1 or Path2 listings"
	apuntar("una pasada fallida con _async conserva el log", "el log completo",
		fmt.Sprintf("%d caracteres", len(res.output)))
	apuntar("el error del job no explica nada", "un mensaje genérico", err.Error())
	apuntar("el diagnóstico está en output, que ES el log", aguja,
		fmt.Sprintf("%v", strings.Contains(res.output, aguja)))
	if res.output == "" {
		return errors.New("el job asíncrono no conservó el log")
	}
	if strings.Contains(err.Error(), aguja) {
		return errors.New("el error del job ya trae el diagnóstico: revisar si hay que leer el output")
	}
	if !strings.Contains(res.output, aguja) {
		return fmt.Errorf("el log no trae el diagnóstico:\n%s", res.output)
	}

	// (2) La misma pasada SIN _async: el error llega, el log no.
	entrada, err := json.Marshal(params)
	if err != nil {
		return err
	}
	sinc, status, errSinc := llamar("sync/bisync", string(entrada))
	_, traeOutput := sinc["output"]
	apuntar("la misma pasada SIN _async pierde el log", "sin campo output",
		fmt.Sprintf("status=%d, trae output=%v", status, traeOutput))
	if errSinc == nil {
		return errors.New("la pasada sin baseline tenía que fallar")
	}
	if traeOutput {
		return errors.New("librclone ya devuelve el output de una llamada fallida: revisar si _async sigue haciendo falta")
	}

	// (3) Sin colores ANSI. Los apaga RcloneInitialize en la configuración
	// global, y tiene que ser ahí: por la llamada no se puede.
	apuntar("el log no trae ANSI", "sin ANSI",
		fmt.Sprintf("%v", !strings.Contains(res.output, "\x1b[")))
	apuntar("y bisync no los ha encendido", "false", fmt.Sprintf("%v", bisync.Colors))
	if strings.Contains(res.output, "\x1b[") || bisync.Colors {
		return errors.New("el log viene coloreado: RcloneInitialize no está apagando los colores")
	}
	// La prueba de por qué no vale hacerlo en la llamada: `_config` valida
	// `transfers` pero se traga un `color` inválido sin decir nada, porque
	// `TerminalColorMode` es un fs.Enum y configstruct no lo recoge.
	_, stTransfers, _ := llamar("sync/bisync", `{"path1":"disp:Documentos","path2":"disp:Documentos","_config":{"transfers":"noesunnumero"}}`)
	_, stColor, _ := llamar("sync/bisync", `{"path1":"disp:Documentos","path2":"disp:Documentos","_config":{"color":"noesunvalor"}}`)
	apuntar("_config valida transfers pero ignora color", "400 y no-400",
		fmt.Sprintf("transfers=%d, color=%d", stTransfers, stColor))
	if stTransfers != 400 || stColor == 400 {
		return fmt.Errorf("_config ya valida 'color' (transfers=%d, color=%d): se podría apagar por llamada",
			stTransfers, stColor)
	}
	fmt.Printf("  [fallo] el log solo llega por _async, y el diagnóstico solo en 'output'\n")

	// (4) Y los modos que no son bisync no devuelven log ninguno.
	return comprobarElLogDeLosOtrosModos(remotoDir)
}

// comprobarElLogDeLosOtrosModos es la razón de ser del sumidero: los modos
// `up`, `down` y los dos `*-mirror` de prdrive son `sync/copy` y `sync/sync`,
// que no devuelven nada.
func comprobarElLogDeLosOtrosModos(remotoDir string) error {
	entrada := fmt.Sprintf(`{"_async":true,"srcFs":"disp:Documentos","dstFs":%q,"createEmptySrcDirs":true}`,
		"nas:"+filepath.ToSlash(filepath.Join(remotoDir, "copia")))
	prdrive.RcloneLogReiniciar()
	lanzado, _, err := llamar("sync/copy", entrada)
	if err != nil {
		return fmt.Errorf("sync/copy: %w", err)
	}
	jobid, ok := lanzado["jobid"].(float64)
	if !ok {
		return fmt.Errorf("sync/copy no devolvió jobid: %v", lanzado)
	}
	estado, err := esperarElJob(int64(jobid))
	if err != nil {
		return err
	}
	salida, _ := estado["output"].(map[string]any)
	registrado := prdrive.RcloneLogTexto()
	if fallo := cadena(estado, "error"); fallo != "" {
		return fmt.Errorf("sync/copy falló: %s\n%s", fallo, registrado)
	}
	apuntar("sync/copy no devuelve log", "output vacío", fmt.Sprintf("%d campos", len(salida)))
	apuntar("pero el sumidero sí lo tiene", "el log de la copia",
		fmt.Sprintf("%d caracteres", len(registrado)))
	if len(salida) != 0 {
		return errors.New("sync/copy ya devuelve algo: revisar si el sumidero sigue haciendo falta para los otros modos")
	}
	if !strings.Contains(registrado, "Copied") {
		return fmt.Errorf("el sumidero no registró la copia:\n%s", registrado)
	}
	fmt.Printf("  [copy] sin log en el RPC; el del sumidero sí está (%d caracteres)\n", len(registrado))
	return nil
}

// ---------------------------------------------------------------------------
// Los parámetros genéricos: `_config` y `_filter`
// ---------------------------------------------------------------------------

// comprobarLosGenericos resuelve CÓMO se pasan los flags de prdrive que no son
// parámetros del método, y la respuesta no es la que parecía.
//
// `jobs.NewJob` llama a `rc.AddConfig(ctx, in)` y a `rc.AddFilter(ctx, in)`
// (fs/rc/jobs/job.go), y las dos van a `rc.ParseOptions` (fs/rc/context.go),
// que admite los valores por DOS caminos distintos y NO son equivalentes:
//
//   - **Sueltos, en el primer nivel de la llamada**, por
//     `configstruct.SetAny`: los nombres son las etiquetas `config:"…"`, o sea
//     snake_case (`dry_run`, `max_delete`, `include`).
//   - **Dentro de `_config` / `_filter`**, por `GetStructMissingOK` →
//     `rc.Reshape`, que es `json.Marshal` + `json.Unmarshal` sobre
//     `fs.ConfigInfo`. Y esa estructura **no tiene etiquetas `json`**: el
//     nombre que casa es el del CAMPO de Go (`DryRun`, `MaxDelete`,
//     `IncludeRule`), no el de la etiqueta. `encoding/json` ignora el guión
//     bajo y descarta sin decir nada lo que no encuentra.
//
// O sea que `{"_config":{"dry_run":true}}` —la forma que parecía la buena— no
// pone ningún dry-run: **sincroniza de verdad**. Un «Simular» que sincroniza
// es el peor fallo posible en este proyecto, y no da ni un aviso.
//
// De paso, la misma trampa con otro sombrero: los cuatro parámetros de bisync
// que son enumerados (`checkSync`, `resyncMode`, `conflictResolve`,
// `conflictLoser`) se leen con `in.GetString` y `setEnum` (cmd/bisync/rc.go)
// trata «no es una cadena» igual que «no está»: pasar un booleano o un número
// se ignora en silencio, y solo una cadena con un valor inválido da 400.
func comprobarLosGenericos(base string) error {
	origen := filepath.Join(base, "generico", "origen")
	if err := os.MkdirAll(origen, 0o755); err != nil {
		return err
	}
	for _, n := range []string{"a.txt", "b.bin"} {
		if err := os.WriteFile(filepath.Join(origen, n), []byte("x\n"), 0o644); err != nil {
			return err
		}
	}

	casos := []struct {
		nombre   string
		extra    string // lo que se añade a la llamada
		esperado int    // ficheros que deben quedar en el destino
		porque   string
	}{
		{"dry_run suelto", `"dry_run":true`, 0,
			"suelto va por configstruct, que lee las etiquetas config:"},
		{"_config dry_run", `"_config":{"dry_run":true}`, 2,
			"dentro de _config el nombre es el del campo de Go: dry_run no casa con DryRun"},
		{"_config DryRun", `"_config":{"DryRun":true}`, 0,
			"con el nombre del campo sí"},
		{"include suelto", `"include":["*.txt"]`, 1,
			"filter.Options lleva RulesOpt incrustado, así que 'include' es un item suelto"},
		{"_filter IncludeRule", `"_filter":{"IncludeRule":["*.txt"]}`, 1,
			"el campo de Go de --include"},
		{"_filter include", `"_filter":{"include":["*.txt"]}`, 2,
			"y la etiqueta no vale: se descarta y se copia todo"},
	}

	for i, c := range casos {
		destino := filepath.Join(base, "generico", fmt.Sprintf("destino%d", i))
		if err := os.MkdirAll(destino, 0o755); err != nil {
			return err
		}
		entrada := fmt.Sprintf(`{"_async":true,"srcFs":%q,"dstFs":%q,%s}`,
			"nas:"+filepath.ToSlash(origen), "nas:"+filepath.ToSlash(destino), c.extra)
		prdrive.RcloneLogReiniciar()
		lanzado, _, err := llamar("sync/copy", entrada)
		if err != nil {
			return fmt.Errorf("%s: %w", c.nombre, err)
		}
		jobid, ok := lanzado["jobid"].(float64)
		if !ok {
			return fmt.Errorf("%s: sin jobid: %v", c.nombre, lanzado)
		}
		estado, err := esperarElJob(int64(jobid))
		if err != nil {
			return err
		}
		if fallo := cadena(estado, "error"); fallo != "" {
			return fmt.Errorf("%s: la copia falló: %s\n%s", c.nombre, fallo, prdrive.RcloneLogTexto())
		}
		entradas, err := os.ReadDir(destino)
		if err != nil {
			return err
		}
		apuntar("copiados con "+c.nombre, fmt.Sprintf("%d", c.esperado), fmt.Sprintf("%d", len(entradas)))
		if len(entradas) != c.esperado {
			return fmt.Errorf("con %s se copiaron %d ficheros y se esperaban %d (%s).\n"+
				"Si esto ha cambiado, hay que revisar cómo pasa Pasada.kt los flags: "+
				"la forma que no funciona NO da error",
				c.nombre, len(entradas), c.esperado, c.porque)
		}
	}

	// Y los enumerados de bisync: solo una cadena inválida se queja.
	scratch := filepath.Join(base, "generico", "enum")
	if err := os.MkdirAll(filepath.Join(scratch, "lado2"), 0o755); err != nil {
		return err
	}
	base1 := "nas:" + filepath.ToSlash(filepath.Join(base, "generico", "origen"))
	base2 := "nas:" + filepath.ToSlash(filepath.Join(scratch, "lado2"))
	plantilla := `{"path1":%q,"path2":%q,"workdir":%q,"resync":true,"conflictResolve":%s}`

	_, estado, fallo := llamar("sync/bisync",
		fmt.Sprintf(plantilla, base1, base2, filepath.Join(scratch, "wd1"), `"tonteria"`))
	apuntar("conflictResolve inválido como cadena", "la llamada falla", fmt.Sprintf("%d: %v", estado, fallo))
	if estado == 200 {
		return errors.New("una cadena inválida en conflictResolve ya no falla: " +
			"si rclone deja de validarla, el motor no puede fiarse de que le avise")
	}
	// Y el estado NO es 400, aunque sea un parámetro inválido: solo los que
	// rclone envuelve en `rc.NewErrParamInvalid` lo son (el `maxDelete` fuera
	// de 0..100 lo es; este sale de `setEnum` como un error pelado). Así que
	// el motor no puede leer el código: tiene que leer el mensaje.
	apuntar("…y no con un 400, que es de NewErrParamInvalid", "500", fmt.Sprintf("%d", estado))

	if err := os.MkdirAll(filepath.Join(scratch, "wd2"), 0o755); err != nil {
		return err
	}
	_, estado2, err := llamar("sync/bisync",
		fmt.Sprintf(plantilla, base1, base2, filepath.Join(scratch, "wd2"), `5`))
	apuntar("conflictResolve como número", "se ignora y la pasada va", fmt.Sprintf("%d", estado2))
	if estado2 != 200 {
		return fmt.Errorf("un número en conflictResolve ya no se ignora (%d: %v): mejor, "+
			"pero el motor está escrito suponiendo que NO avisa", estado2, err)
	}
	return nil
}

// ---------------------------------------------------------------------------
// Hablar con la biblioteca
// ---------------------------------------------------------------------------

type resultado struct {
	output, session, workDir, basePath, listing1, listing2 string
	// entrada es el JSON tal y como se envió, que es lo que se apunta en
	// sesiones.json para comparar con el que monta el motor.
	entrada string
	// log es lo que rclone registró durante la pasada, recogido del sumidero
	// del paquete del .aar. Es donde está el diagnóstico de un fallo.
	log string
}

// bisyncAsync lanza la pasada con `_async` y espera el job. Devuelve lo que
// rclone contó de ella, y el error de la pasada si la hubo — con el log
// dentro del resultado en los dos casos.
func bisyncAsync(params map[string]any) (resultado, error) {
	con := map[string]any{"_async": true, "_group": "spike"}
	for k, v := range params {
		con[k] = v
	}
	entrada, err := comoJSON(con)
	if err != nil {
		return resultado{}, err
	}
	prdrive.RcloneLogReiniciar()
	lanzado, _, err := llamar("sync/bisync", entrada)
	if err != nil {
		return resultado{entrada: entrada}, err
	}
	jobid, ok := lanzado["jobid"].(float64)
	if !ok {
		return resultado{}, fmt.Errorf("sync/bisync con _async no devolvió jobid: %v", lanzado)
	}

	estado, err := esperarElJob(int64(jobid))
	if err != nil {
		return resultado{}, err
	}
	{
		salida, _ := estado["output"].(map[string]any)
		res := resultado{
			entrada:  entrada,
			log:      prdrive.RcloneLogTexto(),
			output:   cadena(salida, "output"),
			session:  cadena(salida, "session"),
			workDir:  cadena(salida, "workDir"),
			basePath: cadena(salida, "basePath"),
			listing1: cadena(salida, "listing1"),
			listing2: cadena(salida, "listing2"),
		}
		if fallo := cadena(estado, "error"); fallo != "" {
			return res, errors.New(fallo)
		}
		return res, nil
	}
}

// comoJSON escribe el JSON de una llamada **sin escapar el HTML**.
//
// `json.Marshal` convierte `<`, `>` y `&` en `\u003c` y compañía, y el JSON que
// escribe `engine/Json.kt` no lo hace: como los dos textos se comparan en
// `SesionesTest`, un `&` en una ruta los separaría por un detalle que no tiene
// nada que ver con lo que se está comprobando. Las claves siguen saliendo
// ordenadas, que es lo que hace `encoding/json` con un mapa y lo que el motor
// copia a propósito.
func comoJSON(v any) (string, error) {
	var b strings.Builder
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return "", err
	}
	return strings.TrimSuffix(b.String(), "\n"), nil
}

// parametrosDeBisync saca de la ayuda que rclone registra los nombres y los
// tipos de los parámetros de `sync/bisync`.
//
// No es documentación suelta: `cmd/bisync/rc.md` se genera del propio código
// con `go generate`, y es lo que `rc/list` devuelve. Así que preguntárselo a
// la biblioteca en marcha es preguntárselo a rclone — y cuando rclone
// renombre un parámetro o le cambie el tipo, lo que falla es un test y no un
// flag que deja de aplicarse en silencio.
func parametrosDeBisync() ([]parametro, error) {
	lista, _, err := llamar("rc/list", "{}")
	if err != nil {
		return nil, err
	}
	comandos, _ := lista["commands"].([]any)
	var ayuda string
	for _, c := range comandos {
		m, _ := c.(map[string]any)
		if cadena(m, "Path") == "sync/bisync" {
			ayuda = cadena(m, "Help")
			break
		}
	}
	if ayuda == "" {
		return nil, errors.New("rc/list no trae la ayuda de sync/bisync")
	}
	re := regexp.MustCompile(`(?m)^- ([A-Za-z0-9]+)(?: \(required\))? - \(([A-Za-z]+)\)`)
	var salida []parametro
	for _, m := range re.FindAllStringSubmatch(ayuda, -1) {
		salida = append(salida, parametro{Nombre: m[1], Tipo: m[2]})
	}
	if len(salida) < 20 {
		return nil, fmt.Errorf("solo se han reconocido %d parámetros en la ayuda de "+
			"sync/bisync: ¿ha cambiado el formato?", len(salida))
	}
	sort.Slice(salida, func(i, j int) bool { return salida[i].Nombre < salida[j].Nombre })
	return salida, nil
}

// opcionesSueltas son los nombres que rclone acepta en el primer nivel de una
// llamada: las etiquetas `config:"…"` de `fs.ConfigInfo` y de
// `filter.Options`, que es lo que lee `configstruct.SetAny` desde
// `rc.ParseOptions`. Con esto, el motor no puede mandar un flag suelto que
// rclone vaya a descartar sin decir nada.
func opcionesSueltas() ([]string, error) {
	var nombres []string
	for _, opt := range []any{&fs.ConfigInfo{}, &filter.Opt} {
		items, err := configstruct.Items(opt)
		if err != nil {
			return nil, err
		}
		for _, it := range items {
			nombres = append(nombres, it.Name)
		}
	}
	sort.Strings(nombres)
	return nombres, nil
}

// esperarElJob espera a que un job asíncrono acabe y devuelve su estado.
func esperarElJob(jobid int64) (map[string]any, error) {
	for {
		estado, _, err := llamar("job/status", fmt.Sprintf(`{"jobid":%d}`, jobid))
		if err != nil {
			return nil, err
		}
		if acabado, _ := estado["finished"].(bool); acabado {
			return estado, nil
		}
		time.Sleep(50 * time.Millisecond)
	}
}

// llamar hace una llamada al rc y devuelve la respuesta ya deserializada, el
// estado HTTP, y el error que rclone puso en el JSON si lo hay.
func llamar(metodo, entrada string) (map[string]any, int, error) {
	res := prdrive.RcloneRPC(metodo, entrada)
	var respuesta map[string]any
	if err := json.Unmarshal([]byte(res.Output), &respuesta); err != nil {
		return nil, res.Status, fmt.Errorf("respuesta de %q ilegible: %w: %s", metodo, err, res.Output)
	}
	if res.Status != 200 {
		return respuesta, res.Status, errors.New(cadena(respuesta, "error"))
	}
	return respuesta, res.Status, nil
}

// versionDeRclone devuelve la versión del módulo con el que se compiló, no
// `fs.Version`: esa dice «-DEV» porque aquí rclone se construye desde fuente,
// sin los ldflags de su release, y en el JSON quedaría una versión falsa.
func versionDeRclone() string {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return "desconocida"
	}
	for _, dep := range info.Deps {
		if dep.Path == "github.com/rclone/rclone" {
			return dep.Version
		}
	}
	return "desconocida"
}

func cadena(m map[string]any, clave string) string {
	if m == nil {
		return ""
	}
	s, _ := m[clave].(string)
	return s
}

func apuntar(que, esperado, visto string) {
	notas = append(notas, nota{Que: que, Esperado: esperado, Visto: visto})
	fmt.Printf("  · %-58s %s\n", que, visto)
}

// ---------------------------------------------------------------------------
// La configuración, escrita como la escribirá la app
// ---------------------------------------------------------------------------

func rutaEnCombine(p pareja) string {
	tramos := tramosLocales(p.local)
	if len(tramos) == 0 {
		return "raiz" // model.RAIZ_UPSTREAM
	}
	return strings.Join(tramos, "/")
}

func tramosLocales(local string) []string {
	var tramos []string
	for _, t := range strings.Split(strings.ReplaceAll(local, "\\", "/"), "/") {
		if t != "" && t != "." {
			tramos = append(tramos, t)
		}
	}
	return tramos
}

// rcloneConf escribe el `[disp]` que en el escritorio viaja en
// RCLONE_CONFIG_DISP_UPSTREAMS. El `[nas]` es de tipo local a propósito: para
// el nombre de sesión da igual el tipo de backend (FsPath solo mira nombre y
// raíz), y así el spike no necesita red.
//
// El entrecomillado es el de fs.SpaceSepList (`fs/types.go`): las comillas
// envuelven el PAR ENTERO `nombre=ruta`, nunca solo la ruta, y una comilla de
// dentro se duplica. `engine/Model.kt` hace lo mismo y `SesionesTest` compara
// los dos textos: si no coinciden, uno de los dos está mal.
func seccionDisp(raizVolumen string) string {
	tops := map[string]string{}
	for _, p := range parejas {
		tramos := tramosLocales(p.local)
		nombre, ruta := "raiz", raizVolumen
		if len(tramos) > 0 {
			nombre = tramos[0]
			ruta = filepath.Join(raizVolumen, tramos[0])
		}
		tops[nombre] = filepath.ToSlash(ruta)
	}
	nombres := make([]string, 0, len(tops))
	for n := range tops {
		nombres = append(nombres, n)
	}
	sort.Strings(nombres)
	partes := make([]string, 0, len(nombres))
	for _, n := range nombres {
		partes = append(partes, fmt.Sprintf(`"%s=%s"`, n, strings.ReplaceAll(tops[n], `"`, `""`)))
	}
	return "[disp]\ntype = combine\nupstreams = " + strings.Join(partes, " ") + "\n"
}

// rcloneConf es el fichero entero: el remoto de mentira más la sección del
// dispositivo.
func rcloneConf(raizVolumen string) string {
	return "[nas]\ntype = local\n\n" + seccionDisp(raizVolumen)
}

// syncConfigToml escribe el config con el que el motor tiene que llegar al
// mismo nombre de sesión. El `remote_path` es absoluto porque el remoto de
// mentira es de tipo local: `f.Root()` devuelve entonces la ruta tal cual, que
// es lo que el motor pone en el `remote_path`.
func syncConfigToml(remotoDir string) string {
	var b strings.Builder
	b.WriteString("# Generado por rclone/spike. No editar a mano.\n\n")
	b.WriteString("[defaults]\nremote = \"nas\"\ndevice_remote = \"disp\"\n")
	for _, p := range parejas {
		fmt.Fprintf(&b, "\n[[pair]]\nname = %q\nlocal = %q\nremote_path = %q\nmode = \"bisync\"\n",
			p.nombre, p.local, filepath.ToSlash(filepath.Join(remotoDir, p.nombre)))
		escribirArray(&b, "include", p.includes)
		escribirArray(&b, "exclude", p.excludes)
	}
	return b.String()
}

// escribirArray escribe un array del TOML como lo hace config_file.py: en una
// línea si tiene un solo elemento, y partido si tiene más.
func escribirArray(b *strings.Builder, clave string, valores []string) {
	switch len(valores) {
	case 0:
	case 1:
		fmt.Fprintf(b, "%s = [%q]\n", clave, valores[0])
	default:
		fmt.Fprintf(b, "%s = [\n", clave)
		for _, v := range valores {
			fmt.Fprintf(b, "    %q,\n", v)
		}
		b.WriteString("]\n")
	}
}

// contenidoFiltros replica `bisync.filters_content()` de prdrive, porque el
// md5 que bisync guarda es del contenido exacto y el motor tiene que escribir
// ese mismo texto. El `- **` va solo cuando hay reglas `+`: igual que
// `--include`, una regla positiva deja fuera todo lo demás.
//
// Es una tercera copia de la misma regla, y por eso `SesionesTest` compara
// este texto con el que da `Bisync.filtersContent()`: si se separan, falla.
func contenidoFiltros(p pareja) string {
	lineas := []string{
		"# Generado por sync.py desde sync_config.toml. No editar a mano:",
		"# se regenera en cada ejecución. Cambiar los patrones exige --resync.",
	}
	for _, i := range p.includes {
		lineas = append(lineas, "+ "+i)
	}
	for _, e := range p.excludes {
		lineas = append(lineas, "- "+e)
	}
	if len(p.includes) > 0 {
		lineas = append(lineas, "- **")
	}
	return strings.Join(lineas, "\n") + "\n"
}
