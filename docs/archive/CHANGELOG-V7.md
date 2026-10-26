# AKZSA v7 – keyboard-close fix (step 1)

- Index phone (keyboard): if storage access/folder was already granted, indexing now starts in the background
  from inside the keyboard. FileAiActivity opens only when permission/folder is still missing.
- Voice "find file": no longer rebuilds the whole keyboard if File mode is already open.

NOT compiled or tested here (no Android SDK). Build and test on the Android 12 phone.
Still to do: Android 12 background-indexing check, model fallback, combined filename+AI ranking, arm64-only APK.

## v7.1 – crash guard (likely real cause of keyboard closing during search)
- First search loaded the ~388 MB AI model inside the keyboard process. Out-of-memory / native errors are not
  `Exception`s, so they killed the keyboard. Now: Throwable is caught, and a "model load in progress" flag is
  stored; if a load never finished (crash), AI search is turned off and normal filename/text search is used.
- Reset: clear app data, or remove pref `semantic_disabled_after_crash` in `akzsa_file_ai`.

## v7.2 – AI indexing: real error + Retry
Screenshot showed "AI semantic indexing failed: check model compatibility and retry" with 4240 files indexed.
That generic text appears when the worker dies from a low-level Error (not an Exception) so no reason was saved.
- Worker now catches Throwable, starts the model first (warmUp) and reports the exact reason on the setup screen.
- A single bad file can no longer stop the whole AI index.
- New "Retry AI meaning index" button (also re-enables AI after the crash guard turned it off).
- If the model crashes the app while loading, the next run says so ("probably not enough memory").
Not compiled here; build and test on device.

## v7.3 – real cause of AI failure found (from the phone screenshot)
UnsatisfiedLinkError ... BaseOptionsUtils.nativeGetDirectBufferAddress. It was NOT memory.
libmediapipe_tasks_jni.so is in the APK and contains the function, but the app called BaseOptions.build()
before anything had loaded that library. Fix: System.loadLibrary("mediapipe_tasks_jni") before building options.
Not compiled here; build and test on device.

## v7.4 – model handed to MediaPipe as a file descriptor
Phone screenshot after v7.3: "MediaPipeException: invalid argument: Model path or file descriptor must be
specified in BaseOptions." (native library now loads fine.) The embedder in tasks-retrieval:1.1.0 does not accept
setModelAssetBuffer. BaseOptions.Builder has setModelAssetFileDescriptor(Integer) (checked in the APK's dex), so the
downloaded model file is now opened read-only and passed by descriptor, kept open for the session.
Not compiled here; build and test on device.

## v7.5 – build fix
v7.4 failed to compile: "Unresolved reference resetAfterCrash / lastError / warmUp / recordError".
Cause: the v7.4 edit accidentally deleted those four helper functions from LocalSemanticSearch.kt. Restored them.

## v8.0 – light photo AI (MobileCLIP2-S0) replaces heavy Gemma; heat protection
Why: the 388 MB Gemma model made the phone hot and could not keep up.
- Gemma is OFF by default (pref `gemma_enabled`, default false). Its code is still there but dormant.
- New photo AI: MobileCLIP2-S0 via ONNX Runtime (vision FP32 43 MB + text INT8 61 MB + tokenizer 2 MB, ~107 MB, Wi-Fi only).
  Model source: huggingface.co/felixhrdyn/mobileclip2-s0-onnx (community ONNX of Apple's MobileCLIP2; check licence before publishing).
  Model card says INT8 vision is ~12x slower on CPU, so vision stays FP32.
- Heat protection: 2 CPU threads, 250 ms rest after each photo, pause when battery >= 40 C or system thermal >= MODERATE
  (resume < 38 C), only new/changed photos, battery-not-low constraint, resumable after interruption.
- Search: photo-AI hits are merged with filename / OCR / label / lexical results. English queries only
  (Kannada/Hindi fall back to OCR + filename). The result reason shows the similarity number so thresholds can be tuned
  (ClipRanking.MIN_SIMILARITY = 0.20, BAND = 0.07).
- Setup screen: sections 1 and 3 now show Photo AI download/index status; buttons "Retry photo AI" and "Re-download photo AI model".
- APK: arm64-v8a only (much smaller than the 255 MB debug build).
- Tokenizer verified against the OpenAI CLIP reference algorithm (14/14 cases) with kotlinc; scoring logic tested too.
  All new files type-checked against API stand-ins, but the app itself was NOT built here (no Android SDK). Build on PC.
- Dependency added: com.microsoft.onnxruntime:onnxruntime-android:1.22.0

## v8.1 – strict order: OCR first, photo recognition second
Photo AI used to be startable early (app open, model download finished) while the OCR scan was still running.
Now the photo worker checks WorkManager's real state of the scan (UNIQUE_SCAN / CHANGE / PERIODIC). While any is
RUNNING it waits (status shown: "Waiting for OCR/text indexing to finish first…"), up to ~8 minutes, then retries.
Scan completion still queues photo indexing, so new/changed photos also get OCR first, then photo AI.
