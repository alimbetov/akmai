# FileService → AkmAI Knowledge Contract v1

**Status:** TARGET  
**Branch:** `feature/fileservice-knowledge-contract-v1`  
**Scope:** AkmAI ingestion boundary, canonical document contract, ingestion result, provenance, Inbox/Outbox integration contract, contract versioning and migration.  
**Out of scope:** implementation of FileService itself, RustFS deployment, broker selection, parser engine implementation, UI.

---

## 1. Goal

Prepare AkmAI for integration with a separate FileService that:

1. accepts uploaded files;
2. stores the immutable original in RustFS or another object storage;
3. parses files asynchronously, potentially in scheduled/night batches;
4. produces a deterministic structured representation;
5. submits that representation to AkmAI;
6. receives a stable processing result and/or publication event;
7. keeps source identity and provenance traceable from an AkmAI retrieval hit back to the original file.

The central architectural decision is that FileService does **not** send pre-chunked RAG data. It sends a versioned canonical source representation. AkmAI remains the owner of semantic normalization, RAG chunking, embedding, retrieval indexing, graph construction, generation publication and retrieval policy.

---

## 2. Target architecture

```text
Client
  │
  ▼
FileService
  │
  ├── validate / authorize / hash
  ├── persist file metadata
  └── store immutable original
              │
              ▼
            RustFS
              │
              ▼
        async parse job
              │
              ▼
        Parser Worker
              │
              ▼
   CanonicalKnowledgeDocument
              │
              ├── optional canonical artifact in object storage
              │
              ▼
         FileService Outbox
              │
              ▼
            Broker
              │
              ▼
         AkmAI Inbox
              │
              ▼
       Admission / Dedup
              │
              ▼
    Canonical preparation
              │
              ▼
   Semantic normalization
              │
              ▼
       Chunk planning
              │
              ▼
         Enrichment
              │
              ▼
         Embedding
              │
              ▼
 Structural / semantic graph
              │
              ▼
     Generation publication
              │
              ▼
         AkmAI Outbox
              │
              ▼
 KnowledgeDocumentPublished
```

---

## 3. Bounded-context ownership

### 3.1 FileService owns

- binary file upload;
- upload validation;
- object-storage persistence;
- stable `fileId` allocation;
- immutable source-file identity;
- media type detection;
- source byte hash;
- parser selection;
- parser execution and retries;
- physical page/layout extraction;
- structural extraction: heading, paragraph, table, list, code, footnote, image text;
- parser version;
- storage references;
- FileService-side lifecycle/status.

### 3.2 AkmAI owns

- ingestion admission and idempotency;
- canonical contract validation;
- semantic normalization;
- semantic chunk planning;
- chunk identity;
- embedding profile selection;
- embedding generation;
- lexical retrieval representation;
- graph representation;
- generation lifecycle;
- atomic publication;
- retrieval/reranking;
- adaptive graph learning;
- RAG learning signals;
- agent-facing retrieval output.

### 3.3 Explicitly forbidden coupling

FileService MUST NOT own or prescribe:

- final RAG chunk size;
- RAG overlap strategy;
- embedding model/profile;
- ANN index structure;
- graph learning edge admission;
- retrieval ranking;
- reranking;
- query policy.

AkmAI MUST NOT require FileService to expose a permanent public RustFS URL.

---

## 4. Contract model

The integration SHALL use four different contracts with different semantics:

1. `CanonicalKnowledgeDocument` — inbound source representation;
2. `KnowledgeIngestionResult` — synchronous/command result;
3. `KnowledgeDocumentPublished` — asynchronous durable publication event;
4. `RetrievalHit` + typed `SourceProvenance` — retrieval/agent output.

One DTO MUST NOT be reused for all four purposes.

---

## 5. CanonicalKnowledgeDocument v1

### 5.1 Purpose

`CanonicalKnowledgeDocument` is the stable intermediate representation between source parsing and RAG processing.

It SHALL preserve source structure and provenance without embedding RAG-specific decisions.

### 5.2 Required top-level fields

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
      "versionId": "optional-provider-version"
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

### 5.3 Identity requirements

`documentId` is the knowledge-document identity visible to AkmAI.

`fileId` is the stable FileService identity of the physical source artifact.

`sourceVersion` identifies the version of the source known to FileService.

`contentHash` represents source content identity. It MUST NOT be a presigned URL and MUST NOT depend on temporary access credentials.

A storage URL MUST NOT be treated as source identity.

### 5.4 Storage reference

The canonical contract SHOULD use a stable storage reference:

```json
{
  "provider": "rustfs",
  "bucket": "knowledge-raw",
  "objectKey": "...",
  "versionId": "..."
}
```

A presigned URL MAY be produced operationally when content must be downloaded, but it MUST NOT be persisted as the canonical durable identity.

### 5.5 Blocks

Supported v1 block types:

- `HEADING`;
- `PARAGRAPH`;
- `TABLE`;
- `LIST`;
- `CODE`;
- `FOOTNOTE`;
- `IMAGE_TEXT`.

Each block MUST have:

- stable `blockId` within a canonical document version;
- block type;
- textual representation;
- optional heading level;
- optional `pageFrom` / `pageTo`;
- optional structured section path;
- optional bounding box.

Recommended representation:

```json
{
  "blockId": "b-137",
  "type": "PARAGRAPH",
  "text": "PostgreSQL is the durable authority for graph state.",
  "pageFrom": 37,
  "pageTo": 37,
  "sectionPath": ["Architecture", "Persistence", "PostgreSQL"],
  "boundingBox": {
    "x": 78.2,
    "y": 192.5,
    "width": 430.0,
    "height": 84.0
  }
}
```

The current scalar `sectionPath` representation in AkmAI SHOULD be migrated to a typed ordered path or an equivalent immutable value object. Backward compatibility MAY be maintained during migration.

### 5.6 Canonical contract validation

AkmAI MUST reject a canonical document before expensive processing when any of the following is true:

- unsupported `schemaVersion`;
- blank `documentId`;
- blank `version` / `sourceVersion`;
- missing source identity;
- blank/invalid language;
- null domain;
- non-positive access level;
- empty block list;
- duplicate block IDs within one document version;
- invalid page ranges;
- invalid bounding boxes;
- unsupported block type;
- malformed storage reference when storage reference is present.

Validation failures MUST NOT allocate a generation and MUST NOT execute embedding.

---

## 6. Determinism and fingerprints

Three identities MUST remain conceptually separate:

### 6.1 Request identity

Examples:

- HTTP `Idempotency-Key`;
- command ID.

Protects against command retry.

### 6.2 Event identity

Example:

- `eventId` from broker/outbox.

Protects against duplicate delivery.

### 6.3 Content identity

Example:

- source `contentHash`;
- optional normalized `canonicalHash`.

Protects against duplicate content processing.

AkmAI MUST NOT treat these as interchangeable values.

### 6.4 Canonical hash

A normalized canonical hash SHOULD be introduced for structured-content identity.

It MUST be calculated over deterministic canonical serialization and MUST exclude volatile fields such as:

- processing timestamps;
- temporary URLs;
- transport tracing IDs.

### 6.5 Processing fingerprint

A processing fingerprint SHOULD be available for future reprocessing decisions:

```text
SHA-256(
  canonicalHash
  + canonicalSchemaVersion
  + semanticNormalizerVersion
  + chunkerVersion
  + embeddingProfile
)
```

Expected behavior:

- same canonical content + same processing fingerprint → reprocessing MAY be skipped/replayed;
- same content + different chunker version → rechunk/reembed MAY be required;
- same content + different embedding profile → reembed is required;
- different canonical content → new processing attempt/generation is required.

---

## 7. Ingestion admission / Inbox

### 7.1 Requirement

AkmAI SHALL preserve the current idempotency semantics for direct HTTP ingestion and SHALL be ready to accept an external event identity for asynchronous FileService integration.

### 7.2 Required admission identities

The admission layer SHOULD expose a transport-neutral model containing, where applicable:

- request identity;
- event identity;
- document identity;
- source/file identity;
- content identity;
- request fingerprint.

### 7.3 Duplicate-event behavior

When the same `eventId` is delivered repeatedly:

- the first accepted delivery may process;
- subsequent deliveries MUST NOT duplicate chunking, embedding, generation publication or graph side effects;
- the duplicate SHALL be acknowledged/replayed according to transport semantics.

### 7.4 Existing HTTP idempotency

Existing `Idempotency-Key` behavior MUST remain backward compatible unless an explicit API major-version migration is introduced.

---

## 8. KnowledgeIngestionResult v1

The current `KnowledgeIngestionResponse(documentId, chunkCount)` is insufficient for FileService integration.

Introduce a richer result model while preserving a compatibility strategy.

### 8.1 Required fields

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
    "semanticNormalizerVersion": "semantic-v1",
    "chunkerVersion": "hierarchical-v2",
    "embeddingProfile": "bge-m3-v2"
  }
}
```

### 8.2 Publication status

The external result MUST distinguish at least:

- `PUBLISHED`;
- `REPLAYED`;
- `SUPERSEDED` where externally relevant;
- `IN_PROGRESS` through the existing conflict/error model rather than pretending success;
- unknown publication outcome through the existing explicit ambiguity error model.

`PUBLISHED` MUST mean that AkmAI has durable evidence that the generation committed.

### 8.3 Generation

Successful publication result MUST expose generation identity.

A generation allocated but not successfully published MUST NOT be returned as a successful published result.

### 8.4 Compatibility

Preferred migration strategy:

- introduce `KnowledgeIngestionResult` internally first;
- map existing endpoint shape where backward compatibility is required;
- add the richer contract either additively or behind a versioned endpoint/representation;
- do not silently change old JSON semantics if external consumers already depend on them.

---

## 9. KnowledgeDocumentPublished event v1

### 9.1 Purpose

Durably notify FileService and other consumers that knowledge is available for retrieval.

### 9.2 Event shape

```json
{
  "schemaVersion": 1,
  "eventId": "evt-01K...",
  "eventType": "KnowledgeDocumentPublished",
  "occurredAt": "2026-10-09T12:00:00Z",
  "documentId": "doc-01K...",
  "source": {
    "type": "FILE",
    "fileId": "file-01K...",
    "sourceVersion": "17",
    "contentHash": "sha256:..."
  },
  "generation": 42,
  "chunkCount": 137
}
```

### 9.3 Outbox requirement

When asynchronous event delivery is implemented, creation of the publication event MUST be transactionally coupled to the durable publication state through an Outbox pattern or equivalent atomic mechanism.

A crash after publication commit MUST NOT permanently lose the publication event.

A retry MUST NOT create logically duplicated publication events for the same publication identity.

### 9.4 Event semantics

Consumers MUST assume at-least-once delivery.

Event `eventId` MUST therefore be unique and suitable for Inbox deduplication.

---

## 10. Typed SourceProvenance for retrieval

The current generic `Map<String,Object> metadata` remains useful for extensibility, but stable provenance MUST NOT live only in untyped metadata.

Introduce a typed provenance model.

### 10.1 Minimum typed fields

```json
{
  "fileId": "file-01K...",
  "sourceVersion": "17",
  "fileName": "architecture.pdf",
  "blockIds": ["b-137", "b-138"],
  "pageFrom": 37,
  "pageTo": 38,
  "sectionPath": ["Architecture", "Persistence", "PostgreSQL"],
  "contentHash": "sha256:..."
}
```

### 10.2 Retrieval traceability invariant

For each persisted searchable chunk produced from a canonical document, AkmAI SHOULD be able to trace:

```text
RetrievalHit
  → chunkId
  → canonical block IDs
  → canonical document version
  → fileId/source identity
  → original file reference
```

### 10.3 Metadata policy

Typed fields:

- routing identity;
- source identity;
- generation;
- source version;
- pages;
- section path;
- canonical block IDs;
- durable content fingerprint.

Generic `metadata` remains for:

- experimental semantic labels;
- extracted entities;
- future enrichment attributes;
- non-contractual diagnostic hints.

Security-sensitive internal storage credentials MUST NOT be copied into retrieval metadata.

---

## 11. URL and object-storage rules

### 11.1 Durable identity

A full RustFS URL MUST NOT be the durable identity of a source.

Use:

- `fileId`;
- storage provider;
- bucket;
- object key;
- optional object version;
- content hash.

### 11.2 Presigned URLs

Presigned URLs:

- MAY be generated by FileService on demand;
- MUST have bounded TTL;
- MUST NOT be stored as a permanent canonical field;
- MUST NOT be included in content fingerprints;
- MUST NOT be used for idempotency identity.

### 11.3 Retrieval output

AkmAI SHOULD return a stable source reference, not an object-storage credential. A caller that needs source content SHOULD resolve it through FileService.

---

## 12. Processing/version fields

AkmAI SHOULD make processing lineage explicit.

Minimum future processing lineage:

- canonical schema version;
- parser version, when supplied by FileService;
- semantic normalizer version;
- chunker version;
- embedding profile/version;
- optional graph projection version.

These versions MUST be treated as processing metadata, not source meaning.

---

## 13. Lifecycle and state model

Suggested cross-service state flow:

### FileService

```text
UPLOADED
  ↓
STORED
  ↓
QUEUED_FOR_PARSE
  ↓
PARSING
  ↓
PARSED
  ↓
SUBMITTED_TO_KNOWLEDGE
  ↓
KNOWLEDGE_READY
```

Failure states:

```text
UPLOAD_FAILED
PARSE_FAILED
SUBMISSION_FAILED
```

### AkmAI

Existing generation/idempotency lifecycle remains authoritative for knowledge publication.

FileService status MUST NOT be treated as proof that AkmAI generation publication succeeded.

Only AkmAI result/event with durable publication evidence can move FileService to `KNOWLEDGE_READY`.

---

## 14. Positive business cases

### 14.1 New PDF

1. FileService stores source in RustFS.
2. FileService computes source hash.
3. Parser produces canonical blocks.
4. FileService emits/submits canonical document.
5. AkmAI validates contract.
6. Admission accepts new identity.
7. AkmAI creates semantic chunks.
8. AkmAI embeds and builds retrieval representation.
9. AkmAI publishes a generation atomically.
10. Result/event exposes published generation and source identity.
11. Retrieval hits contain typed provenance back to file/page/section.

### 14.2 Duplicate event delivery

1. `KnowledgeDocumentReady(eventId=X)` is delivered.
2. AkmAI processes and publishes.
3. Same `eventId=X` is delivered again.
4. AkmAI returns replay/ack behavior.
5. No duplicate embeddings or publication side effects occur.

### 14.3 Same source, retry of HTTP request

Existing idempotency replay behavior remains unchanged.

### 14.4 Parser version changes without source change

FileService may produce a new canonical representation/version. AkmAI uses canonical/processing fingerprints to decide whether a new processing generation is necessary according to explicit policy.

### 14.5 Chunker or embedding profile changes

AkmAI can reprocess the same canonical source without requiring re-upload of the original binary.

---

## 15. Negative and failure cases

### 15.1 Invalid canonical schema

Reject before chunking and embedding.

### 15.2 Missing file/source identity

Reject source contract as invalid for FileService integration.

### 15.3 Expired presigned URL

Must not invalidate source identity. File content access is resolved again through FileService/storage reference.

### 15.4 FileService sends duplicate event

AkmAI Inbox/admission deduplicates by event identity.

### 15.5 Same request identity with different fingerprint

Keep current `IDEMPOTENCY_KEY_REUSE` semantics.

### 15.6 Embedding failure

No publication event may claim success. Failed generation is not exposed as published.

### 15.7 Publication call throws after commit

Existing publication ambiguity resolver determines durable state. If committed, result/event may be published as success. If unknown, do not guess failure and do not emit a false success event.

### 15.8 Outbox dispatcher unavailable

Knowledge publication remains durable. Event remains pending in outbox and is delivered later.

### 15.9 Broker redelivery

Consumer deduplicates with Inbox/event identity.

### 15.10 Source deleted from FileService after knowledge publication

Policy must be explicit. Retrieval data MUST NOT silently pretend source is still downloadable. Future implementation should support source availability state or document withdrawal workflow; this is not required for the first contract patch.

### 15.11 Access-level mismatch

Request/source authority rules remain explicit. No arbitrary metadata field may override protected access-control fields.

---

## 16. Security requirements

- never persist access tokens or presigned query signatures into canonical/retrieval metadata;
- protected fields (`accessLevel`, source identity, tenant identity when introduced) MUST NOT be overrideable by arbitrary metadata;
- do not expose internal RustFS credentials in API responses;
- source-reference authorization remains FileService responsibility;
- AkmAI retrieval authorization remains based on AkmAI access-level semantics;
- contract logs SHOULD avoid dumping entire document content at info/error level;
- content hashes are identifiers, not authorization proofs.

---

## 17. Observability requirements

Add/extend metrics and tracing dimensions without creating unbounded-cardinality labels.

Required logical observations:

- canonical validation success/failure;
- ingestion source type;
- replay vs new processing;
- duplicate event count;
- publication result;
- outbox pending/failure count when implemented;
- provenance-mapping failures;
- canonical schema version distribution;
- parser/chunker/embedding processing lineage in structured logs/traces, not high-cardinality metrics.

Trace/correlation SHOULD carry:

- request ID;
- event ID where applicable;
- document ID;
- file ID;
- generation once allocated.

---

## 18. Required AkmAI code changes

### P0 — contract foundation

1. Introduce explicit contract `schemaVersion`.
2. Evolve `CanonicalDocument` toward `CanonicalKnowledgeDocument` semantics without breaking current ingestion unnecessarily.
3. Introduce typed source descriptor / file reference.
4. Introduce typed ordered section path.
5. Add source/content identity fields required for FileService.
6. Strengthen canonical validation.
7. Preserve deterministic canonical fingerprinting.

### P0 — ingestion result

8. Introduce `KnowledgeIngestionResult` containing publication status, generation, source identity and processing lineage.
9. Preserve compatibility for existing `KnowledgeIngestionResponse` consumers through mapping/versioning.
10. Ensure replay returns the same durable logical result.

### P0 — retrieval provenance

11. Introduce typed `SourceProvenance`.
12. Propagate block/page/section/source identity from canonical blocks into persisted chunks.
13. Extend retrieval assembly so `RetrievalHit` exposes typed provenance.
14. Keep generic metadata for extensibility, not core provenance.

### P1 — async integration boundary

15. Introduce transport-neutral event identity in ingestion admission.
16. Add Inbox storage/claim semantics when broker integration is enabled.
17. Define `KnowledgeDocumentPublished` event contract.
18. Add durable AkmAI Outbox for publication events when asynchronous delivery is enabled.

### P1 — processing lineage

19. Track canonical schema version.
20. Track parser version supplied by FileService.
21. Track semantic normalizer/chunker version.
22. Track embedding profile/version.
23. Introduce canonical/processing fingerprint where required for safe reprocessing policy.

### P2 — future lifecycle enhancements

24. Source withdrawal/deletion propagation.
25. Reparse/reindex orchestration API.
26. Source availability indicator.
27. Multi-artifact source packages if needed for tables/images/layout.

---

## 19. Database/migration expectations

Exact schema is implementation-specific, but the implementation SHALL support durable storage of:

- source/file identity;
- source version;
- content hash;
- canonical schema version;
- processing lineage needed for reproducibility;
- provenance mapping required by retrieval;
- Inbox event identity where async consumption is enabled;
- Outbox publication identity where async publication is enabled.

Migration requirements:

- additive migrations preferred;
- no destructive rewrite of existing published generations;
- old rows must remain readable;
- nullability/default strategy must be explicitly documented for legacy documents;
- indexes must exist for new lookup/dedup keys;
- unique constraints must enforce event/idempotency identities where required.

---

## 20. API compatibility policy

The implementation MUST document whether each changed contract is:

- internal only;
- additive backward-compatible;
- versioned breaking change.

No silent semantic change is allowed for an already public field.

`Map<String,Object> metadata` MUST NOT become a hidden substitute for contract versioning.

---

## 21. Test specification

### 21.1 Contract tests

- valid canonical document accepted;
- unsupported schema rejected;
- missing file identity rejected for FileService source;
- duplicate block IDs rejected;
- invalid page range rejected;
- invalid bounding box rejected;
- section path order preserved;
- arbitrary metadata cannot override protected source/access fields.

### 21.2 Idempotency / Inbox tests

- same request key + same fingerprint → replay;
- same request key + different fingerprint → reject reuse;
- same event ID delivered twice → one set of side effects;
- concurrent duplicate event delivery → one owner/one publication;
- lost claim/fencing behavior remains safe.

### 21.3 Publication tests

- published generation returned with generation ID;
- embedding failure emits no success result/event;
- publication ambiguity resolved from durable state;
- unknown publication outcome emits no false failure/success event;
- superseded generation not reported as newly published.

### 21.4 Provenance tests

- canonical block IDs survive into persisted chunk provenance;
- pages survive;
- ordered section path survives;
- retrieval hit exposes typed provenance;
- provenance does not contain presigned URL/token;
- retrieval hit can be traced to document/file identity.

### 21.5 Outbox tests

When implemented:

- publication + outbox write are atomic;
- dispatcher failure leaves event retryable;
- repeated dispatch does not change event identity;
- consumer can deduplicate by event ID.

### 21.6 Migration tests

- pre-migration documents remain retrievable;
- pre-migration idempotency replay still works;
- old API response compatibility is preserved according to chosen strategy;
- mixed old/new corpus does not break retrieval.

### 21.7 Performance tests

Measure before/after:

- ingestion p50/p95/p99;
- DB round trips;
- extra row/storage cost per chunk for provenance;
- serialization cost for canonical documents;
- retrieval latency impact of typed provenance;
- duplicate-event fast-path latency.

No P0 contract work should introduce a material retrieval latency regression.

---

## 22. Acceptance criteria

The work is complete only when all of the following are true:

1. AkmAI accepts a versioned canonical file-derived document without requiring pre-chunked RAG input.
2. Stable FileService identity is preserved independently from RustFS URL.
3. Successful ingestion exposes durable publication generation.
4. Existing idempotency behavior remains correct.
5. Duplicate asynchronous event delivery cannot duplicate ingestion side effects once Inbox integration is enabled.
6. Retrieval hits expose typed source provenance.
7. A retrieval hit can be traced to canonical block(s), source document version and file identity.
8. Presigned URLs/tokens are absent from durable canonical/retrieval identity.
9. Publication events have schema version and unique event identity.
10. Outbox semantics prevent loss of a committed publication event once async publication is enabled.
11. Positive, negative, concurrency and failure-injection tests cover all new invariants.
12. Existing test suite remains green.
13. Documentation is updated together with code.
14. DB migrations are backward compatible and indexed.
15. No duplicated alternative ingestion pipeline is introduced; text and canonical ingestion converge on the same downstream RAG pipeline.

---

## 23. Implementation order

### Phase A — P0 contract baseline

1. Freeze contract names and versioning policy.
2. Add typed source identity and provenance value objects.
3. Evolve canonical document model.
4. Extend canonical mapper.
5. Extend chunk provenance propagation.
6. Introduce richer ingestion result.
7. Extend retrieval hit/output.
8. Add DB migrations where required.
9. Add tests.
10. Update service documentation.

### Phase B — async reliability

1. Add event identity abstraction.
2. Add Inbox repository/claiming semantics.
3. Add publication event contract.
4. Add Outbox persistence.
5. Add dispatcher boundary.
6. Add failure-injection/concurrency tests.

### Phase C — reproducibility/reprocessing

1. Canonical hash.
2. Processing fingerprint.
3. Parser/chunker/embedding lineage policy.
4. Explicit reprocess/reindex rules.

Do not implement Phase C heuristics before Phase A contract semantics are stable.

---

## 24. Non-goals for this branch specification

This specification does not require:

- choosing Kafka vs RabbitMQ vs another broker;
- implementing FileService;
- configuring RustFS;
- implementing OCR;
- moving binary files through AkmAI;
- embedding JSON field names as retrieval text;
- making LLM parsing mandatory;
- changing adaptive-graph learning semantics;
- replacing generation publication architecture.

---

## 25. Architectural invariants

1. Original file and knowledge representation are different artifacts.
2. URL is a locator, not durable identity.
3. FileService parses source structure; AkmAI owns RAG semantics.
4. Canonical JSON is an intermediate representation, not the embedding text format.
5. Request identity, event identity and content identity are separate concepts.
6. Published generation is the durable authority for knowledge availability.
7. Retrieval provenance is first-class typed data.
8. Generic metadata is not a substitute for typed core contracts.
9. Async delivery is assumed at-least-once; consumers must be idempotent.
10. Source provenance must remain recoverable after chunking.
11. Contract schemas are explicitly versioned.
12. Reprocessing policy must be deterministic and explainable.

---

## 26. Definition of Done

Before merging an implementation based on this specification:

- code review confirms bounded-context ownership;
- all contract records are documented;
- OpenAPI/API examples are updated where applicable;
- DB migration and rollback/compatibility notes exist;
- unit tests cover validation/mapping;
- integration tests cover persistence/publication;
- failure-injection tests cover duplicate delivery, lost ownership and ambiguous publication;
- retrieval tests verify provenance;
- benchmark/regression check is recorded;
- no dead compatibility scaffolding remains beyond explicitly documented temporary adapters;
- documentation reflects the actual implementation, not the intended design.

---

## 27. Final decision

AkmAI should evolve from a text-centric ingestion contract to a versioned knowledge-ingestion boundary built around `CanonicalKnowledgeDocument`.

The target interface is:

```text
FileService
    │
    │ CanonicalKnowledgeDocument
    ▼
AkmAI
    ├── KnowledgeIngestionResult
    ├── KnowledgeDocumentPublished
    └── RetrievalHit + SourceProvenance
```

The implementation must extend the existing ingestion/generation architecture rather than create a parallel pipeline.
