# Knowledge ingestion service contract

**Implementation:** `KnowledgeIngestionService`  
**Status:** CURRENT

## Process

AkmAI accepts either plain text (`addText`) or a parsed canonical document (`addCanonical`), normalizes it into a `KnowledgeDocument`, produces semantic chunks, enriches searchable chunks, and delegates generation persistence/publication to `PersistenceCoordinator`.

With an `Idempotency-Key`, ingestion first claims a lease-backed idempotency record. A successful replay returns the stored response without repeating chunking, enrichment, or persistence.

## Business Rules

1. The request fingerprint is bound to the idempotency key before ingestion work starts.
2. `REPLAY` returns the previous `KnowledgeIngestionResponse` without repeating ingestion work.
3. `IN_PROGRESS` fails with `INGESTION_IN_PROGRESS` and exposes `retryAfterSeconds`.
4. A claimed ingestion renews its idempotency lease before expensive stages.
5. Text ingestion canonicalizes supported language aliases before chunking.
6. Request `source` and `accessLevel` are authoritative and override conflicting metadata values.
7. A document that produces zero searchable chunks is rejected and is never enriched or persisted.
8. Canonical ingestion must prepare the canonical structure before semantic chunking.
9. Runtime failures after a claim mark the idempotency request failed and rethrow the original exception.
10. Successful persistence receives the same idempotency context and access level as the ingestion request.

## Positive Cases

- Plain-text ingestion: normalize language -> chunk -> count searchable chunks -> enrich -> persist -> return document/chunk count.
- Canonical ingestion: prepare canonical document -> chunk semantic units -> enrich -> persist.
- Existing successful idempotency request: return replay immediately.
- Active claim: renew lease across ingestion stages and persist using the claim context.
- Caller metadata without protected-key conflicts is preserved; `source` and `access_level` remain authoritative.

## Negative Cases

- Unsupported language -> reject before chunking.
- Zero searchable chunks -> reject before enrichment/persistence.
- Concurrent request with the same active idempotency key -> `INGESTION_IN_PROGRESS`; no ingestion work starts.
- Enrichment failure -> mark claimed request failed; do not persist.
- Canonical preparation failure -> mark claimed request failed; do not chunk/enrich/persist.
- Null canonical document -> reject before fingerprinting or idempotency work.

## Invariants

- Idempotency replay must never duplicate side effects.
- No persistence is allowed for a zero-searchable-chunk document.
- Protected request metadata (`source`, `access_level`) cannot be spoofed by arbitrary metadata.
- Once a claim is acquired, failures observed by `KnowledgeIngestionService` must be recorded through `IngestionIdempotencyRepository.fail(...)`.
- Canonical and text paths converge before enrichment/persistence and share the same searchable-chunk admission rule.

## Tests

`src/test/java/kz/alimbetov/akmai/knowledge/service/KnowledgeIngestionServiceTest.java`

- `canonicalizesLanguageAliasesBeforeChunkingAndPersistence`
- `requestSourceAndAccessLevelOverrideConflictingMetadata`
- `acceptsExpandedLanguageAliasBeforeChunking`
- `persistsClaimedIngestionAndRenewsLeaseAcrossStages`
- `returnsReplayWithoutRepeatingIngestionWork`
- `rejectsConcurrentInProgressRequestBeforeChunking`
- `marksClaimFailedWhenEnrichmentFails`
- `rejectsDocumentThatNormalizesToNoIndexableChunks`
- `rejectsUnsupportedLanguageBeforeAnyChunkingWork`

`src/test/java/kz/alimbetov/akmai/knowledge/service/KnowledgeCanonicalIngestionServiceTest.java`

- `rejectsNullCanonicalDocumentBeforeAnyIngestionWork`
- `preparesChunksEnrichesAndPersistsCanonicalDocument`
- `returnsCanonicalReplayWithoutRepeatingProcessing`
- `marksCanonicalClaimFailedWhenPreparationFails`

## Transaction / Failure Boundary

`KnowledgeIngestionService` is an orchestration boundary, not the publication transaction itself. Persistence/publication transaction semantics remain owned by `PersistenceCoordinator` and downstream generation/publication components. Idempotency lease renewal is deliberately performed around expensive orchestration stages so a stale claimant cannot silently continue as the current owner.
