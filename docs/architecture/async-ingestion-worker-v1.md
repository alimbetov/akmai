# Async Knowledge Ingestion Worker v1

**Status:** TARGET  
**Branch:** `feature/async-ingestion-worker-v1`  
**Scope:** asynchronous FileService → AkmAI ingestion admission, durable job queue, worker lifecycle, lease/fencing, bounded concurrency, retry/recovery semantics, status API, publication completion and observability.  
**Out of scope:** FileService implementation, RustFS deployment, broker selection, parser engine internals, UI, changes to retrieval semantics.

---

## 1. Goal

Move external canonical-document ingestion away from a long synchronous request and introduce a durable asynchronous ingestion boundary.

The required behavior is:

```text
FileService
    │
    │ POST canonical ingestion command
    ▼
AkmAI Admission API
    │
    ├── validate envelope
    ├── deduplicate
    ├── persist durable ingestion job
    └── COMMIT
    │
    ▼
HTTP 202 Accepted

        asynchronous processing

Durable Ingestion Queue
    │
    ▼
CanonicalIngestionWorker
    │
    ├── claim + lease
    ├── load CanonicalKnowledgeDocument
    └── call existing KnowledgeIngestionService
    │
    ▼
Generation publication
    │
    ▼
INGESTED
```

The design MUST reuse the existing `KnowledgeIngestionService`, generation lifecycle, idempotency, publication ambiguity resolution and fencing semantics. A second RAG ingestion pipeline MUST NOT be created.

---

## 2. Architectural decision

The AkmAI ingestion boundary SHALL consist of four separate responsibilities:

1. **Ingestion Admission API** — validates the command envelope, performs transport-level deduplication and durably persists work.
2. **Durable Ingestion Job Store** — acts as admission registry, Inbox/dedup registry, work queue and processing state store.
3. **CanonicalIngestionWorker** — claims jobs under a lease, controls concurrency, retries and invokes the existing business service.
4. **KnowledgeIngestionService** — remains the single owner of canonical preparation, chunking, enrichment, embedding, generation allocation and publication.

The architecture is therefore more than a minimal Inbox Pattern. It combines:

```text
Inbox semantics
+ durable work queue
+ lease/fencing
+ retry state machine
+ processing result registry
```

---

## 3. Non-negotiable invariants

1. `202 Accepted` MUST be returned only after the ingestion job is durably committed.
2. `202 Accepted` means AkmAI has accepted responsibility for processing; it does not mean the knowledge is available for retrieval.
3. `INGESTED` MUST mean that AkmAI has durable evidence that the target generation was successfully published and is eligible for retrieval.
4. Worker code MUST NOT implement chunking, embedding, graph or publication business logic itself.
5. A duplicate delivery MUST NOT create duplicate chunking, embedding, generation publication or graph side effects.
6. A worker that loses lease ownership MUST stop before performing further protected downstream work.
7. A crashed worker MUST NOT leave a job permanently stuck in `PROCESSING`.
8. Retryable and non-retryable failures MUST be distinguished explicitly.
9. Queue concurrency MUST be bounded and configurable.
10. Request identity, event identity, source/document identity and content identity MUST remain distinct concepts.
11. PostgreSQL time, not application-node wall clock, SHOULD be the distributed lease authority.
12. Unknown publication outcome MUST NOT be destructively guessed as failed.

---

## 4. Transport contract

### 4.1 Endpoint

Target endpoint:

```http
POST /api/v1/knowledge/ingestions
```

The transport MAY later be supplemented by Kafka/RabbitMQ/etc., but the durable admission/job model MUST remain transport-neutral.

A broker is not required for v1.

### 4.2 Request

The request SHALL identify the submitted source and either contain the canonical document or a durable immutable canonical artifact reference.

Recommended command envelope:

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

The previously defined `CanonicalKnowledgeDocument` contract remains the canonical payload model.

### 4.3 Response

On first durable acceptance:

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

A duplicate of the same `eventId` MUST return the existing logical ingestion job instead of creating new work. It MAY therefore return `ACCEPTED`, `PROCESSING`, `RETRY_WAIT`, `INGESTED` or terminal `FAILED` according to the current job state.

### 4.4 Durability rule

The required ordering is:

```text
validate command envelope
    ↓
BEGIN
    ↓
insert/find durable ingestion job
    ↓
COMMIT
    ↓
return 202
```

Returning `202` before the durable commit is forbidden.

---

## 5. Identity and deduplication

The implementation SHALL treat the following identities separately.

### 5.1 Event identity

`eventId` protects against duplicate transport delivery.

Required rule:

```text
UNIQUE(event_id)
```

The same `eventId` MUST map to the same ingestion job.

### 5.2 Request identity

An optional request/command identity protects synchronous API retries where a caller supplies its own idempotency identity.

Existing HTTP `Idempotency-Key` semantics MUST remain compatible for current endpoints.

### 5.3 Source/document identity

A logical source version is represented by stable source identity, for example:

```text
fileId + sourceVersion
```

Different event IDs referring to the same immutable source version MUST NOT blindly trigger independent ingestion publications.

### 5.4 Content identity

`contentHash` and/or `canonicalHash` identify actual content.

Content identity MAY be used to avoid unnecessary reprocessing, but it MUST NOT be treated as identical to event identity.

### 5.5 Conflict behavior

If an existing `eventId` is reused with a different document/source fingerprint, AkmAI MUST reject the request as an idempotency conflict and MUST NOT mutate the existing job.

---

## 6. Durable job model

Recommended table name:

```text
knowledge_ingestion_job
```

It represents the durable asynchronous ingestion lifecycle. Inbox semantics are provided by unique event identity and replay behavior.

### 6.1 Minimum fields

```text
id                     UUID / stable ingestion identity
schema_version         integer

event_id               string, unique
request_id             nullable string

document_id            string
file_id                 nullable string
source_version          string
content_hash            nullable string
canonical_hash          nullable string

payload_mode            INLINE | ARTIFACT_REF
payload_json            nullable JSONB
artifact_id             nullable string

status                  enum/string
attempt_count           integer
next_attempt_at         timestamptz nullable

lease_owner             nullable string
lease_until             timestamptz nullable
lease_version           bigint or equivalent fencing token

generation_id           nullable bigint
chunk_count             nullable integer

last_error_class        nullable string
last_error_code         nullable string
last_error_message      nullable bounded text

accepted_at             timestamptz
started_at              nullable timestamptz
finished_at             nullable timestamptz
created_at              timestamptz
updated_at              timestamptz
```

### 6.2 Payload storage

Two v1-compatible strategies are allowed:

- **INLINE** — canonical payload is stored durably in AkmAI, preferably only for bounded payload sizes;
- **ARTIFACT_REF** — job contains an immutable artifact identity owned by FileService/object storage.

If `ARTIFACT_REF` is used, the upstream contract MUST guarantee that after AkmAI returns `202`, the referenced canonical artifact cannot be deleted, replaced or mutated until AkmAI reaches a terminal status or the documented retention window expires.

Temporary/presigned URLs MUST NOT be the durable artifact identity.

---

## 7. State machine

Target state model:

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

### 7.1 ACCEPTED

The command is durably stored and is eligible to be claimed.

### 7.2 PROCESSING

A worker owns the job under a valid lease/fencing token.

`PROCESSING` without a valid non-expired lease MUST be recoverable.

### 7.3 RETRY_WAIT

A retryable failure occurred. The job is not eligible before `next_attempt_at`.

### 7.4 INGESTED

Terminal success state.

It MUST only be written when publication is durably known to be committed/published.

### 7.5 FAILED

Terminal failure state for non-retryable failures or exhausted retry policy.

A terminal failure MUST preserve enough bounded diagnostic information for operations without storing secrets or unbounded stack traces in business tables.

---

## 8. Worker claim and lease semantics

### 8.1 Claim

The worker SHALL claim only eligible jobs and MUST avoid duplicate ownership across pods.

Recommended PostgreSQL pattern:

```sql
SELECT id
FROM knowledge_ingestion_job
WHERE (
        status = 'ACCEPTED'
        OR (status = 'RETRY_WAIT' AND next_attempt_at <= CURRENT_TIMESTAMP)
        OR (status = 'PROCESSING' AND lease_until < CURRENT_TIMESTAMP)
      )
ORDER BY accepted_at, id
FOR UPDATE SKIP LOCKED
LIMIT :claimBatchSize;
```

The exact SQL MAY differ, but equivalent semantics are required.

### 8.2 Lease

On successful claim:

```text
status       = PROCESSING
lease_owner  = current instance/worker identity
lease_until  = PostgreSQL now + lease duration
lease_version/fence token advances
attempt_count increments according to defined retry semantics
```

### 8.3 Heartbeat

Long-running ingestion MUST renew the lease before it expires.

The heartbeat MUST use ownership/fencing predicates. A stale worker MUST NOT be able to renew or finalize a job after ownership has transferred.

### 8.4 Crash recovery

If a pod crashes after claim, another worker MAY reclaim after lease expiry.

Reclaim MUST not rely on manually changing `PROCESSING` to `ACCEPTED` during normal operation.

---

## 9. Worker responsibilities

`CanonicalIngestionWorker` SHALL only:

1. discover/claim eligible jobs;
2. establish worker/lease ownership;
3. load the canonical payload or artifact;
4. validate immutable artifact identity/hash where relevant;
5. invoke `KnowledgeIngestionService` through the supported canonical ingestion port;
6. heartbeat around expensive stages through the existing ingestion ownership mechanisms where possible;
7. classify failures;
8. record `INGESTED`, `RETRY_WAIT` or `FAILED` using fencing-safe updates;
9. emit metrics/traces;
10. release its local concurrency slot.

It MUST NOT independently implement:

- semantic chunking;
- enrichment;
- embedding;
- vector persistence;
- graph creation;
- generation publication;
- publication ambiguity guessing.

---

## 10. Integration with KnowledgeIngestionService

The existing `KnowledgeIngestionService` remains the single business ingestion engine.

The worker SHOULD pass a stable idempotency/admission context derived from the durable job so that retry/reclaim cannot create uncontrolled duplicate side effects.

The existing publication result semantics and `PublicationOutcomeResolver` MUST remain authoritative.

### 10.1 Publication ambiguity

If publication throws after a possible commit:

- `COMMITTED` → finalize job as `INGESTED`;
- `NOT_COMMITTED` → classify the original failure for retry/terminal handling;
- `SUPERSEDED` → do not report successful ingestion for that generation; handle according to existing ingestion result semantics;
- `UNKNOWN` → MUST NOT mark the generation/job definitively failed merely by assumption.

`UNKNOWN` MAY remain `PROCESSING` under reconciliation ownership or transition to an internal recoverable state/diagnostic classification, provided the system can deterministically reconcile it later.

---

## 11. Retry model

Failures SHALL be classified into at least:

```text
RETRYABLE
NON_RETRYABLE
AMBIGUOUS
```

### 11.1 Typical retryable failures

- temporary FileService/object-storage timeout;
- transient PostgreSQL availability failure;
- temporary embedding-provider timeout/rate limit;
- temporary network failure before a known publication commit;
- dependency unavailable where retry is safe.

### 11.2 Typical non-retryable failures

- unsupported `schemaVersion`;
- malformed canonical document;
- invalid source identity;
- duplicate block IDs;
- unsupported content semantics that violate the contract;
- permanent authorization/ACL contract violation.

### 11.3 Ambiguous failures

Publication result cannot be safely classified as committed/not committed.

These MUST be reconciled rather than converted directly to terminal failure.

### 11.4 Backoff

Retry MUST use configurable bounded backoff with jitter or equivalent contention spreading.

Recommended initial defaults:

```text
maxAttempts = 5
baseBackoff = 5s
maxBackoff = 5m
```

Exact values are operational defaults, not architectural constants.

---

## 12. Bounded concurrency

### 12.1 Default

The initial production default SHALL be:

```text
maxConcurrentIngestions = 3 per AkmAI instance
```

This is a starting safety limit, not a permanent business rule.

### 12.2 Important distinction

The worker MUST NOT process rigid batches of exactly three documents.

Required behavior:

```text
3 execution slots

job A ── running
job B ── running
job C ── running

job B finishes
    ↓
slot immediately becomes free
    ↓
job D may start
```

A slow document MUST NOT block reuse of unrelated free slots.

### 12.3 Claim batch size

`claimBatchSize` MUST be independently configurable from execution concurrency.

Recommended initial configuration:

```text
maxConcurrentIngestions = 3
claimBatchSize = 3..6
```

The implementation MUST NOT lease far more work than it can reasonably start before lease expiry.

### 12.4 Cluster concurrency

Concurrency is per instance unless a future global admission limiter is introduced.

Examples:

```text
4 pods × 3 = up to 12 concurrent document ingestions
6 pods × 3 = up to 18 concurrent document ingestions
```

Therefore performance qualification MUST test cluster-level saturation, not only one-pod concurrency.

---

## 13. Configuration contract

Recommended properties:

```yaml
akmai:
  ingestion:
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

- non-local environments SHOULD fail validation when heartbeat is not safely below lease duration;
- concurrency and claim size MUST have bounded validated ranges;
- disabling the worker MUST stop new claims but MUST NOT corrupt active durable jobs;
- configuration changes MUST not reinterpret existing terminal states.

---

## 14. Status/read model

Target endpoint:

```http
GET /api/v1/knowledge/ingestions/{ingestionId}
```

Example successful result:

```json
{
  "schemaVersion": 1,
  "ingestionId": "ing-01K...",
  "documentId": "doc-01K...",
  "status": "INGESTED",
  "attemptCount": 1,
  "publication": {
    "generation": 81,
    "chunkCount": 137
  },
  "acceptedAt": "2026-10-09T12:00:00Z",
  "startedAt": "2026-10-09T12:00:01Z",
  "finishedAt": "2026-10-09T12:00:21Z"
}
```

Internal lease owner, DB fencing token, secrets and storage credentials MUST NOT be exposed in the normal external response.

---

## 15. Completion notification / Outbox

AkmAI SHOULD eventually publish `KnowledgeDocumentPublished` after successful ingestion.

Outbox support is P1 after the durable worker core unless the FileService integration requires asynchronous completion notification immediately.

Required semantic rule:

```text
INGESTED
    ⇔ durable generation publication is known
```

The completion event MUST refer to that durable publication identity.

Consumers MUST assume at-least-once event delivery.

---

## 16. Backpressure and overload behavior

The durable queue itself is the primary backpressure boundary.

When ingestion demand exceeds worker capacity:

- API admission MAY continue while configured queue/storage limits are healthy;
- execution concurrency MUST remain bounded;
- queue age/depth MUST become observable;
- AkmAI MUST NOT spawn unbounded threads/tasks proportional to queue depth;
- embedding and JDBC downstreams MUST not be flooded by one large FileService batch.

A future admission cap MAY reject new work with an explicit overload response when durable queue size/age exceeds configured safety thresholds.

---

## 17. Transaction boundaries

### 17.1 Admission transaction

Owns only:

- event dedup lookup/insert;
- durable job creation;
- immutable command fingerprint/source identity persistence.

It MUST NOT perform chunking or embedding.

### 17.2 Claim transaction

Owns:

- selection of eligible jobs;
- state transition to `PROCESSING`;
- lease/fence allocation.

It MUST be short-lived.

### 17.3 Ingestion work

Expensive parsing/fetching/chunking/embedding MUST NOT run while holding the job-claim database transaction open.

### 17.4 Finalization transaction

Final state transition MUST verify the current ownership/fencing token before writing `INGESTED`, `RETRY_WAIT` or terminal failure.

---

## 18. Observability

Minimum metrics:

```text
akmai_ingestion_jobs_total{status}
akmai_ingestion_queue_depth
akmai_ingestion_processing
akmai_ingestion_queue_wait_seconds
akmai_ingestion_processing_duration_seconds
akmai_ingestion_retry_total{reason}
akmai_ingestion_failed_total{reason}
akmai_ingestion_lease_recovery_total
akmai_ingestion_claim_conflict_total
akmai_ingestion_duplicate_delivery_total
```

Required diagnostic dimensions MUST be low-cardinality. `documentId`, `eventId`, `fileId` MUST NOT become unbounded metric labels.

Tracing SHOULD link:

```text
external request/event
→ ingestionId
→ worker attempt
→ KnowledgeIngestionService
→ generation publication
```

Logs SHOULD include ingestion/document identity in structured form but MUST avoid canonical payload text and credentials by default.

---

## 19. Positive cases

1. New canonical ingestion → durable job → `202` → worker claim → publication → `INGESTED`.
2. Same `eventId` retried while `ACCEPTED` → existing job returned, no duplicate row/work.
3. Same `eventId` retried while `PROCESSING` → existing status returned, no duplicate work.
4. Same `eventId` retried after `INGESTED` → successful state replayed.
5. Retryable dependency failure → `RETRY_WAIT` → due time → reprocessing → `INGESTED`.
6. Worker dies during processing → lease expires → another worker reclaims → exactly one durable successful publication becomes visible.
7. Four pods with concurrency three → at most twelve local worker executions at once, subject to deployment/runtime limits.
8. One slow document among three jobs → other free slots continue to accept new jobs.

---

## 20. Negative/failure cases

### Admission

- malformed command → reject; no durable job;
- unsupported schema → reject; no durable job;
- same `eventId`, different fingerprint → conflict; original job unchanged;
- DB commit fails → MUST NOT return `202`;
- temporary FileService URL used as identity → contract validation/design violation.

### Claim/concurrency

- two workers race on one job → only one obtains valid ownership;
- worker tries to finalize with stale fence → update rejected;
- `PROCESSING` lease expires → job becomes reclaimable;
- claim transaction holds locks during embedding → implementation failure; prohibited by design.

### Payload loading

- referenced artifact missing → retry or fail according to retention/contract classification;
- canonical hash mismatch → terminal integrity failure, no embedding/publication;
- payload too large for configured inline mode → reject or require artifact reference.

### Ingestion

- canonical validation fails → `FAILED`, no retries unless classification explicitly says transient;
- embedding timeout → retryable;
- cardinality mismatch → do not publish invalid generation;
- worker loses ownership during expensive work → stale worker must stop protected continuation/finalization;
- publication exception after commit → resolve durable publication state before status decision;
- publication outcome unknown → do not force `FAILED`.

### Retry

- max attempts exhausted → terminal `FAILED`;
- retry backoff not yet due → worker MUST NOT claim;
- many retries become simultaneously due → bounded concurrency/backpressure still applies.

---

## 21. Required tests

### Unit

- state transition rules;
- error classifier;
- retry/backoff calculation;
- configuration validation;
- duplicate event fingerprint validation;
- external status mapping.

### Repository/integration

- unique event identity;
- `FOR UPDATE SKIP LOCKED`/equivalent claim exclusivity;
- expired lease reclaim;
- stale fence finalization rejection;
- retry due-time eligibility;
- terminal rows are not reclaimed;
- PostgreSQL-time lease behavior.

### Service integration

- admission commit before `202`;
- duplicate request returns same ingestion identity;
- worker invokes existing `KnowledgeIngestionService` canonical path;
- successful publication produces `INGESTED` and generation/chunk result;
- retryable failure schedules retry;
- permanent validation failure terminates;
- publication ambiguity uses current resolver semantics.

### Failure injection

- crash immediately after claim;
- crash after payload load;
- crash during embedding;
- crash/exception immediately after generation publication commit;
- DB unavailable during finalization;
- lease expires while first worker is paused and second worker reclaims;
- stale first worker resumes after reclaim and is fenced out.

### Performance

Qualification matrix SHOULD measure at least:

```text
concurrency: 1 / 3 / 5 / 8 per instance
pods:        1 / representative production count
payload:     small / median / large canonical documents
load:        steady / burst/night batch
```

Observe:

- throughput;
- p50/p95/p99 processing duration;
- p50/p95/p99 queue wait;
- CPU/heap/GC;
- JDBC pool saturation;
- embedding latency/rate limiting;
- PostgreSQL lock/query latency;
- retry/failure rate;
- generation publication latency.

`3` remains the default unless benchmark evidence justifies another production value.

---

## 22. Suggested implementation decomposition

### P0 — durable asynchronous core

1. DB migration for `knowledge_ingestion_job` and indexes.
2. state/status/error model.
3. admission service + HTTP `POST`/status `GET` API.
4. event/source/content fingerprinting and dedup rules.
5. claim repository using PostgreSQL-safe ownership semantics.
6. lease/fencing primitive integration/reuse.
7. `CanonicalIngestionWorker` with bounded executor.
8. integration with existing `KnowledgeIngestionService` canonical path.
9. retry classification/backoff.
10. metrics and structured tracing.
11. positive/negative/failure-injection tests.
12. operational documentation.

### P1 — completion/event integration

1. AkmAI Outbox.
2. `KnowledgeDocumentPublished` delivery.
3. FileService completion integration.
4. additional queue-age admission controls if needed.

### P2 — scale optimization if evidence requires it

1. adaptive worker concurrency or workload classes;
2. global rate limiter for expensive dependencies;
3. broker transport adapter;
4. priority queues/fairness by tenant/source;
5. separate execution pools for very large documents.

P2 MUST be justified by profiling/benchmark evidence rather than implemented speculatively.

---

## 23. Acceptance criteria

The implementation is acceptable only when all conditions are met:

1. Caller receives `202` only after durable job persistence.
2. Duplicate `eventId` does not create duplicate work.
3. Same logical source version cannot accidentally cause uncontrolled duplicate publication through different transport event IDs.
4. Worker execution is bounded; default is three concurrent ingestions per instance.
5. Free execution slots are reused immediately; processing is not rigid batches of three.
6. Multiple pods can safely claim jobs without duplicate ownership.
7. Pod crash during `PROCESSING` is automatically recoverable by lease expiry.
8. Stale workers are fenced from finalization.
9. Retryable errors retry with bounded backoff; permanent errors terminate.
10. Existing `KnowledgeIngestionService` remains the sole RAG ingestion business engine.
11. `INGESTED` is written only for a durably known successful generation publication.
12. Publication ambiguity is reconciled, not guessed.
13. Queue depth, queue wait, processing duration, retry/failure and lease recovery are observable.
14. No unbounded executor/thread creation is possible from queue depth.
15. Contract, migration, positive/negative cases and operational behavior are documented.
16. Integration and failure-injection tests cover crash/reclaim/fencing paths.
17. Performance tests validate the default concurrency and identify the real saturation point before increasing it.

---

## 24. Definition of Done

The target architecture may be changed from `TARGET` to `CURRENT` only after:

- schema migration is merged;
- admission/status API is implemented and documented;
- worker is production-capable and bounded;
- lease/fencing/recovery tests pass;
- current synchronous canonical ingestion behavior remains supported or has an explicit migration path;
- `KnowledgeIngestionService` has not been duplicated;
- positive and negative business cases are documented;
- failure injection is green;
- benchmark/profiler pass validates the production default;
- operations documentation includes stuck/retry/failed job diagnostics and safe recovery procedures.

Until then this document describes TARGET architecture, not current runtime behavior.
