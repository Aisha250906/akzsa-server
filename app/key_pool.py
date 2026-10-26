"""Health-aware pool of the server's OWN provider API keys (configured only in server environment variables).

Purpose: spread the single call a search needs across the keys the operator legitimately holds, and stop using a key
for a while after the provider says it is rate limited or rejects it. It is NOT a way around published limits:
organisation-level quotas are shared by the keys of one organisation, so retries are few, bounded and honour
Retry-After. Key material is never logged or returned; keys are identified as k1, k2...
"""
from __future__ import annotations

import threading
import time
from typing import Callable, Dict, List, Optional, Tuple

AUTH_COOLDOWN_S = 600.0
DEFAULT_RATE_COOLDOWN_S = 20.0
MAX_RATE_COOLDOWN_S = 120.0


def parse_keys(keys_env: str, single_key: str = "") -> List[str]:
    """Comma/newline separated list first, then the classic single key; whitespace and duplicates removed."""
    pieces = [p.strip() for chunk in (keys_env or "").replace("\n", ",").split(",") for p in [chunk] if p.strip()]
    if single_key and single_key.strip():
        pieces.append(single_key.strip())
    return list(dict.fromkeys(pieces))


class KeyPool:
    def __init__(self, keys: List[str], clock: Callable[[], float] = time.monotonic):
        self._clock = clock
        self._keys = list(dict.fromkeys(k for k in keys if k))
        self._lock = threading.Lock()
        self._next = 0
        self._cooldown_until: Dict[int, float] = {i: 0.0 for i in range(len(self._keys))}
        self._stats: Dict[int, Dict[str, int]] = {i: {"requests": 0, "rate_limited": 0, "auth_failed": 0, "errors": 0, "tokens": 0} for i in range(len(self._keys))}

    @property
    def size(self) -> int:
        return len(self._keys)

    def acquire(self) -> Optional[Tuple[str, str]]:
        """Next healthy key as (key_id, key), round-robin; None when every key is cooling down."""
        with self._lock:
            n = len(self._keys)
            now = self._clock()
            for offset in range(n):
                i = (self._next + offset) % n
                if self._cooldown_until[i] <= now:
                    self._next = (i + 1) % n
                    self._stats[i]["requests"] += 1
                    return f"k{i + 1}", self._keys[i]
            return None

    def seconds_until_available(self) -> float:
        with self._lock:
            if not self._keys:
                return 0.0
            now = self._clock()
            return max(0.0, min(self._cooldown_until.values()) - now)

    def report(self, key_id: str, status: int, retry_after: Optional[float] = None, tokens: int = 0) -> None:
        try:
            i = int(key_id[1:]) - 1
        except (ValueError, IndexError):
            return
        with self._lock:
            if i not in self._stats:
                return
            now = self._clock()
            if status == 429:
                self._stats[i]["rate_limited"] += 1
                wait = retry_after if retry_after and retry_after > 0 else DEFAULT_RATE_COOLDOWN_S
                self._cooldown_until[i] = max(self._cooldown_until[i], now + min(wait, MAX_RATE_COOLDOWN_S))
            elif status in (401, 403):
                self._stats[i]["auth_failed"] += 1
                self._cooldown_until[i] = now + AUTH_COOLDOWN_S
            elif status >= 500 or status == 0:
                self._stats[i]["errors"] += 1
            elif 200 <= status < 300 and tokens > 0:
                self._stats[i]["tokens"] += int(tokens)

    def snapshot(self) -> List[Dict[str, object]]:
        """Operational view without any key material."""
        with self._lock:
            now = self._clock()
            return [
                {"id": f"k{i + 1}", "state": "cooling" if self._cooldown_until[i] > now else "healthy",
                 "cooldown_s": round(max(0.0, self._cooldown_until[i] - now), 1), **self._stats[i]}
                for i in range(len(self._keys))
            ]
