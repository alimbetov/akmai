# Adaptive Graph Dream cycle

Status: **DRAFT**

## Purpose

Adaptive Graph Dream is a bounded, single-owner background process that discovers and revalidates semantic graph hypotheses without creating online learned evidence. PostgreSQL lease time, fencing tokens, lifecycle state and guarded DML remain authoritative.

Dream v1 is intentionally sequential. The fast lane accelerates recently changed source discovery; the bounded rescan lane is the eventual-coverage mechanism for the eligible corpus.

## Entry points

- `AdaptiveGraphDreamScheduler` — scheduled trigger on every pod.
- `AdaptiveGraphDreamCoordinator.runOnce()` — local overlap prevention, lease acquisition, budgets, lane orchestration and final run outcome.
- `DreamLeaseManager` — PostgreSQL-time ownership and fencing.
- `DreamLeaseHeartbeat` — dedicated owner heartbeat.
- `DreamCheckpointRepository` — fenced fast-lane watermark.
- `DreamRescanCheckpointRepository` — fenced deterministic rescan cursor.
- `DreamCandidateDiscovery` — forward/reverse ANN verification and current candidate state.
- `DreamCandidateRepository` — canonical candidate persistence under live fencing authority.
- `SemanticGraphPriorWriter` — optional restricted semantic-prior application.

## Process

```text
scheduler tick on every pod
  -> static/runtime Dream gate
  -> local overlap CAS
  -> resolve immutable semantic policy epoch
  -> require non-zero bounded rescan capacity
  -> PostgreSQL lease acquire
       -> unavailable: STANDBY
       -> acquired: owner + fencing token
  -> start dedicated heartbeat
  -> initialize fenced checkpoints
  -> start run row
  -> reserve source capacity for rescan
  -> process fast lane
       -> source
       -> forward ANN
       -> reciprocal verification
       -> candidate current-state transition
       -> optional fenced semantic prior apply
       -> authority-loss stop barrier
       -> fenced fast checkpoint CAS
  -> process bounded rescan lane
       -> same discovery/revalidation
       -> authority-loss stop barrier
       -> fenced rescan cursor CAS
  -> authority-loss stop barrier
  -> fenced run finalization
  -> clear per-run dedupe
  -> stop heartbeat
  -> fenced best-effort release
```

## Business rules

### DREAM-BR-01 — one distributed owner

A Dream run may perform authoritative state changes only while its `(graph_version, semantic_policy_fingerprint, owner_id, fencing_token)` identifies the current live PostgreSQL lease.

Expired takeover increments the fencing token. An old owner/token cannot renew, advance checkpoints, persist candidate observations, apply priors or finalize success.

### DREAM-BR-02 — PostgreSQL time is authority

Lease acquisition, renewal and guarded mutations use `clock_timestamp()`. JVM clocks are not distributed ownership authority.

### DREAM-BR-03 — heartbeat failure is a local stop barrier

Any heartbeat renewal failure marks the local run as having lost authority. After that signal, the coordinator must not intentionally advance a checkpoint or finalize the run as successful, even if the previous lease timestamp has not yet expired.

The coordinator therefore rechecks the local authority-loss barrier:

- before each unit of work;
- after candidate discovery and before fast/rescan checkpoint advancement;
- after rescan reads before cursor reset/advance;
- before final `SUCCEEDED` / `PARTIAL_BUDGET` finalization paths.

Guarded PostgreSQL DML remains the final stale-owner fence.

### DREAM-BR-04 — fast lane is not completeness authority

The fast lane is keyed by the source lifecycle update watermark and accelerates discovery of recent changes. Missing a source in the fast lane must not make it permanently invisible to Dream.

### DREAM-BR-05 — bounded rescan is eventual coverage authority

Every enabled Dream run must reserve at least one source slot for deterministic rescan. A configuration with zero rescan capacity is rejected by the coordinator before lease acquisition.

The rescan cursor is durable, policy-scoped and compare-and-set under live fencing authority. End-of-corpus resets the cursor to `null`, starting the next bounded rotation from the beginning.

### DREAM-BR-06 — malformed rescan cursor fails explicitly

`DreamRescanCursor.decode()` rejects malformed encodings. The run fails rather than silently skipping an unknown corpus range. Recovery is operator/data repair of the invalid checkpoint followed by a normal bounded run; silent cursor invention is forbidden.

### DREAM-BR-07 — DB/ANN work is budgeted before execution

Source and ANN budgets are reserved before calls. DB-row capacity must likewise be reserved before candidate/prior DML. If a guarded operation performs no durable mutation or fails, its DB-row reservation is released.

A `MAX_DB_ROWS` stop therefore occurs before the write that would exceed the configured bound.

### DREAM-BR-08 — ACTIVE means current semantic state

`ACTIVE` is not a historical “was once activated” flag.

For a pair that was already `ACTIVE` at the start of the source evaluation:

```text
mutual-KNN
AND confidence >= retentionThreshold
-> remain ACTIVE
```

For a pair that was not already active:

```text
mutual-KNN
AND confidence >= activationThreshold
AND per-source new-edge admission remains
-> ACTIVE
```

The `maxNewEdgesPerChunk` limit applies to new ACTIVE admissions for the current source/run; retaining an already-active pair does not consume a new-edge slot.

### DREAM-BR-09 — current negative evidence deactivates old ACTIVE state

An existing ACTIVE pair becomes `STALE` when current evaluation proves any of the following:

- the pair is no longer in the source's forward top-K above the candidate threshold;
- source/target lifecycle verification becomes ineligible;
- reciprocal verification or confidence falls below the ACTIVE retention contract.

Candidate retirement is fenced by the live Dream lease. A missing prior candidate row is a safe no-op; loss of authority is not.

### DREAM-BR-10 — candidate state changes do not synthesize online evidence

Dream candidate updates must not increment:

- `support_count`;
- `context_count`;
- `citation_count`;
- `distinct_query_support`;
- `query_support_sketch`.

Dream remains semantic-prior authority only.

### DREAM-BR-11 — candidate persistence is fenced in final DML

Candidate observations and stale transitions include the live lease predicate in the same SQL statement as the mutation. A separate preliminary ownership check is insufficient.

### DREAM-BR-12 — apply remains independently fenced

Candidate `ACTIVE` does not itself authorize graph mutation. `SemanticGraphPriorWriter` independently requires the apply gate, eligible graph nodes, degree admission and current Dream fencing authority in final DML.

### DREAM-BR-13 — runtime disable is fail-safe

If Dream is disabled during a run, the coordinator stops at the next safe boundary and attempts fenced `CANCELLED` finalization. If authority was lost at the same time, lost ownership wins over optimistic cancellation/success semantics.

### DREAM-BR-14 — run success requires current authority

A run may not claim `SUCCEEDED` after the heartbeat stop barrier or after guarded finalization rejects the owner/token. Lost authority is represented as `LOST_OWNERSHIP` best-effort; failure to record that diagnostic must not restore authority.

## Positive cases

- one pod acquires the lease; other pods return `STANDBY`;
- healthy heartbeat renews the same owner/token;
- fast lane processes changed sources and advances the fenced watermark;
- bounded rescan advances the deterministic cursor independently of fast-lane completeness;
- an existing ACTIVE pair at confidence between retention and activation remains ACTIVE;
- a new high-confidence mutual pair can become ACTIVE subject to per-source admission;
- apply-disabled shadow mode persists Dream-internal observations without graph mutation;
- budget stop produces a bounded partial outcome while preserving resumable checkpoints.

## Negative / failure cases

- stale owner/token tries candidate/checkpoint/prior mutation -> rejected by fencing;
- heartbeat loses authority during discovery -> no subsequent checkpoint advancement;
- heartbeat loss before finalization -> no `SUCCEEDED` outcome;
- zero rescan reservation -> run fails before lease acquisition;
- malformed rescan cursor -> explicit run failure;
- ACTIVE pair disappears from forward top-K -> candidate becomes STALE;
- ACTIVE pair becomes lifecycle-ineligible -> candidate becomes STALE;
- DB-row budget already exhausted -> candidate/prior DML is not invoked;
- apply gate disabled -> no online graph mutation;
- ANN/JDBC timeout -> current run fails or stops according to the owning boundary; no stale authority is inferred locally.

## Transaction and fencing boundary

Dream intentionally does not hold one long transaction across ANN work. Ownership is durable lease/fencing state, not a JVM lock or a database transaction spanning the run.

Each authoritative persistence operation carries its own live authority predicate:

```text
lease row
  graph_version
  semantic_policy_fingerprint
  owner_id
  fencing_token
  lease_until > clock_timestamp()
```

Checkpoint updates additionally use compare-and-set expected cursor/watermark semantics so stale/replayed work cannot skip ranges.

## Candidate-state recovery

The candidate store is re-evaluated by future fast/rescan observations. `STALE` records remain auditable under their semantic-policy fingerprint and may become current again only through a later valid observation/admission path.

DREAM-4B does not accumulate cross-run verification streaks. Stateful streak accumulation remains deferred to DREAM-6 and must not be approximated by repeatedly incrementing the current observation row.

## Known gap before VERIFIED

Candidate `ACTIVE -> STALE` is now explicit, but graph-side semantic-prior retirement still requires one final decision/implementation pass: either prove existing graph maintenance/lifecycle cleanup is sufficient for every stale-candidate cause, or add a restricted fenced semantic-prior retirement DML that clears semantic fields while preserving learned counters. Do not mark this contract `VERIFIED` until that boundary is closed by tests.

## Tests

Primary coverage:

- `DreamCoreContractsTest`;
- `DreamCandidateDiscoveryFailureModelTest`;
- `AdaptiveGraphDreamCoordinatorFailureModelTest`;
- `SemanticGraphPriorWriterIntegrationTest`.

Required remaining integration coverage before `VERIFIED`:

- lease token monotonic takeover and stale-owner rejection across two DB clients;
- heartbeat renewal failure / takeover sequence;
- checkpoint resume across coordinator restart;
- malformed persisted rescan cursor run outcome;
- graph semantic-prior retirement preserving learned evidence;
- exact-head CI / quality / storage / image verification.

## Definition of Done

- [x] Single-owner and fencing authority documented
- [x] Heartbeat stop barrier implemented at checkpoint/finalization boundaries
- [x] Fast lane classified as accelerator only
- [x] Bounded rescan coverage requirement enforced
- [x] ACTIVE retention/current-state semantics implemented
- [x] Forward-top-K and lifecycle deactivation implemented
- [x] DB-row budget reserved before Dream writes
- [x] Candidate state unit failure matrix added
- [x] Coordinator authority-loss unit failure matrix added
- [ ] Multi-client lease/checkpoint integration matrix complete
- [ ] Semantic-prior retirement boundary closed
- [ ] Exact-head required gates green
- [ ] Inventory promoted from DRAFT only on verified final SHA
