> **Update 1.7.0 (Writing Mode):** `bash scripts/verify.sh` now passes 63 backend tests plus static XML/resource checks. Writing Mode source, the `/v1/writing/generate` route, text-selection/share intents, and action-specific reply schema are included. A full Android Gradle compile/APK build is still **UNVERIFIED** here: this environment has no configured Android SDK and cannot download the Gradle distribution. Physical-device text selection, insertion, speech recognition, and keyboard behavior must still be tested on an Android device.

> **Update 1.6.1:** the first real Gradle build (Windows, JDK 21) got through dependency resolution and resource/manifest processing; its only Kotlin error (missing `java.util.Locale` import in `ClipTokenizer.kt`) is fixed in 1.6.1. APK, unit tests and device tests are still unconfirmed.
>
> **Status for v1.6.0 (voice upgrade; includes the 1.5.2 merge):** the table below is the v1.5.0 report, kept as history. 1.5.2 changed code (see `CHANGELOG.md` and `MERGE_NOTES.md`) but **nothing has been compiled with Gradle or run on a device**, so gates 2, 3, 7, 11, 12, 13, 14, 15, 16, 17, 18 and 19 are still BLOCKED/UNVERIFIED. Differences from the text below: Kotlin plugins are pinned to 2.3.21 (not 2.4.21) and the Gemma/MediaPipe/LiteRT dependencies no longer exist.

# Release Gate Report - AKZSA v1.5.0

**Verdict: NOT READY for Google Play production. READY for a first real Gradle build + device test cycle.**
Nothing below is claimed unless it was actually executed. Evidence logs: `reports/`.

Legend: PASS = executed here and passed. UNVERIFIED = code written/reviewed but not executed by a compiler/device. BLOCKED = cannot be done in the preparation sandbox (no Android SDK, no Google/Maven/HuggingFace access, no device). FAIL = known failing.

## Gates
| # | Gate | Status | Evidence / reason |
|---|---|---|---|
| 1 | Official Gradle wrapper (jar + scripts, Gradle 8.14.4) | PASS | generated with Gradle's own `wrapper` task; sha256 of the dist in `reports/toolchain.txt`. Compare with the value published at gradle.org before trusting. |
| 2 | `./gradlew assemble…` / `bundle…` builds | **BLOCKED** | no Android SDK; `dl.google.com`, Maven, Gradle plugin portal unreachable. No APK/AAB exists. |
| 3 | Dependency versions resolvable (compose-bom 2025.08, mediapipe tasks-retrieval 1.1.0, litertlm 0.18.0, ONNX, ML Kit, Kotlin 2.4.21) | **BLOCKED** | Kotlin 2.4.21 release verified to exist on GitHub; the others could not be checked. First Gradle sync may need version edits. |
| 4 | Pure-logic unit tests (JUnit 4.13.2 via kotlinc) | PASS | 126 tests, 0 failures (`reports/kotlin-unit-tests.log`). 8 of the repo's 134 tests need Android/serialization/OkHttp libs and were not run (BackendErrorMapperTest, CommandSerialization, ClipRanking...). |
| 5 | Keyboard service compiles | PASS (stubs) | full `AkzsaInputMethodService.kt` compiled against Android API 34 stubs; third-party classes stubbed from call sites (`reports/service-compile.log`). Not a Gradle compile. |
| 6 | `LocalSemanticSearch` (fusion + provider policy) compiles | PASS (stubs) | MediaPipe types stubbed. |
| 7 | Compose / Activity / worker edits compile (`AkzsaApp`, `MainActivity`, `FileAiActivity`, `ClipModelManager`, `ClipEngine`, `ClipImageIndexWorker`, `BackendClient`, `build.gradle.kts`) | **UNVERIFIED** | edited with asserted patches and reviewed; no compiler available for Compose/WorkManager/OkHttp/Gradle DSL. Expect to fix small errors on first build. |
| 8 | Backend tests | PASS | 42 passed (16 original + 26 new). |
| 9 | Android XML well-formed, all `@res` references resolve | PASS | 15 files, 0 unresolved. |
| 10 | Search regression corpus (lexical/OCR/PDF/label path) | PASS | top-1 28/28, top-3 28/28, recall@10 1.000, no-result 3/3. **Synthetic fixtures; not a model-quality or real-world metric.** |
| 11 | Semantic model quality (MobileCLIP / Gemma) on real files | **BLOCKED** | needs a device + models. |
| 12 | Model artifact hashes pinned + immutable revisions | **BLOCKED** | HuggingFace unreachable. Gemma SHA-256 was already pinned. MobileCLIP uses trust-on-first-use + `scripts/pin-model-hashes.sh`. |
| 13 | Lint | **BLOCKED** | needs SDK. `lint` configured to report, not fail. |
| 14 | R8/minified **release** build runs correctly | **BLOCKED** | keep rules added for MediaPipe/LiteRT JNI; must be tested signed on a device. |
| 15 | Signed release (keystore) | **BLOCKED** | no keystore created. Never commit one. |
| 16 | Device matrix (see DEVICE_TEST_PLAN) - keyboard in real apps, voice, TalkBack, foldables, low-RAM | **BLOCKED** | no devices. |
| 17 | Play Console: Data safety, permissions declarations, privacy policy URL, target-API/account requirements | **BLOCKED** | account/legal tasks. Drafts in `docs/PLAY_STORE_CHECKLIST.md`, `PRIVACY_POLICY_TEMPLATE.md`. Verify current Play policy yourself. |
| 18 | Backend deployed & load-tested | **BLOCKED** | not deployed. |
| 19 | Accessibility audit with TalkBack / font 200% | **BLOCKED** | static contrast verified (WCAG tests); real audit needs device. |

## What changed vs. the supplied project
See `CHANGELOG.md`. Highest-impact fixes: dictation spacing/capitalisation/lost-partial bugs, Hinglish/Hindi path, keyboard-switch key, Hindi layout, sensitive-field policy, rank fusion, model integrity, silent 107 MB download, cancellation handling, official wrapper, backend abuse controls.

## Release blockers (must be closed before Play production)
1. Real Gradle build succeeds (gates 2, 3, 7) and unit tests pass under Gradle.
2. Device test pass for keyboard + voice (gate 16), including a **signed minified** build (gates 14, 15).
3. Pin model hashes/revisions and confirm model licences allow redistribution/download (gate 12).
4. Play declarations + privacy policy hosted at a public URL (gate 17); decide `play` vs `full` flavor (the `play` flavor is the recommended store build).
5. Backend deployed with `AKZSA_SERVER_TOKEN`, and a plan for per-install attestation (Play Integrity) - the shared bearer token ships inside the APK and is extractable (see SECURITY_PRIVACY.md).

## Next actions (in order)
`scripts/build.sh test` -> fix any compile errors -> `scripts/build.sh debug` -> install -> run `docs/DEVICE_TEST_PLAN.md` -> `scripts/pin-model-hashes.sh` -> keystore + `release-aab` -> internal testing track.
