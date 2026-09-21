// Dos módulos, y la separación es la regla de fondo del proyecto: `engine` es
// Kotlin puro (JVM), sin una línea de Android, y se prueba con `gradle test`
// sin SDK, sin emulador y sin dispositivo; `app` solo dibuja y llama.
//
// `:app` necesita dos cosas que no están en el repositorio: el SDK de Android
// (en `local.properties` o en ANDROID_HOME) y el `.aar` de rclone en
// `app/libs/`, que se construye con `sh rclone/aar.sh app/libs`. Sin ellas,
// `:engine:test` sigue funcionando igual: es lo que permite trabajar en el
// motor sin nada instalado.
rootProject.name = "prdrive-app"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":engine")
include(":app")
