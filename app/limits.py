"""Small, dependency-free abuse controls for a single-instance deployment.

State is in memory, per process. That is correct for the current Render free-tier single instance and is
documented as a limit in docs/BACKEND_OPERATIONS.md: with more than one instance, move the counters to a shared
store (Redis) or an edge rate limiter. Nothing here stores request content, only counters keyed by an opaque
client key (install id or IP) and timestamps.
"""
from __future__ import annotations

import re
import threading
import time
from collections import deque
from typing import Callable, Deque, Dict, Optional, Tuple

_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def client_key(install_id: Optional[str], ip: Optional[str]) -> str:
    """Opaque rate-limit key. A syntactically valid install id wins; otherwise the peer IP is used.

    An attacker can mint new install ids, so the IP limiter below is the backstop (see IpAndInstallLimiter)."""
    if install_id and _UUID.match(install_id.strip().lower()):
        return "i:" + install_id.strip().lower()
    return "ip:" + (ip or "unknown")


class SlidingWindowLimiter:
    def __init__(self, limit: int, window_s: float = 60.0, clock: Callable[[], float] = time.monotonic, max_keys: int = 20_000):
        if limit < 1 or window_s <= 0:
            raise ValueError("limit and window must be positive")
        self.limit, self.window_s, self._clock, self._max_keys = limit, window_s, clock, max_keys
        self._hits: Dict[str, Deque[float]] = {}
        self._lock = threading.Lock()

    def check(self, key: str) -> Tuple[bool, int]:
        """Records a hit. Returns (allowed, retry_after_seconds)."""
        now = self._clock()
        with self._lock:
            q = self._hits.setdefault(key, deque())
            cutoff = now - self.window_s
            while q and q[0] <= cutoff:
                q.popleft()
            if len(q) >= self.limit:
                return False, max(1, int(q[0] + self.window_s - now) + 1)
            q.append(now)
            if len(self._hits) > self._max_keys:
                self._evict(cutoff)
            return True, 0

    def _evict(self, cutoff: float) -> None:
        for k in [k for k, v in self._hits.items() if not v or v[-1] <= cutoff]:
            del self._hits[k]
        while len(self._hits) > self._max_keys:          # still too many live keys: drop the oldest-touched
            oldest = min(self._hits, key=lambda k: self._hits[k][-1])
            del self._hits[oldest]

    def reset(self) -> None:
        with self._lock:
            self._hits.clear()


class IpAndInstallLimiter:
    """Both the per-install and the per-IP budget must allow the request. The IP budget is larger so several
    people behind one NAT are fine, but rotating fake install ids cannot bypass it."""

    def __init__(self, per_install: int, per_ip: int, window_s: float = 60.0, clock: Callable[[], float] = time.monotonic):
        self.per_install = SlidingWindowLimiter(per_install, window_s, clock)
        self.per_ip = SlidingWindowLimiter(per_ip, window_s, clock)

    def check(self, install_id: Optional[str], ip: Optional[str]) -> Tuple[bool, int]:
        ok, wait = self.per_ip.check("ip:" + (ip or "unknown"))
        if not ok:
            return False, wait
        return self.per_install.check(client_key(install_id, ip))

    def reset(self) -> None:
        self.per_install.reset()
        self.per_ip.reset()
