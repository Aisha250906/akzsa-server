# Build and verification status

Status: **source patched; Android APK build unverified**.

This package contains the native Android source, backend source, build scripts, automatic on-device model download, and local file indexing implementation. The model binary is not embedded in the ZIP or APK: the app downloads it after first launch, verifies SHA-256, then uses it for local embeddings.

Changes included in this iteration:

- Kotlin plugin aligned to `2.4.21`.
- LiteRT-LM transitive dependency pinned to `0.18.0`.
- SQLite vector dimension explicitly set to `768`.
- Build/repair docs updated to distinguish source changes from validation.

What was checked here: ZIP contents and project structure were inspected; Python sources can be syntax-checked separately. What was **not** checked: Android Kotlin compilation, dependency resolution, unit-test execution, APK assembly, installation, model inference, file permissions, and semantic retrieval on a real Android device. The authoring environment has no Android SDK and cannot resolve Android/Maven dependencies from its network environment.

Run `build-akzsa-ai-search.ps1` on Windows with Android SDK Platform 36, Build Tools, JDK 17/21, and internet access. Review `UPGRADE-ROADMAP.md` for the required device smoke tests and Android storage/Play policy checks.
