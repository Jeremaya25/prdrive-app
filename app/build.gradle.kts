// El módulo de Android. La regla del proyecto es que aquí **solo se dibuja y se
// llama**: todo lo que decide algo está en `:engine`, que se prueba sin SDK y
// sin dispositivo. Es la misma separación que `tk_*` en prdrive.
plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "prdrive.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "prdrive.app"
        // minSdk 26 por `java.nio.file`, y con él las operaciones atómicas de
        // fichero que el motor usa para escribir el estado.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Sin minificar de momento: el .aar trae su propio proguard.txt y
            // el paso 1 no publica nada.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // El .so de rclone pesa 26 MB sin comprimir y va sin comprimir a
    // propósito: Android lo mapea directamente del APK en vez de extraerlo, lo
    // que ahorra ese espacio otra vez en disco. Ver PLAN.md.
    packaging {
        jniLibs.useLegacyPackaging = false
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

kotlin {
    compilerOptions {
        // El mismo que `compileOptions` de arriba, y el mismo que `:engine`:
        // AGP compila el Java a 17 y si Kotlin fuera a otro, el build falla
        // con un mensaje que no dice por qué.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

// El .aar no está en el repositorio, así que puede faltar. Se dice con una
// frase y la orden que lo construye, en vez de dejar que AGP falle más adelante
// con un mensaje que no explica nada.
val aarDeRclone = file("libs/prdrive-rclone.aar")
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project == project } && !aarDeRclone.exists()) {
        throw GradleException(
            "Falta $aarDeRclone, que es rclone como biblioteca.\n" +
                "Se construye con:  ANDROID_HOME=... ANDROID_NDK_HOME=... " +
                "sh rclone/aar.sh app/libs\n" +
                "No está en el repositorio porque son 13 MB por versión de rclone, y se " +
                "generan. `./gradlew :engine:test` no lo necesita.",
        )
    }
}

dependencies {
    implementation(project(":engine"))

    // rclone como biblioteca. NO está en el repositorio: se construye con
    // `sh rclone/aar.sh app/libs` (pide NDK y SDK; ver el script). Es un
    // binario de 13 MB por versión de rclone, así que versionarlo sería meter
    // en el historial algo que se genera.
    implementation(files("libs/prdrive-rclone.aar"))

    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    // El escáner de códigos de ML Kit. Se elige por una razón concreta: lo
    // ejecuta Google Play Services en su propio proceso, así que la app **no
    // pide el permiso de cámara**. Y hay alternativa por si no está: pegar el
    // texto del QR a mano.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
