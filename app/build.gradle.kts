import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

fun gitOutput(vararg arguments: String): String? =
    runCatching {
        ProcessBuilder("git", *arguments)
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader()
            .use { it.readText().trim() }
    }.getOrNull()?.takeIf { it.isNotBlank() && it != "HEAD" }

fun sanitizeDebugPart(value: String, fallback: String): String {
    val sanitized = value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
        .ifBlank { fallback }

    return if (sanitized.first() in 'a'..'z') sanitized else "b_$sanitized"
}

val debugBranchName = System.getenv("GITHUB_HEAD_REF")
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv("GITHUB_REF_NAME")
        ?.takeIf { it.isNotBlank() }
    ?: gitOutput("rev-parse", "--abbrev-ref", "HEAD")
    ?: "local"

val debugRunNumber = System.getenv("GITHUB_RUN_NUMBER")
    ?.takeIf { it.isNotBlank() }
    ?: "local"

val debugShortSha = System.getenv("GITHUB_SHA")
    ?.takeIf { it.isNotBlank() }
    ?.take(7)
    ?: gitOutput("rev-parse", "--short", "HEAD")
        ?.take(7)
    ?: "unknown"

val debugBranchId = sanitizeDebugPart(debugBranchName, "branch")
    .take(32)
    .trimEnd('_')
    .ifBlank { "branch" }
val debugBranchLabel = debugBranchId.take(24)
val debugBuildId = sanitizeDebugPart(
    "run_${debugRunNumber}_sha_${debugShortSha}",
    "run_local"
)

android {
    namespace = "io.github.helterfall.ramen"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.helterfall.ramen"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("debug") {
            // Give every CI run a distinct package name so Android keeps prior debug APKs installed.
            applicationIdSuffix = ".dbg_${debugBranchId}_${debugBuildId}"
            resValue("string", "app_name", "Ramen #${debugRunNumber} (${debugBranchLabel})")
        }
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.xz)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
