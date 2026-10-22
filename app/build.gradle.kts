// La app: solo dibuja y llama. Lo que decide qué se sincroniza y cómo está en
// `engine` (Kotlin puro, probado sin dispositivo) y lo que sincroniza es rclone,
// que entra como biblioteca en `rclone/build/rclone.aar` (ver
// rclone/construir.sh).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "prdrive.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "prdrive.app"
        // 26: java.nio.file, y con él las operaciones atómicas de fichero.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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

    // Los vectores del motor también se prueban en el teléfono
    // (MotorEnAndroidTest): sus expresiones regulares y su formato de números
    // no son los de la JVM.
    sourceSets.getByName("androidTest").resources.directories.add("../engine/src/test/resources")

    packaging {
        // La biblioteca de rclone es un .so por arquitectura y ya va sin
        // símbolos (-s -w): que AGP no intente quitárselos otra vez.
        jniLibs.keepDebugSymbols += "**/libgojni.so"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

// El .aar no se versiona: sin él, la compilación fallaría con un «unresolved
// reference» que no dice qué falta. Se comprueba antes de construir nada (y no
// al configurar, que rompería también `:engine:test`).
val aarDeRclone = rootProject.file("rclone/build/rclone.aar")
tasks.named("preBuild") {
    doFirst {
        if (!aarDeRclone.isFile) {
            throw GradleException("Falta $aarDeRclone: constrúyelo con rclone/construir.sh (ver AGENTS.md).")
        }
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(files(aarDeRclone))

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    // El escáner de códigos de Play services: lee el QR sin que la app pida la
    // cámara.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // La prueba de punta a punta maneja las pantallas de verdad.
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // El Espresso que trae ui-test busca `InputManager.getInstance`, que ya no
    // existe en API 37; el 3.7 (el de androidx.test 1.7) no.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
