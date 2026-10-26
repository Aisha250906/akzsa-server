# Search quality

Corpus: `AKZSA-Android/app/src/test/resources/corpus/{files,queries}.tsv` (20 files, 31 queries: exact filename, duplicate names, searchable PDF, scanned PDF/OCR, screenshots, image labels + synonyms, Hindi, Kannada, Hinglish, malformed files, no-result). Run: `SearchRegressionCorpusTest`.
Baseline on the supplied engine: top-1 27/28 (miss: identifier query tied at the score cap). After the fix: **top-1 28/28, top-3 28/28, recall@10 1.000, no-result 3/3**.
**Limits of this evidence:** synthetic, small, lexical path only, authored by the same team that wrote the fix. It protects against regressions; it does **not** estimate real-world accuracy or semantic-model quality. Fusion mechanics are unit-tested with synthetic ranked lists, not real embeddings. Add your own anonymised file set and device-measured latency/RAM before choosing fusion weights.
Provisional targets (set from this baseline, to be re-set on real data): top-1 >= 0.90, top-3 >= 0.95, no-result precision 1.0 on the corpus; every category enforced individually.
