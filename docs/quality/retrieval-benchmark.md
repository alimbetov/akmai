# Phase B Retrieval Quality Benchmark

This document is the regression contract and closeout record for Phase B retrieval quality.

## Metrics

- Recall@5 and Recall@10 measure whether relevant chunks survive retrieval.
- MRR measures how early the first relevant chunk appears.
- nDCG@10 measures ranking quality when multiple chunks are relevant.

## Languages

Every production retrieval-quality change is evaluated across:

- KK
- RU
- EN
- ZH

## Phase B delivery map

| Stage | Capability | Regression contract |
| --- | --- | --- |
| B1 | Reusable retrieval benchmark | Deterministic Recall@K, MRR and nDCG fixtures |
| B2 | Real bounded semantic reranker | Reranking must preserve Recall@K and improve or preserve ranking quality |
| B3 | Multilingual lexical retrieval | KK/RU/EN/ZH lexical retrieval remains language-aware |
| B4 | Semantic chunking depth | Full legal ancestry and atomic medical facts are preserved |
| B5 | Query decomposition | Multi-intent questions produce bounded, deduplicated retrieval units |
| B6 | Deterministic planner tuning | Equal retrieval units produce stable step IDs, ordering and dependencies |

## Final quality contracts

### Multilingual non-regression

The final Phase B ranked fixtures must satisfy, per language:

- final Recall@5 >= B1 baseline Recall@5;
- final MRR >= B1 baseline MRR;
- final nDCG@5 >= B1 baseline nDCG@5;
- all relevant deterministic fixtures remain inside Recall@5.

The metric arithmetic and deterministic ranking contract are implemented by:

`MultilingualRetrievalQualityBaselineTest`

and:

`MultilingualRetrievalQualityRegressionTest`

Those tests use deterministic ranked fixtures. They do not claim to execute the retrieval pipeline.

A corpus-backed PostgreSQL lexical gate is implemented by:

`PostgresRetrievalIntegrationTest.corpusBackedLexicalQualityGateCoversAllTargetLanguages`

It persists KK/RU/EN/ZH corpus rows, executes the real PostgreSQL lexical repository and evaluates the returned ranking with Recall@5, MRR and nDCG@5.

Full vector + Ollama + fusion + reranker evaluation remains the separate live E2E gate described below.

### Legal hierarchy acceptance

Legal semantic chunking must preserve the complete ancestry chain where present:

`LAW -> Part -> Chapter -> Section -> Article -> Paragraph -> Subparagraph`

Equivalent KK/RU/EN/ZH legal headings are covered by `SemanticChunkingDepthTest`.

### Medical atomic-fact acceptance

The following medical semantic units are atomic retrieval facts and must not be merged solely to reach a target token size:

- INDICATION
- DOSAGE
- CONTRAINDICATION
- INTERACTION
- MONITORING

KK/RU/EN/ZH fixtures cover all five fact types.

### Multi-intent acceptance

A coordinated multi-intent clause retains the original clause and may produce bounded subqueries. Multi-sentence input is decomposed into bounded sentence retrieval units; the full multi-sentence text is not duplicated as an extra retrieval unit, avoiding unnecessary identifier and planner fan-out.

The planner must preserve deterministic execution topology for every retrieval unit:

`VECTOR -> LEXICAL -> REFERENCE`

while respecting the configured decomposition bound and duplicate suppression.

### Identifier + semantic acceptance

When a query contains an identifier and semantic intent, the plan must remain:

`IDENTIFIER -> VECTOR / LEXICAL -> REFERENCE`

VECTOR and LEXICAL depend on the identifier lookup, and REFERENCE depends on both semantic branches.

### Deterministic planning

Retrieval step identity must not depend on random `QueryChunk.id` values or JVM restart state.

Stable step identity is derived from the canonical retrieval unit and retrieval type. Equivalent identifier sets are normalized with deterministic ordering before identity generation.

## Rules

1. A feature may not replace the baseline with easier cases.
2. Reranking must preserve or improve Recall@K; ranking gains are measured by MRR/nDCG.
3. Language-specific retrieval changes must report per-language metrics, not only an average.
4. Deterministic benchmark and acceptance tests run in normal CI.
5. Live Ollama/pgvector evaluation is a separate E2E gate and must use the same benchmark case/result model. Normal CI proves deterministic metric logic, PostgreSQL lexical retrieval, planner/chunking contracts and reranker fallback semantics; it does not claim live model quality.
6. Any accepted quality regression requires an explicit documented reason and threshold change.
7. Phase B is not green until the final exact PR-head SHA completes the GitHub Actions `verify` job successfully.

## Phase B sign-off gate

Required final sequence:

```text
B6 deterministic planner
-> multilingual retrieval regression
-> Recall@K / MRR / nDCG non-regression
-> legal hierarchy acceptance
-> medical atomic-fact acceptance
-> multi-intent acceptance
-> identifier + semantic acceptance
-> full verify
-> exact-SHA GitHub Actions success
```

PR #12 remains draft until the final exact SHA satisfies this gate.
