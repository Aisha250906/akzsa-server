"""Groq vision request/response schemas and privacy-safe parsing helpers.

The Android client retains durable queue state. The server processes bounded batches only; it does not persist
uploaded image bytes or learn the phone's filesystem paths.
"""
from __future__ import annotations

import base64
import binascii
import re
import threading
import time
from collections import deque
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

MAX_IMAGES_PER_BATCH = 3
MAX_IMAGE_BYTES = 4 * 1024 * 1024
_ITEM_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
_ALLOWED_MIME_TYPES = {"image/jpeg", "image/png", "image/webp"}


class VisionImage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    item_id: str = Field(min_length=1, max_length=80)
    mime_type: Literal["image/jpeg", "image/png", "image/webp"]
    image_base64: str = Field(min_length=16, max_length=((MAX_IMAGE_BYTES + 2) // 3) * 4 + 8)
    task: Literal["visual", "ocr", "visual_ocr"] = "visual_ocr"
    sensitive_document_suspected: bool = False
    sensitive_document_authorized: bool = False

    @field_validator("item_id")
    @classmethod
    def validate_item_id(cls, value: str) -> str:
        if not _ITEM_ID_RE.fullmatch(value):
            raise ValueError("item_id must contain only letters, digits, underscores or hyphens")
        return value

    @field_validator("image_base64")
    @classmethod
    def validate_encoded_image(cls, value: str) -> str:
        clean = value.strip()
        if clean.startswith("data:"):
            raise ValueError("Send base64 image bytes without a data URI prefix")
        try:
            raw = base64.b64decode(clean, validate=True)
        except (binascii.Error, ValueError) as error:
            raise ValueError("image_base64 is not valid base64") from error
        if not raw or len(raw) > MAX_IMAGE_BYTES:
            raise ValueError(f"Each image must be between 1 byte and {MAX_IMAGE_BYTES} bytes")
        return clean

    def decoded_bytes(self) -> bytes:
        return base64.b64decode(self.image_base64, validate=True)


class VisionBatchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    images: list[VisionImage] = Field(min_length=1, max_length=MAX_IMAGES_PER_BATCH)

    @field_validator("images")
    @classmethod
    def unique_item_ids(cls, value: list[VisionImage]) -> list[VisionImage]:
        ids = [image.item_id for image in value]
        if len(ids) != len(set(ids)):
            raise ValueError("item_id values must be unique within a batch")
        return value

    @model_validator(mode="after")
    def images_match_declared_mime(self) -> "VisionBatchRequest":
        for image in self.images:
            raw = image.decoded_bytes()
            mime = image.mime_type
            matches = (
                (mime == "image/jpeg" and raw.startswith(b"\xff\xd8\xff"))
                or (mime == "image/png" and raw.startswith(b"\x89PNG\r\n\x1a\n"))
                or (mime == "image/webp" and len(raw) >= 12 and raw.startswith(b"RIFF") and raw[8:12] == b"WEBP")
            )
            if not matches:
                raise ValueError("Image bytes do not match the declared MIME type")
        return self


class VisionItemResult(BaseModel):
    model_config = ConfigDict(extra="ignore")

    item_id: str = Field(min_length=1, max_length=80)
    status: Literal["success", "partial", "unreadable", "failed"] = "success"
    caption: str = Field(default="", max_length=600)
    objects: list[str] = Field(default_factory=list, max_length=30)
    scene_tags: list[str] = Field(default_factory=list, max_length=30)
    search_tags: list[str] = Field(default_factory=list, max_length=40)
    document_type: str = Field(default="unknown", max_length=80)
    has_visible_text: bool = False
    extracted_text: str = Field(default="", max_length=4000)
    uncertainty: str = Field(default="", max_length=300)

    @field_validator("objects", "scene_tags", "search_tags")
    @classmethod
    def sanitize_tags(cls, values: list[str]) -> list[str]:
        result: list[str] = []
        for value in values:
            cleaned = " ".join(str(value).split()).strip()[:80]
            if cleaned and cleaned.casefold() not in {v.casefold() for v in result}:
                result.append(cleaned)
            # Keep every tag list within the strictest per-field limit (objects/scene_tags: 30).
            if len(result) >= 30:
                break
        return result

    @field_validator("caption", "document_type", "extracted_text", "uncertainty")
    @classmethod
    def normalize_strings(cls, value: str) -> str:
        return " ".join(value.split()).strip()


class VisionBatchResponse(BaseModel):
    results: list[VisionItemResult]
    model: str
    processed: int


def build_vision_prompt(images: list[VisionImage]) -> str:
    instructions = (
        "Analyze each supplied image independently for private file retrieval. Return one JSON object with a top-level "
        "results array. Each result must contain item_id, status, caption, objects, scene_tags, search_tags, "
        "document_type, has_visible_text, extracted_text, uncertainty. Use only item_id values supplied below. "
        "Describe visible facts; do not infer hidden facts, invent text, guess obscured identity characters, or treat "
        "numbers as money without evidence. If text is unreadable, leave it out and explain uncertainty briefly. "
        "Keep captions concise and search tags useful. Output valid JSON only. For identity, bank and other official "
        "documents, transcribe every clearly readable field exactly as printed (names, numbers, dates) so the owner can "
        "search their own files later; set document_type to a short label such as aadhaar_card, pan_card, passport, "
        "bank_statement or receipt. Never guess an unclear character: write only what is legible."
    )
    item_list = "\n".join(
        f"- item_id={image.item_id}; task={image.task}; "
        f"looks_like_identity_or_financial_document={str(image.sensitive_document_suspected).lower()}"
        for image in images
    )
    return f"{instructions}\nRequested items:\n{item_list}"


def normalize_model_results(raw: Any, requested_ids: list[str]) -> list[VisionItemResult]:
    """Only accept results that map to this exact batch. Missing or untrusted IDs are ignored."""
    if isinstance(raw, dict):
        rows = raw.get("results", [])
    else:
        rows = []
    valid_ids = set(requested_ids)
    accepted: dict[str, VisionItemResult] = {}
    if isinstance(rows, list):
        for row in rows:
            if not isinstance(row, dict):
                continue
            item_id = row.get("item_id")
            if not isinstance(item_id, str) or item_id not in valid_ids or item_id in accepted:
                continue
            try:
                result = VisionItemResult.model_validate(row)
            except Exception:
                continue
            accepted[item_id] = result
    output: list[VisionItemResult] = []
    for item_id in requested_ids:
        result = accepted.get(item_id)
        if result is not None:
            output.append(result)
        else:
            output.append(VisionItemResult(
                item_id=item_id,
                status="failed",
                uncertainty="The vision provider returned no valid result for this image.",
            ))
    return output


def estimated_input_tokens(image_count: int) -> int:
    # Conservative rough estimate for application-side rate limiting; provider-reported usage is authoritative.
    return max(1, image_count) * 2048 + 800


class VisionQuotaGuard:
    """Single-instance organisation quota guard for vision RPM/RPD/TPM/TPD.

    Configure these numbers to the real account limits. The provider remains authoritative. For multiple backend
    instances, replace the in-process windows with one shared Redis/database limiter.
    """

    def __init__(
        self,
        requests_per_minute: int,
        tokens_per_minute: int,
        requests_per_day: int = 1000,
        tokens_per_day: int = 200_000,
    ):
        self.requests_per_minute = max(1, int(requests_per_minute))
        self.tokens_per_minute = max(2048, int(tokens_per_minute))
        self.requests_per_day = max(1, int(requests_per_day))
        self.tokens_per_day = max(2048, int(tokens_per_day))
        self._requests: deque[float] = deque()
        self._tokens: deque[tuple[float, int]] = deque()
        self._daily_requests: deque[float] = deque()
        self._daily_tokens: deque[tuple[float, int]] = deque()
        self._lock = threading.Lock()

    @staticmethod
    def _wait_until_enough_tokens(rows: deque[tuple[float, int]], limit: int, estimate: int, now: float, window_s: float) -> int:
        total = sum(count for _, count in rows)
        if total + estimate <= limit:
            return 0
        freed = total
        for tick, count in rows:
            freed -= count
            if freed + estimate <= limit:
                return max(1, int(tick + window_s - now) + 1)
        if rows:
            return max(1, int(rows[0][0] + window_s - now) + 1)
        return max(1, int(window_s))

    def reserve(self, estimated_tokens: int, now: float | None = None) -> tuple[bool, int]:
        tick = time.monotonic() if now is None else now
        estimate = max(1, int(estimated_tokens))
        with self._lock:
            cutoff_minute = tick - 60.0
            cutoff_day = tick - 86_400.0
            while self._requests and self._requests[0] <= cutoff_minute:
                self._requests.popleft()
            while self._tokens and self._tokens[0][0] <= cutoff_minute:
                self._tokens.popleft()
            while self._daily_requests and self._daily_requests[0] <= cutoff_day:
                self._daily_requests.popleft()
            while self._daily_tokens and self._daily_tokens[0][0] <= cutoff_day:
                self._daily_tokens.popleft()

            waits = [1]
            denied = False
            if len(self._requests) >= self.requests_per_minute:
                denied = True
                waits.append(max(1, int(self._requests[0] + 60.0 - tick) + 1))
            if len(self._daily_requests) >= self.requests_per_day:
                denied = True
                waits.append(max(1, int(self._daily_requests[0] + 86_400.0 - tick) + 1))
            minute_wait = self._wait_until_enough_tokens(self._tokens, self.tokens_per_minute, estimate, tick, 60.0)
            if minute_wait:
                denied = True
                waits.append(minute_wait)
            daily_wait = self._wait_until_enough_tokens(self._daily_tokens, self.tokens_per_day, estimate, tick, 86_400.0)
            if daily_wait:
                denied = True
                waits.append(daily_wait)

            if denied:
                # Long values are intentional for a daily quota. The Android worker schedules a delayed continuation.
                return False, max(waits)
            self._requests.append(tick)
            self._tokens.append((tick, estimate))
            self._daily_requests.append(tick)
            self._daily_tokens.append((tick, estimate))
            return True, 0


_AADHAAR_GROUPED = re.compile(r"(?<!\d)\d{4}[ -]\d{4}[ -]\d{4}(?!\d)")
_AADHAAR_PLAIN = re.compile(r"(?<![\d])[2-9]\d{11}(?!\d)")
_AADHAAR_CONTEXT = re.compile(r"(?i)aadhaar|aadhar|uidai|\u0906\u0927\u093e\u0930")
_PAN = re.compile(r"(?i)\b[A-Z]{5}\d{4}[A-Z]\b")


def redact_sensitive_identifiers(text: str) -> str:
    """Mask Aadhaar and PAN-shaped identifiers. Only used when the operator or the user asks for masking.

    Grouped 4-4-4 numbers are always masked. A plain 12-digit number is masked only when the text itself mentions
    Aadhaar/UIDAI, so UPI references, order numbers and phone numbers stay searchable.
    """
    value = _AADHAAR_GROUPED.sub("[REDACTED_AADHAAR_NUMBER]", text)
    if _AADHAAR_CONTEXT.search(value):
        value = _AADHAAR_PLAIN.sub("[REDACTED_AADHAAR_NUMBER]", value)
    value = _PAN.sub("[REDACTED_PAN_NUMBER]", value)
    return value[:4000]
