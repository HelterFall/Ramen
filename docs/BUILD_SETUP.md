# Android Build Setup

## Toolchain

The initial Android app scaffold uses:

| Component | Version |
|---|---:|
| Android Gradle Plugin | 9.1.1 |
| Gradle | 9.7.0 |
| Kotlin | 2.4.21 |
| Compose BOM | 2026.09.00 |
| Activity Compose | 1.13.0 |
| JDK | 17 |
| Android compile SDK | 37 |
| Android minimum SDK | 26 |

The versions are pinned in `gradle/libs.versions.toml`. AGP 9 provides built-in Kotlin support, so the project does not apply `org.jetbrains.kotlin.android`. The root build declares Kotlin Gradle Plugin 2.4.21 explicitly to use a Kotlin version compatible with the selected Gradle release.

## Local prerequisites

Install:

1. JDK 17.
2. Android SDK Platform 37 and the matching build tools through Android Studio's SDK Manager.
3. Gradle 9.7.0.

The first scaffold does not yet include the Gradle Wrapper JAR. Until it is added, use a local Gradle 9.7.0 installation (or configure Android Studio to use that Gradle installation).

## Build commands

Run these commands from the repository root:

```bash
gradle --version
gradle assembleDebug
gradle testDebugUnitTest
gradle lintDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

The `Android CI` workflow installs JDK 17 and Gradle 9.7.0, then runs:

- `assembleDebug`
- `testDebugUnitTest`
- `lintDebug`

A successful build uploads the debug APK as a short-lived workflow artifact. CI does not sign a release build.

## Version maintenance

Before changing Android, Kotlin, Gradle, or Compose versions, verify the compatibility tables and stable release notes for all four. Avoid upgrading one component in isolation.
