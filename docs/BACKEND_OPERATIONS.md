# Backend operations
Deploy: `server/render.yaml` (Render, singapore, free plan). Secrets (`GROQ_API_KEY`, `AKZSA_SERVER_TOKEN`) are set in the dashboard, never committed. `AKZSA_ENV=production` -> docs disabled, **503 if no token configured** (fail closed).
Endpoints: `GET /health`, `POST /v1/commands/interpret`, `POST /v1/voice/transcribe` (audio body, 20 MB cap).
Controls: per-install and per-IP sliding-window limits (defaults 30/90 commands/min, 40/120 voice/min (live dictation sends one request per phrase)), provider concurrency cap 8 (503 + Retry-After when saturated), 256 KB JSON cap, request IDs (`X-Request-ID`), security headers, provider 429->429, 401/403->503, other->502.
**Limits:** counters are in memory (reset on restart, per instance). Free-tier instances sleep: first request after idle is slow - the app shows a readable error and the user can retry. For >1 instance use Redis or an edge limiter. Configure trusted proxy headers before relying on IP limits.
Observability: logs are content-free. Alert on 5xx rate, 429 rate, p95 latency. Rotate `AKZSA_SERVER_TOKEN` by shipping an app update + switching the secret; plan Play Integrity next.
Tests: `cd server && python3 -m pytest -q` (42).
