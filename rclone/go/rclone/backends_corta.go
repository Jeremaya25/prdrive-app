//go:build !todos

package rclone

// La lista corta de backends: lo que la app necesita y lo que un catálogo de
// prdrive usa de verdad. backend/all son unos cincuenta y se lleva la mayor
// parte del tamaño del .aar; con esta lista, un [remote] de otro tipo se
// rechaza con un mensaje («didn't find backend called ...»).
//
// local y combine no son negociables: el volumen es local y el lado local de
// toda pareja va como un remote combine. alias, porque bisync.py sabe nombrar
// sus extremos y un catálogo puede usarlo. sftp es el remoto de prdrive;
// webdav y crypt, los siguientes que se piden.
//
// Para construir con todos: etiqueta "todos" (ver construir.sh).
import (
	_ "github.com/rclone/rclone/backend/alias"
	_ "github.com/rclone/rclone/backend/combine"
	_ "github.com/rclone/rclone/backend/crypt"
	_ "github.com/rclone/rclone/backend/local"
	_ "github.com/rclone/rclone/backend/sftp"
	_ "github.com/rclone/rclone/backend/webdav"
)
