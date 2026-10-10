# Async Knowledge Ingestion Worker v1 — Implementation Closeout

**Status:** CURRENT / IMPLEMENTED  
**Implementation PR:** #89 `feature/async-ingestion-worker-v1`  
**Merge commit:** `fbc8fd6c2a5bce9a785753211df702cf02735858`  
**Authority:** runtime code + Liquibase migrations + `application.yml`; `async-ingestion-worker-v1.md` remains the behavioral contract and the code blueprint remains the implementation reference.

---

## 1. Closeout decision

Async ingestion v1 is implemented and merged. The feature is no longer a TARGET-only design.

The implemented runtime provides:

- `POST /api/knowledge/ingestions` asynchronous admission with `202 Accepted` semantics;
- durable PostgreSQL `knowledge_ingestion_job` queue state;
- event alias/deduplication and immutable source-version protection;
- stable internal idempotency key reused across retries/reclaims;
- bounded worker concurrency;
- independent outer job lease heartbeat;
- DB-time lease/reclaim/fencing predicates;
- retry, permanent-failure and ambiguous-failure classification;
- replay recovery after publication-before-job-finalization crash windows;
- status lookup by ingestion id;
- integration with the existing `KnowledgeIngestionPort` rather than a second RAG pipeline;
- active-claim-aware stale-generation recovery protection.

The implementation is therefore suitable to move from feature development to rollout qualification.

---

## 2. Verified architecture invariants

### Durable acceptance

Admission serializes and bounds the inline canonical payload before repository admission. The repository owns the admission transaction and returns the durable job. The controller returns HTTP `202` only after the admission service completes.

### Separate ownership domains

The async job repository owns `lease_owner`, `lease_until` and `lease_version` for queue state. The worker passes the persisted `internal_idempotency_key` into the existing knowledge-ingestion port, leaving the existing ingestion claim/fencing mechanism authoritative for protected RAG side effects.

### Fenced terminal/retry mutation

`renew`, `markRetry`, `markIngested` and `markFailed` require the active `PROCESSING` state plus matching owner/version and an unexpired DB-time lease. A stale worker cannot overwrite queue state after ownership loss.

### Crash/replay recovery

The same internal idempotency key is retained for the logical job across reclaims. If publication succeeds and the worker crashes before the job is finalized, a later reclaim re-enters the existing ingestion pipeline with the same key and can finish from replayed publication state.

### Source-version identity

Migration `030-async-ingestion-source-version-identity.sql` enforces uniqueness for `(file_id, source_version)` when `file_id` is present. Admission rejects reuse of the same immutable source identity with a different job fingerprint.

### Stale-generation safety prerequisite

`DocumentGenerationRepository.failStaleIngestionBatch(...)` excludes a candidate generation while a matching `knowledge_ingestion_request` is `IN_PROGRESS` with an unexpired PostgreSQL-time lease. Long-running active ingestion is therefore not falsely failed by stale-generation recovery.

---

## 3. Verification evidence

PR #89 was merged into `main` on 2026-10-09 and changed 37 files with the async API, queue/orchestration implementation, migrations, configuration, metrics and tests.

Observed post-merge GitHub Actions evidence for merge commit `fbc8fd6c2a5bce9a785753211df702cf02735858` includes successful runs for:

- `verify`;
- `Production Image Build`;
- `Retrieval Quality Gate` / required retrieval benchmark gate;
- retrieval ACL matrix jobs visible on the merge commit.

This closeout does not treat GitHub's legacy combined commit-status endpoint as authoritative because this repository reports the relevant workflow results as check runs.

---

## 4. Test coverage present in the implementation change

The merged implementation includes dedicated coverage for:

- controller admission/status behavior;
- admission validation and deduplication;
- failure classification;
- PostgreSQL queue claim/lease/fencing transitions;
- publication replay recovery;
- source-version identity conflict/reuse behavior;
- worker success/retry/failure behavior;
- stale-ingestion recovery with active-claim protection.

These tests cover the principal positive and negative business paths defined by the TARGET specification.

---

## 5. Remaining work is rollout qualification, not missing core implementation

The following items remain operational qualification tasks and SHOULD NOT be implemented by adding another ingestion architecture:

1. qualify `payload-max-inline-bytes` against representative FileService canonical documents and deployment request-body limits;
2. load-test queue depth, claim latency, lease renewals and database pool pressure at the intended pod count;
3. validate worker shutdown/restart behavior under in-flight ingestion and confirm reclaim latency is acceptable;
4. establish alert thresholds for queue age/depth, retry rate, lease loss, permanent failures and replay recovery;
5. keep `ARTIFACT_REF` disabled until an immutable artifact-loader port and upstream retention contract exist;
6. run a multi-pod failure-injection pass for worker crash, DB interruption and publication-before-finalization recovery before production enablement.

These are release/operations gates. They do not justify duplicating `KnowledgeIngestionService`, merging the two lease domains, or introducing a broker in v1.

---

## 6. Completion rule

`async-ingestion-worker-v1` is considered **implementation-complete** when this closeout change is merged.

Production enablement remains conditional on the rollout qualification in section 5 and the configured `akmai.ingestion.async-worker.enabled` safety gate.
