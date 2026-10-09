# Async Knowledge Ingestion Worker v1

**Status:** TARGET / IMPLEMENTATION-READY AFTER P0 PREREQUISITE  
**Branch:** `feature/async-ingestion-worker-v1`  
**Scope:** asynchronous FileService → AkmAI ingestion admission, PostgreSQL durable job queue, worker lifecycle, two-layer lease/fencing model, bounded concurrency, retry/recovery semantics, status API, publication completion and observability.  
**Out of scope:** FileService implementation, RustFS deployment, broker selection, parser engine internals, retrieval behavior changes.

---

## 1. Goal

Move external canonical-document ingestion away from a long synchronous request while preserving the existing AkmAI ingestion/generation implementation as the single business pipeline.

Target flow:

```text
FileService
    │
    │ POST canonical ingestion command
    ▼
AkmAI Async Ingestion Admission
    │
    ├── validate envelope
    ├── validate identity/fingerprint
    ├── deduplicate event
    ├── persist durable job
    └── COMMIT
    │
    ▼
HTTP 202 Accepted

        asynchronous processing

knowledge_ingestion_job
    │
    ▼
CanonicalIngestionWorker
    │
    ├── claim job lease
    ├── load CanonicalKnowledgeDocument
    ├── use stable internal idempotency key
    └── call existing KnowledgeIngestionService
    │
    ▼
existing ingestion idempotency / generation pipeline
    │
    ├── chunking
    ├── enrichment
    ├── embedding
    ├── generation allocation
    ├── publication
    └── publication outcome resolution
    │
    ▼
KnowledgeIngestionResult
    │
    ▼
fenced job finalization
    │
    ▼
INGESTED
```

A second RAG ingestion pipeline MUST NOT be introduced.

---

## 2. Current runtime primitives that MUST be reused

The implementation SHALL treat the following existing components as authoritative runtime primitives:

- `KnowledgeIngestionService.addCanonicalKnowledge(CanonicalKnowledgeDocument, idempotencyKey)`;
- `CanonicalRequestFingerprint` / canonical hash;
- `IngestionIdempotencyRepository`;
- `IngestionIdempotencyContext`;
- `PersistenceCoordinator`;
- `DocumentGenerationRepository`;
- `GenerationPublicationService`;
- `PublicationOutcomeResolver`;
- `ParallelIngestionExecutor`;
- `KnowledgeIngestionResult`.

The async worker is an orchestration layer around those components. It MUST NOT duplicate their chunking, embedding, generation or publication behavior.

---

## 3. Architectural responsibilities

The boundary SHALL consist of five responsibilities:

1. **AsyncIngestionAdmission** — validates transport/business envelope, deduplicates delivery and durably persists work.
2. **AsyncIngestionJobRepository** — stores queue state, retry state, job lease/fence and terminal result.
3. **CanonicalIngestionWorker** — claims jobs, controls document-level concurrency and invokes the existing ingestion service.
4. **Existing KnowledgeIngestionService** — remains the only owner of canonical preparation, chunking, enrichment, embedding, generation allocation and publication.
5. **AsyncIngestionHeartbeat** — renews only the outer job lease independently from heavy ingestion execution.

The architecture therefore combines:

```text
Inbox semantics
+ durable work queue
+ job lifecycle/retry
+ outer job lease/fencing
+ existing ingestion idempotency lease/fencing
+ generation publication
```

---

## 4. Non-negotiable invariants

1. `202 Accepted` MUST be returned only after the durable job transaction commits.
2. `202 Accepted` means AkmAI accepted responsibility for processing, not that knowledge is retrieval-ready.
3. `INGESTED` MUST mean publication is durably known to have completed and the resulting generation is eligible for retrieval.
4. Worker code MUST NOT implement chunking, embedding, vector persistence, graph publication or generation publication.
5. Duplicate delivery MUST NOT create duplicate ingestion side effects.
6. The job lease and existing ingestion idempotency lease MUST remain separate because they protect different resources.
7. The worker MUST use one stable internal idempotency key for the logical async job across all retries/reclaims.
8. A stale job owner MUST NOT finalize or mutate job state after losing its job lease/fence.
9. Existing ingestion idempotency fencing remains authoritative for protected RAG side effects.
10. A crashed worker MUST be recoverable without manual state edits.
11. Retryable, non-retryable and ambiguous failures MUST be classified explicitly.
12. PostgreSQL time (`clock_timestamp()` or equivalent DB time) SHALL be the distributed lease authority.
13. Publication ambiguity MUST be resolved through existing publication semantics, not guessed by the worker.
14. `knowledge_ingestion_request` MUST NOT be repurposed as the durable async queue.
15. Active long-running ingestion MUST NOT be falsely failed by stale-generation recovery.

---

## 5. Two-layer ownership model

### 5.1 Outer job lease

`knowledge_ingestion_job` answers:

> Which async worker currently owns this queue job?

It protects:

- queue scheduling;
- retry transition;
- terminal job status;
- status/result mutation.

Recommended identity:

```text
lease_owner
lease_until
lease_version
```

Every state-changing job update after claim MUST include the active ownership/fencing predicate.

### 5.2 Existing ingestion idempotency lease

`knowledge_ingestion_request` answers:

> Which ingestion claim is currently allowed to perform the protected RAG ingestion/publication operation?

It already provides:

- `claim_id`;
- `lease_until`;
- DB-time claim/reclaim;
- renew/heartbeat;
- replay;
- generation attachment;
- publication-time locking;
- fencing against stale ingestion claims.

### 5.3 Rule

These two leases MUST NOT be collapsed into one mechanism in v1.

```text
job lease
    protects async queue state

existing ingestion lease
    protects ingestion/publication side effects
```

---

## 6. Transport contract

### 6.1 Endpoint

```http
POST /api/v1/knowledge/ingestions
```

A broker MAY be added later as another admission adapter. Broker technology is not part of the business contract and is not required in v1.

### 6.2 Request

The request SHALL identify the submitted source and either contain the canonical document or a durable immutable artifact reference.

Recommended envelope:

```json
{
  "schemaVersion": 1,
  "eventId": "evt-01K...",
  "requestId": "optional-command-id",
  "documentId": "doc-01K...",
  "source": {
    "type": "FILE",
    "fileId": "file-01K...",
    "sourceVersion": "17",
    "contentHash": "sha256:..."
  },
  "canonicalArtifact": {
    "artifactId": "canonical-01K...",
    "canonicalHash": "sha256:..."
  }
}
```

`CanonicalKnowledgeDocument` remains the canonical payload contract.

### 6.3 Acceptance response

```http
202 Accepted
```

```json
{
  "schemaVersion": 1,
  "ingestionId": "ing-01K...",
  "documentId": "doc-01K...",
  "status": "ACCEPTED"
}
```

Duplicate `eventId` delivery MUST return the existing logical job and its current status instead of creating new work.

### 6.4 Durability rule

Required ordering:

```text
validate envelope
    ↓
BEGIN
    ↓
insert/find durable job
    ↓
COMMIT
    ↓
return 202
```

Returning `202` before durable commit is forbidden.

---

## 7. Identity model

The implementation SHALL keep the following identities distinct.

### 7.1 Event identity

`eventId` identifies transport delivery.

Required constraint:

```text
UNIQUE(event_id)
```

Reusing the same `eventId` with a different job fingerprint MUST return an idempotency conflict.

### 7.2 Job identity

`ingestionId` is generated exactly once when the durable job is created.

Recommended representation: UUID/UUIDv7/ULID-equivalent stable identifier.

### 7.3 Internal business idempotency identity

At admission time AkmAI SHALL derive and persist one stable key:

```text
internalIdempotencyKey = "async-ingestion:" + ingestionId
```

This value MUST be reused for every execution attempt of the same logical job.

It MUST NOT be regenerated per retry, per pod or per lease reclaim.

### 7.4 Source identity

Logical immutable source version is identified by stable source identity, typically:

```text
fileId + sourceVersion
```

### 7.5 Content identity

`contentHash` and `canonicalHash` identify actual content representations. They are not interchangeable with event or job identity.

### 7.6 Job fingerprint

Admission SHALL persist a deterministic job fingerprint over the immutable command identity fields. It is used to detect `eventId` reuse with different content/identity.

---

## 8. Durable job model

Target table:

```text
knowledge_ingestion_job
```

This table is the async queue/admission registry. Existing `knowledge_ingestion_request` remains the ingestion idempotency registry.

### 8.1 Required fields

```text
ingestion_id              UUID primary key
schema_version            integer

event_id                  varchar unique
request_id                nullable varchar
job_fingerprint           varchar not null
internal_idempotency_key  varchar unique not null

document_id               varchar not null
source_type               varchar
file_id                   nullable varchar
source_version            nullable varchar
content_hash              nullable varchar
canonical_hash            nullable varchar

payload_mode              INLINE | ARTIFACT_REF
payload_json              nullable JSONB
artifact_id               nullable varchar

job_status                ACCEPTED | PROCESSING | RETRY_WAIT | INGESTED | FAILED
attempt_count             integer not null
next_attempt_at           timestamptz nullable

lease_owner               nullable varchar
lease_until               timestamptz nullable
lease_version             bigint not null

generation                nullable bigint
chunk_count               nullable integer
embedding_profile_id      nullable varchar

last_error_class          nullable varchar
last_error_code           nullable varchar
last_error_message        nullable varchar(1000)

accepted_at               timestamptz not null
started_at                nullable timestamptz
finished_at               nullable timestamptz
created_at                timestamptz not null
updated_at                timestamptz not null
```

### 8.2 Constraints/indexes

At minimum:

```text
PK(ingestion_id)
UNIQUE(event_id)
UNIQUE(internal_idempotency_key)
INDEX(job_status, next_attempt_at, accepted_at)
INDEX(document_id, accepted_at desc)
INDEX(file_id, source_version) where applicable
```

All state checks should be backed by DB constraints where practical.

### 8.3 Payload storage

Allowed modes:

- `INLINE`: canonical payload is stored durably in AkmAI up to a configured byte bound;
- `ARTIFACT_REF`: only immutable artifact identity/hash is stored.

Presigned/temporary URL MUST NOT be durable identity.

For `ARTIFACT_REF`, upstream MUST guarantee immutability and retention after `202` until terminal job state plus documented retention/grace period.

---

## 9. Job state machine

```text
                       ┌──────────┐
                       │ ACCEPTED │
                       └────┬─────┘
                            │ claim
                            ▼
                      ┌────────────┐
                      │ PROCESSING │
                      └─────┬──────┘
                            │
          ┌─────────────────┼─────────────────┐
          │                 │                 │
       success         retryable          permanent
          │              failure            failure
          ▼                 │                  │
     ┌──────────┐           ▼                  ▼
     │ INGESTED │      ┌────────────┐      ┌────────┐
     └──────────┘      │ RETRY_WAIT │      │ FAILED │
                       └─────┬──────┘      └────────┘
                             │ due
                             └────────────► PROCESSING
```

### ACCEPTED

Durably accepted and eligible for claim.

### PROCESSING

Owned by a worker under a valid job lease/fence.

### RETRY_WAIT

Retryable failure or temporary `INGESTION_IN_PROGRESS`; not eligible before `next_attempt_at`.

### INGESTED

Terminal success. MUST contain durable result identity (`generation`, `chunkCount`, `embeddingProfileId` where available).

### FAILED

Terminal permanent failure or exhausted retry budget.

---

## 10. Claim and job lease semantics

### 10.1 Claim

Eligible jobs:

```sql
SELECT ingestion_id
FROM knowledge_ingestion_job
WHERE (
        job_status = 'ACCEPTED'
        OR (job_status = 'RETRY_WAIT' AND next_attempt_at <= clock_timestamp())
        OR (job_status = 'PROCESSING' AND lease_until < clock_timestamp())
      )
ORDER BY accepted_at, ingestion_id
FOR UPDATE SKIP LOCKED
LIMIT :claimBatchSize;
```

Exact SQL MAY differ, but equivalent multi-pod semantics are mandatory.

### 10.2 On claim

Atomically:

```text
job_status    = PROCESSING
lease_owner   = current worker identity
lease_until   = DB now + job lease duration
lease_version = lease_version + 1
attempt_count = attempt_count + 1
started_at    = COALESCE(started_at, DB now)
```

### 10.3 Fenced mutation

Terminal/retry transitions MUST use predicates equivalent to:

```text
ingestion_id = ?
AND job_status = 'PROCESSING'
AND lease_owner = ?
AND lease_version = ?
AND lease_until > clock_timestamp()
```

If update count is zero, ownership is lost. The stale worker MUST NOT overwrite job state.

### 10.4 Reclaim

An expired `PROCESSING` job is reclaimable. Manual `PROCESSING → ACCEPTED` repair is not required in normal operation.

---

## 11. Heartbeat model

The worker needs two heartbeat domains:

1. the existing ingestion idempotency heartbeat, performed by the current ingestion pipeline;
2. the outer async job heartbeat, performed by `AsyncIngestionHeartbeat`.

The outer heartbeat SHOULD run on a lightweight independent scheduler/executor and MUST NOT depend on document execution slots or the chunk enrichment pool.

Recommended initial relationship:

```text
jobLeaseDuration = 120s
jobHeartbeatInterval = 30s
```

Validation MUST ensure heartbeat interval is safely below lease duration.

Loss of job heartbeat/lease MUST set local ownership-lost state. The stale worker MUST not finalize job state after that point.

No invasive callback through every RAG stage is required in v1; protected RAG side effects remain fenced by the existing ingestion idempotency claim.

---

## 12. Integration with KnowledgeIngestionService

For each claimed job the worker SHALL call:

```text
KnowledgeIngestionService.addCanonicalKnowledge(
    canonicalDocument,
    job.internalIdempotencyKey
)
```

The internal idempotency key is stable across retries/reclaims.

### 12.1 Normal success

```text
job PROCESSING
    ↓
KnowledgeIngestionService
    ↓
PUBLISHED / ALREADY_PUBLISHED / REPLAYED success result
    ↓
fenced job update
    ↓
INGESTED
```

### 12.2 Crash after publication but before job finalization

Required recovery behavior:

```text
worker-1 publishes generation
existing idempotency row becomes SUCCEEDED
worker-1 crashes before job → INGESTED
    ↓
job lease expires
    ↓
worker-2 reclaims same job
    ↓
uses same internal idempotency key
    ↓
existing ingestion returns REPLAY
    ↓
worker-2 finalizes job → INGESTED
```

This is the primary exactly-once-effect recovery mechanism for v1.

### 12.3 Existing ingestion still active

If `KnowledgeIngestionService` reports `INGESTION_IN_PROGRESS` with retry-after metadata, the async job SHALL transition to `RETRY_WAIT` using the suggested delay or a safe bounded equivalent. It MUST NOT manipulate the existing ingestion request directly.

---

## 13. Publication boundary

The existing `GenerationPublicationService` transaction remains authoritative and MUST NOT be expanded to include `knowledge_ingestion_job` in v1.

Publication currently owns the atomic business boundary for generation/vector/search/idempotency publication. The async job is finalized after that transaction returns.

This deliberate two-step design is safe because replay can recover from a crash between publication commit and job finalization.

---

## 14. Publication ambiguity

Current `PublicationOutcomeResolver` has authoritative outcomes:

```text
COMMITTED
SUPERSEDED
NOT_COMMITTED
```

There is no `Outcome.UNKNOWN` enum in the current runtime.

If resolution itself fails and the ingestion layer throws `PublicationOutcomeUnknownException`, the async failure classifier SHALL classify it as `AMBIGUOUS`.

Rules:

- `COMMITTED` / successful replay → job may become `INGESTED`;
- `SUPERSEDED` → MUST NOT be reported as successful publication of this job's generation;
- `NOT_COMMITTED` → classify original failure for retry/permanent handling;
- `PublicationOutcomeUnknownException` → `AMBIGUOUS`, reconcile/retry without destructive assumptions.

The worker MUST NOT invent its own publication state by querying partial tables independently when existing publication primitives can decide it.

---

## 15. Retry model

Failures SHALL be mapped to:

```text
RETRYABLE
NON_RETRYABLE
AMBIGUOUS
```

### Typical RETRYABLE

- `INGESTION_IN_PROGRESS`;
- FileService/object-storage timeout;
- transient DB/connectivity failure before known terminal publication;
- embedding provider timeout/rate limit;
- temporary dependency unavailability.

### Typical NON_RETRYABLE

- unsupported schema version;
- malformed canonical payload;
- invalid immutable source identity/hash;
- duplicate canonical block IDs;
- permanent ACL/authorization contract violation;
- deterministic validation failure.

### AMBIGUOUS

- `PublicationOutcomeUnknownException` or equivalent inability to determine whether publication committed.

### Backoff

Initial target defaults:

```text
maxAttempts = 5
retryBaseDelay = 5s
retryMaxDelay = 5m
jitter = enabled
```

`INGESTION_IN_PROGRESS` SHOULD respect the existing retry-after value when available.

---

## 16. P0 prerequisite: stale generation recovery hardening

This change is mandatory before async worker enablement.

### 16.1 Current risk

Current stale ingestion cleanup can fail an old `STAGING` ingestion generation based primarily on `started_at` age. For long-running async ingestion this can race a healthy active ingestion whose idempotency lease is still being renewed.

A large document MUST NOT be marked `FAILED/STALE_INGESTION` solely because total processing duration exceeds a static age threshold.

### 16.2 Required authority check

Before failing a `STAGING` ingestion generation as stale, recovery MUST verify that no active current ingestion idempotency claim owns that exact `documentId + generation`.

Equivalent rule:

```text
candidate generation is old STAGING
AND
NO knowledge_ingestion_request exists with:
    same document_id
    same generation
    request_status = IN_PROGRESS
    lease_until > DB now
```

Illustrative predicate:

```sql
NOT EXISTS (
    SELECT 1
    FROM knowledge_ingestion_request r
    WHERE r.document_id = g.document_id
      AND r.generation = g.generation
      AND r.request_status = 'IN_PROGRESS'
      AND r.lease_until > clock_timestamp()
)
```

### 16.3 Required behavior

- Active current idempotency claim → generation MUST NOT be stale-failed.
- Expired/no claim + sufficiently old `STAGING` → existing stale recovery MAY fail generation.
- Recovery remains bounded/batched and multi-pod safe (`FOR UPDATE SKIP LOCKED` or equivalent).
- Tests MUST cover long-running healthy ingestion beyond stale-age threshold.

Simply increasing the timeout is not an acceptable fix.

---

## 17. Bounded concurrency

### 17.1 Document-level default

```text
maxConcurrentIngestions = 3 per AkmAI instance
```

This is a safety default, not a business invariant.

The worker MUST use independent execution slots, not rigid batches of three.

```text
A running
B running
C running

B finishes
→ D starts immediately
```

### 17.2 Claim batch size

`claimBatchSize` is separate from execution concurrency.

Initial target:

```text
maxConcurrentIngestions = 3
claimBatchSize = 3..6
```

The worker MUST NOT lease substantially more work than it can begin before lease expiry.

### 17.3 Existing chunk-level concurrency

Current AkmAI already has shared chunk-level ingestion concurrency (`akmai.ingestion.parallelism`, current default 8, and bounded queue capacity).

The two levels are intentionally different:

```text
DOCUMENT LEVEL
async maxConcurrentIngestions = 3

        ↓ shared business pipeline

CHUNK LEVEL
existing ingestion executor parallelism = 8
```

Do not create a private chunk executor per document.

### 17.4 Cluster concurrency

Per-instance document concurrency implies:

```text
4 pods × 3 = up to 12 document ingestions
6 pods × 3 = up to 18 document ingestions
```

Cluster saturation MUST therefore be measured explicitly.

---

## 18. Configuration contract

Recommended properties:

```yaml
akmai:
  ingestion:
    parallelism: 8
    queue-capacity: 128
    async-worker:
      enabled: false
      max-concurrent-ingestions: 3
      claim-batch-size: 3
      lease-duration: 120s
      heartbeat-interval: 30s
      poll-interval: 1s
      max-attempts: 5
      retry-base-delay: 5s
      retry-max-delay: 5m
      payload-max-inline-bytes: 1048576
```

Rules:

- async worker defaults to disabled until migrations/tests/P0 stale-recovery fix are complete;
- heartbeat interval must be safely below lease duration;
- concurrency/claim sizes require bounded validation;
- disabling worker stops new claims but does not corrupt active durable jobs;
- existing `akmai.idempotency.lease-duration` remains a separate service-level setting and MUST NOT silently inherit async job lease settings.

---

## 19. Status/read API

```http
GET /api/v1/knowledge/ingestions/{ingestionId}
```

Example terminal success:

```json
{
  "schemaVersion": 1,
  "ingestionId": "ing-01K...",
  "documentId": "doc-01K...",
  "status": "INGESTED",
  "attemptCount": 2,
  "publication": {
    "generation": 42,
    "chunkCount": 137,
    "embeddingProfileId": "..."
  },
  "acceptedAt": "...",
  "finishedAt": "..."
}
```

Do not expose internal lease tokens, worker IDs, stack traces or sensitive storage credentials in the public response.

---

## 20. Outbox/publication notification

`KnowledgeDocumentPublished` remains a P1 asynchronous integration event after successful publication/job finalization.

The v1 queue architecture MUST NOT require an external broker for correctness.

If outbox publication is added, its correctness boundary must preserve the invariant that a published knowledge generation cannot be permanently invisible to downstream notification without retry/reconciliation.

---

## 21. Observability

Required metrics include:

```text
async_ingestion_queue_depth
async_ingestion_processing
async_ingestion_retry_total
async_ingestion_failed_total
async_ingestion_ingested_total
async_ingestion_queue_wait_duration
async_ingestion_processing_duration
async_ingestion_job_lease_recovery_total
async_ingestion_job_lease_lost_total
async_ingestion_replay_recovery_total
async_ingestion_ambiguous_total
```

Existing ingestion metrics remain authoritative for inner pipeline latency/result.

Logs/traces SHOULD correlate:

```text
ingestionId
eventId (safe/bounded)
documentId
fileId/sourceVersion when safe
attemptCount
leaseVersion
generation
internal ingestion outcome (claimed/replay/in-progress)
```

Do not log canonical payloads, secrets or presigned URLs.

---

## 22. Required positive cases

1. First submission → durable job → `202 ACCEPTED`.
2. Worker claim → service ingestion → publication → `INGESTED`.
3. Duplicate `eventId` with identical fingerprint → existing job returned.
4. Same job reclaimed after worker crash → same internal idempotency key reused.
5. Crash after publication commit but before job finalization → replay → `INGESTED` without duplicate publication.
6. Existing ingestion still in progress → async job goes to `RETRY_WAIT` and later succeeds.
7. Long-running healthy ingestion beyond stale-age threshold remains protected by active idempotency claim.
8. Three document slots run independently; completion of one immediately permits another claim/start.

---

## 23. Required negative/failure cases

1. Same `eventId`, different fingerprint → conflict, no mutation.
2. Invalid canonical schema/payload → terminal `FAILED`, no retry storm.
3. Artifact hash mismatch → terminal/non-retryable failure.
4. File/object storage temporary outage → retry/backoff.
5. Worker crashes after claim → lease expiry + reclaim.
6. Two pods race for same job → exactly one active job owner/fence.
7. Stale worker tries to mark `INGESTED` after losing job lease → update rejected.
8. Ingestion idempotency claim is still active after job reclaim → `INGESTION_IN_PROGRESS`, no duplicate RAG side effects.
9. Embedding timeout/rate limit → retryable according to classifier.
10. Publication resolver returns `SUPERSEDED` → not reported as successful generation publication.
11. Publication outcome resolution fails → `AMBIGUOUS`, no destructive terminal guess.
12. Retry budget exhausted → terminal `FAILED` with bounded diagnostics.
13. Retention stale-recovery runs while healthy long ingestion is active → active generation is not failed.

---

## 24. Integration/failure-injection test matrix

Mandatory tests:

- admission transaction commit failure before `202`;
- duplicate admission races from multiple pods;
- claim race using `SKIP LOCKED`;
- job lease expiry/reclaim;
- heartbeat failure and stale-worker finalization rejection;
- worker crash before calling ingestion service;
- worker crash during ingestion;
- worker crash after publication commit and before job finalization;
- stable internal idempotency key across retry/reclaim;
- replay result reconstructs generation/chunk/profile result correctly;
- `INGESTION_IN_PROGRESS` retry-after handling;
- publication `COMMITTED`, `SUPERSEDED`, `NOT_COMMITTED` paths;
- `PublicationOutcomeUnknownException` → ambiguous handling;
- stale-generation cleanup cannot kill an active claim;
- stale-generation cleanup still recovers genuinely abandoned `STAGING` generations;
- multi-pod document concurrency and fairness;
- worker disable/re-enable with durable backlog.

---

## 25. Performance qualification

Benchmark document-level concurrency:

```text
1 / 3 / 5 / 8
```

against representative small/medium/large canonical documents and the current shared chunk executor.

Measure at minimum:

```text
documents/sec
chunks/sec
queue depth
queue wait p50/p95/p99
end-to-end ingestion p50/p95/p99
embedding latency/rate-limit behavior
JDBC pool utilization/saturation
publication transaction latency
CPU
heap/GC
retry rate
lease loss/recovery rate
```

`3` remains the default unless evidence supports a safer/higher value. Increasing concurrency is not acceptable if throughput gain is offset by connection-pool saturation, embedding throttling, retry amplification or p99 collapse.

---

## 26. Implementation components

Expected new components:

```text
AsyncIngestionController
AsyncIngestionAdmissionService
AsyncIngestionJobRepository
AsyncIngestionJob
AsyncIngestionStatus
AsyncIngestionProperties
CanonicalIngestionWorker
AsyncIngestionWorkerPool
AsyncIngestionHeartbeat
AsyncIngestionFailureClassifier
AsyncIngestionStatusResponse
```

Expected reused components:

```text
CanonicalKnowledgeDocument
CanonicalRequestFingerprint
KnowledgeIngestionService
KnowledgeIngestionResult
IngestionIdempotencyRepository
PersistenceCoordinator
GenerationPublicationService
PublicationOutcomeResolver
DocumentGenerationRepository
ParallelIngestionExecutor
AkmaiMetrics
```

Expected targeted modifications:

- Liquibase migration for `knowledge_ingestion_job`;
- stale-generation recovery hardening in `DocumentGenerationRepository` and related tests;
- configuration registration/validation;
- metrics/logging/tracing;
- async endpoints;
- integration/failure-injection tests.

---

## 27. Implementation order

### P0 — correctness prerequisite

1. Harden stale `STAGING` ingestion recovery to respect active current idempotency claim/lease.
2. Add regression tests proving long healthy ingestion is not stale-failed.

### P1 — durable admission

3. Add `knowledge_ingestion_job` migration/repository/model.
4. Add deterministic job fingerprint and stable `internalIdempotencyKey`.
5. Add admission service/controller and `202` contract.
6. Add status/read endpoint.

### P1 — worker

7. Add claim/lease/fencing primitives for job queue.
8. Add bounded worker pool with default document concurrency `3`.
9. Add independent outer-job heartbeat.
10. Invoke existing `KnowledgeIngestionService.addCanonicalKnowledge` using persisted internal idempotency key.
11. Add retry classifier/backoff and replay recovery.

### P1 — verification

12. Add full integration/failure-injection matrix.
13. Add observability.
14. Run concurrency/DB/embedding performance qualification.

### P2

15. Add `KnowledgeDocumentPublished` outbox event if required by FileService orchestration.
16. Consider broker adapter only if operational evidence justifies it.

---

## 28. Acceptance criteria

The TARGET is accepted for implementation only when all of the following hold:

- `202` is provably post-commit;
- duplicate delivery creates one durable logical job;
- job retry/reclaim always reuses the same internal ingestion idempotency key;
- job lease/fence prevents stale queue-state mutation;
- existing ingestion idempotency lease/fence remains authoritative for RAG side effects;
- crash after publication but before job finalization recovers through replay;
- no second chunking/embedding/publication pipeline exists;
- `INGESTED` is written only after durable publication/replay evidence;
- publication ambiguity is not guessed;
- long healthy ingestion cannot be killed by stale-generation recovery;
- document concurrency is bounded and configurable, default `3`;
- cluster-level load qualification shows no unacceptable JDBC/embedding/p99 regression;
- all positive, negative, concurrency and failure-injection scenarios pass.

---

## 29. Definition of Done

The feature is done when:

1. P0 stale-recovery prerequisite is implemented and tested.
2. Schema migration and indexes are reviewed against actual claim/status query plans.
3. Admission endpoint returns `202` only after durable commit.
4. Durable job replay/conflict semantics are deterministic.
5. Worker is multi-pod safe under claim/lease/fencing races.
6. Stable internal idempotency key is persisted and reused across all attempts.
7. Existing `KnowledgeIngestionService` remains the sole business ingestion engine.
8. Publication/replay results populate terminal async status correctly.
9. Retry/non-retry/ambiguous classifications are covered by tests.
10. Crash/reclaim/failure-injection tests pass.
11. Benchmark matrix `1/3/5/8` is recorded and default concurrency is evidence-based.
12. Documentation, configuration and executable behavior agree.

---

## 30. Final target

The approved architecture is:

```text
FileService
    │
    ▼
Async Admission API
    │
    ▼
knowledge_ingestion_job
(queue/retry/job lease/fence)
    │
    ▼
CanonicalIngestionWorker
    │ stable internal idempotency key
    ▼
knowledge_ingestion_request
(existing business idempotency/lease/replay/fence)
    │
    ▼
KnowledgeIngestionService
    │
    ▼
knowledge_document_generation
(STAGING → PUBLISHED / FAILED / SUPERSEDED)
    │
    ▼
KnowledgeIngestionResult
    │
    ▼
fenced async job finalization
    │
    ▼
INGESTED
```

This is the implementation authority for `feature/async-ingestion-worker-v1`.