//go:build rclone_todos

package prdrive

// Todos los backends de rclone, con `-tags rclone_todos`. Es el juego que trae
// el .aar de serie, y la otra mitad de la medición.
import (
	_ "github.com/rclone/rclone/backend/all"
)
