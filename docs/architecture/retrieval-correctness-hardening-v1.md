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
  -> eligibility-aware context budgeting / backfill
  -> final published-context TOCTOU revalidation
  -> generation / citations / grounding
```

This is a correctness hardening change. It is not a feature expansion.

## 2. Non-goals

The branch must not:

- change semantic canonical IDs or multilingual semantic corpus content;
- lower retrieval quality thresholds or release gates;
- change embedding model/profile contracts;
- change RRF weights, reranker scoring policy or graph-learning policy unless required only to preserve an existing invariant;
- introduce Redis or another queue/cache dependency;
- redesign ingestion/publication semantics;
- turn graph evidence into an authoritative retrieval lane;
- change public API request/response contracts unless a proven defect makes that unavoidable;
- mix unrelated cleanup or broad refactoring into this branch.

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

The current context flow also applies `TemporalAuthorityFilter` before `ParentContextExpansion`, which means a newly introduced parent chunk may not pass the same document-status/effective-date fence as the child that caused the expansion.

Finally, context budgeting happens before the final lifecycle revalidation. If selected entries become ineligible late in the request, valid lower-ranked candidates are not backfilled into the freed context capacity.

These are retrieval-correctness and recall gaps, not merely performance concerns.

## 4. Canonical retrieval eligibility invariant

### 4.1 Authoritative lifecycle predicate

Every candidate that can consume a bounded candidate slot, ranking slot, exact-identifier slot, reference slot or context slot must satisfy the same lifecycle predicate at the point where that slot is allocated:

```sql
l.lifecycle_status = 'READY'
AND l.retention_status = 'ACTIVE'
AND (l.expires_at IS NULL OR l.expires_at > clock_timestamp())
AND l.access_level = candidate.access_level
AND l.document_id = candidate.document_id
AND l.published_generation = candidate.generation
```

Caller ACL scope remains an additional mandatory predicate.

### 4.2 Two fences, not one

Candidate-time filtering does not replace final revalidation.

The target model is:

```text
candidate-time canonical eligibility
        -> ranking / fusion / expansion
        -> context selection with eligibility-aware backfill
        -> final synchronous canonical eligibility revalidation
        -> answer generation
```

The final fence remains necessary for TOCTOU protection against lifecycle/TTL changes that occur after candidate generation.

### 4.3 Fail-closed behavior

Malformed routing identity, invalid ACL, unknown generation, non-READY lifecycle, non-ACTIVE retention, expired TTL or a generation mismatch must never be promoted into final answer context.

Infrastructure failure is not equivalent to an empty result. Existing typed failure/degradation semantics remain authoritative.

## 5. Workstream A — push canonical eligibility into candidate SQL

### 5.1 VECTOR

**Primary code:** `PublishedVectorSearchRepository`.

All vector paths that allocate ANN/exact candidate slots must apply canonical lifecycle eligibility before the bounded candidate set becomes authoritative.

Affected paths include, at minimum:

- global ANN search;
- eligibility-aware ANN retry/fallback;
- fenced global ANN;
- document-scoped exact search;
- language-routed/fallback branches.

Requirements:

1. An `INGESTING`, expired, inactive or unpublished candidate must not consume a final `topK` slot ahead of an eligible candidate.
2. ANN retry health must be computed against canonically eligible rows, not merely `retention_status='ACTIVE'` rows.
3. Candidate-time lifecycle joins must retain ACL and exact generation identity.
4. HNSW behavior must remain deterministic under the existing strict-order configuration.
5. The change must not remove the final lifecycle fence.
6. Storage benchmark evidence must show no unacceptable regression in retrieval latency or saturation.

### 5.2 LEXICAL

**Primary code:** `PostgresSearchProjectionRepository` lexical search paths.

Canonical eligibility must be applied inside the SQL relation that is ranked and limited. Oversampling may remain as a performance/quality technique, but it must not be relied upon for correctness.

A stale or expired projection must not displace a valid lexical result before `LIMIT`.

### 5.3 CONCEPT

**Primary code:** semantic-concept search paths in `PostgresSearchProjectionRepository` used by `ConceptRetrievalStrategy`.

Concept candidates must satisfy the same canonical lifecycle predicate before concept `topK` is exhausted.

No semantic confidence threshold, concept scoring rule or canonical concept ID is changed by this workstream.

### 5.4 IDENTIFIER

**Primary code:** `DocumentIdentifierRepository#queryPublishedScoped` and callers.

Exact identifier retrieval is an authority boundary. Therefore canonical lifecycle eligibility must be enforced before identifier ordering and `LIMIT`.

Required behavior:

```text
newest matching identifier -> ineligible
older matching identifier  -> eligible
identifierLimit = 1

EXPECTED: eligible identifier is returned
NOT: EMPTY after late filtering
```

An identifier-scoped miss must remain fail-closed for dependent semantic lanes; the fix is to make the identifier result correct, not to permit global semantic fallback.

### 5.5 REFERENCE

**Primary code:** `ReferenceGraphRepository#resolveSameDocumentTargets` and the projection materialization used by `ReferenceRetrievalStrategy`.

Canonical lifecycle eligibility must be enforced before reference target `LIMIT` is consumed.

Reference lookup must continue to preserve:

- same-document scope;
- exact generation;
- ACL;
- reference type/value identity;
- exclusion of seed chunks.

### 5.6 Predicate drift prevention

The implementation must avoid five subtly different definitions of "published and eligible".

Preferred solution: introduce one internal canonical SQL predicate/builder or a narrowly scoped repository helper that can be reused by retrieval repositories without coupling write-side lifecycle code to retrieval internals.

If SQL-shape constraints require local SQL fragments, add architecture/contract tests that assert all candidate-producing paths contain the required `READY + ACTIVE + TTL + generation + ACL` semantics.

## 6. Workstream B — ParentContextExpansion before TemporalAuthorityFilter

The current ordering must change from:

```text
... -> graph competition
    -> TemporalAuthorityFilter
    -> ParentContextExpansion
    -> diversity / budget
```

to:

```text
... -> graph competition
    -> ParentContextExpansion
    -> TemporalAuthorityFilter
    -> diversity / budget
```

Rationale: parent expansion creates new evidence. Every newly introduced parent must pass the same temporal/document-authority rules as directly retrieved evidence.

Required cases:

- ACTIVE child -> ACTIVE parent: parent remains eligible;
- ACTIVE child -> DRAFT parent: parent rejected;
- ACTIVE child -> SUPERSEDED parent: parent rejected;
- ACTIVE child -> future `effectiveFrom` parent: parent rejected;
- ACTIVE child -> expired `effectiveTo` parent: parent rejected;
- malformed temporal metadata on parent: fail closed;
- no temporal metadata: preserve current backward-compatible behavior.

The final lifecycle/TTL revalidation still runs after context selection.

## 7. Workstream C — eligibility-before-budget with backfill + final TOCTOU fence

### 7.1 Problem

A fixed context budget can currently be consumed by candidates that later fail final revalidation. When those entries are removed, lower-ranked valid candidates are not promoted into the freed slots.

### 7.2 Target behavior

Context selection must operate on a candidate pool large enough to backfill invalidated entries.

The target sequence is:

```text
ranked / expanded candidate pool
  -> temporal authority
  -> pre-budget canonical eligibility
  -> diversity
  -> budget selection
  -> final TOCTOU lifecycle/ACL/TTL revalidation
  -> bounded backfill when final revalidation creates capacity
  -> optional final revalidation of only newly backfilled entries
  -> immutable final context
```

A simpler equivalent implementation is acceptable if it proves the same invariants and does not introduce unbounded DB round-trips.

### 7.3 Backfill invariants

1. Final context never exceeds `contextMaxChunks`.
2. Final serialized context never exceeds `contextMaxTokens`.
3. Per-document and per-section diversity rules remain enforced after backfill.
4. Graph context quota remains enforced.
5. Backfill preserves original ranking order among still-eligible candidates.
6. Exact-authority evidence remains protected according to existing authority semantics.
7. Backfill is bounded; no open-ended retry loop is permitted.
8. Any candidate inserted by backfill must pass canonical eligibility after the point at which it is selected.
9. If a TOCTOU change invalidates all candidates, the request returns insufficient information rather than stale evidence.

### 7.4 Query-count constraint

The design should prefer batched eligibility reads. Backfill must not degenerate into one lifecycle query per candidate.

## 8. Workstream D — batch/VALUES implementation of `findPublishedByKeys`

**Primary code:** `PostgresSearchProjectionRepository#findPublishedByKeys`.

Replace the per-key `UNION ALL` shape with a bounded batched relation, preferably:

```sql
WITH requested(access_level, document_id, generation, chunk_id) AS (
    VALUES
      (?, ?, ?, ?),
      (?, ?, ?, ?),
      ...
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
WHERE l.lifecycle_status = 'READY'
  AND l.retention_status = 'ACTIVE'
  AND (l.expires_at IS NULL OR l.expires_at > clock_timestamp())
```

Requirements:

- bounded batch size, recommended starting point 128-256 keys;
- deterministic de-duplication of requested keys;
- no cross-ACL/cross-generation match;
- result identity remains `(accessLevel, documentId, generation, chunkId)`;
- empty input returns empty without DB access;
- result order must be deterministic where callers rely on ordering; otherwise callers must explicitly reapply their own rank order;
- no SQL-size explosion proportional to thousands of `UNION ALL` branches.

The implementation should be reusable by fusion, parent/graph materialization and final revalidation paths where applicable without changing their business semantics.

## 9. Workstream E — preserve all ORIGINAL query chunks under multi-query expansion

**Primary code:** `QueryChunker` multi-query merge logic.

The expansion budget must never truncate deterministic ORIGINAL query decomposition.

Target invariant:

```text
all ORIGINAL chunks are retained first
multi-query chunks may consume only remaining expansion capacity
```

If `maxExpandedChunks` is smaller than the number of ORIGINAL chunks, ORIGINAL chunks still win. The effective returned count may therefore be at least the original decomposition size. Configuration validation or effective-cap calculation must make this behavior explicit rather than silently dropping original intent.

Recommended rule:

```text
effectiveMaxChunks = max(maxExpandedChunks, originalChunkCount)
remainingExpansionSlots = effectiveMaxChunks - originalChunkCount
```

Additional invariants:

- deterministic de-duplication remains stable;
- ORIGINAL and MULTI_QUERY identity remain distinguishable;
- generated paraphrases never replace an original unit;
- model timeout/rejection still falls back to originals only;
- no extra model call is introduced when multi-query is disabled.

## 10. Transaction, timeout and cancellation requirements

All retrieval DB operations introduced or changed by this branch must remain under the existing bounded retrieval transaction contract.

The implementation must not introduce raw unbounded JDBC reads on the request path.

Requirements:

- candidate SQL runs under retrieval transaction timeout;
- batch materialization runs under retrieval transaction timeout;
- final lifecycle revalidation remains bounded;
- cancellation/timeout continues to produce typed retrieval outcomes rather than `EMPTY` where infrastructure failed;
- no swallowed `DataAccessException` may convert infrastructure failure into a valid empty retrieval result at an authority boundary.

## 11. Test specification

Tests are part of the implementation contract. Each workstream requires positive, negative and failure/concurrency coverage.

### 11.1 Candidate eligibility integration tests

For VECTOR, LEXICAL, CONCEPT, IDENTIFIER and REFERENCE create repository/integration cases proving that ineligible high-ranked/newer candidates do not consume bounded slots ahead of eligible candidates.

Required lifecycle variants:

- `READY + ACTIVE + unexpired` -> eligible;
- `INGESTING + ACTIVE` -> rejected;
- `FAILED + ACTIVE` -> rejected;
- `READY + inactive retention` -> rejected;
- `READY + ACTIVE + expired TTL` -> rejected;
- previous/unpublished generation -> rejected;
- wrong ACL -> rejected.

At least one test per bounded lane must put an ineligible candidate ahead of an eligible candidate and assert that the eligible candidate still appears inside the requested limit/topK.

### 11.2 Identifier authority tests

Positive:

- exact identifier finds the eligible canonical chunk;
- multiple ACLs retain only caller-authorized results;
- newest ineligible + older eligible with `limit=1` returns the older eligible result.

Negative:

- all matching identifiers ineligible -> identifier outcome `EMPTY`;
- identifier infrastructure failure remains critical and does not trigger global semantic fallback.

### 11.3 Parent temporal-authority tests

- parent introduced after child retrieval passes through temporal authority;
- DRAFT/SUPERSEDED/future/expired/malformed parent metadata is rejected;
- valid parent remains eligible and preserves child-derived attribution metadata required by citations/authority handling.

### 11.4 Budget/backfill tests

Positive:

- early candidates invalidated -> lower-ranked eligible candidates fill released slots;
- token and chunk limits remain satisfied after backfill;
- per-document/per-section quotas remain satisfied;
- exact-authority ordering remains protected.

Negative/failure:

- all backup candidates ineligible -> context shrinks safely;
- lifecycle changes between pre-budget check and final fence -> stale candidate removed;
- backfill candidate itself becomes stale before finalization -> removed, no stale generation leaks;
- DB timeout during authoritative final fence -> retrieval unavailable, not insufficient-information false success.

### 11.5 `findPublishedByKeys` tests

- empty keys -> no DB query / empty result;
- duplicate keys -> one canonical result;
- > batch size -> multiple bounded batches, complete result set;
- exact ACL/generation/chunk matching;
- expired/INGESTING rows excluded;
- large key set does not generate per-key `UNION ALL` SQL;
- deterministic behavior under mixed found/missing keys.

### 11.6 Multi-query tests

- 8 ORIGINAL chunks + `maxExpandedChunks=3` -> all 8 originals retained;
- originals consume capacity before generated variants;
- remaining slots are filled by unique MULTI_QUERY variants only;
- timeout/rejection/model failure -> originals preserved exactly;
- duplicate generated variant cannot displace original chunk.

### 11.7 Concurrency / TOCTOU tests

Use deterministic synchronization/latches where possible; do not rely on sleep-only races.

Required scenarios:

1. candidate selected while document is READY, lifecycle becomes INGESTING before final fence -> candidate rejected;
2. TTL crosses expiry between candidate search and final fence -> candidate rejected;
3. generation N candidate is ranked, generation N+1 publication supersedes it before final fence -> N rejected;
4. candidate invalidated after pre-budget eligibility -> valid backup fills capacity if still eligible;
5. simultaneous requests with different ACL scopes must not contaminate batched key materialization.

### 11.8 Performance/regression tests

- retrieval storage benchmark must cover the changed candidate SQL and batched key lookup;
- compare p50/p95/p99 or existing benchmark-equivalent metrics before/after where the harness supports them;
- assert no unbounded growth in SQL text or DB round-trips with increasing candidate count;
- ANN candidate correctness must not be traded for silent recall loss.

## 12. Observability

Existing metrics remain authoritative. Add metrics only when they provide bounded operational value.

Recommended counters/timers if not already observable:

- candidate lifecycle rejections by lane/reason class;
- context candidates rejected before budget;
- final TOCTOU rejections;
- context backfill count;
- backfill exhausted count;
- `findPublishedByKeys` batch count / requested key count.

Do not put document IDs, chunk text, identifiers or other high-cardinality/sensitive values into metric labels.

## 13. Documentation updates required in the implementation PR

When implementation lands, update in the same branch/PR:

- `docs/services/retrieval-flow.md`;
- `docs/services/retrieval-execution.md` if execution/degradation wording changes;
- `docs/architecture/current-runtime-architecture.md`;
- relevant storage/indexing documentation if SQL topology changes materially;
- this document from `TARGET` to `CURRENT / IMPLEMENTED` or remove it after folding all durable rules into current-state docs;
- `docs/README.md` synchronization baseline.

No stale TARGET specification may remain after implementation is complete.

## 14. Implementation order

Implement in this order to keep failures attributable:

1. establish shared canonical candidate eligibility contract;
2. fix IDENTIFIER and REFERENCE candidate predicates;
3. fix LEXICAL and CONCEPT candidate predicates;
4. fix VECTOR candidate/ANN eligibility and retry semantics;
5. replace `findPublishedByKeys` with bounded batched `VALUES` lookup;
6. move ParentContextExpansion before TemporalAuthorityFilter;
7. implement eligibility-before-budget and bounded backfill while retaining final TOCTOU fence;
8. preserve all ORIGINAL query chunks under multi-query expansion;
9. complete positive/negative/concurrency/failure tests;
10. run full exact-head validation.

Commits may be split by coherent workstream, but the branch and PR remain one retrieval-correctness change set.

## 15. Definition of Done

The branch is merge-ready only when all of the following are true:

- all five retrieval candidate producers enforce canonical eligibility before bounded candidate truncation;
- parent-expanded evidence passes temporal authority;
- final context can backfill released slots without violating any context budget or authority rule;
- final synchronous TOCTOU lifecycle/ACL/TTL fence remains present;
- `findPublishedByKeys` uses bounded batched lookup rather than one `UNION ALL` branch per key;
- multi-query expansion cannot remove any ORIGINAL query chunk;
- positive, negative, failure-injection and deterministic concurrency tests exist and pass;
- no canonical semantic IDs or quality thresholds changed;
- current-state documentation matches executable behavior;
- the same immutable head SHA passes all required exact-head gates.

## 16. Exact-head acceptance gates

The following workflows must all report `success` against the exact same immutable branch head SHA:

1. `CI`;
2. `Retrieval Quality Gate`;
3. `Retrieval Storage Final Benchmark`;
4. `Production Image Build`.

A green workflow on an earlier SHA is not acceptance evidence for a later head.

If any gate fails, fix the implementation or test/documentation defect. Do not lower thresholds, remove assertions, disable tests or weaken eligibility predicates to obtain green status.

## 17. System architect conclusion

**Assessment:** APPROVED AS TARGET, implementation recommended before declaring the current retrieval runtime quality-stable.

The current retrieval architecture is structurally sound: it has generation identity, ACL-scoped routing, typed degradation semantics, bounded executors, canonical fusion, final lifecycle revalidation and grounded answer verification. The identified problems are not a reason to redesign the system.

The main architectural weakness is predicate placement. A safety rule applied only after a bounded `LIMIT/topK` protects confidentiality and staleness but does not protect retrieval completeness. Therefore lifecycle eligibility belongs both at candidate admission and at the final TOCTOU boundary.

Moving parent expansion before temporal authority restores the invariant that every piece of newly introduced evidence crosses all authority fences. Eligibility-aware backfill then restores the complementary completeness invariant: a stale candidate must neither leak into context nor permanently consume a context slot that an eligible lower-ranked candidate could use.

The proposed batched `VALUES` materialization is architecturally preferable to per-key `UNION ALL` because it bounds SQL-plan complexity while preserving exact composite identity. The multi-query change is also correct: probabilistic expansion is supplemental and must never evict deterministic original intent.

No new infrastructure component is required. The change should remain inside the existing PostgreSQL/retrieval architecture.

**Architectural merge recommendation:** merge only after exact-head evidence proves all four gates on one SHA and concurrency/failure tests prove the TOCTOU and backfill invariants.

## 18. RAG expert conclusion

**Assessment:** HIGH-VALUE CORRECTNESS HARDENING; expected effect is improved recall stability and lower false-negative retrieval under lifecycle churn without reducing precision/grounding safeguards.

The strongest RAG benefit comes from moving canonical eligibility ahead of lane-specific truncation. Late filtering alone can make an otherwise healthy retriever appear weak: relevant valid evidence exists but is hidden behind expired, ingesting or superseded candidates that consumed ANN, lexical, concept, identifier or reference slots. This is especially damaging for exact-identifier queries because an incorrect `EMPTY` authority result intentionally suppresses broader semantic fallback.

The target preserves the right precision controls: exact identifier/reference authority, ACL scoping, generation identity, temporal authority, bounded graph contribution, context budgets, citations and grounding all remain intact. The proposal therefore improves recall without obtaining it by relaxing safety or relevance thresholds.

Backfill is important for answer quality because final TOCTOU revalidation can otherwise reduce context after ranking and budgeting. A bounded, order-preserving backfill mechanism converts that unavoidable race from silent evidence loss into deterministic context completion.

Preserving all ORIGINAL query chunks is also a necessary RAG invariant. Multi-query generation is probabilistic recall augmentation; it must consume spare budget only after deterministic decomposition has been retained.

**RAG merge recommendation:** accept the implementation only if the quality gate remains green, identifier false-negative regression tests are present, valid candidates behind ineligible top-ranked candidates are recovered in every bounded lane, and no benchmark evidence shows unacceptable latency/saturation regression.