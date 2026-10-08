# Post-retrieval audit implementation decisions

Status: **CURRENT IMPLEMENTATION DECISIONS**  
Branch: `quality/post-retrieval-project-audit`  
Parent specs:

- `post-retrieval-project-audit-remediation-spec.md`
- `post-retrieval-project-audit-code-blueprint.md`

This file records implementation discoveries that refine or supersede lower-level mechanism choices in the TARGET specifications. Scope, invariants and acceptance criteria in the parent specifications remain normative unless explicitly changed here.

## DEC-01 — Generation reconciliation multi-pod ownership

### Earlier target assumption

The first blueprint proposed short PostgreSQL `FOR UPDATE SKIP LOCKED` candidate claiming.

### Implementation finding

A row lock acquired only in a short discovery transaction is released at commit, before `GenerationRepairService` performs the actual reconciliation work. Therefore a plain short `SKIP LOCKED` discovery step does **not** provide cross-transaction ownership.

Keeping the generation row locked across discovery and repair would also risk changing the established lifecycle/generation lock order used by publication and reconciliation.

### Final implementation

Generation reconciliation uses two complementary mechanisms:

1. **candidate-scoped transaction advisory lock**
   - `GenerationReconciliationService` calls `pg_try_advisory_xact_lock(candidateKey)` inside the existing bounded cleanup transaction;
   - failure to acquire is non-blocking: the candidate is skipped;
   - the advisory lock is an intra-transaction ownership/anti-contention primitive only;
   - lifecycle and generation row locks remain final mutation authority;
   - lock order remains lifecycle -> generation.

2. **durable `PURGING` tombstone claim for RETIRING**
   - phase A creates and commits a `knowledge_retired_generation` row with `cleanup_status='PURGING'` before physical deletion;
   - a fresh `PURGING` tombstone is treated as active work and is not stolen;
   - stale `PURGING` is reclaimable after `max(reconciliation.grace-period, reconciliation.fixed-delay)`;
   - reclaim increments `cleanup_attempts`, refreshes `purge_started_at`, and retries;
   - phase B reacquires the candidate advisory lock and rechecks publication/lifecycle authority before repair.

### Why this is preferred

- no new reconciliation lease/fencing schema is required;
- no long discovery transaction is introduced;
- no generation-before-lifecycle row-lock inversion is introduced;
- terminal `RETIRED` / `FAILED` work remains protected for the full existing cleanup transaction;
- RETIRING already has a durable tombstone that naturally bridges its intentional two-transaction protocol;
- PostgreSQL automatically releases transaction advisory locks on commit/rollback/connection loss;
- stale durable work remains recoverable after process crash.

### Valid `SKIP LOCKED` usage that remains

`FOR UPDATE SKIP LOCKED` is still used when selection and mutation complete in the **same transaction**, for example bounded deletion of expired VERIFIED tombstones.

It must not be described as a durable ownership claim after the selecting transaction commits.

### Tests

- `GenerationReconciliationMultipodIntegrationTest.busyCandidateIsSkippedWithoutBlockingAndRetriesAfterLockRelease`
- `GenerationReconciliationMultipodIntegrationTest.freshPurgingTombstoneIsNotStolenButStaleClaimIsRecovered`
- existing `PostgresVectorReconciliationIntegrationTest`
- existing `GenerationRepairServiceIntegrationTest`

### Documentation authority

For PRA-04 implementation mechanics, this decision and `docs/services/generation-reconciliation.md` supersede any earlier TARGET text that prescribes short `SKIP LOCKED` claiming. The original requirements remain unchanged:

- avoid duplicate active work/row-lock contention across pods;
- preserve publication/lifecycle fail-closed behavior;
- preserve two-phase RETIRING durability;
- preserve bounded/idempotent repair and crash recovery.
