# Operations documentation

Classification: **CURRENT OPERATIONS INDEX**

This directory contains runbooks and production gates. Runtime defaults still come from `src/main/resources/application.yml`; executable deployment/build behavior comes from Docker/CI/runtime code.

## Documents

| Document | Purpose |
|---|---|
| `production-release-gates.md` | Minimum evidence required before calling a deployment release-qualified. |
| `non-local-deployment-security.md` | Fail-closed security/credential requirements outside explicit local development. |
| `disaster-recovery-rebuild.md` | Canonical-source rebuild / disaster-recovery procedure. |
| `dr-post-rebuild-verification.sql` | SQL verification after rebuild. |
| `postgres-hnsw-diagnostics.sql` | PostgreSQL/pgvector/HNSW diagnostics. |

## Operational invariants

- non-local deployments must not rely on permissive local security defaults;
- request and strategy timeouts must be enforced at real resource boundaries, not only at `CompletableFuture` level;
- bounded executors are part of the capacity model;
- retrieval failure should fail closed rather than silently generating from missing evidence;
- retention/reconciliation/repair must verify residual generation payload;
- re-embedding ownership must be database-visible and fenced across replicas;
- adaptive/self-optimizing features should be promoted through measured shadow/canary evidence rather than enabled globally by assumption;
- release evidence must correspond to the exact candidate SHA/tag.

## Adaptive graph operations

The graph has independent learning, maintenance, shadow and online-serving controls. See [`../architecture/adaptive-graph-runtime.md`](../architecture/adaptive-graph-runtime.md).

Recommended production rollout:

```text
learning only
  -> maintenance
  -> shadow expansion
  -> replay/statistical acceptance
  -> constrained online expansion
  -> competition/canary
```

Do not use HOT-edge count as the primary success metric. Prefer grounded-answer lift, evidence/citation recall, context cost and latency regression.

## Production qualification evidence

Retain, for the same candidate revision:

- quality benchmark result and baseline comparison;
- SMALL/MEDIUM application-level performance artifacts;
- semantic-grounding calibration evidence;
- final integrated qualification result;
- target-hardware saturation/SLO measurements where production claims are made;
- DR/rebuild rehearsal evidence where recovery commitments are part of the deployment contract.
