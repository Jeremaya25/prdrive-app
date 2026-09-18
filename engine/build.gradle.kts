import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(kotlin("test"))
}

// Bytecode 17, que es lo que consume el módulo de Android (AGP 8.x), pero SIN
// fijar la versión del JDK con el que se compila: así el módulo se construye
// con cualquier JDK 17 o posterior —el del que tenga cada uno y el de CI— en
// vez de exigir que haya uno concreto instalado.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}
