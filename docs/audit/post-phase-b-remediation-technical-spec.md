# AKMAI — Post-Phase-B Defect Remediation Technical Specification

Branch: fix/post-phase-b-defect-remediation  
Source ledger: docs/audit/post-phase-b-defect-ledger.md  
Scope: D01–D72  
Status: implementation contract  
Specification consistency review: completed; the binding decisions below supersede any earlier audit-time alternatives.  
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
- next_generation BIGINT NOT NULL — the next value to allocate, initialized to 1
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

Generation allocation is exact: in one PostgreSQL transaction read N = next_generation, increment next_generation to N+1, and insert generation N as STAGING. Never derive a generation from MAX(generation).

ACTIVE means “not in a retention deletion workflow”; it MAY temporarily have published_generation=NULL for a never-published document. Retrieval still requires both retention_status=ACTIVE and published_generation IS NOT NULL.

After knowledge_document_generation exists, add a DEFERRABLE composite foreign key from (document_id, published_generation) to (document_id, generation). Publication and deletion transactions MUST keep the pointer and generation journal consistent.

### 3.2 Generation journal

Add knowledge_document_generation:

- document_id VARCHAR(100)
- generation BIGINT
- generation_status STAGING|PUBLISHED|FAILED|RETIRED|CLEANED
- embedding_profile_id VARCHAR(128)
- content_fingerprint VARCHAR(64)
- physical_id_version SMALLINT
- started_at TIMESTAMPTZ
- published_at TIMESTAMPTZ NULL
- failed_at TIMESTAMPTZ NULL
- retired_at TIMESTAMPTZ NULL
- cleaned_at TIMESTAMPTZ NULL
- last_error VARCHAR(1000) NULL
- cleanup_required BOOLEAN NOT NULL DEFAULT false
- PRIMARY KEY(document_id, generation)

Required constraints/indexes:

- generation > 0;
- one PUBLISHED generation per document at most;
- lookup by generation_status/started_at for stale recovery;
- FK from generation-scoped relational state where practical;
- allowed transitions are STAGING -> PUBLISHED|FAILED, PUBLISHED -> RETIRED, FAILED|RETIRED -> CLEANED;
- lifecycle.published_generation may reference only a PUBLISHED generation;
- retention deletion of the currently published generation atomically clears lifecycle.published_generation, changes retention_status to DELETED and changes that generation PUBLISHED -> RETIRED; physical/relational cleanup then changes RETIRED -> CLEANED.

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

Persist generation-scoped targets and edges in the following dedicated tables:

- knowledge_reference_target(document_id, generation, chunk_id, type, canonical_value, ...)
- knowledge_reference_edge(document_id, generation, source_chunk_id, type, canonical_value, raw_value, target_scope, target_document_id, ...)

Target uniqueness is (document_id, generation, type, canonical_value). A reference without an explicit external document/instrument identifier has target_scope=SAME_DOCUMENT and resolves only inside the source document. Cross-document resolution is allowed only when the parsed reference contains an explicit target document/instrument identity; then target_scope=EXPLICIT_DOCUMENT and target_document_id is mandatory.

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

Every non-legacy generation MUST reference one profile.

Add a singleton knowledge_embedding_runtime row with:
- active_profile_id;
- migration_profile_id nullable;
- migration_status IDLE|STAGING|READY_TO_CUTOVER;
- row_version;
- updated_at.

Every retrieval-visible generation MUST use active_profile_id. Startup readiness MUST compare the configured serving profile against knowledge_embedding_runtime.active_profile_id. Same-dimension but different model/profile is incompatible unless the corpus-level re-embedding flow in section 23 is active.

### 3.7 Idempotency journal

Add knowledge_ingestion_request:

- idempotency_key VARCHAR(200) PRIMARY KEY
- document_id VARCHAR(100)
- request_fingerprint VARCHAR(64)
- generation BIGINT NULL
- request_status IN_PROGRESS|SUCCEEDED|FAILED
- generation BIGINT NULL
- claim_id UUID NULL
- lease_until TIMESTAMPTZ NULL
- response_json JSONB NULL
- last_error VARCHAR(1000) NULL
- created_at / updated_at

The same idempotency key + same fingerprint returns the original completed result. Same key + different fingerprint returns 409. Concurrent same-key execution is lease-fenced with PostgreSQL time: an unexpired IN_PROGRESS row returns 409 INGESTION_IN_PROGRESS plus Retry-After; an expired row may be reclaimed. A reclaimed request MUST inspect its linked generation before allocating another one: if that generation is PUBLISHED, finalize SUCCEEDED and return the original result; if STAGING/FAILED, reconcile it before retrying.

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

Legacy backfill rules are explicit:
- existing READY lifecycle rows may be converted to a PUBLISHED generation only when their current generation and relational rows are internally consistent;
- existing INGESTING/INGEST_FAILED rows are never guessed to be published; convert them to FAILED/reconciliation-required and leave published_generation NULL unless prior publication can be proven from durable data;
- pre-profile vectors are assigned a sentinel embedding profile legacy-unknown and physical_id_version=0. Readiness remains DOWN while any retrieval-visible generation uses legacy-unknown. An operator must explicitly attest/map the historical profile or run re-ingestion/re-embedding; the application MUST NOT assume the currently configured model created legacy vectors;
- legacy rows without a verifiable vector manifest enter reconciliation-required state rather than being declared clean.

## 5. Core Java model changes

Introduce or refactor the following types.

- DocumentLifecycle: publishedGeneration, nextGeneration, retentionStatus and claim fields.
- DocumentGeneration: immutable generation journal record.
- GenerationStatus.
- RetentionStatus; replace the current mixed LifecycleStatus semantics.
- KnowledgeLanguage enum with exactly KK, RU, EN, ZH plus a query-only UNKNOWN decision state that is never persisted as document language.
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

The production design MUST exploit the actual deployment fact that canonical relational state and pgvector rows are stored in the same PostgreSQL database. Do not keep vector persistence behind an opaque VectorStore mutation boundary.

Remove VectorStore mutation calls from PersistenceCoordinator. Introduce:

- GenerationEmbeddingService — computes embeddings through the qualified vectorWriteEmbeddingModel, validates count/dimension/finiteness and performs no database writes;
- GenerationPublicationService — owns generation allocation/publication state machine;
- GenerationPublicationRepository — performs one atomic PostgreSQL publication transaction;
- PostgresGenerationVectorRepository — inserts/deletes pgvector rows using PGvector/JdbcTemplate inside caller transactions;
- PublishedVectorSearchRepository — performs published-only similarity search.

Required normal ingestion sequence:

1. Validate/idempotency-claim the request.
2. Allocate generation G in a short PostgreSQL transaction:
   - lock/create lifecycle row;
   - reject new allocation while embedding migration blocks ingestion;
   - G = next_generation; increment next_generation;
   - insert generation G as STAGING with the current active embedding_profile_id and request/idempotency identity.
3. Release the transaction. Build canonical chunks/projections/identifiers/reference graph in memory.
4. Generate every embedding for G outside any database transaction using GenerationEmbeddingService and the profile captured at allocation time.
5. If embedding generation fails or times out:
   - mark G FAILED with no publication change;
   - no vector/projection/identifier rows exist for G;
   - published generation P remains unchanged.
6. Build vector IDs, reserved metadata and manifest rows in memory.
7. Publish G in ONE PostgreSQL transaction:
   - SELECT the lifecycle row FOR UPDATE;
   - require G is still STAGING;
   - require G.embedding_profile_id still equals knowledge_embedding_runtime.active_profile_id;
   - reject publication if lifecycle.published_generation > G (a newer request already won);
   - insert all generation-scoped projections;
   - insert all generation-scoped identifiers;
   - insert all reference targets/edges;
   - insert the complete vector manifest;
   - insert every pgvector row for G into the vector table belonging to G.embedding_profile_id;
   - retire prior PUBLISHED generation P if present;
   - mark G PUBLISHED;
   - set lifecycle.published_generation=G and retention_status=ACTIVE;
   - apply retention policy/expiry;
   - finalize the idempotency journal as SUCCEEDED with the response.
8. Commit. Publication becomes visible atomically across vector/lexical/identifier/reference modalities.
9. Clean retired P asynchronously in a separate database transaction; cleanup failure cannot roll back G publication.

A generation with a smaller number than the already-published generation is SUPERSEDED: its publish transaction inserts no retrieval state and a follow-up transition marks it FAILED/SUPERSEDED. A newer STAGING generation does not prevent an older G from publishing temporarily; if the newer generation later succeeds it atomically supersedes G. This gives deterministic last-successful-generation semantics without holding a document lock across Ollama calls.

Unknown transaction outcome handling is mandatory. If JDBC reports a connection/commit failure where commit outcome is uncertain, PublicationOutcomeResolver re-reads generation G, lifecycle.published_generation and idempotency state before taking any failure action:
- if G is PUBLISHED and is/was the committed pointer, treat publication as successful;
- if G remains STAGING with no rows committed, mark/retry according to policy;
- never perform destructive compensation based only on an ambiguous commit exception.

Re-ingestion versus retention is coordinated through lifecycle row transactions, not a long-lived advisory lock. Generation allocation atomically invalidates a DELETE_PENDING claim token if cleanup has not started and retention claiming excludes documents with STAGING generations. If retention cleanup has already locked the lifecycle row and commits first, ingestion subsequently starts from DELETED state. If generation allocation commits first, the stale retention claim fails its fence.

No old published generation is deleted before the new generation transaction commits.

## 7. Canonical identity

### 7.1 ChunkIdentity

Replace newline-delimited concatenation with an unambiguous binary/length-prefixed encoding over:

- NFC-normalized documentId;
- chunkIndex as fixed-width integer;
- NFC-normalized sectionPath;
- NFC-normalized normalizedText.

Serialize a version byte 0x02 followed by 32-bit big-endian byte lengths and UTF-8 bytes for every variable field, plus chunkIndex as signed 64-bit big-endian. Hash the resulting bytes with SHA-256 and encode exactly as "c2_" + 64 lowercase hexadecimal characters.

Different tuples MUST never produce identical pre-hash bytes.

### 7.2 VectorIdentity

Use exactly one implementation everywhere.

The physical ID MUST remain compatible with the configured Spring AI PgVectorStore UUID id type.

VectorIdentity v2 algorithm:
1. canonical bytes = version byte 0x02 + length-prefixed NFC documentId + generation as signed 64-bit big-endian + length-prefixed chunkId;
2. digest = SHA-256(canonical bytes);
3. take the first 16 digest bytes;
4. set RFC 4122 variant bits and UUID version bits to version 8/custom;
5. construct java.util.UUID and use UUID.toString().

The exact encoding MUST be deterministic, versioned and tested. PersistenceCoordinator MUST NOT implement its own vector ID function.

knowledge_document_vector_generation MUST store physical_id_version and embedding_profile_id.

### 7.3 Missing manifest reconciliation

Never infer current physical IDs from chunk_id.

Add VectorReconciliationService / PublishedVectorAdminRepository able to enumerate vectors by trusted metadata documentId + generation through the production PgVector table/adapter, backfill a manifest and only then delete.

If enumeration cannot prove absence, retention MUST fail/retry and MUST NOT mark DELETED.

## 8. Profile-scoped pgvector storage and published retrieval

A fixed PostgreSQL vector(N) column cannot host generations from embedding profiles with different dimensions. Therefore vector storage is profile-scoped.

Extend knowledge_embedding_profile with:
- vector_schema;
- vector_table;
- index_type;
- distance_type;
- dimensions.

EmbeddingProfileStorageManager deterministically derives table name as akmai_vector_p_<first16 lowercase hex chars of profile_id>. Identifiers are generated by AKMAI, validated against [a-z_][a-z0-9_]* and never taken from user metadata.

For each profile, ensure a PostgreSQL table equivalent to:

- id UUID PRIMARY KEY;
- content TEXT NOT NULL;
- metadata JSONB NOT NULL;
- embedding VECTOR(profile.dimensions) NOT NULL.

Create the configured HNSW/IVFFlat/exact index using that profile's distance type. DDL runs before any generation may reference the profile. Profile registration is serialized by a short database transaction/advisory transaction lock; no long-lived session lock is held during embedding work.

PostgresGenerationVectorRepository MUST:
- accept already-computed finite embeddings;
- insert vector rows with canonical UUID VectorIdentity;
- participate in the caller's Spring transaction on the main DataSource;
- batch inserts are allowed but the enclosing publication transaction makes all batches atomic;
- delete only by generation manifest/profile table inside cleanup transactions.

Reserved metadata keys are:
- akmaiMetadataVersion;
- akmaiDocumentId;
- akmaiGeneration;
- akmaiEmbeddingProfileId;
- akmaiChunkId.

User metadata keys beginning with "akmai" are rejected.

PublishedVectorSearchRepository:
- resolves knowledge_embedding_runtime.active_profile_id;
- obtains the active profile/table/distance configuration;
- generates the query vector with retrievalEmbeddingModel for that exact active profile;
- queries only that profile table;
- joins reserved documentId/generation metadata to knowledge_document_lifecycle;
- requires retention_status='ACTIVE';
- requires lifecycle.published_generation = vector generation;
- applies optional explicit document scope;
- applies the profile distance operator, configured threshold and topK;
- returns canonical published RetrievalHit provenance;
- rejects non-finite query/model results.

The similarity score/threshold conversion MUST be defined once in PgVectorDistancePolicy and shared by search tests; do not duplicate Spring AI's implicit formulas.

Legacy public.vector_store is not used by normal new writes after this remediation. LegacyVectorReconciliationRepository handles it only for migration/reconciliation. New profile tables are the production vector adapter.

D58 verification therefore targets the actual production PostgresGenerationVectorRepository + PublishedVectorSearchRepository + profile-table schema, not a mock and not an unused Spring AI PgVectorStore bean.

## 9. Transaction boundaries

There are exactly three persistence transaction classes.

1. Generation allocation transaction:
   - short lifecycle/generation/idempotency state mutation;
   - no Ollama/network calls.

2. Generation publication transaction:
   - projections + identifiers + reference graph + vector manifest + profile-scoped pgvector rows + generation state + published pointer + idempotency success;
   - all batchUpdate calls participate in the same transaction;
   - no Ollama/network calls.

3. Cleanup transaction:
   - claim fence validation + profile vector deletes + relational generation deletes + manifest delete + lifecycle/generation completion;
   - no Ollama/network calls.

Embedding/chat calls always occur outside DB transactions.

A failure in batch 2 of any publication SQL batch rolls back batch 1 and every other publication write. There is no normal-path “partial relational/vector generation” to compensate.

The generation journal remains the recovery journal for crashes before publication and ambiguous transaction outcomes. Stale STAGING rows contain no published retrieval state; recovery marks them FAILED after the configured stale threshold.

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

Aggregate failure policy is exact:
- if an identifier is present, IDENTIFIER is critical for that query unit;
- without an identifier, VECTOR and LEXICAL are alternative primary strategies: at least one must finish SUCCESS or EMPTY; if both FAILED/TIMED_OUT/REJECTED, the unit is criticalFailure;
- REFERENCE and expansion are optional/degraded;
- reranker failure falls back to fused order and is degraded, not critical.

RagQuestionService MUST distinguish:
- healthy zero evidence -> deterministic service-generated “insufficient information” response;
- critical retrieval failure -> stable degraded/503 error contract.

## 11. Deadlines and executor semantics

Add validated properties:

- akmai.retrieval.request-timeout
- akmai.retrieval.strategy-timeout
- akmai.retrieval.reranker-timeout
- akmai.retrieval.answer-timeout
- akmai.retrieval.embedding-http-timeout
- akmai.vector.embedding-http-timeout
- akmai.vector.db-transaction-timeout
- akmai.retention.cleanup-transaction-timeout
- database/query timeouts where required

All Duration values MUST be positive, have explicit minimum/maximum and avoid toMillis precision collapse.

BoundedExecutorFactory MUST use rejection that completes/submits exceptionally; do not use CallerRunsPolicy.

Shutdown MUST:
- stop accepting new requests/tasks and retention claims;
- drain/await active work for a bounded period;
- cancel remaining non-transactional work;
- allow active bounded database transactions to finish or roll back;
- guarantee no CompletableFuture remains forever incomplete.

Transport-level HTTP/JDBC timeouts MUST back application deadlines so cancellation is not dependent only on Thread.interrupt.

Embedding is the only network call in the ingestion publication path and occurs before publication DB mutation. It MUST NOT be detached into a Future whose timeout can race with later state transitions.

Spring AI 1.0.3 auto-configuration shares one OllamaApi between chat and embedding, which cannot express the required independent transport deadlines. Replace that implicit sharing with explicit qualified beans:
- vectorWriteOllamaApi + vectorWriteEmbeddingModel: same embedding model/profile, transport timeout <= akmai.vector.embedding-http-timeout; used only by GenerationEmbeddingService;
- retrievalOllamaApi + retrievalEmbeddingModel: same semantic embedding profile, transport timeout <= akmai.retrieval.embedding-http-timeout and <= strategy timeout; used by PublishedVectorSearchRepository and semantic reranker;
- chatOllamaApi + chatModel: chat transport timeout <= akmai.retrieval.answer-timeout.

Publication and cleanup JDBC work is bounded by transaction/statement timeouts, not Future cancellation. Timeout/client settings are operational and are NOT part of EmbeddingProfile semantic fingerprint.

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

Use a deterministic bounded interleave policy.

Add retrieval.context-expansion-max-chunks, validated as 0..contextMaxChunks. After reranking:
1. take expansionSeeds highest-ranked canonical hits;
2. fetch unique adjacent neighbors ordered by absolute chunk distance then chunkIndex;
3. walk the ranked list in order and emit each ranked hit;
4. immediately after a seed, emit at most one not-yet-emitted nearest neighbor while the expansion-context quota remains;
5. after the quota is exhausted, emit remaining ranked hits only;
6. ContextBudget then applies token/per-document/max-chunk limits and may backfill later ranked hits if an inserted neighbor does not fit.

This policy guarantees expansion can influence context while bounding how many higher-ranked base hits it can displace.

Neighbor hits MUST carry full canonical Provenance.

## 14. Prompt and citation boundary

Replace ad-hoc text concatenation with a structured, escaped context envelope serialized by Jackson.

Mandatory model-facing JSON structure:

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
- for every model-generated nonblank answer produced from nonempty context, require at least one valid citation; the only citation-free “insufficient information” answer is the deterministic service-generated fallback, never arbitrary model text;
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

Map the following case-insensitive trimmed aliases at request ingestion and persist only canonical codes:
- kk: kk, kaz, kazakh;
- ru: ru, rus, russian;
- en: en, eng, english;
- zh: zh, zho, chi, chinese.
Any other value is a validation error.

QueryLanguageDetector returns LanguageDecision(primary, candidates, confidence). Clear Han -> zh; Kazakh-specific Cyrillic -> kk; clear Latin -> en. Shared Cyrillic without Kazakh-specific evidence returns primary=UNKNOWN, candidates=[kk,ru]. Lexical retrieval for that decision executes bounded KK and RU lexical searches and fuses them; it MUST NOT silently force RU. UNKNOWN with no script evidence uses a bounded language-neutral fallback and vector retrieval.

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

Introduce ModelTokenBudgetRegistry keyed by EmbeddingProfile/chat profile. A profile may be READY only when AKMAI has either (a) an exact compatible tokenizer implementation, or (b) a formally verified upper-bound counter for that tokenizer family. Unverified character/3.2 heuristics MUST NOT enforce a hard model limit. The existing TokenEstimator may remain only as a packing heuristic below the hard budget.

## 17. Identifier subsystem

IdentifierNormalizer becomes IdentifierCanonicalizer with per-IdentifierType rules.

Distinct values such as AB-12 and AB/12 MUST remain distinct unless that type’s documented grammar declares them equivalent.

Business parsers MUST require an unambiguous identifier signal:
- explicit №/No/number marker; or
- an independently validated identifier shape containing the required digit/separator structure.

Ordinary phrases such as contract termination, order status and case management MUST produce no identifier.

Enforce raw/canonical length <= 500 before persistence.

For this remediation, introduce IdentifierCapabilityRegistry and advertise/support only CONTRACT_NUMBER, ORDER_NUMBER, INVOICE_NUMBER, APPLICATION_NUMBER, CASE_NUMBER and DOCUMENT_NUMBER. Legacy enum constants CLAIM_NUMBER, PAYMENT_NUMBER, PROTOCOL_NUMBER, LETTER_NUMBER and DOCUMENT_ID may remain in the persistence enum solely to read historical rows, but they MUST NOT be returned by supportedTypes(), instantiated by production parsers or advertised in README/API capability docs. Reintroduction requires a separate grammar specification and tests.

Prefix/partial LIKE search MUST escape wildcard semantics where literal behavior is intended.

## 18. Cross-reference subsystem

Replace free-form regex-only references with language-specific parsers that emit typed CrossReference.

Support at least:
- RU: статья 25, ст. 25, пункт 3, п. 3;
- EN: article 25, Art. 25, section 3, clause 3;
- KK: 25-бап, 1-тармақ and supported word-before-number variants;
- ZH: 第25条, 第二十五条 and supported paragraph forms.

Structural declarations create StructuralAnchor targets. Mentions create CrossReference edges.

Resolution scope is mandatory: SAME_DOCUMENT references query targets only for source documentId + published generation. EXPLICIT_DOCUMENT references first resolve their explicit target document identity and then query that document's published target. A bare "Article 25" MUST NOT search Article 25 globally across every document.

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

Retention is entirely PostgreSQL-backed after this redesign; vector deletion is no longer an external VectorStore call.

Claiming:
- acquire worker capacity before claiming;
- claim only retention_status ACTIVE/DELETE_FAILED rows whose TTL expired, published_generation IS NOT NULL and no STAGING generation exists;
- use one transaction with FOR UPDATE SKIP LOCKED;
- set DELETE_PENDING, claim_generation=published_generation, claim_id, claimed_by, claimed_at and lease_until using PostgreSQL clock time.

Cleanup:
- one cleanup transaction SELECTs the lifecycle row FOR UPDATE;
- require matching claim_id, claim_generation, expected state and unexpired lease using DB time;
- transition DELETE_PENDING -> DELETING inside the transaction;
- resolve the claimed generation's embedding profile/vector table and verified manifest;
- delete exactly those vector UUIDs from that profile table;
- delete reference edges/targets, identifiers, projections and manifest for exactly claim_generation;
- change generation PUBLISHED -> RETIRED -> CLEANED;
- clear lifecycle.published_generation;
- set retention_status=DELETED, deleted_at=DB time and clear claim fields;
- commit atomically.

If any SQL/vector delete fails, the transaction rolls back. A separate markFailed transaction may change DELETE_PENDING/DELETING to DELETE_FAILED and increment attempt_count only while the same claim token is still unexpired. If the lease expired, markFailed is a no-op.

Because cleanup is one database transaction, a worker cannot leave “vectors deleted but relational state still committed” or vice versa. The cleanup transaction timeout MUST be strictly less than leaseDuration/2.

Re-ingestion races are serialized by the lifecycle row lock/fences described in section 6; retention does not use DocumentOperationLock.

## 21. Retention scheduling without queued leases or heartbeat

The heartbeat subsystem is removed.

Rationale: after vector storage becomes PostgreSQL-transactional, a claimed cleanup consists only of one bounded database transaction. Claims are created only when a worker slot already exists, so no claimed job waits in an executor queue. A lease comfortably larger than the cleanup transaction timeout is sufficient for crash recovery.

Required RetentionWorkerPool behavior:
1. acquire one local worker semaphore permit;
2. atomically claim at most the number of permits acquired;
3. submit each claim immediately to a fixed worker executor with no unbounded queue;
4. if submission is rejected, release the database claim immediately and release the permit;
5. execute the bounded cleanup transaction;
6. release the permit in finally.

Remove startHeartbeat, heartbeatExecutor, lostLeases and renewal scheduling from the normal retention design. retain renewLease only if another future maintenance operation genuinely requires a long lease; retention cleanup does not call it.

Shutdown:
1. stop scheduler/claiming;
2. stop accepting submissions;
3. await active cleanup transactions for a bounded duration;
4. interrupt/cancel remaining workers;
5. release local permits; DB leases recover crashed work naturally.

This structural removal is the remediation for D09 and D60. D40 is closed by permit-before-claim/no-claimed-queue semantics.

Stale-ingestion recovery uses an atomic PostgreSQL UPDATE ... FROM (SELECT ... FOR UPDATE SKIP LOCKED LIMIT :batch) over STAGING generation rows to mark them FAILED with cleanup_required=true. It repeats bounded batches and requires no document advisory lock.

## 22. Per-document concurrency without long-lived advisory locks

Remove DocumentOperationLock from ingestion, retention and stale recovery.

Same-document concurrency is controlled by:
- monotonic generation allocation under lifecycle row lock;
- conditional publication under lifecycle row lock;
- “newer published generation wins” comparison;
- generation-scoped relational/vector keys;
- retention claim_generation/claim_id fences.

No JDBC connection is held across chunking, enrichment, embedding or other network calls.

Delete the dedicated session-advisory lock design from the remediation target. A short transaction-scoped advisory lock MAY be used only for global schema/profile registration where no network work occurs, but it is not part of document correctness.

D28 verification uses a deliberately tiny main Hikari pool and concurrent same/different-document ingestions plus retention. Operations must make progress without requiring a second connection per document lock because no session lock connection exists.

## 23. Embedding lifecycle and corpus-level re-embedding

EmbeddingProfileResolver fingerprints provider/model/dimensions/distance/tokenizer configuration. Each profile owns one profile-scoped vector table from section 8.

knowledge_embedding_runtime is the corpus-level authority:
- active_profile_id;
- migration_profile_id nullable;
- migration_status IDLE|PREPARING|STAGING|READY_TO_CUTOVER;
- row_version;
- updated_at.

Normal ingestion may allocate only while migration_status=IDLE and always captures active_profile_id.

Startup:
- if no runtime row and no published data exist, register the configured profile, create its vector table and set it active;
- configured serving profile must equal active_profile_id for readiness UP;
- legacy-unknown or profile mismatch keeps readiness DOWN.

ReembeddingService performs one corpus cutover:
1. atomically IDLE -> PREPARING, which blocks new ingestion and new retention claims but permits already-allocated STAGING generations to finish/fail;
2. wait a bounded period until no normal STAGING generations and no active retention claims remain; otherwise abort migration and return to IDLE;
3. register target profile Y and its vector table, then PREPARING -> STAGING;
4. snapshot every ACTIVE document and its current published generation;
5. for each snapshot document, create a candidate generation under Y, compute Y embeddings, and in a per-document staging transaction insert candidate projections/identifiers/reference graph/manifest/Y vector rows while leaving candidate generation STAGING and leaving lifecycle.published_generation unchanged;
6. any candidate failure aborts cutover: active_profile_id stays X, all old pointers stay unchanged, Y candidates are cleaned, migration returns IDLE; readiness requires serving config X or a successful retry;
7. after every snapshot candidate is verified, set READY_TO_CUTOVER;
8. one PostgreSQL cutover transaction locks knowledge_embedding_runtime, verifies the snapshot is still valid, changes each old X generation PUBLISHED -> RETIRED, each Y candidate STAGING -> PUBLISHED, each lifecycle.published_generation to its Y candidate, then active_profile_id=Y and migration_status=IDLE;
9. resume retention, ingestion and RAG; asynchronously clean retired X generations/table rows.

A dimension or distance-type change is safe because X and Y use separate fixed-dimension vector tables. Mixed embedding spaces are never searched together: PublishedVectorSearchRepository reads only active_profile_id's table.

A target profile table may be dropped only after no generation journal row references it in PUBLISHED/STAGING/RETIRED state and legacy/reconciliation policy permits removal.

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
- use X-AKMAI-API-Key with a single externally supplied secret for the current deployment; compare in constant time and never log the secret;
- explicit local-only development profile may permit unauthenticated access only when intentionally enabled;
- production startup fails closed when required credentials are absent.

### 24.4 Idempotency

Accept Idempotency-Key for ingestion.

Canonical request fingerprint includes normalized documentId/title/text/source/language/domain/metadata and any retention inputs.

Canonical fingerprint uses deterministic canonical JSON: recursively sorted object keys, normalized Unicode strings, canonical language/domain values, and stable JSON number representation before SHA-256.

Same key/same fingerprint and SUCCEEDED returns the stored KnowledgeIngestionResponse and does not create a new generation or refresh TTL.
Same key/same fingerprint and unexpired IN_PROGRESS returns 409 INGESTION_IN_PROGRESS with Retry-After.
Same key/same fingerprint and expired IN_PROGRESS is reclaimed using DB time and follows the generation-inspection rules in section 3.7.
Same key/different fingerprint -> 409 IDEMPOTENCY_KEY_REUSE.

## 25. Readiness and observability

Add readiness contributors for:
- PostgreSQL;
- active embedding profile compatibility;
- Ollama embedding capability/model availability;
- Ollama chat capability/model availability;
- PgVector schema/vector compatibility.

Liveness MUST remain process-local and MUST NOT fail only because Ollama is temporarily unavailable.

Add bounded-cardinality Micrometer metrics for:
- ingestion duration/chunks/failures/publication rollback/stale-generation recovery;
- vector add/delete/reconciliation latency;
- generation state counts;
- retrieval per-strategy latency/hits/status;
- reranker timeout/failure;
- reference query/candidate counts;
- context serialized tokens/chunks;
- answer generation latency/failure;
- citation validation failures;
- retention claims/deletes/failures/stale claims/lease loss/backlog/run duration.

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
- production PostgresGenerationVectorRepository + PublishedVectorSearchRepository with deterministic test EmbeddingModel;
- ResultFusion;
- Reranker;
- KnowledgeExpansion;
- ContextBudget.

Compute actual ranked chunk IDs and per-language Recall@5/10, MRR and nDCG.

A mutation that reverses fusion ranking or makes vector retrieval empty MUST fail the production quality gate.

### 26.3 Production pgvector E2E

A Testcontainers PostgreSQL image with pgvector MUST exercise the actual production vector repositories.

Test:
- profile table creation for at least two different dimensions;
- deterministic embedding generation;
- atomic publication of vector + relational state;
- published-generation vector visibility;
- document scope;
- distance/threshold semantics;
- transaction rollback on a deliberately failing later vector batch;
- exact UUID deletion in retention/retired-generation cleanup;
- legacy vector reconciliation when manifest/profile identity is missing.

Mock vector tests may remain unit tests but cannot verify D01/D02/D19/D32/D58/D71.

## 27. Defect-by-defect implementation and test contract

## 27. Defect-by-defect implementation and test contract

The following mapping is mandatory. A defect may share implementation with other defects, but it MUST have an explicit regression assertion.

### D01 — orphan vectors after partial add
Code: remove opaque vectorStore.add mutation path. Compute embeddings first, then insert vector rows + manifest + all relational generation state inside the single publication PostgreSQL transaction. A failed/partial batch rolls back entirely.  
Tests: replace the old partial-add mock oracle with a real pgvector transaction fault test proving a later-batch failure leaves zero vector/manifest/projection rows and the previous published generation intact.

### D02 — published-generation retrieval visibility
Code: every vector/lexical/identifier/reference read is constrained to lifecycle.published_generation, ACTIVE retention state and corpus active_profile_id for vectors.  
Tests: failed/staging generation is invisible in all strategies; successful publication switches all modalities together.

### D03 — destructive re-ingestion
Code: generation publication transaction retires the previous generation only in the same commit that publishes the new one; retired cleanup runs only after commit.  
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
Code: remove the heartbeat subsystem; permit-before-claim and one bounded DB cleanup transaction make heartbeat startup/shutdown unnecessary.  
Tests: shutdown with active cleanup leaks no local permit or database claim; expired claims remain reclaimable after process loss.

### D10 — prompt injection from retrieved text
Code: structured JSON context + explicit untrusted-data system rule.  
Tests: malicious chunk instructions cannot alter source framing and are passed as data.

### D11 — uncited grounded answer accepted
Code: every nonblank model-generated answer produced from nonempty context requires >=1 valid citation; the only citation-free insufficient-information text is generated deterministically by the service.  
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
Code: canonical KnowledgeLanguage at API write boundary using the exact alias map in section 16.2.  
Tests: RU/russian/Ru normalize to ru; unsupported aliases reject; storage uses canonical code only.

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
Code: all profile vector repositories/manifests call the UUID-compatible VectorIdentity v2 algorithm; delete every duplicate/local ID algorithm.  
Tests: persistence manifest/vector document/retention use exactly the same ID.

### D20 — relational state not generation-scoped
Code: generation column and published-only repositories for projections/identifiers/reference graph.  
Tests: failed N+1 leaves N relational results unchanged; successful publish switches all.

### D21 — citation integer overflow
Code: guarded/bounded parse; oversized markers become invalid.  
Tests: extremely large SOURCE number never throws.

### D22 — stale retention destructive tail
Code: vector and relational deletion are one PostgreSQL cleanup transaction after a single row-lock/fence validation; there is no external destructive tail.  
Tests: expire/reclaim the claim before cleanup transaction starts and prove stale cleanup changes zero vector/relational/lifecycle rows.

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
Code: remove long-lived document advisory locks entirely; use generation/lifecycle row transactions and CAS publication.  
Tests: tiny main pool + concurrent same/different-document ingestion/retention completes without connection starvation.

### D29 — no final LLM deadline
Code: AnswerGenerationService with application + transport deadline.  
Tests: blocked chat call returns stable timeout/degraded error within bound.

### D30 — null metadata crashes RetrievalHit
Code: metadata validator/canonicalizer; core provenance typed; RetrievalHit receives null-free immutable map.  
Tests: null-containing request rejected/sanitized deterministically; retrieval never throws Map.copyOf NPE.

### D31 — lifecycle cannot represent published N + staging N+1
Code: published_generation pointer + knowledge_document_generation journal; multiple STAGING generations may coexist while exactly one generation is PUBLISHED.  
Tests: state-machine test explicitly observes N PUBLISHED while N+1 STAGING/FAILED.

### D32 — no embedding profile lifecycle
Code: persisted EmbeddingProfile + profile-scoped vector tables + corpus active_profile_id + corpus-level cutover ReembeddingService.  
Tests: same-dimension and different-dimension migrations stage in separate profile tables; any failure preserves X globally; successful cutover switches every snapshot document and active_profile_id atomically.

### D33 — destructive/schema-drift migration
Code: preservation migration, additive upgrade path, Liquibase-only runtime schema.  
Tests: seed legacy schema/data, run full changelog, verify rows and constraints survive.

### D34 — advertised identifier types missing parsers
Code: add IdentifierCapabilityRegistry; advertise only the six parser-backed types. Keep unsupported enum constants legacy-only if required for persisted-row compatibility.  
Tests: parameterized capability test guarantees each advertised type has WRITE and READ parser coverage and every legacy-only type is absent from supportedTypes().

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
Code: qualified vectorWriteEmbeddingModel has hard HTTP transport timeout; no DB transaction/lock is held while Ollama runs. Embedding failure marks STAGING generation FAILED.  
Tests: never-returning embedding fails within bound, leaves no retrieval rows and does not consume a database lock/connection after timeout.

### D40 — queued claim lease/retry consumption
Code: fixed worker permits are acquired before DB claim and claimed work is submitted immediately with no claimed queue; retry count changes only after an executed cleanup failure.  
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
Code: atomically mark stale STAGING generation rows FAILED/cleanup_required via PostgreSQL FOR UPDATE SKIP LOCKED batched update; reconciliation performs external cleanup.  
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
Code: use the deterministic ranked-hit/nearest-neighbor interleave algorithm and context-expansion-max-chunks quota from section 13.  
Tests: useful neighbor survives even when original ranked list exceeds contextMaxChunks.

### D54 — numbered medical facts become headings
Code: structural role separated from semantic type; medical classifier still runs.  
Tests: numbered dosage/contraindication/monitoring lists remain atomic typed facts in KK/RU/EN/ZH.

### D55 — query intents silently truncated
Code: QueryDecompositionResult always keeps the full normalized original question as retrieval unit 0, then adds at most MAX_SEGMENTS-1 unique decomposed units; overflowCount records omitted decomposed units. The full original is the catch-all for every omitted intent.  
Tests: 9+ intents keep the original catch-all, at most seven decomposed units and a non-zero overflowCount; no overflow is silent.

### D56 — final chunk exceeds hard max
Code: unconditional hard check before adding a unit and final invariant assertion.  
Tests: sub-min first unit + near-hard second never emits >hardMax.

### D57 — embedding payload exceeds budget
Code: budget exact EmbeddingTextBuilder output with embedding profile estimator.  
Tests: long title/deep section still respects model limit.

### D58 — no production pgvector E2E
Code/test infrastructure: real pgvector Testcontainers gate for PostgresGenerationVectorRepository, PublishedVectorSearchRepository and profile-table schema with deterministic EmbeddingModel.  
Tests: profile creation, atomic insert/publication, search/filter/delete/rollback/reconciliation use the actual production repositories.

### D59 — LIKE wildcard semantics
Code: literal escaping and ESCAPE clause for KK/ZH fallback; minimum/selectivity rule.  
Tests: %, _, 5% mean literals and cannot match-all.

### D60 — single heartbeat thread failure domain
Code: remove heartbeat renewal from retention; bounded immediate DB cleanup completes well inside lease.  
Tests: retention has no heartbeat executor; multiple active cleanup transactions are independent and a blocked/timed-out transaction cannot expire another queued claim because claims are created only for available workers.

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
Code: cleanup/markFailed transactions require claim token + generation + unexpired DB-time lease; stale failure transition is a no-op.  
Tests: expiry immediately before cleanup exception prevents state/retry mutation.

### D67 — NaN/Infinity reranker scores
Code: finite/shape checks before cosine and before sorting.  
Tests: NaN/+Inf/-Inf/dimension mismatch cause safe fallback, never rank promotion.

### D68 — relational batch partial commit
Code: one publication transaction contains every relational batch plus vector rows and pointer switch.  
Tests: deliberate second-batch failure (>100 rows) rolls back the entire generation and leaves prior publication unchanged.

### D69 — nDCG duplicate inflation
Code: unique relevance gain; range invariant.  
Tests: relevant {A}, ranking [A,A] never produces >1; generated duplicate/property tests.

### D70 — hard-coded quality regression is falsely green
Code/test: production pipeline benchmark generates final rankings; fixtures only validate metric math.  
Tests: intentional ranking mutation causes gate failure.

### D71 — missing manifest guesses wrong physical vector IDs
Code: new generations always commit manifest and profile vector rows atomically. Legacy reconciliation queries the declared legacy profile/table by reserved/legacy metadata and never guesses chunk_id as a physical UUID.  
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
- ConcurrentGenerationPublicationIntegrationTest
- IdempotencyIntegrationTest

PgVector production-adapter:
- PostgresVectorPublicationIntegrationTest
- PostgresVectorRollbackIntegrationTest
- PostgresVectorReconciliationIntegrationTest
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
- permit-before-claim/no-claimed-queue invariant;
- cleanup transaction timeout/lease expiry;
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
akmai.security.api-key — external secret only; consumed as X-AKMAI-API-Key  
akmai.vector.embedding-http-timeout  
akmai.vector.db-transaction-timeout  
akmai.retrieval.embedding-http-timeout  
akmai.retention.cleanup-transaction-timeout  
akmai.retrieval.context-expansion-max-chunks  
akmai.idempotency.lease-duration  
embedding profile/token budget settings needed by active model

Keep secrets out of committed application.yml values. Defaults must be safe for local development and explicit for production.

## 31. Error and recovery semantics

The following outcomes MUST be deterministic.

- Failed N+1 ingestion with published N -> N stays published; N+1 FAILED; cleanup is retryable.
- Crash after generation allocation or after embedding but before publication transaction -> STAGING generation has no retrieval rows and is recoverable.
- Failure/crash during the publication DB transaction -> PostgreSQL rolls back vector + manifest + relational rows + pointer together; ambiguous commit outcomes are resolved by re-reading authoritative state.
- Embedding timeout occurs before any publication DB mutation and therefore requires no vector compensation.
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
5. Production profile-scoped pgvector repository E2E gate passes.
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
