# Changelog

## 1.7.0 (versionCode 11) - AKZSA Writing Mode improvements

- Added a Writing Mode screen to the existing home screen without replacing existing keyboard, voice, or File AI functionality.
- Added one-tap grammar, professional, friendly, shorter, longer, and translation actions backed by `/v1/writing/generate`.
- Added three reply suggestions with explicit selection, an editable before/after preview, retry, cancel, and original-text restore.
- Added local reusable templates and locally stored tone/output-language preferences with reset.
- Added spoken editing instructions and preview-only natural-language edits.
- Added Android `ACTION_PROCESS_TEXT` integration so supported apps can pass selected text to Writing Mode and receive the reviewed result, plus `ACTION_SEND` support for shared text. Read-only selections fall back to copy.
- Fixed the backend structured-output schema so only reply-generation requests require exactly three suggestions; other actions can return an empty suggestions array as requested.
- Added request validation for empty voice-edit instructions and regression tests for action-specific response schemas.
- Bumped versionCode to 11 so the new build can update an installed v1.6.1 build.
- Verification: backend suite and XML/reference checks pass. A full Android Gradle build and physical-device test remain unverified in this environment because the Gradle distribution cannot be downloaded and no Android SDK is configured.

## 1.6.1 (versionCode 10) - first real compile
- **Fixed the only Kotlin error the first Gradle build reported:** `ClipTokenizer.kt` used `Locale.ROOT` without `import java.util.Locale` (code inherited from the 1.4.x line). A scan of every other Kotlin file found no further missing imports.
- Everything else in the first build (resources, manifest, dependency resolution, dexing steps before Kotlin) completed on a real Windows machine (JDK 21, Gradle 8.14.4, Kotlin 2.3.21), so the Gradle wrapper checksum and plugin versions are confirmed to work.
- `AKZSA-Build.ps1`: shows clean error text, auto-repairs this kind of missing `Locale` import in an unpacked copy, and names the APK after its version.
- Still not confirmed: the APK itself, the unit tests, and everything on a phone.

## 1.6.0 (versionCode 9) - voice upgrade: live dictation, noise handling, Hinglish dictionary (NOT YET COMPILED)

### Live ("streaming") cloud dictation
- Plain cloud dictation now works phrase by phrase: the microphone is read continuously, every pause ends a phrase, the phrase is sent and inserted (in order) while you keep talking. Text appears about a second after each pause. It is near-live, not word-by-word.
- Safety: falls back to the old record-then-upload path if the microphone cannot be opened or no token is saved; command/writing/File AI modes keep their old paths; a failed phrase is retried once; auto-stop after 25 s without speech and after 10 minutes; Settings > "Live cloud dictation" turns it off.
- New classes: `voice/StreamingDictationSession`, `voice/VoiceCapture`, `voice/AudioDsp` (+ `SpeechEndpointer`), `BackendClient.transcribeSegment`.

### Noise handling (cloud dictation)
- 16 kHz mono capture from the VOICE_RECOGNITION source, the phone's NoiseSuppressor when available, a 100 Hz high-pass filter (wind, handling noise, hum), quiet-speech boost (up to +18 dB, never attenuates).
- Speech detection follows the room: threshold = 3x the quietest level of the last 3 s, so a fan or AC already running is not speech. Bursts shorter than 0.3 s (door, cough, click) are dropped, so Whisper is never asked to transcribe noise-only audio. 300 ms pre-roll keeps word beginnings.
- Settings > "Noise reduction" turns the filter/suppressor/boost off.

### Hinglish
- New built-in `HinglishDictionary` (about 590 entries): English words that Hindi engines write in Devanagari go back to English ("इनवॉइस" -> "invoice", "मीटिंग" -> "meeting", "व्हाट्सएप" -> "WhatsApp") and the most common Hindi words get their usual typed spelling ("नहीं" -> "nahi"). Lookup ignores nukta and candrabindu/anusvara differences. Everything else still uses the rule-based converter.
- Server: the Hinglish prompt now contains a Roman-letter example sentence (Whisper copies the script/style of its prompt). `AKZSA_HINGLISH_LANGUAGE=hi` is an opt-in A/B switch (default: auto-detect, as before).
- Session context: the end of what was already dictated is sent with each phrase as the end of the Whisper prompt, so spelling, script and style stay consistent between phrases.

### Accuracy / dictionary
- Personal dictionary (Settings) is now a real dictionary: a plain term (`NEFEON`) is a recognition hint and its spelling/capitals are enforced; `nefion = NEFEON` (also `=>` and `->`) corrects a word that is often heard wrongly. Whole-word, case-insensitive, local. Only correct spellings are sent to the server. Limit raised from 1,200 to 4,000 characters.
- Spoken punctuation (English, Hindi, Hinglish): "comma", "full stop", "question mark", "exclamation mark", "colon", "semicolon", "new line", "new paragraph", "open/close bracket". Settings > "Spoken punctuation".
- `DictationFormatter` keeps intentional newlines. `HinglishTransliteratorTest` now expects "invoice" (it previously documented the limitation as "invois").

### Server
- WAV bodies are forwarded with the right file name/type; `X-AKZSA-Context-B64` header; verbatim repeats of the session context are rejected like prompt echoes; prompt logic moved to `app/voice_prompt.py` (pure Python, 12 tests); default voice limits raised 10 -> 40 per install and 30 -> 120 per IP per minute (live dictation sends one request per phrase).

## 1.5.2 (versionCode 8) - merge with Production v1 device fixes + faster OCR / photo AI (NOT YET COMPILED)

### Merged from the Production v1 zip (fixes that were reported on a real phone)
- **Open file jumped to the Photos/Gallery home screen.** New `FileOpener`: images/videos/audio open through their MediaStore URI, documents through the FileProvider; a moved/deleted file is reported instead of launching an app that cannot show it; a chooser appears when no default app exists. Used by `FileAiActivity` and the keyboard.
- **Indexing missed documents / indexed junk.** New `IndexPolicy`: Documents/Download are visited before Pictures/Screenshots/WhatsApp, then other folders, then DCIM/video/music, then Android. Hidden folders and files (`.thumbnails`, `.Trash`, `.trashed-*`, `.nomedia`, `*.tmp`, `*.crdownload`...) are skipped in the phone scan, the folder-picker scan and the single-file update path, and are also hidden at search time (text search and photo search) for rows older builds had already indexed. Photo AI no longer embeds such images.
- **Wrong file types.** New `MimeResolver`: videos, audio, zip, apk, office files get proper types instead of `application/octet-stream`.
- **Scans that would not stop.** Extraction, image analysis, OCR and PDF OCR no longer swallow coroutine cancellation.
- **Build flavors.** The `full` flavor no longer adds `.full` to the application id, so `assembleFullDebug` installs over earlier AKZSA debug builds (keyboard stays enabled, all-files permission and index are kept). `scripts/build.*` build `full` by default; release scripts still build `play`.
- Added `IndexPolicyAndMimeTest` (from the Production zip).

### Faster, without removing features
- **Photo AI no longer waits for the OCR scan.** It starts immediately (newest photos first) and rests longer between photos only while OCR is still running. `ThermalGuard` still pauses it when the phone is warm.
- Rest between photos 250 ms -> 30 ms (120 ms while OCR runs). CLIP threads: fixed 2 -> half of the CPU cores, between 2 and 4.
- **Devanagari OCR pass skipped on long, clean English pages** (>= 250 characters, >= 180 letters, no Indic characters, almost no stray symbols). Short, noisy or mixed-script pages still get both passes. Trade-off: a bilingual page with a long clean English part may lose its Hindi text; set `LATIN_ONLY_MIN_CHARS` to `Int.MAX_VALUE` in `LocalFileIndexer` to always run both passes again.

### Deliberately NOT taken from the Production zip
- Gemma / MediaPipe semantic stack (`LocalSemanticSearch`, `SemanticEmbeddingIndexWorker`, `SemanticModelDownloadWorker`, `domain/search/*`) - already removed in 1.5.1 (needs about 6 GB RAM in the keyboard process, never measured).
- Kotlin plugin 2.4.21 (kept 2.3.21 to match AGP 8.13's R8), the Gemma/MediaPipe ProGuard rules (kept the ML Kit/PDFBox rules), and the 25,000-file cap (kept 50,000 / 200,000 visited).

### Not changed (still open)
- OCR image size (3,000 px), 5 embeddings per photo, 30-page PDF OCR cap, one file at a time. Measure on a real phone before tuning these.

## 1.5.1 (versionCode 7) - v1 + v2 merge, file/photo pipeline rebuilt (NOT YET COMPILED)

### Fixed - file and photo pipeline (taken from v2, adapted to v1)
- **Search loaded every document's full OCR text into memory on each query** (`allFiles()`), including from inside the keyboard process. Search now uses bounded SQLite FTS candidates (`searchCandidates`) and loads only the matching rows (`findByIds`).
- **Index capped at 5,000 files / 10,000 characters per file / 6 PDF pages**; now 50,000 files / 200,000 characters / 30 PDF pages.
- **Rotated phone photos were OCR'd sideways** (EXIF orientation ignored): orientation is now applied; OCR bitmap width raised from 1,350-1,600 px to 3,000 px (small print on receipts/invoices was unreadable).
- **Office files**: all worksheets and slides are read (v1 read only `sheet1` and slides 1-5) via `OfficeTextExtractor`.
- **Photo search** now embeds the full frame plus four quadrant tiles per photo (v1 centre-cropped and lost subjects near the edges), with `ClipRanking` for scoring.
- **Hinglish / Hindi / Kannada / Tamil / Telugu / Bengali / Urdu queries** map to English image labels and concepts (`MultilingualQueryNormalizer`).
- GSTIN / invoice number / amount are extracted once at index time instead of on every query (v1 still falls back to the text for old records).
- Photo index and file index are fused with rank-based fusion (RRF) instead of mixed score scales.

### Fixed - photo model download
- **Download restarted from zero after every dropped connection**: now resumes with HTTP Range.
- **Model could never download on mobile data**: Wi-Fi remains the default, a new button allows an explicit mobile-data download.
- Model URLs now point at an immutable Hugging Face revision instead of `main`.

### Kept from v1
- Play / full flavors, ALL_FILES_ACCESS gate, consent-first startup, model integrity verifier (trust-on-first-use), identifier exact-match scoring, backend hardening, Hindi layout, dictation formatter.

### Removed
- Gemma / EmbeddingGemma semantic stack (MediaPipe `tasks-retrieval`, LiteRT-LM): needed about 6 GB RAM inside the keyboard process, was never resolved or measured, and `domain/search/*` fusion code that only served it.

### Build / project
- Kotlin plugins pinned to 2.3.21 (2.4.x needs a newer R8 than AGP 8.13.0 bundles).
- Added `androidx.exifinterface`, Gradle wrapper SHA-256, ML Kit / PDFBox ProGuard rules.
- Added GitHub Actions CI and a non-root Dockerfile.

## 1.5.0 (versionCode 6) - production hardening

### Fixed - voice text
- **Dictation glued onto previous text** ("Hellohow are you"): new `DictationFormatter` adds the leading space, avoids double spaces, keeps punctuation attached, never adds spaces in e-mail/URL/number fields.
- **First word never capitalised** in sentence-case fields: now follows the field's caps mode (`getCursorCapsMode`).
- **Speech lost on "no match" / timeout**: the last partial hypothesis is now inserted instead of discarded.
- **Recogniser stopped at the first short pause**: silence-length hints added (best effort; providers may ignore).
- **Cloud English could insert the prompt text itself** (Whisper echoes `prompt` on quiet audio): English prompt is now glossary-only; an echo guard and subtitle-boilerplate guard return 422 instead of inserting garbage.
- Hindi/Kannada offline "language not supported" errors now explain what to do; rate-limit error added.

### Fixed - Hindi / Hinglish
- Hinglish on-device used `en-IN` (garbles Hindi words). Now Hindi engine + local Devanagari->Roman transliteration (`HinglishTransliterator`); cloud results containing Devanagari are romanised too.
- **New Hindi (Devanagari) typing layout** (consonants, matras, vowels, conjunct keys, halant, danda) with an `अ/A` toggle.

### Fixed - keyboard
- No way to leave the keyboard: added a next-keyboard / picker key.
- No auto-capitalisation, no caps lock (double-tap Shift): added.
- Fixed 23-43 dp rows and 26-31 dp buttons: now 44-56 dp key rows, 48 dp toolbar/actions via `KeyboardLayoutPolicy` (width/height/font-scale/profile based, never device model); width bounded on tablets/landscape; insets handled.
- Files mode stacked a big panel above the full keyboard and **wiped results on any re-render**: results now persist, the results surface replaces the letter keys, with `Edit query` and `Back to typing`.
- Password check missed OTP/PIN/CVV fields and incognito editors: new `SensitiveFieldPolicy` (voice, cloud, AI and file search all off).
- Screen-reader labels for symbol keys; text sizes no longer 9.5-10.5 sp.

### Fixed - search / privacy / build
- `max(lexical, semantic)` score merging replaced by weighted Reciprocal Rank Fusion; at most one embedding model active (`EmbeddingProviderPolicy`).
- Score capped at 100 made strong matches tie (wrong invoice ranked first) - cap raised; exact-identifier boost added.
- Invented "Match score: N/100" removed from the UI.
- MobileCLIP model had **no integrity check** (size only): now hash-recorded, verified before load, atomic promote; corrupt files are deleted and re-downloaded. Vector fingerprints include a model tag.
- A 107 MB model download was queued on **every app launch**; now only after the user opts in to File AI.
- File AI permission screen no longer auto-opens on first launch.
- `catch (Exception)` swallowed coroutine cancellation in the client and service: fixed.
- Shipped `gradlew` was a custom bootstrap pinned to Gradle 8.13 while properties said 8.14.4, and `gradle-wrapper.jar` was missing: replaced with the official generated wrapper.
- Removed 5 duplicate/patching scripts and the duplicate backend `main.py`.
- Backend: constant-time token check, per-install + per-IP rate limits, provider concurrency cap, request IDs, security headers, 413 body cap, provider 429/401 mapping, no traceback logging, docs off in production.

### Added
- Graphite Signal design tokens (light/dark, WCAG-tested), new adaptive + monochrome icon, edge-to-edge, predictive-back opt-in, backup/extraction rules, `play`/`full` flavors, env-based release signing, R8 keep rules, regression corpus, 134 tests.
