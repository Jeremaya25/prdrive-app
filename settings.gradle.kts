// El módulo `engine` es Kotlin puro (JVM), sin nada de Android, a propósito: es
// donde viven las constantes copiadas de prdrive y se prueba con `gradle test`
// sin SDK, sin emulador y sin dispositivo. El módulo `app` (Android) entra
// cuando entre el .aar de rclone, que es lo que le da algo que hacer.
rootProject.name = "prdrive-app"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":engine")
