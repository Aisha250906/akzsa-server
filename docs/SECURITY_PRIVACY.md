# Security & privacy

## Data inventory
| Data | Where | Leaves phone? |
|---|---|---|
| Typed text | target app only | never |
| Voice audio | on-device recogniser, or (Cloud Dictation, user-enabled) one clip to the AKZSA server -> Groq | only when Cloud Dictation is used; not stored by AKZSA |
| Transcript / selected text for commands | request body | only on explicit command/write action; never from protected fields |
| File index, OCR text, image labels, embeddings | app-private SQLite/files | **no** |
| Server token | Android Keystore-backed store (`SecureTokenStore`) | sent as Authorization header |
| Install ID | random UUID, app-private | header `X-AKZSA-Install-Id`, rate-limit key only; resets on clear-data |
| Backend logs | request id, method, path, status, latency | no text, audio, tokens, query strings |

## Protected fields
`SensitiveFieldPolicy`: password/visible/web/numeric-password input types, `IME_FLAG_NO_PERSONALIZED_LEARNING` (incognito), and heuristic hint/label matching for OTP, PIN, CVV, card, passcode (English + Hindi/Kannada words). In these fields voice, cloud, AI commands and file search are disabled. Hint matching is heuristic; apps that don't label OTP boxes cannot be detected.
## Controls added
Constant-time token check; per-install + per-IP rate limits; provider concurrency cap; 256 KB JSON cap; 20 MB audio cap; request IDs; nosniff/no-store; docs off in production; fail-closed production without token; error bodies never echo user text; diagnostics `Redactor` strips tokens, keys, e-mails, paths, URIs, long numbers, GSTIN; backups and device transfer disabled; cancellation never swallowed.
## Residual risks
1. **Shared bearer token in the APK is extractable.** Treat as a speed bump; add Play Integrity attestation + per-install quotas before scaling.
2. Third parties: audio/transcripts go to Groq when cloud features are used; disclose this in the privacy policy and Play Data Safety form.
3. A custom keyboard can see everything typed: review Play's IME and "Prominent disclosure" requirements.
4. Model downloads come from HuggingFace over HTTPS; until hashes are pinned a compromised upstream at first download would be trusted.
5. Share/Open send a file URI to another app by the user's explicit tap.
