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
    }

    // Match the official Ren'Py Android packaging setup. SDLActivity loads
    // native libraries by name, and the runtime probe verifies nativeLibraryDir.
    // Legacy packaging ensures JNI libraries are extracted on installation.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Ren'Py reads its private runtime tarball as an asset stream and may also
    // access packed game files through APK offsets.
    androidResources {
        noCompress += listOf("mp3", "rpyc", "rpymc", "rpyb")
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
