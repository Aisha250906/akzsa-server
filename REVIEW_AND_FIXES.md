# AKZSA v1.7.0 — Review and Verification Report

## Changes made

1. **Fixed shell script permissions.** Marked `AKZSA-Android/gradlew`, `scripts/build.sh`, `scripts/verify.sh`, and `scripts/pin-model-hashes.sh` executable. The original archive did not preserve executable permissions, so direct Linux/macOS/CI invocations failed with `Permission denied`.
2. **Aligned Android CI tasks with product flavors.** The app defines `play` and `full` distribution flavors. Updated CI to run the explicit, existing flavor task names used by the repository's own build script: `testFullDebugUnitTest`, `lintPlayRelease`, `assembleFullDebug`, and `bundlePlayRelease`.
3. **Removed a test-only coroutine warning.** The concurrency-cap test mocked `asyncio.wait_for` to raise before awaiting its coroutine, leaving an unawaited-coroutine warning. The mock now closes that coroutine before raising; the test continues to verify the intended HTTP 503 behavior.

## Checks run in this environment

- Python syntax compilation (`python -m compileall -q main.py server/app`): **PASS**.
- Backend unit tests (`python -m pytest -q server/tests`): **PASS — 63 tests**.
- Android XML well-formedness: **PASS — 15 XML files checked**.
- Repository verification script checks: **PASS** (backend tests and XML checks).

## Not verified here

- Android Gradle tests, lint, APK and AAB builds could not run because the Gradle wrapper needs to download Gradle 8.14.4, but network DNS access to `services.gradle.org` is unavailable in this environment. Android SDK availability/build outputs are therefore **UNVERIFIED**, not claimed as passing.
- No physical-device testing or live Groq/Render integration test was performed.

## Next verification on a machine with Android Studio/SDK and internet

From the repository root:

```bash
scripts/verify.sh
scripts/build.sh test
scripts/build.sh debug
scripts/build.sh lint
scripts/build.sh release-aab
```

A release AAB needs valid signing configuration if it is intended for upload to Google Play.
