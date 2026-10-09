# Audit documentation

Classification: **CURRENT INDEX / HISTORICAL EVIDENCE MAP**

Audit documents are snapshots tied to a particular repository state. They are not evergreen architecture specifications unless explicitly marked as current-state contracts.

## Current readiness and remediation sources

Use, in precedence order for the current stabilization line:

1. [`documentation-truthfulness-2026-10-09.md`](documentation-truthfulness-2026-10-09.md) for the current documentation/code reconciliation baseline and the rules for `DRAFT` versus `VERIFIED`;
2. [`post-retrieval-project-audit-implementation-decisions.md`](post-retrieval-project-audit-implementation-decisions.md) for implementation discoveries and final low-level mechanism choices already validated against code;
3. [`post-retrieval-project-audit-remediation-spec.md`](post-retrieval-project-audit-remediation-spec.md) for normative stabilization scope, target behavior, invariants, acceptance criteria and Definition of Done;
4. [`post-retrieval-project-audit-code-blueprint.md`](post-retrieval-project-audit-code-blueprint.md) for the concrete class/method/SQL/test execution map;
5. [`post-v1.1-readiness-2026-10-08.md`](post-v1.1-readiness-2026-10-08.md) only as the earlier post-v1.1 readiness snapshot.

The TARGET remediation spec and code blueprint define intent and implementation plan. When implementation discovery proves a lower-level mechanism in those TARGET documents insufficient, the CURRENT implementation-decisions document supersedes that mechanism while preserving the parent invariant and acceptance criteria. Current service contracts must describe executable behavior, not the superseded implementation plan.

Always distinguish:

```text
GitHub issue state
!=
current implementation state
!=
documentation verification state
!=
release qualification state
```

## Documents

| Document | Status | Use |
|---|---|---|
| `documentation-truthfulness-2026-10-09.md` | CURRENT VERIFICATION BASELINE | Exact current-state reconciliation before the full regression pass; defines what may and may not be called `VERIFIED`. |
| `post-retrieval-project-audit-implementation-decisions.md` | CURRENT IMPLEMENTATION DECISIONS | Resolved implementation choices discovered while executing the post-retrieval audit; supersedes conflicting lower-level TARGET mechanics. |
| `post-retrieval-project-audit-remediation-spec.md` | TARGET EXECUTION SPEC | Normative post-retrieval stabilization scope: governance, reconciliation HA, process contracts, CI/docs cleanup and release qualification. |
| `post-retrieval-project-audit-code-blueprint.md` | TARGET CODE BLUEPRINT | Concrete implementation map to Java classes, transaction/SQL boundaries, tests, docs and execution order. |
| `post-v1.1-readiness-2026-10-08.md` | HISTORICAL READINESS SNAPSHOT | Readiness decision tied to its audited SHA; later tracker and implementation state may differ. |
| `post-v1.1-readiness-remediation-spec.md` | SUPERSEDED EXECUTION SPEC | Earlier work breakdown; consult only for retained detail not superseded by the post-retrieval line. |
| `post-phase-b-defect-ledger.md` | HISTORICAL AUDIT | Defect collection/evidence from the Phase-B audit line. |
| `post-phase-b-remediation-technical-spec.md` | HISTORICAL REMEDIATION SPEC | Detailed remediation plan used during earlier hardening. |

## Issues #36-#41

As of the post-retrieval audit on 2026-10-08, GitHub reported issues #36-#41 as **closed/completed**.

Their tracker closure does not by itself constitute release qualification. Historical documents that instructed the team to complete or close these items are retained as snapshots and must not be read as current tracker state.

| Issue | Topic | Current tracker interpretation | Runtime interpretation |
|---|---|---|---|
| #36 | synchronous TTL retrieval fence | historical closed/completed item | remediated; retain real PostgreSQL TTL evidence as lifecycle qualification evidence |
| #37 | fail-closed non-local security | historical closed/completed item | remediated/tested |
| #38 | explicit non-local DB/security configuration | historical closed/completed item | remediated/tested |
| #39 | retrieval deadline/resource recovery | historical closed/completed item | remediated; retain resource-boundary/recovery evidence |
| #40 | streaming/unknown-length request byte limit | historical closed/completed item | remediated/tested |
| #41 | multi-replica re-embedding fencing | historical closed/completed item | remediated/tested |

Do not reopen or describe these as current production defects without new failing evidence.

## Current remaining stabilization/release work

The current workstream is now deliberately sequential:

1. finish documentation truthfulness reconciliation against the current code head;
2. run the complete system regression/CI gate set on that exact head;
3. classify and fix every reproducible red result without weakening safety invariants;
4. rerun affected gates, then the complete gate set;
5. only after exact-head green evidence, promote eligible service contracts from `DRAFT` to `VERIFIED`;
6. separately complete repository governance, immutable benchmark baseline, target-hardware SLO/capacity sign-off and integrated release qualification where required.

Implemented configuration facts such as CI support for `quality/**` and `docs/**` must no longer be listed as open code gaps. Governance enforcement and release qualification remain separate evidence requirements.

## Audit writing rule

Every new audit document must include:

- audit date;
- exact Git SHA/ref when it makes current-state claims;
- whether findings describe code defects, evidence gaps, governance gaps or external-validity gaps;
- explicit distinction between implementation readiness and release qualification;
- links to current-state service/architecture docs where behavior is asserted.

A documentation-only tail commit must not be used to claim exact-head verification performed on an earlier SHA.