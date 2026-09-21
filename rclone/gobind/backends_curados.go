//go:build !rclone_todos

package prdrive

// Juego corto de backends: el de por defecto.
//
// `backend/all` son unos cincuenta y se lleva la mayor parte del tamaño del
// .aar. Esta lista cubre lo que un remoto de prdrive es en la práctica, y a
// cambio un `[remote]` del catálogo de otro tipo se rechaza con un mensaje en
// vez de funcionar. Cuál de los dos juegos entra en la app se decide midiendo:
// `herramientas/medir_backends.sh` construye las dos y compara.
//
// `local` y `combine` no son negociables: `local` es el volumen y `combine` es
// el remote `disp` que hace que el nombre de sesión de bisync no dependa de
// dónde esté montado el volumen.
import (
	_ "github.com/rclone/rclone/backend/combine" // el remote 'disp' del dispositivo
	_ "github.com/rclone/rclone/backend/crypt"   // un remoto cifrado sobre cualquier otro
	_ "github.com/rclone/rclone/backend/local"   // el volumen
	_ "github.com/rclone/rclone/backend/sftp"    // el caso de prdrive: un NAS por SSH
	_ "github.com/rclone/rclone/backend/webdav"  // Nextcloud y compañía
)
