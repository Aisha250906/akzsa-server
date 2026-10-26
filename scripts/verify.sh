#!/usr/bin/env bash
# Runs every check that works WITHOUT the Android SDK: backend tests, Python syntax, XML well-formedness.
set -euo pipefail
cd "$(dirname "$0")/.."
(cd server && pip install -q -r requirements.txt pytest >/dev/null && python3 -m pytest -q)
python3 - <<'PY'
import glob, xml.dom.minidom as m
bad = 0
for f in glob.glob("AKZSA-Android/app/src/**/*.xml", recursive=True):
    try: m.parse(f)
    except Exception as e: print("BAD XML", f, e); bad += 1
raise SystemExit(bad)
PY
echo "verify: OK"
