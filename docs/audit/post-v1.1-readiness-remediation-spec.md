# AkmAI post-v1.1 readiness remediation technical specification

Status: **implementation-ready**  
Prepared: **2026-10-08**  
Target branch: `docs/post-v1.1-readiness-audit`  
Audited baseline: `main@607e4641db9270c74be57bba7167d3ed3ffc85a4`

## 1. Objective

This specification defines the work required to move AkmAI from **code-capable / internally pilot-ready** to a **reproducibly release-qualified v1.1** state.

The scope is deliberately evidence-first. Issues #36-#41 remain open in GitHub, but their primary runtime remediations were already merged through PR #44 and subsequent contract alignment. The remaining task is **not to reimplement the same fixes blindly**. It is to:

1. prove every issue acceptance criterion with deterministic tests and retained evidence;
2. add only the missing implementation required by a failing acceptance test;
3. reconcile the issue tracker with the actual code state;
4. close the remaining release-qualification and repository-governance gaps.

No issue may be closed solely because a matching class or migration exists. Closure requires issue-specific acceptance evidence on the current `main` contract.

## 2. Current closure disposition

| Issue | Finding | Runtime remediation | Closure status | Required action |
|---|---|---|---|---|
| #36 / C01 | TTL eligibility on every retrieval/context path | Implemented | **PARTIAL EVIDENCE** | Add PostgreSQL-backed expired-row integration proof for all applicable retrieval lanes plus final context revalidation. |
| #37 / C02 | Missing runtime JWT secret must fail startup | Implemented and tested | **CLOSURE-READY** | Re-run current tests, attach exact test/commit evidence to issue, close as completed if green. |
| #38 / C03 | Security mode and DB credentials must fail closed together | Implemented and tested | **CLOSURE-READY** | Re-run current tests, verify non-local deployment documentation, attach evidence and close if green. |
| #39 / C04 | Retrieval deadlines must cancel underlying work | Implemented | **EVIDENCE RECONFIRMATION** | Retain deterministic cancellation/saturation evidence and resource-boundary DB/model timeout proof on current timeout contract. |
| #40 / C05 | Request size must be enforced while streaming | Implemented and tested | **CLOSURE-READY** | Add/retain exact-boundary and `max+1` streaming tests, attach evidence and close if green. |
| #41 / C06 | Persistent re-embedding leases need fencing tokens | Implemented and tested | **CLOSURE-READY** | Re-run stale-owner/fencing concurrency evidence and migration compatibility, attach evidence and close if green. |

## 3. Global Definition of Done

The remediation program is complete only when all of the following are true:

- issues #36-#41 are reconciled against current `main`, with acceptance evidence linked in each issue before closure;
- `mvn -B clean verify` succeeds on the candidate SHA;
- the normal GitHub Actions gates are green for that SHA;
- `benchmarks/rag-benchmark-v1/baselines/approved.json` exists and was created from an explicitly reviewed live run;
- `RAG v1.1 Release Qualification` completes successfully against live Ollama for the release SHA/tag;
- quality, SMALL/MEDIUM performance, grounding and final qualification artifacts are retained;
- `main` is protected by branch protection or an equivalent repository ruleset with required checks;
- production SLO/capacity acceptance is based on target/reference hardware measurements;
- external domain-quality claims, if made, are backed by a separately versioned human-reviewed corpus.

## 4. Workstream A — close issues #36-#41 with executable evidence

### A1. Issue #36 / C01 — TTL eligibility integration proof

**Current state:** central published-lifecycle eligibility is implemented and uses:

```sql
retention_status = 'ACTIVE'
AND (expires_at IS NULL OR expires_at > clock_timestamp())
```

The existing retrieval contract tests prove that lanes consult the eligibility abstraction and that final contexts are re-pruned. The remaining gap is direct database evidence for the exact acceptance scenario in issue #36.

**Required implementation:**

1. Add a PostgreSQL/Testcontainers integration fixture that publishes otherwise-valid chunks/documents with `retention_status='ACTIVE'` and `expires_at < clock_timestamp()`.
2. Exercise every applicable production retrieval lane against that fixture: VECTOR, LEXICAL, IDENTIFIER, REFERENCE and CONCEPT/graph-assisted retrieval when enabled by the test profile.
3. Assert that the expired chunk is absent even when its lexical/vector/business-identifier score would otherwise rank first.
4. Add a final-context race test: retrieve an eligible context, expire it before final pre-generation revalidation, and prove it is removed before generation.
5. Add positive controls for `expires_at IS NULL` and future expiry so the fence cannot regress into blanket denial.
6. If any lane fails, fix the production SQL/path at the earliest retrieval boundary and keep final revalidation as defense in depth.

**Acceptance:** no expired ACTIVE content can enter or survive the generation context; the proof uses the real PostgreSQL lifecycle state, not only a mocked eligibility predicate.

### A2. Issue #37 / C02 — JWT startup secret

**Current state:** `SecurityStartupValidator` fails non-local startup when the runtime JWT/API security secret is absent and leaves the documented local-development exception intact.

**Required closure work:**

1. Keep explicit tests for non-local missing secret, placeholder/default secret if supported by configuration, safe explicit secret and local exception.
2. Ensure failure messages never echo the secret value.
3. Ensure deployment documentation states which environment/property must be supplied outside local development.
4. Re-run the tests on the candidate SHA and link the test class plus successful CI run in issue #37.

**Acceptance:** non-local authenticated startup cannot proceed without an explicit runtime secret, while local development remains intentionally documented and testable.

### A3. Issue #38 / C03 — non-local security and datasource credential invariants

**Current state:** non-local startup rejects disabled security and predictable default datasource credentials.

**Required closure work:**

1. Retain tests for: security disabled, default username, default password, both defaults, and explicit safe configuration.
2. Verify the validator is driven by the effective AkmAI environment and cannot be bypassed accidentally by a Spring profile mismatch.
3. Keep local defaults clearly scoped to local development in documentation/examples.
4. Link the validator/test evidence and candidate CI run in issue #38.

**Acceptance:** every non-local configuration fails closed for unsafe security/credential combinations before serving requests.

### A4. Issue #39 / C04 — deadline cancellation and resource recovery

**Current state:** retrieval strategies run as owned cancellable tasks; request/strategy budgets are validated; unfinished work is cancelled; DB/model paths have bounded timeouts. The current configuration contract requires `strategy-timeout < request-timeout`.

**Required evidence hardening:**

1. Keep deterministic tests proving a timed-out strategy is interrupted before the request returns.
2. Keep request-deadline tests proving all unfinished strategy tasks are cancelled.
3. Run repeated timeout cycles and prove the retrieval executor returns to steady state rather than accumulating occupied workers/queue depth.
4. Add or retain PostgreSQL resource-boundary proof using a deliberately slow query (`pg_sleep` or equivalent) and assert query termination/connection reuse within the configured budget.
5. Add or retain a delayed model/HTTP response test and prove the transport call is bounded and the worker is released.
6. Emit/verify timeout telemetry that differentiates strategy timeout, request deadline and downstream resource timeout where the runtime already exposes those classes.

**Acceptance:** repeated deadline failures do not create runaway worker, JDBC-connection or HTTP-call occupancy, and the request cannot return while owned strategy work continues indefinitely.

### A5. Issue #40 / C05 — streaming request byte limit

**Current state:** `RequestBodySizeFilter` rejects oversized declared lengths early and wraps the actual body stream so unknown/chunked requests fail while reading.

**Required closure work:**

1. Keep JSON unknown-length/chunked test with exactly `maxRequestBytes + 1` bytes and assert HTTP 413.
2. Keep multipart streaming overflow coverage.
3. Add/retain a positive exact-boundary test for exactly `maxRequestBytes` bytes.
4. Assert the downstream filter chain is not invoked after overflow.
5. Avoid any test helper or production path that requires materializing the complete oversized body before the filter can decide.

**Acceptance:** exactly-at-limit requests can proceed; the first byte over the limit terminates consumption and returns 413 without unbounded buffering.

### A6. Issue #41 / C06 — re-embedding fencing-token concurrency proof

**Current state:** persistent re-embedding state carries owner, lease expiry and a monotonic fencing token; owner/token-scoped updates prevent stale replicas from mutating a migration after takeover.

**Required closure work:**

1. Prove fencing tokens increase monotonically across acquisition/takeover.
2. Start worker A, let its lease expire, acquire with worker B, then prove A cannot write progress, complete, fail or release B's lease using the stale token.
3. Prove B can continue normally with the new token.
4. Verify migration `V6__add_reembedding_lease_fencing.sql` upgrades an existing compatible schema without losing migration state.
5. Link concurrency integration evidence and candidate CI run in issue #41.

**Acceptance:** a stale replica can never mutate or release a migration owned by a newer fencing token.

## 5. Workstream B — release-qualification blockers

### B1. Establish the approved immutable quality baseline

The workflow currently expects:

`benchmarks/rag-benchmark-v1/baselines/approved.json`

Required process:

1. run the live benchmark in baseline-establishment mode against the intended model/profile;
2. review retrieval, abstention, grounding and failure-class evidence;
3. record model versions, embedding profile, corpus version, candidate SHA and benchmark configuration;
4. commit the reviewed baseline as an immutable release-engineering input;
5. require any future baseline change to explain why the quality contract changed.

### B2. Execute integrated release qualification

Run `.github/workflows/rag-v1.1-release-qualification.yml` for the exact candidate SHA/tag with live Ollama available. Retain:

- quality report and baseline comparison;
- SMALL performance report;
- MEDIUM performance report;
- RU/KK/EN grounding calibration evidence;
- final `rag-v1.1-qualification.json` with `qualified=true`.

A successful normal CI run is necessary but does not substitute for this live release gate.

### B3. Establish external-validity evidence

The controlled synthetic corpus is appropriate for regression and release engineering. It must not be used as the sole evidence for broad legal/medical/technical accuracy claims.

Create a separate versioned corpus with human-reviewed representative source documents, real query distributions, answerability labels, expected evidence and adjudication records. Keep it logically separate from the deterministic controlled benchmark.

### B4. Enforce repository governance

At the audited state, `main` is reported as unprotected and no repository ruleset is present.

Before formal release:

1. require pull-request-based changes to `main`;
2. disallow force-push/deletion for normal contributors;
3. require current mandatory checks, at minimum the normal CI, quality, quality-size and storage-compatibility gates;
4. require review appropriate to repository ownership;
5. treat live v1.1 qualification as a release/tag promotion gate even if it remains manually dispatched.

### B5. Production SLO/capacity sign-off

Run SMALL/MEDIUM and any deployment-specific load profiles on the intended topology. Record at minimum:

- p50/p95/p99 end-to-end latency;
- retrieval-only latency by lane;
- throughput and queue depth;
- executor saturation/rejections;
- PostgreSQL connection-pool saturation;
- model/embedding latency;
- CPU/RAM/GPU/VRAM/storage utilization;
- timeout and abstention rates.

Derive alert thresholds and capacity limits from those measurements rather than GitHub Actions timings.

## 6. Execution order

Use one closure sequence to minimize rework:

1. **#36 and #39 first** — they have the most meaningful remaining acceptance-evidence work.
2. Re-run and reconcile **#37, #38, #40 and #41**; fix only if an acceptance test fails.
3. Run `mvn -B clean verify` and all normal CI gates on the resulting candidate SHA.
4. Establish and review the immutable approved quality baseline.
5. Run the integrated live v1.1 release qualification and retain artifacts.
6. Enable `main` protection / required checks.
7. Complete target-hardware SLO/capacity sign-off.
8. Close #36-#41 with evidence comments and publish the final readiness decision.

## 7. Required issue-closure evidence

Before closing each issue, add a concise comment containing:

```text
Implemented by: <PR/commit>
Acceptance evidence: <test classes / workflow run>
Candidate SHA: <sha>
Result: PASS
Residual risk: <none or explicit follow-up>
```

If an issue's acceptance criterion is intentionally superseded by a stronger invariant, document that mapping explicitly instead of silently treating it as complete.

## 8. Exit criteria and target readiness state

Target state after this specification is completed:

- #36-#41: **closed as completed with current evidence**;
- normal candidate CI: **green**;
- approved benchmark baseline: **present and reviewed**;
- live v1.1 qualification: **green with retained artifacts**;
- `main`: **protected with required checks**;
- target-hardware performance/SLO review: **accepted**;
- external quality claims: **limited to the evidence actually available**.

At that point AkmAI can move from **CONDITIONAL GO** to **GO for the qualified deployment profile**. External domain-accuracy claims remain separately gated by the human-reviewed corpus.
