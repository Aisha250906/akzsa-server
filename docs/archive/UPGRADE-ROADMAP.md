# AKZSA upgrade roadmap (priority order)

This roadmap separates build blockers from product improvements. Fix and verify P0 before adding more search features.

## P0 — Must pass before trusting this build

1. **Rebuild from the extracted v4 ZIP on Windows.** Use JDK 17 or 21, Android SDK Platform 36, Build Tools, and an internet connection for Gradle/Maven dependency resolution. Run `Set-ExecutionPolicy -Scope Process Bypass -Force` then `./build-akzsa-ai-search.ps1`. Confirm both `:app:testDebugUnitTest` and `:app:assembleDebug` succeed. The first Kotlin/Gradle error matters more than the final `BUILD FAILED` line.
2. **Confirm dependency resolution.** In `AKZSA-Android`, run `gradlew.bat :app:dependencyInsight --dependency litertlm-android --configuration debugRuntimeClasspath` and confirm `com.google.ai.edge.litertlm:litertlm-android:0.18.0` wins resolution. If dependency insight still shows `0.17.0-alpha1`, stop and fix the dependency graph before device tests.
3. **Check the 768-dimensional vector store.** Keep the embedding model, text embeddings, image embeddings, query embeddings, and SQLite vector dimension aligned at 768. If a future model uses a different dimension, migrate/reset the vector database deliberately instead of silently reusing the old schema.
4. **Test actual model initialization.** Install on a physical Android device; connect to unmetered Wi-Fi; start File AI; verify the model download completes, checksum passes, model initializes without a crash, and the download/index progress UI reaches completion. Keep enough free disk space for the roughly 388 MB model plus the vector DB and working files.
5. **Test representative searches.** Index a photo, screenshot, image containing text, text document, searchable PDF, scanned PDF, and a file with a non-English filename. Try exact filename and meaning-based queries, then confirm results open the right file. Compare with AI model disabled to ensure lexical fallback still works.

## P1 — Reliability and privacy

- Exercise interrupted/failed model downloads and confirm resume, checksum rejection, retry and user-facing error states.
- Verify added, changed, renamed, moved, and deleted files are reflected in both the regular index and semantic vector database.
- Test PDFs with text layers versus scanned pages; check OCR language support and long-document truncation/chunking.
- Measure cold model load, first query latency, repeated query latency, RAM, battery and storage usage on low/mid/high-memory devices.
- Review broad-storage permission (`MANAGE_EXTERNAL_STORAGE`) and Android 16/Google Play policy. Prefer a clear Storage Access Framework folder-selection experience if broad access is not appropriate or not approved.
- Verify local file content, OCR, paths, and embeddings stay on-device and are never sent to the AKZSA backend.

## P2 — Quality upgrades after stability

- Add a small automated regression corpus with expected top results for semantic image search, screenshot text, PDF search, and multilingual filenames.
- Tune the `MIN_SIMILARITY` threshold using real device data instead of guessing; track false positives and missed matches.
- Make indexing incremental and cancellable so large libraries do not repeatedly re-embed unchanged files.
- Add model versioning, migration rules and an explicit “rebuild semantic index” action for future model upgrades.
- Add language-specific OCR packs and evaluate Kannada/Hinglish support independently from semantic model quality.
- Add diagnostics export (with private text/path data omitted by default) for build/runtime errors.

## Official references

- Kotlin release compatibility/version history: https://kotlinlang.org/docs/releases.html
- MediaPipe Universal Embedder Android setup and `.litertlm` usage: https://developers.google.com/edge/mediapipe/solutions/retrieval/universal_embedder/android
- MediaPipe Semantic Retriever Android setup and local vector storage: https://developers.google.com/edge/mediapipe/solutions/retrieval/semantic_retriever/android
- LiteRT-LM v0.18.0: https://github.com/google-ai-edge/LiteRT-LM/releases
- EmbeddingGemma 2 shared 768-dimensional embedding space: https://ai.google.dev/gemma/docs/embeddinggemma/model_card_2


## Kotlin DSL / Gradle repair (v5)

The Android module must not use `kotlinOptions { jvmTarget = "17" }` with the current Kotlin Gradle plugin. Use the typed DSL in a top-level block:

```kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
    }
}
```

Run `fix-and-build-akzsa.ps1` or `build-akzsa-ai-search.ps1` from the extracted package root. The script uses the project's own Gradle 8.14.4 bootstrapper and creates `output\AKZSA-AI-Search-debug.apk` only if Gradle succeeds. A successful source patch alone does not prove model inference works on a device.
