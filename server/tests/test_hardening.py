import os
import unittest
from unittest.mock import patch

os.environ.setdefault("AKZSA_ENV", "development")
from fastapi.testclient import TestClient

import app.main as main
from app import asr_guard, limits


class Clock:
    def __init__(self): self.t = 1000.0
    def __call__(self): return self.t


class FakeResponse:
    def __init__(self, status_code, payload): self.status_code, self._payload = status_code, payload
    def json(self): return self._payload


class FakeAsyncClient:
    responses, calls = [], []
    def __init__(self, *a, **k): pass
    async def __aenter__(self): return self
    async def __aexit__(self, *a): return False
    async def post(self, url, **kwargs):
        self.calls.append((url, kwargs)); return self.responses.pop(0)


UUID = "123e4567-e89b-42d3-a456-426614174000"


class LimiterTests(unittest.TestCase):
    def test_sliding_window_blocks_then_recovers(self):
        c = Clock(); lim = limits.SlidingWindowLimiter(3, 60, c)
        self.assertTrue(all(lim.check("k")[0] for _ in range(3)))
        ok, wait = lim.check("k")
        self.assertFalse(ok); self.assertGreaterEqual(wait, 1)
        c.t += 61
        self.assertTrue(lim.check("k")[0])

    def test_keys_are_independent(self):
        lim = limits.SlidingWindowLimiter(1, 60, Clock())
        self.assertTrue(lim.check("a")[0]); self.assertTrue(lim.check("b")[0]); self.assertFalse(lim.check("a")[0])

    def test_memory_is_bounded(self):
        c = Clock(); lim = limits.SlidingWindowLimiter(5, 60, c, max_keys=100)
        for i in range(1000):
            lim.check(f"k{i}"); c.t += 0.001
        self.assertLessEqual(len(lim._hits), 101)

    def test_fake_install_ids_cannot_bypass_ip_budget(self):
        lim = limits.IpAndInstallLimiter(per_install=100, per_ip=5, clock=Clock())
        results = [lim.check(f"{i:08x}-1111-4111-8111-111111111111", "1.2.3.4")[0] for i in range(10)]
        self.assertEqual(results.count(True), 5)

    def test_client_key_rejects_garbage_install_ids(self):
        self.assertEqual(limits.client_key("not-a-uuid", "9.9.9.9"), "ip:9.9.9.9")
        self.assertEqual(limits.client_key(UUID, "9.9.9.9"), "i:" + UUID)

    def test_invalid_limit_rejected(self):
        with self.assertRaises(ValueError): limits.SlidingWindowLimiter(0)


class AsrGuardTests(unittest.TestCase):
    def test_prompt_echo_detected(self):
        prompt = "Transcribe spoken English accurately. Keep names, technical terms and the speaker's meaning."
        self.assertTrue(asr_guard.is_prompt_echo("Transcribe spoken English accurately. Keep names, technical terms", prompt))

    def test_real_speech_is_not_flagged(self):
        prompt = "Glossary: AKZSA, NEFEON."
        self.assertFalse(asr_guard.is_prompt_echo("send the report to Rahul by Friday evening", prompt))
        self.assertFalse(asr_guard.is_prompt_echo("AKZSA", prompt))      # a single glossary word is legitimate speech

    def test_boilerplate_only_rejected_for_short_audio(self):
        self.assertTrue(asr_guard.is_silence_hallucination("Thanks for watching!", 10_000))
        self.assertFalse(asr_guard.is_silence_hallucination("Thanks for watching!", 500_000))
        self.assertFalse(asr_guard.is_silence_hallucination("Thank you", 10_000))   # real speech, never dropped

    def test_indic_text_is_normalised_without_crashing(self):
        self.assertFalse(asr_guard.is_prompt_echo("मेरा नाम राहुल है", "Hinglish sample"))


class HardeningEndpointTests(unittest.TestCase):
    def setUp(self):
        main.GROQ_API_KEY = "test-key"; main.SERVER_TOKEN = ""
        FakeAsyncClient.calls = []; FakeAsyncClient.responses = []
        self.client = TestClient(main.app)

    def test_request_id_and_security_headers(self):
        r = self.client.get("/health", headers={"X-Request-ID": "abcdef123456"})
        self.assertEqual(r.headers["x-request-id"], "abcdef123456")
        self.assertEqual(r.headers["x-content-type-options"], "nosniff")
        self.assertEqual(r.headers["cache-control"], "no-store")

    def test_bad_request_id_is_replaced(self):
        r = self.client.get("/health", headers={"X-Request-ID": "bad id\r\n"})
        self.assertRegex(r.headers["x-request-id"], r"^[0-9a-f]{32}$")

    def test_oversized_json_rejected_before_parsing(self):
        r = self.client.post("/v1/commands/interpret", content=b"x", headers={"Content-Length": str(300 * 1024), "Content-Type": "application/json"})
        self.assertEqual(r.status_code, 413)

    def test_auth_still_enforced_with_constant_time_compare(self):
        main.SERVER_TOKEN = "s3cret-token"
        self.assertEqual(self.client.post("/v1/commands/interpret", json={"transcript": "new paragraph"}).status_code, 401)
        self.assertEqual(self.client.post("/v1/commands/interpret", json={"transcript": "new paragraph"}, headers={"Authorization": "Bearer wrong"}).status_code, 401)
        ok = self.client.post("/v1/commands/interpret", json={"transcript": "new paragraph"}, headers={"Authorization": "Bearer s3cret-token"})
        self.assertEqual(ok.status_code, 200)

    def test_non_ascii_authorization_header_does_not_crash(self):
        main.SERVER_TOKEN = "s3cret-token"
        r = self.client.post("/v1/commands/interpret", json={"transcript": "new paragraph"}, headers={"Authorization": "Bearer tökén".encode("latin-1")})
        self.assertEqual(r.status_code, 401)

    def test_command_rate_limit_returns_429_with_retry_after(self):
        with patch.object(main, "COMMAND_LIMITER", limits.IpAndInstallLimiter(2, 2)):
            codes = [self.client.post("/v1/commands/interpret", json={"transcript": "new paragraph"}, headers={"X-AKZSA-Install-Id": UUID}) for _ in range(3)]
        self.assertEqual([c.status_code for c in codes], [200, 200, 429])
        self.assertIn("retry-after", codes[2].headers)

    def test_voice_rate_limit(self):
        FakeAsyncClient.responses = [FakeResponse(200, {"text": "hello there my friend"})] * 5
        with patch.object(main, "VOICE_LIMITER", limits.IpAndInstallLimiter(1, 1)), patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            first = self.client.post("/v1/voice/transcribe", content=b"a" * 60000, headers={"Content-Type": "audio/mp4", "X-AKZSA-Polish": "false"})
            second = self.client.post("/v1/voice/transcribe", content=b"a" * 60000, headers={"Content-Type": "audio/mp4", "X-AKZSA-Polish": "false"})
        self.assertEqual((first.status_code, second.status_code), (200, 429))

    def test_english_asr_prompt_has_no_instruction_text(self):
        FakeAsyncClient.responses = [FakeResponse(200, {"text": "see you at five"})]
        with patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            r = self.client.post("/v1/voice/transcribe", content=b"a" * 60000, headers={"Content-Type": "audio/mp4", "X-AKZSA-Language": "en", "X-AKZSA-Polish": "false"})
        self.assertEqual(r.status_code, 200)
        data = FakeAsyncClient.calls[0][1]["data"]
        self.assertNotIn("Transcribe", data.get("prompt", ""))
        self.assertEqual(data["language"], "en")

    def test_english_glossary_is_sent_as_glossary_only(self):
        import base64
        FakeAsyncClient.responses = [FakeResponse(200, {"text": "ask Rahul about it"})]
        vocab = base64.urlsafe_b64encode("AKZSA, Rahul".encode()).decode()
        with patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            self.client.post("/v1/voice/transcribe", content=b"a" * 60000, headers={"Content-Type": "audio/mp4", "X-AKZSA-Language": "en", "X-AKZSA-Polish": "false", "X-AKZSA-Vocabulary-B64": vocab})
        self.assertTrue(FakeAsyncClient.calls[0][1]["data"]["prompt"].startswith("Glossary:"))

    def test_prompt_echo_transcript_is_rejected_not_inserted(self):
        import base64
        vocab = base64.urlsafe_b64encode("alpha beta gamma delta epsilon".encode()).decode()
        FakeAsyncClient.responses = [FakeResponse(200, {"text": "Glossary: alpha beta gamma delta epsilon."})]
        with patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            r = self.client.post("/v1/voice/transcribe", content=b"a" * 60000, headers={"Content-Type": "audio/mp4", "X-AKZSA-Language": "en", "X-AKZSA-Polish": "false", "X-AKZSA-Vocabulary-B64": vocab})
        self.assertEqual(r.status_code, 422)

    def test_silence_boilerplate_rejected_on_short_audio(self):
        FakeAsyncClient.responses = [FakeResponse(200, {"text": "Thanks for watching!"})]
        with patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            r = self.client.post("/v1/voice/transcribe", content=b"a" * 2000, headers={"Content-Type": "audio/mp4", "X-AKZSA-Language": "en", "X-AKZSA-Polish": "false"})
        self.assertEqual(r.status_code, 422)

    def test_command_provider_429_and_auth_errors_are_mapped(self):
        for upstream, expected in ((429, 429), (401, 503), (500, 502)):
            FakeAsyncClient.responses = [FakeResponse(upstream, {})]
            with patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
                r = self.client.post("/v1/commands/interpret", json={"transcript": "write a polite reply", "mode": "write"})
            self.assertEqual(r.status_code, expected, upstream)

    def test_error_responses_never_echo_user_text(self):
        FakeAsyncClient.responses = [FakeResponse(500, {})]
        with patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            r = self.client.post("/v1/commands/interpret", json={"transcript": "my secret ssn 123-45-6789", "mode": "write"})
        self.assertNotIn("123-45-6789", r.text)

    def test_production_hides_docs(self):
        with patch.dict(os.environ, {"AKZSA_ENV": "production"}):
            self.assertTrue(main.is_production())
        self.assertFalse(main.is_production())

    def test_production_without_token_fails_closed(self):
        main.SERVER_TOKEN = ""
        with patch.dict(os.environ, {"AKZSA_ENV": "production"}):
            r = self.client.post("/v1/commands/interpret", json={"transcript": "new paragraph"})
        self.assertEqual(r.status_code, 503)

    def test_concurrency_cap_returns_503_when_saturated(self):
        import asyncio
        sem = asyncio.Semaphore(0)
        async def timeout_after_closing(awaitable, timeout):
            # The mocked wait_for raises before awaiting its argument; close the
            # acquire coroutine so the test itself does not emit an unawaited warning.
            close = getattr(awaitable, "close", None)
            if close is not None:
                close()
            raise asyncio.TimeoutError

        async def run():
            with patch.object(main, "PROVIDER_SLOTS", sem):
                with patch("asyncio.wait_for", side_effect=timeout_after_closing):
                    async with main.provider_slot():
                        pass
        from fastapi import HTTPException
        with self.assertRaises(HTTPException) as ctx: asyncio.run(run())
        self.assertEqual(ctx.exception.status_code, 503)


if __name__ == "__main__":
    unittest.main()
