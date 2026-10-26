import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault("AKZSA_ENV", "development")
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from fastapi.testclient import TestClient
import app.main as main


class FakeResponse:
    def __init__(self, status_code, payload):
        self.status_code = status_code
        self._payload = payload

    def json(self):
        return self._payload


class FakeAsyncClient:
    responses = []
    calls = []

    def __init__(self, *args, **kwargs):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def post(self, url, **kwargs):
        self.calls.append((url, kwargs))
        return self.responses.pop(0)


class VoiceEndpointTests(unittest.TestCase):
    def setUp(self):
        main.GROQ_API_KEY = "test-key"
        main.SERVER_TOKEN = ""
        FakeAsyncClient.calls = []
        self.client = TestClient(main.app)

    def test_whisper_transcription_and_cleanup(self):
        FakeAsyncClient.responses = [
            FakeResponse(200, {"text": "um let's meet at four pm no actually five pm"}),
            FakeResponse(200, {"choices": [{"message": {"content": '{"text":"Let\'s meet at five PM."}'}}]}),
        ]
        with patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            response = self.client.post(
                "/v1/voice/transcribe",
                content=b"a" * 1024,
                headers={
                    "Content-Type": "audio/mp4",
                    "X-AKZSA-Language": "en",
                    "X-AKZSA-Polish": "true",
                },
            )
        self.assertEqual(response.status_code, 200, response.text)
        payload = response.json()
        self.assertEqual(payload["transcript"], "um let's meet at four pm no actually five pm")
        self.assertEqual(payload["text"], "Let's meet at five PM.")
        self.assertTrue(payload["polish_applied"])
        self.assertIn("/audio/transcriptions", FakeAsyncClient.calls[0][0])
        self.assertIn("/chat/completions", FakeAsyncClient.calls[1][0])

    def test_raw_transcript_when_cleanup_is_disabled(self):
        FakeAsyncClient.responses = [FakeResponse(200, {"text": "hello world"})]
        with patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            response = self.client.post(
                "/v1/voice/transcribe",
                content=b"a" * 1024,
                headers={"Content-Type": "audio/mp4", "X-AKZSA-Language": "en", "X-AKZSA-Polish": "false"},
            )
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()["text"], "hello world")
        self.assertFalse(response.json()["polish_applied"])
        self.assertEqual(len(FakeAsyncClient.calls), 1)

    def test_rejects_unsupported_content_type(self):
        response = self.client.post(
            "/v1/voice/transcribe",
            content=b"x" * 1024,
            headers={"Content-Type": "text/plain"},
        )
        self.assertEqual(response.status_code, 415)

    def test_rejects_unsupported_language(self):
        response = self.client.post(
            "/v1/voice/transcribe",
            content=b"x" * 1024,
            headers={"Content-Type": "audio/mp4", "X-AKZSA-Language": "fr"},
        )
        self.assertEqual(response.status_code, 422)


    def test_kannada_transcription_passes_kn_language_and_preserves_kannada_prompt(self):
        FakeAsyncClient.responses = [FakeResponse(200, {"text": "ನಮಸ್ಕಾರ"})]
        with patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            response = self.client.post(
                "/v1/voice/transcribe", content=b"a" * 1024,
                headers={"Content-Type": "audio/mp4", "X-AKZSA-Language": "kn", "X-AKZSA-Polish": "false"},
            )
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()["language"], "kn")
        self.assertEqual(FakeAsyncClient.calls[0][1]["data"]["language"], "kn")
        self.assertIn("Kannada script", FakeAsyncClient.calls[0][1]["data"]["prompt"])

    def test_hinglish_omits_single_language_hint_for_auto_detection(self):
        FakeAsyncClient.responses = [FakeResponse(200, {"text": "Mera order deliver nahi hua"})]
        with patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            response = self.client.post(
                "/v1/voice/transcribe", content=b"a" * 1024,
                headers={"Content-Type": "audio/mp4", "X-AKZSA-Language": "hinglish", "X-AKZSA-Polish": "false"},
            )
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()["language"], "hinglish")
        self.assertNotIn("language", FakeAsyncClient.calls[0][1]["data"])
        self.assertIn("code-switched Hindi and English", FakeAsyncClient.calls[0][1]["data"]["prompt"])

    def test_accepts_kn_in_language_tag_alias(self):
        FakeAsyncClient.responses = [FakeResponse(200, {"text": "ನಮಸ್ಕಾರ"})]
        with patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            response = self.client.post(
                "/v1/voice/transcribe", content=b"a" * 1024,
                headers={"Content-Type": "audio/mp4", "X-AKZSA-Language": "kn-IN", "X-AKZSA-Polish": "false"},
            )
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(FakeAsyncClient.calls[0][1]["data"]["language"], "kn")

    def test_hinglish_cleanup_prompt_keeps_mixed_language_and_latin_script(self):
        FakeAsyncClient.responses = [
            FakeResponse(200, {"text": "मेरा order deliver नहीं हुआ"}),
            FakeResponse(200, {"choices": [{"message": {"content": '{"text":"Mera order deliver nahi hua."}'}}]}),
        ]
        with patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            response = self.client.post(
                "/v1/voice/transcribe", content=b"a" * 1024,
                headers={"Content-Type": "audio/mp4", "X-AKZSA-Language": "hinglish", "X-AKZSA-Polish": "true"},
            )
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()["text"], "Mera order deliver nahi hua.")
        cleanup_prompt = FakeAsyncClient.calls[1][1]["json"]["messages"][0]["content"]
        self.assertIn("mixed Hindi-English Hinglish", cleanup_prompt)
        self.assertIn("do not convert it to Devanagari", cleanup_prompt)

    def test_rejects_tiny_audio(self):
        response = self.client.post(
            "/v1/voice/transcribe",
            content=b"tiny",
            headers={"Content-Type": "audio/mp4"},
        )
        self.assertEqual(response.status_code, 422)

    def test_cleanup_failure_returns_raw_asr_text(self):
        FakeAsyncClient.responses = [
            FakeResponse(200, {"text": "um hello there"}),
            FakeResponse(503, {"error": "provider unavailable"}),
        ]
        with patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            response = self.client.post(
                "/v1/voice/transcribe", content=b"a" * 1024,
                headers={"Content-Type": "audio/mp4", "X-AKZSA-Language": "en", "X-AKZSA-Polish": "true"},
            )
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()["text"], "um hello there")
        self.assertFalse(response.json()["polish_applied"])

    def test_rejects_oversized_body(self):
        old_limit = main.MAX_VOICE_AUDIO_BYTES
        main.MAX_VOICE_AUDIO_BYTES = 512
        try:
            response = self.client.post(
                "/v1/voice/transcribe", content=b"a" * 1024,
                headers={"Content-Type": "audio/mp4"},
            )
            self.assertEqual(response.status_code, 413)
        finally:
            main.MAX_VOICE_AUDIO_BYTES = old_limit

    def test_requires_token_when_server_token_is_configured(self):
        main.SERVER_TOKEN = "private-test-token"
        try:
            response = self.client.post(
                "/v1/voice/transcribe", content=b"a" * 1024,
                headers={"Content-Type": "audio/mp4"},
            )
            self.assertEqual(response.status_code, 401)
        finally:
            main.SERVER_TOKEN = ""


class LiveDictationPhraseTests(unittest.TestCase):
    """One live-dictation phrase: WAV body, no polish, session context as the end of the Whisper prompt."""

    def setUp(self):
        main.GROQ_API_KEY = "test-key"
        main.SERVER_TOKEN = ""
        FakeAsyncClient.calls = []
        self.client = TestClient(main.app)

    def test_wav_phrase_is_forwarded_as_wav_with_session_context(self):
        import base64
        FakeAsyncClient.responses = [FakeResponse(200, {"text": "ab kal milte hain"})]
        context = base64.urlsafe_b64encode("Aaj meeting mein client ka invoice".encode()).decode()
        with patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            response = self.client.post(
                "/v1/voice/transcribe", content=b"RIFF" + b"a" * 60000,
                headers={"Content-Type": "audio/wav", "X-AKZSA-Language": "hinglish", "X-AKZSA-Polish": "false", "X-AKZSA-Context-B64": context},
            )
        self.assertEqual(response.status_code, 200, response.text)
        kwargs = FakeAsyncClient.calls[0][1]
        self.assertEqual(kwargs["files"]["file"][0], "akzsa-dictation.wav")
        self.assertEqual(kwargs["files"]["file"][2], "audio/wav")
        self.assertTrue(kwargs["data"]["prompt"].endswith("Aaj meeting mein client ka invoice"))

    def test_verbatim_context_repeat_is_not_inserted(self):
        import base64
        FakeAsyncClient.responses = [FakeResponse(200, {"text": "Aaj meeting mein client ka invoice bhejna hai"})]
        context = base64.urlsafe_b64encode("Aaj meeting mein client ka invoice bhejna hai".encode()).decode()
        with patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            response = self.client.post(
                "/v1/voice/transcribe", content=b"RIFF" + b"a" * 60000,
                headers={"Content-Type": "audio/wav", "X-AKZSA-Language": "en", "X-AKZSA-Polish": "false", "X-AKZSA-Context-B64": context},
            )
        self.assertEqual(response.status_code, 422)

    def test_hinglish_language_hint_is_opt_in(self):
        FakeAsyncClient.responses = [FakeResponse(200, {"text": "kal milte hain"})]
        with patch.object(main, "HINGLISH_LANGUAGE_HINT", "hi"), patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
            response = self.client.post(
                "/v1/voice/transcribe", content=b"a" * 60000,
                headers={"Content-Type": "audio/mp4", "X-AKZSA-Language": "hinglish", "X-AKZSA-Polish": "false"},
            )
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(FakeAsyncClient.calls[0][1]["data"]["language"], "hi")


if __name__ == "__main__":
    unittest.main()
