# AKMAI Implementation Gap Closure Specification

Status: proposed  
Baseline: main @ bf9f472b672f2abb4b2fbc8813c75127c8cc1563  
Purpose: convert intentional placeholders, partial implementations, hard-coded retrieval behavior and missing production capabilities into explicit implementation work.

## 1. Definition of done

A work package is complete only when:
- production code contains no placeholder/no-op for the capability being closed;
- behavior is configuration-driven where tuning is expected;
- unit tests cover boundaries and failure paths;
- PostgreSQL/Testcontainers gates cover persistence/search semantics where applicable;
- multilingual cases include KK/RU/EN/ZH where the capability is language-sensitive;
- failures are observable rather than silently converted to empty results;
- exact branch HEAD passes spotless and mvn clean verify.

## 2. P0 — Identifier search capability contract

### Current gap
PostgresIdentifierSearchIndex.rebuild() is a no-op and MatchMode other than EXACT throws UnsupportedOperationException. The interface therefore advertises capabilities that the active implementation does not actually provide.

### Required implementation
1. Replace ambiguous rebuild() semantics with an explicit capability model.
2. For the PostgreSQL implementation, either:
   - remove rebuild from the common interface; or
   - return a typed RebuildResult/Capability indicating NOT_REQUIRED.
3. Implement required MatchMode values in PostgreSQL. At minimum EXACT and PREFIX. Add NORMALIZED/FUZZY only if a measured use case exists.
4. Add indexes supporting the selected match modes.
5. Validate query limit and prevent unbounded identifier scans.
6. If Lucene/OpenSearch is later introduced, implement a separate derived-index adapter whose rebuild reads the canonical PostgreSQL table.

### Acceptance
- no silent no-op rebuild contract;
- unsupported match mode is rejected at API/config validation, not discovered deep in retrieval;
- exact and prefix tests for identifier types;
- Testcontainers verifies query plan-compatible indexes and document deletion consistency.

## 3. P0 — Real reranking

### Current gap
Reranker.rerank() returns input unchanged.

### Required implementation
1. Introduce Reranker interface and at least:
   - NoOpReranker, explicitly named and config-selectable;
   - SemanticReranker production implementation.
2. Preserve RRF score/evidence and add rerankScore separately.
3. Bound candidates before reranking.
4. Define timeout and fallback: reranker failure returns RRF ordering and emits an observable failure.
5. Never rerank reference/identifier evidence in a way that destroys exact-match authority without an explicit policy.

### Acceptance
- reranking can change ordering in deterministic tests;
- timeout/failure fallback tested;
- configuration selects implementation;
- benchmark records Recall@K/MRR/nDCG before and after.

## 4. P0 — Embedding profile and re-embedding lifecycle

### Current gap
Generation exists, but persisted state does not define embedding model/profile/version/dimensions. Changing qwen3-embedding model cannot be managed as a controlled migration.

### Required implementation
Persist an EmbeddingProfile containing provider, model, dimensions, profileVersion and normalization/config fingerprint. Associate every vector generation with that profile.

Implement:
- active profile validation at startup;
- detection of documents on an obsolete profile;
- bounded multi-pod re-embedding worker using SKIP LOCKED + lease + fencing;
- generation-safe publication;
- old vector generation deletion only after new generation publication;
- restart/crash recovery;
- progress/status API or operational query.

### Acceptance
- 0.6b -> new profile migration test;
- crash during re-embedding does not remove current searchable generation;
- mixed-profile detection is observable;
- dimension mismatch fails before writes.

## 5. P1 — Retrieval configuration instead of magic numbers

### Current gap
Examples include vector topK=5, similarityThreshold=0.65, lexical limit=10, identifier limit=10, reference limit=20, RRF_K=60, expansion seeds=5/radius=1/max=10 and context budget constants.

### Required implementation
Create validated @ConfigurationProperties for retrieval:
- vector top-k and threshold;
- lexical/identifier/reference limits;
- RRF k;
- expansion seed/radius/max;
- rerank candidate limit;
- context token budget.

Define safe ranges and startup validation. Keep pragmatic defaults.

### Acceptance
- no tuning constants embedded in strategies;
- invalid values fail startup;
- tests demonstrate property overrides.

## 6. P1 — Multilingual lexical retrieval

### Current gap
PostgreSQL FTS uses the 'simple' configuration for all KK/RU/EN/ZH content.

### Required implementation
Design language-aware lexical search:
- store/query language;
- RU/EN language-aware normalization/config where PostgreSQL supports it;
- explicit strategy for Kazakh morphology;
- explicit Chinese tokenization strategy rather than pretending whitespace FTS is equivalent;
- retain safe fallback for unknown language.

Evaluate pg_trgm or another measured fallback where appropriate.

### Acceptance
Create a KK/RU/EN/ZH lexical corpus and expected-query fixture. Record Recall@K. No language may silently use a demonstrably incompatible tokenizer without a documented fallback.

## 7. P1 — Semantic chunking domain depth

### Current gaps
StructuralUnitExtractor recognizes a narrow heading grammar. DomainSemanticClassifier is keyword-based. AtomicUnitProtector only joins adjacent pairs. CrossReferenceExtractor supports a small legal-reference grammar.

### Required implementation
Legal:
LAW -> Part -> Chapter -> Section -> Article -> Paragraph -> Subparagraph hierarchy with stable structured metadata and normalized references.

Medical:
protect atomic Drug + Indication + Population + Dose + Route + Frequency + Duration + Condition facts. Prevent hard split inside dosage/contraindication facts.

Multilingual:
KK/RU/EN/ZH fixtures for headings, sentence boundaries and references.

### Acceptance
Golden fixtures verify hierarchy, references, chunk boundaries and hard-max behavior. Atomic facts survive chunking intact unless hard maximum forces a typed split.

## 8. P1 — Retrieval failure observability

### Current gap
ParallelRetrievalExecutor converts every exceptional future to List.of(), hiding strategy outages from callers and operations.

### Required implementation
1. Preserve partial-result behavior.
2. Record strategy, queryChunkId, exception category, latency and fallback.
3. Add Micrometer counters/timers.
4. Distinguish expected empty result from failed retrieval.
5. Define fail-open/fail-closed policy for identifier/reference/vector/lexical strategies.
6. Surface degraded retrieval in structured diagnostics without leaking internals to end users.

### Acceptance
A failed vector backend plus healthy lexical backend returns lexical results and increments a vector failure metric. A normal zero-hit query does not increment failure metrics.

## 9. P1 — Provenance and citation integrity

### Current gap
ContextAssembler serializes raw metadata and RagQuestionService trusts model-generated [SOURCE n] references. Returned sources are the entire bounded context, not necessarily the sources actually cited by the answer.

### Required implementation
- define stable SourceRef;
- whitelist metadata exposed to the model;
- parse/validate generated source markers;
- reject or repair references outside supplied context;
- return cited sources separately from retrieved context;
- preserve documentId/chunkId/page/section/source URI where available;
- define behavior for an answer with factual text but no valid citation.

### Acceptance
Hallucinated [SOURCE 99] cannot appear as a valid source. Source response maps deterministically to supplied chunks. Sensitive/internal metadata is not injected into prompts.

## 10. P1 — Generation-aware relational projections

### Current gap
Vector state is generation-scoped, while knowledge_search_projection and document_identifier are document/chunk scoped. Correctness currently depends strongly on the document advisory lock and destructive replace ordering.

### Required implementation
Evaluate and implement one explicit model:
A. add generation to projections and identifiers and publish an active generation; or
B. document and enforce the current single-visible-generation transaction/lock model with database constraints.

Preferred for robust recovery: generation-aware relational projections followed by atomic active-generation publication.

### Acceptance
A crash at every persistence boundary is tested. Readers never observe a mixed generation. Recovery can distinguish unpublished generation data from active data.

## 11. P2 — Query decomposition

### Current gap
QueryChunker uses punctuation splitting and duplicates a clause for multiple identifiers. It does not model conjunctions, comparative questions or cross-language compound questions.

### Required implementation
Introduce deterministic decomposition first; optional model-assisted decomposition must be bounded and fallback-safe. Preserve original question and relation between subqueries.

### Acceptance
Fixtures cover multiple identifiers, conjunctions, comparison, legal references and KK/RU/EN/ZH queries without losing semantic relations.

## 12. P2 — Retrieval planning policy

### Current gap
RetrievalPlanner is fixed logic: identifiers gate vector/lexical; reference depends on vector+lexical. No policy configuration or measured strategy selection exists.

### Required implementation
Make plan decisions explicit and testable:
- exact identifier authority;
- semantic fallback;
- lexical fallback;
- reference expansion prerequisites;
- optional domain/language policy.
Do not introduce an LLM planner until deterministic policy has measurable limitations.

## 13. P2 — Production observability

Add metrics and structured logs for:
- ingestion duration/chunks/failures;
- lifecycle states and stale ingestion recovery;
- retention claimed/deleted/failed/stale/lease-lost;
- vector add/delete latency;
- retrieval strategy latency/hits/failures;
- RRF/rerank candidate counts;
- context tokens;
- answer latency and citation validation.

No raw document/question content in metrics labels.

## 14. P2 — Security and tenancy

Before multi-tenant production use:
- introduce tenantId into document ownership and every retrieval/delete predicate;
- tenant-scoped advisory lock key;
- tenant-scoped vector metadata/filter;
- tenant-scoped identifier/projection/lifecycle tables or keys;
- authorization at API boundary;
- tests proving tenant A cannot retrieve/delete/rebuild tenant B data.

## 15. P2 — API and operational hardening

Implement:
- Bean Validation on ingestion/question requests;
- explicit request/body size limits;
- stable error model;
- idempotency semantics for ingestion;
- document lifecycle/status endpoint;
- delete/reingest operational API with authorization;
- health/readiness checks for PostgreSQL/Ollama/vector compatibility;
- graceful overload responses for bounded executors.

## 16. Delivery order

Phase A — close deceptive/partial contracts:
1. IdentifierSearchIndex capability/rebuild semantics.
2. Retrieval properties and validation.
3. Retrieval failure observability.
4. Provenance/citation integrity.

Phase B — retrieval quality:
5. Real reranker.
6. Multilingual lexical retrieval.
7. Semantic chunking depth.
8. Query decomposition/planning.

Phase C — model lifecycle:
9. EmbeddingProfile.
10. Re-embedding worker.
11. Generation-aware relational publication.

Phase D — production:
12. Metrics/structured diagnostics.
13. API hardening.
14. Tenant isolation/security.
15. full Ollama + pgvector + PostgreSQL E2E and multilingual benchmark.

## 17. Release gates

Each phase must finish with:
- spotless:check;
- unit tests;
- PostgreSQL Testcontainers where relevant;
- concurrency/crash tests for state transitions;
- exact-SHA mvn clean verify;
- updated architecture docs;
- no unresolved TODO/FIXME/no-op/UnsupportedOperationException in the completed scope unless explicitly documented as an intentional supported behavior.

Do not claim a phase green from a previous SHA.
