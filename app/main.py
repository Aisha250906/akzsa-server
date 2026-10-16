import os
import json
import logging
import re
import base64
from typing import Literal, Optional

import httpx
from fastapi import FastAPI, Header, HTTPException, Depends, Request
from pydantic import BaseModel, Field
from dotenv import load_dotenv

load_dotenv()

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
logger = logging.getLogger("akzsa")

APP_NAME = "AKZSA Backend"
APP_VERSION = "0.1.0"
GROQ_API_KEY = os.getenv("GROQ_API_KEY", "").strip()
GROQ_MODEL = os.getenv("GROQ_MODEL", "openai/gpt-oss-20b").strip()
SERVER_TOKEN = os.getenv("AKZSA_SERVER_TOKEN", "").strip()
GROQ_BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
GROQ_AUDIO_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
GROQ_ASR_MODEL = os.getenv("GROQ_ASR_MODEL", "whisper-large-v3-turbo").strip()
MAX_VOICE_AUDIO_BYTES = 20 * 1024 * 1024

app = FastAPI(
    title=APP_NAME,
    version=APP_VERSION,
    description="Backend foundation for the AKZSA voice keyboard.",
)


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
