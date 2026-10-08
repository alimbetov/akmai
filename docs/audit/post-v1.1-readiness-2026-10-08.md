# AkmAI post-v1.1 readiness audit

Audit date: **2026-10-08**  
Audited ref: `main@607e4641db9270c74be57bba7167d3ed3ffc85a4`  
Primary defect remediation: PR #44 (`fix: remediate RAG v1.1 audit defects and harden executable gates`)  
Self-optimizing platform merge: PR #57  
CI contract alignment: PR #59

Remediation specification: [`post-v1.1-readiness-remediation-spec.md`](post-v1.1-readiness-remediation-spec.md)

## Executive conclusion

AkmAI v1.1 is **code-capable and suitable for controlled internal/pilot deployment, but it is not yet release-qualified for an externally claimed production release**.

The six historical audit findings tracked as issues #36-#41 are still open in GitHub, although their primary runtime remediations are already present in `main`. The remaining work is therefore split into two different categories:

1. **issue closure evidence** — prove each original acceptance criterion against the current code, then reconcile/close the stale tracker items;
2. **release qualification** — establish the approved benchmark baseline, run the integrated live qualification, retain performance/grounding evidence, enforce default-branch governance and sign off target-hardware SLOs.

Recommended state:

- **GO** for development, integration and controlled internal/pilot use;
- **CONDITIONAL GO** for a production candidate after deployment-specific qualification;
- **NO-GO** for a formal externally release-qualified v1.1 claim until the release gates in this audit are complete.

## Readiness scorecard

| Area | Status | Assessment |
|---|---|---|
| Core RAG architecture | READY | Hybrid retrieval, reranking, bounded context, provenance, citations and lifecycle separation are implemented. |
| Retrieval correctness | READY / EVIDENCE HARDENING | All production lanes are fenced by published lifecycle eligibility; issue #36 still deserves direct PostgreSQL expired-row acceptance proof before closure. |
| Security startup invariants | READY | Non-local deployments fail closed for unsafe security/secret/default-credential combinations. |
| Request boundary | READY | Request byte limit is enforced while consuming the stream, including unknown/chunked requests. |
| Re-embedding HA | READY | Persistent ownership, lease expiry and fencing tokens are implemented. |
| Deadline/resource control | READY / EVIDENCE RECONFIRMATION | Owned cancellable tasks and downstream bounds exist; issue #39 should be closed only after current resource-recovery evidence is attached. |
| Self-optimizing memory | READY WITH GUARDS | Query Memory, policy isolation, bounded feedback and anti-poisoning controls are implemented. |
| Adaptive routing | READY WITH GUARDS | Candidate -> SHADOW -> CANARY -> APPROVED/ROLLBACK lifecycle is evidence-gated. |
| Semantic grounding | IMPLEMENTED / CALIBRATION PENDING | Live RU/KK/EN calibration evidence is still required for release qualification. |
| CI/build | READY | Current main and the documentation audit PR have green normal CI/storage checks on their audited heads. |
| Quality benchmark | IMPLEMENTED / BASELINE BLOCKED | Controlled corpus and live runner exist; `benchmarks/rag-benchmark-v1/baselines/approved.json` is absent. |
| Performance qualification | IMPLEMENTED / EVIDENCE PENDING | SMALL/MEDIUM harness exists; target/reference-hardware evidence must be retained. |
| Release governance | NOT READY | `main` reports `protected=false` and no repository ruleset is present at audit time. |

## Issues #36-#41 — actual status

The tracker state and implementation state have diverged. These issues should not all be treated as six unimplemented defects.

| Issue | Finding | Code state | Closure state |
|---|---|---|---|
| #36 / C01 | TTL eligibility on every retrieval/context reuse path | **Remediated** | **Partial acceptance evidence** |
| #37 / C02 | Fail startup when authenticated security has no runtime secret | **Remediated + tested** | **Closure-ready after evidence comment** |
| #38 / C03 | Validate security mode and datasource credentials together | **Remediated + tested** | **Closure-ready after evidence comment** |
| #39 / C04 | Retrieval deadlines cancel underlying DB/model work | **Remediated** | **Reconfirm resource-boundary evidence** |
| #40 / C05 | Enforce request byte limit while reading body | **Remediated + tested** | **Closure-ready after boundary evidence** |
| #41 / C06 | Add fencing tokens to persistent re-embedding leases | **Remediated + tested** | **Closure-ready after concurrency evidence** |

### #36 / C01 — TTL retrieval fence

The production lifecycle predicate contains the required synchronous fence:

```sql
retention_status = 'ACTIVE'
AND (expires_at IS NULL OR expires_at > clock_timestamp())
```

The eligibility abstraction is wired across production retrieval and final-context revalidation. Existing tests prove the lanes consult the lifecycle fence and prove re-pruning behavior.

**Remaining closure gap:** the issue acceptance criteria explicitly call for integration tests that insert an otherwise eligible `ACTIVE` row with a past `expires_at` and prove that no retrieval lane returns it. The current contract tests primarily inject eligibility behavior, so a real PostgreSQL expired-row fixture should be added before #36 is closed. This is an evidence gap, not evidence that the runtime fence is missing.

### #37 / C02 — runtime security secret

`SecurityStartupValidator` is active for non-local environments and fails startup when the required runtime security secret is absent. The local-development exception is preserved intentionally and covered by tests. Failure paths do not need to reveal secret values.

**Disposition:** implementation is closure-ready; re-run the current tests on the candidate SHA and attach exact test/CI evidence to #37 before closing.

### #38 / C03 — security mode + datasource credentials

The same startup validator rejects unsafe non-local combinations, including disabled security and predictable default datasource credentials, while local defaults remain an explicit development convenience.

**Disposition:** implementation is closure-ready; attach validator/test evidence and confirm the deployment documentation names the non-local requirement before closing #38.

### #39 / C04 — cancellation and resource recovery

`ParallelRetrievalExecutor` owns cancellable strategy tasks, enforces the current `strategy-timeout < request-timeout` contract, cancels unfinished work and avoids starting dependent work without sufficient remaining budget. JDBC/model boundaries are also bounded by resource-level timeouts.

Regression coverage includes interruption/cancellation and repeated-timeout executor recovery.

**Remaining closure work:** retain deterministic evidence on the current contract showing that deliberately slow PostgreSQL/model calls are bounded and that repeated timeout cycles do not accumulate worker, queue or connection occupancy. Close #39 only after the resource-boundary evidence is linked from the issue.

### #40 / C05 — streaming request byte limit

`RequestBodySizeFilter` rejects an oversized declared `Content-Length` before the chain and wraps the actual request stream for unknown/chunked bodies. The stream fails on the first byte beyond the configured limit and returns HTTP 413, avoiding reliance on full-body materialization.

**Disposition:** closure-ready. Retain JSON and multipart overflow tests and an exact-boundary positive case; then attach evidence and close #40.

### #41 / C06 — re-embedding fencing

Persistent re-embedding state carries `owner_id`, `lease_until` and a monotonic `fencing_token`. State mutations are owner/token scoped, preventing a stale replica from mutating or releasing a migration after a newer worker acquires the lease.

**Disposition:** closure-ready after re-running the concurrency tests that prove monotonic fencing and rejection of stale progress/complete/fail/release operations.

## Evidence from the remediation line

PR #44 explicitly targeted C01-C06 and changed the relevant runtime and integration-test surfaces, including lifecycle eligibility, startup validation, retrieval cancellation, streaming body enforcement and re-embedding fencing. Its head CI completed successfully across the normal CI, storage compatibility and quality gates before merge.

PR #59 subsequently aligned deadline tests with the strict runtime configuration contract. Its audited head also completed the normal CI/quality/storage checks successfully.

The current conclusion is therefore **not** “six critical defects remain unimplemented.” The accurate conclusion is “six tracker items remain open; most are implementation-complete, while #36 and #39 deserve stronger issue-specific closure evidence.”

## Release-qualification blockers

### R1 — immutable approved quality baseline is absent

The release workflow expects:

`benchmarks/rag-benchmark-v1/baselines/approved.json`

That file is not present on the audited `main`. Until an explicitly reviewed live run is committed as the approved baseline, normal candidate comparison is not reproducible.

### R2 — successful integrated live qualification must be retained

`.github/workflows/rag-v1.1-release-qualification.yml` exists and combines quality, SMALL/MEDIUM performance and RU/KK/EN grounding gates. A normal green CI run is not a substitute for this live qualification.

For the release SHA/tag, retain the quality/performance/grounding artifacts and final `rag-v1.1-qualification.json` with `qualified=true`.

### R3 — default-branch governance is not enforced

At audit time the GitHub branch metadata reports `main` as `protected=false`, and the repository ruleset collection is empty.

Before a formal release, enforce at minimum:

- pull-request-based changes to `main`;
- required normal CI, quality/size and storage-compatibility checks;
- force-push/deletion restrictions appropriate to repository ownership;
- review requirements appropriate to the project;
- live v1.1 qualification as a release/tag promotion gate.

### R4 — external-validity corpus is missing

The existing `CONTROLLED_SYNTHETIC` benchmark is appropriate for deterministic regression and release engineering. It is not sufficient by itself to substantiate broad real-world legal/medical/technical accuracy claims.

Create a separately versioned human-reviewed representative corpus with real queries, answerability/evidence labels and adjudication records for external quality claims.

### R5 — target-hardware SLO/capacity sign-off is pending

The performance harness is implemented, but production p95/p99, throughput, saturation and capacity limits must be derived from the intended CPU/GPU/RAM/storage/Ollama/PostgreSQL topology. GitHub Actions timings are not production SLO evidence.

## Release decision

### Ready now

AkmAI is suitable for:

- local development and integration;
- controlled internal deployment;
- pilot enterprise knowledge assistants;
- Auth/FileService/BFF integration work;
- benchmarked adaptive-retrieval and semantic-grounding experiments.

### Not yet qualified

Do not yet claim:

- formal v1.1 production release qualification;
- externally validated legal/medical accuracy;
- universal multilingual quality on arbitrary corpora;
- production SLO compliance without target-hardware measurements;
- reproducible release qualification before the approved baseline and live artifacts exist.

## Required execution order

1. Complete #36 PostgreSQL TTL acceptance proof and #39 resource-boundary/recovery proof.
2. Re-run and reconcile #37, #38, #40 and #41; fix production code only if an acceptance test fails.
3. Close #36-#41 with issue-specific evidence comments.
4. Establish and review `benchmarks/rag-benchmark-v1/baselines/approved.json`.
5. Run the integrated v1.1 live qualification for the exact release candidate and retain artifacts.
6. Protect `main` with required checks / repository rules.
7. Run target-hardware performance qualification and sign off SLO/capacity limits.
8. Add a human-reviewed external-validity corpus before making broad domain-accuracy claims.

The detailed work breakdown and Definition of Done are in [`post-v1.1-readiness-remediation-spec.md`](post-v1.1-readiness-remediation-spec.md).

## Source-of-truth documents

- `README.md` — product overview and current release state;
- `docs/architecture/rag-self-optimizing-platform-v1.1-technical-spec.md` — v1.1 engineering contract;
- `docs/audit/post-v1.1-readiness-remediation-spec.md` — issue-closure and release-readiness remediation specification;
- `benchmarks/rag-benchmark-v1/README.md` — controlled benchmark contract and limits;
- `.github/workflows/rag-v1.1-release-qualification.yml` — integrated release gate;
- this document — post-v1.1 readiness decision as of 2026-10-08.
