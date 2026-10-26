"""Compatibility shim. The ONE canonical backend lives in server/app/main.py.

Run from the repo root with:  uvicorn main:app
(Deploy with server/render.yaml, which runs server/app/main.py directly.)
"""
"""Application entry point."""

from app.main import app



