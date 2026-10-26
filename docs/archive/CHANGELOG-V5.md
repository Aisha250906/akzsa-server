# AKZSA Continuation v5 — Kotlin DSL build fix

- Removed the deprecated/erroring `android { kotlinOptions { jvmTarget = "17" } }` configuration.
- Added a top-level typed Kotlin `compilerOptions` block targeting JVM 17.
- Updated the custom Gradle bootstrapper and wrapper properties to Gradle 8.14.4.
- Added `fix-and-build-akzsa.ps1` as the canonical idempotent repair/build script.
- Redirected the existing build entry points to the canonical script so they do not select the unrelated external Gradle 8.13 install.

The ZIP includes source changes only. The Android APK build still needs to be run on the user's Windows machine with Android SDK Platform 36 and Build Tools installed.
