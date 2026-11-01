// AGP 9 provides built-in Kotlin support. We intentionally do not apply
// org.jetbrains.kotlin.android; instead, AGP owns the Android Kotlin integration.
// Override AGP's default KGP with the Kotlin version selected for this project.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
