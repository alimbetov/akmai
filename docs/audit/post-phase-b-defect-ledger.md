# Post-Phase-B Defect Remediation Ledger

Branch: `fix/post-phase-b-defect-remediation`

Purpose: accumulate defects found by repeated deep audits and close them with reproducible tests and exact-SHA CI evidence.

## Audit cadence

- Audit 1/10: completed — initial deep audit after Phase B
- Audit 2/10: completed — generation-publication model and citation failure-path audit
- Audit 3/10: completed — retention fencing, context budgeting and reranker isolation audit
- Audit 4/10: completed — process simulation, queue/load envelope and cross-pipeline contract audit
- Audit 5/10: completed — system-analysis and architecture-contract review
- Audits 6/10 .. 10/10: pending
- A defect is never removed from this ledger. It moves through `OPEN -> IMPLEMENTING -> FIXED -> VERIFIED`.
- `VERIFIED` requires an automated regression test and exact-SHA successful CI.

## Severity

- P0: data corruption / security compromise / unrecoverable consistency failure
- P1: major correctness, availability, retrieval-integrity or production blocker
- P2: medium correctness/performance/operability defect
- P3: hardening / maintainability / documentation gap

## Audit 1/10 findings

| ID | Sev | Area | Defect | Required remediation | Verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D01 | P0 | ingestion/vector | Failed ingestion can leave orphan physical vectors when vector add partially succeeds before generation manifest is saved | generation staging + compensation/reconciliation | fault-injection integration test (`4d255816`) | IMPLEMENTING |
| D02 | P0 | retrieval/lifecycle | Retrieval does not enforce READY/current generation visibility | published-generation visibility fence on vector/lexical/identifier reads | INGEST_FAILED generation invisible in all strategies | OPEN |
| D03 | P0 | ingestion | Re-ingestion deletes previous READY generation before replacement is successfully published | non-destructive generation replacement / atomic publication switch | failing replacement contract (`4d255816`) | IMPLEMENTING |
| D04 | P1 | fusion | Identifier snippet can become canonical representative for a chunk and hide full chunk payload/provenance | deterministic representative priority / canonical payload | mixed identifier+semantic query preserves canonical text | OPEN |
| D05 | P1 | retrieval | Identifier-only queries return identifier context but do not resolve canonical chunk/neighbors | canonical resolution after identifier hit | identifier-only acceptance | OPEN |
| D06 | P1 | PostgreSQL FTS | RU/EN queries build russian/english tsvector at query time while indexed generated vector is `simple` | language-specific expression/generated indexes | EXPLAIN/integration index contract | OPEN |
| D07 | P1 | retrieval/runtime | Retrieval plan has no overall/strategy deadlines; blocking dependency can stall request indefinitely | bounded per-strategy/request deadlines + cancellation/fallback | timeout isolation tests | OPEN |
| D08 | P1 | concurrency | Shared bounded executor uses CallerRunsPolicy for latency-sensitive retrieval, allowing work on HTTP thread under saturation | retrieval-specific reject/fallback policy | saturation isolation test | OPEN |
| D09 | P1 | retention | Retention shutdown can stop heartbeat executor before queued workers start; heartbeat scheduling failure bypasses cleanup/finally and leaks reservations | safe shutdown ordering + guarded heartbeat startup | shutdown race test | OPEN |
| D10 | P1 | RAG security | Retrieved document text is inserted into prompt without explicit untrusted-data boundary | prompt-injection resistant system/context framing + test corpus | malicious-context acceptance test | OPEN |
| D11 | P1 | citations | Non-empty generated answers with zero citations are accepted as valid | require citation integrity for grounded answers | uncited answer test | OPEN |
| D12 | P2 | citations | Invalid citation sanitizer recognizes flexible whitespace but removes only exact single-space form | regex-based invalid marker removal | whitespace variant tests | OPEN |
| D13 | P2 | provenance | Page metadata contract is inconsistent: `page`, `pageNumber`, `pageFrom` | typed/canonical provenance metadata | source mapping tests | OPEN |
| D14 | P2 | expansion | Neighbor expansion drops source/page/domain provenance | preserve canonical projection metadata during expansion | neighbor citation test | OPEN |
| D15 | P1 | multilingual | Ingestion accepts arbitrary language spelling/case while retrieval filters canonical lower-case codes | canonicalize/validate KK/RU/EN/ZH at write boundary | RU/russian/ru normalization tests | OPEN |
| D16 | P2 | multilingual | QueryLanguageDetector can classify Kazakh text containing only shared Cyrillic letters as Russian | stronger language routing/fallback | ambiguous KK corpus tests | OPEN |
| D17 | P1 | API/security | No request size/field length boundaries; DB VARCHAR constraints are not mirrored at API boundary | request/body/token/metadata limits and validation | oversized request tests | OPEN |
| D18 | P1 | security | HTTP ingestion and RAG endpoints have no authentication/authorization | explicit local-only profile or Spring Security/API auth | unauthorized request tests | OPEN |
| D19 | P2 | identity | `VectorIdentity.physicalId` and runtime PersistenceCoordinator use incompatible vector ID schemes | one canonical vector identity implementation | identity contract test | OPEN |

## Audit 2/10 findings

| ID | Sev | Area | Defect | Required remediation | Verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D20 | P0 | ingestion/publication | Lifecycle/vector data is generation-scoped, but `knowledge_search_projection` and `document_identifier` are not. New lexical projections and identifiers cannot be staged alongside generation N+1 without deleting or overwriting generation N before publication, so a true atomic multi-modality publication switch is impossible with the current schema | add generation identity to canonical projections and identifiers; stage rows per generation; make vector/lexical/identifier reads resolve only the published generation; retire old generation only after successful publication; compensate failed staged generation | integration contract: failed N+1 leaves N vector + lexical + identifier results unchanged and N+1 invisible; successful publish switches all retrieval modalities to N+1 together | OPEN |
| D21 | P2 | citations/availability | `CitationValidator` parses the numeric body of every `[SOURCE n]` marker with `Integer.parseInt`; a model response containing an out-of-range integer marker throws `NumberFormatException` and fails the whole RAG request instead of treating the citation as invalid | parse citation numbers without overflow (bounded parse or guarded exception) and sanitize/record oversized markers as invalid | huge citation marker such as `[SOURCE 999999999999999999999]` does not throw and is removed/reported invalid | OPEN |


## Audit 3/10 findings

| ID | Sev | Area | Defect | Required remediation | Verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D22 | P1 | retention/concurrency | `ChunkRetentionService` checks `isCurrentClaim` before vector deletion and again afterwards, but then deletes identifiers, projections and generation metadata without another fencing check. If the lease expires or the claim is replaced after the second check, a stale worker can continue destructive cleanup and only discover loss of ownership when `markDeleted` fails | make destructive cleanup lease-fenced through the entire critical section; re-check/atomically fence immediately before destructive tail, and ensure stale workers cannot mutate SQL/vector state after claim loss | race test that forces claim loss after vector phase but before SQL cleanup and proves stale worker performs no further destructive mutations | OPEN |
| D23 | P2 | RAG/context budget | `ContextBudget` counts only `hit.text()`, while `ContextAssembler` adds source labels and metadata fields (`documentId`, `chunkId`, `source`, `language`, `sectionPath`, `page`) for every chunk. The assembled context can therefore exceed `contextMaxTokens` even when the budget reports it within limit | budget the exact serialized context envelope, or reserve deterministic overhead per source plus prompt framing | test with long metadata / many chunks proving assembled context stays within configured token budget | OPEN |
| D24 | P2 | reranker/runtime | Reranker timeout uses `Future.get(timeout)` + `cancel(true)`, but the single reranker worker can remain blocked inside `EmbeddingModel.embed()` if the client ignores interruption. One hung embed can monopolize the only worker and force later reranks into repeated timeout/rejection fallback until the underlying call returns | enforce transport-level embedding timeout/cancellation, isolate or rotate poisoned workers, and bound queued rerank work independently of candidate count | blocking scorer test proving one timed-out request cannot prevent a later rerank from executing successfully | OPEN |


## Audit 4/10 findings

| ID | Sev | Area | Defect | Required remediation | Verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D25 | P1 | retrieval/identifier scoping | Identifier-dependent vector/lexical steps receive an empty `RetrievalContext` when exact identifier lookup misses. Both strategies interpret an empty document set as “unscoped global search”, so a query for an unknown contract/order/etc. can silently broaden from identifier-scoped retrieval to the entire corpus | distinguish “no scope requested” from “required scope resolved to zero documents”; fail closed or use an explicit configured fallback policy for identifier-bearing queries | unknown identifier + semantic text must not execute unrestricted vector/lexical retrieval; multi-identifier tests cover partial misses | OPEN |
| D26 | P2 | retrieval/reference amplification | `ReferenceRetrievalStrategy` executes one identifier-index query per distinct textual reference, up to `referenceLimit` per reference step, and each lookup can return up to `identifierLimit` candidates. With 8 query units and defaults 20×10 this permits up to 160 identifier SQL lookups and 1,600 pre-fusion identifier candidates, plus projection lookups, from one user question | batch reference resolution, cap total resolved targets per step/request, deduplicate before database round-trips, and expose amplification metrics | query-count integration test and bounded-cardinality test for max-segment/max-reference input | OPEN |
| D27 | P1 | retrieval/reference correctness | The production reference pipeline has no real producer for the keys consumed by `ReferenceRetrievalStrategy`: `CrossReferenceExtractor` emits forms such as `статья/article/section/clause 48`, while the ingestion `IdentifierExtractor` only indexes business/document identifiers captured as their value token. Existing reference tests manually synthesize `references` and `DocumentIdentifier` rows that normal ingestion cannot create | define a typed canonical cross-reference identity and index it during ingestion; make extractor, normalizer, identifier storage and reference resolver share that contract | end-to-end Testcontainers test that ingests a source containing a real cross-reference and a target containing the referenced article/section, then resolves it without manually inserted identifier rows | OPEN |
| D28 | P1 | JDBC/concurrency | `DocumentOperationLock` holds a DataSource connection for the full ingestion/retention critical section, while code inside that section uses `JdbcTemplate`/pgvector and therefore needs additional connections from the same pool. If concurrent document locks consume the pool, every lock holder can block waiting for a second connection and no operation can progress to release its lock | avoid holding an application-pool connection solely for session advisory locking, use a dedicated lock datasource/connection budget or a single-connection transactional/fencing design, and enforce a concurrency invariant against pool capacity | Hikari integration test with a deliberately small pool and concurrent different-document operations proves no connection-starvation cycle | OPEN |
| D29 | P1 | RAG/runtime | Retrieval and reranking can be given deadlines, but the final synchronous `chatClient.prompt().call().content()` stage has no application-level request deadline/cancellation/fallback contract in this service. A slow or wedged generation call can therefore dominate end-to-end request latency after retrieval has completed | add an explicit answer-generation deadline and transport timeout, cancellation/isolation, and a deterministic fallback/error contract | blocking/fault-injected chat model test proves `ask()` returns or fails within the configured end-to-end deadline | OPEN |
| D30 | P2 | metadata/retrieval | Request metadata permits null values; those values are persisted in `metadata_json`, while `RetrievalHit` canonicalizes metadata with `Map.copyOf`, which rejects null keys/values. A document containing a null metadata value can therefore make a retrieval branch fail when its projection metadata is converted to a hit | validate/canonicalize metadata at ingestion and/or sanitize nulls before constructing retrieval hits; define supported metadata value types | ingest metadata containing a null value and prove vector/lexical retrieval remains deterministic rather than throwing/dropping the branch | OPEN |

### Audit 4 simulation / capacity evidence

These are stress-model results, not production telemetry.

- Query decomposition permits up to 8 units. An identifier-bearing unit can create 4 retrieval steps, giving a structural maximum of 32 retrieval tasks per request. With 8 workers, a simplified service-demand model yields an executor-only saturation envelope of about 5 max-fanout requests/s at 50 ms average task time, 2.5 requests/s at 100 ms, and 1 request/s at 250 ms, before database/model overhead.
- For identifier-scoped queries, if the independent per-identifier resolution miss rate were 10%, the probability that at least one of 8 identifier units enters the current unintended global fallback path is `1 - 0.9^8 ≈ 56.95%`. At a 5% miss rate it is about 33.66%. This quantifies D25 sensitivity; the miss rates themselves are illustrative.
- The reference-stage upper bound from the current defaults is deterministic: up to 8 reference steps × 20 identifier lookups = 160 identifier SQL lookups, and up to 8 × 20 × 10 = 1,600 identifier candidates before fusion. Projection reads add further round-trips.
- The application does not set a Hikari maximum pool size. With Hikari's default maximum of 10 connections, 10 concurrent document operations holding session advisory-lock connections leave no free connection for nested JDBC work. A Poisson/lognormal stress model with 1 s mean lock hold is highly sensitive once offered concurrency approaches that boundary; this is used only to prioritize D28, while the defect itself follows from the connection-allocation invariant.
- The reranker model from Audit 3 is also load-sensitive: with one worker and a 20-entry queue, cancellation of queued `FutureTask` instances does not create a transport-level guarantee that the underlying embed call stopped; D24 remains a separate poisoned-worker concern.



## Audit 5/10 findings

| ID | Sev | Area | Defect | Required remediation | Verification | Status |
| --- | --- | --- | --- | --- | --- | --- |
| D31 | P0 | lifecycle/publication architecture | The lifecycle model has only one `generation` plus one `lifecycle_status`. `beginIngestion(N+1)` immediately increments `generation` and changes the document to `INGESTING`, so the system no longer has an authoritative representation that generation N is still the published READY generation while N+1 is staging. This makes the D02/D03/D20 target semantics impossible to express cleanly with the current state model | separate published/active generation from working/staging generation, or model generations as separate rows with an atomic published pointer; retrieval and retention must consume the published pointer while ingestion mutates only staging state | state-machine integration test: N remains explicitly published/searchable while N+1 is INGESTING/FAILED, then one atomic publication switch makes N+1 active | OPEN |
| D32 | P1 | embedding lifecycle | Persisted vector state has no embedding provider/model/version/dimension/config fingerprint. Startup validation only checks configured pgvector dimensions/HNSW limits and does not prove the active embedding model matches persisted vectors. Changing the embedding model can silently query old vectors with a different embedding space (or fail later on dimension mismatch) with no mixed-profile detection or controlled re-embedding path | introduce a persisted EmbeddingProfile and associate every vector generation with it; validate active model/profile before serving, detect obsolete/mixed profiles, and implement generation-safe bounded re-embedding | same-dimension model/profile change is detected before mixed-space retrieval; dimension mismatch fails before writes; re-embedding crash preserves the prior published generation | OPEN |
| D33 | P0 | database migration safety | Liquibase changeset `001-canonical-retrieval-schema.sql` executes `DROP TABLE IF EXISTS document_identifier CASCADE` before recreating the identifier table. Any upgrade from a deployment where `document_identifier` already contains data can destroy identifier state. The repository also contains a conflicting standalone `identifier-schema.sql`, so upgrade behavior depends on which historical schema source was used | replace destructive bootstrap logic with an additive/data-preserving migration path; establish Liquibase as the single schema source of truth; add explicit legacy-to-current migration if historical installations are supported | Testcontainers upgrade test seeds the legacy identifier schema/data, runs Liquibase, and proves all rows/constraints required by the repository survive | OPEN |
| D34 | P2 | identifier capability contract | `IdentifierType` and README advertise CLAIM_NUMBER, PAYMENT_NUMBER, PROTOCOL_NUMBER, LETTER_NUMBER and DOCUMENT_ID, but production parser beans only exist for CONTRACT_NUMBER, ORDER_NUMBER, INVOICE_NUMBER, APPLICATION_NUMBER, CASE_NUMBER and DOCUMENT_NUMBER. The shared WRITE/READ extractor therefore cannot actually ingest or recognize several advertised identifier types | either implement parsers and normalization contracts for every advertised type or remove unsupported types from the capability contract/docs/API until implemented | parameterized ingestion/query tests for every supported IdentifierType, with startup/capability test proving no advertised type lacks a parser | OPEN |
| D35 | P1 | identifier identity | `IdentifierNormalizer` removes all punctuation, so distinct identifiers such as `AB-12` and `AB/12` collapse to the same normalized key `AB12`. Exact lookup can therefore return the wrong document, and within one document/chunk the unique constraint can overwrite one occurrence with the other | define type-aware canonicalization that preserves semantically significant separators or adds an unambiguous canonical encoding; do not use one lossy normalization rule for all identifier types | collision corpus proves semantically distinct raw identifiers never share an exact-match identity unless the type contract explicitly declares them equivalent | OPEN |
| D36 | P2 | configuration/chunking | `ChunkingProperties` has no validation. Invalid values and invalid ordering (`min > target`, `target > soft`, `soft > hard`, non-positive limits) are accepted at startup and can silently degrade chunking into one-character/one-unit fragmentation or violate configured hard/soft semantics | add validated configuration invariants for positive values and `min <= target <= soft <= hard`, with explicit safe upper bounds | application-context/property-binding tests reject invalid combinations at startup and accept documented defaults/overrides | OPEN |
| D37 | P2 | API/ingestion idempotency | `POST /api/knowledge/text` has no idempotency/version precondition semantics. If a client loses a successful response and retries the same request, the retry is treated as a new ingestion generation, redoes embeddings/index writes and refreshes lifecycle/TTL state even though the logical command is identical | define idempotency semantics (idempotency key and/or content/version fingerprint with conditional generation advance), persist request identity/result, and make retries return the original outcome without republishing equivalent state | lost-response retry test proves the same logical request does not create a new generation or refresh TTL; changed content/version still creates a new generation | OPEN |
| D38 | P2 | retention observability | The lifecycle specification requires structured retention logs and metrics for claimed/deleted/failed/stale/lease-lost work, backlog and run duration, but `RetentionScheduler`, `RetentionWorkerPool` and `ChunkRetentionService` emit none. Failures can persist only as row state without the operational signals required to detect a stuck backlog or repeated lease loss | add bounded-cardinality Micrometer metrics and structured lifecycle events for run, claim, delete, failure, stale claim, lease loss, recovery and backlog; never label with document text | meter/log tests distinguish successful deletion, retryable failure, stale claim and lease loss; backlog gauge changes with seeded lifecycle rows | OPEN |


## Remediation order

### Wave 1 — consistency and visibility

```text
D33 migration safety / schema source of truth
D31 active-vs-staging generation state model
D20 generation-scoped lexical/identifier publication
D03 non-destructive replacement
D01 orphan vectors
D02 published-generation visibility
D19 vector identity canonicalization
D32 embedding profile / re-embedding lifecycle
```

### Wave 2 — retrieval correctness

```text
D04 canonical representative
D05 identifier-only canonical resolution
D06 RU/EN FTS indexes
D07 retrieval deadlines
D08 saturation isolation
D25 identifier-scope fail-closed semantics
D35 collision-safe identifier identity
D34 complete identifier capability contract
D27 real cross-reference indexing contract
D26 reference fan-out/query amplification
D24 reranker poisoned-worker isolation
D29 answer-generation deadline
D23 exact assembled-context budget
```

### Wave 3 — lifecycle/concurrency

```text
D09 retention shutdown race
D22 retention lease fencing
D28 JDBC/advisory-lock pool starvation
D38 retention observability
```

### Wave 4 — trust, provenance and API boundaries

```text
D10 prompt injection boundary
D11 citation requirement
D12 citation sanitization
D21 citation numeric overflow
D13 provenance contract
D14 expansion provenance
D15 language canonicalization
D16 ambiguous language routing
D17 request limits
D36 chunking configuration invariants
D37 ingestion idempotency semantics
D30 metadata canonicalization
D18 authentication/authorization
```

## Required final gate

```text
all P0/P1 = VERIFIED
all accepted P2 have explicit disposition
full Testcontainers suite
spotless
mvn clean verify
exact-SHA GitHub Actions success
Audit 10/10 completed
```
