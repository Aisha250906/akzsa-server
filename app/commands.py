"""Command models and the deterministic (no-LLM) command parser.

Split out of main.py so the HTTP layer stays small and the parser can be tested without FastAPI or a provider.
"""
from __future__ import annotations

import re
from typing import Literal, Optional

from pydantic import BaseModel, Field


class CommandRequest(BaseModel):
    transcript: str = Field(min_length=1, max_length=2000)
    selected_text: Optional[str] = Field(default=None, max_length=10000)
    surrounding_text: Optional[str] = Field(default=None, max_length=10000)
    # Keep this explicit: the model proposes an action; the Android client validates/executes it.
    mode: Literal["auto", "dictation", "command", "write"] = "auto"


class CommandAction(BaseModel):
    kind: Literal[
        "dictation", "delete_last_words", "delete_last_characters", "delete_all_text",
        "undo", "new_paragraph", "correct_text", "rewrite_text",
        "translate_text", "generate_text", "find_file", "unknown"
    ]
    count: Optional[int] = Field(default=None, ge=1, le=500)
    text: Optional[str] = Field(default=None, max_length=10000)
    query: Optional[str] = Field(default=None, max_length=1000)
    language: Optional[str] = Field(default=None, max_length=100)
    requires_confirmation: bool = False
    explanation: str = Field(default="", max_length=300)


class CommandResponse(BaseModel):
    action: CommandAction
    model: str
    server_executed_action: bool = False
    message: str


# Deterministic parsing for simple, high-confidence commands. These rules run before
# the LLM so common commands do not depend on model classification or Groq availability.
_NUMBER_WORDS = {
    "zero": 0, "one": 1, "two": 2, "three": 3, "four": 4,
    "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9,
    "ten": 10, "eleven": 11, "twelve": 12, "thirteen": 13,
    "fourteen": 14, "fifteen": 15, "sixteen": 16, "seventeen": 17,
    "eighteen": 18, "nineteen": 19, "twenty": 20, "thirty": 30,
    "forty": 40, "fifty": 50, "sixty": 60, "seventy": 70,
    "eighty": 80, "ninety": 90,
}
_NUMBER_ONES = r"(?:one|two|three|four|five|six|seven|eight|nine)"
_NUMBER_ATOM = r"(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)"
_NUMBER_PHRASE = rf"(?:\d{{1,3}}|{_NUMBER_ATOM}(?:[ -]+{_NUMBER_ONES})?)"
_DELETE_PATTERN = re.compile(
    rf"^\s*(?:hey\s+akzsa\s*[, ]+)?(?:please\s+)?"
    rf"(?:(?:can|could|would)\s+you\s+)?(?:please\s+)?"
    rf"(?:delete|remove|erase)\s+(?:(?:the|my)\s+)?"
    rf"(?:last|previous|final)\s+(?P<count>{_NUMBER_PHRASE})\s+"
    rf"(?P<unit>words?|characters?|letters?)"
    rf"(?:\s+(?:please|from the end|at the end))?[.!? ]*$",
    re.IGNORECASE,
)
_CLEAR_ALL_PATTERN = re.compile(
    r"^\s*(?:hey\s+akzsa\s*[, ]+)?(?:please\s+)?"
    r"(?:(?:can|could|would)\s+you\s+)?(?:please\s+)?"
    r"(?:clear|delete|remove|erase)\s+"
    r"(?:(?:all|everything|the entire|all of the)\s+)?"
    r"(?:(?:the|this)\s+)?(?:text|contents?|content|field|text field)"
    r"(?:\s+(?:in|of)\s+(?:this|the)\s+(?:text\s+)?field)?"
    r"(?:\s+(?:please|completely))?[.!? ]*$",
    re.IGNORECASE,
)
_PARAGRAPH_PATTERN = re.compile(
    r"^\s*(?:hey\s+akzsa\s*[, ]+)?(?:please\s+)?"
    r"(?:(?:can|could|would)\s+you\s+)?(?:please\s+)?"
    r"(?:(?:insert|start|begin|add)\s+)?(?:a\s+)?"
    r"(?:new\s+paragraph|new\s+line|line\s+break)[.!? ]*$",
    re.IGNORECASE,
)


def _parse_count(raw: str) -> Optional[int]:
    value = raw.strip().lower().replace("-", " ")
    if value.isdigit():
        return int(value)
    parts = value.split()
    if len(parts) == 1:
        return _NUMBER_WORDS.get(parts[0])
    if len(parts) == 2:
        tens = _NUMBER_WORDS.get(parts[0])
        ones = _NUMBER_WORDS.get(parts[1])
        if tens is not None and tens in {20, 30, 40, 50, 60, 70, 80, 90} and ones is not None and 1 <= ones <= 9:
            return tens + ones
    return None


def _try_deterministic_command(transcript: str) -> Optional[CommandAction]:
    """Recognize only complete, unambiguous commands; leave other text to the model."""
    clean = (transcript or "").strip()
    if not clean:
        return None

    if _CLEAR_ALL_PATTERN.fullmatch(clean) or re.fullmatch(
        r"(?i)\s*(?:clear|delete|erase|remove)\s+everything\s*(?:from\s+(?:this|the)\s+field)?[.!? ]*", clean
    ):
        return CommandAction(
            kind="delete_all_text",
            requires_confirmation=True,
            explanation="Clear all text in the currently focused text field.",
        )

    delete_match = _DELETE_PATTERN.fullmatch(clean)
    if delete_match:
        count = _parse_count(delete_match.group("count"))
        unit = delete_match.group("unit").lower()
        if count is None or count < 1:
            return None
        if unit.startswith("word"):
            # Match the Android client's current operation limit.
            if count > 50:
                return None
            return CommandAction(
                kind="delete_last_words",
                count=count,
                requires_confirmation=True,
                explanation=f"Delete the last {count} word{'s' if count != 1 else ''} at the end of the text field.",
            )
        if count > 100:
            return None
        return CommandAction(
            kind="delete_last_characters",
            count=count,
            requires_confirmation=True,
            explanation=f"Delete the last {count} character{'s' if count != 1 else ''} at the end of the text field.",
        )

    if _PARAGRAPH_PATTERN.fullmatch(clean):
        return CommandAction(
            kind="new_paragraph",
            requires_confirmation=False,
            explanation="Insert a new line at the cursor.",
        )

    file_search_verb = re.search(r"\b(find|search|locate|look\s+for|show\s+me|dhundo|dhoondo|khojo|ढूंढो|खोजो|ಹುಡುಕು|ಹುಡುಕಿ)\b", clean, re.IGNORECASE)
    file_search_target = re.search(r"\b(file|files|document|documents|pdf|invoice|receipt|spreadsheet|photo|photos|image|images|folder|folders|gst|gstin|फाइल|दस्तावेज|बिल|ಇನ್ವಾಯ್ಸ್|ಡಾಕ್ಯುಮೆಂಟ್)\b", clean, re.IGNORECASE)
    if file_search_verb and file_search_target:
        return CommandAction(
            kind="find_file",
            query=clean[:300],
            requires_confirmation=False,
            explanation="Search the shared-storage files indexed locally by AKZSA File AI.",
        )

    return None
