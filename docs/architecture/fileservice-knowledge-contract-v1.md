# FileService → AkmAI Knowledge Contract v1

**Status:** CURRENT  
**Implementation:** merged into `main`; canonical v1 is currently a service/port contract and is not yet exposed by a dedicated public REST endpoint.  
**Implementation posture:** synchronous-first, existing-pipeline adaptation, no second ingestion pipeline.  
**Scope:** canonical file-derived knowledge contract, stable source identity, deterministic canonical identity, generation-aware ingestion result/replay, source provenance through retrieval, backward compatibility, regression and PostgreSQL E2E coverage.  
**Deferred:** mandatory Inbox/Outbox, broker integration, asynchronous publication events, standalone event-processing framework, automatic processing-fingerprint skip policy.  
**Out of scope:** FileService implementation, RustFS deployment, parser engine internals, UI, broker selection.

---

## 1. Goal

AkmAI supports a separate FileService that uploads and stores files, parses them asynchronously or in scheduled batches, and submits a deterministic structured document to AkmAI through the canonical service boundary.

The integration extends the current AkmAI ingestion model rather than creating a parallel subsystem.

The current runtime contract is:

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

For concrete request/response JSON and public RAG question/answer examples, see [`../services/external-api-contracts.md`](../services/external-api-contracts.md).

---

## 2. Architectural decisions

### 2.1 One ingestion pipeline

AkmAI MUST NOT introduce a FileService-specific persistence pipeline.

Both legacy and file-derived ingestion converge on the existing orchestration and generation/publication machinery:

```text
legacy AddKnowledgeRequest ─┐
                            ├→ KnowledgeIngestionService
CanonicalKnowledgeDocument ─┘
                            → chunking
                            → enrichment
                            → PersistenceCoordinator
                            → GenerationPublicationService
```

Any new transport layer is an adapter around this runtime, not an alternative to it.

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

The FileService contract extends this model rather than introducing a second chunk-level provenance hierarchy.

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

`generation` and final publication status come from the existing generation/publication flow.

### 2.6 Synchronous-first v1

For v1, the required integration mode is a direct command/service call from FileService to AkmAI.

Inbox, Outbox, broker delivery and `KnowledgeDocumentPublished` remain valid future extensions, but they are not required for the current v1 contract.

The public HTTP controller currently exposes legacy text ingestion only. A future canonical REST endpoint must be a thin adapter over the same service path.

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
    "contentHash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
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

`contentHash` identifies source content independently from object-storage URLs and must use `sha256:<64 hex characters>`.

For v1, `version` MUST equal `source.sourceVersion`.

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

`storage.objectKey` must be a stable object key and must not be an HTTP(S) URL.

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

AkmAI rejects the request before expensive processing when any of the following is true:

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
- invalid parser metadata;
- credential-bearing, token-bearing or signed-URL metadata.

Validation failure MUST NOT allocate a generation or call embedding.

---

## 5. Identity model

The following identities have different semantics and MUST NOT be conflated.

### 5.1 Request identity

`Idempotency-Key` protects command retry when the transport exposes the canonical service contract.

### 5.2 Source identity

`fileId + sourceVersion` identifies a FileService source version.

### 5.3 Source content identity

`contentHash` identifies source bytes/content supplied by FileService.

### 5.4 Canonical content identity

`canonicalHash` identifies the normalized `CanonicalKnowledgeDocument` representation used by AkmAI.

For canonical v1, this hash is also the stable idempotency fingerprint used by the canonical service path.

### 5.5 Generation identity

`documentId + generation + accessLevel` remains AkmAI's durable publication identity.

### 5.6 Deferred event identity

`eventId` belongs only to a future asynchronous delivery mechanism. It MUST NOT be overloaded into `Idempotency-Key`.

---

## 6. Canonical hash

AkmAI provides one deterministic canonical hashing implementation for `CanonicalKnowledgeDocument`.

The hash:

- uses deterministic canonical serialization;
- preserves semantic block order;
- normalizes textual values consistently;
- includes stable source/content identity;
- includes canonical structure relevant to meaning;
- excludes volatile processing timestamps;
- excludes storage location;
- excludes presentation-only source fields such as `fileName`/`mediaType`;
- excludes temporary URLs;
- excludes tracing/correlation identifiers;
- excludes secrets.

The existing post-chunk projection fingerprint remains a different layer and must not be renamed conceptually into `canonicalHash`.

---

## 7. Mapping and provenance

### 7.1 Canonical mapping

`CanonicalDocumentMapper` is the compatibility boundary from canonical source structure into the current `KnowledgeDocument + SemanticUnit` model.

It:

- preserves source identity in a controlled typed/whitelisted representation;
- maps blocks into semantic units;
- preserves block IDs;
- preserves page ranges;
- preserves block-level bounding boxes where available;
- preserves/derives section path deterministically;
- continues using `UnitProvenance` as the chunk-level provenance model.

### 7.2 Chunk provenance merge

When one AkmAI chunk contains multiple canonical blocks:

- block IDs are ordered and de-duplicated;
- `pageFrom` is the minimum known page;
- `pageTo` is the maximum known page;
- block references remain traceable;
- section path follows existing deterministic chunking semantics.

### 7.3 Source metadata compatibility bridge

The runtime still uses `Map<String,Object> metadata` across `KnowledgeDocument`, chunks, projections and vectors.

The v1 implementation uses a centralized source-provenance codec/bridge to carry stable source fields through this existing pipeline. Generic metadata is not the public contract, and sensitive metadata is rejected before ingestion.

---

## 8. KnowledgeIngestionResult v1

The legacy `KnowledgeIngestionResponse(documentId, chunkCount)` remains supported for backward compatibility.

FileService-facing ingestion uses the richer `KnowledgeIngestionResult` service contract.

### 8.1 Current semantics

```json
{
  "schemaVersion": 1,
  "documentId": "doc-01K...",
  "source": {
    "type": "FILE",
    "fileId": "file-01K...",
    "sourceVersion": "17",
    "contentHash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "publication": {
    "status": "PUBLISHED",
    "generation": 42,
    "chunkCount": 137
  },
  "processing": {
    "canonicalSchemaVersion": 1,
    "canonicalHash": "...",
    "parser": "pdf-parser",
    "parserVersion": "4.2.0",
    "embeddingProfile": "..."
  }
}
```

### 8.2 Publication status

Current success statuses:

- `PUBLISHED` — durable publication confirmed;
- `REPLAYED` — an equivalent previously successful request was replayed;
- `ALREADY_PUBLISHED` — publication state proves the generation was already published.

`IN_PROGRESS`, publication ambiguity and hard failure remain explicit failure/error states rather than false success responses.

### 8.3 Publication result invariant

The result is built after `PersistenceCoordinator` / publication outcome is known.

The result exposes the actual published generation.

`chunkCount` means searchable chunks and aligns with the searchable vector-generation manifest, not all structural parent/child projections.

The legacy response continues to expose only `documentId` and searchable chunk count.

---

## 9. Idempotency and replay

Existing `Idempotency-Key` semantics remain authoritative.

The FileService contract reuses the same repository/lease/fencing flow.

Required behavior:

- same key + same canonical identity → replay successful result;
- same key + different canonical identity → `IDEMPOTENCY_KEY_REUSE`;
- current live claim → `INGESTION_IN_PROGRESS`;
- lost claim → explicit idempotency-loss error;
- crash after durable publication → reclaim/replay resolves the published generation rather than reprocessing blindly.

For FileService-facing replay, AkmAI retains or reconstructs the published `generation` and embedding profile identity. If durable publication identity cannot be established, the typed canonical path fails closed rather than inventing a success result.

Legacy persisted `response_json` remains readable for backward compatibility.

---

## 10. Retrieval SourceProvenance

Stable provenance is available as a typed retrieval concept while generic metadata remains supported internally.

Typed source provenance includes stable fields such as:

```json
{
  "sourceType": "FILE",
  "fileId": "file-01K...",
  "sourceVersion": "17",
  "fileName": "architecture.pdf",
  "mediaType": "application/pdf",
  "contentHash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "blockIds": ["b-137", "b-138"],
  "pageFrom": 37,
  "pageTo": 38,
  "sectionPath": ["Architecture", "Persistence", "PostgreSQL"]
}
```

### 10.1 Traceability invariant

For a searchable chunk derived from FileService input, AkmAI can trace:

```text
RetrievalHit
  → chunkId
  → canonical block IDs/pages
  → documentId + generation
  → fileId + sourceVersion + contentHash
```

AkmAI does not need to return storage credentials or a presigned object URL.

### 10.2 Public RAG provenance

The public `RagResponse` currently exposes canonical-block provenance (`blockId`, page range, section path and optional bounding box). Not every internal `SourceProvenance` field is automatically a public HTTP response field.

For exact public question/answer JSON, use [`../services/external-api-contracts.md`](../services/external-api-contracts.md).

---

## 11. Persistence policy for v1

The compact v1 design does not require a new dedicated source-provenance table.

Source identity currently travels through the existing projection/vector metadata path via a centralized typed codec. This preserves the current generation transaction and minimizes schema risk.

A dedicated generation-level table such as `knowledge_document_source` should be introduced only when one of the following is demonstrated:

- source-level queries require it;
- per-chunk duplication becomes operationally significant;
- retention/purge semantics require normalized source rows;
- provenance reconstruction from existing projection metadata is too expensive;
- security or governance requires explicit typed columns;
- benchmark evidence shows the model is superior without harming ingestion/publication cost.

If a new table is introduced later, it remains subordinate to `documentId + generation + accessLevel` and must not become a parallel lifecycle authority.

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

For v1 it remains optional lineage/diagnostic data and does not automatically skip processing without an explicit policy and test evidence.

The current post-chunk projection fingerprint remains valid for its existing generation/content-fencing purpose.

---

## 13. Backward compatibility

Current compatibility rules:

- existing text-ingestion HTTP endpoint remains functional;
- existing canonical ingestion remains usable;
- legacy `KnowledgeIngestionResponse` JSON remains stable;
- old idempotency replay rows remain readable;
- retrieval metadata remains available for code that still consumes it;
- typed provenance is additive;
- generation/publication semantics are not weakened.

---

## 14. Security

The following data must not be persisted in canonical identity, retrieval metadata or hashes:

- access tokens;
- authorization headers;
- passwords;
- object-storage credentials;
- presigned URLs;
- temporary query credentials.

The canonical boundary rejects sensitive metadata and signed-URL/token-like values before chunking/embedding.

AkmAI exposes stable source identity/provenance. FileService remains responsible for authorizing access to original binary content.

ACL remains an AkmAI routing boundary and part of generation/publication authority.

---

## 15. Positive cases

The implemented regression coverage includes:

1. valid canonical file-derived document → published generation;
2. source identity survives canonical mapping, chunking and retrieval;
3. one chunk formed from multiple blocks retains ordered block IDs and page range;
4. same canonical request + same idempotency key → replay without duplicate generation;
5. retry after caller loses response but publication committed → existing generation is recovered;
6. new source version → new valid publication generation;
7. operational storage changes and parse timestamps do not change canonical identity;
8. source presentation fields such as file name do not change canonical identity;
9. legacy text ingestion remains unaffected.

---

## 16. Negative and failure cases

Covered/rejected cases include:

- unsupported canonical schema version;
- empty block list;
- duplicate block IDs;
- invalid page range;
- invalid bounding box;
- malformed source content hash;
- URL used as storage object key;
- sensitive/signed-URL metadata;
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

The FileService contract does not add a second concurrency authority.

Relevant test coverage includes concurrent idempotency behavior, claim loss/recovery, ambiguous publication recovery and published-generation fencing.

---

## 18. Performance constraints

The adaptation must not materially regress existing ingestion/retrieval performance.

The current implementation deliberately avoids additional DB schema/hot-path joins for provenance. Canonical hashing is performed once per FileService canonical request, and retrieval provenance is reconstructed from metadata already carried through the existing projection/vector path.

Retrieval quality, storage decision-matrix and full CI gates were green on the implementation exact head before merge.

---

## 19. Deferred asynchronous extension

The following remains deliberately deferred:

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

- `eventId` remains distinct from HTTP idempotency key;
- Inbox dedup prevents duplicate processing side effects;
- publication event creation is transactionally coupled to publication, normally via Outbox;
- delivery is at-least-once;
- broker calls do not occur inside the publication DB transaction.

None of these are required for the current synchronous FileService knowledge contract v1.

---

## 20. Current completion status

The v1 implementation in `main` provides:

- versioned and validated `CanonicalKnowledgeDocument`;
- URL-independent source identity using `fileId/sourceVersion/contentHash`;
- deterministic `canonicalHash`;
- canonical mapping that reuses `UnitProvenance`;
- one shared ingestion/generation/publication pipeline;
- publication-aware result with real generation;
- generation-aware replay/recovery;
- backward-compatible legacy ingestion response;
- typed `SourceProvenance` on retrieval hits;
- provenance preservation through the retrieval path;
- rejection of credential/presigned-URL leakage;
- PostgreSQL/Testcontainers canonical ingestion → publication → retrieval provenance coverage.

The remaining transport distinction is explicit: `CanonicalKnowledgeDocument -> KnowledgeIngestionResult` is implemented at the service/port boundary, while the public `KnowledgeController` currently publishes only the legacy text endpoint.

Inbox/Outbox/event-bus implementation remains outside v1.

---

## 21. Current stable boundary

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

The design deliberately prefers a small number of strong contracts over a larger integration framework. Future HTTP or asynchronous transport can be added around this boundary without changing the core knowledge-processing model.
