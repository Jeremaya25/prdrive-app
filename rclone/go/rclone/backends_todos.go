//go:build todos

package rclone

// Todos los backends de rclone, como el .aar de serie. Se construye con la
// etiqueta "todos" para medir cuánto pesa frente a la lista corta
// (backends_corta.go).
import (
	_ "github.com/rclone/rclone/backend/all"
)
