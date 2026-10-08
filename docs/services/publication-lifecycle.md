# Publication / activation lifecycle

Status: **DRAFT**

This contract documents the current publication authority implemented by `GenerationPublicationService` and the ambiguous-outcome resolution implemented by `PublicationOutcomeResolver`.

Publication is the atomic cutover from a staged generation to the generation visible through `knowledge_document_lifecycle.published_generation`. Post-commit semantic linking is explicitly outside the publication transaction and is not publication authority.

## Entry points

Primary runtime entry point:

```java
GenerationPublicationService.publish(...)
```

Atomic transaction implementation:

```java
GenerationPublicationService.publishInTransaction(...)
```

Ambiguous transaction outcome resolution:

```java
PublicationOutcomeResolver.resolve(documentId, generation, idempotency)
```

Post-commit optional quality side effect:

```java
IngestionSemanticLinker.linkPublishedGeneration(...)
```

## Process

Normal publication executes through `publicationTransactionTemplate`:

```text
publish
  -> lock current idempotency claim when present
  -> lock active embedding runtime row
  -> require generation embedding profile == active profile
  -> lock document lifecycle row
  -> read current published_generation
  -> lock candidate generation row
  -> validate candidate status/profile
  -> reject/supersede stale generation
  -> persist canonical search projections
  -> persist identifiers
  -> persist reference graph rows
  -> persist vector generation manifest
  -> persist generation vectors
  -> previous PUBLISHED generation -> RETIRING when replacing
  -> candidate STAGING -> PUBLISHED
  -> lifecycle published_generation / ACL / retention state cutover
  -> complete idempotency record when present
COMMIT
  -> optional semantic association linking
```

The publication transaction is all-or-nothing. Search projections, identifiers, references, vector manifest, vectors, previous-generation retirement, candidate publication, lifecycle cutover and idempotency completion belong to the same database transaction.

## Business rules

### PUB-BR-01 — active embedding profile is locked publication authority

Before staging data is persisted, publication locks `knowledge_embedding_runtime` and verifies that the candidate generation profile still equals `active_profile_id`.

**Positive:** candidate profile remains active -> publication may continue.

**Negative:** active profile changed after generation preparation -> publication fails and transaction rolls back.

### PUB-BR-02 — lifecycle row serializes publication per document

Publication obtains `FOR UPDATE` on `knowledge_document_lifecycle` before locking the candidate generation.

Invariant:

```text
publication lock order = embedding runtime -> lifecycle -> generation
```

Concurrent publication attempts for the same document therefore serialize through the lifecycle row.

### PUB-BR-03 — only STAGING is newly publishable

A candidate generation in `STAGING` may proceed.

A generation already in `PUBLISHED` returns `ALREADY_PUBLISHED`.

Other states are rejected as not publishable.

### PUB-BR-04 — a newer published generation cannot be replaced by an older candidate

When:

```text
published_generation > candidate_generation
```

the candidate becomes:

```text
generation_status = FAILED
failure_code = SUPERSEDED
```

and publication returns `SUPERSEDED`.

This prevents late or concurrent stale work from moving document visibility backward.

### PUB-BR-05 — staged retrieval payload is atomic with cutover

The following writes are inside the publication transaction:

- `knowledge_search_projection`;
- `document_identifier`;
- reference target/edge rows;
- `knowledge_document_vector_generation`;
- physical vector rows.

A later failure must roll these writes back together with lifecycle/generation state.

### PUB-BR-06 — previous visible generation retires in the same transaction

When replacing generation N with N+1, generation N must transition from `PUBLISHED` to `RETIRING` with `cleanup_required=true` before N+1 is made visible.

If that fenced update affects anything other than exactly one expected row, publication fails and the transaction rolls back.

### PUB-BR-07 — candidate publication is fenced

Candidate cutover uses a status predicate requiring `STAGING`.

If the update does not affect exactly one row, publication fails.

### PUB-BR-08 — lifecycle cutover is the visibility authority

The lifecycle update atomically sets at least:

- `published_generation`;
- current `generation`;
- lifecycle status `READY`;
- retention status `ACTIVE`;
- lifecycle policy / expiration;
- published access level;
- clears ingestion claim/lease state;
- increments `row_version`.

A generation row being `PUBLISHED` without a successful lifecycle update is not an acceptable committed publication state; transaction rollback must prevent this split state.

### PUB-BR-09 — ACL changes become visible only at publication

A staged generation may carry a different access level, but `knowledge_document_lifecycle.access_level` changes only in the successful publication transaction.

Failed staging must not change published ACL visibility.

### PUB-BR-10 — idempotency completion is part of publication transaction

When an idempotency context exists:

- the current claim is locked in the publication transaction;
- a response is required;
- `completeInCurrentTransaction` must succeed before commit.

If idempotency completion fails, publication must roll back.

### PUB-BR-11 — ambiguous commit outcome is resolved from durable state

A runtime exception escaping `TransactionTemplate.execute` does not prove rollback: the commit may have succeeded while acknowledgement failed.

`PublicationOutcomeResolver` checks durable truth in this order:

1. matching idempotency claim with `request_status=SUCCEEDED` -> `COMMITTED`;
2. generation with non-null `published_at` and status `PUBLISHED`, `RETIRED` or `CLEANED` -> `COMMITTED`;
3. generation `FAILED` with `failure_code=SUPERSEDED` -> `SUPERSEDED`;
4. otherwise -> `NOT_COMMITTED`.

A `PUBLISHED` status without `published_at` is not sufficient evidence of a committed publication.

### PUB-BR-12 — outcome-resolution failure must not mask the primary publication failure

If the publication transaction throws and `PublicationOutcomeResolver` also throws, the original publication exception remains the primary exception.

The resolver failure is attached as a suppressed exception.

This preserves the actual failure boundary while retaining diagnostic evidence from the recovery probe.

### PUB-BR-13 — committed ambiguous outcome is returned as success

If durable evidence resolves an ambiguous exception as `COMMITTED`, `publish()` returns `PUBLISHED` rather than surfacing a false failure.

If durable evidence resolves it as `SUPERSEDED`, `publish()` returns `SUPERSEDED`.

If it is `NOT_COMMITTED`, the original publication exception is rethrown.

### PUB-BR-14 — semantic linking is post-commit and fail-open

`IngestionSemanticLinker` executes only after publication transaction completion for `PUBLISHED` or `ALREADY_PUBLISHED` results with non-empty projections and vectors.

A semantic-linking exception:

- is logged;
- does not change the publication result;
- does not roll back the already committed generation;
- may leave semantic graph enrichment incomplete for later repair/rebuild.

This layer is a quality enhancer, not publication authority.

### PUB-BR-15 — access-level consistency is rechecked before post-commit linking

All published projections used for semantic linking must have one positive access level. Mixed/invalid projection ACL causes semantic linking to fail closed locally while publication remains committed.

## Concurrent publication semantics

For two staged generations of one document, N and N+1, starting concurrently:

```text
both attempt lifecycle FOR UPDATE
        ↓
PostgreSQL serializes them
```

Valid final state:

```text
published_generation = N+1
N+1 = PUBLISHED
N = RETIRING or FAILED/SUPERSEDED
```

Depending on lock acquisition order, N may publish first and then become `RETIRING`, or N+1 may publish first and force N to `FAILED/SUPERSEDED`. Visibility must never end at N after N+1 has successfully published.

## Rollback semantics

Any failure before transaction commit must leave the database equivalent to the pre-publication authoritative state.

Example late failure:

```text
projection saved
identifier/reference/manifest stage proceeds
vector insertion fails
        ↓
transaction rollback
        ↓
no candidate projection/manifest remains
candidate remains STAGING
previous generation remains PUBLISHED
lifecycle published_generation unchanged
```

The failure must not require compensating cleanup for writes performed inside the same publication transaction.

## Ambiguous commit cases

### Commit succeeded, acknowledgement failed

Durable idempotency/generation evidence resolves `COMMITTED`; caller receives `PUBLISHED`.

### Commit rolled back

Resolver finds neither durable publication evidence nor superseded state; original exception propagates.

### Candidate was superseded

Resolver returns `SUPERSEDED`; caller receives a non-exceptional superseded result.

### Resolver database unavailable

The publication exception stays primary and resolver failure is suppressed.

## Post-commit semantic-link failure

A graph/semantic memory outage after publication is intentionally degraded behavior:

```text
publication COMMITTED
semantic linking FAILED
        ↓
return PUBLISHED
log post_publication_failed
```

Do not attempt to roll back publication because publication has already crossed its transaction boundary.

## Positive cases

- first staged generation publishes successfully;
- replacement generation publishes and previous generation becomes `RETIRING`;
- staged ACL changes only at successful cutover;
- replay of already-published generation returns `ALREADY_PUBLISHED`;
- concurrent N/N+1 publication converges on N+1;
- ambiguous commit with durable success evidence returns `PUBLISHED`;
- retired/cleaned generation with `published_at` remains recognized as previously committed.

## Negative / failure cases

- active embedding profile changed before publication;
- generation missing;
- generation not `STAGING`/`PUBLISHED`;
- stale candidate after newer publication;
- projection/identifier/reference/manifest/vector persistence failure;
- previous-generation retirement fence failure;
- candidate publication fence failure;
- lifecycle cutover failure;
- idempotency completion failure;
- ambiguous commit with no durable success evidence;
- ambiguous resolver itself unavailable;
- post-commit semantic linker unavailable;
- inconsistent projection access levels during post-commit linking.

## Transaction and timeout boundary

Publication uses `publicationTransactionTemplate`, configured from `VectorStorageProperties.dbTransactionTimeout()`.

The DB transaction includes only publication persistence and fenced state transitions. Semantic graph linking is outside the transaction.

The transaction timeout must bound JDBC work at the Spring transaction/JDBC boundary; callers must not interpret a timeout exception alone as proof that commit did not occur, hence the durable outcome resolver.

## Observability

Current explicit post-commit semantic failure log event:

```text
semantic_memory_linking event=post_publication_failed
```

Publication failures also propagate to ingestion orchestration where request/generation failure handling is recorded.

Future metrics may count publication outcomes, but metrics are not publication authority.

## Tests

Current relevant tests include:

- `ConcurrentGenerationPublicationIntegrationTest`
  - newer generation cannot be replaced by older staging work;
  - ACL cutover only on successful publication;
  - failed replacement leaves published ACL unchanged.
- `GenerationPublicationLifecycleIntegrationTest`
  - true concurrent N/N+1 publication converges on N+1;
  - late vector-stage failure rolls back earlier projection/manifest writes and leaves authoritative publication unchanged.
- `GenerationPublicationFailureModelTest`
  - ambiguous `COMMITTED` recovery;
  - ambiguous `SUPERSEDED` recovery;
  - `NOT_COMMITTED` rethrows primary failure;
  - resolver outage preserves primary publication failure and suppresses resolver failure;
  - post-commit semantic linking failure does not change `PUBLISHED` result.
- `PublicationOutcomeResolverTest`
  - baseline durable-state outcome mapping.
- `PublicationOutcomeResolverFailureModelTest`
  - `published_at` is required for committed generation evidence;
  - `CLEANED` with publication timestamp remains committed;
  - ordinary FAILED is not superseded;
  - non-success idempotency falls back to generation truth.
- `PersistenceCoordinatorFailureModelTest`
  - orchestration-level publication failure/ambiguous handling.

## Known gaps / follow-up

- A real network-level PostgreSQL commit acknowledgement-loss test is difficult to make deterministic in normal CI; the resolver contract is therefore proven through durable-state integration/unit boundaries rather than packet-level fault injection.
- Post-commit semantic linking currently logs failure but does not enqueue a dedicated retry record. Existing graph/Dream maintenance can provide eventual graph coverage, but a dedicated semantic-link repair queue is not introduced by this hardening work.
- Service status remains `DRAFT` until the final exact PR head containing this document and its tests passes the required CI/quality/storage/image gates.

## Change rule

Any future publication change that modifies lock order, lifecycle authority, ambiguous-outcome interpretation, ACL cutover, or post-commit side effects must update this contract and its negative/concurrency tests in the same PR.
