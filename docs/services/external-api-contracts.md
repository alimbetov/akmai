# AkmAI external and integration contracts

**Status:** CURRENT  
**Authority:** executable DTO/controller/service contracts in `main`  
**Scope:** knowledge ingestion, FileService integration boundary, RAG question/answer API, source provenance, feedback and common error envelope.

This document answers two practical integration questions:

1. **What does AkmAI expect from an upstream system?**
2. **What question can a client send to AkmAI and what answer shape will it receive?**

The examples below are contract examples. Example business text is illustrative; field names, required semantics and endpoint availability describe the current code.

---

## 1. Contract map

```text
FileService / upstream source
        │
        │ CanonicalKnowledgeDocument v1
        │ service-level contract
        ▼
KnowledgeIngestionPort.addCanonicalKnowledge(...)
        │
        ▼
KnowledgeIngestionResult v1

Legacy HTTP client
        │
        │ POST /api/knowledge/text
        ▼
KnowledgeIngestionResponse

RAG client
        │
        │ POST /api/rag/ask
        │ QuestionRequest
        ▼
RagResponse
        ├── requestId
        ├── answer
        └── sources[]
             └── provenance.canonicalBlocks[]
```

### Availability rule

The current `KnowledgeController` exposes only:

```text
POST /api/knowledge/text
```

`CanonicalKnowledgeDocument -> KnowledgeIngestionResult` is implemented in the service/port layer and is the integration contract expected from FileService, but there is currently **no dedicated public HTTP endpoint for this canonical v1 contract**.

Do not integrate FileService by inventing a REST path that is not present in the controller. If/when a canonical HTTP endpoint is added, it must be a thin adapter over the existing `KnowledgeIngestionPort.addCanonicalKnowledge(...)` path and must not create a second ingestion pipeline.

---

## 2. Legacy text ingestion HTTP contract

### Endpoint

```http
POST /api/knowledge/text
Content-Type: application/json
Idempotency-Key: <optional stable retry key>
```

### Request

```json
{
  "documentId": "policy-2026-001",
  "title": "Information Security Policy",
  "text": "Full plain-text document content...",
  "source": "fileservice://file-01KXYZ/version/7",
  "language": "en",
  "domain": "TECHNICAL",
  "accessLevel": 1,
  "metadata": {
    "tenant": "example"
  }
}
```

Required semantics:

| Field | Required | Meaning |
|---|---:|---|
| `documentId` | yes | Stable AkmAI document identity. |
| `title` | yes | Human-readable document title. |
| `text` | yes | Plain text to be chunked by AkmAI. |
| `source` | yes | Source descriptor for the legacy text contract. |
| `language` | yes | Supported language/alias; AkmAI canonicalizes it. |
| `domain` | yes | Knowledge domain enum. |
| `accessLevel` | yes | Positive ACL/routing level. |
| `metadata` | no | Additional metadata. Protected authoritative fields are not controlled by arbitrary metadata. |

### Success response

HTTP `201 Created`:

```json
{
  "documentId": "policy-2026-001",
  "chunkCount": 23
}
```

`chunkCount` means the number of **searchable chunks**, not the number of all structural parent/child projections.

### Idempotency

If `Idempotency-Key` is supplied:

- the key is bound to the request fingerprint;
- a successful retry replays the stored result without re-running chunking, embedding or publication;
- reuse of the key for different content is rejected;
- an already-running claim is rejected as `INGESTION_IN_PROGRESS`.

---

## 3. FileService -> AkmAI canonical contract v1

This is the preferred contract for parsed files.

### Responsibility split

FileService owns:

- upload/download lifecycle;
- file identity and source version;
- source byte/content hash;
- parsing/OCR/extraction;
- stable RustFS object reference;
- source-layout blocks, pages and bounding boxes.

AkmAI owns:

- canonical validation;
- canonical hash;
- RAG chunking;
- embeddings;
- generation allocation and publication;
- retrieval projections;
- retrieval policy;
- final source provenance returned with answers.

**FileService must not send final RAG chunks.** Canonical blocks are source structure, not retrieval chunks.

### Expected `CanonicalKnowledgeDocument`

```json
{
  "schemaVersion": 1,
  "documentId": "doc-01KXYZ",
  "version": "7",
  "title": "Architecture Specification",
  "language": "en",
  "domain": "TECHNICAL",
  "accessLevel": 1,
  "source": {
    "type": "FILE",
    "fileId": "file-01KXYZ",
    "sourceVersion": "7",
    "fileName": "architecture.pdf",
    "mediaType": "application/pdf",
    "contentHash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "storage": {
      "provider": "rustfs",
      "bucket": "knowledge",
      "objectKey": "files/file-01KXYZ/v7/architecture.pdf",
      "versionId": null
    }
  },
  "processing": {
    "parser": "pdf-parser",
    "parserVersion": "1.0.0",
    "parsedAt": "2026-10-09T12:00:00Z"
  },
  "blocks": [
    {
      "blockId": "b-001",
      "type": "HEADING",
      "text": "Persistence",
      "headingLevel": 2,
      "pageFrom": 37,
      "pageTo": 37,
      "sectionPath": ["Architecture", "Persistence"],
      "boundingBox": null
    },
    {
      "blockId": "b-002",
      "type": "PARAGRAPH",
      "text": "PostgreSQL is the durable authority for publication state.",
      "headingLevel": null,
      "pageFrom": 37,
      "pageTo": 38,
      "sectionPath": ["Architecture", "Persistence"],
      "boundingBox": {
        "x": 42.0,
        "y": 100.0,
        "width": 480.0,
        "height": 64.0
      }
    }
  ],
  "metadata": {
    "tenant": "example"
  }
}
```

### Required identity rules

```text
source identity          = fileId + sourceVersion
source content identity  = contentHash
canonical identity       = canonicalHash
publication identity     = documentId + generation + accessLevel
storage location         = provider + bucket + objectKey [+ versionId]
```

These identities are not interchangeable.

In particular:

- a RustFS/presigned URL is **not** source identity;
- `objectKey` must be a stable object key, not `http://...` or `https://...`;
- `contentHash` must be `sha256:<64 hex characters>`;
- `version` must equal `source.sourceVersion`;
- block IDs must be unique within the canonical document version;
- `accessLevel` must be positive;
- page ranges must be positive and ordered;
- sensitive credentials, authorization tokens and signed URLs are rejected from generic metadata.

Supported canonical block types:

```text
HEADING
PARAGRAPH
TABLE
LIST
CODE
FOOTNOTE
IMAGE_TEXT
```

### Expected FileService-facing result

On successful publication the service-level contract returns:

```json
{
  "schemaVersion": 1,
  "documentId": "doc-01KXYZ",
  "source": {
    "type": "FILE",
    "fileId": "file-01KXYZ",
    "sourceVersion": "7",
    "contentHash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "publication": {
    "status": "PUBLISHED",
    "generation": 42,
    "chunkCount": 23
  },
  "processing": {
    "canonicalSchemaVersion": 1,
    "canonicalHash": "<sha256 canonical identity>",
    "parser": "pdf-parser",
    "parserVersion": "1.0.0",
    "embeddingProfile": "<active embedding profile id>"
  }
}
```

Allowed publication statuses:

- `PUBLISHED` — this invocation published the generation;
- `REPLAYED` — an idempotent retry returned the already committed publication identity;
- `ALREADY_PUBLISHED` — durable publication state proves the generation is already published.

A success result must contain a positive real `generation` and positive searchable `chunkCount`.

---

## 4. RAG question contract

### Endpoint

```http
POST /api/rag/ask
Content-Type: application/json
```

### Request

```json
{
  "question": "What publication state is authoritative for the current document generation?",
  "accessLevels": [1]
}
```

Contract:

| Field | Required | Meaning |
|---|---:|---|
| `question` | yes | Non-blank natural-language question. |
| `accessLevels` | yes | Non-empty set of positive ACL levels; maximum 256 values. |

The client does not choose vector/lexical/identifier/graph lanes. Retrieval planning remains an AkmAI responsibility.

### What kinds of questions are expected

Typical supported question shapes include:

- **fact lookup** — "What retention period is specified for audit records?";
- **exact identifier lookup** — "What does contract KZ-2026-001847 require?";
- **section/policy lookup** — "What are the publication requirements in the Persistence section?";
- **cross-reference question** — "What exception is referenced by Article 25?";
- **comparison within available evidence** — "How do the requirements for staging and published generations differ?";
- **procedural question** — "What must happen before a generation becomes retrieval-visible?";
- **multi-part question** — "What evidence is required, and what exceptions apply?".

The request should contain the user's actual information need, not retrieval implementation instructions such as "use vector search" or "search graph first".

---

## 5. RAG answer contract

### Response shape

```json
{
  "requestId": "rag-01KXYZ",
  "answer": "The current generation becomes retrieval-visible only after publication completes and lifecycle authority points to that published generation.",
  "sources": [
    {
      "number": 1,
      "documentId": "doc-01KXYZ",
      "chunkId": "chunk-...",
      "source": "Architecture Specification",
      "language": "en",
      "sectionPath": "Architecture > Persistence",
      "page": "37-38",
      "provenance": {
        "canonicalBlocks": [
          {
            "blockId": "b-002",
            "pageFrom": 37,
            "pageTo": 38,
            "sectionPath": "Architecture > Persistence",
            "boundingBox": {
              "x": 42.0,
              "y": 100.0,
              "width": 480.0,
              "height": 64.0
            }
          }
        ]
      }
    }
  ]
}
```

### Response semantics

`requestId`
: Correlation identity for this RAG execution. Store it if feedback or operational investigation may be required.

`answer`
: Final generated/grounded answer. Its exact prose is not a stable API field value; clients must not parse business data from fixed sentence templates.

`sources[]`
: Evidence selected for the final answer. Source numbering is intended for citation/reference presentation.

`documentId` / `chunkId`
: AkmAI retrieval identities. They are not RustFS object identities.

`provenance.canonicalBlocks[]`
: Trace from the selected retrieval chunk back to canonical source blocks. When source layout exists it may include original page range, section path and bounding box.

### Important answer rule

The stable contract is **answer + evidence**, not answer text alone.

Clients that need auditability should persist at least:

```text
requestId
answer
sources[].documentId
sources[].chunkId
sources[].provenance
```

Do not persist or reconstruct a source citation from a temporary storage URL.

---

## 6. Question -> answer examples

These examples describe expected interaction patterns; answer wording is illustrative.

### Example A — exact document identifier

Question:

```json
{
  "question": "What monitoring is required for contract KZ-2026-001847?",
  "accessLevels": [1, 2]
}
```

Expected response behavior:

- exact identifier evidence should outrank unrelated semantic similarity;
- the answer should be limited to evidence visible to the resolved ACL scope;
- `sources` should identify the selected document/chunks;
- canonical provenance should be returned when available.

### Example B — source section question

Question:

```json
{
  "question": "What is required before a generation becomes visible to retrieval?",
  "accessLevels": [1]
}
```

Illustrative answer:

```text
A generation must be completely persisted and atomically published before the lifecycle pointer makes it retrieval-visible. Partial or staging generations must not be returned as current evidence.
```

The client should use `sources[]` to show where this statement came from rather than treating the prose alone as proof.

### Example C — insufficient/unsafe evidence

Question:

```json
{
  "question": "What does the policy say about a requirement that is not present in the accessible corpus?",
  "accessLevels": [1]
}
```

Expected behavior:

- AkmAI must not manufacture inaccessible evidence;
- ACL remains a pre-routing boundary;
- an answer may be conservative/abstaining when the available context does not support a grounded answer;
- clients must not assume `sources` is non-empty for every conceivable question.

The exact abstention sentence is not a versioned API constant.

---

## 7. Source provenance contract

For file-derived knowledge, the internal typed source provenance includes stable source identity such as:

```text
fileId
sourceVersion
fileName
contentHash
blockIds
pageFrom
pageTo
sectionPath
```

The public RAG response currently exposes canonical block provenance through `RagResponse.Source.provenance.canonicalBlocks`.

Do not expect the public `RagResponse` to expose every internal `SourceProvenance` field unless the DTO is explicitly extended. The public contract must be documented from `RagResponse`, not inferred from internal retrieval objects.

---

## 8. Feedback contract

### Endpoint

```http
POST /api/rag/feedback
Content-Type: application/json
Idempotency-Key: <required>
```

Feedback is tied to a prior `requestId`. The idempotency key protects retry of the feedback command.

The feedback endpoint returns HTTP `202 Accepted` with no response body on successful acceptance.

---

## 9. Common error envelope

API failures use the common error shape:

```json
{
  "code": "INGESTION_IN_PROGRESS",
  "message": "An ingestion with this Idempotency-Key is still in progress",
  "requestId": "req-...",
  "status": 409,
  "details": {
    "retryAfterSeconds": 12
  }
}
```

Envelope fields:

```text
code      stable machine-oriented error category
message   human-readable explanation
requestId correlation identifier
status    HTTP status code
details   structured error-specific metadata
```

Clients should branch on `code`/HTTP status rather than matching human-readable `message` text.

---

## 10. Integration rules that must not drift

1. FileService sends source structure, not final RAG chunks.
2. RustFS URL/presigned URL is never document identity.
3. `fileId + sourceVersion` is source identity; `contentHash` is source-content identity.
4. AkmAI owns canonical hashing, chunking, embedding, generation and publication.
5. Legacy text ingestion and canonical FileService ingestion converge on the same generation/publication pipeline.
6. `chunkCount` means searchable chunks.
7. Successful canonical replay preserves the real generation/publication identity.
8. RAG callers send a question and ACL scope; they do not control retrieval lanes.
9. RAG answers are returned with evidence sources; clients should preserve provenance for auditability.
10. ACL filtering is authoritative and occurs before retrieval routing.
11. Credentials, signed URLs and secret-bearing metadata must never be propagated as retrieval provenance.
12. Public HTTP availability must be taken from controllers; service-level methods are not automatically public endpoints.

---

## 11. Code authority

Current contract sources:

- `knowledge/api/KnowledgeController.java` — published knowledge HTTP endpoints;
- `knowledge/api/AddKnowledgeRequest.java` — legacy text ingestion request;
- `knowledge/api/CanonicalKnowledgeDocument.java` — FileService canonical v1 contract;
- `knowledge/api/KnowledgeIngestionResult.java` — FileService-facing publication result;
- `knowledge/service/KnowledgeIngestionPort.java` — canonical service boundary;
- `rag/api/RagController.java` — RAG ask/feedback HTTP endpoints;
- `rag/api/QuestionRequest.java` — question request;
- `rag/api/RagResponse.java` — answer/source/public provenance response;
- `api/ApiErrorResponse.java` — common error envelope.

When any of these executable contracts change, update this document in the same change set.
