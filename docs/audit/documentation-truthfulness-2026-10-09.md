# Documentation truthfulness baseline — 2026-10-09

Status: **CURRENT VERIFICATION BASELINE**  
Audit date: **2026-10-09**  
Audited code baseline: `main@b40cfc66e68cb2ea84c437d5d658d3a4d04633e3`  
Working branch: `quality/documentation-truthfulness-regression-pass`

## Purpose

This pass reconciles current documentation with executable behavior before the full regression run. It does not promote a service to `VERIFIED` merely because implementation or tests exist. `VERIFIED` requires the exact code/documentation head to pass the required verification gates.

Historical audit snapshots remain historical evidence and are not rewritten to pretend they described the current repository state at the time they were authored.

## Current truthfulness rules

1. Current-state service indexes must point to contracts that actually exist.
2. `IMPLEMENTED` describes code state; it is not equivalent to `VERIFIED`.
3. `VERIFIED` requires current implementation, concrete positive/negative tests, and green exact-head verification.
4. Static feature configuration is the immutable outer safety boundary where the contract says it is a kill switch; a runtime database flag cannot override `static=false`.
5. Mutation-capable runtime gates use authoritative PostgreSQL reads and fail closed when that authority is unavailable.
6. Multi-pod scheduler triggers are not distributed authority. Authority comes from PostgreSQL lease/fencing, row/advisory locks, claims, or explicitly duplicate-safe behavior.
7. Claims about multi-pod correctness must identify the actual ownership primitive and the test evidence; JVM scheduling alone is never accepted as proof.
8. Documentation must distinguish implementation readiness from release qualification.

## Reconciled discrepancies

### DT-01 — service inventory lagged implemented contracts

Before this pass, `docs/services/service-inventory.md` still listed scheduled/background jobs and runtime safety flags as `TBD/TODO`, although `scheduled-jobs.md` and `runtime-safety-flags.md` already existed.

Disposition: inventory now links those contracts and keeps them `DRAFT` until the exact current head passes verification.

### DT-02 — graph maintenance documentation was absent from the inventory

Graph maintenance has executable behavior and multi-pod ownership semantics documented across `scheduled-jobs.md` and `adaptive-graph-mutation.md`, but the inventory still said `TBD/TODO`.

Disposition: inventory now points to both current contracts and remains `DRAFT` pending exact-head verification.

### DT-03 — runtime double-gate semantics

Current mutation consumers use the static configuration as the outer gate. For example, adaptive graph learning and maintenance return disabled immediately when the corresponding static property is false; only when the static gate is open may the authoritative runtime flag authorize work.

This is the required interpretation for current documentation. Any older wording that implies runtime `true` can override a static `false` is stale.

### DT-04 — historical #36-#41 tracker state

Issues #36-#41 are historical closed/completed tracker items. Historical readiness documents may retain the earlier action plan, but current indexes must not describe those issues as still-open production defects without new failing evidence.

### DT-05 — CI branch-family statement

Current `.github/workflows/ci.yml` includes `quality/**` and `docs/**` push branches and keeps `pull_request` verification. The old PRA-08 finding is therefore implemented at code/configuration level; release governance still depends on repository required-check enforcement and exact-head results.

## Items intentionally not promoted to VERIFIED yet

The service inventory remains conservative. Publication, retention, re-embedding, graph/Dream, reconciliation, scheduled jobs, runtime safety flags, ingestion and retrieval contracts stay `DRAFT` until the branch head containing this truthfulness pass is exercised by the complete regression/CI gate set.

No documentation-only status promotion is allowed before that evidence exists.

## Required next step

Run the full system regression pass on the exact branch head, including normal Maven verification and the repository's dedicated retrieval/storage/image gates. For every red test:

- classify as production defect, stale regression expectation, flaky test, or environment/infrastructure failure;
- reproduce from current code/evidence before changing production behavior;
- add or strengthen negative/concurrency coverage when a real defect is fixed;
- rerun the affected gate and then the full gate set;
- only after all required exact-head gates are green may eligible contracts move from `DRAFT` to `VERIFIED`.

## Exit criterion

Documentation truthfulness is complete when current indexes and service contracts no longer contradict current implementation semantics, while historical documents remain clearly labeled as snapshots. Release qualification is a separate later decision.