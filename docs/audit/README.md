# Audit documentation

Classification: **CURRENT INDEX / HISTORICAL EVIDENCE MAP**

Audit documents are snapshots tied to a particular repository state. They are not evergreen architecture specifications.

## Current readiness and remediation sources

Use:

- [`post-v1.1-readiness-2026-10-08.md`](post-v1.1-readiness-2026-10-08.md) for the post-v1.1 readiness snapshot;
- [`post-retrieval-project-audit-remediation-spec.md`](post-retrieval-project-audit-remediation-spec.md) for the current stabilization/remediation execution plan after retrieval hardening.

Always distinguish:

```text
GitHub issue state
!=
current implementation state
!=
release qualification state
```

## Documents

| Document | Status | Use |
|---|---|---|
| `post-retrieval-project-audit-remediation-spec.md` | TARGET EXECUTION SPEC | Current post-retrieval stabilization plan: governance, reconciliation HA, process contracts, CI/docs cleanup and release qualification. |
| `post-v1.1-readiness-2026-10-08.md` | READINESS SNAPSHOT | Readiness decision tied to its audited SHA; some tracker-state statements were superseded later on 2026-10-08. |
| `post-v1.1-readiness-remediation-spec.md` | SUPERSEDED EXECUTION SPEC | Earlier work breakdown for issue closure evidence and release qualification; consult only for retained detail not superseded by the post-retrieval specification. |
| `post-phase-b-defect-ledger.md` | HISTORICAL AUDIT | Defect collection/evidence from the Phase-B audit line. |
| `post-phase-b-remediation-technical-spec.md` | HISTORICAL REMEDIATION SPEC | Detailed remediation plan used during earlier hardening. |

## Issues #36-#41

As of the post-retrieval audit on 2026-10-08, GitHub reports issues #36-#41 as **closed/completed**.

Their tracker closure does not by itself constitute full release qualification. The implementation and evidence state remains relevant when evaluating a release candidate.

| Issue | Topic | Current tracker state | Runtime interpretation |
|---|---|---|---|
| #36 | synchronous TTL retrieval fence | closed/completed | remediated; retain real PostgreSQL TTL evidence as part of lifecycle qualification |
| #37 | fail-closed non-local security | closed/completed | remediated/tested |
| #38 | explicit non-local DB/security configuration | closed/completed | remediated/tested |
| #39 | retrieval deadline/resource recovery | closed/completed | remediated; retain resource-boundary/recovery evidence |
| #40 | streaming/unknown-length request byte limit | closed/completed | remediated/tested |
| #41 | multi-replica re-embedding fencing | closed/completed | remediated/tested |

Do not reopen or describe these as unimplemented production defects without new failing evidence.

## Current remaining stabilization/release work

The current execution spec tracks these categories:

1. branch protection / required checks on `main`;
2. approved immutable benchmark baseline and retained live release qualification;
3. multi-pod generation reconciliation work claiming;
4. stale documentation and service-inventory status reconciliation;
5. CI trigger consistency for `quality/**` and `docs/**`;
6. positive/negative/concurrency process contracts for publication, lifecycle/retention, re-embedding, repair/reconciliation, graph/Dream jobs and runtime safety flags;
7. target-hardware SLO/capacity sign-off and external-validity evidence where broad production claims require it.

## Audit writing rule

Every new audit document must include:

- audit date;
- exact Git SHA/ref;
- whether findings describe code defects, evidence gaps, governance gaps or external-validity gaps;
- explicit distinction between implementation readiness and release qualification;
- links to current-state architecture docs where behavior is asserted.
