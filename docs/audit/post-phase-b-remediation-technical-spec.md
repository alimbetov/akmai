# AKMAI — Post-Phase-B Defect Remediation Technical Specification

Branch: fix/post-phase-b-defect-remediation  
Source ledger: docs/audit/post-phase-b-defect-ledger.md  
Scope: D01–D72  
Status: implementation contract  
Rule: this document is one implementation scope. Section numbering is navigation only; it does not define separate delivery phases or independently releasable parts.

## 1. Purpose

Implement and verify every defect D01–D72 from the post-Phase-B ledger without leaving architecture decisions implicit.

The implementation MUST produce one coherent runtime model for:

- document publication and generation ownership;
- generation-scoped relational and vector retrieval state;
- physical vector identity and reconciliation;
- bounded ingestion, retrieval, reranking, answer generation and retention;
- typed retrieval failure semantics;
- multilingual chunking, identifiers and cross-references;
- prompt/citation/provenance trust boundaries;
- migration safety, API validation/authentication/idempotency;
- operational readiness, metrics and exact-SHA verification.

No defect may be marked VERIFIED from a mock-only test when the defect concerns a production adapter, database transaction, distributed lease or physical vector state.

## 2. Non-negotiable system invariants

The final code MUST satisfy all of the following at all times.

1. Exactly one published generation may be visible for a document.
2. Starting generation N+1 MUST NOT make published generation N non-retrievable.
3. A failed or stale staging generation MUST NOT alter the published pointer.
4. Vector, lexical, identifier and reference retrieval MUST resolve the same published generation.
5. Physical vectors MUST have one canonical identity implementation and one declared identity version.
6. A missing vector manifest MUST be treated as reconciliation-required, never as proof that chunk IDs are physical vector IDs.
7. All relational writes for one staged generation MUST be atomic or fully reconcilable.
8. A retention worker may mutate state only while its generation/token/lease fence is valid.
9. Distributed lease time MUST be decided by PostgreSQL time, not pod-local clocks.
10. Retrieval infrastructure failure MUST NOT be represented as a normal zero-hit result.
11. Exact identifier/reference authority MUST survive fusion and semantic reranking according to an explicit policy.
12. Hard limits MUST be hard limits: no final chunk, embedding payload, request, context or deadline may exceed the configured contract.
13. User-controlled document content and provenance metadata MUST always remain untrusted data in prompts.
14. Quality gates MUST execute production retrieval code; hard-coded ranked lists may test metric arithmetic only.
15. Exact-SHA CI is the only acceptable final verification evidence.

## 3. Required target data model

### 3.1 Document lifecycle row

Refactor knowledge_document_lifecycle so it represents document-level publication and retention, not the transient ingestion attempt.

Required logical columns:

- document_id VARCHAR(100) PRIMARY KEY
- lifecycle_policy PERMANENT|TTL
- retention_status ACTIVE|DELETE_PENDING|DELETING|DELETE_FAILED|DELETED
- published_generation BIGINT NULL
- next_generation BIGINT NOT NULL
- claim_generation BIGINT NULL
- claim_id UUID NULL
- claimed_by VARCHAR(200) NULL
- claimed_at TIMESTAMPTZ NULL
- lease_until TIMESTAMPTZ NULL
- expires_at TIMESTAMPTZ NULL
- delete_started_at TIMESTAMPTZ NULL
- deleted_at TIMESTAMPTZ NULL
- attempt_count INTEGER NOT NULL
- last_error VARCHAR(1000) NULL
- row_version BIGINT NOT NULL
- created_at / updated_at TIMESTAMPTZ

Do not overload one status field with both ingestion and retention state.

### 3.2 Generation journal

Add knowledge_document_generation:

- document_id VARCHAR(100)
- generation BIGINT
- generation_status STAGING|PUBLISHED|FAILED|RETIRED
- embedding_profile_id VARCHAR(128)
- content_fingerprint VARCHAR(64)
- physical_id_version SMALLINT
- started_at TIMESTAMPTZ
- published_at TIMESTAMPTZ NULL
- failed_at TIMESTAMPTZ NULL
- retired_at TIMESTAMPTZ NULL
- last_error VARCHAR(1000) NULL
- cleanup_required BOOLEAN NOT NULL DEFAULT false
- PRIMARY KEY(document_id, generation)

Required constraints/indexes:

- generation > 0;
- one PUBLISHED generation per document at most;
- lookup by generation_status/started_at for stale recovery;
- FK from generation-scoped relational state where practical.

### 3.3 Generation-scoped projections

Refactor knowledge_search_projection:

- add generation BIGINT NOT NULL;
- primary ownership key MUST include document_id + generation + chunk_id;
- unique document order MUST be document_id + generation + chunk_index;
- chunk_id alone MUST NOT be a global ownership key;
- published retrieval MUST join lifecycle.published_generation;
- old/failed generation rows may exist physically but MUST be invisible.

Repository methods that are used by retrieval MUST be published-only by construction. Prefer names that expose the invariant:

- findPublishedByChunkIds(...)
- findPublishedAdjacent(...)
- searchPublishedLexical(...)
- findPublishedChunkIds(documentId, generation)

Staging/cleanup methods MUST require explicit documentId + generation.

### 3.4 Generation-scoped identifiers

Refactor document_identifier:

- add generation BIGINT NOT NULL;
- unique occurrence includes document_id + generation + chunk_id + identifier_type + canonical_value;
- retrieval joins lifecycle.published_generation;
- cleanup always requires documentId + generation;
- exact/prefix/partial indexes include type and canonical form.

DocumentIdentifier record MUST carry generation.

### 3.5 Typed cross-reference graph

Stop using free-form strings as the authority for legal cross-reference resolution.

Add:

CrossReferenceType:
- ARTICLE
- SECTION
- CLAUSE
- PARAGRAPH
- SUBPARAGRAPH

CrossReference:
- type
- canonicalValue
- rawValue
- language

StructuralAnchor:
- type
- canonicalValue
- rawValue

Persist generation-scoped targets and edges, either as dedicated tables or equivalent normalized relational structures:

- knowledge_reference_target(document_id, generation, chunk_id, type, canonical_value, ...)
- knowledge_reference_edge(document_id, generation, source_chunk_id, type, canonical_value, raw_value, ...)

Reference retrieval MUST resolve published edges to published targets. Heading declarations MUST create targets, not outgoing self-reference edges.

### 3.6 Embedding profile

Add knowledge_embedding_profile:

- profile_id VARCHAR(128) PRIMARY KEY
- provider
- model
- dimensions
- distance_type
- tokenizer/profile version
- canonical config fingerprint
- created_at

Every generation MUST reference one profile.

Startup readiness MUST compare the configured active profile against persisted published generations. Same-dimension but different model/profile is incompatible unless the explicit re-embedding flow is used.

### 3.7 Idempotency journal

Add knowledge_ingestion_request:

- idempotency_key VARCHAR(200) PRIMARY KEY
- document_id VARCHAR(100)
- request_fingerprint VARCHAR(64)
- generation BIGINT NULL
- request_status IN_PROGRESS|SUCCEEDED|FAILED
- response_json JSONB NULL
- created_at / updated_at

The same idempotency key + same fingerprint returns the original completed result. Same key + different fingerprint returns 409.

## 4. Migration contract

Liquibase is the only runtime schema source of truth.

Do not silently edit historical applied changesets 001–006 in a way that breaks checksums on existing installations.

Add a preservation changeset before destructive legacy migration logic. It MUST detect whether akmai:001-canonical-retrieval-schema was already recorded in DATABASECHANGELOG. If not recorded and a legacy document_identifier table exists, preserve/rename it before 001 can drop it. A later additive migration copies validated legacy rows into the current generation-aware schema.

Required migration files after reconciliation should cover:

- legacy identifier preservation;
- publication/generation journal;
- generation-scoped projections/identifiers/vector manifest extensions;
- embedding profile;
- typed reference graph;
- idempotency journal and constraints;
- RU/EN FTS expression/generated indexes and KK/ZH trigram indexes;
- any compatibility backfill needed for current installations.

Remove src/main/resources/db/identifier-schema.sql from runtime authority. If historical documentation is required, move it under docs/legacy and clearly mark it non-executable.

Every schema migration MUST have an upgrade Testcontainers fixture. A clean-database test is not sufficient for D33.

## 5. Core Java model changes

Introduce or refactor the following types.

- DocumentLifecycle: publishedGeneration, nextGeneration, retentionStatus and claim fields.
- DocumentGeneration: immutable generation journal record.
- GenerationStatus.
- RetentionStatus; replace the current mixed LifecycleStatus semantics.
- KnowledgeLanguage enum or equivalent canonical value object for kk/ru/en/zh.
- Provenance record for source, pageFrom, pageTo, language, domain, sectionPath and safe metadata.
- StructuralRole enum separate from SemanticFactType.
- SemanticUnit MUST carry structure and semantic classification separately.
- CanonicalIdentifier with IdentifierType, canonicalValue, rawValue, normalizationVersion.
- CrossReference / StructuralAnchor as typed values.
- RetrievalOutcome and RetrievalExecutionResult with explicit statuses.
- RetrievalFailureCategory: FAILED, TIMED_OUT, REJECTED, BACKEND_UNAVAILABLE, INVALID_RESPONSE.
- EmbeddingProfile.
- VectorPhysicalIdentityVersion.

Collections/maps crossing service boundaries MUST be defensively copied and validated for nulls.

## 6. Publication and persistence algorithm

PersistenceCoordinator MUST be redesigned around staging, not destructive replacement.

Required sequence for one document:

1. Acquire the document-operation lock.
2. Begin generation N+1 in PostgreSQL:
   - atomically increment next_generation;
   - insert knowledge_document_generation as STAGING;
   - leave lifecycle.published_generation unchanged.
3. Build all SearchProjection, DocumentIdentifier, reference target/edge and vector manifest entries in memory.
4. In one PostgreSQL transaction stage:
   - generation-scoped projections;
   - generation-scoped identifiers;
   - reference targets/edges;
   - complete vector manifest for every intended physical vector ID.
5. Call vectorStore.add for generation N+1 using the exact IDs already in the manifest.
6. If vector add throws:
   - delete ALL attempted vector IDs from the manifest, not only confirmed IDs;
   - if compensation itself fails, set cleanup_required=true;
   - mark generation FAILED;
   - published_generation remains N;
   - rethrow a bounded domain exception.
7. Publish in one PostgreSQL transaction:
   - verify N+1 is STAGING;
   - retire prior PUBLISHED generation N if present;
   - mark N+1 PUBLISHED;
   - set lifecycle.published_generation=N+1;
   - set retention_status=ACTIVE;
   - refresh retention policy/expiry according to the accepted request.
8. Only after publication, clean physical/relational state belonging to retired N. Failure to retire old physical state is an operability/reconciliation issue, not a reason to roll back the new published pointer.
9. Reconciliation MUST be able to clean stale STAGING/FAILED/RETIRED generations from the generation journal + manifests.

No method may delete the old published generation before the new generation is published.

## 7. Canonical identity

### 7.1 ChunkIdentity

Replace newline-delimited concatenation with an unambiguous binary/length-prefixed encoding over:

- NFC-normalized documentId;
- chunkIndex as fixed-width integer;
- NFC-normalized sectionPath;
- NFC-normalized normalizedText.

Hash with SHA-256 and encode deterministic lowercase hex/base64url. Prefix with an identity version if useful.

Different tuples MUST never produce identical pre-hash bytes.

### 7.2 VectorIdentity

Use exactly one implementation everywhere.

Recommended physical form:

v2:<sha256(length-prefixed(documentId), generation, chunkId)>

The exact encoding MUST be deterministic, versioned and tested. PersistenceCoordinator MUST NOT implement its own vector ID function.

knowledge_document_vector_generation MUST store physical_id_version and embedding_profile_id.

### 7.3 Missing manifest reconciliation

Never infer current physical IDs from chunk_id.

Add VectorReconciliationService / PublishedVectorAdminRepository able to enumerate vectors by trusted metadata documentId + generation through the production PgVector table/adapter, backfill a manifest and only then delete.

If enumeration cannot prove absence, retention MUST fail/retry and MUST NOT mark DELETED.

## 8. Published vector retrieval

The generic VectorStore similaritySearch path cannot by itself express “metadata generation equals the per-document published_generation” for an unscoped multi-document search.

Introduce PublishedVectorSearchRepository implemented for the production PostgreSQL/pgvector storage.

It MUST:

- obtain the query embedding through the configured EmbeddingModel;
- query the actual Spring AI pgvector table;
- join/filter vector metadata documentId + generation against knowledge_document_lifecycle.published_generation;
- exclude non-ACTIVE/DELETED documents;
- apply optional explicit document scope;
- apply configured distance/similarity threshold and topK;
- return canonical RetrievalHit provenance;
- reject non-finite query/vector results.

Do not solve D02 by post-filtering a too-small topK after similaritySearch; that can underfill with unpublished candidates.

D58 requires production-adapter E2E coverage of this repository and real PgVectorStore add/delete behavior.

## 9. Transaction boundaries

Add an explicit GenerationStagingRepository or transaction service using TransactionTemplate / @Transactional for all relational staging writes.

One staging transaction MUST include projections, identifiers, reference graph and vector manifests. batchUpdate chunking at 100 rows is allowed only inside that transaction.

Fault in batch 2 MUST roll back batch 1.

External/model/vector calls MUST NOT be presented as one ACID transaction with PostgreSQL. Their recovery journal is knowledge_document_generation + vector manifest.

## 10. Retrieval execution contract

ParallelRetrievalExecutor MUST stop returning only List<RetrievalHit>.

Define a typed result per step:

- SUCCESS with non-empty hits;
- EMPTY;
- FAILED;
- TIMED_OUT;
- REJECTED;
- SKIPPED_DEPENDENCY.

RetrievalExecutionResult MUST contain:

- merged hits;
- immutable per-step outcomes;
- degraded boolean;
- criticalFailure boolean.

Identifier dependency rules:

- no identifier requested -> global semantic retrieval may run;
- identifier requested and exact lookup SUCCESS -> semantic retrieval uses only resolved document scope;
- identifier requested and exact lookup EMPTY -> dependent semantic retrieval is fail-closed/explicit fallback, never silently global;
- identifier lookup FAILED/TIMED_OUT/REJECTED -> dependent steps do not reinterpret that state as EMPTY.

RagQuestionService MUST distinguish:
- healthy zero evidence -> “insufficient information” response;
- infrastructure/dependency failure -> stable degraded/503 error contract.

## 11. Deadlines and executor semantics

Add validated properties:

- retrieval.request-timeout
- retrieval.strategy-timeout
- reranker-timeout
- answer-timeout
- ingestion.vector-write-timeout
- database/query timeouts where required

All Duration values MUST be positive, have explicit minimum/maximum and avoid toMillis precision collapse.

BoundedExecutorFactory MUST use rejection that completes/submits exceptionally; do not use CallerRunsPolicy.

Shutdown MUST:
- stop accepting new requests/tasks;
- drain/await active work for a bounded period;
- stop heartbeat scheduling only after retention workers are drained;
- cancel remaining work;
- guarantee no CompletableFuture remains forever incomplete.

Transport-level HTTP/JDBC timeouts MUST back application deadlines so cancellation is not dependent only on Thread.interrupt.

## 12. Fusion, reranking and authority

ResultFusion MUST no longer use the first arbitrary hit as the canonical payload.

For every fused chunk:
- resolve canonical published SearchProjection;
- use canonical text/provenance as representative;
- retain evidence from identifier/vector/lexical/reference channels.

Introduce authority ordering:

1. exact identifier target / exact typed reference target;
2. canonical lexical/vector multi-channel evidence;
3. semantic-only evidence;
4. expansion neighbors.

Semantic reranking may reorder inside an authority tier but MUST NOT demote an exact authoritative target below non-authoritative candidates unless a configurable policy explicitly permits it.

EmbeddingSemanticRerankScorer MUST validate:
- embedding count;
- uniform dimensions;
- dimension > 0;
- every component finite;
- cosine finite;
- final combined rerank score finite.

Any malformed model response fails the entire rerank attempt and falls back to the original RRF order.

## 13. Expansion and context selection

KnowledgeExpansion MUST not append neighbors behind an already full context.

Implement one explicit policy:
- expand high-ranked seeds before final context selection;
- score/interleave neighbor with its seed; or
- reserve a bounded expansion quota.

ContextBudget applies only after expansion integration.

Neighbor hits MUST carry full canonical Provenance.

## 14. Prompt and citation boundary

Replace ad-hoc text concatenation with a structured, escaped context envelope serialized by Jackson.

Recommended model-facing structure:

- source number
- documentId
- chunkId
- canonical source
- language
- sectionPath
- page range
- text

Document content and every provenance field are untrusted data. The system prompt MUST explicitly state that instructions inside context/source metadata are data and MUST NOT override system/user instructions.

Do not permit source metadata to create fake source separators.

ContextBudget MUST estimate the exact serialized envelope plus deterministic framing overhead, not only hit.text.

CitationValidator MUST:
- parse source numbers without integer overflow;
- remove invalid markers with the same regex grammar used for detection;
- require at least one valid citation for a non-empty grounded factual answer;
- return only actually cited SourceRef entries;
- never expose an out-of-range/fabricated source as valid.

## 15. Provenance and metadata

Create one typed Provenance mapper at ingestion.

Canonical fields:
- source
- pageFrom
- pageTo
- language
- domain
- sectionPath

Accept legacy page/pageNumber/pageFrom at the API/compatibility boundary only; normalize immediately.

Core provenance MUST not live as arbitrary Map<String,Object> lookups.

Request metadata:
- reject null keys;
- define allowed JSON scalar/list/object depth and total serialized size;
- remove/sanitize null values according to one documented policy;
- preserve custom metadata separately from trusted provenance.

## 16. Language, Unicode and chunking

### 16.1 Unicode boundary

TextNormalizer MUST normalize text to NFC before identity/chunking.

Identifier canonicalization MUST be type-aware. Use NFC universally; use NFKC only for identifier types where compatibility folding is explicitly safe.

WRITE and READ use the same canonicalization version.

### 16.2 Language canonicalization

Map accepted aliases to KnowledgeLanguage KK/RU/EN/ZH at request ingestion. Persist only canonical codes.

QueryLanguageDetector MUST represent ambiguity. Shared Cyrillic without Kazakh-specific evidence MUST NOT automatically be treated as confidently Russian. Use UNKNOWN/ambiguous candidates and a documented lexical fallback.

### 16.3 Structural and semantic typing

SemanticUnit MUST separate StructuralRole from SemanticFactType.

Generic numbered formatting may create LIST_ITEM/HEADING structure but MUST NOT prevent MEDICAL semantic classification.

Legal classifier:
- boundary-aware token/phrase matching;
- negative phrases such as must not/shall not/may not evaluated before positive must/shall/may;
- no substring false positives.

Kazakh heading mapping MUST use exact canonical tokens so ТАРМАҚША does not match ТАРМАҚ.

### 16.4 Sentence segmentation

Create a language/script-aware SentenceSegmenter.

Requirements:
- Chinese 。！？ split without whitespace;
- RU/EN legal/technical abbreviations such as ст. 25, п. 3, Art. 25, No. 42 stay attached;
- genuine sentences still split;
- query and document segmentation share tested boundary rules where applicable.

### 16.5 Hard token limits

OversizedUnitSplitter MUST split on valid Unicode code-point/grapheme boundaries.

SemanticChunker MUST enforce final chunk hardMaxTokens unconditionally; min/target/soft are preferences only.

The limit for embedding MUST be checked against the exact EmbeddingTextBuilder payload, including title/domain/language/sectionPath.

Introduce a tokenizer/profile-aware estimator when supported by the configured embedding model; otherwise use a conservative profile-specific estimator with explicit safety margin.

## 17. Identifier subsystem

IdentifierNormalizer becomes IdentifierCanonicalizer with per-IdentifierType rules.

Distinct values such as AB-12 and AB/12 MUST remain distinct unless that type’s documented grammar declares them equivalent.

Business parsers MUST require an unambiguous identifier signal:
- explicit №/No/number marker; or
- an independently validated identifier shape containing the required digit/separator structure.

Ordinary phrases such as contract termination, order status and case management MUST produce no identifier.

Enforce raw/canonical length <= 500 before persistence.

Implement or remove from advertised capability every IdentifierType. If CLAIM_NUMBER, PAYMENT_NUMBER, PROTOCOL_NUMBER, LETTER_NUMBER and DOCUMENT_ID remain supported enum/API values, each MUST have a production parser and tests.

Prefix/partial LIKE search MUST escape wildcard semantics where literal behavior is intended.

## 18. Cross-reference subsystem

Replace free-form regex-only references with language-specific parsers that emit typed CrossReference.

Support at least:
- RU: статья 25, ст. 25, пункт 3, п. 3;
- EN: article 25, Art. 25, section 3, clause 3;
- KK: 25-бап, 1-тармақ and supported word-before-number variants;
- ZH: 第25条, 第二十五条 and supported paragraph forms.

Structural declarations create StructuralAnchor targets. Mentions create CrossReference edges.

Batch resolution MUST:
- deduplicate references before SQL;
- resolve in one/bounded batch rather than one query per reference;
- enforce a request-level maximum target count;
- expose query-count/candidate metrics.

## 19. Lexical SQL

RU/EN:
- create indexes using the same tsvector configuration used by the query;
- RU uses russian configuration;
- EN uses english configuration;
- query and index expressions MUST be identical.

KK/ZH trigram/substring fallback:
- escape %, _ and escape character;
- use explicit ESCAPE clause;
- define a minimum/selectivity policy for very short queries;
- keep literal query semantics.

Integration tests MUST inspect representative EXPLAIN plans on a sufficiently large corpus or with controlled planner settings.

## 20. Retention concurrency model

Retention claims the currently published generation only.

Repository lease methods MUST use PostgreSQL clock_timestamp()/now() for:
- claim time;
- lease_until;
- current-claim checks;
- renewal;
- expiry/reclaim;
- failure/deleted transitions.

Remove correctness dependence on pod-local Clock from production repository APIs.

markDeleting, markDeleted, markFailed and releaseClaim MUST all require:
- document_id;
- generation;
- claim_id;
- expected state;
- unexpired lease according to DB time.

SQL deletion of projections/identifiers/reference rows MUST be fence-aware in the same SQL statement or transaction.

For vector deletion:
- verify current claim immediately before external delete;
- delete only generation-scoped physical IDs from a verified manifest;
- a stale worker can at worst delete its old claimed generation, never the newly published generation.

## 21. Retention scheduling and heartbeat

Do not claim work before execution capacity exists.

Preferred implementation:
- acquire a worker permit first;
- claim at most the number of acquired permits;
- submit immediately;
- no claim waits un-heartbeated in a general queue.

Heartbeat executor MUST not be a single blocking failure domain. Size it to active worker parallelism or use an equivalent isolated/bounded renewal design.

Every renewal has a DB timeout shorter than heartbeat interval. One blocked claim renewal MUST NOT prevent unrelated claims from renewing.

Shutdown order:
1. stop scheduler/claiming;
2. let active workers complete or timeout;
3. keep heartbeat alive while workers are active;
4. stop heartbeat;
5. terminate worker executor.

Stale-ingestion recovery MUST page/claim beyond a contended oldest batch and distribute work across pods instead of repeatedly selecting the same rows.

## 22. Document operation lock

Do not consume a connection from the main application pool for the full external ingestion/cleanup critical section.

Implement a dedicated lock DataSource/Hikari pool for session advisory locks, with its own bounded maximum and timeout, or an equivalent leased distributed document lock.

The main SQL pool MUST remain available while document locks are held.

Add a small-pool integration test proving concurrent different-document operations do not deadlock/starve waiting for a second connection.

## 23. Embedding lifecycle and re-embedding

EmbeddingProfileResolver builds the active profile fingerprint from provider/model/dimensions/distance/tokenizer configuration.

Startup:
- active profile must be persisted/resolved;
- incompatible published profiles make readiness DOWN unless re-embedding migration mode is explicitly enabled.

ReembeddingService:
- reads canonical published content;
- creates a new STAGING generation using the new profile;
- stages manifests/relational state;
- writes new vectors;
- atomically publishes only on success;
- crash/failure leaves the prior generation published.

Mixed vector spaces MUST never participate in one retrieval result.

## 24. API validation, errors, auth and idempotency

### 24.1 Request limits

Add validation/custom validators for at least:

- documentId: nonblank, <=100, no control/newline characters;
- title/source: bounded;
- text: configured maximum characters/bytes;
- language: canonical supported value;
- question: bounded maximum;
- metadata: bounded entry count/depth/serialized bytes;
- identifier-derived values <=500.

Configure server/container request-body limits as defense in depth.

### 24.2 Stable error model

Add @RestControllerAdvice with stable JSON error fields:
- code
- message
- requestId/correlationId
- status
- optional safe details

Do not leak stack traces, SQL or document text.

Distinguish validation 400, auth 401/403, idempotency conflict 409, overload 429/503, backend degraded 503 and internal 500.

### 24.3 Authentication

Add Spring Security.

Minimum supported contract:
- health liveness/readiness endpoints unauthenticated;
- /api/** authenticated;
- configurable API-key/Bearer mechanism for current deployment;
- explicit local-only development profile may permit unauthenticated access only when intentionally enabled;
- production startup fails closed when required credentials are absent.

### 24.4 Idempotency

Accept Idempotency-Key for ingestion.

Canonical request fingerprint includes normalized documentId/title/text/source/language/domain/metadata and any retention inputs.

Same key/same fingerprint returns original successful KnowledgeIngestionResponse and does not create a new generation or refresh TTL.

Same key/different fingerprint -> 409.

## 25. Readiness and observability

Add readiness contributors for:
- PostgreSQL;
- active embedding profile compatibility;
- Ollama embedding capability/model availability;
- Ollama chat capability/model availability;
- PgVector schema/vector compatibility.

Liveness MUST remain process-local and MUST NOT fail only because Ollama is temporarily unavailable.

Add bounded-cardinality Micrometer metrics for:
- ingestion duration/chunks/failures/compensation;
- vector add/delete/reconciliation latency;
- generation state counts;
- retrieval per-strategy latency/hits/status;
- reranker timeout/failure;
- reference query/candidate counts;
- context serialized tokens/chunks;
- answer generation latency/failure;
- citation validation failures;
- retention claims/deletes/failures/stale claims/lease loss/heartbeats/backlog/run duration.

No document/question text in metric labels.

## 26. Quality and verification architecture

### 26.1 Correct metrics

Fix RetrievalQualityMetrics.ndcgAtK so one relevant chunk contributes gain at most once.

Required properties:
- 0 <= nDCG <= 1;
- duplicate relevant IDs cannot increase score above the unique ranking;
- empty relevant set has the documented result;
- Recall and MRR remain bounded.

Add property-based or generated permutation/duplicate tests.

### 26.2 Production pipeline quality gate

MultilingualRetrievalQualityRegressionTest MUST NOT claim production non-regression from hard-coded finalRanked fixtures.

Keep fixture-only tests for metric arithmetic.

Add a corpus-backed production pipeline benchmark that executes:
- QueryChunker;
- RetrievalPlanner;
- ParallelRetrievalExecutor;
- production PostgreSQL lexical/identifier/reference repositories;
- production PgVectorStore write/search adapter with deterministic test EmbeddingModel;
- ResultFusion;
- Reranker;
- KnowledgeExpansion;
- ContextBudget.

Compute actual ranked chunk IDs and per-language Recall@5/10, MRR and nDCG.

A mutation that reverses fusion ranking or makes vector retrieval empty MUST fail the production quality gate.

### 26.3 PgVector E2E

A Testcontainers PostgreSQL image with pgvector MUST instantiate the actual Spring AI PgVectorStore configuration.

Test:
- add real vectors using deterministic embedding model;
- metadata persistence;
- published-generation vector visibility;
- document scope;
- similarity threshold;
- delete exact physical IDs;
- partial/failure compensation path where injectable;
- reconciliation from metadata when manifest is absent.

Mock VectorStore tests remain useful unit tests but cannot verify D01/D02/D19/D58/D71.

## 27. Defect-by-defect implementation and test contract

The following mapping is mandatory. A defect may share implementation with other defects, but it MUST have an explicit regression assertion.

### D01 — orphan vectors after partial add
Code: manifest-first staging; compute/store all intended physical IDs before vectorStore.add; on any add exception delete the complete attempted set; mark cleanup_required if compensation fails; reconciliation consumes manifest.  
Tests: keep failedVectorWriteCompensatesEntireNewGenerationIdSet; add PgVector integration/fault test and crash-reconciliation test.

### D02 — published-generation retrieval visibility
Code: every vector/lexical/identifier/reference read is constrained to lifecycle.published_generation and ACTIVE retention state.  
Tests: failed/staging generation is invisible in all strategies; successful publication switches all modalities together.

### D03 — destructive re-ingestion
Code: remove replacePreviousGeneration-before-publish behavior; old generation cleanup starts only after atomic publication of new generation.  
Tests: keep failedReplacementKeepsPreviousReadyGenerationIntact and extend to lexical/identifier/reference/vector E2E.

### D04 — wrong fusion representative
Code: fused result resolves canonical published projection; identifier snippet is evidence only.  
Tests: identifier+semantic hit for same chunk returns canonical full text/provenance.

### D05 — identifier-only query does not resolve canonical chunk
Code: exact identifier hit resolves canonical published projection and may seed bounded neighbor expansion.  
Tests: identifier-only query returns canonical chunk, not only contextText.

### D06 — RU/EN FTS index mismatch
Code: language-specific query/index expressions using russian/english configs.  
Tests: corpus retrieval + EXPLAIN/index contract.

### D07 — no retrieval deadlines
Code: strategy/request deadlines, cancellation and backend timeouts.  
Tests: blocked vector/lexical/reference strategy cannot exceed configured request deadline.

### D08 — CallerRuns saturation
Code: retrieval executor AbortPolicy/fail-fast result status; no work on HTTP caller thread.  
Tests: saturated executor returns REJECTED/degraded promptly and scorer/strategy never executes on caller.

### D09 — retention shutdown race
Code: shutdown ordering keeps heartbeat alive until active workers finish; heartbeat startup failure enters cleanup/finally.  
Tests: shutdown while work queued/active leaks no reservations or claims.

### D10 — prompt injection from retrieved text
Code: structured JSON context + explicit untrusted-data system rule.  
Tests: malicious chunk instructions cannot alter source framing and are passed as data.

### D11 — uncited grounded answer accepted
Code: grounded non-empty factual answer requires >=1 valid citation or deterministic fallback/error.  
Tests: model answer without citation is rejected/repaired according to policy.

### D12 — citation sanitizer whitespace mismatch
Code: sanitize with the same SOURCE regex, not string replace.  
Tests: multiple whitespace variants are removed consistently.

### D13 — page provenance inconsistency
Code: typed Provenance normalizes legacy page/pageNumber/pageFrom into pageFrom/pageTo.  
Tests: all accepted legacy forms produce the same SourceRef.

### D14 — expansion drops provenance
Code: neighbor built from canonical SearchProjection + Provenance.  
Tests: expanded neighbor citation retains source/page/domain/language/section.

### D15 — language spelling/case mismatch
Code: canonical KnowledgeLanguage at API write boundary.  
Tests: RU/russian/Ru aliases normalize or invalid aliases reject per contract; storage uses canonical code only.

### D16 — ambiguous Kazakh/Russian
Code: detector supports ambiguous/unknown result and multi-language fallback.  
Tests: shared-Cyrillic Kazakh fixture is not confidently forced to RU; clear KK/RU remain correct.

### D17 — missing API limits
Code: Bean/custom validation and container body limits.  
Tests: boundary max accepted, max+1 rejected for documentId/question/text/metadata/derived identifier sizes.

### D18 — unauthenticated API
Code: Spring Security auth contract, health exceptions, local-only explicit bypass.  
Tests: unauthenticated API rejected; authenticated accepted; health probes remain accessible.

### D19 — incompatible vector IDs
Code: all code calls VectorIdentity.physicalId; delete duplicate local algorithm.  
Tests: persistence manifest/vector document/retention use exactly the same ID.

### D20 — relational state not generation-scoped
Code: generation column and published-only repositories for projections/identifiers/reference graph.  
Tests: failed N+1 leaves N relational results unchanged; successful publish switches all.

### D21 — citation integer overflow
Code: guarded/bounded parse; oversized markers become invalid.  
Tests: extremely large SOURCE number never throws.

### D22 — stale retention destructive tail
Code: fence-aware SQL deletes and re-check before every external destructive operation.  
Tests: lose claim between vector phase and relational cleanup; stale worker cannot mutate tail.

### D23 — context budget ignores envelope
Code: estimate exact serialized ContextEnvelope + framing.  
Tests: long metadata and many chunks never exceed configured budget.

### D24 — poisoned reranker worker
Code: transport timeout plus isolated bounded reranker execution; timed-out dependency cannot monopolize future requests.  
Tests: blocking scorer ignoring interrupt cannot prevent a later request from being serviced within its bound.

### D25 — identifier miss becomes global scope
Code: represent “scope not requested” separately from “required scope resolved empty”; fail closed.  
Tests: unknown identifier does not execute unrestricted vector/lexical retrieval.

### D26 — reference N+1 amplification
Code: typed batched resolution, request-level cap, pre-query dedupe.  
Tests: max input produces bounded SQL query count and candidate count.

### D27 — no real reference producer
Code: StructuralAnchor targets + CrossReference edges persisted by normal ingestion.  
Tests: ingest real source and target documents; reference retrieval works without manual DB inserts.

### D28 — advisory lock main-pool starvation
Code: dedicated lock datasource or equivalent lock service outside main SQL pool.  
Tests: tiny main pool + concurrent different-document locks completes without starvation.

### D29 — no final LLM deadline
Code: AnswerGenerationService with application + transport deadline.  
Tests: blocked chat call returns stable timeout/degraded error within bound.

### D30 — null metadata crashes RetrievalHit
Code: metadata validator/canonicalizer; core provenance typed; RetrievalHit receives null-free immutable map.  
Tests: null-containing request rejected/sanitized deterministically; retrieval never throws Map.copyOf NPE.

### D31 — lifecycle cannot represent published N + staging N+1
Code: published_generation pointer + knowledge_document_generation journal.  
Tests: state-machine test explicitly observes N PUBLISHED while N+1 STAGING/FAILED.

### D32 — no embedding profile lifecycle
Code: persisted EmbeddingProfile + generation FK + readiness + ReembeddingService.  
Tests: same-dimension model change detected; dimension mismatch detected; reembedding failure preserves old publication.

### D33 — destructive/schema-drift migration
Code: preservation migration, additive upgrade path, Liquibase-only runtime schema.  
Tests: seed legacy schema/data, run full changelog, verify rows and constraints survive.

### D34 — advertised identifier types missing parsers
Code: implement every advertised parser or remove unsupported enum/API/docs entries.  
Tests: parameterized capability test guarantees each supported type has WRITE and READ parser coverage.

### D35 — punctuation-colliding identifier canonicalization
Code: type-aware canonicalizer preserving significant separators.  
Tests: AB-12 and AB/12 do not collide unless an explicit type rule says they are equivalent.

### D36 — invalid chunking properties accepted
Code: @Validated ChunkingProperties with positive bounds and min <= target <= soft <= hard.  
Tests: application context rejects every invalid ordering and non-positive value.

### D37 — no ingestion idempotency
Code: Idempotency-Key journal + canonical request fingerprint.  
Tests: lost-response retry creates no new generation/TTL refresh; conflicting body returns 409.

### D38 — missing retention observability
Code: metrics and structured events for claim/delete/fail/stale/lease/recovery/backlog.  
Tests: SimpleMeterRegistry assertions for all outcomes; no high-cardinality document text labels.

### D39 — unbounded vector/embedding ingestion
Code: vector-write application/transport deadline; failure compensation and lock release.  
Tests: never-returning dependency fails within bound and document lock becomes acquirable.

### D40 — queued claim lease/retry consumption
Code: claim only available execution permits; no un-heartbeated claimed queue; retry count represents cleanup failures, not queue waits.  
Tests: saturated workers cannot consume retry budget before cleanup begins.

### D41 — legal polarity/substring classifier
Code: phrase/token boundary matcher with negative-first precedence.  
Tests: must not/shall not/may not and substring counterexamples across supported languages.

### D42 — Kazakh ТАРМАҚША hierarchy
Code: exact token mapping, not overlapping prefix matching.  
Tests: article > тармақ > тармақша ancestry preserved.

### D43 — shutdown silently discards async tasks
Code: reject exceptionally; graceful bounded drain; no CallerRunsPolicy.  
Tests: submissions during/after shutdown complete exceptionally, never hang join.

### D44 — no Unicode normalization
Code: NFC text/identity boundary; reviewed type-aware identifier compatibility normalization.  
Tests: NFC/NFD logical equivalents behave per canonical policy; distinct confusables remain distinct.

### D45 — stale-ingestion recovery starvation
Code: DB-backed claim/paging or continue scanning beyond locked oldest batch with disjoint multi-pod work.  
Tests: locked first batch does not starve later stale rows in same run.

### D46 — oversized extracted identifiers
Code: parser/canonicalizer upper bound <= schema before persistence.  
Tests: >500 token is rejected/skipped deterministically and never produces SQL length failure.

### D47 — provenance metadata prompt injection
Code: JSON escaping/whitelist Provenance; no raw source delimiters.  
Tests: source/title containing newline/fake SOURCE markers stays inside one JSON field.

### D48 — surrogate-pair split
Code: code-point/grapheme-safe splitter.  
Tests: emoji/supplementary CJK never produce unpaired surrogate and rejoin losslessly.

### D49 — pod clock skew controls leases
Code: all lease ownership comparisons use DB clock.  
Tests: opposing injected application clocks do not change claim/renew/reclaim behavior.

### D50 — ambiguous ChunkIdentity serialization
Code: versioned length-prefixed canonical encoding + ownership-safe DB constraints.  
Tests: previously colliding tuples now differ; projection conflict cannot move chunk ownership between documents.

### D51 — false-positive identifiers from ordinary prose
Code: explicit marker/shape validation and negative corpus.  
Tests: contract termination/order status/case management yield zero identifiers.

### D52 — exact authority lost in rerank
Code: authority tier retained through fusion/rerank.  
Tests: exact target cannot rank below semantic-only candidate under default policy.

### D53 — expansion discarded by context ordering
Code: integrate expansion before final context cutoff/reserve quota.  
Tests: useful neighbor survives even when original ranked list exceeds contextMaxChunks.

### D54 — numbered medical facts become headings
Code: structural role separated from semantic type; medical classifier still runs.  
Tests: numbered dosage/contraindication/monitoring lists remain atomic typed facts in KK/RU/EN/ZH.

### D55 — query intents silently truncated
Code: QueryDecompositionResult with overflow/catch-all representation; bound work without silent loss.  
Tests: 9+ intents preserve each intent or explicit catch-all/overflow signal.

### D56 — final chunk exceeds hard max
Code: unconditional hard check before adding a unit and final invariant assertion.  
Tests: sub-min first unit + near-hard second never emits >hardMax.

### D57 — embedding payload exceeds budget
Code: budget exact EmbeddingTextBuilder output with embedding profile estimator.  
Tests: long title/deep section still respects model limit.

### D58 — no production PgVector E2E
Code/test infrastructure: real PgVectorStore Testcontainers gate with deterministic EmbeddingModel.  
Tests: add/search/filter/delete/publication/compensation/reconciliation use production adapter.

### D59 — LIKE wildcard semantics
Code: literal escaping and ESCAPE clause for KK/ZH fallback; minimum/selectivity rule.  
Tests: %, _, 5% mean literals and cannot match-all.

### D60 — single heartbeat thread failure domain
Code: isolated/bounded per-claim renewal concurrency + DB query timeout.  
Tests: blocked renewal A does not stop renewal B.

### D61 — Chinese sentence boundary requires whitespace
Code: script-aware segmenter splits 。！？ without whitespace.  
Tests: one Chinese paragraph with dosage+contraindication+monitoring becomes separate semantic facts.

### D62 — retrieval failures collapse to empty
Code: typed RetrievalOutcome and aggregate degraded/error policy.  
Tests: healthy zero-hit differs from backend outage; identifier outage cannot trigger global fallback.

### D63 — invalid reranker duration accepted
Code: positive min/max validation and precision-safe timeout usage.  
Tests: zero/negative/sub-min/too-large rejected at startup.

### D64 — missing KK/ZH reference grammar
Code: language-specific typed parsers for native forms.  
Tests: equivalent KK/RU/EN/ZH references map to same canonical target type/value.

### D65 — no AI/vector readiness
Code: readiness contributors for Ollama chat/embed/profile/pgvector; liveness separate.  
Tests: DB healthy + Ollama unavailable => readiness DOWN, liveness UP.

### D66 — expired worker can markFailed
Code: markFailed SQL requires claim token + generation + unexpired DB-time lease.  
Tests: expiry immediately before cleanup exception prevents state/retry mutation.

### D67 — NaN/Infinity reranker scores
Code: finite/shape checks before cosine and before sorting.  
Tests: NaN/+Inf/-Inf/dimension mismatch cause safe fallback, never rank promotion.

### D68 — relational batch partial commit
Code: one staging transaction around all relational batch writes.  
Tests: deliberate second-batch failure (>100 rows) rolls back entire staged relational generation.

### D69 — nDCG duplicate inflation
Code: unique relevance gain; range invariant.  
Tests: relevant {A}, ranking [A,A] never produces >1; generated duplicate/property tests.

### D70 — hard-coded quality regression is falsely green
Code/test: production pipeline benchmark generates final rankings; fixtures only validate metric math.  
Tests: intentional ranking mutation causes gate failure.

### D71 — missing manifest guesses wrong physical vector IDs
Code: reconciliation enumerates actual vectors by metadata and identity version; never guess chunk ID.  
Tests: missing manifest + current UUID/v2 IDs cannot mark DELETED until all actual vectors verified absent.

### D72 — abbreviation sentence splitting
Code: abbreviation-aware query sentence segmentation.  
Tests: ст. 25, п. 3, Art. 25, No. 42 remain one retrieval unit while real sentence boundaries still split.

## 28. Required test classes and additions

The implementation MUST add/extend at least the following test surfaces.

Unit/property tests:
- ChunkIdentityTest
- VectorIdentityTest
- UnicodeNormalizationTest
- IdentifierCanonicalizerTest
- BusinessIdentifierParsersTest
- CrossReferenceParserTest
- StructuralUnitExtractorTest
- SemanticChunkerTest / SemanticChunkingDepthTest
- SentenceSegmenterTest
- QueryDecomposerTest
- QueryLanguageDetectorTest
- CitationValidatorTest
- ContextAssemblerTest
- ContextBudgetTest
- ResultFusionTest
- RerankerTest
- EmbeddingSemanticRerankScorerTest
- RetrievalQualityMetricsTest
- configuration binding tests for ChunkingProperties/RetrievalProperties/API/security

PostgreSQL/Testcontainers:
- LegacySchemaUpgradeTest
- PublicationGenerationIntegrationTest
- GenerationStagingTransactionTest
- PostgresRetrievalIntegrationTest
- IdentifierGenerationVisibilityIntegrationTest
- ReferenceGraphIntegrationTest
- RetentionGenerationFencingIntegrationTest
- RetentionLeaseClockIntegrationTest
- RetentionFailureFenceIntegrationTest
- StaleIngestionRecoveryIntegrationTest
- DocumentOperationLockIntegrationTest
- IdempotencyIntegrationTest

PgVector production-adapter:
- PgVectorPublicationIntegrationTest
- PgVectorCompensationIntegrationTest
- PgVectorReconciliationIntegrationTest
- PublishedVectorSearchIntegrationTest

End-to-end service:
- KnowledgeIngestionPublicationE2ETest
- FailedReingestionE2ETest
- IdentifierOnlyRetrievalE2ETest
- ReferenceResolutionE2ETest
- RetrievalDegradedOutcomeE2ETest
- RagCitationIntegrityE2ETest
- MultilingualProductionRetrievalQualityTest
- ReadinessIntegrationTest
- SecurityApiIntegrationTest

Concurrency/fault injection:
- retrieval saturation/shutdown;
- reranker poisoned dependency;
- vector write timeout;
- heartbeat head-of-line;
- queued retention lease;
- lease loss at every destructive boundary;
- failure after every ingestion side effect;
- >100 row batch rollback;
- multi-pod stale recovery fairness.

## 29. Test data requirements

Maintain versioned deterministic corpora under src/test/resources.

Required corpora:

- legal KK/RU/EN/ZH hierarchy and cross-reference forms;
- medical KK/RU/EN/ZH dosage/contraindication/monitoring facts;
- Chinese punctuation without whitespace;
- ordinary prose containing contract/order/case words but no identifiers;
- punctuation-collision identifiers AB-12 vs AB/12;
- Unicode NFC/NFD and supplementary code points;
- wildcard text containing %, _, 5%;
- malformed >500-character identifier token;
- malicious prompt/context metadata;
- legacy database schema/data fixture;
- retrieval benchmark with explicit relevant chunk IDs per language.

No test should depend on live nondeterministic model output unless it is explicitly the separate live-model gate.

## 30. Configuration additions

Add documented properties with validated bounds, including:

akmai.ingestion.vector-write-timeout  
akmai.retrieval.request-timeout  
akmai.retrieval.strategy-timeout  
akmai.retrieval.answer-timeout  
akmai.retrieval.reranker-timeout  
akmai.api.max-document-chars  
akmai.api.max-question-chars  
akmai.api.max-metadata-bytes  
akmai.security.enabled  
akmai.security.api-key or equivalent external secret binding  
akmai.lock.datasource.* / dedicated lock-pool settings  
embedding profile/token budget settings needed by active model

Keep secrets out of committed application.yml values. Defaults must be safe for local development and explicit for production.

## 31. Error and recovery semantics

The following outcomes MUST be deterministic.

- Failed N+1 ingestion with published N -> N stays published; N+1 FAILED; cleanup is retryable.
- Crash after staging relational state but before vector add -> stale STAGING generation is reconcilable and invisible.
- Crash during partial vector add -> complete attempted IDs are known from manifest and reconcilable.
- Crash after vectors but before publication -> vectors remain unpublished/invisible and are reconcilable.
- Crash immediately after publication -> N+1 remains published; old N may remain physically but is invisible and cleanup can resume.
- Retrieval backend failure -> degraded/error status, never fabricated zero evidence.
- Expired retention lease -> stale worker loses all mutation rights.
- Missing vector manifest -> reconciliation required; never mark DELETED by guessing IDs.
- Model emits malformed embedding/citation -> safe fallback/invalid citation, never undefined ranking/request crash.
- Overload/shutdown -> fast bounded failure, never unbounded caller execution or hanging future.

## 32. Ledger status rules

For each Dxx:

OPEN -> IMPLEMENTING:
- production code or regression test work has started.

IMPLEMENTING -> FIXED:
- production implementation and its dedicated regression tests are committed;
- local/CI test evidence may still be pending.

FIXED -> VERIFIED:
- dedicated regression test exists;
- relevant integration/E2E adapter is exercised;
- exact PR-head SHA has a successful GitHub Actions verify run;
- no later commit invalidates that evidence.

The ledger MUST retain all 72 rows permanently.

## 33. CI / release gate

PR #13 MUST remain draft until every condition below is true.

1. D01–D72 have no OPEN or IMPLEMENTING P0/P1 entries.
2. Every P0/P1 is VERIFIED.
3. Every P2 is VERIFIED or has an explicit accepted disposition recorded in the ledger.
4. Legacy upgrade Testcontainers gate passes.
5. Production PgVectorStore E2E gate passes.
6. Generation publication/reingestion/retention race suites pass.
7. Multilingual production-pipeline quality gate passes.
8. Retrieval metrics satisfy mathematical invariants.
9. Security/readiness/API tests pass.
10. mvn -B spotless:check passes.
11. mvn -B clean verify passes.
12. GitHub Actions verify is successful for the exact final PR HEAD SHA.
13. The exact SHA is written into the ledger verification evidence.
14. No earlier SHA may be cited as final evidence after any subsequent code/document change.

## 34. Definition of Done

The remediation is complete only when the implementation demonstrates, through code and automated tests, that:

- publication is generation-safe and non-destructive;
- failed/stale generations are invisible and recoverable;
- all retrieval modalities agree on one published generation;
- physical vector identity is canonical, versioned and reconcilable;
- retention cannot delete or mutate state without a valid fence;
- all external work is bounded by deadlines and overload/shutdown behavior is deterministic;
- multilingual parsing/chunking/reference behavior is covered by real corpora;
- exact identifiers keep authority without producing false-positive scope;
- prompts/citations/provenance are structurally safe;
- API/auth/idempotency/readiness contracts are explicit;
- quality metrics and quality gates test the production pipeline rather than fixtures alone;
- exact-SHA CI is green.

No TODO, FIXME, no-op or “temporary compatibility fallback” may remain in these contracts unless it is explicitly documented in the ledger with an accepted disposition.
