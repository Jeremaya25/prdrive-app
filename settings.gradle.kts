// El módulo `engine` es Kotlin puro (JVM), sin nada de Android, a propósito: es
// donde viven las constantes copiadas de prdrive y se prueba con `gradle test`
// sin SDK, sin emulador y sin dispositivo.
//
// El módulo `app` (Android) solo entra si hay un SDK de Android a mano
// (ANDROID_HOME, ANDROID_SDK_ROOT o `sdk.dir` en local.properties). Así
// `./gradlew :engine:test` sigue funcionando en una máquina —o en el CI— que no
// tiene SDK, que es justo lo que promete el motor.
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

val hayAndroidSdk = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").any { !System.getenv(it).isNullOrBlank() } ||
    file("local.properties").let { it.isFile && it.readText().contains("sdk.dir") }
if (hayAndroidSdk) {
    include(":app")
}
