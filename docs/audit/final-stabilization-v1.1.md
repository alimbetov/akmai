# AkmAI v1.1 final stabilization

Status: **STABILIZATION / FEATURE FREEZE CANDIDATE**  
Baseline: `main@e2eece172106104ac08db7ef8bb2ad2d9e074fdc`  
Stabilization branch: `fix/final-stabilization-v1.1`

## Purpose

This patch is the proposed boundary between feature development and the next project phase.
After this branch is accepted, new product features should not be added to the v1.1 line unless they fix a demonstrated correctness, security, lifecycle, availability or data-integrity defect.

The next phase is intentionally limited to:

- retrieval and answer quality;
- measurable latency/throughput/resource efficiency;
- concurrency and soak testing;
- production observability and release evidence;
- regression prevention.

## Correctness defects included in this stabilization

### S01 — deployment classification must fail closed

Problem:
An installation with neither `AKMAI_ENVIRONMENT`/`akmai.environment` nor an active Spring profile could be classified implicitly as local. This allowed local-friendly security defaults to be selected by omission.

Patch:

- deployment classification is now mandatory;
- only explicit `local`, `dev` or `test` classification is considered local;
- an unspecified environment aborts startup;
- test runtime declares `akmai.environment=test` explicitly;
- a regression test covers the no-environment/no-profile case.

Invariant:

> Missing deployment classification must never weaken authentication or database-secret requirements.

### S02 — TTL eligibility before derived retrieval expansion

Problem:
A final TTL revalidation existed, but expired rows that were still marked `ACTIVE` could be materialized by published projection reads between base retrieval and final context revalidation. Such rows could therefore consume expansion/context-budget slots before being removed.

Patch:

- `PublishedProjectionLifecycleEligibility` applies the canonical `READY + ACTIVE + unexpired + published generation` predicate to materialized search projections;
- `TimedPublishedSearchProjectionReader` applies that fence to keyed reads, adjacency, lexical and semantic-concept materialization;
- final `PublishedContextRevalidator` remains the last synchronous safety fence.

Invariant:

> An expired authoritative generation must not influence ranking, expansion or context budgeting even while asynchronous retention cleanup has not run.

Performance note:
The correctness patch introduces an additional lifecycle eligibility lookup on published projection materialization. The quality/performance phase must measure this cost and, if material, push the canonical predicate into lower-level SQL or introduce a bounded eligibility cache without weakening synchronous expiry semantics.

### S03 — live re-embedding ownership heartbeat

Problem:
Re-embedding ownership is fenced by `owner_id`, `lease_until` and `fencing_token`, but a healthy owner could spend longer than the lease duration inside a blocking embedding/model call because renewal happened only at orchestration boundaries.

Patch:

- `ReembeddingLeaseManager` can renew all still-live active migrations owned by the current replica;
- `ReembeddingLeaseHeartbeatScheduler` renews those leases independently of the blocking migration call path;
- expired leases are not resurrected: renewal requires `lease_until > clock_timestamp()` and the current `owner_id`.

Invariant:

> A healthy owner must retain its lease during long-running work, while a stale owner must remain fenced after takeover.

Required quality evidence after stabilization:

- blocked embedding duration greater than lease duration does not permit takeover while heartbeat is healthy;
- heartbeat loss allows takeover after lease expiry;
- the stale owner cannot stage or cut over after fencing-token change.

### S04 — derived Query Memory cannot freely outlive its sources

Problem:
Persistent Query Memory has its own TTL and previously could retain grounded historical notes after the source knowledge was expired or retired. Those notes are not directly cited, but they can influence HyDE query construction.

Patch:

- `QueryMemorySourceEligibility` validates historical observation source references immediately before they enter the HyDE prompt;
- every source reference must still resolve through a caller-authorized, currently published, `READY`, `ACTIVE` and unexpired search projection;
- an observation with missing or stale sources is omitted from HyDE memory hints.

Invariant:

> Derived retrieval memory must not remain an active semantic hint after its authoritative evidence is no longer retrieval-eligible.

Known hardening item:
Current persisted source references use the legacy `documentId:chunkId` representation. The quality phase should migrate provenance to a generation-aware identity such as `(access_level, document_id, generation, chunk_id)` so a reused chunk identifier in a newer generation cannot validate an older historical note by identity alone.

## Deliberately deferred from the stabilization patch

### Adaptive graph lock-protocol unification

Learning/semantic seeding use ordered advisory node locks while maintenance primarily uses row locking with `FOR UPDATE SKIP LOCKED`. This is a plausible concurrency/deadlock risk, but a corruption defect has not been demonstrated.

Changing the lock protocol immediately before feature freeze would be higher risk than first producing deterministic evidence.

Quality-phase requirement:

- concurrent stress test with reinforcement + semantic seed + maintenance + compaction on overlapping pairs;
- measure deadlocks, lock waits, aborted transactions and throughput;
- unify lock ordering only if evidence demonstrates a problem or the simpler protocol can be proven equivalent.

## Issue disposition after this patch

The GitHub issue state must follow acceptance evidence, not code presence alone.

- #36 TTL synchronous fence: closure candidate only after expansion/materialization regression coverage passes.
- #37 non-local security: closure candidate after explicit-environment fail-closed tests pass.
- #38 DB credentials: closure candidate together with #37 because deployment classification controls when credential hardening is mandatory.
- #39 retrieval resource timeout: keep as acceptance/evidence work until saturation testing proves worker/resource recovery.
- #40 request byte limit: implementation appears complete; close only with transport-level regression evidence retained in CI.
- #41 re-embedding HA fencing: closure candidate only after long-running heartbeat/takeover integration tests pass.

## Feature-freeze exit criteria

The v1.1 development phase can be considered functionally closed when all of the following hold:

1. this stabilization PR is green in normal CI and release-relevant quality workflows;
2. no open P0/P1 correctness or security defect has a confirmed execution path in current `main`;
3. #36–#41 are either closed with acceptance evidence or explicitly reclassified as quality evidence tasks rather than missing product functionality;
4. target-hardware quality and performance baselines are captured and versioned;
5. `main` is protected by required checks so a failed CI revision cannot become the accepted release state silently.

## Post-freeze quality/performance backlog

Priority order:

1. real-corpus retrieval benchmark: Recall@K, MRR, nDCG, citation recall, grounded-answer rate and abstention quality;
2. p50/p95/p99 end-to-end latency and per-stage latency under mixed load;
3. saturation/soak tests for retrieval executors, JDBC pool, Ollama/model clients and re-embedding heartbeat;
4. measure TTL eligibility query overhead and optimize only with unchanged expiry correctness;
5. generation-aware Query Memory provenance and lifecycle-driven invalidation;
6. Adaptive Graph concurrent lock stress test and online incremental-utility measurement;
7. context efficiency: useful/cited evidence per selected chunk and per prompt token;
8. branch protection + required CI/release checks;
9. production observability review for timeout, lifecycle, graph, query-memory and migration failure modes.

No item above should become a new user-facing feature unless a benchmark or production defect demonstrates that functionality is required.
