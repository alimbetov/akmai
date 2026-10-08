# Knowledge ingestion service contract

**Implementation:** `KnowledgeIngestionService`, `PersistenceCoordinator`, `PublicationOutcomeResolver`  
**Status:** CURRENT

## Process

AkmAI accepts either plain text (`addText`) or a parsed canonical document (`addCanonical`), normalizes it into a `KnowledgeDocument`, produces semantic chunks, enriches searchable chunks, allocates a new generation, builds generation-scoped vectors/manifests, and atomically publishes the generation.

With an `Idempotency-Key`, ingestion first claims a lease-backed idempotency record. A successful replay returns the stored response without repeating chunking, enrichment, generation allocation, embedding, or publication.

## Business Rules

1. A non-blank idempotency key is bound to a canonical request fingerprint before ingestion work starts.
2. Blank or absent idempotency keys bypass fingerprinting and the idempotency repository entirely.
3. `REPLAY` returns the previous `KnowledgeIngestionResponse` without repeating ingestion work.
4. `IN_PROGRESS` fails with `INGESTION_IN_PROGRESS` and exposes `retryAfterSeconds`.
5. A claimed ingestion renews its lease before expensive stages; a lost claim stops further work.
6. Text ingestion canonicalizes supported language aliases before chunking.
7. Request `source` and `accessLevel` are authoritative and override conflicting metadata values.
8. A document that produces zero searchable chunks is rejected and is never enriched or persisted.
9. A persistence batch must contain exactly one document and a positive access level.
10. The configured embedding profile must be active before generation allocation.
11. An allocated generation is attached to the current idempotency claim before embedding/publication continues.
12. Embedding count must exactly match the searchable projection count.
13. Publication ambiguity is resolved from durable state before deciding whether the generation committed, was superseded, or definitely did not commit.
14. An unknown publication outcome is not force-failed; it is left for recovery/reconciliation.
15. A definitely failed ingestion marks the new generation and current idempotency request failed.
16. Failure-recording/cleanup errors must never replace the primary ingestion failure; cleanup errors are attached as suppressed exceptions.
17. Canonical ingestion must prepare the canonical structure before semantic chunking.

## Positive Cases

- Plain-text ingestion: normalize language -> chunk -> count searchable chunks -> enrich -> allocate generation -> embed -> publish -> return document/chunk count.
- Canonical ingestion: prepare canonical document -> chunk semantic units -> enrich -> persist/publish.
- Existing successful idempotency request: return replay immediately with no repeated side effects.
- Active claim: renew lease across stages and use the same claim context through persistence/publication.
- Publication call throws after commit but durable state says `COMMITTED`: treat ingestion as successful.
- A published, retired, or cleaned generation with a non-null `published_at` remains a committed publication for ambiguity recovery.

## Negative Cases

### Request / orchestration

- Unsupported language -> reject before chunking.
- Null canonical document -> reject before fingerprint/idempotency work.
- Zero searchable chunks -> reject before enrichment/persistence.
- Reused idempotency key with another fingerprint -> propagate `IDEMPOTENCY_KEY_REUSE`; no heartbeat or ingestion work.
- Concurrent request with the same active key -> `INGESTION_IN_PROGRESS`; no mapping/chunking/enrichment/persistence.
- Claim lost on the first heartbeat -> stop before chunking and attempt to record the claim failure.
- Claim lost after chunking -> stop before enrichment/persistence.
- Enrichment failure -> mark claimed request failed; do not persist.
- Canonical preparation or canonical chunking failure -> mark claimed request failed; no downstream work.
- Persistence/publication exception -> propagate the primary error and mark the claim failed.
- Failure-recording repository unavailable -> keep the primary exception and attach cleanup error as suppressed.

### Persistence / generation

- `accessLevel <= 0` -> reject before profile/generation work.
- Batch containing chunks from multiple documents -> reject before profile/generation work.
- Inactive configured embedding profile -> stop before generation allocation.
- Lost idempotency ownership while attaching the allocated generation -> fail that new generation and stop before publication.
- Embedding service failure -> fail only the newly allocated generation; never publish it.
- Embedding cardinality mismatch -> fail the new generation and idempotency request; never publish.
- Publication exception + resolver says `NOT_COMMITTED` -> fail generation/idempotency and rethrow the publication error.
- Publication result/resolver says `SUPERSEDED` -> do not expose the generation as a successful ingestion.
- Publication exception + resolver itself unavailable -> raise `PublicationOutcomeUnknownException`; do not mark the generation failed because commit state is unknown.
- Generation-failure or idempotency-failure cleanup also throws -> preserve the original ingestion exception; cleanup failures are suppressed.

### Publication recovery

- Idempotency row is `SUCCEEDED` -> outcome is `COMMITTED`.
- Generation is `PUBLISHED`, `RETIRED`, or `CLEANED` and has `published_at` -> outcome is `COMMITTED`.
- Generation is `FAILED` with `SUPERSEDED` -> outcome is `SUPERSEDED`.
- Generation is `STAGING`, absent, or otherwise not durably published -> outcome is `NOT_COMMITTED`.

## Invariants

- Idempotency replay must never duplicate side effects.
- A claimant that loses its lease must not continue expensive downstream work.
- No persistence is allowed for a zero-searchable-chunk document.
- Protected request metadata (`source`, `access_level`) cannot be spoofed by arbitrary metadata.
- One persistence invocation may contain chunks for one document only.
- An embedding vector list must be cardinality-aligned with searchable projections.
- A known failed attempt must not leave its new generation eligible for publication.
- An unknown publication outcome must not be destructively guessed as failed.
- Primary failure causality is preserved even when failure bookkeeping itself is unavailable.
- Canonical and text paths converge before enrichment/persistence and share the same searchable-chunk admission rule.

## Tests

### Service happy path and baseline validation

`src/test/java/kz/alimbetov/akmai/knowledge/service/KnowledgeIngestionServiceTest.java`

- language canonicalization;
- authoritative source/access-level metadata;
- claimed ingestion + lease renewal;
- replay;
- in-progress conflict;
- enrichment failure;
- zero searchable chunks;
- unsupported language.

`src/test/java/kz/alimbetov/akmai/knowledge/service/KnowledgeCanonicalIngestionServiceTest.java`

- null canonical input;
- canonical happy path;
- canonical replay;
- canonical preparation failure.

### Service failure model

`src/test/java/kz/alimbetov/akmai/knowledge/service/KnowledgeIngestionFailureModelTest.java`

- claim conflict before work;
- lost claim on first heartbeat;
- lost claim after chunking;
- persistence failure propagation;
- cleanup failure suppression;
- blank idempotency-key bypass.

`src/test/java/kz/alimbetov/akmai/knowledge/service/KnowledgeCanonicalIngestionFailureModelTest.java`

- canonical in-progress conflict;
- canonical zero-searchable-chunk rejection;
- canonical chunking failure;
- blank canonical idempotency-key bypass.

### Persistence/publication failure model

`src/test/java/kz/alimbetov/akmai/knowledge/ingestion/PersistenceCoordinatorTest.java`

- generation-scoped manifest/vector metadata;
- lost claim after generation allocation;
- embedding failure;
- committed publication recovery after ambiguous exception;
- unknown publication outcome left for recovery.

`src/test/java/kz/alimbetov/akmai/knowledge/ingestion/PersistenceCoordinatorFailureModelTest.java`

- invalid access level;
- mixed-document batch;
- inactive embedding profile;
- embedding cardinality mismatch;
- definitely-not-committed publication;
- cleanup failure suppression.

`src/test/java/kz/alimbetov/akmai/knowledge/ingestion/PublicationOutcomeResolverTest.java`

- idempotency success -> committed;
- published/retired durable generation -> committed;
- superseded failure -> superseded;
- staging/missing generation -> not committed.

## Transaction / Failure Boundary

`KnowledgeIngestionService` is the orchestration/idempotency boundary, not the publication transaction. `PersistenceCoordinator` owns generation allocation, embedding assembly, publication decision handling, and failure compensation. `GenerationPublicationService` owns the atomic publication transaction. `PublicationOutcomeResolver` is used only after an exception makes the publication result ambiguous.

Lease heartbeats are deliberately placed around expensive stages so stale claimants are fenced before consuming more work. Failure bookkeeping is best-effort with respect to causality: if bookkeeping itself fails, its exception is suppressed onto the primary failure rather than replacing the original cause.
