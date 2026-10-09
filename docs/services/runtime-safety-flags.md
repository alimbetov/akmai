# Runtime safety flags

Status: **IMPLEMENTED**

## Purpose

Runtime app parameters are operational safety gates, not a second configuration authority. Static application configuration remains the immutable outer boundary; PostgreSQL runtime parameters can further disable or enable behavior only inside that boundary.

The read contract is deliberately split between availability-oriented cached reads and authority-oriented database reads. Callers must choose the contract from the side effects they can cause, not from convenience.

## Read contracts

### Cached / fail-safe read

`AppParameterService.get(key)` and `AppParameterService.isEnabled(key)` may return, in order:

1. a bounded Caffeine value;
2. a current database value;
3. the last-known-good database value when the database is unavailable or the row is temporarily missing;
4. the defined static fallback when no last-known-good value exists.

This contract is allowed only when bounded staleness cannot authorize a durable mutation. It favors availability.

### Authoritative read

`AppParameterService.getAuthoritative(key)` and `AppParameterService.isEnabledAuthoritative(key)` always consult PostgreSQL.

Database unavailability is surfaced as `AppParameterUnavailableException`; missing registry state is an error. Authoritative reads never convert failure into last-known-good or static fallback state.

A successfully resolved authoritative value refreshes local cache/LKG state. Cache publication is version-monotonic so a delayed older read cannot overwrite a newer value already observed by the process.

## Consumer classification

| Runtime key | Static outer gate / fallback | Read mode | Main consumers | Rationale |
|---|---|---|---|---|
| `ADAPTIVE_GRAPH_LEARNING_ENABLED` | `akmai.adaptive-graph.learning-enabled` | **authoritative** | `AssociationLearningRecorder` | authorizes durable graph reinforcement writes |
| `ADAPTIVE_GRAPH_MAINTENANCE_ENABLED` | `akmai.adaptive-graph.maintenance-enabled` | **authoritative** | `AdaptiveGraphMaintenanceScheduler` | authorizes scoring/lifecycle/cleanup mutations |
| `ADAPTIVE_GRAPH_SHADOW_EXPANSION_ENABLED` | `akmai.adaptive-graph.shadow-expansion-enabled` | cached/fail-safe | `AdaptiveGraphShadowExpansion` | shadow observation/telemetry; no durable graph mutation authority |
| `ADAPTIVE_GRAPH_EXPANSION_ENABLED` | `akmai.adaptive-graph.expansion-enabled` | cached/fail-safe | `AdaptiveGraphOnlineExpansion` | query-time retrieval expansion; no durable graph mutation |
| `ADAPTIVE_GRAPH_COMPETITION_ENABLED` | `akmai.adaptive-graph.competition.enabled` | cached/fail-safe | `AdaptiveGraphCompetitiveAdmission` | query-time candidate admission only |
| `ADAPTIVE_GRAPH_DREAM_ENABLED` | `akmai.adaptive-graph.dream-enabled` | **authoritative** | `DreamRuntimeSwitches` / Dream coordinator | permits Dream lifecycle work and candidate-store mutation |
| `ADAPTIVE_GRAPH_DREAM_APPLY_ENABLED` | `akmai.adaptive-graph.dream.apply-enabled` | **authoritative** | `DreamRuntimeSwitches` / prior writer | authorizes graph prior apply/retire mutations |
| `SEMANTIC_MEMORY_INGESTION_LINKING_ENABLED` | semantic-memory ingestion static policy; service fallback is `false` | **authoritative** | `IngestionSemanticLinker` | authorizes post-publication semantic-edge seeding |

If a consumer evolves from read-only/telemetry behavior into a durable mutation path, it must be reclassified to authoritative reads in the same change.

## Administrative transition contract

`updateBoolean(...)` executes inside a transaction and locks the complete runtime-parameter registry before dependency validation. Therefore dependency checks are based on one authoritative locked snapshot, not Caffeine/LKG state.

Current dependency invariants are:

- online expansion cannot be enabled unless maintenance is enabled;
- competition cannot be enabled unless online expansion is enabled;
- maintenance cannot be disabled while online expansion is enabled;
- online expansion cannot be disabled while competition is enabled;
- Dream apply cannot be enabled unless Dream is enabled;
- Dream cannot be disabled while Dream apply is enabled;
- adaptive learning enablement requires a configured fingerprint secret of at least 32 characters.

The all-row lock intentionally serializes concurrent administrative transitions. This keeps dependency transitions atomic across pods and prevents two individually valid operations from committing an invalid combined state.

## Optimistic conflicts and cache safety

Updates use `row_version` optimistic concurrency. A version conflict means the caller's view is stale. The losing process therefore invalidates both its Caffeine entry and LKG entry for that key before returning `AppParameterConflictException`; the next cached/fail-safe read must reacquire state instead of continuing to serve a value known to be stale.

Successful update values are published to local cache only after transaction commit when transaction synchronization is active. A rollback must never publish the attempted value.

Local remembered database state is version-monotonic. If concurrent reads complete out of order, an older `row_version` cannot replace a newer version already observed by the process.

## Failure semantics

Positive cases:

- cached read hits local cache without JDBC;
- cache miss reads PostgreSQL and populates LKG/cache;
- transient DB failure on an explicitly cache-safe path uses LKG, then static fallback if no LKG exists;
- authoritative mutation gate reads current PostgreSQL state;
- successful admin transition validates dependencies under lock and publishes cache only after commit;
- concurrent dependency transitions serialize and preserve invariants.

Negative cases:

- authoritative DB failure -> explicit unavailable; no fallback authorization;
- corrupt persisted boolean -> error; corruption is not treated as availability failure;
- optimistic conflict -> no attempted-value cache publication and stale local state is evicted;
- rollback -> no cache publication;
- delayed older read completion -> cannot downgrade newer local `row_version`;
- stale cached `true` -> cannot authorize consumers classified as authoritative mutation paths.

## Operational rule

Runtime flags are kill switches, not feature-discovery state. For mutation-capable consumers, temporary PostgreSQL unavailability means **do not start new mutation work**. Availability degradation is preferable to executing durable work under a stale `true`.
