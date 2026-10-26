#!/usr/bin/env bash
# Downloads each model artifact listed below and prints its size and SHA-256 so they can be pinned in
# AKZSA-Android/app/src/main/java/com/nefeon/akzsa/recognition/ModelManifest.kt and docs/MODEL_MANIFEST.md.
# Also resolves the repo's current commit so the URLs can be changed from resolve/main/ to an immutable revision.
# Needs network access to huggingface.co (not available in the sandbox this repo was prepared in).
set -euo pipefail
REPO="felixhrdyn/mobileclip2-s0-onnx"
REV=$(curl -fsSL "https://huggingface.co/api/models/$REPO" | python3 -c 'import sys,json;print(json.load(sys.stdin)["sha"])')
echo "Immutable revision for $REPO: $REV"
for f in vision_model.onnx text_model_quantized.onnx tokenizer.json; do
  curl -fsSL -o "/tmp/$f" "https://huggingface.co/$REPO/resolve/$REV/$f"
  printf '%s  size=%s  sha256=%s\n' "$f" "$(stat -c %s "/tmp/$f")" "$(sha256sum "/tmp/$f" | cut -d' ' -f1)"
done
