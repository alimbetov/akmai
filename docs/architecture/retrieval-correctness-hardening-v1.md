# Retrieval Correctness Hardening v1

Status: **CURRENT / IMPLEMENTED**  
Merged through PR #95 (`fix: harden retrieval correctness boundaries`).  
Current implementation baseline: included in `main@57c5941166a9bd0bac67b1e273b4f908ee27e167`.

## Purpose

This contract defines the implemented retrieval-correctness boundaries that prevent stale, expired, unpublished or unauthorized candidates from consuming bounded retrieval/context capacity before a later filter can recover them.

The governing sequence is:

```text
Question
  -> deterministic ORIGINAL query chunks
  -> optional generated query variants
  -> retrieval planning
  -> IDENTIFIER / VECTOR / LEXICAL / CONCEPT / HYDE
  -> REFERENCE dependencies
  -> canonical candidate-time lifecycle/ACL eligibility
  -> ParallelRetrievalExecutor
  -> ResultFusion
  -> Reranker
  -> KnowledgeExpansion
  -> Adaptive Graph expansion / competition
  -> ParentContextExpansion
  -> TemporalAuthorityFilter
  -> eligibility-aware context selection / bounded backfill
  -> final published-context TOCTOU revalidation
  -> generation / citations / grounding
```

This is correctness hardening, not a new retrieval feature.

## Canonical candidate eligibility

Every candidate that can consume a bounded retrieval slot must prove current online authority before that slot is consumed:

```text
lifecycle_status = READY
retention_status = ACTIVE
expires_at IS NULL OR expires_at > PostgreSQL clock_timestamp()
published_generation = candidate.generation
generation_status = PUBLISHED
caller ACL contains candidate.access_level
```

The executable lifecycle SQL contract is `PublishedLifecycleSql`. Candidate repositories may use the joined form when generation is already part of the query, but must not implement a weaker local predicate.

Candidate-time filtering does **not** replace final revalidation. The implemented model deliberately keeps two authority fences:

```text
candidate-time canonical eligibility
  -> ranking / fusion / expansion
  -> bounded context selection
  -> final synchronous canonical revalidation
  -> answer generation
```

The late fence closes TOCTOU races. The early fence prevents invalid rows from displacing valid rows before `LIMIT`, `topK` or context budgets.

## VECTOR

`PublishedVectorSearchRepository` preserves bounded HNSW probing while distinguishing raw ANN probes from canonically eligible results.

Implemented behavior:

1. collect bounded raw HNSW candidates;
2. apply canonical lifecycle/ACL/published-generation eligibility;
3. count only eligible candidates toward sufficiency;
4. expand the raw candidate window only under bounded retry rules;
5. use the fenced fallback when the bounded raw window is exhausted;
6. return only canonically eligible rows.

Stale/expired candidates may exist physically and may appear in a raw probe, but they cannot consume final `topK` authority.

The existing HNSW strict-order/index-friendly shape, retry multiplier, maximum candidate window and fallback remain bounded.

## LEXICAL and CONCEPT

`PostgresSearchProjectionRepository` applies canonical lifecycle eligibility inside ranked/limited SQL branches before lifecycle-invalid rows can consume the branch limit.

This applies to FTS, trigram, simple-language, language fallback and semantic-concept overlap retrieval.

Semantic confidence, canonical concept IDs and lexical scoring policy are not changed by this contract.

## IDENTIFIER

Identifier retrieval applies canonical eligibility before identifier ordering/limit.

Required authority property:

```text
newest identifier match = ineligible
older identifier match  = eligible
identifierLimit = 1
=> eligible older match can still be returned
```

An invalid newest match must not consume the only slot and turn the result into a false miss after a late filter.

Exact identifier semantics remain distinct from ordinary semantic similarity.

## REFERENCE

Reference target lookup keeps same-document, exact-generation, ACL and reference-identity constraints and applies canonical eligibility before bounded target selection.

A stale target row may remain stored, but cannot displace a valid current target before `LIMIT`.

## Parent context and temporal authority

The implemented ordering is:

```text
graph competition
  -> ParentContextExpansion
  -> TemporalAuthorityFilter
  -> diversity / context budget
```

This ensures newly introduced parent evidence passes the same document/effective-time authority boundary as child evidence.

Parent expansion remains exact document/generation/chunk based and current lifecycle publication eligibility still applies.

## Context selection and bounded backfill

Context selection uses at most two rounds.

### Round 1

```text
ordered candidate pool
  -> pre-budget canonical eligibility
  -> ContextDiversityFilter
  -> ContextBudget
  -> selected S1
  -> final PublishedContextRevalidator
  -> valid V1
```

If no selected identity is lost, `V1` is final.

### Round 2

If round 1 loses selected entries to the final fence:

```text
POOL2 = original ordered pool - identities rejected from S1
  -> rebuild diversity from scratch
  -> rebuild ContextBudget from scratch
  -> selected S2
  -> revalidate the entire S2
  -> V2
```

There is no third round. A second-round race may reduce the final context but cannot create an unbounded retry loop.

Rebuilding selection from scratch preserves the coupled invariants for context chunk count, serialized token budget, per-document quota, section diversity, graph quota and stable candidate ordering.

Infrastructure failure is not treated as an empty successful context.

## Batched projection materialization

`findPublishedByKeys` uses bounded batched exact-key lookup rather than one SQL `UNION ALL` branch per key.

The request identity is:

```text
(access_level, document_id, generation, chunk_id)
```

Rules:

- null/invalid inputs do not create unsafe lookups;
- keys are de-duplicated in first-occurrence order;
- caller ACL is enforced;
- batches are bounded (`MAX_KEYS_PER_QUERY`);
- SQL joins exact projection identity and canonical published lifecycle authority;
- missing/ineligible keys simply produce no materialized row;
- returned order follows the request ordinals for found eligible rows;
- lookup remains inside retrieval timeout/transaction boundaries.

## ORIGINAL query preservation

Deterministic ORIGINAL query chunks have priority over generated multi-query variants.

Implemented policy:

```text
1. build and ordered-deduplicate all ORIGINAL chunks;
2. effectiveMaxChunks = max(configuredMaxExpandedChunks, originalUniqueCount);
3. append unique generated variants only while capacity remains;
4. a generated duplicate never replaces an ORIGINAL chunk.
```

Therefore probabilistic expansion cannot evict deterministic decomposition of the user's original question.

## Positive cases

1. Valid eligible candidates consume retrieval limits normally.
2. An invalid high-ranked row does not prevent a lower-ranked valid row from being returned when the bounded candidate query can see it.
3. Parent evidence that satisfies temporal/document authority may enter context.
4. A late lifecycle race removes stale selected evidence and one bounded backfill round may fill the freed budget.
5. All ORIGINAL query chunks survive even when generated expansion is heavily populated.

## Negative cases

1. `INGESTING`, expired, inactive, superseded or non-`PUBLISHED` rows cannot consume final candidate authority.
2. ACL mismatch cannot enter a candidate list or final context.
3. Invalid parent temporal state is rejected after parent expansion.
4. A candidate that expires after initial retrieval is rejected by the final context fence.
5. Backfill never retries indefinitely and never bypasses context/document/section/graph quotas.
6. Missing/ineligible exact projection keys are not replaced by a cross-generation or cross-ACL match.
7. Generated query variants cannot evict deterministic ORIGINAL chunks.

## Failure and resource semantics

- Retrieval strategy timeout remains strictly below request timeout.
- Owned retrieval tasks are cancellable; timed-out tasks do not permanently consume executor capacity.
- PostgreSQL retrieval transactions are bounded; `RetrievalTransactionTimeoutIntegrationTest` proves a real `pg_sleep(5)` query is terminated and the connection remains reusable, including repeated timeout cycles.
- Database/infrastructure failure remains typed availability/degradation behavior, not a successful empty result.
- Final lifecycle revalidation remains mandatory even when candidate SQL already filters canonically.

## Executable evidence

The implementation is covered by repository/unit/integration/architecture tests including:

- candidate-time lifecycle/TTL/ACL coverage across vector, lexical, identifier, reference and concept paths;
- `PostgresPublishedLifecycleEligibilityIntegrationTest` for real PostgreSQL TTL/final-context/generation/ACL fencing;
- identifier/reference bounded-slot regression tests;
- context-selection/backfill tests;
- `PublishedContextRevalidator` race tests;
- parent temporal-authority ordering tests;
- `findPublishedByKeys` batching/order/identity tests;
- QueryChunker ORIGINAL-preservation tests;
- `ParallelRetrievalExecutorCancellationTest` and `ParallelRetrievalExecutorRecoveryTest`;
- `RetrievalTransactionTimeoutIntegrationTest`.

Current source/tests and quality workflows remain executable truth when individual test names evolve.

## Non-goals

This contract does not authorize semantic canonical-ID changes, weaker quality thresholds, different RRF/reranker scoring policy, graph-learning policy changes, Redis as lifecycle/cache authority, removal of final lifecycle fences for performance or broad unrelated cleanup.

## Verification status

The runtime behavior in this contract was implemented and merged through PR #95, then inherited by the unified lifecycle merge and current main line.

Status `CURRENT` means the contract reflects implemented behavior. It does not mean AkmAI as a whole is production-release-qualified. Current release blockers and exact-head qualification status are tracked in `docs/audit/production-readiness-hardening-2026-10-12.md`.
