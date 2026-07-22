# Retrieval ablation baseline

- Knowledge base: `1`
- Labeled questions: `12` (10 positive, 2 no-answer)
- Top K: `4`

| Strategy | Recall@K | MRR | No-answer accuracy |
|---|---:|---:|---:|
| LEXICAL | 1.0000 | 1.0000 | 1.0000 |
| VECTOR | 0.0000 | 0.0000 | 1.0000 |
| HYBRID | 1.0000 | 1.0000 | 1.0000 |
| HYBRID_RERANK | 1.0000 | 1.0000 | 1.0000 |

The VECTOR row is intentionally a valid baseline for this local run: the Compose acceptance stack disables external vector indexing, so it returns no eligible vector candidates. HYBRID_RERANK is evaluated on the same threshold-qualified candidate set and exposes deterministic score components for trace inspection.
