# Runtime boundary hardening v1

Status: CURRENT validation patch

This patch closes three runtime-boundary findings discovered after the semantic validation baseline was restored.

## Scope

1. Final retrieval eligibility uses one canonical predicate: `READY + ACTIVE + unexpired + exact published generation + ACL`.
2. Projection materialization and lifecycle eligibility remain inside the configured retrieval transaction deadline; the final lifecycle revalidation DB read is also bounded by the retrieval transaction timeout.
3. Async-ingestion lease renewals are dispatched independently so one slow renewal cannot serially block the heartbeat cycle for unrelated jobs. Each renewal executes inside a dedicated bounded heartbeat transaction.

## Positive cases

- A `READY`, `ACTIVE`, unexpired hit for the requested ACL and published generation survives final revalidation.
- Independent async jobs renew concurrently when the database responds normally.
- Existing source-version, publication and lease fencing semantics remain unchanged.

## Negative and failure cases

- A previously published generation is rejected by final revalidation while the document lifecycle is `INGESTING` for a newer generation.
- Expired, wrong-generation and wrong-ACL hits remain rejected.
- A blocked or failed heartbeat renewal marks only its owned claim as lost; it does not serially delay renewals for other active claims.
- Retrieval lifecycle DB work cannot silently escape the configured retrieval transaction timeout.

## Validation contract

The patch is mergeable only when the same immutable head SHA is green for:

- CI;
- Retrieval Quality Gate;
- Retrieval Storage Final Benchmark;
- Production Image Build.

No retrieval thresholds, semantic canonical IDs or publication/source-version fencing rules are relaxed by this patch.
