# rag-benchmark-v1 dataset

This directory defines the release-quality corpus contract for AkmAI.

A dataset root contains:

- `manifest.json`
- `documents.jsonl`
- `queries.jsonl`

The branch includes a deterministic versioned **CONTROLLED_SYNTHETIC** corpus.
`generate_controlled_corpus.py` contains the multilingual fixture surfaces and
chunk-identity primitives; `materialize_controlled_corpus.py` is the canonical
materializer and deliberately decorrelates language rotation from query classes.
The default release workflow materializes the corpus into
`benchmarks/rag-benchmark-v1/release` and verifies its labels against the
production `HierarchicalChunker` before any live model run.

The controlled corpus is a release-engineering and regression gate. It is **not**
a claim of real-world legal/medical/technical task accuracy and does not replace
a separately curated, human-reviewed corpus for external quality claims.

## Generate the controlled corpus

```bash
python3 benchmarks/rag-benchmark-v1/materialize_controlled_corpus.py \
  --out benchmarks/rag-benchmark-v1/release
```

The current materializer produces:

- 330 labelled queries;
- 255 answerable cases / documents;
- 75 unanswerable cases (22.7%);
- all 11 target languages: `kk`, `ru`, `en`, `zh`, `de`, `fr`, `es`, `pt`, `it`, `tr`, `el`;
- LEGAL, MEDICAL and TECHNICAL domains;
- all required query classes and EASY/MEDIUM/HARD difficulty;
- deterministic document-level and child-chunk-level relevance truth.

`expectedSearchableChunkId` metadata and query `relevantChunkIds` are calculated
with the same byte-level identity format as `ChunkIdentity`. The Java
`ControlledBenchmarkCorpusContractTest` then independently chunks every document
with production chunking code and rejects any identity drift.

## manifest.json

```json
{
  "benchmarkVersion": "rag-benchmark-v1",
  "corpusVersion": "controlled-synthetic-2026-10-v1",
  "releaseQualified": true,
  "metadata": {
    "owner": "akmai-quality",
    "annotationPolicy": "deterministic-controlled-fixture-v1",
    "corpusKind": "CONTROLLED_SYNTHETIC",
    "realWorldQualityClaim": "false"
  }
}
```

## documents.jsonl

One JSON document per line:

```json
{"id":"bench-doc-001","title":"AkmAI Legal Fixture 001","text":"...","source":"benchmark://controlled/bench-doc-001","language":"ru","domain":"LEGAL","accessLevel":1,"metadata":{"corpusKind":"CONTROLLED_SYNTHETIC","expectedSearchableChunkId":"cc1_..."}}
```

## queries.jsonl

One labelled query per line:

```json
{"id":"q-001","language":"ru","domain":"LEGAL","queryClass":"FACTUAL","difficulty":"EASY","question":"...","answerable":true,"relevantDocumentIds":["bench-doc-001"],"relevantChunkIds":["cc1_..."],"forbiddenChunkIds":[]}
```

Unanswerable cases MUST use empty `relevantDocumentIds` and `relevantChunkIds`.

## Release qualification contract

`RagBenchmarkDatasetValidator` rejects a release dataset unless it satisfies all
of the following:

- at least 300 labelled queries;
- 20–30% unanswerable cases;
- all 11 target languages;
- LEGAL, MEDICAL and TECHNICAL domains;
- FACTUAL, PARAPHRASE, LEXICAL_EXACT, IDENTIFIER_ONLY,
  IDENTIFIER_SEMANTIC, REFERENCE, NUMERIC, TEMPORAL, MULTI_INTENT,
  COMPARISON, CROSS_LANGUAGE and UNANSWERABLE query classes;
- EASY, MEDIUM and HARD difficulty;
- unique document/query ids;
- document-level and chunk-level truth for every answerable query.

For a curated production corpus, annotations should be human-reviewed and
versioned independently. Do not inflate a curated corpus by duplicating or
mechanically paraphrasing a small number of cases merely to reach the minimum.

## Intended execution

The release runner executes the production path:

`dataset -> KnowledgeIngestionPort -> semantic/hierarchical chunking -> PostgreSQL/pgvector -> RetrievalPlanner -> retrieval lanes -> fusion -> reranker -> context -> RagQuestionService -> citations/grounding -> metrics`

No mocked retriever or relevance-aware scorer is permitted in release qualification.
