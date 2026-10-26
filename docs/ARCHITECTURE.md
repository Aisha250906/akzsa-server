# Architecture

```
Android app (single process)
 ├─ keyboard/  AkzsaInputMethodService (View-based IME)  ── policies: SensitiveFieldPolicy, KeyboardLayoutPolicy,
 │             DictationFormatter, HinglishTransliterator, DevanagariLayout, KeyLabels, KeyboardInsets
 ├─ files/     LocalFileIndexer → PrivateFileIndexStore (SQLite) → LocalFileSearch (exact/OCR/PDF/labels)
 │             └ semantic: ClipSearch (MobileCLIP2 ONNX)  |  LocalSemanticSearch (EmbeddingGemma, opt-in)
 ├─ domain/search/  HybridRankFusion (RRF), FileHitFusion, EmbeddingProviderPolicy  (pure Kotlin)
 ├─ recognition/    ModelManifest, ModelArtifactVerifier, VectorCompatibility        (pure Kotlin)
 ├─ network/   BackendClient (OkHttp)  →  server/app/main.py (FastAPI) → Groq (Whisper + LLM)
 ├─ ui/        Compose app: onboarding, home, settings, privacy, diagnostics (Graphite Signal theme)
 └─ design/    GraphiteSignal tokens
```
**Data flow rules.** Files and their content never leave the phone (index, OCR, labels, embeddings are local). Only a transcript/selected text (commands) or an audio clip (Cloud Dictation) is sent, only after a user action, never from protected fields. Search ranking = lexical + at most one semantic provider, combined by rank fusion; lexical always works with models off.
**Why the IME package was not renamed:** `com.nefeon.akzsa.keyboard.AkzsaInputMethodService` is the component name Android stores when a user enables the keyboard; renaming would silently disable it for existing users.

## ADR-1 Rank fusion instead of max()
`max(lexicalPoints, cosine)` compares unrelated scales. Weighted RRF (k=60; lexical 1.0, semantic 0.8, photo 0.8) uses ranks only, deterministic tie-break (recency, id). Weights are provisional; tune on a device-measured corpus.
## ADR-2 One embedding provider at a time
Default MobileCLIP2-S0 (~107 MB). EmbeddingGemma (~440M params) only if opted in, installed, not crash-disabled, not low-RAM, >=6 GB (provisional). Both loading together risks OOM-killing the keyboard.
## ADR-3 Model integrity
Pinned SHA-256 where known (Gemma). Otherwise trust-on-first-use: hash recorded at download, re-verified once per process before the ONNX session is created; mismatch deletes the file. Vectors carry `id@revision/dim` in their fingerprint so a model change re-embeds.
## ADR-4 play / full flavors
Play rejects broad storage access for most apps; `play` removes `MANAGE_EXTERNAL_STORAGE` and uses SAF folder picks. `full` keeps all-files for sideload.
## ADR-5 Hinglish = Hindi recogniser + local romanisation
English recognisers mangle Hindi words; Hindi recognisers return Devanagari. Romanising locally gives Hinglish text with no extra network call.
## ADR-6 Keyboard sizing from measurements
`KeyboardLayoutPolicy` takes width, height, font scale and user height preference; no device/brand checks.
