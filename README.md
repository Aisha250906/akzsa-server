# AKZSA Voice Keyboard - Writing Mode Improvements (v1.7.0)

A custom Android keyboard (IME) with voice typing (English, Hindi, Hinglish, Kannada), voice commands, private on-device File AI search, and Writing Mode tools for rewriting, translation, reply suggestions, reusable templates, personal style, and voice-guided editing. The FastAPI backend supports cloud dictation, command interpretation, and writing previews.

> **Honest status:** the logic, backend and keyboard service were verified here by tests and compilation, but the
> **APK/AAB were NOT built** (this sandbox has no Android SDK and cannot reach Google/Maven repositories) and nothing was
> tested on a physical device. See `docs/RELEASE_GATE_REPORT.md` for exactly what is PASS, UNVERIFIED and BLOCKED.

## Layout
| Path | What |
|---|---|
| `AKZSA-Android/` | Android app (Kotlin, Compose, classic-View keyboard). Official Gradle 8.14.4 wrapper included. |
| `server/` | Canonical backend (`server/app/main.py`), tests, `render.yaml`. Root `main.py` is only a shim. |
| `corpus/` , `AKZSA-Android/app/src/test/resources/corpus/` | Search regression corpus (files + queries). |
| `scripts/` | `build.ps1`, `build.sh`, `verify.sh`, `pin-model-hashes.sh`. Scripts never edit source. |
| `docs/` | Architecture, security/privacy, Play checklist, device test plan, ADRs, release gate report. |

## Quick start
```bash
scripts/verify.sh                       # backend tests + XML checks (no Android SDK needed)
scripts/build.sh test                   # Android unit tests (needs Android SDK + internet for Gradle deps)
scripts/build.sh debug                  # play-flavor debug APK
scripts/build.sh release-aab            # needs keystore.properties (see keystore.properties.example)
```
Windows: `.\scripts\build.ps1 -Task debug`. Install Android Studio first (it provides the SDK and JDK 17+).

## Build flavors
* `play` - Google Play build. No `MANAGE_EXTERNAL_STORAGE`; File AI indexes folders the user picks (SAF).
* `full` - sideload/enterprise build. May use Android all-files access.

## Writing Mode
The app home screen includes **Writing Mode**. It supports grammar correction, professional/friendly rewrites, shortening/expansion, translation, three reply suggestions, an editable before/after preview, retry/restore, local templates, local tone/language preferences, and spoken edit instructions. On Android apps that support `ACTION_PROCESS_TEXT`, users can select text and open AKZSA Writing Mode from the text-selection menu; read-only selections use copy instead. Incoming text can also be shared to AKZSA from apps that support Android `ACTION_SEND`. Other apps can use the standalone screen by pasting text. AI actions send only the text explicitly submitted to the configured backend; avoid sending passwords or sensitive content.

Writing requests use `POST /v1/writing/generate`. Configure the backend URL and server token in AKZSA settings, and configure `GROQ_API_KEY` on the backend. See `docs/WRITING_MODE.md` for details and known limits.

## Backend
```bash
cd server && pip install -r requirements.txt && cp .env.example .env   # add GROQ_API_KEY
uvicorn app.main:app --reload
```
Production (`AKZSA_ENV=production`) fails closed without `AKZSA_SERVER_TOKEN`. See `docs/BACKEND_OPERATIONS.md`.

## Read next
`docs/RELEASE_GATE_REPORT.md` -> `docs/KNOWN_LIMITATIONS.md` -> `docs/DEVICE_TEST_PLAN.md` -> `docs/PLAY_STORE_CHECKLIST.md`
