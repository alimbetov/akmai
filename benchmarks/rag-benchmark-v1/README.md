# rag-benchmark-v1 dataset

This directory defines the external release-quality corpus contract for AkmAI.

The checked-in Java smoke corpus is intentionally not release-qualified. A release corpus is loaded from a dataset root containing:

- `manifest.json`
- `documents.jsonl`
- `queries.jsonl`

## manifest.json

```json
{
  "benchmarkVersion": "rag-benchmark-v1",
  "corpusVersion": "2026-10-legal-medical-technical-v1",
  "releaseQualified": true,
  "metadata": {
    "owner": "quality",
    "annotationPolicy": "double-review-v1"
  }
}
```

## documents.jsonl

One JSON document per line:

```json
{"id":"contract-001","title":"Contract 001","text":"...","source":"file://contract-001.pdf","language":"ru","domain":"LEGAL","accessLevel":1,"metadata":{"version":"1"}}
```

## queries.jsonl

One labelled query per line:

```json
{"id":"q-001","language":"ru","domain":"LEGAL","queryClass":"IDENTIFIER_SEMANTIC","difficulty":"MEDIUM","question":"Какие штрафы в договоре KZ-2026-001847?","answerable":true,"relevantDocumentIds":["contract-001"],"relevantChunkIds":["<stable-canonical-chunk-id>"],"forbiddenChunkIds":[]}
```

Unanswerable cases MUST use empty `relevantDocumentIds` and `relevantChunkIds`.

## Release qualification contract

`RagBenchmarkDatasetValidator` rejects a release dataset unless it satisfies all of the following:

- at least 300 labelled queries;
- 20–30% unanswerable cases;
- all 11 target languages: `kk`, `ru`, `en`, `zh`, `de`, `fr`, `es`, `pt`, `it`, `tr`, `el`;
- LEGAL, MEDICAL and TECHNICAL domains;
- FACTUAL, PARAPHRASE, LEXICAL_EXACT, IDENTIFIER_ONLY, IDENTIFIER_SEMANTIC, REFERENCE, NUMERIC, TEMPORAL, MULTI_INTENT, COMPARISON, CROSS_LANGUAGE and UNANSWERABLE query classes;
- unique document/query ids;
- document-level and chunk-level truth for every answerable query.

The corpus should be curated and reviewed; do not inflate it by duplicating or mechanically paraphrasing a small number of cases merely to reach the minimum size.

## Intended execution

The release runner must execute the production path:

`dataset -> KnowledgeIngestionPort -> semantic chunking -> PostgreSQL/pgvector -> RetrievalPlanner -> retrieval lanes -> fusion -> reranker -> context -> RagQuestionService -> citations/grounding -> metrics`

No mocked retriever or relevance-aware scorer is permitted in release qualification.
