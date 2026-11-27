val debugBranchName: String = run {
    // GitHub uses GITHUB_HEAD_REF for pull requests (GITHUB_REF_NAME is "123/merge").
    System.getenv("GITHUB_HEAD_REF")?.takeIf { it.isNotBlank() }
        ?: System.getenv("GITHUB_REF_NAME")?.takeIf { it.isNotBlank() }
        ?: runCatching {
            val process = ProcessBuilder(
                "git",
                "-C",
                rootProject.projectDir.absolutePath,
                "branch",
                "--show-current",
            ).redirectErrorStream(true).start()
            val branch = process.inputStream.bufferedReader().use { it.readText().trim() }
            if (process.waitFor() == 0) branch.takeIf { it.isNotBlank() } else null
        }.getOrNull()
        ?: "local"
}

val debugApplicationIdSuffix: String = if (debugBranchName == "main") {
    ".debug"
} else {
    // Keep the suffix readable and package-name safe. The hash prevents different
    // branch names from colliding after normalisation (for example, "a-b" and "a/b").
    val branchSlug = debugBranchName.lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .take(40)
        .trimEnd('_')
        .ifBlank { "branch" }
    val packageSafeSlug = if (branchSlug.first() in 'a'..'z') {
        branchSlug
    } else {
        "branch_$branchSlug"
    }
    val branchHash = java.security.MessageDigest.getInstance("SHA-256")
        .digest(debugBranchName.toByteArray(Charsets.UTF_8))
        .take(4)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    ".$packageSafeSlug_$branchHash"
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.helterfall.ramen"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.helterfall.ramen"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = debugApplicationIdSuffix
            manifestPlaceholders["appLabel"] = "Ramen [$debugBranchName]"
        }
    }

    buildFeatures {
        compose = true
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

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
