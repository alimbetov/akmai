# Retrieval Correctness Hardening v1

**Lifecycle status:** TARGET  
**Branch:** `feature/retrieval-correctness-hardening-v1`  
**Baseline:** `main@0966cc2bb22cfe924cc55a8d7cafd16ccf1c681a`  
**Scope:** retrieval correctness, lifecycle eligibility, context selection and bounded query expansion only.

## 1. Purpose

This specification hardens the current AkmAI retrieval path without redesigning the RAG architecture and without weakening existing quality gates.

The target is to make one canonical published-knowledge eligibility rule authoritative at every retrieval boundary where candidate truncation, ranking, expansion or context budgeting can otherwise lose valid evidence.

The implementation must preserve the current architecture:

```text
Question
  -> QueryChunker / query analysis
  -> RetrievalPlanner
  -> IDENTIFIER / VECTOR / LEXICAL / CONCEPT / HYDE
  -> REFERENCE dependencies
  -> ParallelRetrievalExecutor
  -> ResultFusion
  -> Reranker
  -> KnowledgeExpansion
  -> Adaptive Graph expansion / competition
  -> ParentContextExpansion
  -> TemporalAuthorityFilter
  -> context diversity
  -> eligibility-aware context selection / budget / bounded backfill
  -> final published-context TOCTOU revalidation
  -> generation / citations / grounding
```

This is a correctness hardening change. It is not a feature expansion.

## 2. Non-goals

The branch must not:

- change semantic canonical IDs or multilingual semantic corpus content;
- lower retrieval quality thresholds or release gates;
- change embedding model/profile contracts;
- change RRF weights, reranker scoring policy or graph-learning policy;
- introduce Redis or another queue/cache dependency;
- redesign ingestion/publication semantics;
- turn graph evidence into an authoritative retrieval lane;
- change public API request/response contracts unless a proven defect makes that unavoidable;
- remove existing lifecycle fences merely to reduce DB reads;
- push `ChunkRole.isSearchable(...)` into candidate SQL in this v1 change;
- mix unrelated cleanup or broad refactoring into this branch.

`ChunkRole` searchability remains under the existing oversampling/searchable-filtering semantics. It may be optimized later only as a separate benchmark-backed change.

## 3. Current correctness gaps

The current final `PublishedContextRevalidator` already acts as a late fail-closed fence and `PostgresPublishedLifecycleEligibility` requires:

```sql
l.lifecycle_status = 'READY'
AND l.retention_status = 'ACTIVE'
AND (l.expires_at IS NULL OR l.expires_at > clock_timestamp())
AND l.access_level = requested_access_level
AND l.published_generation = requested_generation
```

However, candidate-producing repositories still contain paths that apply a weaker lifecycle predicate before `LIMIT`, `topK`, ANN candidate truncation or reference/identifier result truncation. A late filter prevents leakage but cannot recover a valid candidate that was displaced before the filter.

The current context flow applies `TemporalAuthorityFilter` before `ParentContextExpansion`, which means newly introduced parent evidence can bypass the same temporal/document-authority fence that applied to the child.

Finally, context budgeting occurs before the final lifecycle revalidation. If selected entries become ineligible late in the request, valid lower-ranked candidates are not promoted into the freed context capacity.

These are retrieval-correctness and recall gaps, not merely performance concerns.

## 4. Canonical retrieval eligibility invariant

### 4.1 Authoritative lifecycle predicate

Every candidate that can consume a bounded retrieval slot must satisfy this lifecycle state:

```sql
l.lifecycle_status = 'READY'
AND l.retention_status = 'ACTIVE'
AND (l.expires_at IS NULL OR l.expires_at > clock_timestamp())
```

The candidate identity join must additionally prove:

```sql
l.access_level = candidate.access_level
AND l.document_id = candidate.document_id
AND l.published_generation = candidate.generation
```

Caller ACL scope remains an additional mandatory predicate.

### 4.2 Mandatory shared SQL contract

The implementation must introduce one narrow reusable lifecycle SQL contract, recommended name:

```text
kz.alimbetov.akmai.knowledge.lifecycle.PublishedLifecycleSql
```

It must expose the canonical `READY + ACTIVE + unexpired` predicate as the single executable fragment used by retrieval repositories. Identity joins remain local to each repository because aliases and candidate relations differ.

At minimum the shared lifecycle contract must be consumed by:

- `PublishedVectorSearchRepository`;
- `PostgresSearchProjectionRepository`;
- `DocumentIdentifierRepository`;
- `ReferenceGraphRepository`;
- `PublishedProjectionLifecycleEligibility`;
- `PostgresPublishedLifecycleEligibility`.

Do not introduce a generic SQL-builder framework. A small constant/helper is sufficient.

### 4.3 Two fences, not one

Candidate-time filtering does not replace final revalidation.

The target model is:

```text
candidate-time canonical eligibility
        -> ranking / fusion / expansion
        -> context selection with bounded backfill
        -> final synchronous canonical eligibility revalidation
        -> answer generation
```

Existing intermediate lifecycle filters remain in place in this v1 patch. They provide race protection between repository candidate production and later pipeline stages. Removing redundant reads is explicitly out of scope until correctness is merged and benchmarked.

### 4.4 Fail-closed behavior

Malformed routing identity, invalid ACL, unknown generation, non-READY lifecycle, non-ACTIVE retention, expired TTL or generation mismatch must never enter final answer context.

Infrastructure failure is not equivalent to an empty result. Existing typed failure/degradation semantics remain authoritative.

## 5. Workstream A — canonical eligibility in bounded candidate production

### 5.1 VECTOR

**Primary code:** `PublishedVectorSearchRepository`.

The vector implementation must preserve the current HNSW-friendly raw probe architecture. The specification distinguishes:

```text
raw ANN probe candidate
```

from:

```text
canonically eligible retrieval candidate
```

A raw probe may be stale. A stale probe must never consume final `topK` authority.

#### 5.1.1 Required global ANN algorithm

Use the existing bounded ANN window/retry model with the following exact semantics:

```text
1. Collect raw HNSW candidates using the current strict-order ANN probe.
2. Apply canonical READY + ACTIVE + TTL + ACL + published-generation eligibility.
3. Compute eligible_count per branch from canonically eligible rows.
4. If enough eligible rows exist, return the global eligible topK.
5. If the raw ANN window is saturated and eligible_count is insufficient,
   increase candidateLimit using the existing bounded retry multiplier.
6. Repeat only up to ANN_MAX_CANDIDATES_PER_BRANCH.
7. When the bounded ANN window is exhausted and eligible results are still
   insufficient, execute the existing fenced fallback with the full canonical
   lifecycle predicate inside the branch query.
8. Return only canonically eligible rows.
```

The raw HNSW probe `LIMIT` may remain before the lifecycle join to preserve index behavior. The requirement is that only canonical eligible rows count toward output `topK` and toward retry sufficiency.

`visible_counts`/equivalent health logic must represent full canonical eligibility after this change; naming should be updated to `eligible`/`eligible_counts` where practical so the code does not imply weaker semantics.

Affected paths include:

- global ANN search;
- ANN retry health;
- fenced global ANN;
- document-scoped exact search;
- language-routed and language-fallback searches;
- HYDE, because it reuses `PublishedVectorSearchRepository`.

Requirements:

1. `INGESTING`, expired, inactive or unpublished rows cannot consume final `topK`.
2. Retry sufficiency uses canonical eligible rows, not only `retention_status='ACTIVE'`.
3. ACL and exact generation identity remain part of every lifecycle join.
4. Current HNSW `strict_order` behavior remains.
5. `ANN_RETRY_MULTIPLIER` and `ANN_MAX_CANDIDATES_PER_BRANCH` remain bounded.
6. Existing fenced fallback remains present.
7. The final context lifecycle fence remains present.
8. Storage benchmark evidence must show no unacceptable latency/saturation regression.

#### 5.1.2 Existing architecture test

`HotRetentionArchitectureTest#globalAnnPreservesHnswThenDetectsStaleCandidateCrowding` must be updated, not removed.

The updated architecture test must prove:

- raw HNSW candidate collection remains index-friendly;
- lifecycle filtering participates in eligible candidate counting;
- canonical `READY + ACTIVE + TTL` semantics are present;
- stale candidate crowding can trigger retry;
- retry remains bounded;
- fenced fallback remains.

### 5.2 LEXICAL

**Primary code:** lexical methods in `PostgresSearchProjectionRepository`.

Canonical eligibility must be inside every ranked/limited SQL branch before `LIMIT` consumes a slot:

- FTS RU/EN;
- trigram paths;
- simple-language paths;
- language fallback paths.

Oversampling in `TimedPublishedSearchProjectionReader` remains unchanged in this v1 patch and must not be relied upon for lifecycle correctness.

### 5.3 CONCEPT

**Primary code:** `PostgresSearchProjectionRepository#searchSemanticConcepts` used by `ConceptRetrievalStrategy`.

Canonical eligibility must be applied before concept overlap ordering and branch/global `LIMIT` can consume a slot.

Semantic confidence, overlap scoring and canonical concept IDs are unchanged.

### 5.4 IDENTIFIER

**Primary code:** `DocumentIdentifierRepository#queryPublishedScoped` and callers.

Canonical eligibility must be applied before identifier ordering and `LIMIT`.

Required regression:

```text
newest matching identifier -> ineligible
older matching identifier  -> eligible
identifierLimit = 1

EXPECTED: older eligible identifier is returned
NOT: EMPTY after late filtering
```

Identifier-scoped semantic lanes remain fail-closed. Do not introduce global semantic fallback for an identifier miss.

### 5.5 REFERENCE

**Primary code:** `ReferenceGraphRepository#resolveSameDocumentTargets` and projection materialization used by `ReferenceRetrievalStrategy`.

Canonical eligibility must be applied before reference target `LIMIT`.

Preserve:

- same-document scope;
- exact generation;
- ACL;
- reference type/value identity;
- seed exclusion.

## 6. Workstream B — ParentContextExpansion before TemporalAuthorityFilter

Change orchestration from:

```text
graph competition
  -> TemporalAuthorityFilter
  -> ParentContextExpansion
  -> diversity / budget
```

to:

```text
graph competition
  -> ParentContextExpansion
  -> TemporalAuthorityFilter
  -> diversity / budget
```

No new authority filter is introduced. The existing `TemporalAuthorityFilter` remains authoritative.

Required cases:

- ACTIVE child -> ACTIVE parent: parent remains eligible;
- ACTIVE child -> DRAFT parent: parent rejected;
- ACTIVE child -> SUPERSEDED parent: parent rejected;
- ACTIVE child -> future `effectiveFrom` parent: parent rejected;
- ACTIVE child -> expired `effectiveTo` parent: parent rejected;
- malformed parent temporal metadata: fail closed;
- no temporal metadata: preserve current backward-compatible behavior.

The final lifecycle/TTL revalidation still runs after context selection.

## 7. Workstream C — eligibility-before-budget with exactly one backfill round

### 7.1 Component boundary

Introduce one narrow orchestration component for context selection, recommended name:

```text
ContextSelectionCoordinator
```

Recommended public contract:

```java
List<RetrievalHit> select(
    List<RetrievalHit> candidates,
    String question,
    Set<Long> accessLevels
)
```

It owns context-selection sequencing only. It must reuse existing:

- canonical lifecycle eligibility/revalidator primitives;
- `ContextDiversityFilter`;
- `ContextBudget`;
- `PublishedContextRevalidator`.

Do not duplicate token, document, section or graph quota logic in a second implementation.

### 7.2 Exact selection algorithm

Use at most two selection rounds:

```text
MAX_CONTEXT_SELECTION_ROUNDS = 2
```

#### Round 1

```text
POOL = temporal-authority-filtered candidates
  -> pre-budget canonical eligibility
  -> ContextDiversityFilter.apply(POOL)
  -> ContextBudget.apply(...)
  -> S1
  -> PublishedContextRevalidator.revalidate(S1)
  -> V1
```

If `S1` and `V1` have the same routing identities, return `V1`.

#### Round 2 — one bounded backfill round

If round 1 lost entries:

```text
REJECTED = identities(S1) - identities(V1)
POOL2 = POOL - REJECTED

POOL2
  -> ContextDiversityFilter.apply(...) from scratch
  -> ContextBudget.apply(...) from scratch
  -> S2
  -> PublishedContextRevalidator.revalidate(S2) for the ENTIRE S2
  -> V2

return V2
```

There is no third round. If the second final fence rejects another candidate, return the smaller `V2` context.

The second final fence must revalidate all of `S2`, not only newly backfilled entries, because previously valid evidence can become stale between rounds.

### 7.3 Backfill invariants

1. Final context never exceeds `contextMaxChunks`.
2. Final serialized context never exceeds `contextMaxTokens`.
3. `contextMaxChunksPerDocument` remains enforced.
4. `ContextDiversityFilter` section quota remains enforced.
5. Graph context quota remains enforced by `ContextBudget`.
6. Ranking order among surviving candidates remains stable.
7. Exact-authority evidence keeps existing authority semantics.
8. No open-ended retry loop exists.
9. Every round uses batched eligibility/revalidation, not one DB query per candidate.
10. If all candidates become ineligible, return insufficient information rather than stale evidence.
11. `DataAccessException`/transaction failure remains availability failure, not an empty successful context.

### 7.4 Why selection is rebuilt from scratch

Round 2 must rerun diversity and `ContextBudget` over `POOL2` rather than incrementally appending chunks to `V1`.

This is mandatory because the existing budget owns coupled state for:

- chunk count;
- serialized token count;
- per-document count;
- graph quota;
- chat-model budget;

and `ContextDiversityFilter` owns per-section state. Rebuilding selection from the reduced ordered pool re-proves all invariants without introducing a second quota implementation.

## 8. Workstream D — exact batched `VALUES` implementation of `findPublishedByKeys`

**Primary code:** `PostgresSearchProjectionRepository#findPublishedByKeys`.

### 8.1 Fixed batch size

Use:

```java
private static final int MAX_KEYS_PER_QUERY = 256;
```

This intentionally aligns with the existing lifecycle identity batching scale.

### 8.2 Ordered de-duplication

Before SQL batching:

- remove null keys;
- reject/filter keys outside caller ACL using existing behavior;
- de-duplicate by `(accessLevel, documentId, generation, chunkId)`;
- **first occurrence wins**;
- preserve first-occurrence input order across batch boundaries.

### 8.3 SQL shape

Replace per-key `UNION ALL` with bounded `VALUES` batches containing an ordinal:

```sql
WITH requested(
    ordinal,
    access_level,
    document_id,
    generation,
    chunk_id
) AS (
    VALUES
      (?, ?, ?, ?, ?),
      (?, ?, ?, ?, ?)
)
SELECT p.*
FROM requested r
JOIN knowledge_search_projection p
  ON p.access_level = r.access_level
 AND p.document_id = r.document_id
 AND p.generation = r.generation
 AND p.chunk_id = r.chunk_id
JOIN knowledge_document_lifecycle l
  ON l.access_level = p.access_level
 AND l.document_id = p.document_id
 AND l.published_generation = p.generation
WHERE <canonical PublishedLifecycleSql predicate>
ORDER BY r.ordinal
```

Requirements:

- empty input returns empty without DB access;
- no cross-ACL/cross-generation/chunk match;
- no per-key `UNION ALL` SQL;
- batch boundaries are invisible to callers;
- final returned order follows first-occurrence request order for found eligible keys;
- missing/ineligible keys simply do not produce rows;
- all reads remain inside the existing retrieval transaction timeout.

## 9. Workstream E — preserve all ORIGINAL query chunks

**Primary code:** `QueryChunker#chunk(String)` multi-query merge logic.

Original deterministic decomposition is mandatory. Probabilistic multi-query variants are supplemental.

Implement the following exact policy:

```text
1. Build all ORIGINAL chunks first without applying maxExpandedChunks.
2. Insert ORIGINAL chunks into ordered de-duplication map.
3. effectiveMaxChunks = max(maxExpandedChunks, originalUniqueCount).
4. Generate/add MULTI_QUERY chunks only while unique.size < effectiveMaxChunks.
5. A generated duplicate never replaces an ORIGINAL chunk.
6. Return ordered unique values bounded by effectiveMaxChunks.
```

Equivalent code structure:

```java
Map<String, QueryChunk> unique = new LinkedHashMap<>();
addOriginalsWithoutExpansionCap(unique, original);

int effectiveMaxChunks = Math.max(
        advancedProperties.maxExpandedChunks(),
        unique.size()
);

for (String variant : variants) {
    addGeneratedUntil(unique, variant, effectiveMaxChunks);
    if (unique.size() >= effectiveMaxChunks) {
        break;
    }
}
```

Do not use the current expansion cap inside original insertion.

Additional invariants:

- ORIGINAL and MULTI_QUERY origin remain distinguishable;
- timeout/rejection/model failure returns original chunks exactly;
- no extra model call when multi-query is disabled;
- deterministic de-duplication key semantics remain unchanged unless a failing test proves a defect.

## 10. Transaction, timeout and cancellation requirements

All retrieval DB reads changed by this branch must stay under existing bounded retrieval transactions.

Requirements:

- candidate SQL runs under retrieval transaction timeout;
- `findPublishedByKeys` batches run under retrieval transaction timeout;
- pre-budget eligibility is bounded;
- final TOCTOU revalidation remains bounded;
- executor timeout/cancellation semantics remain typed;
- no swallowed `DataAccessException` may become a normal `EMPTY` authority result;
- no new raw unbounded JDBC read may be introduced on the request path.

## 11. Existing fences and classes — preservation rules

This v1 patch is correctness-first. Therefore:

- `TimedPublishedSearchProjectionReader` keeps its existing post-query eligibility filtering;
- `PublishedProjectionLifecycleEligibility` remains and switches to the shared lifecycle SQL contract;
- `ParallelRetrievalExecutor` lifecycle filtering remains;
- `PublishedContextRevalidator` remains the final synchronous context fence;
- `PostgresPublishedLifecycleEligibility` remains authoritative for final lifecycle/ACL/TTL filtering;
- `ContextDiversityFilter` semantics remain unchanged;
- `ContextBudget` semantics remain unchanged;
- redundant-read optimization is deferred to a separate benchmark-backed patch.

## 12. Test specification

Tests are implementation requirements, not optional examples.

### 12.1 Candidate eligibility integration matrix

For VECTOR, LEXICAL, CONCEPT, IDENTIFIER and REFERENCE prove:

- `READY + ACTIVE + unexpired` -> eligible;
- `INGESTING + ACTIVE` -> rejected;
- `FAILED + ACTIVE` -> rejected;
- `READY + inactive retention` -> rejected;
- `READY + ACTIVE + expired TTL` -> rejected;
- previous/unpublished generation -> rejected;
- wrong ACL -> rejected.

At least one test per bounded lane must place an ineligible candidate ahead of an eligible candidate and prove the valid candidate still appears inside requested `limit/topK`.

### 12.2 VECTOR-specific tests

Mandatory:

1. raw ANN window contains enough canonical eligible rows -> no retry;
2. raw ANN window is saturated by stale/ineligible rows and eligible `< topK` -> retry occurs;
3. deeper eligible rows are recovered after retry;
4. bounded retry reaches `ANN_MAX_CANDIDATES_PER_BRANCH` with insufficient eligible rows -> fenced fallback is used;
5. fenced fallback returns only canonical eligible rows;
6. document-scoped exact search excludes `INGESTING` and expired lifecycle rows before `topK`;
7. updated `HotRetentionArchitectureTest` proves retry/fallback/canonical predicate structure and is not deleted.

### 12.3 Identifier authority tests

- exact identifier finds eligible canonical evidence;
- multiple ACLs retain only caller-authorized results;
- newest ineligible + older eligible + `limit=1` returns older eligible;
- all matching identifiers ineligible -> `EMPTY`;
- identifier infrastructure failure remains critical and never triggers global semantic fallback.

### 12.4 Reference tests

- an ineligible target cannot consume the bounded reference slot ahead of an eligible target;
- same-document, exact generation and ACL invariants remain;
- expired/INGESTING lifecycle rows are excluded before `LIMIT`.

### 12.5 Parent temporal-authority tests

- valid parent survives;
- DRAFT/SUPERSEDED/future/expired/malformed parent is rejected;
- parent retains attribution metadata required by citation/authority behavior.

### 12.6 Context selection / backfill tests

Positive:

- final revalidation removes an early selected candidate and round 2 fills the freed capacity from a lower-ranked valid candidate;
- round 2 preserves ranking order;
- token/chunk/document/section/graph quotas remain satisfied;
- exact-authority semantics remain unchanged.

Negative/failure:

- all backup candidates ineligible -> smaller/empty context safely returned;
- lifecycle changes after pre-budget eligibility -> stale selection removed;
- a candidate becomes stale between round 1 and round 2 -> second full `S2` revalidation removes it;
- second revalidation removes another candidate -> **no third selection round**;
- DB timeout during authoritative revalidation -> retrieval unavailable, not normal insufficient-information success.

### 12.7 `findPublishedByKeys` tests

- empty input -> no DB query / empty result;
- duplicate keys -> one result; first occurrence defines order;
- 256 keys -> one batch;
- 257+ keys -> multiple bounded batches with complete result set;
- batch boundaries preserve request order;
- exact ACL/generation/chunk identity;
- expired/INGESTING rows excluded;
- mixed found/missing/ineligible keys deterministic;
- SQL does not contain one `UNION ALL` branch per key.

### 12.8 Multi-query tests

- 8 ORIGINAL chunks + `maxExpandedChunks=3` -> all 8 ORIGINAL chunks retained;
- generated variants start only after originals are inserted;
- only remaining effective capacity is available to MULTI_QUERY;
- generated duplicate cannot replace original;
- timeout/rejection/model failure preserves originals exactly;
- multi-query disabled -> no additional model work.

### 12.9 Deterministic concurrency / TOCTOU tests

Use latches/barriers/test hooks where possible; do not rely on sleep-only races.

Required:

1. READY candidate selected, lifecycle becomes INGESTING before final fence -> rejected;
2. TTL crosses expiry between candidate search and final fence -> rejected;
3. generation N ranked, N+1 becomes published before final fence -> N rejected;
4. candidate invalidated after pre-budget eligibility -> eligible backup may fill in round 2;
5. simultaneous callers with different ACL scopes do not contaminate batched materialization.

### 12.10 Performance/regression evidence

- storage benchmark covers changed candidate SQL and batched keyed materialization;
- use existing p50/p95/p99 or benchmark-equivalent measures where supported;
- no unbounded SQL text growth with increasing keyed candidate count;
- no one-query-per-backfill-candidate pattern;
- ANN correctness must not be traded for a silent recall regression;
- benchmark evidence must be compared on the same relevant environment/profile when asserting performance improvement/regression.

## 13. Observability

Reuse existing metrics. Add only bounded, low-cardinality telemetry when necessary.

Useful candidates:

- candidate lifecycle rejections by lane/reason class;
- pre-budget lifecycle rejection count;
- final TOCTOU rejection count;
- context backfill triggered count;
- backfill exhausted count;
- `findPublishedByKeys` batch count/requested-key count.

Never use document IDs, chunk IDs/text, identifier values or other high-cardinality/sensitive values as metric labels.

## 14. Documentation updates required with implementation

When implementation lands, update in the same branch/PR:

- `docs/services/retrieval-flow.md`;
- `docs/services/retrieval-execution.md` only if execution/degradation semantics change;
- `docs/architecture/current-runtime-architecture.md`;
- relevant storage/indexing documentation if SQL topology changes materially;
- this document from `TARGET` to `CURRENT / IMPLEMENTED`, or fold durable rules into current-state docs and remove this TARGET document;
- `docs/README.md` synchronization baseline.

No stale TARGET specification may remain after implementation completion.

## 15. Implementation order

Implement in this order so failures remain attributable:

1. add mandatory shared `PublishedLifecycleSql` contract;
2. update `PublishedProjectionLifecycleEligibility` and `PostgresPublishedLifecycleEligibility` to use it without changing semantics;
3. fix IDENTIFIER and REFERENCE candidate predicates;
4. fix LEXICAL and CONCEPT candidate predicates;
5. fix VECTOR canonical eligible counting/retry/fallback while preserving HNSW probe shape;
6. update `HotRetentionArchitectureTest` for the new ANN contract;
7. replace `findPublishedByKeys` with ordered `VALUES` batches of max 256 keys;
8. move `ParentContextExpansion` before `TemporalAuthorityFilter`;
9. add `ContextSelectionCoordinator` with at most two rounds and full second-round revalidation;
10. preserve all ORIGINAL query chunks before MULTI_QUERY expansion;
11. complete positive/negative/concurrency/failure tests;
12. update current-state documentation;
13. run exact-head validation.

Commits may be split by coherent workstream, but branch/PR scope remains one retrieval-correctness change set.

## 16. Definition of Done

The branch is merge-ready only when all are true:

- one shared executable lifecycle SQL contract defines `READY + ACTIVE + unexpired`;
- VECTOR output/retry/fallback uses canonical eligible counts while preserving raw HNSW probe behavior;
- LEXICAL, CONCEPT, IDENTIFIER and REFERENCE enforce canonical eligibility before bounded truncation;
- existing intermediate and final lifecycle fences remain;
- parent-expanded evidence passes temporal authority;
- context selection performs at most one backfill round and revalidates the complete second selection;
- all context quotas/budgets remain enforced through existing components;
- `findPublishedByKeys` uses ordered bounded `VALUES` batches with `MAX_KEYS_PER_QUERY=256`;
- ORIGINAL query chunks cannot be evicted by multi-query expansion capacity;
- `HotRetentionArchitectureTest` is updated rather than deleted;
- positive, negative, failure-injection and deterministic concurrency tests pass;
- no canonical semantic IDs, quality thresholds or public API contracts are weakened;
- current-state documentation matches executable behavior;
- the same immutable head SHA passes all required gates.

## 17. Exact-head acceptance gates

The exact same immutable branch head SHA must report `success` for:

1. `CI`;
2. `Retrieval Quality Gate`;
3. `Retrieval Storage Final Benchmark`;
4. `Production Image Build`.

A green workflow on an earlier SHA is not acceptance evidence.

If a gate fails, correct the implementation/test/documentation defect. Do not lower thresholds, remove assertions, disable tests, delete architecture guards or weaken eligibility predicates to obtain green status.

## 18. System architect conclusion

**Assessment:** APPROVED AS IMPLEMENTATION-GRADE TARGET after the precision amendments in this document.

The current AkmAI retrieval architecture is structurally sound: generation identity, ACL-scoped routing, typed degradation, bounded executors, canonical fusion, final lifecycle revalidation and grounded answer verification are already present. This branch should therefore remain a narrow correctness hardening rather than a retrieval redesign.

The central architectural rule is now explicit: raw ANN probing and canonical retrieval eligibility are different concepts. HNSW may first produce a bounded raw window for planner/index efficiency, but only `READY + ACTIVE + unexpired + exact published generation + ACL` evidence may satisfy retrieval `topK`, identifier/reference limits or context capacity.

The second rule is that late TOCTOU safety must not create unbounded compensating work. One backfill round is sufficient: rebuild selection from the remaining ordered pool using the existing diversity/budget components, revalidate the entire second selection, and stop. This preserves correctness while keeping latency and database work bounded.

The ordered `VALUES` materialization with a fixed 256-key batch removes per-key SQL-plan growth without changing composite identity. The shared lifecycle SQL fragment prevents future predicate drift without introducing a new framework.

No new infrastructure component is required. Redis, distributed cache or a separate retrieval service would add complexity without addressing these defects.

**Architectural merge recommendation:** merge only after one exact head proves all four workflows green and deterministic concurrency tests prove lifecycle/TTL/generation races fail closed.

## 19. RAG expert conclusion

**Assessment:** STRONGLY RECOMMENDED, HIGH-VALUE CORRECTNESS HARDENING.

The expected benefit is higher recall stability and fewer avoidable false negatives under ingestion/lifecycle churn without weakening precision, ACL, generation, temporal-authority, citation or grounding safeguards.

The most important quality defect is bounded-slot pollution: stale or non-authoritative evidence can rank highly enough to consume ANN, lexical, concept, identifier or reference capacity, then disappear in a later fence. That behavior makes a healthy corpus appear incomplete. Moving canonical eligibility into bounded candidate admission fixes the recall defect while retaining the late TOCTOU fence for safety.

Identifier retrieval deserves special emphasis because an incorrect identifier `EMPTY` intentionally prevents broad semantic fallback. Recovering an eligible older exact match behind an ineligible newer row is therefore a correctness requirement, not a recall tuning preference.

The bounded context backfill also improves answer quality. Final lifecycle revalidation is unavoidable in a concurrent system, but it should not unnecessarily leave the LLM with a sparse context when valid lower-ranked evidence already exists. Rebuilding the selection once from the reduced candidate pool preserves ranking, token limits, diversity and graph quotas without inventing a parallel budgeting algorithm.

Preserving all ORIGINAL query chunks follows the same principle: deterministic intent extraction is authoritative input to retrieval; probabilistic multi-query generation is only supplemental recall augmentation and may consume spare expansion capacity, never replace original intent.

Expected quality direction:

```text
recall stability           up
false-negative retrieval   down
avoidable abstention       down
context completeness       up
precision safeguards       unchanged
ACL/generation safety      unchanged
grounding strictness       unchanged
```

**RAG merge recommendation:** accept only when every bounded lane recovers valid evidence behind ineligible higher-ranked candidates, the identifier regression is proven, context backfill remains exactly bounded, retrieval quality gates stay green, and storage benchmark evidence shows no unacceptable latency/saturation regression.