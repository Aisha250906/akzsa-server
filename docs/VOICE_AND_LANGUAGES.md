# Voice and languages

| Mode | On-device engine language | Cloud (Whisper) | Output |
|---|---|---|---|
| English | en-IN / en-US | `en` | English |
| Hindi | hi-IN | `hi` | Devanagari |
| Hinglish | **hi-IN** | auto-detect + Hinglish prompt | **Roman letters** (Devanagari results are romanised locally) |
| Kannada | kn-IN | `kn` | Kannada script |

Insertion (`DictationFormatter`): leading space only when the previous character is a word character; no double spaces; punctuation attached; first letter capitalised only if the field asks (sentence/word caps); trailing space optional and never in e-mail/URL/number fields; a selection is replaced without a leading space.
Recovery: if the engine ends with no-match/timeout/server/network error after partial text was heard, that partial text is inserted once.
Typing: English QWERTY with Shift (one-shot) and caps lock (double-tap), auto-capitalisation; Hindi layer via the `अ/A` key (consonants+matras page, vowels/conjuncts page, halant, danda). Hinglish is typed on the English layer.
Cloud English prompt: glossary only. Echo/boilerplate results return HTTP 422 and insert nothing.

## Live dictation, noise handling and the dictionary (1.6.0)

**Live dictation (cloud mode).** Tap Talk, speak; after each pause the phrase is transcribed and inserted while you keep talking. Tap Stop to finish (the last phrase is still sent). It stops by itself after 25 s of silence or 10 minutes. Off switch: Settings > Live cloud dictation (then the whole session is uploaded once at Stop, as before).

**Noise handling.** Rumble filter (100 Hz), phone noise suppressor if present, quiet-speech boost, room-adaptive speech detection, short noise bursts ignored. It cannot remove other people talking nearby (babble); use a headset mic in very noisy places. Off switch: Settings > Noise reduction.

**Personal dictionary** (Settings, one entry per line or comma):
```
NEFEON                 # recognition hint + enforced spelling/capitals
nefion = NEFEON        # correction: "nefion" -> "NEFEON"
kar na => karna        # multi-word corrections work too
```
**Spoken punctuation:** "comma", "full stop", "question mark", "exclamation mark", "colon", "semicolon", "new line", "new paragraph", "open bracket", "close bracket"; Hindi/Hinglish: "poorn viram", "nayi line", "पूर्ण विराम", "नई लाइन". A sentence that really contains these words ("a comma separated list") will be converted too: switch the option off if that bothers you.

**Hinglish.** Built-in dictionary (about 590 words) + rules. Add your own names and words to the personal dictionary, e.g. `राहुल = Rahul`.

**Tuning knobs** (all in code, no rebuild of the server needed except env vars): `SpeechEndpointer.Config` (pause lengths 1.3 s / 0.8 s, minimum speech 0.3 s, speech threshold), server env `AKZSA_HINGLISH_LANGUAGE` (`hi` to test Hindi decoding for Hinglish), `GROQ_ASR_MODEL` (use `whisper-large-v3` for more accuracy, `whisper-large-v3-turbo` for speed), `AKZSA_RATE_VOICE_PER_MIN`.
