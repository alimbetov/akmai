# Production release gates

Classification: **CURRENT OPERATIONAL CONTRACT**  
Updated: 2026-10-08

A green unit-test/build run means the code is buildable. It does **not** by itself mean an AkmAI deployment is release-qualified.

## 1. Candidate identity

All release evidence must correspond to the same immutable candidate SHA/tag. Do not combine quality evidence from one revision with performance or grounding evidence from another.

Retain the candidate identity in every benchmark/qualification artifact.

## 2. Main-branch governance

Target governance for `main`:

- pull-request-based changes required;
- force pushes disabled;
- branch deletion restricted;
- review policy appropriate to repository ownership;
- required normal CI checks;
- required retrieval-quality checks;
- required retrieval-storage compatibility/performance gate for retrieval-sensitive changes.

At the 2026-10-08 readiness audit, `main` was still reported as unprotected. Branch governance is therefore a release-readiness blocker until enforced in repository settings/rulesets.

## 3. Integrated v1.1 qualification

The release workflow is:

```text
.github/workflows/rag-v1.1-release-qualification.yml
```

A candidate is release-qualified only when the integrated workflow completes the required sub-gates for the same SHA/tag and emits a successful final qualification result.

Required evidence classes:

1. **quality** — production RAG pipeline against an approved immutable baseline;
2. **performance** — SMALL and MEDIUM application-level profiles;
3. **semantic grounding** — live RU/KK/EN calibration/acceptance;
4. **final qualification** — integrated result declaring the candidate qualified.

Normal CI is a prerequisite, not a substitute for this workflow.

## 4. Approved quality baseline

The quality comparison baseline is expected at:

```text
benchmarks/rag-benchmark-v1/baselines/approved.json
```

The baseline must be explicitly reviewed/approved and retained. A candidate must never generate its own baseline and then claim success against it in the same qualification step.

The controlled synthetic corpus is suitable for deterministic regression and release engineering, but it is not sufficient evidence for broad real-world legal/medical/technical accuracy claims.

## 5. Target-hardware performance gate

Production SLOs must be derived from the intended deployment topology, including relevant CPU/GPU/RAM/storage/PostgreSQL/Ollama characteristics.

Retain at minimum:

- p50/p95/p99 end-to-end latency;
- retrieval/generation stage latency;
- throughput;
- executor/queue saturation;
- database pool/statement timeout behavior;
- model transport timeout/error behavior;
- memory/CPU/GPU pressure where applicable;
- mixed ingestion/retrieval behavior if the deployment permits concurrent write load.

GitHub Actions elapsed time is not production SLO evidence.

## 6. Retrieval/resource deadline gate

Before qualification, prove that timeout behavior releases/bounds underlying resources rather than merely completing a wrapper future exceptionally.

The runtime contract requires:

```text
strategy-timeout < request-timeout
```

Evidence should show worker recovery after repeated slow/blocked strategy calls and bounded JDBC/model operations.

## 7. Lifecycle/retention gate

For retention-sensitive deployments, verify:

- expired-but-still-ACTIVE content is synchronously retrieval-ineligible;
- final context revalidation rejects no-longer-eligible content;
- retirement removes/verifies all generation payload families;
- reconciliation/repair detects residual payload;
- re-embedding migration ownership/fencing remains correct across rolling restarts.

## 8. Production security gate

Any deployment that is not explicit local development must satisfy [`non-local-deployment-security.md`](non-local-deployment-security.md).

Minimum hardened requirements include:

```text
AKMAI_SECURITY_ENABLED=true
AKMAI_ALLOW_UNAUTH_LOCAL=false
regular API key present and sufficiently strong
admin API key present, sufficiently strong and different
database credentials explicitly injected
no predictable local DB defaults
```

Production safety must not depend solely on a literal `prod` Spring profile name.

## 9. Adaptive/self-optimizing feature gate

Adaptive behavior must be qualified independently from base RAG correctness.

### Adaptive Association Graph

Do not enable broad online graph influence solely because learning/maintenance run successfully.

Recommended evidence path:

```text
learning
 -> maintenance
 -> shadow expansion
 -> replay/statistical evaluation
 -> constrained online expansion
 -> competition/canary + CONTROL
```

Promote based on grounded/evidence/citation lift, contradiction/regression rate, context cost and latency — not edge count.

See [`../architecture/adaptive-graph-runtime.md`](../architecture/adaptive-graph-runtime.md).

### Self-optimizing retrieval policies

Policy candidates must pass offline/shadow/canary gates with a simultaneous CONTROL cohort and fail-closed rollback semantics.

## 10. Disaster-recovery gate

Where recovery commitments are part of the production contract, execute and retain a canonical-source rebuild rehearsal using:

- [`disaster-recovery-rebuild.md`](disaster-recovery-rebuild.md)
- [`dr-post-rebuild-verification.sql`](dr-post-rebuild-verification.sql)

Measured recovery evidence should be used to establish realistic RTO/RPO claims.

## 11. External-validity gate

Before making broad external claims about legal, medical or technical answer accuracy, maintain a separately versioned human-reviewed representative corpus with:

- real/representative user questions;
- answerability labels;
- source/evidence labels;
- adjudication records;
- domain/language coverage appropriate to the claim;
- explicit corpus versioning and change control.

Synthetic regression quality is not equivalent to external validity.

## 12. Historical audit issue reconciliation

Issues #36-#41 remain open tracker items even though most primary runtime remediations are already in `main`.

Before formal v1.1 release:

- attach current issue-specific acceptance evidence;
- fix code only where current acceptance tests fail;
- close/reclassify stale issues so tracker state matches implementation state.

See [`../audit/post-v1.1-readiness-2026-10-08.md`](../audit/post-v1.1-readiness-2026-10-08.md).

## 13. Release decision

A release candidate is **NO-GO** if any mandatory qualification artifact is missing or any required gate fails.

A candidate may be **CONDITIONAL GO** for controlled/pilot use with explicitly documented limits, but such a deployment must not be represented as fully release-qualified.

A candidate is **GO / release-qualified** only after the exact revision has passed the applicable quality, performance, grounding, security, lifecycle, governance and deployment-specific operational gates and the evidence is retained.
