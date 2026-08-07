// Top-level build file. Per-module config lives in app/build.gradle.kts.
//
// No Kotlin Android plugin applied: AGP 9's built-in Kotlin support compiles Kotlin
// sources without it (see https://kotl.in/gradle/agp-built-in-kotlin). The line below
// pins the actual Kotlin compiler AGP uses under the hood so it matches the Compose
// compiler plugin version applied in app/build.gradle.kts (they must be the same version).
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
}
