# Audit documentation

Classification: **CURRENT INDEX / HISTORICAL EVIDENCE MAP**

Audit documents are snapshots tied to a particular repository state. They are not evergreen architecture specifications.

## Current readiness source

Use [`post-v1.1-readiness-2026-10-08.md`](post-v1.1-readiness-2026-10-08.md) for the current post-v1.1 readiness decision.

The audited implementation has advanced beyond several older defect descriptions. Always distinguish:

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
| `post-v1.1-readiness-2026-10-08.md` | CURRENT READINESS SNAPSHOT | Current implementation/readiness decision and release blockers. |
| `post-v1.1-readiness-remediation-spec.md` | CURRENT EXECUTION SPEC | Work breakdown for issue closure evidence and release qualification. |
| `post-phase-b-defect-ledger.md` | HISTORICAL AUDIT | Defect collection/evidence from the Phase-B audit line. |
| `post-phase-b-remediation-technical-spec.md` | HISTORICAL REMEDIATION SPEC | Detailed remediation plan used during earlier hardening. |

## Issues #36-#41

As of 2026-10-08, GitHub still reports issues #36-#41 open, but their tracker state is stale relative to current `main`.

Current interpretation from the readiness audit:

| Issue | Topic | Runtime state | Tracker action |
|---|---|---|---|
| #36 | synchronous TTL retrieval fence | remediated; stronger PostgreSQL acceptance evidence desired | attach evidence, then close |
| #37 | fail-closed non-local security | remediated/tested | attach evidence, then close |
| #38 | explicit non-local DB/security configuration | remediated/tested | attach evidence, then close |
| #39 | retrieval deadline/resource recovery | remediated; resource-boundary evidence should be reconfirmed | attach evidence, then close |
| #40 | streaming/unknown-length request byte limit | remediated/tested | attach evidence, then close |
| #41 | multi-replica re-embedding fencing | remediated/tested | attach evidence, then close |

Do not describe these six issues as six unimplemented production defects without re-checking current code.

## Remaining release blockers

The main remaining blockers are qualification/evidence and governance, not a missing core RAG architecture:

1. approved immutable quality baseline;
2. retained integrated live v1.1 qualification artifacts;
3. branch protection/required checks on `main`;
4. target-hardware performance/SLO sign-off;
5. separately versioned human-reviewed real-world corpus for broad external legal/medical/technical quality claims;
6. issue-specific closure evidence for #36-#41.

## Audit writing rule

Every new audit document must include:

- audit date;
- exact Git SHA/ref;
- whether findings describe code defects, evidence gaps, governance gaps or external-validity gaps;
- explicit distinction between implementation readiness and release qualification;
- links to current-state architecture docs where behavior is asserted.
