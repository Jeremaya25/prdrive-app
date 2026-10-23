// La app: solo dibuja y llama. Lo que decide qué se sincroniza y cómo está en
// `engine` (Kotlin puro, probado sin dispositivo) y lo que sincroniza es rclone,
// que entra como biblioteca en `rclone/build/rclone.aar` (ver
// rclone/construir.sh).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// VERSION, en la raíz, es la única fuente de la versión, como en prdrive: de
// ella salen el versionName, el versionCode y el tag de la release
// (.github/workflows/release.yml publica v<VERSION> cuando un push a main la
// cambia). Con dos sitios, el APK y su release podrían decir cosas distintas.
val versionDeLaApp: String =
    providers.fileContents(rootProject.layout.projectDirectory.file("VERSION")).asText.get().trim()

// El versionCode es mayor·1 000 000 + menor·1 000 + parche: Android solo
// instala encima una versión con un código igual o mayor, y así sube siempre
// que sube la versión. Un sufijo (1.2.0-rc1) no cuenta: 1.2.0 se instala encima
// de su rc porque el código es el mismo. El tope es el de Google Play.
val codigoDeVersion: Int = run {
    val partes = Regex("""([0-9]+)\.([0-9]+)\.([0-9]+)[-.0-9A-Za-z]*""").matchEntire(versionDeLaApp)
        ?: throw GradleException("VERSION ('$versionDeLaApp') no parece una versión. Se esperaba algo como 1.2.3.")
    val (mayor, menor, parche) = partes.destructured.toList().map { it.toLongOrNull() ?: Long.MAX_VALUE }
    if (mayor > 2100 || menor > 999 || parche > 999) {
        throw GradleException(
            "VERSION ('$versionDeLaApp') no cabe en un versionCode: el mayor va hasta 2100, y el menor y el parche, hasta 999.",
        )
    }
    val codigo = mayor * 1_000_000 + menor * 1_000 + parche
    if (codigo !in 1..2_100_000_000) {
        throw GradleException("VERSION ('$versionDeLaApp') da un versionCode ($codigo) fuera de 1..2100000000.")
    }
    codigo.toInt()
}

android {
    namespace = "prdrive.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "prdrive.app"
        // 26: java.nio.file, y con él las operaciones atómicas de fichero.
        minSdk = 26
        targetSdk = 37
        versionCode = codigoDeVersion
        versionName = versionDeLaApp
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // PuntaAPuntaTest necesita un servidor y la carga de su QR
        // (herramientas/punta_a_punta.py). Sin ella se deja fuera: el motor de
        // tests de AGP cuenta un test saltado con `assumeTrue` como fallido, y
        // la batería no saldría nunca en verde.
        if (!providers.gradleProperty("android.testInstrumentationRunnerArguments.carga_b64").isPresent) {
            testInstrumentationRunnerArguments["notClass"] = "prdrive.app.PuntaAPuntaTest"
        }
    }

    // La release sale sin firmar, siempre: la firma se pone después, con
    // apksigner, en un paso aparte del CI que es el único que ve la clave
    // (.github/workflows/release.yml). Así Gradle, sus plugins y los tests nunca
    // la tienen a mano.
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
// La ruta se copia a una variable de la tarea: con la caché de configuración
// (gradle.properties), una acción que mirara la del script no se podría guardar.
val aarDeRclone = rootProject.file("rclone/build/rclone.aar")
tasks.named("preBuild") {
    val aar = aarDeRclone
    doFirst {
        if (!aar.isFile) {
            throw GradleException("Falta $aar: constrúyelo con rclone/construir.sh (ver AGENTS.md).")
        }
    }
}

// A veces el emulador no encuentra la instrumentación recién instalada
// (`INSTRUMENTATION_FAILED: Unable to find instrumentation info`), y AGP lo da
// por bueno con 0 tests. Un verde sin tests no es un verde.
tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }.configureEach {
    val resultados = layout.buildDirectory.dir("outputs/androidTest-results/connected")
    doLast {
        val informes = resultados.get().asFile.walkTopDown().maxDepth(2)
            .filter { it.isFile && it.name.startsWith("TEST-") && it.name.endsWith(".xml") }.toList()
        if (informes.none { !Regex("""<testsuites tests="0"""").containsMatchIn(it.readText()) }) {
            throw GradleException(
                "No se ha ejecutado ningún test: si el filtro está bien, es el emulador, que no ha encontrado " +
                    "la instrumentación recién instalada. Vuelve a lanzarlo.",
            )
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
    // Las pasadas del llavero en segundo plano (llavero/TrabajoLlavero.kt): al
    // guardar la base, al leerla y si algo quedó sin subir, con red y
    // reintentos, también con la app cerrada.
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Un WorkManager de prueba, que encola sin ejecutar (TrabajoLlaveroTest).
    androidTestImplementation("androidx.work:work-testing:2.12.0")
    // La prueba de punta a punta maneja las pantallas de verdad.
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // El Espresso que trae ui-test busca `InputManager.getInstance`, que ya no
    // existe en API 37; el 3.7 (el de androidx.test 1.7) no.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    // «Abrir llavero» lanza un ACTION_VIEW: PantallaLlaveroTest lo intercepta
    // y lo comprueba sin necesitar una app de KeePass en el emulador.
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
