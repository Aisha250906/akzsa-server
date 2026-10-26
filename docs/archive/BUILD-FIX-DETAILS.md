# Build-fix details

## Applied in this source package

- Kotlin Android, Compose, and serialization plugins: `2.4.21`.
- Android Gradle Plugin: `8.13.0`; configured Gradle distribution: `8.14.4`.
- Transitive LiteRT-LM override: `com.google.ai.edge.litertlm:litertlm-android:0.18.0`, the stable release that includes EmbeddingGemma 2 support.
- Vector database constructor: `SqliteVectorStore(context.applicationContext, VECTOR_DB, 768)`.
- Kotlin compiler setting migrated from the rejected `kotlinOptions { jvmTarget = "17" }` string API to `kotlin { compilerOptions { jvmTarget.set(JvmTarget.fromTarget("17")) } }`.
- Custom Gradle bootstrap and wrapper properties updated to Gradle `8.14.4`; build scripts no longer prefer the unrelated external Gradle 8.13 installation.
- Repair script now applies the LiteRT-LM compatibility pin as well as the Kotlin plugin and vector-dimension fixes when directed at another source project.

## Why

The reported compiler message, “binary metadata version 2.4.0; compiler expected 2.2.0,” indicates that an older Kotlin compiler/plugin was being used against a newer Kotlin-compiled dependency. The plugin and the compiler actually used by Gradle must agree; editing only a Kotlin source file cannot solve this.

The source project uses MediaPipe Tasks Retrieval, which depends on LiteRT-LM. Pinning the transitive runtime avoids resolving an older prerelease while this app loads the EmbeddingGemma 2 `.litertlm` model. The selected model's shared embedding space is 768 dimensions, so the vector-store dimension must match it.

## References checked 18 October 2026

- Kotlin releases: https://kotlinlang.org/docs/releases.html
- MediaPipe Universal Embedder for Android: https://developers.google.com/edge/mediapipe/solutions/retrieval/universal_embedder/android
- MediaPipe Semantic Retriever for Android: https://developers.google.com/edge/mediapipe/solutions/retrieval/semantic_retriever/android
- LiteRT-LM releases (v0.18.0): https://github.com/google-ai-edge/LiteRT-LM/releases
- EmbeddingGemma 2 model card: https://ai.google.dev/gemma/docs/embeddinggemma/model_card_2

## Not yet proven

No real Android build or emulator/device inference test was run in the authoring environment. A source-level patch is not proof of a successful Gradle dependency graph, APK assembly, model download, or on-device semantic search. Follow the build and device-validation steps in `UPGRADE-ROADMAP.md`.
