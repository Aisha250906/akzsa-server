# AKZSA Android client

Native Kotlin Android input method editor (IME) with voice typing, AI commands, Writing Mode, and private on-device File AI. Writing Mode supports one-tap rewrites, translation, three reply suggestions, preview/apply-or-copy, Android text-selection integration where supported, local templates and style preferences, retry/restore, and spoken edit instructions.

The FastAPI backend is in the repository's `server/` directory. Writing requests use `POST /v1/writing/generate`; configure the backend URL/token in app settings and `GROQ_API_KEY` on the server.

Build with Android Studio and JDK 17 or newer. From this directory, run `./gradlew :app:testFullDebugUnitTest` and `./gradlew :app:assembleFullDebug` after Gradle dependencies and the Android SDK are available. See the root `README.md` and `docs/WRITING_MODE.md` for setup and known limits.
