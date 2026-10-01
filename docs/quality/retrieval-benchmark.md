# Phase B Retrieval Quality Benchmark

This benchmark is the regression contract for retrieval-quality changes.

## Metrics

- Recall@5 and Recall@10 measure whether relevant chunks survive retrieval.
- MRR measures how early the first relevant chunk appears.
- nDCG@10 measures ranking quality when multiple chunks are relevant.

## Languages

Every production quality change must be evaluated on KK, RU, EN and ZH cases.

## Rules

1. A feature may not replace the baseline with easier cases.
2. Reranking must preserve or improve Recall@K; ranking gains are measured by MRR/nDCG.
3. Language-specific retrieval changes must report per-language metrics, not only an average.
4. Deterministic benchmark tests run in normal CI.
5. Live Ollama/pgvector evaluation is a separate E2E gate and must use the same case/result model.
6. Any accepted regression requires an explicit documented reason and threshold change.

The initial deterministic fixtures validate the metric/evaluator infrastructure. Phase B extends them with real corpus-backed retrieval cases before quality sign-off.
