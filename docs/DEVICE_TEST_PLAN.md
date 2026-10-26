# Device test plan (not executed - no devices were available)

Record Pass/Fail per row. Use at least: a small 5" phone (320-360 dp), a 6.7" phone, a foldable (folded + unfolded), a tablet, a low-RAM (<=3 GB) phone, Android 8, 10, 13, 15/16 (gesture + 3-button nav), light and dark theme, font scale 0.85 / 1.0 / 1.3 / 2.0, portrait and landscape, split-screen.

## A. Keyboard
1. Enable and select AKZSA; type in Messages, Chrome URL bar, a search box, Notes (multi-line), a number field, an e-mail field. Keys never clipped; nothing under the nav bar; rows >= 44 dp.
2. Rotate / fold / unfold / enter split-screen while a keyboard is open: layout re-flows, text untouched.
3. Shift: tap = one capital; double-tap = caps lock (icon changes); auto-capital at sentence start in a sentence-case field.
4. Hold Backspace repeats; fast typing loses no characters.
5. `⌨` key switches to the previous keyboard (and picker on API <28). No dead end.
6. `अ/A` toggles Hindi; type "नमस्ते", a conjunct "क्ष", a halant joined word, danda; back to English.
7. TalkBack on: every key announces a sensible name; toolbar targets reachable. Font scale 2.0: no clipped labels.
8. Dark/light: all keys legible; active toggle has a visible outline in light mode.

## B. Protected fields
Password, PIN (numeric password), OTP box labelled "OTP", incognito Chrome tab: Talk is disabled, no network request is made (verify with a proxy/log), file search off, status explains why.

## C. Voice (on-device and Cloud)
1. English: "hello how are you" after typing "Hi" -> `Hi hello how are you`; second dictation continues with one space; first word capitalised in a sentence-case field only.
2. Quiet room, tap Talk, say nothing: no text inserted (cloud: server returns 422, nothing inserted).
3. Speak one sentence, pause 2 s, continue: is it one session? (record result per phone; hint is best-effort).
4. Force "no match" after speaking: partial text is recovered once.
5. Hindi mode: Devanagari output. Hinglish mode: "मेरा नाम राहुल है" -> Roman letters. Kannada mode on a phone without Kannada installed: clear message.
6. Airplane mode: on-device works / clear message; Cloud Dictation fails with a readable error, nothing lost.
7. Rotate or switch app mid-recording: recording stops cleanly, mic released.

## D. Files mode
1. Without indexing: clear explanation, nothing crashes. `play` flavor: folder picker appears (no all-files screen).
2. Index a folder; search "invoice", "dog photo", a filename, a Hindi filename. Results survive Shift/rotation. `Edit query` and `Back to typing` work and typed text is intact.
3. Delete/rename a file outside the app, re-index: stale result disappears.
4. Uninstall/clear data: index and model files gone.

## E. Resource limits (record numbers)
Cold start of keyboard (ms), time to first key, RAM before/after loading MobileCLIP, then Gemma (6 GB+ phone only), index 5k files (time, battery %), keyboard process survives 30 min of use, no ANR.

## F. Release build
Install the **signed minified** `release` APK/AAB-derived build and repeat A-D smoke tests (R8 can break JNI libraries that work in debug).

## C2. Live dictation, noise, dictionary (1.6.0)
- [ ] Cloud on, token saved: speak 3 sentences with natural pauses; each appears about 1-2 s after its pause, in order, no duplicates, no lost first words.
- [ ] Tap Stop mid-sentence: last phrase still appears; keyboard returns to normal (no stuck "Processing").
- [ ] Say nothing for 30 s: it stops itself with a message. Fan/AC on, silent: no text appears. Tap desk / cough: no text appears.
- [ ] Walk in a noisy street / cafe, repeat with Noise reduction on and off; note which is better on THIS phone (record device model).
- [ ] Airplane mode during dictation: a clear message, no crash; turn network back on and dictate again.
- [ ] Switch Live cloud dictation off: old one-shot behaviour still works. No token saved: old path shows the token message.
- [ ] Hinglish sentences with English words (meeting, invoice, WhatsApp, payment): Roman output, no Devanagari left. Try `AKZSA_HINGLISH_LANGUAGE=hi` on the server and compare 10 sentences.
- [ ] Dictionary: add `nefion = NEFEON`, say it; add a plain term and check capitals. Say "comma", "full stop", "new line", "question mark".
- [ ] Keyboard hidden / app switched while recording: recording stops, no crash. Password field: nothing inserted.
- [ ] Record: time from end of phrase to inserted text (Wi-Fi, 4G), battery use over 5 minutes of dictation.
