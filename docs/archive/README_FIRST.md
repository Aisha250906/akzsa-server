# AKZSA AI Search — Continuation v4 repair package

## What was corrected in this package

1. **Kotlin metadata mismatch:** the Android project uses Kotlin Gradle Plugin `2.4.21`, which understands Kotlin metadata `2.4.0`. The older error saying the compiler expected metadata `2.2.0` means the project actually being built still had an older Kotlin compiler/plugin somewhere; check that PowerShell is pointing to this extracted project or run the supplied repair script against the intended project path.
2. **LiteRT-LM runtime:** `tasks-retrieval` brings LiteRT-LM in transitively. The app Gradle file now pins `com.google.ai.edge.litertlm:litertlm-android` to stable `0.18.0` rather than allowing an older `0.17.0-alpha1` runtime to win dependency resolution.
3. **Vector dimension:** `LocalSemanticSearch.kt` passes `768` as the third positional argument, matching the unified dimension of the selected EmbeddingGemma 2 Text+Vision model.
4. **Build path:** the repair script applies the same compatibility settings, runs unit tests, then assembles the debug APK and packages the source if the build succeeds.

The model/runtime choice is consistent with Google's current MediaPipe Universal Embedder and Semantic Retriever guides, which describe `.litertlm` models and state that Tasks Retrieval uses LiteRT-LM. See `UPGRADE-ROADMAP.md` for links and what still needs device testing.

## Build on Windows

Requirements: Android Studio/Android SDK Platform 36 and Build Tools installed, JDK 17 or 21, PowerShell, and internet access for Gradle/Maven dependencies.

From this extracted package folder:

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force
.\build-akzsa-ai-search.ps1
```

To repair/build a different extracted project, pass the folder containing `app\`, `settings.gradle.kts`, and the top-level `build.gradle.kts`:

```powershell
.\repair-and-build-akzsa-continuation-v3.ps1 -ProjectRoot "C:\Users\rosha\Downloads\AKZSA-AI-Search-Continuation-v2\AKZSA-AI-Search-Continuation-v2\AKZSA-Android"
```

The script backs up source files it changes. On success, expected outputs are in this package's `output` folder: `AKZSA-AI-Search-debug.apk` and `AKZSA-AI-Search-Continuation-FIXED-SOURCE.zip`.

## Important

This is a **patched source ZIP, not a verified APK**. The reported `jvmTarget: String` compiler error is addressed by migrating to the typed `compilerOptions` DSL. The authoring environment does not have an Android SDK and cannot resolve Android/Maven dependencies, so the full Android build has not been executed here. Do not assume runtime behavior is confirmed until the Windows build and the device checks in `UPGRADE-ROADMAP.md` pass.
