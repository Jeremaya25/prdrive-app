// Nada en la raíz salvo la versión de los plugins: cada módulo declara lo suyo.
plugins {
    kotlin("jvm") version "2.1.20" apply false
    kotlin("android") version "2.1.20" apply false
    // El compilador de Compose va como plugin de Kotlin desde 2.0, y su
    // versión es la de Kotlin: no hay una tercera que cuadrar.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("com.android.application") version "8.13.2" apply false
}
