"""Guards against speech-to-text output that is not what the user said.

Why this exists: Whisper treats its `prompt` as preceding transcript text. On quiet, very short or noisy audio it
can return the prompt itself (or familiar subtitle boilerplate such as "Thanks for watching") as the "dictation".
Inserting that into the user's text field is worse than inserting nothing.
"""
from __future__ import annotations

import re
from typing import Optional

_PUNCT = re.compile(r"[^\w\u0900-\u097F\u0C80-\u0CFF]+", re.UNICODE)

# Multi-word subtitle boilerplate only. Single words/short phrases such as "Thank you" are deliberately NOT
# listed: a person can really dictate them, and rejecting them would drop real speech.
_BOILERPLATE = {
    "thanks for watching",
    "thank you for watching",
    "thanks for watching and see you next time",
    "please subscribe",
    "please subscribe to my channel",
    "like and subscribe",
    "don t forget to subscribe",
    "subtitles by the amara org community",
    "subtitles by amara org",
    "transcribed by",
    "www mooji org",
}
SHORT_AUDIO_BYTES = 48 * 1024  # roughly <= 6 s of 64 kbps AAC


def normalize(text: str) -> str:
    return _PUNCT.sub(" ", (text or "").lower()).strip()


def is_prompt_echo(transcript: str, prompt: Optional[str]) -> bool:
    """True when the transcript is (mostly) the prompt text coming back."""
    t, p = normalize(transcript), normalize(prompt or "")
    if not t or not p:
        return False
    words = t.split()
    if len(words) >= 6 and t in p:
        return True
    if len(words) >= 4:
        prompt_words = set(p.split())
        overlap = sum(1 for w in words if w in prompt_words) / len(words)
        return overlap >= 0.8
    return False


def is_context_repeat(transcript: str, context: Optional[str]) -> bool:
    """True when a phrase is a verbatim copy (6+ words) of the session context that was sent as the prompt tail:
    Whisper sometimes repeats its prompt on noise-only audio. Shorter or partial overlaps are real speech."""
    t, c = normalize(transcript), normalize(context or "")
    return bool(t) and bool(c) and len(t.split()) >= 6 and t in c


def is_silence_hallucination(transcript: str, audio_bytes: int) -> bool:
    t = normalize(transcript)
    return audio_bytes < SHORT_AUDIO_BYTES and t in _BOILERPLATE
