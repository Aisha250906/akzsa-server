# Known limitations (honest list)

* **No APK/AAB, no device testing, no Gradle compile** of the whole app was possible while preparing this release.
* **Voice quality is mostly the speech engine's**, not AKZSA's. On-device recognition needs the language installed in the phone's speech service; Hindi/Kannada offline may be unavailable on some phones. Cloud Dictation (Groq Whisper) needs the server + token.
* **Hinglish romanisation is a heuristic**, not a dictionary. Medial schwa deletion is deliberately conservative ("kamala" rather than "kamla"). A table fixes ~35 very common words. English words spoken inside Hindi may be spelled phonetically ("invois").
* **Silence-length hints are best effort**; Android recognisers may ignore them and still stop at a pause. Tap Talk again to continue.
* **The echo/boilerplate guard can drop real speech** in one narrow case: a short (<48 KB) clip whose entire content is multi-word subtitle boilerplate such as "thanks for watching".
* **Hindi layout** is a simple varga-ordered layout, not InScript or phonetic. No Kannada typing layout yet (Kannada voice works).
* No word suggestions, auto-correct, swipe typing, or long-press accents (typing aid is deliberately minimal).
* **Semantic search quality is unmeasured on real data.** `MIN_GEMMA_RAM_MB = 6000` is provisional. MobileCLIP text encoder is English-focused; Hindi/Kannada queries fall back to exact/OCR matching.
* MobileCLIP artifacts download from an immutable Hugging Face revision, but their SHA-256 hashes are **not pinned** yet (trust-on-first-use). Run `scripts/pin-model-hashes.sh` and pin them.
* In-memory backend rate limits reset on restart and are per instance. Behind a proxy the peer IP is the proxy unless `--proxy-headers`/trusted forwarded IPs are configured.
* The client token is a **shared secret inside the APK**: it deters casual abuse only. Use Play Integrity + per-install quotas for real protection.
* IME runs inside the app process: Gemma loading (opt-in, >=6 GB RAM, crash-guarded) happens on demand off the main thread, not at startup, but memory pressure can still kill the keyboard process.
* `file_paths.xml` exposes all shared storage to the (non-exported) FileProvider by per-URI grant only; narrow it if you restrict indexing roots.
* Share/Open file actions hand a URI to another app via Android's chooser - that is the user's explicit action, but the receiving app then has that file.

## Voice (added in 1.6.0)
* Live dictation is phrase-level (text after each pause), not word-by-word, and cloud only. The on-device engine already streams its own partial results.
* Noise handling is signal processing, not an AI denoiser: steady noise, hum, wind and short bursts are handled; background conversation (babble) is not. An RNNoise/ML denoiser would be the next step.
* Spoken punctuation converts those phrases everywhere (see the switch). The Hinglish dictionary has about 590 entries; unknown words use rules. Forcing Hindi decoding for Hinglish (`AKZSA_HINGLISH_LANGUAGE=hi`) is untested: compare on real speech before making it the default.
* Phrase upload uses 16 kHz WAV (about 32 KB per second of speech): fine on Wi-Fi/4G, noticeable on very slow data.
