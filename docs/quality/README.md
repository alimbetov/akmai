# Quality documentation

Classification: **CURRENT QUALITY INDEX**

Quality evidence in AkmAI has several distinct purposes. Do not mix regression evidence, release qualification and external domain-validity claims.

## Documents

| Document | Purpose |
|---|---|
| `retrieval-benchmark.md` | Retrieval benchmark contract and quality metrics. |
| `retrieval-storage-performance-benchmark.md` | Storage/retrieval performance methodology. |
| `rag-assurance-contracts-technical-spec.md` | RAG assurance contracts and validation requirements. |
| `rag-assurance-ra02-ra08-technical-spec.md` | Detailed assurance work for the RA02-RA08 line. |
| `results/` | Retained quality/result artifacts where committed. |
| `../../benchmarks/rag-benchmark-v1/README.md` | Controlled synthetic benchmark dataset/materialization contract. |

## Evidence classes

### 1. Deterministic regression evidence

The controlled synthetic corpus verifies reproducible behavior across known query classes, answerability cases, languages and domains.

It is appropriate for:

- CI regression detection;
- retrieval metric comparison;
- abstention regressions;
- policy/graph replay;
- release-engineering gates.

It is not, by itself, evidence of real-world legal/medical correctness.

### 2. Live release qualification

The integrated v1.1 qualification requires quality, performance and semantic-grounding evidence for the same candidate revision.

The approved baseline must be immutable/reviewed rather than generated ad hoc by the candidate being evaluated.

### 3. External-validity evidence

Broad claims such as legal/medical/technical accuracy require a separately versioned, human-reviewed representative corpus with evidence labels and adjudication records.

## Adaptive graph quality

Evaluate the Adaptive Association Graph by incremental retrieval/answer utility, not by graph size.

Recommended metrics:

- grounded-answer-rate delta;
- evidence recall delta;
- citation recall/coverage delta;
- abstention delta;
- graph-added chunk survival/citation rate;
- contradiction rate;
- context-token delta;
- p95/p99 latency delta;
- failure/timeout delta.

The safest comparison modes are:

```text
BASE_ONLY
GRAPH_SHADOW
GRAPH_APPEND_ONLY
GRAPH_COMPETITIVE
```

A graph feature should not be promoted merely because it returns more context.

## Self-optimizing policy quality

Policy promotion must preserve a simultaneous CONTROL cohort and enforce configured failure/regression gates. Shadow evaluation must remain non-user-visible.

## Quality documentation rule

Every benchmark result should identify:

- exact Git SHA/tag;
- corpus and corpus version;
- embedding/retrieval/learning policy versions;
- runtime configuration relevant to the result;
- hardware/model topology for performance claims;
- whether the result is synthetic regression evidence, release qualification or external-validity evidence.
