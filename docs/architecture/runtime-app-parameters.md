# Runtime App Parameters

## Purpose

AkmAI uses PostgreSQL-backed runtime parameters for feature controls that must be
changed without restarting the application. The first managed controls are the
Adaptive Graph activation flags.

PostgreSQL is authoritative. Caffeine is a bounded per-pod read cache.

## Managed keys

- `akmai.adaptive-graph.learning-enabled`
- `akmai.adaptive-graph.maintenance-enabled`
- `akmai.adaptive-graph.shadow-expansion-enabled`
- `akmai.adaptive-graph.expansion-enabled`
- `akmai.adaptive-graph.competition.enabled`

The registry is closed: unknown keys cannot be created through the API.

## Read path

```text
graph component
    |
    v
AppParameterService
    |
    +--> Caffeine hit
    |
    +--> cache miss
            |
            v
        PostgreSQL
            |
            +--> success -> cache + last-known-good
            |
            +--> failure -> last-known-good
                              |
                              +--> cold-start only:
                                   static application.yml fallback
```

Default Caffeine policy:

```yaml
akmai:
  app-parameters:
    cache-ttl: 2s
    cache-maximum-size: 128
```

The cache is intentionally local to each pod. A successful admin update is visible
on the pod that processed the write immediately after transaction commit. Other
pods converge after their bounded cache TTL. No correctness or authorization
decision may depend on instantaneous cross-pod propagation of these feature flags.

## Write path

Admin updates use optimistic versioning:

```json
{
  "value": true,
  "expectedVersion": 3
}
```

Every update transaction:

1. locks all registered parameter rows in deterministic key order;
2. validates cross-parameter invariants against that locked snapshot;
3. updates the target only when `row_version = expectedVersion`;
4. increments `row_version`;
5. records `updated_at` and `updated_by`;
6. refreshes the local cache only after transaction commit.

Locking the complete five-row registry is deliberate. It prevents two concurrent
administrators from passing dependency checks independently and committing an
invalid final state.

## Adaptive Graph transition invariants

Current transition constraints:

- learning may be enabled only when the HMAC fingerprint secret has at least
  32 characters;
- online expansion may be enabled only after maintenance is enabled;
- competition may be enabled only after online expansion is enabled;
- maintenance may not be disabled while online expansion is enabled;
- online expansion may not be disabled while competition is enabled.

Shadow expansion may run independently.

A safe activation sequence is:

```text
maintenance = true
learning = true
shadow-expansion = true
expansion = true
competition = true
```

For conservative rollout, stop before competition and run append-only graph
retrieval first.

## Admin API

Endpoints:

```text
GET /api/admin/app-parameters
GET /api/admin/app-parameters/{key}
PUT /api/admin/app-parameters/{key}
```

Production administration uses a dedicated credential:

```http
X-AKMAI-Admin-Key: <secret>
```

The normal `X-AKMAI-API-Key` does not authorize `/api/admin/**`.

Production requires both API credentials to be at least 32 characters and the
admin credential must differ from the normal API credential.

## Failure model

Runtime retrieval must not fail merely because the parameter table is briefly
unavailable.

Therefore:

- runtime reads use cached or last-known-good values;
- a cold pod with no prior successful DB read falls back to static configuration;
- admin reads and writes are authoritative and return service unavailable when
  PostgreSQL cannot be reached;
- malformed or missing runtime rows never create arbitrary parameter values.

Static configuration remains the cold-start safety fallback, not the normal
source of truth after the parameter table has been read.

## Persistence

Liquibase migration:

`db/changelog/greenfield/012-runtime-app-parameters.sql`

The initial migration seeds all five graph activation flags to `false`.

Thresholds, quotas, graph version, scoring coefficients and other graph tuning
parameters remain static configuration. They should move into the runtime
parameter system only when they have explicit validation, versioning and rollout
semantics comparable to these feature flags.
