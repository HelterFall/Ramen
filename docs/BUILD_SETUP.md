# Android Build Setup

## Toolchain

The Android app uses the following pinned toolchain:

| Component | Version |
|---|---:|
| Android Gradle Plugin | 9.1.1 |
| Gradle Wrapper | 9.7.0 |
| Kotlin | 2.4.21 |
| Compose BOM | 2026.09.00 |
| Activity Compose | 1.13.0 |
| JDK | 17 |
| Android compile SDK | 37 |
| Android minimum SDK | 26 |

Plugin and library versions are pinned in `gradle/libs.versions.toml`. The Gradle distribution is pinned in `gradle/wrapper/gradle-wrapper.properties`, including the official SHA-256 checksum. The Gradle Wrapper JAR is the upstream Gradle 9.7.0 wrapper artifact.

AGP 9 provides built-in Kotlin support, so the project does not apply `org.jetbrains.kotlin.android`. The root build declares Kotlin Gradle Plugin 2.4.21 explicitly.

## Local prerequisites

Install:

1. JDK 17.
2. Android Studio with Android SDK Platform 37 and the matching build tools.

A separate system-wide Gradle installation is not required. The checked-in Wrapper downloads and runs the pinned Gradle distribution.

## Build commands

Run these commands from the repository root:

On macOS / Linux:

```bash
./gradlew --version
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

On Windows:

```powershell
.\gradlew.bat --version
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

The `Android CI` workflow:

- Checks out the repository with the current checkout action.
- Installs JDK 17.
- Uses Gradle through the committed Wrapper.
- Validates the Wrapper JAR checksum.
- Runs `assembleDebug`, `testDebugUnitTest`, and `lintDebug`.
- Uploads the debug APK when all checks pass.

The workflow uses the basic open-source caching provider. CI does not sign a release build.

## Compatibility note: navigation bar

The app supports API 26 and newer. The explicit `android:windowLightNavigationBar` theme item was removed because it requires API 27 and its explicit `false` value was unnecessary for the dark navigation-bar design.

## Version maintenance

Before changing Android, Kotlin, Gradle, or Compose versions, verify the compatibility tables and stable release notes for all four. When upgrading Gradle, update the Wrapper distribution checksum and Wrapper JAR together. Gradle's official security guidance recommends verifying the distribution SHA-256 checksum:

https://docs.gradle.org/9.7.0/userguide/best_practices_security.html
