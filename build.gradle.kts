// Nada en la raíz salvo la versión de los plugins: cada módulo declara lo suyo.
//
// AGP 9 trae Kotlin integrado (no se aplica `kotlin("android")`), y compila con
// el plugin de Kotlin que haya en el classpath: el que se declara aquí, el mismo
// que usa `engine`.
plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.android.application") version "9.4.1" apply false
}
