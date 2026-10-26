# AKZSA automatic on-device AI setup

## What happens when the user opens AKZSA the first time

1. AKZSA schedules an automatic one-time download of the public LiteRT Text+Vision EmbeddingGemma 2 model (about 388 MB), only while the device is on an unmetered network/Wi-Fi. The download is resumable and the final file is accepted only if the known SHA-256 matches.
2. Existing filename/OCR/ML Kit/PDF text search works while the model downloads.
3. Android does not allow a newly installed app to silently read every shared file. The first setup screen asks the user to grant the required storage access once. After returning from Android Settings, the file-index job starts automatically.
4. Basic file extraction and semantic vector indexing run as background WorkManager jobs. Both display visible progress in the File AI setup screen and Android notification shade. The semantic index runs after the model and basic file rows are available and automatically repeats after file scans.
5. Text+Vision vectors, OCR results, and file paths are stored locally. The model and file content are not sent to Render.

## Model

- Model: `litert-community/embeddinggemma-2-text-vision-440m-litert-lm`
- File: `embeddinggemma-2-text-vision-440m.litertlm` (~388 MB)
- Source URL: `https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/main/embeddinggemma-2-text-vision-440m.litertlm`
- SHA-256: `92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0`
- License: Apache 2.0 per the model repository.

The model binary is intentionally not embedded in the ZIP or the debug APK. The app downloads it to app-private storage after first launch, verifies its SHA-256, and memory-maps that private file for MediaPipe. It does not try to pass the downloaded file to `setModelAssetPath`, which is for APK assets. This keeps the APK much smaller, but requires roughly 388 MB of network data and device storage. Download currently waits for Wi-Fi to avoid surprising cellular data use.

## Android permissions

Model download can start without file permission, but indexing cannot start until Android has granted access to the files the user authorized. For sideload/debug builds the current project supports broad shared-storage permission or a selected folder. Play Store publication requires a separate permission-policy review; if Google Play does not approve `MANAGE_EXTERNAL_STORAGE`, use MediaStore and the Storage Access Framework with a clear user-selected folder scope.

## Build

From Windows PowerShell run the package's `build-akzsa-ai-search.ps1`. The build must be verified on a Windows PC with JDK 17/21, Android SDK Platform 36, Build Tools, and internet access. This source package was not built into a verified APK in the Linux container because no Android SDK is installed there.

## Server `main.py`

`main.py` remains the complete FastAPI command/voice server. The local embedding model intentionally lives in the Android app, not the server: it is downloaded directly to each device and indexes private files locally. No local file index or embedding database is sent to the backend.

## Progress and first-use privacy

The File AI setup screen observes three independent WorkManager jobs: model download (bytes and percent), base file scan (count/current file), and semantic vector indexing (completed/total and failures). Notifications are also posted by the foreground workers where Android notification policy permits. AKZSA cannot read private folders owned by other apps, and must not bypass Android storage consent.
