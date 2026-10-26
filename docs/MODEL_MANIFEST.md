# Model manifest

Code: `recognition/ModelManifest.kt` (a test asserts internal consistency).
| Model | Role | Size | Revision | SHA-256 | Dim |
|---|---|---|---|---|---|
| mobileclip2-s0-onnx (vision_model.onnx, text_model_quantized.onnx, tokenizer.json) | default photo/image search | ~107 MB | `99ca0cde6c76c0c56fcfeace07eb3a340ba17fc0` (immutable) | **not pinned** (trust-on-first-use) | 512 |

The Gemma / EmbeddingGemma semantic stack was removed in 1.5.1.

**To close the gap:** run `scripts/pin-model-hashes.sh` on a networked machine, compare the printed hashes, paste them into `ModelManifest.kt`, then update the `isFullyPinned` assertion in `ModelArtifactVerifierTest`. Verify the model licence permits download-on-demand by an app.
Lifecycle: download to `.part` (resumes with HTTP Range) -> verify -> atomic move -> hash record -> re-verify before load -> corrupt = delete + re-download. Vector fingerprints include `id@revision/dim`; changing the model forces a one-time re-embed.
