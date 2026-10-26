# Merge notes: v1 (1.5.0) + v2 (1.4.1) -> 1.5.1

STATUS: **merged by static reading only. Nothing here has been compiled or run** (the merge environment had no
Kotlin compiler, Android SDK or network). Run `scripts/build.sh test` on a machine with the Android SDK first and fix
whatever the compiler reports; expect a few small errors.

## Source of each part
| Area | From |
|---|---|
| Whole `files/` package (store, indexer, workers, CLIP engine/store/search/ranking, Office extractor, query normalizer, `FileSearch`) | v2 |
| `FileAiActivity`, keyboard service | v1, patched to the v2 count + FTS search API |
| `LocalFileSearch` | v2 + v1 identifier exact-match and stored-signal fallback |
| `ClipModelManager` / model verifier / `ModelManifest` | v1 (trust-on-first-use) + pinned revision + resumable download |
| Play/full flavors, signing, backend hardening, Hindi layout, dictation formatter, Redactor, install ID | v1 |
| Kotlin pin, CI, Dockerfile | v2 (adapted) |
| Gemma / MediaPipe / `domain/search` | removed |

## Edits made during the merge (review these first)
1. `AkzsaInputMethodService`: `indexedPhoneFiles` now holds only the latest matches; new `indexedPhoneFileCount`.
2. `FileAiActivity`: same pattern (`indexedFileCount`), new "download on mobile data" button.
3. `ClipEngine.newSession`: integrity check re-added (`ClipModelManager.requireIntact`).
4. `LocalFileIndexer.hasFullSharedStorageAccess`: Play-flavor gate re-added.
5. `ClipModelDownloadWorker`: HTTP Range resume; `ensureDownload(allowMobileData)`.
6. `ModelManifest`: revision `99ca0cde6c76c0c56fcfeace07eb3a340ba17fc0`; Gemma entry removed.

## Still not done / unverified
- **Model hashes are NOT pinned.** v2 documented SHA-256 values for the vision and text files
  (vision `13d20ebf...c8a`, text `4eac03c0...cfee`). They were not re-verified here and are not used, because a wrong
  value would make the download fail. Run `scripts/pin-model-hashes.sh` on a trusted machine, compare, then pin all three.
- ~~Photo indexing waits up to 8 minutes for the OCR scan~~ - removed in 1.5.2 (see CHANGELOG).
- `AkzsaInputMethodService.kt` (about 1,700 lines) and `LocalFileIndexer.kt` are still very large.
- Unit tests for the merged code were not executed. `SearchRegressionCorpusTest` should be re-run: `LocalFileSearch` changed.
- The shared-token authentication weakness in the backend is unchanged.

---

# 1.5.2: merge with the "Production v1" zip

STATUS: **again merged by static reading only; nothing compiled or run.** Checked here: bracket/string balance of every Kotlin file, the IndexPolicy / MimeResolver test expectations and the OCR shortcut re-implemented in Python, and that the junk filter hides none of the 20 corpus files. The Production zip itself reports 138 pure-logic Kotlin tests and 42 backend tests passing in its own sandbox (`reports/` there); the backend in this tree is byte-identical to it.

Files touched (review these first when Gradle reports errors):
| File | Change |
|---|---|
| `files/IndexPolicy.kt`, `MimeResolver.kt`, `FileOpener.kt` | new, copied from Production (caps raised to 50,000 / 200,000) |
| `files/LocalFileIndexer.kt` | IndexPolicy ordering + skip rules in 2 walks, MimeResolver, junk skip in the single-file path, cancellation re-thrown, Devanagari-skip shortcut |
| `files/LocalFileSearch.kt`, `ClipSearch.kt` | search-time junk filter |
| `files/ClipImageIndexWorker.kt` | no OCR gate, junk skip, shorter rests |
| `files/ClipEngine.kt` | 2-4 inference threads |
| `files/FileAiActivity.kt`, `keyboard/AkzsaInputMethodService.kt` | open through `FileOpener` (+ import) |
| `app/build.gradle.kts` | `full` flavor id, versionCode 8 / 1.5.2 |
| `scripts/build.sh`, `build.ps1` | debug/test build the `full` flavor |
| `test/.../IndexPolicyAndMimeTest.kt` | new, from Production |

---

# 1.6.0: voice upgrade (on top of 1.5.2)

STATUS: **not compiled, not run.** Checked here: syntax balance of every Kotlin file; the audio pipeline (filter, pause detection, noise rejection) re-implemented line by line in Python and run on 10 scenarios (values used in `AudioDspTest`); the dictionary data (591 entries, no conflicts, test sentences verified against it); the 12 server prompt/guard tests actually executed (`python3 -m unittest tests.test_voice_prompt`). The new endpoint tests in `test_voice_transcription.py` and every Kotlin test are written but NOT executed.

| File | Change |
|---|---|
| `voice/AudioDsp.kt`, `VoiceCapture.kt`, `StreamingDictationSession.kt` | new: filter, pause detection, microphone, live session |
| `keyboard/HinglishDictionary.kt`, `UserDictionary.kt`, `SpokenPunctuation.kt`, `TranscriptCleaner.kt` | new |
| `keyboard/AkzsaInputMethodService.kt` | live session start/stop/cancel, cleaner in `commitDictation`, dictionary hints |
| `keyboard/HinglishTransliterator.kt`, `DictationFormatter.kt` | dictionary first; newlines kept |
| `network/BackendClient.kt` | `transcribeSegment` |
| `settings/SettingsRepository.kt`, `ui/AkzsaApp.kt` | 3 new switches, bigger dictionary field |
| `server/app/voice_prompt.py`, `main.py`, `asr_guard.py` | prompt builder, WAV, context header, limits |
| tests | `AudioDspTest`, `HinglishDictionaryTest`, `UserDictionaryTest`, `SpokenPunctuationTest`, `TranscriptCleanerTest`, `DictationFormatterNewlineTest`, `test_voice_prompt.py`, 3 endpoint tests |
