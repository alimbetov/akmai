# Async Knowledge Ingestion Worker v1 — Code-Level Implementation Blueprint

**Status:** TARGET IMPLEMENTATION ANNEX  
**Branch:** `feature/async-ingestion-worker-v1`  
**Parent authority:** `docs/architecture/async-ingestion-worker-v1.md`  
**Purpose:** bind the approved async-ingestion architecture to the actual AkmAI package layout, existing ports, repositories, transaction boundaries, configuration style, Liquibase layout and test conventions before implementation.

This document does not create a second architecture. `async-ingestion-worker-v1.md` remains the architecture authority. This annex specifies how to implement that target in the current codebase. If a code-level choice here conflicts with an invariant in the parent TARGET, the parent invariant wins and this annex must be corrected in the same change set.

---

## 1. Current code facts that constrain implementation

The implementation MUST align with these existing runtime facts:

- `KnowledgeIngestionService` implements `KnowledgeIngestionPort` and already exposes `addCanonicalKnowledge(CanonicalKnowledgeDocument, String idempotencyKey)`;
- `CanonicalKnowledgeDocument` performs deterministic constructor validation for schema version, identity, access level, source hash, stable storage identity, block uniqueness/page ranges and sensitive metadata;
- `IngestionIdempotencyRepository` already owns business-level claim/replay/renew/generation attachment/publication fencing;
- `ParallelIngestionExecutor` already uses the shared `@Qualifier("ingestionExecutor")` bounded executor;
- `BoundedExecutorFactory` is the project-standard executor factory and already provides Micrometer executor metrics/rejection accounting;
- public knowledge HTTP endpoints currently live below `/api/knowledge`;
- public API failures are normalized by `GlobalApiExceptionHandler`;
- configuration records live in `kz.alimbetov.akmai.config`, use `@ConfigurationProperties`, `@Validated` and constructor validation, and are registered by `AkmaiConfiguration`;
- Liquibase executable truth is `db/changelog/greenfield/db.changelog-greenfield.yaml`; the current last migration is `028-*`;
- `knowledge_ingestion_request` is already a service-level idempotency registry and MUST NOT become the queue table.

Consequently, async ingestion is a new orchestration subsystem, not a replacement for the existing ingestion subsystem.

---

## 2. Package placement

Use the current package boundaries instead of creating a new top-level module.

### API DTO/controller

```text
src/main/java/kz/alimbetov/akmai/knowledge/api/
    AsyncIngestionController.java
    AsyncIngestionRequest.java
    AsyncIngestionAcceptedResponse.java
    AsyncIngestionStatusResponse.java
```

### Queue/orchestration implementation

```text
src/main/java/kz/alimbetov/akmai/knowledge/ingestion/async/
    AsyncIngestionAdmissionService.java
    AsyncIngestionJobRepository.java
    AsyncIngestionJob.java
    AsyncIngestionJobStatus.java
    AsyncIngestionClaim.java
    AsyncIngestionWorker.java
    AsyncIngestionWorkerPool.java
    AsyncIngestionHeartbeat.java
    AsyncIngestionFailureClassifier.java
    AsyncIngestionFailureClass.java
    AsyncIngestionJobFingerprint.java
```

The dedicated `async` subpackage avoids mixing queue lifecycle primitives with current `PersistenceCoordinator`, `GenerationPublicationService` and chunk-level ingestion components.

### Configuration

```text
src/main/java/kz/alimbetov/akmai/config/
    AsyncIngestionProperties.java
    AsyncIngestionConfiguration.java
```

`AsyncIngestionProperties` MUST also be registered in `AkmaiConfiguration` unless `@EnableConfigurationProperties` is provided locally by `AsyncIngestionConfiguration`; choose one registration mechanism, not both.

### Tests

Mirror production packages:

```text
src/test/java/kz/alimbetov/akmai/knowledge/ingestion/async/
src/test/java/kz/alimbetov/akmai/knowledge/api/
src/test/java/kz/alimbetov/akmai/knowledge/lifecycle/
```

---

## 3. Public HTTP contract aligned to current project routes

Do NOT introduce `/api/v1/...` only for this feature while the existing knowledge API is rooted at `/api/knowledge`.

Use:

```http
POST /api/knowledge/ingestions
GET  /api/knowledge/ingestions/{ingestionId}
```

A future project-wide API versioning migration may version all knowledge endpoints together.

### `AsyncIngestionController`

Target shape:

```java
@RestController
@RequestMapping("/api/knowledge/ingestions")
@RequiredArgsConstructor
public class AsyncIngestionController {

    private final AsyncIngestionAdmissionService admissionService;
    private final KnowledgeAccessLevelAuthorizer accessLevelAuthorizer;

    @PostMapping
    public ResponseEntity<AsyncIngestionAcceptedResponse> submit(
            @Valid @RequestBody AsyncIngestionRequest request
    ) {
        accessLevelAuthorizer.requireWriteAccess(request.accessLevel());
        AsyncIngestionAcceptedResponse accepted = admissionService.accept(request);
        return ResponseEntity.accepted().body(accepted);
    }

    @GetMapping("/{ingestionId}")
    public AsyncIngestionStatusResponse status(@PathVariable UUID ingestionId) {
        return admissionService.status(ingestionId);
    }
}
```

The exact read-service split MAY use a separate `AsyncIngestionQueryService`, but the controller MUST remain thin.

Do not add async methods to the existing `KnowledgeIngestionPort`; that port represents the business ingestion engine. The async controller targets the admission service, while the worker later calls the existing `KnowledgeIngestionPort`.

---

## 4. Request DTO: fields required before `202`

The admission request MUST contain enough information to authenticate/authorize and deduplicate before accepting responsibility.

Recommended record:

```java
public record AsyncIngestionRequest(
        int schemaVersion,
        String eventId,
        String requestId,
        String documentId,
        long accessLevel,
        CanonicalKnowledgeDocument canonicalDocument
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
```

For v1, prefer `INLINE` canonical payload as the first implementation path. It gives the strongest interpretation of `202`: after the job transaction commits, AkmAI owns a durable snapshot of the exact canonical payload it will process.

The DB model MAY keep `payload_mode` and artifact-reference columns for forward compatibility, but `ARTIFACT_REF` MUST NOT be enabled until a real immutable artifact-loading port and FileService retention contract exist.

Reasons not to implement a placeholder direct RustFS fetch in AkmAI:

- FileService is intended to own file/object storage semantics;
- storage URLs are explicitly not identity;
- no current FileService client/port exists in AkmAI;
- introducing direct RustFS coupling would violate the bounded-context direction already established for `CanonicalKnowledgeDocument`.

When `ARTIFACT_REF` is implemented later, add an explicit port such as:

```java
interface CanonicalKnowledgeArtifactLoader {
    CanonicalKnowledgeDocument load(CanonicalArtifactReference reference);
}
```

and place storage/FileService adaptation behind that port.

### Admission consistency checks

Before durable insert, validate at minimum:

```text
request.schemaVersion == supported async schema
request.documentId == canonicalDocument.documentId
request.accessLevel == canonicalDocument.accessLevel
canonicalDocument.source/sourceVersion/hash already valid by constructor
```

The constructor validation in `CanonicalKnowledgeDocument` remains authoritative for canonical structure. Do not duplicate all block/schema validation in the controller.

---

## 5. Request-size decision

Current API limits are designed around the existing endpoints. Before enabling large inline canonical documents, explicitly qualify the payload size against `akmai.api.max-request-bytes` and Spring request limits.

Implementation rule:

- do not silently raise global request limits just to make async ingestion pass;
- introduce a documented async canonical payload bound if needed;
- if representative FileService canonical payloads exceed a safe HTTP/JSONB bound, implement the immutable artifact-loader port before production rollout instead of allowing unbounded JSONB payloads.

`payload-max-inline-bytes` is therefore an admission guard, not a promise that arbitrarily large parsed documents are acceptable inline.

---

## 6. Job identity and fingerprint implementation

### `ingestionId`

Use Java `UUID` to match current database/JDBC conventions. Do not add a ULID library solely for this feature.

```java
UUID ingestionId = UUID.randomUUID();
```

UUIDv7 can be adopted later project-wide if desired.

### Stable internal business idempotency key

Persist exactly once:

```java
String internalIdempotencyKey = "async-ingestion:" + ingestionId;
```

This remains well below the existing `knowledge_ingestion_request.idempotency_key VARCHAR(200)` bound.

### `AsyncIngestionJobFingerprint`

Create one deterministic component. Do not reuse `CanonicalRequestFingerprint` for the outer job fingerprint: the two fingerprints have different semantic purposes.

Recommended input:

```text
async schemaVersion
+ event-independent documentId
+ accessLevel
+ canonicalDocument.source.type
+ fileId
+ sourceVersion
+ contentHash
+ canonical hash or deterministic canonical payload hash
```

The canonical business hash used by `KnowledgeIngestionService` remains `CanonicalRequestFingerprint.canonicalHash(document)` and MUST NOT be replaced by this outer fingerprint.

Hash output: lowercase SHA-256 hex, fixed 64 chars.

---

## 7. Liquibase migration plan

Add:

```text
src/main/resources/db/changelog/greenfield/029-async-ingestion-worker.sql
```

and include it after `028-audit-partition-maintenance-ownership.sql` in `db.changelog-greenfield.yaml`.

Do not modify `005-operational.sql` retrospectively.

### Target DDL

```sql
--liquibase formatted sql

--changeset akmai-greenfield:029-async-ingestion-worker
CREATE TABLE knowledge_ingestion_job (
    ingestion_id              UUID PRIMARY KEY,
    schema_version            INTEGER NOT NULL,
    event_id                  VARCHAR(200) NOT NULL,
    request_id                VARCHAR(200),
    job_fingerprint           VARCHAR(64) NOT NULL,
    internal_idempotency_key  VARCHAR(200) NOT NULL,

    document_id               VARCHAR(100) NOT NULL,
    access_level              BIGINT NOT NULL,
    source_type               VARCHAR(32) NOT NULL,
    file_id                   VARCHAR(200),
    source_version            VARCHAR(200) NOT NULL,
    content_hash              VARCHAR(71) NOT NULL,
    canonical_hash            VARCHAR(71),

    payload_mode              VARCHAR(32) NOT NULL,
    payload_json              JSONB,
    artifact_id               VARCHAR(200),

    job_status                VARCHAR(32) NOT NULL,
    attempt_count             INTEGER NOT NULL DEFAULT 0,
    next_attempt_at           TIMESTAMPTZ,

    lease_owner               VARCHAR(200),
    lease_until               TIMESTAMPTZ,
    lease_version             BIGINT NOT NULL DEFAULT 0,

    generation                BIGINT,
    chunk_count               INTEGER,
    embedding_profile_id      VARCHAR(128),

    last_error_class          VARCHAR(32),
    last_error_code           VARCHAR(128),
    last_error_message        VARCHAR(1000),

    accepted_at               TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at                TIMESTAMPTZ,
    finished_at               TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uq_knowledge_ingestion_job_event UNIQUE (event_id),
    CONSTRAINT uq_knowledge_ingestion_job_internal_key
        UNIQUE (internal_idempotency_key),
    CONSTRAINT ck_knowledge_ingestion_job_status
        CHECK (job_status IN (
            'ACCEPTED', 'PROCESSING', 'RETRY_WAIT', 'INGESTED', 'FAILED'
        )),
    CONSTRAINT ck_knowledge_ingestion_job_payload_mode
        CHECK (payload_mode IN ('INLINE', 'ARTIFACT_REF')),
    CONSTRAINT ck_knowledge_ingestion_job_attempt
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_knowledge_ingestion_job_access
        CHECK (access_level > 0),
    CONSTRAINT ck_knowledge_ingestion_job_payload
        CHECK (
            (payload_mode = 'INLINE' AND payload_json IS NOT NULL AND artifact_id IS NULL)
            OR
            (payload_mode = 'ARTIFACT_REF' AND payload_json IS NULL AND artifact_id IS NOT NULL)
        )
);

CREATE INDEX idx_knowledge_ingestion_job_claim
    ON knowledge_ingestion_job(job_status, next_attempt_at, accepted_at, ingestion_id);

CREATE INDEX idx_knowledge_ingestion_job_document
    ON knowledge_ingestion_job(document_id, accepted_at DESC);

CREATE INDEX idx_knowledge_ingestion_job_source_version
    ON knowledge_ingestion_job(file_id, source_version)
    WHERE file_id IS NOT NULL;
```

Exact varchar bounds may be adjusted to existing FileService contract limits, but they MUST remain bounded.

Do not add a foreign key from the job to `knowledge_ingestion_request`: the idempotency row is created lazily by the business service and replay/reclaim must remain decoupled.

---

## 8. P0 stale-generation correction at SQL level

Modify only the candidate predicate in `DocumentGenerationRepository.failStaleIngestionBatch(...)`; preserve its bounded `FOR UPDATE SKIP LOCKED` behavior and existing failure/lifecycle updates.

Current candidate selection must be hardened with an active-claim exclusion equivalent to:

```sql
AND NOT EXISTS (
    SELECT 1
    FROM knowledge_ingestion_request r
    WHERE r.document_id = knowledge_document_generation.document_id
      AND r.generation = knowledge_document_generation.generation
      AND r.request_status = 'IN_PROGRESS'
      AND r.lease_until > clock_timestamp()
)
```

Use table aliases cleanly in the real query.

Do not add another application-clock check. PostgreSQL time remains authoritative.

### Required regression tests

Extend `StaleIngestionRecoveryIntegrationTest` with at least:

1. old `STAGING` + active unexpired matching claim -> not failed;
2. old `STAGING` + expired matching claim -> failed as stale;
3. old `STAGING` + no claim -> failed as stale;
4. active claim for same document but different generation -> does not protect candidate generation;
5. `SUCCEEDED`/`FAILED` request row -> does not protect stale `STAGING` generation.

This is the first implementation commit and must pass before async worker code is enabled.

---

## 9. Repository API and transaction boundaries

`AsyncIngestionJobRepository` MUST own all SQL for queue lifecycle. Worker/service classes must not embed ad-hoc job SQL.

Recommended public methods:

```java
public AdmissionResult admit(AsyncIngestionJobDraft draft);

public List<AsyncIngestionClaim> claimEligible(
        String workerId,
        int limit,
        Duration leaseDuration
);

public boolean renew(
        AsyncIngestionClaim claim,
        Duration leaseDuration
);

public boolean markRetry(
        AsyncIngestionClaim claim,
        Instant nextAttemptAt,
        AsyncIngestionFailure failure
);

public boolean markIngested(
        AsyncIngestionClaim claim,
        KnowledgeIngestionResult result
);

public boolean markFailed(
        AsyncIngestionClaim claim,
        AsyncIngestionFailure failure
);

public Optional<AsyncIngestionJob> find(UUID ingestionId);

public long countBacklog();
```

### Admission transaction

Use `TransactionTemplate`, consistent with current repositories:

```text
BEGIN
insert job ON CONFLICT(event_id) DO NOTHING
select existing/new row by event_id
compare job_fingerprint
COMMIT
```

If same `eventId` has different fingerprint, throw a dedicated conflict such as:

```text
AsyncIngestionConflictException(
    code = "ASYNC_INGESTION_EVENT_REUSE"
)
```

Do not reuse `IdempotencyConflictException` for outer event dedup unless the code semantics are explicitly expanded. Keeping transport/job conflict separate from business-ingestion idempotency makes error classification clearer.

### Claim transaction

Claim in one transaction. Preferred shape is a CTE `SELECT ... FOR UPDATE SKIP LOCKED` followed by `UPDATE ... RETURNING`, so ownership data returned to the worker is the exact committed lease/fence state.

Example:

```sql
WITH candidates AS (
    SELECT ingestion_id
    FROM knowledge_ingestion_job
    WHERE job_status = 'ACCEPTED'
       OR (job_status = 'RETRY_WAIT' AND next_attempt_at <= clock_timestamp())
       OR (job_status = 'PROCESSING' AND lease_until < clock_timestamp())
    ORDER BY accepted_at, ingestion_id
    FOR UPDATE SKIP LOCKED
    LIMIT ?
)
UPDATE knowledge_ingestion_job j
SET job_status = 'PROCESSING',
    lease_owner = ?,
    lease_until = clock_timestamp() + (? * interval '1 millisecond'),
    lease_version = lease_version + 1,
    attempt_count = attempt_count + 1,
    started_at = COALESCE(started_at, clock_timestamp()),
    updated_at = clock_timestamp()
FROM candidates c
WHERE j.ingestion_id = c.ingestion_id
RETURNING j.*;
```

### Fenced terminal updates

Every `renew/markRetry/markIngested/markFailed` mutation after claim MUST predicate on:

```sql
WHERE ingestion_id = ?
  AND job_status = 'PROCESSING'
  AND lease_owner = ?
  AND lease_version = ?
  AND lease_until > clock_timestamp()
```

`updated == 0` means ownership lost, not a generic DB failure.

---

## 10. Worker dependency rule

`AsyncIngestionWorker` SHOULD depend on the existing interface:

```java
KnowledgeIngestionPort
```

not directly on `KnowledgeIngestionService`.

Invocation:

```java
KnowledgeIngestionResult result = ingestionPort.addCanonicalKnowledge(
        document,
        claim.internalIdempotencyKey()
);
```

This preserves the existing application port boundary and makes worker tests simpler.

Worker MUST NOT inject or call:

```text
HierarchicalChunker
ParallelIngestionExecutor
PersistenceCoordinator
GenerationPublicationService
DocumentGenerationRepository
IngestionIdempotencyRepository
```

Those remain behind `KnowledgeIngestionPort`.

---

## 11. Worker execution algorithm

Target algorithm for one claimed job:

```java
void process(AsyncIngestionClaim claim) {
    Ownership ownership = heartbeat.start(claim);
    try {
        CanonicalKnowledgeDocument document = payloadLoader.load(claim.job());
        verifyJobAgainstPayload(claim.job(), document);

        KnowledgeIngestionResult result = ingestionPort.addCanonicalKnowledge(
                document,
                claim.internalIdempotencyKey()
        );

        if (!ownership.isCurrent()) {
            return;
        }

        repository.markIngested(claim, result);
    } catch (RuntimeException ex) {
        if (!ownership.isCurrent()) {
            return;
        }
        handleFailure(claim, ex);
    } finally {
        ownership.close();
    }
}
```

Important: ownership loss prevents outer job-state mutation. It does not attempt to interrupt arbitrary inner service stages in v1. Existing ingestion idempotency fencing continues to protect publication.

### Payload verification before service call

Verify:

```text
job.documentId == canonical.documentId
job.accessLevel == canonical.accessLevel
job.sourceVersion == canonical.source.sourceVersion
job.fileId == canonical.source.fileId when present
job.contentHash == canonical.source.contentHash
```

For inline payload this detects DB corruption/programming defects. For future artifact loading it also detects wrong artifact/version resolution.

---

## 12. Worker pool implementation

Use project infrastructure instead of a custom raw executor.

`AsyncIngestionConfiguration` should create a dedicated monitored executor using:

```java
BoundedExecutorFactory.createMonitored(
        properties.maxConcurrentIngestions(),
        properties.workerQueueCapacity(),
        meterRegistry,
        "async-ingestion"
)
```

Recommended initial worker queue capacity should remain small because PostgreSQL is the durable queue. Do not duplicate a second large in-memory backlog. A value in the `3..12` range should be benchmarked; initial target may equal `maxConcurrentIngestions`.

The DB claim loop MUST consider locally available capacity before claiming. It must not lease six jobs when all execution slots and local queue capacity are already occupied.

The existing `ingestionExecutor` remains untouched and is still used by `ParallelIngestionExecutor` for chunk enrichment.

---

## 13. Poll/drain scheduler

Use a lightweight scheduler component, for example:

```java
@Component
@RequiredArgsConstructor
public class AsyncIngestionScheduler {

    @Scheduled(fixedDelayString = "${akmai.ingestion.async-worker.poll-interval}")
    void drain() { ... }
}
```

The scheduler must:

1. return immediately when `enabled=false`;
2. inspect worker-pool free capacity;
3. claim no more than `min(freeCapacity, claimBatchSize)`;
4. submit claimed jobs;
5. never block waiting for document completion.

Do not claim a large batch and then wait serially.

Use a stable runtime worker id similar to existing lifecycle schedulers (process/pod identity); the fence token remains the actual correctness guard, so worker-id uniqueness is operationally useful but not the sole safety mechanism.

---

## 14. Outer heartbeat implementation

`AsyncIngestionHeartbeat` should use a `ScheduledExecutorService` with very small fixed thread count (normally 1 per instance is sufficient for three active jobs).

Each active claim schedules renewal at `heartbeatInterval`.

On `repository.renew(...) == false` or an ownership-conflict result:

```text
ownership.current = false
metric async_ingestion_job_lease_lost_total++
```

Transient DB exceptions during heartbeat MUST NOT be silently converted to ownership success. Define a conservative bounded policy: if ownership cannot be proven before lease expiry, the worker treats ownership as lost for outer job mutation.

Do not use the document worker executor or shared `ingestionExecutor` for heartbeat scheduling.

---

## 15. Failure classifier mapped to current exceptions

Implement one `AsyncIngestionFailureClassifier` with explicit ordered rules.

### `IdempotencyConflictException`

```text
code == INGESTION_IN_PROGRESS
    -> RETRYABLE
    -> use retryAfterSeconds when present

code == INGESTION_IDEMPOTENCY_LOST
    -> RETRYABLE/AMBIGUOUS recovery path using same internal key

code == IDEMPOTENCY_KEY_REUSE
    -> NON_RETRYABLE, because stable internal key/fingerprint invariant is broken
```

### `PublicationOutcomeUnknownException`

```text
-> AMBIGUOUS
-> bounded retry/reconciliation
-> same internal idempotency key
```

### Deterministic canonical validation

`IllegalArgumentException` thrown while constructing/deserializing/verifying the canonical contract is non-retryable.

Do NOT globally classify every `IllegalArgumentException` from every dependency as permanent without context. Classification should occur around known stages (`load/verify`, service call, persistence).

### Executor saturation

`RejectedExecutionException` from the document worker executor should not be possible after correct capacity accounting. If encountered, release/retry the claimed job safely rather than losing it.

### Unknown runtime failures

Do not default all unknown `RuntimeException` to immediate permanent failure. Prefer a bounded retry category for infrastructure-looking failures only when publication ambiguity is excluded. Add explicit exception mappings as the implementation reveals concrete dependency exception types.

---

## 16. Attempt semantics

`attempt_count` increments on successful outer job claim, including expired-lease reclaim.

Do not increment it merely because admission is replayed.

Retry budget applies to outer processing attempts. `INGESTION_IN_PROGRESS` caused by an inner lease that is still legitimately active SHOULD NOT rapidly burn all attempts; either:

- do not count pure `INGESTION_IN_PROGRESS` waits against terminal retry budget, or
- maintain a separate wait/retry counter.

Preferred v1: keep `attempt_count` as claim count for observability and use a separate `failure_attempt_count` if terminal retry exhaustion must exclude harmless in-progress waits. Do not overload one counter with two meanings.

If schema simplicity is preferred, then max-attempt semantics must explicitly exempt `INGESTION_IN_PROGRESS` from terminal exhaustion.

---

## 17. Configuration class aligned with existing style

Target:

```java
@Validated
@ConfigurationProperties(prefix = "akmai.ingestion.async-worker")
public record AsyncIngestionProperties(
        boolean enabled,
        int maxConcurrentIngestions,
        int claimBatchSize,
        int workerQueueCapacity,
        Duration leaseDuration,
        Duration heartbeatInterval,
        Duration pollInterval,
        int maxAttempts,
        Duration retryBaseDelay,
        Duration retryMaxDelay,
        long payloadMaxInlineBytes
) {
    public AsyncIngestionProperties {
        // fail-fast range and cross-field validation
    }
}
```

Minimum validation:

```text
1 <= maxConcurrentIngestions <= bounded operational maximum
1 <= claimBatchSize <= bounded operational maximum
workerQueueCapacity >= 1
leaseDuration > 0
heartbeatInterval > 0
heartbeatInterval * 2 < leaseDuration (prefer stronger margin, e.g. *3 or *4)
pollInterval > 0
maxAttempts >= 1
retryBaseDelay > 0
retryMaxDelay >= retryBaseDelay
payloadMaxInlineBytes > 0
```

Register with the project configuration mechanism and add defaults to `application.yml` under the existing `akmai.ingestion` section.

Feature default MUST remain `enabled: false` until P0, migration and integration suite pass.

---

## 18. API exception integration

Add narrow async exceptions rather than throwing generic `IllegalStateException` from controllers:

```text
AsyncIngestionConflictException
AsyncIngestionNotFoundException
AsyncIngestionUnavailableException (only if admission store unavailable is intentionally surfaced)
```

Extend `GlobalApiExceptionHandler`:

```text
AsyncIngestionConflictException -> 409
AsyncIngestionNotFoundException -> 404
```

Validation continues through the existing validation handler (`IllegalArgumentException`, malformed JSON, Bean Validation).

Do not leak SQL exception messages, lease owner/fence values or payload contents in error responses.

---

## 19. Status response mapping

`AsyncIngestionStatusResponse` should be a pure read model, not the database entity.

Recommended fields:

```java
public record AsyncIngestionStatusResponse(
        int schemaVersion,
        UUID ingestionId,
        String documentId,
        AsyncIngestionJobStatus status,
        int attemptCount,
        Publication publication,
        String errorCode,
        Instant acceptedAt,
        Instant startedAt,
        Instant finishedAt
) { ... }
```

Expose bounded stable `errorCode`; do not expose raw `last_error_message` by default unless an authenticated operational/admin endpoint explicitly requires it.

Do not expose:

```text
internalIdempotencyKey
leaseOwner
leaseUntil
leaseVersion
payloadJson
stack traces
storage credentials
```

---

## 20. Metrics integration

Extend `AkmaiMetrics` for business-level async queue metrics, while executor saturation remains automatically instrumented by `BoundedExecutorFactory.createMonitored`.

Suggested methods:

```text
asyncIngestionQueueDepth(long value)
asyncIngestionTransition(String outcome)
asyncIngestionQueueWait(Duration duration)
asyncIngestionProcessing(Duration duration, String outcome)
asyncIngestionLeaseRecovered()
asyncIngestionLeaseLost()
asyncIngestionReplayRecovered()
asyncIngestionAmbiguous()
```

Keep metric tag cardinality bounded. Never tag metrics by `documentId`, `fileId`, `ingestionId` or exception message.

Structured logs may contain bounded correlation IDs, but not payload text.

---

## 21. Exact code reuse / no-touch matrix

### Reuse directly

```text
KnowledgeIngestionPort.addCanonicalKnowledge(...)
CanonicalKnowledgeDocument
CanonicalRequestFingerprint.canonicalHash(...)
IngestionIdempotencyRepository behavior
PersistenceCoordinator
GenerationPublicationService
PublicationOutcomeResolver
ParallelIngestionExecutor
BoundedExecutorFactory
KnowledgeAccessLevelAuthorizer
GlobalApiExceptionHandler pattern
AkmaiMetrics pattern
```

### Modify narrowly

```text
DocumentGenerationRepository.failStaleIngestionBatch(...)
StaleIngestionRecoveryIntegrationTest
AkmaiConfiguration (properties registration)
application.yml
AkmaiMetrics
GlobalApiExceptionHandler
Liquibase greenfield changelog
```

### Do not modify for v1 unless a failing test proves necessity

```text
KnowledgeIngestionService business algorithm
PersistenceCoordinator transaction structure
GenerationPublicationService publication transaction
IngestionIdempotencyRepository claim/replay semantics
ParallelIngestionExecutor scheduling algorithm
retrieval pipeline
adaptive graph pipeline
```

This minimizes regression surface.

---

## 22. Unit-test file plan

Add at minimum:

```text
AsyncIngestionJobFingerprintTest
AsyncIngestionAdmissionServiceTest
AsyncIngestionJobRepositoryTest
AsyncIngestionFailureClassifierTest
AsyncIngestionWorkerTest
AsyncIngestionHeartbeatTest
AsyncIngestionSchedulerTest
AsyncIngestionControllerTest
```

Key unit assertions:

- same command identity -> same fingerprint;
- same eventId/different fingerprint -> conflict;
- controller performs write-access authorization before admission;
- worker calls only `KnowledgeIngestionPort.addCanonicalKnowledge`;
- worker reuses persisted internal idempotency key;
- lost outer ownership prevents terminal update;
- `INGESTION_IN_PROGRESS` produces `RETRY_WAIT` with safe delay;
- `PublicationOutcomeUnknownException` never becomes direct terminal `FAILED`;
- successful `REPLAYED` result becomes `INGESTED`.

---

## 23. PostgreSQL integration-test plan

Use the same PostgreSQL/Testcontainers style already used by lifecycle/idempotency integration tests.

Add:

```text
AsyncIngestionJobRepositoryIntegrationTest
AsyncIngestionAdmissionRaceIntegrationTest
AsyncIngestionClaimRaceIntegrationTest
AsyncIngestionCrashRecoveryIntegrationTest
AsyncIngestionEndToEndIntegrationTest
```

Mandatory DB assertions:

- admission is durable before response layer observes success;
- concurrent same-event admissions yield exactly one row;
- `SKIP LOCKED` claims distribute jobs without duplicate owner;
- `lease_version` increases on reclaim;
- stale fence cannot renew/finalize;
- retry job is not claimable before DB `next_attempt_at`;
- expired processing job is reclaimable;
- terminal jobs are never claimable;
- crash-after-publication replay produces one published generation and terminal `INGESTED` job;
- P0 stale-generation protection works with an active `knowledge_ingestion_request` lease.

---

## 24. Transaction-boundary assertions

Keep these boundaries explicit in tests/documentation:

### Admission transaction

```text
knowledge_ingestion_job insert/dedup only
no model/network work
```

### Job claim transaction

```text
select eligible + lease/fence update only
short transaction
```

### Business ingestion

```text
existing KnowledgeIngestionService
existing internal short transactions + model/network work outside publication transaction as currently designed
```

### Generation publication transaction

```text
unchanged existing GenerationPublicationService authority
```

### Job finalization transaction/update

```text
small fenced update after business service returns
```

Never hold a job-table row lock while calling `KnowledgeIngestionPort` or an external dependency.

---

## 25. Implementation commit sequence

Recommended implementation sequence in this branch:

1. `fix: protect active ingestion claims from stale generation cleanup`
2. `db: add durable async ingestion job schema`
3. `feat: add async ingestion job model and repository`
4. `feat: add async ingestion admission and status API`
5. `feat: add async ingestion worker pool and heartbeat`
6. `feat: wire async worker to canonical ingestion port`
7. `test: add async ingestion failure injection and multipod races`
8. `perf: qualify async ingestion concurrency and queue settings`
9. `docs: promote async ingestion target to CURRENT only after implementation`

Do not mix the P0 lifecycle correctness fix with worker implementation in one opaque commit; it must remain independently reviewable.

---

## 26. Pre-implementation gate

Implementation may start only when reviewers agree on all of these code-level choices:

- route is `/api/knowledge/ingestions` to match current API layout;
- `accessLevel` is present in admission identity and authorized before `202`;
- v1 first implementation path is durable inline canonical snapshot; artifact-reference loading is disabled until a real loader port exists;
- worker depends on `KnowledgeIngestionPort`, not `KnowledgeIngestionService` concrete type;
- PostgreSQL table is introduced by new `029-*` migration;
- `BoundedExecutorFactory` is reused for document worker execution;
- job heartbeat has a separate scheduler from both document workers and chunk ingestion executor;
- `DocumentGenerationRepository.failStaleIngestionBatch` is hardened first;
- no publication/idempotency transaction widening is introduced;
- outer retry classification is explicit and does not duplicate inner business idempotency state.

Once this gate is accepted, implementation should be mechanical rather than architectural.
