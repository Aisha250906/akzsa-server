"""Builds the Whisper `prompt` for dictation (pure functions, no web framework needed, easy to test).

Whisper treats the prompt as text that came *before* the audio, so it copies its spelling, script and style.
Three parts, in this order (Whisper pays most attention to the end):
  1. language/style hint (for Hinglish: a Roman-letter example sentence, not an instruction to obey),
  2. the user's glossary (spellings to prefer),
  3. the tail of what was already dictated in this live session (continuity between phrases).
"""
from __future__ import annotations

import re
from typing import Tuple

PROMPT_MAX = 650
GLOSSARY_MAX = 400
CONTEXT_MAX = 160

_INSTRUCTIONS = {
    "hi": (
        "Transcribe spoken Hindi accurately in Devanagari. Preserve names, numbers, technical terms, "
        "and the speaker's meaning. Do not translate or summarize."
    ),
    "kn": (
        "Transcribe spoken Kannada accurately in Kannada script. Preserve names, numbers, and technical terms. "
        "Do not translate to English or summarize."
    ),
    "hinglish": (
        "Hinglish dictation: code-switched Hindi and English in Roman letters; English words stay English. "
        "Example: Aaj meeting mein client ka invoice bhejna hai, payment kal tak aa jayegi."
    ),
    # Whisper treats `prompt` as preceding transcript. An instruction such as "Transcribe spoken English..." can come
    # back as the "dictation" on quiet audio, so English gets a glossary / context only (or no prompt at all).
    "en": "",
}

_CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")


def clean_context(raw: str) -> str:
    """Single line, no control characters, at most CONTEXT_MAX characters (the END of the text, starting at a word)."""
    text = re.sub(r"\s+", " ", _CONTROL.sub(" ", raw or "")).strip()
    if len(text) <= CONTEXT_MAX:
        return text
    tail = text[-CONTEXT_MAX:]
    first_space = tail.find(" ")
    return tail[first_space + 1:] if 0 <= first_space < 40 else tail


def build_asr_prompt(language: str, vocabulary: str = "", context: str = "") -> Tuple[str, str]:
    """Returns (prompt_for_whisper, echo_reference).

    `echo_reference` is the prompt WITHOUT the session context: it is what asr_guard compares the transcript
    against, so a user who naturally continues with similar words is not mistaken for a prompt echo."""
    base = _INSTRUCTIONS.get(language, "")
    glossary = re.sub(r"[\r\n]+", ", ", vocabulary or "").strip()[:GLOSSARY_MAX]
    if glossary:
        base = (base + " Prefer these spellings when clearly spoken: " + glossary).strip() if base else "Glossary: " + glossary + "."
    ctx = clean_context(context)
    if ctx:
        base = base[: max(0, PROMPT_MAX - len(ctx) - 1)]
        return (base + " " + ctx).strip(), base
    base = base[:PROMPT_MAX]
    return base, base


def upload_meta(content_type: str) -> Tuple[str, str]:
    """(file name, mime type) to use when forwarding the audio, so WAV phrases are not labelled as M4A."""
    if content_type in {"audio/wav", "audio/x-wav"}:
        return "akzsa-dictation.wav", "audio/wav"
    if content_type == "audio/flac":
        return "akzsa-dictation.flac", "audio/flac"
    if content_type == "audio/ogg":
        return "akzsa-dictation.ogg", "audio/ogg"
    if content_type == "audio/webm":
        return "akzsa-dictation.webm", "audio/webm"
    return "akzsa-dictation.m4a", "audio/mp4"
