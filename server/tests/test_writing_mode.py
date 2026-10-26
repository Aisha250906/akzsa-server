import os
from unittest.mock import patch

os.environ.setdefault("AKZSA_ENV", "development")
from fastapi.testclient import TestClient
import app.main as main


class FakeResponse:
    status_code = 200
    def json(self):
        return {"choices": [{"message": {"content": '{"text":"Hello, world!","suggestions":[],"explanation":"Grammar corrected."}'}}]}


class FakeAsyncClient:
    def __init__(self, *args, **kwargs): pass
    async def __aenter__(self): return self
    async def __aexit__(self, *args): return False
    async def post(self, *args, **kwargs): return FakeResponse()


def test_writing_requires_text():
    client = TestClient(main.app)
    response = client.post("/v1/writing/generate", json={"action": "fix_grammar", "text": ""})
    assert response.status_code == 422


def test_translation_requires_target_language():
    client = TestClient(main.app)
    with patch.object(main, "GROQ_API_KEY", "test-key"):
        response = client.post("/v1/writing/generate", json={"action": "translate", "text": "Hello"})
    assert response.status_code == 422
    assert "target language" in response.json()["detail"]


def test_writing_returns_preview_without_applying_it():
    client = TestClient(main.app)
    with patch.object(main, "GROQ_API_KEY", "test-key"), patch.object(main.httpx, "AsyncClient", FakeAsyncClient):
        response = client.post("/v1/writing/generate", json={"action": "fix_grammar", "text": "hello world"})
    assert response.status_code == 200
    assert response.json()["text"] == "Hello, world!"
    assert response.json()["suggestions"] == []


def test_reply_suggestions_must_be_three():
    class BadReplyResponse:
        status_code = 200
        def json(self):
            return {"choices": [{"message": {"content": '{"text":"","suggestions":["Only one"],"explanation":"Replies"}'}}]}
    class BadReplyClient(FakeAsyncClient):
        async def post(self, *args, **kwargs): return BadReplyResponse()
    client = TestClient(main.app)
    with patch.object(main, "GROQ_API_KEY", "test-key"), patch.object(main.httpx, "AsyncClient", BadReplyClient):
        response = client.post("/v1/writing/generate", json={"action": "reply_suggestions", "text": "Can you send the report?"})
    assert response.status_code == 502
    assert "invalid response" in response.json()["detail"].lower()


def test_structured_schema_requires_three_suggestions_only_for_reply_action():
    captured = {}

    class CapturingClient(FakeAsyncClient):
        async def post(self, *args, **kwargs):
            captured["body"] = kwargs["json"]
            return FakeResponse()

    client = TestClient(main.app)
    with patch.object(main, "GROQ_API_KEY", "test-key"), patch.object(main.httpx, "AsyncClient", CapturingClient):
        response = client.post("/v1/writing/generate", json={"action": "fix_grammar", "text": "hello world"})
    assert response.status_code == 200
    schema = captured["body"]["response_format"]["json_schema"]["schema"]
    assert "minItems" not in schema["properties"]["suggestions"]

    class ReplyCapturingClient(FakeAsyncClient):
        async def post(self, *args, **kwargs):
            captured["reply_body"] = kwargs["json"]
            class GoodReplyResponse:
                status_code = 200
                def json(self):
                    return {"choices": [{"message": {"content": '{"text":"","suggestions":["Sure.","Happy to.","Certainly."] ,"explanation":"Three options"}'}}]}
            return GoodReplyResponse()

    with patch.object(main, "GROQ_API_KEY", "test-key"), patch.object(main.httpx, "AsyncClient", ReplyCapturingClient):
        response = client.post("/v1/writing/generate", json={"action": "reply_suggestions", "text": "Can you send the report?"})
    assert response.status_code == 200
    schema = captured["reply_body"]["response_format"]["json_schema"]["schema"]
    assert schema["properties"]["suggestions"]["minItems"] == 3
    assert schema["properties"]["suggestions"]["maxItems"] == 3


def test_voice_edit_requires_instruction():
    client = TestClient(main.app)
    with patch.object(main, "GROQ_API_KEY", "test-key"):
        response = client.post("/v1/writing/generate", json={"action": "voice_edit", "text": "I go college"})
    assert response.status_code == 422
    assert "instruction" in response.json()["detail"].lower()
