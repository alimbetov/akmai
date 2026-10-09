# FileService → AkmAI Knowledge Contract v1 — Unified Implementation Checklist

**Status:** TARGET  
**Branch:** `feature/fileservice-knowledge-contract-v1`  
**Parent specification:** [`fileservice-knowledge-contract-v1.md`](fileservice-knowledge-contract-v1.md)  
**Implementation strategy:** one cohesive adaptation of the existing ingestion/generation pipeline; no P0/P1/P2 split and no second ingestion pipeline.

---

## 1. Non-negotiable implementation rules

- [ ] Keep `KnowledgeIngestionService → PersistenceCoordinator → GenerationPublicationService` as the only publication path.
- [ ] Do not create a FileService-specific persistence or generation lifecycle.
- [ ] Keep legacy text ingestion and legacy `KnowledgeIngestionResponse` backward compatible.
- [ ] Reuse `UnitProvenance` for block/page/bounding-box lineage.
- [ ] Add typed source identity and retrieval provenance without duplicating chunk-provenance semantics.
- [ ] Treat publication/generation state as the source of truth for successful ingestion result.
- [ ] Keep `Idempotency-Key`, source identity, content identity and future event identity separate.
- [ ] Do not persist presigned URLs, credentials or authorization material as durable provenance.
- [ ] Prefer an additive compatibility bridge over a large storage redesign unless tests/benchmarks justify the latter.
- [ ] Keep Inbox/Outbox/broker work deferred unless a concrete asynchronous integration requirement appears.

---

## 2. Current baseline to preserve

The implementation must respect the current project architecture:

- `KnowledgeIngestionService` orchestrates ingestion;
- `CanonicalDocumentMapper` maps canonical blocks to `KnowledgeDocument` + `SemanticUnit`;
- `SemanticChunker` and `HierarchicalChunker` own chunking;
- `UnitProvenance` already merges block IDs/page ranges/block refs;
- `PersistenceCoordinator` allocates generation and prepares projections/vectors;
- `GenerationPublicationService` owns the publication transaction;
- `IngestionIdempotencyRepository` owns request claim/replay semantics;
- `RetrievalHit` is the retrieval result carrier;
- PostgreSQL generation/lifecycle state remains authoritative.

If a proposed change duplicates one of these responsibilities, it should be redesigned before coding.

---

## 3. Canonical file contract

### Files / classes

- `src/main/java/kz/alimbetov/akmai/knowledge/api/CanonicalKnowledgeDocument.java`
- legacy `CanonicalDocument` compatibility path where still required
- canonical validation component
- canonical hash component

### Required work

- [ ] `schemaVersion` with v1 validation.
- [ ] `documentId`, `version`, `title`, language, domain and ACL remain explicit.
- [ ] Typed FILE source model:
  - [ ] `fileId`;
  - [ ] `sourceVersion`;
  - [ ] `fileName`;
  - [ ] `mediaType`;
  - [ ] `contentHash`;
  - [ ] optional stable storage reference.
- [ ] Typed parser metadata:
  - [ ] parser name;
  - [ ] parser version;
  - [ ] parsed timestamp.
- [ ] Block IDs remain stable within one canonical version.
- [ ] FileService blocks remain source blocks, not RAG chunks.
- [ ] Storage reference contains only stable coordinates.
- [ ] Presigned URLs/credentials rejected or excluded from durable identity.

### Validation

Reject before generation allocation/embedding:

- [ ] unsupported schema version;
- [ ] blank source/file identity;
- [ ] malformed hash;
- [ ] invalid language/domain/ACL;
- [ ] empty blocks;
- [ ] duplicate block IDs;
- [ ] invalid page range;
- [ ] invalid bounding box;
- [ ] malformed storage reference.

### Tests

- [ ] valid FILE source;
- [ ] invalid schema version;
- [ ] duplicate block ID;
- [ ] version/sourceVersion mismatch according to chosen v1 rule;
- [ ] malformed content hash;
- [ ] malformed storage coordinates;
- [ ] parser metadata normalization.

---

## 4. Deterministic canonical identity

### Component

Use one canonical hashing implementation for the FileService contract.

### Required work

- [ ] SHA-256 over deterministic normalized representation.
- [ ] Preserve block order.
- [ ] Normalize strings consistently.
- [ ] Include stable source/content identity.
- [ ] Exclude `parsedAt` from canonical content hash.
- [ ] Exclude temporary URLs, trace IDs and secrets.
- [ ] Keep canonical hash semantically distinct from the existing post-chunk projection fingerprint.
- [ ] Make FileService request fingerprint reuse canonical hash rather than duplicate canonical serialization logic.

### Tests

- [ ] same semantic canonical input → same hash;
- [ ] changed block text → changed hash;
- [ ] changed block order → changed hash when order changes meaning;
- [ ] changed `parsedAt` → unchanged canonical hash;
- [ ] changed source content identity → changed hash where required by the contract;
- [ ] Unicode normalization is deterministic.

---

## 5. Canonical mapping and provenance reuse

### File

`src/main/java/kz/alimbetov/akmai/knowledge/service/CanonicalDocumentMapper.java`

### Required work

- [ ] Accept `CanonicalKnowledgeDocument` through the existing mapper/orchestration path.
- [ ] Preserve current `KnowledgeDocument + SemanticUnit` internal model.
- [ ] Continue to construct `UnitProvenance.BlockRef`.
- [ ] Preserve block IDs, page ranges and bounding boxes.
- [ ] Preserve/derive section paths deterministically.
- [ ] Map typed source identity through one centralized metadata/provenance compatibility codec.
- [ ] Do not scatter literal source-key names across chunker/retrieval code.
- [ ] Do not create a second `FileChunkProvenance` model competing with `UnitProvenance`.

### Multi-block merge invariants

- [ ] ordered distinct block IDs;
- [ ] minimum `pageFrom`;
- [ ] maximum `pageTo`;
- [ ] block refs preserved;
- [ ] section semantics remain deterministic.

### Tests

- [ ] heading + paragraph mapping;
- [ ] table/list/code block mapping;
- [ ] multi-block semantic chunk provenance;
- [ ] bounding box survives into block refs;
- [ ] source identity survives into chunk metadata bridge.

---

## 6. Source provenance compatibility bridge

### Purpose

Use the existing metadata-carrying pipeline safely without making generic metadata the public contract.

### Required work

- [ ] One component owns canonical source metadata keys.
- [ ] One component serializes typed source identity into internal metadata.
- [ ] One component reconstructs typed `SourceProvenance` from internal metadata.
- [ ] No storage credentials enter metadata.
- [ ] No presigned URL enters vectors/projections.
- [ ] Source fields remain stable across `KnowledgeDocument → KnowledgeChunk → SearchProjection → vector metadata`.
- [ ] Existing arbitrary metadata remains supported separately.

### Storage policy

For compact v1:

- [ ] do not require a new source-provenance table to complete the feature;
- [ ] use current projection/vector metadata path under a controlled codec;
- [ ] document duplication cost;
- [ ] add benchmark/size evidence before introducing `knowledge_document_source` or chunk-provenance tables.

A dedicated table becomes a follow-up only if operational evidence justifies it.

---

## 7. Publication-aware persistence result

### File

`src/main/java/kz/alimbetov/akmai/knowledge/ingestion/PersistenceCoordinator.java`

### Required work

- [ ] Return a typed persistence/publication result instead of relying only on side effects.
- [ ] Include actual generation.
- [ ] Include final publication state.
- [ ] Include searchable chunk count.
- [ ] Include active embedding profile identity used for publication.
- [ ] Preserve current heartbeat/idempotency/failure behavior.
- [ ] Preserve `PublicationOutcomeUnknownException` semantics.
- [ ] Preserve `SUPERSEDED` handling.
- [ ] Do not treat generation allocation alone as success.
- [ ] Keep the current projection/content fingerprint distinct from canonical hash.

Suggested internal shape:

```text
PersistenceResult
  documentId
  generation
  publicationStatus
  searchableChunkCount
  embeddingProfileId
  projectionFingerprint
```

### Tests

- [ ] `PUBLISHED` returns actual generation;
- [ ] `ALREADY_PUBLISHED` maps correctly;
- [ ] `SUPERSEDED` never becomes false success;
- [ ] publication outcome unknown remains explicit;
- [ ] embedding failure never returns a successful result.

---

## 8. KnowledgeIngestionResult

### Files

- FileService-facing/internal richer result DTO;
- compatibility mapper to legacy `KnowledgeIngestionResponse`.

### Required work

- [ ] `schemaVersion`.
- [ ] `documentId`.
- [ ] source summary (`fileId/sourceVersion/contentHash`).
- [ ] publication status.
- [ ] actual generation.
- [ ] searchable chunk count.
- [ ] canonical schema version/hash.
- [ ] embedding profile identity.
- [ ] replay marker/status.
- [ ] no false-success result for in-progress/unknown publication.

### Compatibility

- [ ] Existing endpoint can still return `{documentId, chunkCount}`.
- [ ] Legacy JSON contract remains stable.
- [ ] Rich result is additive/internal/versioned rather than silently replacing old semantics.

---

## 9. KnowledgeIngestionService integration

### File

`src/main/java/kz/alimbetov/akmai/knowledge/service/KnowledgeIngestionService.java`

### Required work

- [ ] Add/retain FileService canonical entry point on the same service.
- [ ] Validate canonical input before expensive work.
- [ ] Use canonical request fingerprint/idempotency claim.
- [ ] Map into existing chunking path.
- [ ] Use existing enrichment executor.
- [ ] Call existing `PersistenceCoordinator`.
- [ ] Build rich result only from returned publication result.
- [ ] Map rich result to legacy response where required.
- [ ] Do not duplicate `persistChunks` or publication logic.

Target flow:

```text
CanonicalKnowledgeDocument
  → validate/hash
  → claim idempotency
  → CanonicalDocumentMapper
  → HierarchicalChunker
  → ParallelIngestionExecutor
  → PersistenceCoordinator
  → publication-aware result
  → KnowledgeIngestionResult
```

---

## 10. Idempotency and replay

### File

`src/main/java/kz/alimbetov/akmai/knowledge/idempotency/IngestionIdempotencyRepository.java`

### Required work

- [ ] Keep existing key/fingerprint/lease semantics.
- [ ] Same key + same fingerprint → replay.
- [ ] Same key + different fingerprint → conflict.
- [ ] Current live claim → in-progress conflict.
- [ ] Lost claim → explicit error.
- [ ] Replay result must retain/recover published generation for FileService.
- [ ] Expired-claim recovery from a PUBLISHED generation returns that generation.
- [ ] Existing `response_json` rows remain readable.

### Compatibility decision

Prefer the smallest correct solution:

- [ ] first attempt to recover generation from existing idempotency/generation state;
- [ ] introduce a versioned replay envelope only if required for correctness or reliable lineage reconstruction;
- [ ] do not migrate persisted JSON merely for DTO aesthetics.

### Tests

- [ ] same request replay;
- [ ] changed request conflict;
- [ ] published generation recovery;
- [ ] lost caller response after commit;
- [ ] expired claim + STAGING generation;
- [ ] expired claim + PUBLISHED generation;
- [ ] multipod same-key race.

---

## 11. RetrievalHit typed provenance

### Files

- `src/main/java/kz/alimbetov/akmai/rag/retrieval/RetrievalHit.java`
- typed `SourceProvenance` model / existing model location

### Required work

Typed provenance must expose at least:

- [ ] source type;
- [ ] file ID;
- [ ] source version;
- [ ] file name;
- [ ] media type;
- [ ] content hash;
- [ ] block IDs;
- [ ] page range;
- [ ] ordered section path.

Generic metadata remains available but is not the stable provenance API.

### Propagation audit

Inspect every place that constructs/copies/rebuilds `RetrievalHit`:

- [ ] vector mapper;
- [ ] lexical mapper;
- [ ] identifier/reference retrieval;
- [ ] fusion;
- [ ] reranking;
- [ ] parent expansion;
- [ ] graph expansion;
- [ ] diversity filtering;
- [ ] final-context conversion.

For each location:

- [ ] preserve provenance explicitly or preserve metadata in a way that the constructor reconstructs it deterministically;
- [ ] add a focused regression test where provenance could otherwise be dropped.

### Invariant

```text
RetrievalHit
  → chunkId
  → blockIds/pages
  → documentId + generation
  → fileId + sourceVersion + contentHash
```

---

## 12. Security audit

Search and prevent persistence/exposure of:

- [ ] presigned URLs;
- [ ] access tokens;
- [ ] authorization headers;
- [ ] RustFS credentials;
- [ ] passwords/secrets;
- [ ] temporary query parameters carrying credentials.

Confirm:

- [ ] hashes exclude volatile credentials;
- [ ] retrieval output contains stable source identity only;
- [ ] FileService remains the authorization boundary for original file download;
- [ ] AkmAI ACL remains part of generation/retrieval authority.

---

## 13. Positive business cases

Implement and document tests for:

- [ ] FileService PDF canonical payload publishes successfully;
- [ ] FileService DOCX/text-derived canonical payload publishes successfully;
- [ ] source identity survives to retrieval;
- [ ] multi-block chunk lineage is correct;
- [ ] same idempotent request replays;
- [ ] committed publication recovered after caller loses response;
- [ ] new source version creates a new valid generation;
- [ ] legacy text ingestion remains unchanged;
- [ ] legacy canonical ingestion remains compatible during transition.

---

## 14. Negative/failure cases

Cover:

- [ ] unsupported schema version;
- [ ] duplicate block IDs;
- [ ] malformed hash;
- [ ] malformed storage reference;
- [ ] unsupported language;
- [ ] invalid ACL;
- [ ] invalid page range;
- [ ] invalid bounding box;
- [ ] no indexable chunks;
- [ ] same idempotency key/different request;
- [ ] claim loss;
- [ ] embedding failure;
- [ ] persistence failure;
- [ ] publication failure;
- [ ] unknown publication outcome;
- [ ] superseded generation;
- [ ] provenance dropped by a retrieval transformation.

For validation failures assert:

- [ ] generation not allocated;
- [ ] embedding not called;
- [ ] publication not called.

---

## 15. PostgreSQL/Testcontainers E2E

Add an end-to-end test using the existing real PostgreSQL/pgvector stack where practical.

Required scenario:

```text
CanonicalKnowledgeDocument
  → validate/hash
  → canonical mapper
  → real chunking
  → enrichment
  → deterministic/fake embedding model
  → generation allocation
  → real publication transaction
  → projections/vectors
  → published retrieval
  → RetrievalHit.SourceProvenance assertions
```

Assertions:

- [ ] generation is PUBLISHED;
- [ ] lifecycle points to the generation;
- [ ] searchable chunk count matches persisted/searchable rows;
- [ ] retrieval returns the expected generation/document;
- [ ] retrieval provenance includes source identity;
- [ ] canonical block IDs/page range survive;
- [ ] no presigned URL/credential appears in projection/vector metadata.

---

## 16. Concurrency/failure-injection coverage

Use the existing generation/idempotency transaction boundaries.

Cover:

- [ ] concurrent same idempotency key;
- [ ] two service instances attempting equivalent current work;
- [ ] lease expiry before generation allocation;
- [ ] lease expiry after allocation;
- [ ] failure during embedding;
- [ ] failure during publication;
- [ ] commit succeeds but response path fails;
- [ ] newer generation supersedes older work;
- [ ] recovery does not create duplicate publication.

Do not introduce a new distributed lock specifically for FileService.

---

## 17. Performance regression audit

Measure enough to prove the adaptation is not materially harmful:

- [ ] canonical hash on large documents;
- [ ] metadata/provenance payload size;
- [ ] chunking throughput;
- [ ] embedding batch count/size;
- [ ] publication transaction duration;
- [ ] retrieval-hit provenance reconstruction cost;
- [ ] memory/allocation overhead from typed provenance.

If provenance duplication becomes significant, use evidence to decide whether a normalized source table is warranted.

---

## 18. Documentation updates during implementation

Keep the following synchronized with executable behavior:

- [ ] parent TARGET specification;
- [ ] this checklist;
- [ ] `docs/services/knowledge-ingestion.md`;
- [ ] current runtime architecture if externally visible runtime flow changes;
- [ ] relevant API examples;
- [ ] failure/idempotency semantics;
- [ ] positive and negative business cases.

When the implementation becomes fully executable and merged, convert the architecture document from TARGET to CURRENT in the same change set.

---

## 19. Explicitly deferred work

The following are not required for v1 readiness:

- [ ] AkmAI Inbox table;
- [ ] FileService/AkmAI event dedup by `eventId`;
- [ ] AkmAI Outbox table;
- [ ] broker dispatcher;
- [ ] `KnowledgeDocumentPublished` event delivery;
- [ ] Kafka/RabbitMQ integration;
- [ ] automatic processing-fingerprint based skip/reprocess policy;
- [ ] dedicated source-provenance table without demonstrated need.

These remain valid follow-up architecture, not unfinished v1 blockers.

---

## 20. Merge readiness / Definition of Done

The branch is merge-ready only when:

- [ ] canonical v1 contract and validation are implemented;
- [ ] canonical hash is deterministic;
- [ ] source identity is stable and URL-independent;
- [ ] existing chunk/provenance machinery is reused;
- [ ] no second ingestion pipeline exists;
- [ ] publication-aware persistence result exposes actual generation;
- [ ] rich FileService result exists;
- [ ] legacy response remains compatible;
- [ ] replay preserves/reconstructs generation;
- [ ] typed retrieval provenance exists;
- [ ] retrieval transformations do not drop provenance;
- [ ] positive/negative/concurrency/recovery tests are present;
- [ ] PostgreSQL/Testcontainers E2E passes;
- [ ] no credential/presigned URL leakage is found;
- [ ] performance regression is acceptable;
- [ ] full exact-head CI/verify is green;
- [ ] documentation matches the final code.

Inbox/Outbox/event bus are not part of this merge gate.
