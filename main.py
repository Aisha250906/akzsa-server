"""Compatibility shim. The ONE canonical backend lives in server/app/main.py.

Run from the repo root with:  uvicorn main:app
(Deploy with server/render.yaml, which runs server/app/main.py directly.)
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "app"))
from app.main import app  # noqa: E402,F401
