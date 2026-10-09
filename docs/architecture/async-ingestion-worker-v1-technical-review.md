# Async Knowledge Ingestion Worker v1 — Technical Review

**Status:** TARGET / NORMATIVE REFINEMENT  
**Branch:** `feature/async-ingestion-worker-v1`  
**Baseline reviewed:** current `main` implementation on 2026-10-09  
**Supersedes on conflict:** `async-ingestion-worker-v1.md`

## 1. Review goal

Validate the async ingestion TARGET against the actual AkmAI runtime before implementation and adapt the design to reuse the current ingestion, idempotency, generation and publication primitives instead of introducing parallel ownership models.

The reviewed runtime already contains more reusable machinery than the original TARGET assumed:

- `KnowledgeIngestionService.addCanonicalKnowledge(...)` already accepts `CanonicalKnowledgeDocument` and returns `KnowledgeIngestionResult`;
- `IngestionIdempotencyRepository` already implements durable claim, PostgreSQL-time lease, heartbeat, reclaim, replay and claim fencing;
- `PersistenceCoordinator` already attaches the allocated generation to the idempotency claim and heartbeats around expensive stages;
- `GenerationPublicationService` already locks the active idempotency claim inside the publication transaction and atomically completes the idempotency request with publication;
- `PublicationOutcomeResolver` already resolves committed / superseded / not-committed publication outcomes;
- `DocumentGenerationRepository` already owns generation allocation and stale-STAGING recovery;
- `ParallelIngestionExecutor` already provides bounded chunk-level parallelism through the shared ingestion executor.

Therefore async ingestion MUST be an orchestration layer around the existing canonical ingestion engine, not a replacement for it.

---

## 2. Accepted target architecture

```text
FileService
    │
    │ POST async ingestion command
    ▼
AsyncIngestionAdmissionService
    │
    ├── validate envelope only
    ├── transport/source dedup
    ├── persist knowledge_ingestion_job
    └── COMMIT
    │
    ▼
202 Accepted

PostgreSQL durable job queue
    │
    ▼
CanonicalIngestionWorker
    │
    ├── claim job under JOB lease
    ├── load immutable CanonicalKnowledgeDocument
    ├── derive stable internal idempotency key
    └── KnowledgeIngestionService.addCanonicalKnowledge(...)
               │
               ├── existing IDEMPOTENCY claim/lease
               ├── canonical hash
               ├── chunking
               ├── enrichment
               ├── generation allocate
               ├── embedding
               └── atomic publication
    │
    ▼
fenced finalize job
    │
    ├── INGESTED
    ├── RETRY_WAIT
    └── FAILED
```

Two ownership layers are intentional:

1. **job lease** — owns scheduling and terminal job-state mutation;
2. **existing ingestion idempotency lease** — owns RAG side effects and generation publication.

They MUST NOT be collapsed into one table or one lease in v1.

---

## 3. Existing primitives and exact reuse decision

### 3.1 `KnowledgeIngestionService`

Reuse without creating a parallel service.

Worker invocation SHALL use:

```java
knowledgeIngestionService.addCanonicalKnowledge(document, internalIdempotencyKey)
```

The worker MUST NOT call `HierarchicalChunker`, `ParallelIngestionExecutor`, `PersistenceCoordinator` or `GenerationPublicationService` directly.

### 3.2 `IngestionIdempotencyRepository`

Reuse as the business-side exactly-once/replay fence.

Existing behavior already provides:

- `IN_PROGRESS` durable claim;
- UUID `claim_id`;
- `lease_until` based on `clock_timestamp()`;
- renewal requiring current claim + unexpired lease;
- generation attachment requiring current claim;
- publication-time `FOR UPDATE` claim lock;
- `SUCCEEDED` replay;
- expired-claim reclaim;
- recovery of a previously published generation into `SUCCEEDED`;
- failure of an abandoned `STAGING` generation during reclaim.

The async job store MUST NOT reimplement these semantics for generation-side business work.

### 3.3 Internal idempotency key

Each durable async job SHALL have one stable internal idempotency key persisted at admission time.

Recommended form:

```text
async-ingestion:<ingestionId>
```

Requirements:

- stable across retries and pod crashes;
- independent from the worker instance;
- independent from retry attempt number;
- maximum length compatible with the existing `knowledge_ingestion_request.idempotency_key` column;
- never derived from temporary URLs.

Using `eventId` directly as the service idempotency key is NOT recommended because event identity and AkmAI job identity are different concepts.

### 3.4 Canonical fingerprint

`KnowledgeIngestionService.addCanonicalKnowledge(...)` already calculates canonical hash and uses it as the idempotency fingerprint when an idempotency key is present.

Therefore the worker SHALL NOT create a second business fingerprint algorithm.

Admission MAY persist `canonical_hash` for dedup/read-model purposes, but runtime ingestion authority remains the canonical hash calculated by the existing `CanonicalRequestFingerprint` path.

### 3.5 `GenerationPublicationService`

No alternate publication transaction is allowed.

Current publication already:

- locks the active embedding profile;
- locks document lifecycle;
- locks candidate generation;
- prevents older generations from superseding a newer publication;
- saves projections/identifiers/references/vector manifest/vectors;
- marks generation `PUBLISHED`;
- switches lifecycle to the published generation;
- completes the current idempotency claim in the same publication transaction.

The async job row MUST NOT be added directly to this publication transaction in v1. Job completion is a separate fenced orchestration write after the service returns.

Reason: coupling the new queue row into the already critical publication transaction would unnecessarily widen the publication boundary and duplicate recovery responsibility.

### 3.6 `PublicationOutcomeResolver`

The original TARGET described an `UNKNOWN` enum value. This does not match current code.

Actual runtime contract:

```text
Outcome.COMMITTED
Outcome.SUPERSEDED
Outcome.NOT_COMMITTED
```

An unknown outcome is represented by resolver failure, surfaced by `PersistenceCoordinator` as `PublicationOutcomeUnknownException`.

The async failure classifier MUST therefore treat:

```text
PublicationOutcomeUnknownException -> AMBIGUOUS
```

and MUST NOT expect `PublicationOutcomeResolver.Outcome.UNKNOWN`.

---

## 4. Job lease vs ingestion lease

### 4.1 Job lease

Purpose:

- prevent two workers from owning the same queue job simultaneously;
- fence job status updates;
- recover a job after worker/pod death.

Fields:

```text
lease_owner
lease_until
lease_version
```

Every heartbeat/finalize/retry transition MUST predicate on all required ownership fields, including `lease_version` or equivalent monotonic fence token.

### 4.2 Existing ingestion lease

Purpose:

- fence chunk/enrichment/embedding/publication side effects;
- protect a concrete service-level ingestion attempt.

The service already heartbeats this lease before/after major stages and during embedding through the existing callback.

### 4.3 Required interaction

A worker holding a valid job lease calls the service with the persisted internal idempotency key.

Possible outcomes:

#### First execution

```text
job PROCESSING
  -> service claim = CLAIMED
  -> ingestion
  -> PUBLISHED
  -> service idempotency = SUCCEEDED
  -> worker fenced finalize = INGESTED
```

#### Worker crashes after publication, before job finalize

```text
service idempotency = SUCCEEDED
job lease expires
new worker reclaims job
same internal idempotency key
service returns REPLAYED
worker marks job INGESTED
```

This is the primary crash-recovery path and is the key reason to reuse the current idempotency mechanism.

#### Worker crashes while ingestion claim is still active

New worker may reclaim the queue job after job-lease expiry, but `KnowledgeIngestionService` can return `INGESTION_IN_PROGRESS` while the existing ingestion lease remains valid.

The new worker MUST NOT classify this as permanent failure. It SHALL transition the job to `RETRY_WAIT`, using the current `retryAfterSeconds` when available.

#### Previous ingestion claim expired

Existing `IngestionIdempotencyRepository.claim(...)` owns reclaim behavior. The async worker SHALL call the service normally and MUST NOT manually mutate `knowledge_ingestion_request` or generation state.

---

## 5. Job heartbeat implementation

The service's internal idempotency heartbeat does NOT renew the outer async job lease.

Therefore the worker needs an independent lightweight heartbeat while the synchronous service invocation is running.

Required v1 design:

- one dedicated small scheduled heartbeat executor, separate from the document execution slots and separate from `ingestionExecutor`;
- heartbeat interval safely below job lease duration;
- heartbeat update guarded by `job_id + lease_owner + lease_version + status=PROCESSING + lease_until > clock_timestamp()`;
- heartbeat failure/lost ownership sets a local `ownershipLost` flag;
- after ownership loss the stale worker MUST NOT mutate job terminal state;
- business-side safety still relies on existing ingestion idempotency fencing.

The worker cannot currently interrupt `KnowledgeIngestionService` at every stage through an outer lease callback without invasive service changes. Therefore v1 safety model is:

```text
job ownership lost
   -> stale worker cannot finalize job
   -> existing ingestion idempotency still fences business publication
   -> current/new job owner eventually replays/reconciles service result
```

No invasive modification of `KnowledgeIngestionService` is required merely to propagate job heartbeat.

---

## 6. Durable job schema refinement

Use a separate table. Do NOT repurpose `knowledge_ingestion_request`.

`knowledge_ingestion_request` currently has service-level semantics and only three statuses: `IN_PROGRESS`, `SUCCEEDED`, `FAILED`. It is keyed by idempotency key and stores service replay data. Queue lifecycle semantics are different.

Target table:

```text
knowledge_ingestion_job
```

Required fields:

```text
ingestion_id             UUID PRIMARY KEY
schema_version           INTEGER NOT NULL

event_id                 VARCHAR(...) NOT NULL UNIQUE
request_id               VARCHAR(...) NULL
job_fingerprint          VARCHAR(64) NOT NULL
internal_idempotency_key VARCHAR(200) NOT NULL UNIQUE

document_id              VARCHAR(100) NOT NULL
source_type              VARCHAR(...) NOT NULL
file_id                  VARCHAR(...) NULL
source_version           VARCHAR(...) NOT NULL
content_hash             VARCHAR(...) NULL
canonical_hash           VARCHAR(64) NULL

payload_mode             VARCHAR(...) NOT NULL
payload_json             JSONB NULL
artifact_id              VARCHAR(...) NULL

job_status               VARCHAR(32) NOT NULL
attempt_count            INTEGER NOT NULL DEFAULT 0
next_attempt_at          TIMESTAMPTZ NULL

lease_owner              VARCHAR(...) NULL
lease_until              TIMESTAMPTZ NULL
lease_version            BIGINT NOT NULL DEFAULT 0

generation               BIGINT NULL
chunk_count              INTEGER NULL
embedding_profile_id     VARCHAR(128) NULL

last_error_class         VARCHAR(...) NULL
last_error_code          VARCHAR(...) NULL
last_error_message       VARCHAR(1000) NULL

accepted_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
started_at               TIMESTAMPTZ NULL
finished_at              TIMESTAMPTZ NULL
created_at               TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
updated_at               TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
```

Required indexes:

```text
UNIQUE(event_id)
UNIQUE(internal_idempotency_key)
INDEX(job_status, next_attempt_at, accepted_at)
INDEX(document_id, accepted_at DESC)
INDEX(file_id, source_version) where file_id is not null
```

### Source-version dedup

Event dedup alone is insufficient because multiple event IDs may refer to the same immutable source version.

Admission SHALL calculate a deterministic `job_fingerprint`, including at minimum:

```text
schemaVersion
+ documentId
+ sourceType
+ fileId when present
+ sourceVersion
+ canonical artifact/hash identity
```

For v1, repeated submission of the same immutable source version and same job fingerprint SHOULD return the existing job rather than create a second ingestion.

A future explicit reprocess operation MUST use a distinct command/contract rather than bypassing this invariant accidentally.

---

## 7. State machine refinement

External states remain:

```text
ACCEPTED
PROCESSING
RETRY_WAIT
INGESTED
FAILED
```

No public `UNKNOWN` state is required in v1.

Ambiguity is represented internally by failure classification and retry/reconciliation metadata.

### `INGESTED`

`INGESTED` means:

- `KnowledgeIngestionService.addCanonicalKnowledge(...)` returned a successful `KnowledgeIngestionResult`, including `PUBLISHED`, `ALREADY_PUBLISHED` or `REPLAYED` semantics accepted by the service contract;
- or a retry/recovery call returned a replay proving the publication is already durable;
- job finalization succeeds under the current job lease fence.

Post-publication semantic linking is currently best-effort and executed after the publication transaction. Therefore `INGESTED` does NOT guarantee that every optional post-publication semantic-linking side effect succeeded. It guarantees durable retrieval publication.

---

## 8. Retry mapping to current exceptions

Introduce one async-specific classifier, for example:

```text
AsyncIngestionFailureClassifier
```

It classifies, but does not alter, existing business exceptions.

Minimum mapping:

### Retryable

- `IdempotencyConflictException` with code `INGESTION_IN_PROGRESS` -> `RETRY_WAIT` using `retryAfterSeconds`;
- transient artifact/FileService fetch errors;
- transient embedding/network dependency failures when publication is known not committed;
- transient database availability failures where no ambiguous commit exists.

### Non-retryable

- invalid canonical contract / `IllegalArgumentException` from deterministic validation;
- idempotency-key reuse/fingerprint conflict that indicates corrupted job identity;
- unsupported schema version;
- permanent authorization/access violation;
- artifact hash mismatch.

### Ambiguous

- `PublicationOutcomeUnknownException`.

For ambiguous failures:

1. do not mark the async job terminal `FAILED` immediately;
2. schedule bounded retry/reconciliation;
3. reuse the same internal idempotency key;
4. allow service replay/reclaim logic to establish the durable result.

Exception-class-only classification is insufficient for every `IllegalStateException`; use explicit codes/types where available and introduce narrow async adapter classification rather than broad `RuntimeException -> retry` rules.

---

## 9. Generation stale-recovery blocker

This review identified a pre-implementation blocker in current lifecycle recovery.

`DocumentGenerationRepository.failStaleIngestionBatch(...)` currently treats a `STAGING` ingestion generation as abandoned based on:

```text
started_at < database_now - retention.leaseDuration
```

It does not consult the active ingestion idempotency lease.

`RetentionScheduler` invokes this recovery using the retention lease duration. Current default retention lease and current idempotency lease are both 10 minutes.

A valid long-running async ingestion can therefore be incorrectly failed as `STALE_INGESTION` while its idempotency claim is still alive or being renewed.

### Required correction before enabling async worker

Stale-generation recovery MUST become ownership-aware.

Preferred v1 rule:

A `STAGING` ingestion generation may be failed as stale only when there is no valid active ingestion claim protecting that exact `document_id + generation`.

Because `knowledge_ingestion_request` already stores the attached generation and lease, stale recovery SHOULD exclude generations for which a matching request exists with:

```text
request_status = 'IN_PROGRESS'
AND generation = candidate generation
AND lease_until > clock_timestamp()
```

The recovery query must remain bounded and use database time.

This correction is P0 for async ingestion.

Simply increasing `retention.lease-duration` is not sufficient because it leaves correctness dependent on expected document duration.

---

## 10. Concurrency refinement

Keep the target default:

```text
maxConcurrentIngestions = 3 per instance
```

But account for existing internal concurrency:

```text
document-level async concurrency = 3
shared chunk-level ingestion executor parallelism = 8 by default
```

The three document workers may concurrently submit chunk enrichment work into the same bounded `ingestionExecutor`; the current executor remains the shared chunk-level backpressure mechanism.

Do NOT create one new chunk executor per async job.

Performance qualification MUST therefore measure:

- async document slots: `1 / 3 / 5 / 8`;
- existing `akmai.ingestion.parallelism` interaction;
- existing ingestion queue capacity;
- embedding request concurrency;
- datasource pool saturation;
- generation publication transaction latency;
- queue wait p50/p95/p99;
- end-to-end document ingestion p50/p95/p99.

`3` remains the default only until this matrix proves a better safe value.

---

## 11. Configuration refinement

Add a dedicated configuration record, e.g. `AsyncIngestionProperties`, rather than extending `IdempotencyProperties`.

Recommended target:

```yaml
akmai:
  ingestion:
    parallelism: 8
    queue-capacity: 128
    async-worker:
      enabled: false
      max-concurrent-ingestions: 3
      claim-batch-size: 3
      lease-duration: 2m
      heartbeat-interval: 30s
      poll-interval: 1s
      max-attempts: 5
      retry-base-delay: 5s
      retry-max-delay: 5m
      payload-max-inline-bytes: 1048576
```

Do not reuse `akmai.idempotency.lease-duration` as the async job lease configuration. They protect different ownership layers.

Validation:

- `heartbeatInterval < leaseDuration / 2` recommended;
- `claimBatchSize >= 1` and bounded;
- `maxConcurrentIngestions >= 1` and bounded;
- `claimBatchSize` SHOULD NOT materially exceed the number of immediately available execution slots in v1;
- all durations positive and bounded.

---

## 12. Transaction boundaries

### Admission transaction

Contains only:

- event/source dedup read/lock as required;
- insert/replay of `knowledge_ingestion_job`;
- no parser, model, embedding or network work.

`202` only after commit.

### Job claim transaction

Short transaction:

- `FOR UPDATE SKIP LOCKED` candidate selection;
- transition to `PROCESSING`;
- lease/fence increment;
- attempt accounting.

No ingestion work inside transaction.

### Business ingestion

Existing boundaries remain unchanged:

- idempotency claim/reclaim transactions;
- generation allocation transaction;
- external embedding outside publication transaction;
- atomic generation publication transaction.

### Job finalize transaction

Short fenced update using current job ownership token.

The job finalization transaction MUST NOT be merged into `GenerationPublicationService` in v1.

---

## 13. Required implementation components

New components:

```text
AsyncIngestionController
AsyncIngestionAdmissionService
AsyncIngestionJobRepository
AsyncIngestionJob
AsyncIngestionStatus
AsyncIngestionProperties
CanonicalIngestionWorker
AsyncIngestionWorkerPool / bounded executor
AsyncIngestionHeartbeat
AsyncIngestionFailureClassifier
AsyncIngestionStatusResponse
```

Existing components to reuse:

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

Existing components requiring targeted modification:

1. `DocumentGenerationRepository.failStaleIngestionBatch(...)` — exclude generations protected by active ingestion claims;
2. lifecycle recovery integration tests — add active-claim negative case;
3. metrics — add async queue/worker metrics;
4. `application.yml` + configuration registration — async worker properties;
5. Liquibase — `knowledge_ingestion_job` and indexes.

No changes to retrieval semantics are required.

---

## 14. Positive cases

1. Submit canonical job -> durable row -> `202 ACCEPTED` -> worker claim -> service publishes -> job `INGESTED`.
2. Duplicate `eventId`, identical fingerprint -> existing job returned, no new job.
3. New event ID but same immutable source-version fingerprint -> existing logical job returned in v1.
4. Worker crash before service call -> job lease expires -> next worker processes.
5. Worker crash during service call before publication -> same internal idempotency key safely reclaims/retries through existing service semantics.
6. Worker crash after publication before job finalize -> next worker receives service replay -> marks `INGESTED`.
7. Service returns `INGESTION_IN_PROGRESS` -> job `RETRY_WAIT` honoring retry-after.
8. Existing published generation is recovered by idempotency reclaim -> job finishes `INGESTED` without duplicate publication.
9. Three document slots run independently; a free slot immediately claims another job.
10. Active idempotency claim prevents stale-generation recovery even when generation age exceeds the old stale threshold.

---

## 15. Negative and failure-injection cases

1. `202` path DB commit fails -> no `202` success.
2. `eventId` reused with another fingerprint -> conflict; old job unchanged.
3. Artifact hash mismatch -> terminal `FAILED`, no service invocation.
4. Job lease lost -> stale worker cannot heartbeat/finalize job.
5. Two pods race claim -> only one fence token owns the job.
6. Job reclaimed while previous ingestion idempotency lease still alive -> second service call gets in-progress/retry, no duplicate publication.
7. Embedding failure -> existing generation failure semantics preserved; async classifier decides retry.
8. Publication commits then response path fails -> same service idempotency key eventually replays success.
9. `PublicationOutcomeUnknownException` -> no destructive terminal fail; bounded reconciliation/retry.
10. Retry exhaustion -> `FAILED`, preserving bounded diagnostic fields.
11. Retention scheduler runs during a valid >10-minute ingestion -> active claim protects generation from stale recovery.
12. Stale `STAGING` generation with no active claim -> existing recovery still fails it.
13. Post-publication semantic-linker failure -> job may still be `INGESTED`, because durable retrieval publication succeeded.

---

## 16. Acceptance gates

Implementation is not merge-ready until all are true:

- no duplicate RAG pipeline exists;
- worker calls `addCanonicalKnowledge(...)` with a stable persisted internal idempotency key;
- duplicate event delivery produces no duplicate job/work;
- duplicate source-version submission produces no accidental duplicate job in v1;
- job claim/finalize updates are fenced;
- job heartbeat uses PostgreSQL time;
- pod-kill recovery test passes before, during and after service invocation;
- publication replay test proves crash-after-commit recovery;
- stale-generation recovery is active-claim-aware;
- current synchronous ingestion tests remain green;
- current idempotency integration tests remain green;
- generation publication/failure-model tests remain green;
- async integration tests cover multi-pod claim races;
- benchmark matrix validates default concurrency `3` against `1/5/8`;
- DB/JDBC/embedding saturation evidence is documented;
- documentation reflects final executable behavior.

---

## 17. Implementation order

### P0 — correctness prerequisites

1. make stale-generation recovery aware of active ingestion claims;
2. add `knowledge_ingestion_job` migration and repository;
3. implement admission/dedup and `202` durability;
4. implement job lease/fencing + heartbeat;
5. derive/persist stable internal idempotency key;
6. worker invokes existing `KnowledgeIngestionService.addCanonicalKnowledge(...)`;
7. implement fenced success/retry/failure finalization;
8. add crash/replay/failure-injection tests.

### P1 — production operation

1. bounded executor default `3`;
2. metrics and backlog/queue-wait telemetry;
3. status endpoint;
4. bounded retry/backoff;
5. load/profile benchmark and DB/pool audit.

### P2 — later transport evolution

- broker adapter;
- AkmAI outbox publication event if required;
- explicit reprocess command;
- optional global cluster ingestion limiter.

---

## 18. Review conclusion

The async-ingestion concept is compatible with the current AkmAI architecture and requires substantially less new business logic than a greenfield Inbox implementation.

The strongest implementation strategy is:

```text
new durable job queue owns scheduling
+
existing idempotency claim owns ingestion side effects
+
existing generation publication owns retrieval visibility
```

The critical correction discovered by this review is that current stale-generation recovery is age-based and must be made active-claim-aware before long-running async jobs are enabled.

With that correction, the TARGET can be implemented incrementally without redesigning the existing RAG ingestion core.
