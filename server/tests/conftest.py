import os
import sys
from pathlib import Path

os.environ.setdefault("AKZSA_ENV", "development")
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pytest


@pytest.fixture(autouse=True)
def _reset_abuse_controls():
    import app.main as main
    main.COMMAND_LIMITER.reset()
    main.VOICE_LIMITER.reset()
    yield
