# FileService → AkmAI Knowledge Contract v1

**Status:** TARGET  
**Branch:** `feature/fileservice-knowledge-contract-v1`  
**Implementation posture:** synchronous-first, existing-pipeline adaptation, no second ingestion pipeline.  
**Scope:** canonical file-derived knowledge contract, stable source identity, deterministic canonical identity, generation-aware ingestion result/replay, source provenance through retrieval, backward compatibility, regression and PostgreSQL E2E coverage.  
**Deferred:** mandatory Inbox/Outbox, broker integration, asynchronous publication events, standalone event-processing framework, automatic processing-fingerprint skip policy.  
**Out of scope:** FileService implementation, RustFS deployment, parser engine internals, UI, broker selection.

---

## 1. Goal

Prepare AkmAI for a separate FileService that uploads and stores files, parses them asynchronously or in scheduled batches, and submits a deterministic structured document to AkmAI.

The integration must improve the current AkmAI ingestion model rather than create a parallel subsystem.

The target runtime is:

```text
FileService
    │
    │ CanonicalKnowledgeDocument
    │ fileId / sourceVersion / contentHash
    │ stable storage reference
    ▼
KnowledgeIngestionService
    │
    ▼
existing canonical mapping / semantic chunking
    │
    ▼
existing enrichment / embeddings
    │
    ▼
PersistenceCoordinator
    │
    ▼
existing generation + publication transaction
    │
    ├── projections
    ├── vectors
    ├── lifecycle
    └── idempotency
    │
    ▼
KnowledgeIngestionResult
    │
    ▼
RetrievalHit + SourceProvenance
```

The central rule is:

> FileService supplies canonical source structure. AkmAI remains the owner of RAG chunking, embeddings, generation lifecycle, retrieval representation and retrieval policy.

---

## 2. Architectural decisions

### 2.1 One ingestion pipeline

AkmAI MUST NOT introduce a FileService-specific persistence pipeline.

Both legacy and file-derived ingestion must converge on the existing orchestration and generation/publication machinery:

```text
legacy AddKnowledgeRequest ─┐
                            ├→ KnowledgeIngestionService
CanonicalKnowledgeDocument ─┘
                            → chunking
                            → enrichment
                            → PersistenceCoordinator
                            → GenerationPublicationService
```

Any new contract layer is an adapter around this runtime, not an alternative to it.

### 2.2 FileService does not own RAG chunks

FileService MUST NOT prescribe:

- final chunk size;
- overlap strategy;
- chunk identifiers used by AkmAI;
- embedding model/profile;
- vector index layout;
- graph-edge admission;
- retrieval ranking or reranking.

FileService owns source parsing and source structure only.

### 2.3 URL is not identity

A full RustFS URL, presigned URL or temporary download credential MUST NOT be treated as source identity.

Durable source identity is based on:

- `fileId`;
- `sourceVersion`;
- `contentHash`;
- stable storage coordinates where required.

### 2.4 Reuse existing provenance machinery

AkmAI already has `UnitProvenance` for block IDs, page ranges, block references and bounding boxes.

The FileService contract MUST extend this model rather than introduce a second chunk-level provenance hierarchy.

Source-level identity is separate from chunk-level provenance:

```text
source identity
  fileId / sourceVersion / contentHash
          │
          ▼
canonical document
          │
          ▼
UnitProvenance
  blockIds / pages / block refs
          │
          ▼
KnowledgeChunk
          │
          ▼
RetrievalHit.SourceProvenance
```

### 2.5 Publication is the source of truth

A successful ingestion result MUST be derived from durable publication evidence.

A result MUST NOT claim `PUBLISHED` merely because chunking, embedding or generation allocation succeeded.

`generation` and final publication status must come from the existing generation/publication flow.

### 2.6 Synchronous-first v1

For v1, the required integration mode is a direct command/API call from FileService to AkmAI.

Inbox, Outbox, broker delivery and `KnowledgeDocumentPublished` are valid future extensions, but they are not required to consider this v1 contract complete.

---

## 3. Responsibility boundaries

### 3.1 FileService owns

- binary upload;
- validation of the uploaded object;
- immutable original storage;
- stable `fileId`;
- source versioning;
- media type;
- source byte/content hash;
- parser selection and execution;
- parser retries;
- physical page/layout extraction;
- headings, paragraphs, tables, lists, code, footnotes and OCR/image text;
- parser name/version;
- storage coordinates;
- FileService-side processing status.

### 3.2 AkmAI owns

- canonical contract validation;
- idempotency admission;
- canonical normalization for hashing;
- semantic normalization;
- semantic grouping/chunking;
- chunk identity;
- identifier/reference extraction;
- embedding profile selection;
- embedding generation;
- search projections;
- graph/reference representation;
- generation allocation;
- atomic publication;
- retrieval/reranking;
- adaptive graph behavior;
- retrieval provenance exposure.

---

## 4. CanonicalKnowledgeDocument v1

`CanonicalKnowledgeDocument` is the stable boundary between source parsing and AkmAI RAG processing.

It preserves source meaning and source provenance without encoding AkmAI retrieval decisions.

### 4.1 Required shape

```json
{
  "schemaVersion": 1,
  "documentId": "doc-01K...",
  "version": "17",
  "title": "AkmAI Architecture",
  "language": "ru",
  "domain": "TECHNICAL",
  "accessLevel": 10,
  "source": {
    "type": "FILE",
    "fileId": "file-01K...",
    "sourceVersion": "17",
    "fileName": "architecture.pdf",
    "mediaType": "application/pdf",
    "contentHash": "sha256:...",
    "storage": {
      "provider": "rustfs",
      "bucket": "knowledge-raw",
      "objectKey": "tenant-17/files/file-01K/source.pdf",
      "versionId": "optional"
    }
  },
  "processing": {
    "parser": "pdf-parser",
    "parserVersion": "4.2.0",
    "parsedAt": "2026-10-09T12:00:00Z"
  },
  "blocks": [],
  "metadata": {}
}
```

### 4.2 Source identity

`documentId` is the AkmAI knowledge-document identity.

`fileId` is the stable source artifact identity allocated by FileService.

`sourceVersion` identifies the source version known to FileService.

`contentHash` identifies source content independently from object-storage URLs.

`version` and `sourceVersion` SHOULD be coherent for the v1 FILE source model. If both are used for different semantics later, that distinction must become explicit rather than implicit.

### 4.3 Storage reference

A storage reference MAY contain:

- provider;
- bucket/container;
- object key;
- provider object version.

It MUST NOT contain:

- credentials;
- authorization headers;
- long-lived secrets;
- presigned URL as durable identity.

A presigned URL may be generated operationally by FileService but is not part of canonical identity, hashing or idempotency.

### 4.4 Blocks

Supported v1 block types:

- `HEADING`;
- `PARAGRAPH`;
- `TABLE`;
- `LIST`;
- `CODE`;
- `FOOTNOTE`;
- `IMAGE_TEXT`.

Each block must provide a stable `blockId` within the canonical document version and textual content.

Optional source-layout fields include:

- heading level;
- `pageFrom` / `pageTo`;
- section path;
- bounding box.

FileService block boundaries are source-structure boundaries, not final RAG chunk boundaries.

### 4.5 Contract validation

AkmAI MUST reject the request before expensive processing when any of the following is true:

- unsupported `schemaVersion`;
- missing/blank document identity;
- missing/blank `fileId` or `sourceVersion`;
- malformed `contentHash`;
- unsupported language;
- missing domain;
- non-positive access level;
- empty block list;
- duplicate block IDs;
- invalid page ranges;
- invalid bounding boxes;
- malformed storage reference;
- invalid parser metadata when processing metadata is present.

Validation failure MUST NOT allocate a generation or call embedding.

---

## 5. Identity model

The following identities have different semantics and MUST NOT be conflated.

### 5.1 Request identity

HTTP `Idempotency-Key` protects command retry.

### 5.2 Source identity

`fileId + sourceVersion` identifies a FileService source version.

### 5.3 Source content identity

`contentHash` identifies source bytes/content supplied by FileService.

### 5.4 Canonical content identity

`canonicalHash` identifies the normalized `CanonicalKnowledgeDocument` representation used by AkmAI.

### 5.5 Generation identity

`documentId + generation + accessLevel` remains AkmAI's durable publication identity.

### 5.6 Deferred event identity

`eventId` belongs only to a future asynchronous delivery mechanism. It MUST NOT be overloaded into `Idempotency-Key`.

---

## 6. Canonical hash

AkmAI SHALL provide one deterministic canonical hashing implementation for `CanonicalKnowledgeDocument`.

The hash MUST:

- use deterministic canonical serialization;
- preserve semantic block order;
- normalize textual values consistently;
- include stable source/content identity;
- include canonical structure relevant to meaning;
- exclude volatile processing timestamps;
- exclude temporary URLs;
- exclude tracing/correlation identifiers;
- exclude secrets.

The canonical hash and request fingerprint are related but not identical concepts.

For the FileService contract, the idempotency fingerprint SHOULD be constructed from the canonical hash plus stable request-processing inputs rather than implementing a separate canonicalization algorithm.

The existing post-chunk projection fingerprint remains a different layer and MUST NOT be renamed conceptually into `canonicalHash`.

---

## 7. Mapping and provenance

### 7.1 Canonical mapping

`CanonicalDocumentMapper` is the compatibility boundary from canonical source structure into the current `KnowledgeDocument + SemanticUnit` model.

It MUST:

- preserve source identity in a controlled typed/whitelisted representation;
- map blocks into semantic units;
- preserve block IDs;
- preserve page ranges;
- preserve block-level bounding boxes where available;
- preserve/derive section path deterministically;
- continue using `UnitProvenance` as the chunk-level provenance model.

### 7.2 Chunk provenance merge

When one AkmAI chunk contains multiple canonical blocks:

- block IDs are ordered and de-duplicated;
- `pageFrom` is the minimum known page;
- `pageTo` is the maximum known page;
- block references remain traceable;
- section path follows existing deterministic chunking semantics.

### 7.3 Source metadata compatibility bridge

The current runtime uses `Map<String,Object> metadata` heavily across `KnowledgeDocument`, chunks, projections and vectors.

The v1 implementation MAY use one centralized source-provenance codec/bridge to carry stable source fields through this existing pipeline, provided that:

- the keys are owned by one component;
- source credentials are never copied;
- parsing into typed `SourceProvenance` is centralized;
- downstream code does not invent duplicate key names;
- generic metadata is not treated as the public contract.

This is preferred over introducing a second persistence hierarchy before there is evidence that one is required.

---

## 8. KnowledgeIngestionResult v1

The legacy `KnowledgeIngestionResponse(documentId, chunkCount)` remains supported for backward compatibility.

FileService-facing ingestion requires a richer result.

### 8.1 Required semantics

```json
{
  "schemaVersion": 1,
  "documentId": "doc-01K...",
  "source": {
    "type": "FILE",
    "fileId": "file-01K...",
    "sourceVersion": "17",
    "contentHash": "sha256:..."
  },
  "publication": {
    "status": "PUBLISHED",
    "generation": 42,
    "chunkCount": 137
  },
  "processing": {
    "canonicalSchemaVersion": 1,
    "canonicalHash": "...",
    "embeddingProfile": "..."
  }
}
```

### 8.2 Publication status

At minimum:

- `PUBLISHED` — durable publication confirmed;
- `REPLAYED` — an equivalent previously successful request was replayed.

`IN_PROGRESS`, publication ambiguity and hard failure MUST remain explicit error/failure states rather than false success responses.

`SUPERSEDED` may remain internal unless FileService has a concrete need for it.

### 8.3 Publication result invariant

The result MUST be built after `PersistenceCoordinator` / publication outcome is known.

The result MUST expose the actual published generation.

The legacy response may continue to expose only `documentId` and chunk count through a compatibility mapper.

---

## 9. Idempotency and replay

Existing `Idempotency-Key` semantics remain authoritative.

The FileService contract MUST reuse the same repository/lease/fencing flow.

Required behavior:

- same key + same fingerprint → replay successful result;
- same key + different fingerprint → `IDEMPOTENCY_KEY_REUSE`;
- current live claim → `INGESTION_IN_PROGRESS`;
- lost claim → explicit idempotency-loss error;
- crash after durable publication → reclaim/replay must resolve the published generation rather than reprocess blindly.

For FileService-facing replay, AkmAI must retain or reconstruct the published `generation`.

The legacy persisted `response_json` format must remain readable during migration.

A schema change to a new replay envelope is optional for v1 if generation can be recovered safely from the existing idempotency/generation state without breaking compatibility.

---

## 10. Retrieval SourceProvenance

Stable provenance MUST be available as a typed retrieval concept even while generic metadata remains supported internally.

Minimum typed fields:

```json
{
  "sourceType": "FILE",
  "fileId": "file-01K...",
  "sourceVersion": "17",
  "fileName": "architecture.pdf",
  "mediaType": "application/pdf",
  "contentHash": "sha256:...",
  "blockIds": ["b-137", "b-138"],
  "pageFrom": 37,
  "pageTo": 38,
  "sectionPath": ["Architecture", "Persistence", "PostgreSQL"]
}
```

### 10.1 Traceability invariant

For a searchable chunk derived from FileService input, AkmAI must be able to trace:

```text
RetrievalHit
  → chunkId
  → canonical block IDs/pages
  → documentId + generation
  → fileId + sourceVersion + contentHash
```

AkmAI does not need to return storage credentials or a presigned object URL.

### 10.2 Retrieval pipeline preservation

Typed provenance must survive every transformation that rebuilds a hit, including where applicable:

- vector retrieval;
- lexical retrieval;
- identifier/reference retrieval;
- fusion;
- reranking;
- parent expansion;
- graph expansion;
- diversity filtering;
- final-context materialization.

If a transformation rebuilds a `RetrievalHit`, dropping provenance is a defect.

---

## 11. Persistence policy for v1

The compact v1 design does NOT require a new dedicated source-provenance table as a prerequisite.

Source identity may initially travel through the existing projection/vector metadata path via a centralized typed codec, because this preserves the current generation transaction and minimizes schema risk.

A dedicated generation-level table such as `knowledge_document_source` SHOULD be introduced only when one of the following is demonstrated:

- source-level queries require it;
- per-chunk duplication becomes operationally significant;
- retention/purge semantics require normalized source rows;
- provenance reconstruction from existing projection metadata is too expensive;
- security or governance requires explicit typed columns;
- benchmark evidence shows the model is superior without harming ingestion/publication cost.

If a new table is introduced later, it MUST remain subordinate to `documentId + generation + accessLevel` and MUST NOT become a parallel lifecycle authority.

---

## 12. Processing fingerprint

A future processing fingerprint may combine:

```text
canonicalHash
+ canonical schema version
+ semantic normalizer version
+ chunker version
+ embedding profile
```

For v1 it is useful as lineage/diagnostic data, but it MUST NOT automatically skip processing without an explicit policy and test evidence.

The current post-chunk projection fingerprint remains valid for its current generation/content-fencing purpose.

---

## 13. Backward compatibility

The change MUST preserve current consumers unless an explicit API major-version migration is made.

Required compatibility rules:

- existing text-ingestion endpoint remains functional;
- existing canonical ingestion remains readable/usable during migration;
- legacy `KnowledgeIngestionResponse` JSON remains stable;
- old idempotency replay rows remain readable;
- retrieval metadata remains available for code that still consumes it;
- typed provenance is additive;
- generation/publication semantics are not weakened.

---

## 14. Security

The following data MUST NOT be persisted in canonical identity, retrieval metadata or hashes:

- access tokens;
- authorization headers;
- passwords;
- object-storage credentials;
- presigned URLs;
- temporary query credentials.

AkmAI should expose a stable source reference (`fileId`, version and content identity). FileService remains responsible for authorizing access to original binary content.

ACL remains an AkmAI routing boundary and must remain part of generation identity/publication checks.

---

## 15. Positive cases

The implementation must cover at least:

1. valid PDF-derived canonical document → published generation;
2. source identity survives canonical mapping, chunking and retrieval;
3. one chunk formed from multiple blocks retains ordered block IDs and page range;
4. same source request + same idempotency key → replay without duplicate generation;
5. retry after caller loses response but publication committed → returns existing generation;
6. new source version → new valid publication generation;
7. same file bytes represented deterministically → same canonical hash when canonical content is unchanged;
8. legacy text ingestion remains unaffected.

---

## 16. Negative and failure cases

The implementation must cover at least:

- unsupported canonical schema version;
- empty block list;
- duplicate block IDs;
- invalid page range;
- invalid bounding box;
- malformed source content hash;
- malformed storage reference;
- unsupported language;
- invalid access level;
- same idempotency key with different canonical request;
- idempotency claim lost before publication;
- embedding failure;
- publication failure;
- publication outcome unknown;
- superseded generation handling;
- provenance accidentally dropped by retrieval-hit transformation.

For all pre-processing validation failures:

- no generation allocation;
- no embedding call;
- no partial publication.

---

## 17. Concurrency and recovery

Existing AkmAI concurrency authority remains unchanged:

- PostgreSQL transaction state;
- generation fences;
- idempotency claim/lease;
- publication transaction;
- lifecycle published-generation pointer.

The FileService contract must not add a second concurrency authority.

Required tests include:

- concurrent same idempotency key;
- expired claim reclaim;
- crash/failure after generation allocation;
- durable publication with lost caller response;
- newer generation wins publication race;
- 2+ service instances do not duplicate publication for the same current claim.

---

## 18. Performance constraints

The adaptation should not materially regress existing ingestion/retrieval performance.

At minimum measure or guard:

- canonical hashing cost for large documents;
- provenance metadata size for large block counts;
- chunking throughput;
- embedding batch behavior;
- publication transaction duration;
- retrieval-hit mapping overhead;
- additional allocation caused by typed provenance.

A dedicated provenance table is not justified unless its operational benefit exceeds its migration/query cost.

---

## 19. Deferred asynchronous extension

The following is deliberately deferred from v1 completion:

```text
FileService Outbox
      ↓
Broker
      ↓
AkmAI Inbox
      ↓
existing ingestion pipeline
      ↓
AkmAI Outbox
      ↓
KnowledgeDocumentPublished
```

When asynchronous integration becomes a real requirement:

- `eventId` must remain distinct from HTTP idempotency key;
- Inbox dedup must prevent duplicate processing side effects;
- publication event creation must be transactionally coupled to publication, normally via Outbox;
- delivery is at-least-once;
- broker calls must not occur inside the publication DB transaction.

None of these are required to merge the synchronous FileService knowledge contract v1.

---

## 20. Definition of Done

The FileService knowledge contract v1 is complete when all of the following are true:

- `CanonicalKnowledgeDocument` is versioned and validated;
- source identity uses `fileId/sourceVersion/contentHash`, not URL identity;
- deterministic `canonicalHash` exists;
- canonical mapping reuses `UnitProvenance`;
- no second ingestion pipeline exists;
- `PersistenceCoordinator` exposes publication/generation truth needed by the richer result;
- FileService-facing result exposes the actual successful generation;
- replay can return/reconstruct the successful generation;
- legacy ingestion response remains backward compatible;
- typed `SourceProvenance` is available on retrieval hits;
- provenance survives retrieval/fusion/reranking/expansion paths;
- storage credentials/presigned URLs do not leak into hashes or retrieval output;
- positive, negative, concurrency and recovery tests are present;
- PostgreSQL/Testcontainers E2E proves canonical input → publication → retrieval provenance;
- full CI/verify is green on the exact branch head;
- active documentation is updated to match executable behavior.

Inbox/Outbox/event-bus implementation is explicitly NOT part of this Definition of Done.

---

## 21. Final target

The v1 contract should leave AkmAI with this stable boundary:

```text
FileService
    │
    │ CanonicalKnowledgeDocument
    ▼
AkmAI existing ingestion pipeline
    │
    ├── KnowledgeIngestionResult
    └── RetrievalHit + SourceProvenance
```

The design deliberately prefers a small number of strong contracts over a larger integration framework. Future asynchronous transport can be added around this boundary without changing the core knowledge-processing model.
