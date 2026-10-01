# AKMAI — Chunk Lifecycle & Retention

Status: design specification  
Branch: `feat/chunk-lifecycle-retention`  
Base: `8180c5451aa375a64e272c177e297abd45972f21`

## 1. Goal

Introduce a production-safe lifecycle for ingested knowledge so expired documents and all of
their derived retrieval state are removed in bounded nightly batches.

The feature must prevent stale knowledge from remaining searchable while avoiding partial
document deletion and uncontrolled full-table cleanup.

## 2. Core invariant

Retention is document-scoped.

A document version owns the lifecycle policy and expiration timestamp. Its chunks inherit that
lifecycle. Individual chunks MUST NOT expire independently from other chunks of the same
document version.

For a document that is no longer active, no stale retrieval state may remain in:

- canonical search projections;
- identifier index;
- vector store.

## 3. Lifecycle policies

`PERMANENT`
: No TTL cleanup. The document remains active until explicitly replaced/deleted.

`TTL`
: The document becomes eligible for retention after `expires_at`.

`REPLACE_ON_REINGESTION` is NOT a retention policy. It is the existing ingestion replacement
behavior and remains separate from retention. Retention policy contains only `PERMANENT` and
`TTL`; this separation prevents ingestion semantics from leaking into expiration selection.

Default retention policy MUST be configurable. Production defaults SHOULD be conservative; permanent
knowledge must never disappear because a scheduler inferred a TTL.

## 4. Lifecycle states

- `READY` — active and retrievable.
- `DELETE_PENDING` — expired and claimed for deletion.
- `DELETING` — cleanup is in progress.
- `DELETE_FAILED` — a cleanup attempt failed and is retryable.
- `DELETED` — retrieval state has been removed.

Allowed transitions:

```text
READY --expiry--> DELETE_PENDING --> DELETING --> DELETED
                                      |
                                      +--> DELETE_FAILED --> DELETE_PENDING
```

A retry MUST be safe after any partial cleanup.

## 5. Data model

Introduce a document lifecycle table rather than attaching independent TTL state to every
projection row.

Required fields:

- `document_id`
- `lifecycle_policy`
- `lifecycle_status`
- `created_at`
- `updated_at`
- `expires_at` nullable
- `delete_started_at` nullable
- `deleted_at` nullable
- `attempt_count`
- `last_error` nullable
- `generation` BIGINT NOT NULL — monotonically increases on each accepted reingestion
- `claim_generation` BIGINT nullable — generation captured when retention claims the row
- `claimed_by` nullable — opaque pod/worker identity
- `claimed_at` nullable
- `lease_until` nullable — claim recovery deadline
- optimistic row version if required by the chosen locking implementation

Indexes MUST support selection by `lifecycle_status, expires_at`.

The migration MUST be additive and non-destructive.

## 6. Ingestion contract

On successful ingestion the lifecycle record must be created/upserted consistently with the
document identity.

Rules:

1. `PERMANENT` requires `expires_at IS NULL`.
2. `TTL` requires a valid future expiration timestamp or a configured default TTL.
3. Reingestion MUST explicitly define whether it preserves, refreshes, or replaces expiration.
   Initial AKMAI rule: reingestion refreshes TTL from the new ingestion request/policy.
4. A reingested document that was `DELETE_FAILED` or `DELETE_PENDING` becomes `READY`
   only as part of a successful new ingestion.
5. Retrieval must never intentionally expose a document in `DELETED` state.

## 7. Nightly scheduler

Component: `ChunkRetentionScheduler`.

Default configuration:

```yaml
akmai:
  retention:
    enabled: true
    cron: "0 30 3 * * *"
    zone: "Asia/Almaty"
    batch-size: 500
    max-batches-per-run: 20
    retry-limit: 5
    worker-parallelism: 4
    queue-capacity: 16
    lease-duration: 10m
    default-policy: PERMANENT
    default-ttl: 90d
```

The scheduler only orchestrates. Business deletion logic belongs in
`ChunkRetentionService`.

One scheduled run MUST be bounded by both batch size and maximum batches.

Per-pod execution MUST also use a fixed-size worker pool and bounded queue. Queue saturation must apply backpressure; it must not create unbounded threads/tasks. Cluster concurrency is therefore bounded by `pod_count × worker_parallelism`.

## 8. Claiming and concurrency

Multiple application instances may execute the scheduler.

Expired work MUST be claimed atomically so two nodes cannot process the same document
concurrently.

Preferred PostgreSQL implementation:

```sql
SELECT document_id
FROM knowledge_document_lifecycle
WHERE lifecycle_status IN ('READY', 'DELETE_FAILED')
  AND lifecycle_policy = 'TTL'
  AND expires_at <= :now
  AND attempt_count < :retryLimit
ORDER BY expires_at, document_id
FOR UPDATE SKIP LOCKED
LIMIT :batchSize;
```

Every application pod may run the retention scheduler. There is deliberately no singleton scheduler requirement: PostgreSQL claiming distributes work across pods.

The claim transaction changes selected rows to `DELETE_PENDING`, records `claimed_by`, `claimed_at`, `lease_until`, copies `generation` into
`claim_generation`, and commits before external vector cleanup begins.

Every destructive step MUST verify that the lifecycle row still has
`generation = claim_generation` and is in the expected deletion state. Reingestion increments
`generation` before publishing the new generation. A stale retention worker MUST abort before
vector deletion when its claim generation no longer matches.

Do not hold a database transaction open while calling the vector store.

Claims are leases, not permanent ownership. If a pod crashes, work in `DELETE_PENDING`, `DELETING`, or `DELETE_FAILED` becomes reclaimable after `lease_until`. Reclaiming increments the attempt counter where appropriate and must preserve generation fencing. A healthy worker may renew its lease for a bounded operation if needed.

## 9. Deletion algorithm

For each claimed document:

1. transition `DELETE_PENDING -> DELETING`;
2. verify `generation = claim_generation` and read canonical chunk IDs for the claimed generation;
3. re-check the generation fence immediately before vector deletion, then delete only those chunk IDs from the vector store;
4. delete document identifiers;
5. delete canonical search projections;
6. verify/record completion;
7. transition to `DELETED`.

All operations MUST be idempotent. Missing vectors/identifiers/projections are treated as
already cleaned, not as fatal corruption.

If an operation fails:

- persist `DELETE_FAILED`;
- increment `attempt_count`;
- persist a bounded/sanitized `last_error`;
- retry on a later run while below `retry-limit`.

## 10. Transaction boundary

PostgreSQL and pgvector/Spring AI VectorStore must not be presented as one ACID transaction.

Database state transitions are transactional. External/vector cleanup is retryable and
idempotent.

The lifecycle state is therefore the recovery journal.

A crash at any point must leave enough state for the next run to converge to either `READY`
or `DELETED`; it must not require manual reconstruction for ordinary failures.

## 11. Retrieval consistency

Retention protects retrieval quality as well as storage.

After `DELETED`:

- lexical search must return no chunk from the document;
- identifier lookup must return no identifier from the document;
- vector search must return no vector from the document;
- reference expansion must not resurrect the document through stale identifiers/projections.

During `DELETE_PENDING/DELETING`, temporary degradation is acceptable, but the worker must
converge and expose metrics for failures.

## 12. Observability

Required structured logs:

- run start/end;
- claimed document count;
- deleted document/chunk count;
- failed document ID and lifecycle attempt;
- run duration.

Never log full document text or embeddings.

Required metrics when Micrometer is introduced:

- `akmai_retention_claimed_total`
- `akmai_retention_deleted_total`
- `akmai_retention_failed_total`
- `akmai_retention_chunks_deleted_total`
- `akmai_retention_run_duration`
- `akmai_retention_backlog`

## 13. Safety controls

- retention can be disabled by configuration;
- default policy is `PERMANENT`;
- no unbounded `DELETE WHERE expires_at < now()`;
- no full vector-store wipe;
- delete vectors only by canonical chunk IDs;
- batch selection is deterministic;
- cleanup is safe to rerun;
- `last_error` is length-bounded and must not contain document content;
- scheduler timezone is explicit.

## 14. API scope

The first implementation MAY keep lifecycle metadata internal to ingestion.

Before public TTL control is exposed, API validation must prevent:

- negative/zero TTL;
- expiration in the past unless explicitly supported;
- arbitrary status transitions;
- clients directly setting `DELETE_PENDING/DELETING/DELETED`.

Lifecycle state is server-owned.

## 15. Tests / acceptance gates

### Unit

- policy validation;
- TTL calculation;
- legal/illegal state transitions;
- batch limit;
- retry limit;
- idempotent missing-state cleanup.

### PostgreSQL Testcontainers

- Liquibase creates lifecycle schema/indexes;
- expired TTL document is claimable;
- future TTL document is not claimable;
- PERMANENT document is not claimable;
- concurrent claims are disjoint (`SKIP LOCKED`);
- failed item becomes retryable;
- retry-limit item is not reclaimed;
- two concurrent pods receive disjoint claims;
- an unexpired lease cannot be stolen;
- an expired lease is reclaimable after simulated pod death;
- a stale owner/generation cannot complete a reclaimed item.

### Retention integration

Given a document with projections + identifiers + vector IDs:

```text
expire
  -> claim
  -> cleanup
  -> DELETED
```

Assert:

- canonical projection absent;
- identifier absent;
- vector delete invoked with exact canonical chunk IDs;
- second cleanup is harmless.

### Reingestion race

Prove that reingestion and retention cannot silently delete the newly ingested generation.
The implementation MUST use generation fencing. A stale retention claim from generation N
must be unable to delete retrieval state published by reingestion generation N+1.

This is a mandatory design gate, not an optional future enhancement.

## 16. Explicit non-goals

This feature does not implement:

- archival/cold storage;
- legal hold;
- tenant retention policies;
- embedding model migration;
- generic database vacuuming;
- arbitrary user-driven hard delete;
- full ingestion outbox/state machine.

The schema should not prevent these later features.

## 17. Definition of Done

The feature is complete only when:

1. lifecycle schema is additive and migrated by Liquibase;
2. ingestion establishes lifecycle metadata;
3. bounded nightly scheduler exists;
4. multi-instance claiming is concurrency-safe;
5. cleanup covers vector + identifier + canonical projection;
6. retry/recovery is proven;
7. reingestion-vs-retention race is fenced;
8. Testcontainers integration gates are green;
9. existing retrieval integration gates remain green;
10. exact branch HEAD passes CI.

Do not claim completion based only on unit tests.
