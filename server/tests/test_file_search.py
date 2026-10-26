import os
import sys
import unittest
from pathlib import Path

os.environ.setdefault("AKZSA_ENV", "development")
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from fastapi.testclient import TestClient
import app.main as main


class FileSearchEndpointTests(unittest.TestCase):
    def setUp(self):
        main.SERVER_TOKEN = ""
        self.client = TestClient(main.app)

    def test_server_refuses_phone_index_uploads(self):
        response = self.client.post("/v1/files/search", json={
            "query": "Find GST invoice for 25000",
            "files": [{"file_id": "doc-a", "filename": "invoice.pdf", "ocr_text": "private invoice contents"}],
        })
        self.assertEqual(response.status_code, 410, response.text)
        self.assertIn("local-only", response.json()["detail"])

    def test_requires_auth_before_deprecation_response(self):
        main.SERVER_TOKEN = "file-search-test-secret"
        try:
            response = self.client.post("/v1/files/search", json={"query": "invoice", "files": []})
            self.assertEqual(response.status_code, 401)
        finally:
            main.SERVER_TOKEN = ""

    def test_voice_file_search_command_is_deterministic(self):
        response = self.client.post("/v1/commands/interpret", json={
            "transcript": "Find me a GST invoice for 25000",
            "selected_text": None,
            "surrounding_text": None,
            "mode": "command",
        })
        self.assertEqual(response.status_code, 200, response.text)
        payload = response.json()
        self.assertEqual(payload["action"]["kind"], "find_file")
        self.assertIn("GST invoice", payload["action"]["query"])
        self.assertEqual(payload["model"], "akzsa-deterministic-rules-v1")

    def test_health_declares_private_on_device_file_search(self):
        response = self.client.get("/health")
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()["file_search_mode"], "on-device-only")
        self.assertFalse(response.json()["file_index_uploaded"])


if __name__ == "__main__":
    unittest.main()
