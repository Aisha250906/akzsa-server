# Google Play checklist (drafts - verify against current Play policy; policies change)

- [ ] Ship the **play** flavor AAB (no MANAGE_EXTERNAL_STORAGE). If you need all-files access, expect a permission declaration review and be ready to justify it; sideload `full` otherwise.
- [ ] targetSdk is 36 now; confirm Play's current minimum target API for new apps/updates before upload.
- [ ] Upload signing: enrol in Play App Signing; keep the upload keystore offline, never in git.
- [ ] Privacy policy at a public URL (start from `PRIVACY_POLICY_TEMPLATE.md`); link in Console and in-app.
- [ ] Data safety form: voice audio and transcripts are sent to a server (and a third-party provider) **only on user action**; file index/OCR/embeddings stay on device; no ads; no account; install ID used for rate limiting. Declare accurately - the form must match behaviour.
- [ ] Keyboard (IME) policy: prominent disclosure that a keyboard can read what you type; explain protected-field behaviour; no collection of typed text.
- [ ] Permissions: RECORD_AUDIO (just-in-time, with in-app rationale), INTERNET, notifications for indexing foreground service (declare the foreground-service type and its use), no contacts/location.
- [ ] Foreground service: declare type + justification in Console; confirm it runs only while indexing.
- [ ] Content rating questionnaire; target audience 13+/general; no children's-data claims.
- [ ] App access: reviewers need a working Cloud Dictation token or a note that on-device voice works without it.
- [ ] Store listing text (below); screenshots from real devices (keyboard light/dark, Files mode, Hindi layer) - none were produced here.
- [ ] Internal testing track first; fix crashes/ANRs from pre-launch report; then closed -> production (new personal accounts may have mandatory closed-test requirements: check current rules).

## Listing draft
**Title:** AKZSA Voice Keyboard  **Short:** Voice typing in English, Hindi, Hinglish and Kannada with private file search.
**Full:** Dictate in English, Hindi, Hinglish or Kannada. Type on an English or Hindi layout. Find files on your phone with on-device search - your files are never uploaded. Voice, cloud and AI features switch off automatically in password, PIN and code fields. Cloud Dictation is optional and only sends the audio you record.
