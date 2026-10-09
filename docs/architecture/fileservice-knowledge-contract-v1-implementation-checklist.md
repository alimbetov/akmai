# FileService → AkmAI Knowledge Contract v1 — Implementation Checklist

**Status:** TARGET  
**Branch:** `feature/fileservice-knowledge-contract-v1`  
**Parent specification:** [`fileservice-knowledge-contract-v1.md`](fileservice-knowledge-contract-v1.md)  
**Purpose:** translate the target contract into an implementation plan tied to the current AkmAI codebase, database schema, transaction boundaries and tests.

---

## 1. Baseline and sequencing

This checklist is intentionally implementation-oriented. It does not replace the parent architecture specification.

Before production code is changed:

- [ ] synchronize this branch with the current `main` baseline;
- [ ] preserve the existing generation/idempotency/publication pipeline;
- [ ] do not create a second ingestion pipeline for FileService;
- [ ] keep the existing HTTP text-ingestion contract backward compatible;
- [ ] introduce richer internal contracts first, then expose additive/versioned transport contracts;
- [ ] keep publication authority inside the existing generation transaction;
- [ ] add tests before or together with every migration step.

Current branch caveat at the time of this checklist: the branch was created before the latest `main` smoke-test merge and must be synchronized before implementation work starts.

---

## 2. Current-state gap summary

| Area | Current implementation | Required target change | Priority |
|---|---|---|---|
| Canonical contract version | `CanonicalDocument` has no `schemaVersion` | explicit schema version and version validation | P0 |
| Source identity | `CanonicalDocument.source` is a `String` | typed `CanonicalSource` with file/source identity | P0 |
| Storage reference | absent | typed provider/bucket/objectKey/versionId; no durable presigned URL | P0 |
| Source processing lineage | only generic metadata | typed parser/parserVersion/parsedAt | P0 |
| Section path | scalar `String` | typed ordered path with compatibility adapter | P1 |
| Canonical content identity | request fingerprint exists, canonical hash absent | deterministic `canonicalHash` | P0 |
| Processing identity | projection fingerprint only | explicit `processingFingerprint` | P1 |
| Ingestion result | `KnowledgeIngestionResponse(documentId, chunkCount)` | internal `KnowledgeIngestionResult` with publication/generation/lineage | P0 |
| Replay serialization | `response_json` deserializes old response directly | versioned replay payload / compatibility mapper | P0 |
| Event identity | no ingestion event identity | transport-neutral admission identity + inbox dedup | P1 |
| Publication event | none | transactionally persisted `KnowledgeDocumentPublished` outbox | P1 |
| Retrieval provenance | provenance primarily in metadata | typed `SourceProvenance` on `RetrievalHit` | P0/P1 |
| Block lineage | available in semantic provenance but not a stable retrieval contract | persist/expose block IDs/pages/path/content identity | P0 |
| Database source identity | no dedicated durable source columns/table | generation/document source provenance storage | P0 |
| Security | generic metadata can carry arbitrary source fields | whitelist durable provenance; exclude storage credentials | P0 |

---

## 3. P0 — Canonical inbound contract

### 3.1 `CanonicalDocument`

**File:** `src/main/java/kz/alimbetov/akmai/knowledge/api/CanonicalDocument.java`

- [ ] rename or supersede with `CanonicalKnowledgeDocument` without breaking current callers during migration;
- [ ] add `int schemaVersion` and support v1 only initially;
- [ ] replace `String source` with a typed source object;
- [ ] add typed processing metadata;
- [ ] preserve `documentId`, knowledge-domain, language and ACL semantics;
- [ ] keep blocks independent from final RAG chunks;
- [ ] validate duplicate block IDs before idempotency claim/generation allocation/embedding;
- [ ] validate `version` and `source.sourceVersion` coherence according to the parent specification;
- [ ] reject malformed hashes, page ranges, bounding boxes and storage references;
- [ ] reject unsupported schema versions with a stable contract error code.

Suggested new value types under `knowledge/api` or a dedicated `knowledge/contract` package:

- [ ] `CanonicalKnowledgeDocument`;
- [ ] `CanonicalSource`;
- [ ] `FileSourceIdentity`;
- [ ] `StorageReference`;
- [ ] `CanonicalProcessingInfo`;
- [ ] `CanonicalBlock` / existing nested block migrated additively;
- [ ] `SectionPath` immutable value object.

### 3.2 File source model

Minimum file-source fields:

- [ ] `type=FILE`;
- [ ] `fileId`;
- [ ] `sourceVersion`;
- [ ] `fileName`;
- [ ] `mediaType`;
- [ ] `contentHash`;
- [ ] optional `StorageReference`.

Validation invariants:

- [ ] `fileId` and `sourceVersion` non-blank;
- [ ] `contentHash` algorithm explicitly encoded, initially `sha256:`;
- [ ] storage provider/bucket/objectKey must be stable identifiers, never credentials;
- [ ] presigned URL must not be accepted as source identity;
- [ ] storage credentials, secrets or authorization headers must never enter metadata or retrieval output.

---

## 4. P0 — Canonical validation and deterministic hashing

### 4.1 New validator

Introduce an explicit component rather than relying only on record constructors.

Suggested file:

`src/main/java/kz/alimbetov/akmai/knowledge/service/CanonicalKnowledgeDocumentValidator.java`

Responsibilities:

- [ ] schema-version validation;
- [ ] source identity validation;
- [ ] language/domain/ACL validation;
- [ ] duplicate block-ID detection;
- [ ] block-type validation;
- [ ] page/bounding-box validation;
- [ ] storage-reference validation;
- [ ] metadata key/value safety limits;
- [ ] bounded document/block sizes where appropriate.

Execution order:

```text
transport decode
→ canonical validation
→ canonical hash
→ admission/idempotency
→ canonical mapping
→ chunking
→ enrichment
→ embedding
→ publication
```

Validation must happen before expensive processing and before generation allocation.

### 4.2 `CanonicalHashService`

Suggested file:

`src/main/java/kz/alimbetov/akmai/knowledge/idempotency/CanonicalHashService.java`

- [ ] deterministic canonical serialization;
- [ ] SHA-256 output with explicit prefix/version;
- [ ] exclude `parsedAt`, tracing IDs, temporary URLs and other volatile fields;
- [ ] preserve block order;
- [ ] include source content identity and semantic canonical content;
- [ ] add golden-vector tests to detect accidental hash-contract changes.

Do not reuse the current projection fingerprint in `PersistenceCoordinator` as canonical identity. That fingerprint is calculated after chunking and represents a different layer.

---

## 5. P0 — `CanonicalDocumentMapper` and provenance preservation

**File:** `src/main/java/kz/alimbetov/akmai/knowledge/service/CanonicalDocumentMapper.java`

Current mapper already preserves block IDs/pages/bounding boxes into `UnitProvenance`, which should be retained.

Required changes:

- [ ] accept `CanonicalKnowledgeDocument` / compatibility adapter;
- [ ] remove generic `metadata.put("source", String)` as the durable source contract;
- [ ] map typed source identity into explicit internal provenance;
- [ ] keep parser/source lineage separate from semantic enrichment metadata;
- [ ] migrate `sectionPath` toward ordered `SectionPath` while keeping the current string rendering available for lexical search;
- [ ] preserve all block IDs that contribute to a semantic unit/chunk;
- [ ] define deterministic merge rules when one chunk spans multiple canonical blocks/pages;
- [ ] carry canonical schema version/content hash/source version into internal provenance.

Positive test cases:

- [ ] heading hierarchy preserved;
- [ ] paragraph/table/list/code provenance preserved;
- [ ] multi-block chunk produces ordered distinct block IDs;
- [ ] page range expands correctly across merged blocks;
- [ ] explicit section path overrides derived path according to contract.

Negative cases:

- [ ] duplicate block IDs rejected before mapper execution;
- [ ] invalid block geometry rejected;
- [ ] malformed source identity rejected;
- [ ] unsupported schema version rejected.

Existing tests to extend:

- `CanonicalDocumentChunkingTest`;
- `KnowledgeCanonicalIngestionServiceTest`;
- `KnowledgeCanonicalIngestionFailureModelTest`.

---

## 6. P0 — Internal ingestion result without breaking old API

### 6.1 New result model

Suggested file:

`src/main/java/kz/alimbetov/akmai/knowledge/api/KnowledgeIngestionResult.java`

Required structure:

- [ ] `schemaVersion`;
- [ ] `documentId`;
- [ ] typed source summary;
- [ ] publication status;
- [ ] published generation;
- [ ] searchable chunk count;
- [ ] canonical schema version;
- [ ] semantic normalizer version;
- [ ] chunker version;
- [ ] embedding profile ID/version;
- [ ] replay marker/status where applicable.

Publication enum should distinguish at minimum:

- `PUBLISHED`;
- `REPLAYED`;
- `SUPERSEDED` when exposed by the selected API;
- no false-success representation for in-progress/unknown outcomes.

### 6.2 Compatibility mapper

Keep:

`src/main/java/kz/alimbetov/akmai/knowledge/api/KnowledgeIngestionResponse.java`

for the existing HTTP contract until an explicit version change.

Introduce:

- [ ] internal result → legacy response mapper;
- [ ] internal result → FileService/v1 response mapper;
- [ ] tests proving old JSON remains `{documentId, chunkCount}`.

---

## 7. P0 — `KnowledgeIngestionService` refactor

**File:** `src/main/java/kz/alimbetov/akmai/knowledge/service/KnowledgeIngestionService.java`

The service must remain the single orchestration path.

Refactor target:

```text
legacy AddKnowledgeRequest ─┐
                            ├→ normalized ingestion command
CanonicalKnowledgeDocument ─┘
       → validation/admission
       → mapping/chunking
       → enrichment
       → PersistenceCoordinator
       → KnowledgeIngestionResult
       → transport-specific compatibility mapping
```

Checklist:

- [ ] introduce a transport-neutral `KnowledgeIngestionCommand` / admission context;
- [ ] represent request identity and optional event identity separately;
- [ ] include source/content identity in the command;
- [ ] do not duplicate `persistChunks` logic for FileService;
- [ ] move rich-result construction after durable publication evidence is known;
- [ ] replay path must return a semantically equivalent rich result internally;
- [ ] legacy endpoint continues returning `KnowledgeIngestionResponse`.

Important correctness change: current code builds `KnowledgeIngestionResponse` before `PersistenceCoordinator.persist()`. The richer result must be created from publication outcome/generation evidence, not predicted before publication.

---

## 8. P0 — `PersistenceCoordinator` return contract and fingerprints

**File:** `src/main/java/kz/alimbetov/akmai/knowledge/ingestion/PersistenceCoordinator.java`

Required changes:

- [ ] change `persist(...)` from `void` to a typed persistence/publication result;
- [ ] return allocated/published generation and final publication state;
- [ ] return active embedding profile identity used for the generation;
- [ ] keep failure/unknown-outcome semantics unchanged;
- [ ] retain lease heartbeat and generation fencing;
- [ ] keep searchable-chunk vs parent-projection counts explicitly distinct;
- [ ] rename current projection fingerprint to reflect its actual meaning, e.g. `projectionContentFingerprint`;
- [ ] accept/store canonical hash and processing fingerprint separately;
- [ ] do not derive source content identity from post-chunk projections.

Suggested result:

`PersistenceResult(documentId, generation, publicationStatus, searchableChunkCount, embeddingProfileId, projectionFingerprint)`.

---

## 9. P0 — Idempotency replay compatibility

**Files:**

- `src/main/java/kz/alimbetov/akmai/knowledge/idempotency/IngestionIdempotencyRepository.java`;
- `src/main/resources/db/changelog/greenfield/005-operational.sql` plus additive migration.

Current `knowledge_ingestion_request.response_json` stores `KnowledgeIngestionResponse` directly. This must not be silently changed in a way that makes existing succeeded rows unreadable.

Plan:

- [ ] introduce a versioned persisted replay envelope, e.g. `IngestionReplayPayload`;
- [ ] reader supports legacy response JSON and v1 rich payload during transition;
- [ ] writer emits only the new version after migration;
- [ ] recovery of expired claims from an already published generation reconstructs a full internal result using generation/source lineage;
- [ ] legacy API maps reconstructed result back to old response shape;
- [ ] test old persisted JSON replay after upgrade;
- [ ] test same key/same request replay;
- [ ] test same key/different fingerprint conflict;
- [ ] test crash after generation publication but before caller receives response;
- [ ] test expired claim reclaim with published generation;
- [ ] test expired claim reclaim with failed/staging generation.

No event ID should be overloaded into the `idempotency_key` column.

---

## 10. P0 — Durable source provenance schema

The source identity should not exist only inside arbitrary `metadata_json`.

Preferred schema direction: introduce a document-generation source provenance table rather than duplicating all source columns across every chunk row.

Suggested additive migration:

`src/main/resources/db/changelog/greenfield/0xx-fileservice-knowledge-contract.sql`

Suggested table:

```text
knowledge_document_source
  document_id
  generation
  access_level
  source_type
  file_id
  source_version
  file_name
  media_type
  content_hash
  storage_provider
  storage_bucket
  storage_object_key
  storage_version_id
  canonical_schema_version
  canonical_hash
  parser_name
  parser_version
  parsed_at
  semantic_normalizer_version
  chunker_version
  processing_fingerprint
  created_at
```

Constraints/indexes:

- [ ] FK to `(document_id, generation, access_level)` generation authority;
- [ ] one source provenance row per generation initially;
- [ ] `access_level > 0`;
- [ ] content/canonical hash shape constraints where practical;
- [ ] index `(file_id, source_version)`;
- [ ] index `content_hash` only if operational queries justify it;
- [ ] no presigned URL or credentials columns;
- [ ] purge/retention cascade behavior explicitly tested.

Do not place source credentials in `knowledge_search_projection.metadata_json` or vector metadata.

---

## 11. P0/P1 — Chunk provenance persistence

**Current table:** `knowledge_search_projection` already stores `metadata_json` and scalar `section_path`.

Required stable retrieval lineage:

- block IDs;
- page range;
- typed/ordered section path;
- source generation link through `document_id + generation + access_level`.

Implementation choices to evaluate before coding:

### Preferred v1

- [ ] keep generation-level source fields in `knowledge_document_source`;
- [ ] persist chunk-level canonical block IDs/page range in explicit columns or a dedicated chunk-provenance table;
- [ ] keep `metadata_json` only as an extensibility surface.

Suggested dedicated table if explicit columns would overly widen partitioned projection storage:

`knowledge_chunk_source_provenance(access_level, document_id, generation, chunk_id, block_ids_json, page_from, page_to, section_path_json)`.

Selection criterion:

- projection hot-path latency;
- retrieval join cost;
- partition maintenance complexity;
- retention cleanup complexity;
- ability to reconstruct provenance in one bounded query.

A benchmark/regression test must decide between extra projection columns and a companion table; do not choose based only on DTO convenience.

---

## 12. P0/P1 — Typed `SourceProvenance` on retrieval

**File:** `src/main/java/kz/alimbetov/akmai/rag/retrieval/RetrievalHit.java`

Introduce:

`src/main/java/kz/alimbetov/akmai/rag/retrieval/SourceProvenance.java`

Minimum fields:

- [ ] `fileId`;
- [ ] `sourceVersion`;
- [ ] `fileName`;
- [ ] `contentHash`;
- [ ] ordered `blockIds`;
- [ ] `pageFrom` / `pageTo`;
- [ ] typed/ordered section path;
- [ ] optional stable source reference identifier, but never storage credentials.

Migration strategy:

- [ ] add provenance to `RetrievalHit` while retaining generic metadata;
- [ ] update all retrieval lane constructors/mappers;
- [ ] update fusion/reranker/graph/parent expansion copy constructors so provenance cannot be dropped;
- [ ] add equality/canonical-key tests where retrieval hits are rebuilt;
- [ ] update citation/context serialization only where provenance is intended to be exposed externally.

Invariant test:

```text
RetrievalHit
→ chunkId
→ blockIds
→ documentId + generation
→ knowledge_document_source
→ fileId + sourceVersion + contentHash
```

---

## 13. P1 — Processing fingerprint

Introduce a dedicated component after version constants become explicit.

Suggested file:

`src/main/java/kz/alimbetov/akmai/knowledge/ingestion/ProcessingFingerprintService.java`

Inputs:

- canonical hash;
- canonical schema version;
- semantic normalizer version;
- chunker version;
- embedding profile ID/version.

Rules:

- [ ] deterministic SHA-256;
- [ ] never include timestamps, generation numbers or temporary transport data;
- [ ] different embedding profile changes fingerprint;
- [ ] different chunker version changes fingerprint;
- [ ] golden-vector tests;
- [ ] no automatic skip/reuse behavior until policy is explicitly implemented and tested.

Initially persist the value for audit/reprocessing decisions; do not prematurely optimize by skipping ingestion based only on it.

---

## 14. P1 — Inbox/event identity

Do not put broker-specific code into `KnowledgeIngestionService`.

Introduce transport-neutral admission identity:

- request ID/idempotency key;
- optional event ID;
- document ID;
- file/source ID;
- content hash;
- request fingerprint.

Suggested components:

- `IngestionAdmissionContext`;
- `IngestionEventRepository` or `IngestionInboxRepository`;
- broker adapter added later outside the domain service.

Suggested table:

`knowledge_ingestion_event`

Fields:

- `event_id` PK;
- `event_type`;
- `document_id`;
- `file_id`;
- `source_version`;
- `content_hash`;
- `event_status`;
- optional linked `idempotency_key` / generation;
- timestamps/error diagnostics.

Concurrency requirements:

- [ ] two pods receiving the same event cannot both perform ingestion side effects;
- [ ] duplicate delivered event replays/acknowledges deterministically;
- [ ] event identity and HTTP idempotency remain independent;
- [ ] DB time/locking semantics are explicit;
- [ ] failure injection around claim ownership.

---

## 15. P1 — Publication Outbox

**Transactional insertion point:** `GenerationPublicationService.publishInTransaction(...)`.

The outbox record must be created in the same publication transaction that:

- writes projections/identifiers/references/vectors;
- marks generation `PUBLISHED`;
- moves lifecycle pointer;
- completes idempotency state.

Suggested table:

`knowledge_outbox_event`

Fields:

- `event_id` UUID/ULID-compatible textual identity;
- `event_type`;
- aggregate/document ID;
- generation;
- payload version;
- payload JSONB;
- status;
- attempt count;
- available/claimed/published timestamps;
- claim owner/lease for multi-pod dispatcher;
- last error.

Required unique logical publication identity:

`(event_type, document_id, generation)`.

Tests:

- [ ] publication commit always has matching outbox row;
- [ ] transaction rollback has neither publication nor outbox row;
- [ ] retry does not create logical duplicate publication events;
- [ ] crash after commit before broker send leaves dispatchable outbox row;
- [ ] duplicate broker send remains safe through consumer inbox semantics;
- [ ] multi-pod outbox claim/lease fencing.

Do not send to a broker inside the publication DB transaction.

---

## 16. P1 — `KnowledgeDocumentPublished`

Suggested file:

`src/main/java/kz/alimbetov/akmai/knowledge/event/KnowledgeDocumentPublished.java`

Required fields:

- schema version;
- event ID;
- event type;
- occurredAt;
- document ID;
- typed source summary;
- generation;
- searchable chunk count;
- optional processing lineage needed by consumers.

Rules:

- [ ] event payload created from committed publication facts;
- [ ] no credentials/presigned URLs;
- [ ] generation in event must be the generation moved into lifecycle authority;
- [ ] at-least-once semantics documented and tested.

---

## 17. P1 — Version constants and lineage

Introduce explicit versions rather than scattering string literals:

- canonical schema version;
- semantic normalizer version;
- chunker version;
- projection/vector metadata version;
- optional graph projection version.

Suggested location:

`knowledge/ingestion/KnowledgeProcessingVersions.java`

Version changes must be deliberate code changes with tests and documentation updates.

---

## 18. P1 — Security and data minimization

- [ ] whitelist source provenance fields that may reach retrieval/vector metadata;
- [ ] explicitly prohibit bearer tokens, presigned query strings, storage secrets and credentials;
- [ ] limit metadata size/depth/string lengths on canonical admission;
- [ ] sanitize source filenames before logs;
- [ ] hash values may be logged only under an explicit policy, preferably truncated where sufficient;
- [ ] FileService caller authentication/authorization remains a transport/deployment concern but ACL supplied to AkmAI must still pass AkmAI validation;
- [ ] provenance must never bypass existing pre-routing ACL boundaries.

---

## 19. P1 — Observability

Add dimensions carefully to avoid cardinality explosion.

Metrics/events:

- canonical validation accepted/rejected by reason;
- schema-version reject count;
- canonical hash/fingerprint calculation latency;
- ingestion source type;
- event duplicate/replay count;
- publication outbox pending/failed age;
- provenance hydration failures;
- source-lineage persistence failures.

Do not use `fileId`, document IDs, hashes or object keys as unbounded metrics labels.

Structured logs may include document/generation/event IDs subject to the project logging policy.

---

## 20. P0/P1 — Database migration plan

Additive only. No destructive rewrite in the first contract release.

Proposed migration order:

1. source provenance tables/columns;
2. replay-payload version support;
3. chunk provenance representation;
4. inbox event table;
5. outbox table;
6. indexes/constraints;
7. optional backfill for legacy rows only when a source fact can be reconstructed safely.

Migration rules:

- [ ] existing text-ingested generations remain readable/retrievable;
- [ ] legacy rows may have `source_type=LEGACY`/null typed provenance rather than fabricated file identity;
- [ ] no synthetic `fileId` must be invented merely to satisfy NOT NULL;
- [ ] new FileService ingestion requires complete typed provenance;
- [ ] cleanup/retention/reembedding paths must handle all added tables;
- [ ] schema bootstrap test updated;
- [ ] rollback/forward-only policy documented consistently with current Liquibase practice.

---

## 21. P0 — Existing components that must be audited/updated

Production classes:

- [ ] `knowledge/api/CanonicalDocument.java`;
- [ ] `knowledge/api/KnowledgeIngestionResponse.java`;
- [ ] `knowledge/service/CanonicalDocumentMapper.java`;
- [ ] `knowledge/service/KnowledgeIngestionService.java`;
- [ ] `knowledge/service/KnowledgeIngestionPort.java`;
- [ ] `knowledge/idempotency/CanonicalRequestFingerprint.java`;
- [ ] `knowledge/idempotency/IngestionIdempotencyRepository.java`;
- [ ] `knowledge/idempotency/IngestionIdempotencyContext.java`;
- [ ] `knowledge/ingestion/PersistenceCoordinator.java`;
- [ ] `knowledge/ingestion/GenerationPublicationService.java`;
- [ ] `knowledge/ingestion/PublicationOutcomeResolver.java`;
- [ ] `knowledge/projection/SearchProjection.java`;
- [ ] `knowledge/projection/SearchProjectionFactory.java`;
- [ ] `knowledge/projection/PostgresSearchProjectionRepository.java`;
- [ ] `knowledge/model/UnitProvenance.java`;
- [ ] `rag/retrieval/RetrievalHit.java`;
- [ ] all vector/lexical/identifier/reference retrieval row mappers creating `RetrievalHit`;
- [ ] `rag/retrieval/ResultFusion.java`;
- [ ] `rag/retrieval/ParentContextExpansion.java`;
- [ ] graph expansion/adaptive graph hit reconstruction;
- [ ] reembedding clone/cutover path;
- [ ] retention/purge/reconciliation services.

Schema:

- [ ] `002-retrieval-parents.sql` interaction with projection provenance;
- [ ] `005-operational.sql` interaction with replay/idempotency;
- [ ] generation/lifecycle schema;
- [ ] provisioning routines for any new partitioned table;
- [ ] index audit after final schema choice.

---

## 22. P0 — Required test matrix before enabling FileService integration

### Contract tests

- [ ] exact JSON v1 canonical deserialization;
- [ ] unknown schema version;
- [ ] missing source identity;
- [ ] duplicate block IDs;
- [ ] invalid page range/bounding box;
- [ ] presigned URL not treated as identity;
- [ ] deterministic canonical hash.

### Ingestion integration

- [ ] large canonical document → real chunking → deterministic fake embeddings → PostgreSQL → publication;
- [ ] source provenance stored with published generation;
- [ ] chunk block lineage stored and retrieval-visible;
- [ ] failed generation never becomes retrieval-visible;
- [ ] publication result generation equals lifecycle published generation.

### Idempotency/concurrency

- [ ] request replay;
- [ ] request key reuse conflict;
- [ ] event replay;
- [ ] event/request identities independent;
- [ ] two-pod same event race;
- [ ] claim loss before/after generation allocation;
- [ ] crash ambiguity recovery.

### Outbox/failure injection

- [ ] rollback before publication;
- [ ] crash after publication commit;
- [ ] dispatcher retry;
- [ ] duplicate broker publication;
- [ ] outbox lease takeover after pod death.

### Retrieval provenance

- [ ] vector hit provenance;
- [ ] lexical hit provenance;
- [ ] identifier/reference hit provenance;
- [ ] parent expansion preserves provenance;
- [ ] fusion/reranking preserves provenance;
- [ ] graph expansion never fabricates source provenance;
- [ ] ACL prevents cross-level provenance leakage.

### Lifecycle

- [ ] reembedding clones/preserves source provenance;
- [ ] retention purge removes dependent source/chunk provenance;
- [ ] reconciliation detects incomplete provenance when required;
- [ ] legacy non-FileService generations remain valid.

---

## 23. P2 — Performance qualification

Do not make typed provenance an uncontrolled hot-path join.

Benchmark before final schema freeze:

- [ ] ingestion throughput with provenance persistence;
- [ ] publication transaction duration;
- [ ] retrieval p50/p95/p99 with provenance hydration;
- [ ] projection-only columns vs companion provenance-table join;
- [ ] batch hydration to prevent N+1 queries;
- [ ] outbox dispatcher throughput and lock contention;
- [ ] storage growth per 1M chunks;
- [ ] index size/cardinality for `fileId`, generation and chunk linkage.

Acceptance: no material regression beyond the performance budget defined by the existing production gates, or an explicitly approved tradeoff with evidence.

---

## 24. Implementation slices

### Slice P0-A — inbound contract and validation

- typed canonical/source/storage DTOs;
- validator;
- canonical hash;
- mapper compatibility;
- contract/failure tests.

### Slice P0-B — result/publication truth

- internal `KnowledgeIngestionResult`;
- `PersistenceCoordinator` typed return;
- publication-generation propagation;
- legacy response mapper;
- replay compatibility.

### Slice P0-C — durable provenance

- source-generation schema;
- chunk provenance schema choice;
- persistence changes;
- retrieval `SourceProvenance`;
- E2E traceability test.

### Slice P1-A — event admission

- event identity model;
- inbox table/repository;
- duplicate/concurrency tests;
- no broker dependency required yet.

### Slice P1-B — publication outbox

- outbox schema/repository;
- atomic creation in publication transaction;
- `KnowledgeDocumentPublished` contract;
- dispatcher abstraction/failure tests.

### Slice P2 — optimization/qualification

- processing-fingerprint policy;
- provenance storage benchmark;
- outbox throughput;
- target-hardware regression qualification.

---

## 25. Merge gates for the implementation branch

The contract implementation is not merge-ready until all of the following are true:

- [ ] branch synchronized with current `main`;
- [ ] no second ingestion pipeline introduced;
- [ ] old HTTP ingestion JSON compatibility proven by tests;
- [ ] canonical v1 validation is fail-fast before generation/embedding;
- [ ] rich result generation is based on committed publication facts;
- [ ] replay survives old and new persisted payloads;
- [ ] source identity is durable and typed;
- [ ] retrieval hit can trace to canonical blocks and file identity;
- [ ] no storage credential appears in persisted/retrieval provenance;
- [ ] generation publication and outbox are atomic when outbox is enabled;
- [ ] multi-pod duplicate event handling is deterministic;
- [ ] reembedding/retention/reconciliation handle provenance tables;
- [ ] full `mvn verify` green on exact head;
- [ ] PostgreSQL/Testcontainers integration suite green;
- [ ] relevant docs converted from TARGET statements to CURRENT only when implementation is actually present.

---

## 26. Explicit non-goals for v1 implementation

Do not add in this branch unless required by an acceptance criterion:

- broker vendor selection;
- FileService implementation;
- RustFS deployment automation;
- parser implementation;
- automatic processing-fingerprint-based skip policy;
- a second chunker controlled by FileService;
- presigned URL persistence;
- changes to adaptive graph learning policy unrelated to provenance;
- unrelated retrieval feature expansion.

The objective is a clean, durable FileService knowledge boundary integrated into the existing AkmAI authority/lifecycle model, not a general ingestion-platform rewrite.
