# Adaptive Graph Dream — DREAM-1 through DREAM-4B engineering specification

Classification: **NORMATIVE / IMPLEMENTATION-READY**  
Target branch: `feature/adaptive-graph-dream`  
Baseline: `main@381b9c6cbf835daf2b301280171c049596e430a3`  
Scope: **DREAM-1, DREAM-2, DREAM-3, DREAM-4A, DREAM-4B only**  
Deployment: **4–6 AkmAI pods, one active Dream coordinator in v1**  
Date: 2026-10-08

This document is the code-level engineering contract for the first implementation milestone of Adaptive Graph Dream.

It refines `adaptive-graph-dream-implementation-spec-v1.md`. If this document is more specific for DREAM-1..DREAM-4B, this document wins for that scope. It does not authorize DREAM-5 graph mutation beyond lifecycle hardening required by DREAM-4A.

The milestone objective is deliberately narrow:

```text
configuration
+ policy identity
+ durable Dream state
+ cluster-safe ownership
+ lifecycle-safe ANN reads
+ bounded reciprocal shadow discovery
```

At the end of DREAM-4B, Dream must be able to run safely in shadow mode in a 4–6 pod deployment and persist candidate observations, while `knowledge_chunk_association` remains unchanged by Dream.

---

## 1. Technical review verdict

| Phase | Pre-implementation verdict | Blocking clarifications resolved here |
|---|---|---|
| DREAM-1 | PASS WITH SPEC HARDENING | exact property shape, flag precedence, fingerprint canonicalization |
| DREAM-2 | PASS WITH SPEC HARDENING | exact DDL, constraints, CAS/fencing repository contracts, canonical pair |
| DREAM-3 | PASS WITH SPEC HARDENING | lease SQL, heartbeat lifecycle, lost-authority semantics, checkpoint ordering |
| DREAM-4A | NEEDS REQUIRED CODE CHANGE | TTL missing in existing semantic ANN/apply lifecycle predicates |
| DREAM-4B | PASS AFTER 4A | source keyset contract, reciprocal cache, budgets, failure classification, metrics |

No runtime implementation exists yet on the reviewed branch. This is therefore a pre-implementation technical review, not a code-completion review.

---

# Part A — DREAM-1: configuration, runtime flags, policy fingerprint

## 2. Files to change

Required:

```text
src/main/java/kz/alimbetov/akmai/config/AdaptiveGraphProperties.java
src/main/java/kz/alimbetov/akmai/runtimeconfig/AppParameterKey.java
src/main/resources/application.yml
src/test/java/.../config/AdaptiveGraphPropertiesTest.java (or project-equivalent)
src/test/java/.../knowledge/graph/dream/DreamPolicyFingerprintTest.java
```

New package:

```text
src/main/java/kz/alimbetov/akmai/knowledge/graph/dream/
```

Minimum new class:

```text
DreamPolicyFingerprint.java
```

## 3. `AdaptiveGraphProperties` exact contract

Extend the existing record; do not create a second independent `@ConfigurationProperties` root for Dream.

Target form:

```java
@ConfigurationProperties("akmai.adaptive-graph")
public record AdaptiveGraphProperties(
        boolean learningEnabled,
        boolean maintenanceEnabled,
        boolean shadowExpansionEnabled,
        boolean expansionEnabled,
        boolean dreamEnabled,
        int graphVersion,
        Learning learning,
        ShadowExpansion shadowExpansion,
        Scoring scoring,
        Maintenance maintenance,
        BandQuotas quotas,
        Storage storage,
        Dream dream
) {
    @ConstructorBinding
    public AdaptiveGraphProperties {
        // existing validation first
        if (dream == null) {
            throw new IllegalArgumentException(
                    "adaptive-graph dream must not be null"
            );
        }
        if (!dreamEnabled && dream.applyEnabled()) {
            throw new IllegalArgumentException(
                    "adaptive-graph dream apply requires dream-enabled=true"
            );
        }
    }
}
```

Nested record:

```java
public record Dream(
        boolean applyEnabled,
        String cron,
        String zone,
        int topK,
        double candidateThreshold,
        double activationThreshold,
        double retentionThreshold,
        double forgettingThreshold,
        int maxNewEdgesPerChunk,
        int maxSourcesPerRun,
        int rescanSourcesPerRun,
        int batchSize,
        int negativeStreakForForgetting,
        boolean decayEnabled,
        int maxAnnQueriesPerRun,
        int maxReverseAnnQueriesPerRun,
        long maxDbRowsTouchedPerRun,
        Duration maxRunDuration,
        Duration queryTimeout,
        Duration transactionTimeout,
        Duration leaseDuration,
        Duration heartbeatInterval,
        int maxDbConcurrency,
        int maxForwardAnnConcurrency,
        int maxReverseAnnConcurrency,
        int reverseCacheMaximumSize,
        String semanticPolicyVersion
) {
    public Dream {
        requireRange("topK", topK, 2, 256);
        requireUnit("candidateThreshold", candidateThreshold);
        requireUnit("activationThreshold", activationThreshold);
        requireUnit("retentionThreshold", retentionThreshold);
        requireUnit("forgettingThreshold", forgettingThreshold);

        if (!(candidateThreshold <= forgettingThreshold
                && forgettingThreshold < retentionThreshold
                && retentionThreshold < activationThreshold)) {
            throw new IllegalArgumentException(
                    "Dream thresholds must satisfy candidate <= forgetting < retention < activation"
            );
        }

        requireRange("maxNewEdgesPerChunk", maxNewEdgesPerChunk, 1, 16);
        requirePositive("maxSourcesPerRun", maxSourcesPerRun);
        if (rescanSourcesPerRun < 0) {
            throw new IllegalArgumentException("rescanSourcesPerRun must be >= 0");
        }
        requireRange("batchSize", batchSize, 1, 1000);
        if (negativeStreakForForgetting < 2) {
            throw new IllegalArgumentException(
                    "negativeStreakForForgetting must be >= 2"
            );
        }

        requirePositive("maxAnnQueriesPerRun", maxAnnQueriesPerRun);
        requirePositive("maxReverseAnnQueriesPerRun", maxReverseAnnQueriesPerRun);
        if (maxDbRowsTouchedPerRun <= 0) {
            throw new IllegalArgumentException("maxDbRowsTouchedPerRun must be positive");
        }
        requirePositiveDuration("maxRunDuration", maxRunDuration);
        requirePositiveDuration("queryTimeout", queryTimeout);
        requirePositiveDuration("transactionTimeout", transactionTimeout);
        requirePositiveDuration("leaseDuration", leaseDuration);
        requirePositiveDuration("heartbeatInterval", heartbeatInterval);

        if (heartbeatInterval.compareTo(leaseDuration.dividedBy(3)) > 0) {
            throw new IllegalArgumentException(
                    "heartbeatInterval must be <= leaseDuration / 3"
            );
        }

        requirePositive("maxDbConcurrency", maxDbConcurrency);
        requirePositive("maxForwardAnnConcurrency", maxForwardAnnConcurrency);
        requirePositive("maxReverseAnnConcurrency", maxReverseAnnConcurrency);
        if (reverseCacheMaximumSize < topK) {
            throw new IllegalArgumentException(
                    "reverseCacheMaximumSize must be >= topK"
            );
        }

        semanticPolicyVersion = requireNonBlank(
                "semanticPolicyVersion",
                semanticPolicyVersion
        );

        // Parse validation must happen at startup.
        CronExpression.parse(requireNonBlank("cron", cron));
        ZoneId.of(requireNonBlank("zone", zone));
    }
}
```

If Spring `CronExpression` is already used elsewhere, reuse it. Do not add Quartz solely for validation.

## 4. `application.yml` contract

Add under existing `akmai.adaptive-graph`:

```yaml
    dream-enabled: ${AKMAI_ADAPTIVE_GRAPH_DREAM_ENABLED:false}
    dream:
      apply-enabled: ${AKMAI_ADAPTIVE_GRAPH_DREAM_APPLY_ENABLED:false}
      cron: "${AKMAI_ADAPTIVE_GRAPH_DREAM_CRON:0 0 3 * * *}"
      zone: ${AKMAI_ADAPTIVE_GRAPH_DREAM_ZONE:UTC}
      top-k: ${AKMAI_ADAPTIVE_GRAPH_DREAM_TOP_K:32}
      candidate-threshold: ${AKMAI_ADAPTIVE_GRAPH_DREAM_CANDIDATE_THRESHOLD:0.88}
      activation-threshold: ${AKMAI_ADAPTIVE_GRAPH_DREAM_ACTIVATION_THRESHOLD:0.94}
      retention-threshold: ${AKMAI_ADAPTIVE_GRAPH_DREAM_RETENTION_THRESHOLD:0.90}
      forgetting-threshold: ${AKMAI_ADAPTIVE_GRAPH_DREAM_FORGETTING_THRESHOLD:0.86}
      max-new-edges-per-chunk: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_NEW_EDGES_PER_CHUNK:3}
      max-sources-per-run: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_SOURCES_PER_RUN:10000}
      rescan-sources-per-run: ${AKMAI_ADAPTIVE_GRAPH_DREAM_RESCAN_SOURCES_PER_RUN:1000}
      batch-size: ${AKMAI_ADAPTIVE_GRAPH_DREAM_BATCH_SIZE:200}
      negative-streak-for-forgetting: ${AKMAI_ADAPTIVE_GRAPH_DREAM_NEGATIVE_STREAK:3}
      decay-enabled: ${AKMAI_ADAPTIVE_GRAPH_DREAM_DECAY_ENABLED:true}
      max-ann-queries-per-run: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_ANN_QUERIES:100000}
      max-reverse-ann-queries-per-run: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_REVERSE_ANN_QUERIES:90000}
      max-db-rows-touched-per-run: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_DB_ROWS:1000000}
      max-run-duration: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_RUN_DURATION:2h}
      query-timeout: ${AKMAI_ADAPTIVE_GRAPH_DREAM_QUERY_TIMEOUT:5s}
      transaction-timeout: ${AKMAI_ADAPTIVE_GRAPH_DREAM_TRANSACTION_TIMEOUT:30s}
      lease-duration: ${AKMAI_ADAPTIVE_GRAPH_DREAM_LEASE_DURATION:90s}
      heartbeat-interval: ${AKMAI_ADAPTIVE_GRAPH_DREAM_HEARTBEAT_INTERVAL:25s}
      max-db-concurrency: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_DB_CONCURRENCY:2}
      max-forward-ann-concurrency: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_FORWARD_ANN_CONCURRENCY:2}
      max-reverse-ann-concurrency: ${AKMAI_ADAPTIVE_GRAPH_DREAM_MAX_REVERSE_ANN_CONCURRENCY:2}
      reverse-cache-maximum-size: ${AKMAI_ADAPTIVE_GRAPH_DREAM_REVERSE_CACHE_SIZE:10000}
      semantic-policy-version: ${AKMAI_ADAPTIVE_GRAPH_DREAM_POLICY_VERSION:dream-v1}
```

All defaults must be valid while `dream-enabled=false` and `apply-enabled=false`.

## 5. Runtime flags and precedence

Add exactly two runtime keys:

```java
ADAPTIVE_GRAPH_DREAM_ENABLED(
        "akmai.adaptive-graph.dream-enabled",
        "Enable bounded Adaptive Graph Dream discovery and verification"
),
ADAPTIVE_GRAPH_DREAM_APPLY_ENABLED(
        "akmai.adaptive-graph.dream.apply-enabled",
        "Allow Dream to mutate semantic priors only"
)
```

Runtime decision helper SHOULD centralize fallback semantics:

```java
@Component
public final class DreamRuntimeSwitches {
    private final AdaptiveGraphProperties properties;
    private final AppParameterService appParameters;

    public boolean enabled() {
        return appParameters == null
                ? properties.dreamEnabled()
                : appParameters.isEnabled(
                        AppParameterKey.ADAPTIVE_GRAPH_DREAM_ENABLED
                );
    }

    public boolean applyEnabled() {
        if (!enabled()) {
            return false;
        }
        return appParameters == null
                ? properties.dream().applyEnabled()
                : appParameters.isEnabled(
                        AppParameterKey.ADAPTIVE_GRAPH_DREAM_APPLY_ENABLED
                );
    }
}
```

Implementation must not scatter flag logic through repositories.

Dynamic semantics:

- Dream disabled mid-run -> stop after safe batch boundary.
- Apply disabled mid-run -> graph mutation prohibited immediately for subsequent apply calls.
- Apply enabled mid-run -> MAY remain shadow until next batch boundary; no requirement for same-candidate retroactive application.

## 6. Deterministic semantic policy fingerprint

Class:

```java
@Component
public final class DreamPolicyFingerprint {
    private static final String ALGORITHM_VERSION = "dream-reciprocal-v1";
    private static final String CONFIDENCE_VERSION = "min-sim-rank-v1";

    public PolicyIdentity current(
            AdaptiveGraphProperties properties,
            EmbeddingProfile profile,
            boolean sameLanguageOnly
    ) {
        AdaptiveGraphProperties.Dream d = properties.dream();
        SortedMap<String, String> canonical = new TreeMap<>();
        canonical.put("algorithm", ALGORITHM_VERSION);
        canonical.put("confidence", CONFIDENCE_VERSION);
        canonical.put("embeddingProfile", profile.id());
        canonical.put("dimensions", Integer.toString(profile.dimensions()));
        canonical.put("distance", profile.distanceType().name());
        canonical.put("sameLanguageOnly", Boolean.toString(sameLanguageOnly));
        canonical.put("topK", Integer.toString(d.topK()));
        canonical.put("candidateThreshold", canonicalDouble(d.candidateThreshold()));
        canonical.put("activationThreshold", canonicalDouble(d.activationThreshold()));
        canonical.put("retentionThreshold", canonicalDouble(d.retentionThreshold()));
        canonical.put("forgettingThreshold", canonicalDouble(d.forgettingThreshold()));

        String payload = canonical.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));

        return new PolicyIdentity(
                d.semanticPolicyVersion(),
                sha256Hex(payload)
        );
    }
}
```

Requirements:

1. UTF-8 only.
2. Lowercase 64-char hex SHA-256.
3. Stable key ordering.
4. Locale-independent double formatting. `Double.toString` is acceptable; locale-dependent `String.format` is not.
5. Secrets, cron, lease durations and resource budgets MUST NOT be in the semantic fingerprint because they do not alter semantic meaning.
6. Thresholds and top-K MUST be included because they alter candidate semantics.

Record:

```java
public record PolicyIdentity(
        String version,
        String fingerprint
) {
    public PolicyIdentity {
        if (version == null || version.isBlank()) throw ...;
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) throw ...;
    }
}
```

## 7. DREAM-1 tests

Mandatory unit tests:

```text
defaults_are_valid_and_dream_is_disabled
apply_cannot_be_true_when_dream_disabled
threshold_order_is_validated
heartbeat_must_fit_inside_one_third_of_lease
cron_must_parse
zone_must_parse
fingerprint_is_stable_for_identical_policy
fingerprint_changes_when_embedding_profile_changes
fingerprint_changes_when_top_k_changes
fingerprint_changes_when_any_semantic_threshold_changes
fingerprint_does_not_change_when_lease_or_budget_changes
runtime_apply_is_never_true_when_runtime_dream_is_false
```

DREAM-1 is DONE only when configuration binds under normal tests and no scheduler/work is started while Dream is disabled.

---

# Part B — DREAM-2: migration 026 and repositories

## 8. Migration file and changelog

Target on the reviewed baseline:

```text
src/main/resources/db/changelog/greenfield/026-adaptive-graph-dream.sql
```

Add after `025-query-memory-policy-isolation.sql` in:

```text
src/main/resources/db/changelog/greenfield/db.changelog-greenfield.yaml
```

If `main` acquires migration 026 before implementation starts, rebase first and use the next free number. Never reuse an occupied migration number.

## 9. Candidate table — executable DDL contract

Representative DDL:

```sql
CREATE TABLE knowledge_chunk_dream_candidate (
    access_level BIGINT NOT NULL,
    node_a_document_id VARCHAR(100) NOT NULL,
    node_a_generation BIGINT NOT NULL,
    node_a_chunk_id VARCHAR(100) NOT NULL,
    node_b_document_id VARCHAR(100) NOT NULL,
    node_b_generation BIGINT NOT NULL,
    node_b_chunk_id VARCHAR(100) NOT NULL,
    graph_version INTEGER NOT NULL,
    semantic_policy_version VARCHAR(100) NOT NULL,
    semantic_policy_fingerprint VARCHAR(64) NOT NULL,

    state VARCHAR(16) NOT NULL,
    forward_similarity DOUBLE PRECISION,
    reverse_similarity DOUBLE PRECISION,
    forward_rank INTEGER,
    reverse_rank INTEGER,
    mutual_knn BOOLEAN NOT NULL DEFAULT FALSE,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0,
    positive_streak INTEGER NOT NULL DEFAULT 0,
    negative_streak INTEGER NOT NULL DEFAULT 0,

    embedding_profile_id VARCHAR(200) NOT NULL,
    discovery_run_id UUID,
    last_verified_run_id UUID,
    discovery_reason VARCHAR(64),
    activation_reason VARCHAR(64),
    retirement_reason VARCHAR(64),

    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    last_verified_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    PRIMARY KEY (
        access_level,
        node_a_document_id,
        node_a_generation,
        node_a_chunk_id,
        node_b_document_id,
        node_b_generation,
        node_b_chunk_id,
        graph_version,
        semantic_policy_fingerprint
    ),

    CHECK (access_level > 0),
    CHECK (node_a_generation > 0),
    CHECK (node_b_generation > 0),
    CHECK (graph_version > 0),
    CHECK (state IN ('CANDIDATE','ACTIVE','STALE','REJECTED')),
    CHECK (confidence >= 0 AND confidence <= 1),
    CHECK (forward_similarity IS NULL OR (forward_similarity >= 0 AND forward_similarity <= 1)),
    CHECK (reverse_similarity IS NULL OR (reverse_similarity >= 0 AND reverse_similarity <= 1)),
    CHECK (forward_rank IS NULL OR forward_rank > 0),
    CHECK (reverse_rank IS NULL OR reverse_rank > 0),
    CHECK (positive_streak >= 0),
    CHECK (negative_streak >= 0),
    CHECK (semantic_policy_fingerprint ~ '^[0-9a-f]{64}$'),
    CHECK (
        ROW(node_a_document_id, node_a_generation, node_a_chunk_id)
        < ROW(node_b_document_id, node_b_generation, node_b_chunk_id)
    )
);
```

Important: because `access_level` is one column for the pair, canonical ordering compares document/generation/chunk after same-ACL validation. Java must canonicalize with `ChunkGraphNode.compareTo`; DB constraint is defense-in-depth and may use explicit lexicographic expression if PostgreSQL row comparison typing becomes awkward.

Indexes:

```sql
CREATE INDEX dream_candidate_verify_idx
ON knowledge_chunk_dream_candidate (
    graph_version,
    semantic_policy_fingerprint,
    state,
    last_verified_at
);

CREATE INDEX dream_candidate_acl_state_idx
ON knowledge_chunk_dream_candidate (
    access_level,
    graph_version,
    state,
    updated_at
);
```

Do not partition v1 candidate table unless measured size requires it.

## 10. Run table

```sql
CREATE TABLE adaptive_graph_dream_run (
    run_id UUID PRIMARY KEY,
    graph_version INTEGER NOT NULL,
    semantic_policy_version VARCHAR(100) NOT NULL,
    semantic_policy_fingerprint VARCHAR(64) NOT NULL,
    owner_id VARCHAR(200) NOT NULL,
    fencing_token BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    sources_fast BIGINT NOT NULL DEFAULT 0,
    sources_rescan BIGINT NOT NULL DEFAULT 0,
    forward_ann_queries BIGINT NOT NULL DEFAULT 0,
    reverse_ann_queries BIGINT NOT NULL DEFAULT 0,
    candidates_seen BIGINT NOT NULL DEFAULT 0,
    candidates_mutual BIGINT NOT NULL DEFAULT 0,
    candidates_activated BIGINT NOT NULL DEFAULT 0,
    candidates_applied BIGINT NOT NULL DEFAULT 0,
    candidates_retired BIGINT NOT NULL DEFAULT 0,
    db_rows_touched BIGINT NOT NULL DEFAULT 0,
    stop_reason VARCHAR(64),
    error_class VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (graph_version > 0),
    CHECK (fencing_token > 0),
    CHECK (status IN (
      'RUNNING','SUCCEEDED','PARTIAL_BUDGET','FAILED',
      'LOST_OWNERSHIP','CANCELLED'
    )),
    CHECK (semantic_policy_fingerprint ~ '^[0-9a-f]{64}$')
);
```

Run rows are audit state, not lease authority.

## 11. Lease table

```sql
CREATE TABLE adaptive_graph_dream_lease (
    graph_version INTEGER NOT NULL,
    semantic_policy_fingerprint VARCHAR(64) NOT NULL,
    owner_id VARCHAR(200),
    lease_until TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (graph_version, semantic_policy_fingerprint),
    CHECK (graph_version > 0),
    CHECK (fencing_token >= 0),
    CHECK (semantic_policy_fingerprint ~ '^[0-9a-f]{64}$')
);
```

A row may be lazily inserted on first acquisition.

## 12. Checkpoint table

Avoid an opaque `rescan_cursor` JSON if a deterministic typed cursor is practical. For v1 use explicit columns or a strictly versioned serialized value.

Representative:

```sql
CREATE TABLE adaptive_graph_dream_checkpoint (
    graph_version INTEGER NOT NULL,
    semantic_policy_fingerprint VARCHAR(64) NOT NULL,

    fast_watermark_updated_at TIMESTAMPTZ,
    fast_watermark_access_level BIGINT,
    fast_watermark_document_id VARCHAR(100),
    fast_watermark_generation BIGINT,
    fast_watermark_chunk_id VARCHAR(100),

    rescan_after_access_level BIGINT,
    rescan_after_document_id VARCHAR(100),
    rescan_after_generation BIGINT,
    rescan_after_chunk_id VARCHAR(100),

    last_successful_run_id UUID,
    last_completed_at TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    PRIMARY KEY (graph_version, semantic_policy_fingerprint),
    CHECK (graph_version > 0),
    CHECK (fencing_token >= 0),
    CHECK (semantic_policy_fingerprint ~ '^[0-9a-f]{64}$')
);
```

Partial cursor groups must be all-null or all-non-null. Add CHECK constraints where practical.

## 13. Canonical pair type

Create one shared Dream type; do not repeatedly normalize manually.

```java
public record DreamPair(
        ChunkGraphNode first,
        ChunkGraphNode second
) {
    public DreamPair {
        Objects.requireNonNull(first);
        Objects.requireNonNull(second);
        if (first.equals(second)) {
            throw new IllegalArgumentException("Dream self-pair is forbidden");
        }
        if (first.accessLevel() != second.accessLevel()) {
            throw new IllegalArgumentException("Dream cross-ACL pair is forbidden");
        }
        if (first.compareTo(second) >= 0) {
            throw new IllegalArgumentException("DreamPair must be canonical");
        }
    }

    public static DreamPair of(ChunkGraphNode left, ChunkGraphNode right) {
        if (left.compareTo(right) < 0) {
            return new DreamPair(left, right);
        }
        return new DreamPair(right, left);
    }
}
```

## 14. Candidate repository contract

```java
public interface DreamCandidateRepository {
    CandidateObservation upsertPositive(
            PolicyIdentity policy,
            int graphVersion,
            DreamPair pair,
            SemanticEvidence evidence,
            UUID runId,
            Instant observedAt
    );

    Optional<DreamCandidate> find(
            PolicyIdentity policy,
            int graphVersion,
            DreamPair pair
    );

    List<DreamCandidate> findVerificationBatch(
            PolicyIdentity policy,
            int graphVersion,
            CandidateCursor after,
            int limit
    );
}
```

DREAM-4B only requires positive discovery observation. Negative verification/update APIs may be implemented in DREAM-6 unless needed earlier for test scaffolding.

Positive upsert must be atomic. Representative logic:

```sql
INSERT ...
VALUES (..., 'CANDIDATE', ..., 1, 0, ...)
ON CONFLICT (...) DO UPDATE SET
    forward_similarity = EXCLUDED.forward_similarity,
    reverse_similarity = EXCLUDED.reverse_similarity,
    forward_rank = EXCLUDED.forward_rank,
    reverse_rank = EXCLUDED.reverse_rank,
    mutual_knn = TRUE,
    confidence = EXCLUDED.confidence,
    positive_streak = knowledge_chunk_dream_candidate.positive_streak + 1,
    negative_streak = 0,
    last_seen_at = EXCLUDED.last_seen_at,
    last_verified_at = EXCLUDED.last_verified_at,
    last_verified_run_id = EXCLUDED.last_verified_run_id,
    updated_at = clock_timestamp();
```

State transition to `ACTIVE` may be computed in SQL or service code, but must be deterministic and concurrency-safe.

## 15. Repository integration tests

Mandatory PostgreSQL/Testcontainers tests:

```text
candidate_canonical_pair_is_unique_under_concurrent_upsert
candidate_policy_fingerprint_isolates_rows
candidate_cross_acl_is_rejected_before_sql
candidate_self_pair_is_rejected
candidate_positive_upsert_increments_streak_once_per_committed_observation
candidate_rollback_does_not_increment_streak
lease_fencing_token_is_monotonic
checkpoint_rejects_stale_fencing_token
run_status_constraint_rejects_unknown_state
```

---

# Part C — DREAM-3: lease, fencing, heartbeat, resumability

## 16. Design rule

Reuse the behavioural model of `ReembeddingLeaseManager`:

- atomic acquisition/takeover;
- monotonically incrementing fencing token;
- renew only while owner/token match and lease is still live;
- expired lease cannot be resurrected by stale owner;
- every authoritative mutation validates owner/token/live lease.

Do not reuse re-embedding tables or migration IDs. Dream ownership is keyed by:

```text
(graph_version, semantic_policy_fingerprint)
```

## 17. Authority type

```java
public record DreamAuthority(
        int graphVersion,
        String policyFingerprint,
        String ownerId,
        long fencingToken
) {
    public DreamAuthority {
        if (graphVersion <= 0) throw ...;
        if (policyFingerprint == null
                || !policyFingerprint.matches("[0-9a-f]{64}")) throw ...;
        if (ownerId == null || ownerId.isBlank()) throw ...;
        if (fencingToken <= 0) throw ...;
    }
}
```

## 18. Owner identity

Provide process-incarnation identity, not just pod name:

```java
@Component
public final class DreamOwnerIdentity {
    private final String value;

    public DreamOwnerIdentity(
            @Value("${HOSTNAME:unknown-pod}") String podName
    ) {
        this.value = podName + ":" + UUID.randomUUID();
    }

    public String value() {
        return value;
    }
}
```

No persistence across restart.

## 19. Lease acquisition SQL

Safe acquisition/takeover pattern:

```sql
INSERT INTO adaptive_graph_dream_lease (
    graph_version,
    semantic_policy_fingerprint,
    owner_id,
    lease_until,
    fencing_token,
    updated_at
)
VALUES (
    ?, ?, ?,
    clock_timestamp() + (? * interval '1 millisecond'),
    1,
    clock_timestamp()
)
ON CONFLICT (graph_version, semantic_policy_fingerprint)
DO UPDATE SET
    owner_id = EXCLUDED.owner_id,
    lease_until = EXCLUDED.lease_until,
    fencing_token = adaptive_graph_dream_lease.fencing_token + 1,
    updated_at = clock_timestamp()
WHERE adaptive_graph_dream_lease.lease_until IS NULL
   OR adaptive_graph_dream_lease.lease_until <= clock_timestamp()
RETURNING fencing_token;
```

If zero rows returned, another live owner exists.

Important: do not allow a different owner to steal a live lease.

The current owner should renew, not reacquire. Reacquisition is for absent/expired ownership only.

## 20. Lease manager API

```java
@Component
public final class DreamLeaseManager {
    public Optional<DreamAuthority> tryAcquire(
            int graphVersion,
            String policyFingerprint
    );

    public void renew(DreamAuthority authority);

    public boolean isOwned(DreamAuthority authority);

    public boolean release(DreamAuthority authority);
}
```

Renew SQL:

```sql
UPDATE adaptive_graph_dream_lease
SET lease_until = clock_timestamp() + (? * interval '1 millisecond'),
    updated_at = clock_timestamp()
WHERE graph_version = ?
  AND semantic_policy_fingerprint = ?
  AND owner_id = ?
  AND fencing_token = ?
  AND lease_until > clock_timestamp();
```

`updateCount != 1` => throw `DreamLostAuthorityException`.

Release must be fenced:

```sql
UPDATE adaptive_graph_dream_lease
SET owner_id = NULL,
    lease_until = NULL,
    updated_at = clock_timestamp()
WHERE graph_version = ?
  AND semantic_policy_fingerprint = ?
  AND owner_id = ?
  AND fencing_token = ?;
```

Do not reset fencing token.

## 21. Heartbeat

Do not use a global `@Scheduled` heartbeat that renews every possible policy row blindly.

Prefer a run-scoped heartbeat object:

```java
public final class DreamLeaseHeartbeat implements AutoCloseable {
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean lost = new AtomicBoolean(false);
    private ScheduledFuture<?> future;

    public void start(DreamAuthority authority) {
        future = scheduler.scheduleWithFixedDelay(
                () -> {
                    try {
                        leases.renew(authority);
                    } catch (DreamLostAuthorityException ex) {
                        lost.set(true);
                        cancellation.cancel(CancelReason.LOST_OWNERSHIP);
                    }
                },
                heartbeatInterval.toMillis(),
                heartbeatInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public boolean authorityLost() {
        return lost.get();
    }

    @Override
    public void close() {
        if (future != null) future.cancel(false);
    }
}
```

The scheduler thread must not share the bounded Dream ANN worker pool. Otherwise ANN saturation can starve heartbeat and cause false takeover.

## 22. Scheduler

```java
@Component
public final class AdaptiveGraphDreamScheduler {
    @Scheduled(
        cron = "${akmai.adaptive-graph.dream.cron:0 0 3 * * *}",
        zone = "${akmai.adaptive-graph.dream.zone:UTC}"
    )
    public void run() {
        if (!switches.enabled()) {
            return;
        }

        PolicyIdentity policy = policyFingerprint.current();
        leases.tryAcquire(graphVersion, policy.fingerprint())
                .ifPresent(authority -> coordinator.run(authority, policy));
    }
}
```

Local overlap guard is also required because two cron invocations on the same pod must not launch two concurrent coordinators before DB acquisition state settles.

Use `AtomicBoolean localRunInProgress` or a single-thread scheduler; DB lease remains the authoritative cluster guard.

## 23. Run lifecycle

Coordinator outline:

```java
public DreamRunReport run(
        DreamAuthority authority,
        PolicyIdentity policy
) {
    UUID runId = UUID.randomUUID();
    cancellation.reset();
    runRepository.start(runId, authority, policy, clock.instant());

    try (DreamLeaseHeartbeat heartbeat = heartbeatFactory.open(
            authority,
            cancellation
    )) {
        heartbeat.start();

        DreamCheckpoint checkpoint = checkpoints.loadOrCreate(
                authority,
                policy
        );

        processFastLane(runId, authority, policy, checkpoint);
        processRescanLane(runId, authority, policy, checkpoint);

        requireAuthority(authority);
        runRepository.finishSuccess(runId, authority, ...);
        return report;
    } catch (DreamLostAuthorityException ex) {
        runRepository.finishBestEffortLostOwnership(runId, ...);
        return DreamRunReport.lostOwnership(...);
    } catch (DreamBudgetExhausted ex) {
        runRepository.finishPartialBudget(runId, authority, ...);
        return DreamRunReport.partial(...);
    } catch (Exception ex) {
        runRepository.finishFailure(runId, authority, ex, ...);
        throw ex;
    } finally {
        leases.releaseBestEffort(authority);
        cancellation.reset();
    }
}
```

`finishBestEffortLostOwnership` must not require stale authority to mutate authoritative checkpoint/lease state. Run audit row may be finalized best-effort because it is not ownership authority; if strict fencing is desired there too, leaving RUNNING for reconciliation is acceptable.

## 24. Checkpoint CAS contract

Checkpoint mutation must validate the **live lease table**, not merely compare the token stored in the checkpoint row.

Recommended SQL pattern inside checkpoint update transaction:

```sql
UPDATE adaptive_graph_dream_checkpoint c
SET fast_watermark_updated_at = ?,
    fast_watermark_access_level = ?,
    fast_watermark_document_id = ?,
    fast_watermark_generation = ?,
    fast_watermark_chunk_id = ?,
    last_successful_run_id = ?,
    last_completed_at = ?,
    fencing_token = ?,
    updated_at = clock_timestamp()
WHERE c.graph_version = ?
  AND c.semantic_policy_fingerprint = ?
  AND EXISTS (
      SELECT 1
      FROM adaptive_graph_dream_lease l
      WHERE l.graph_version = c.graph_version
        AND l.semantic_policy_fingerprint = c.semantic_policy_fingerprint
        AND l.owner_id = ?
        AND l.fencing_token = ?
        AND l.lease_until > clock_timestamp()
  );
```

`updateCount != 1` => `DreamLostAuthorityException`.

For first checkpoint creation, use `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE EXISTS(live authority)` or transactionally lock/create then CAS.

## 25. Resume semantics

Fast lane checkpoint identifies **last durably completed source key**, not last read source.

Ordering:

```text
source batch read
    ↓
ANN + candidate computations
    ↓
candidate rows durably committed
    ↓
(optional in later phases graph writes committed)
    ↓
checkpoint advanced to last completed source
```

Crash before checkpoint advance => replay allowed.

Therefore all candidate writes must be idempotent at canonical pair/policy identity. A replay may increase `positive_streak` twice if the same run/source is blindly reapplied. To avoid false semantic reinforcement, DREAM-2/4B MUST make positive observation idempotent per run observation.

Required resolution: add an observation idempotency key or ensure upsert increments streak only when `last_verified_run_id IS DISTINCT FROM :runId`.

Preferred SQL:

```sql
positive_streak = CASE
    WHEN knowledge_chunk_dream_candidate.last_verified_run_id IS DISTINCT FROM EXCLUDED.last_verified_run_id
    THEN knowledge_chunk_dream_candidate.positive_streak + 1
    ELSE knowledge_chunk_dream_candidate.positive_streak
END,
```

This is a release-blocking resumability invariant.

## 26. DREAM-3 tests

Mandatory:

```text
six_contenders_exactly_one_acquires_live_lease
expired_owner_can_be_taken_over_and_token_increments
live_owner_cannot_be_stolen
expired_owner_cannot_renew
heartbeat_keeps_long_running_owner_alive
heartbeat_runs_on_independent_executor
stale_owner_checkpoint_update_is_rejected
stale_owner_release_does_not_clear_new_owner
owner_kill_then_takeover_replays_only_uncheckpointed_batch
replayed_same_run_observation_does_not_double_increment_streak
rolling_restart_has_no_source_gap
```

---

# Part D — DREAM-4A: TTL hardening

## 27. Existing defect to fix

Current `SemanticNeighborSearchRepository.search(...)` checks:

```text
published generation
READY
ACTIVE
```

but must also check TTL.

Add:

```sql
AND (
    l.expires_at IS NULL
    OR l.expires_at > clock_timestamp()
)
```

The final predicate must be present in every ANN path Dream reuses.

## 28. `SemanticNeighborSearchRepository` review contract

Existing SQL must become equivalent to:

```sql
SELECT ...
FROM <active_vector_table> v
JOIN knowledge_document_lifecycle l
  ON l.document_id = v.document_id
 AND l.access_level = v.access_level
 AND l.published_generation = v.generation
WHERE v.access_level = ?
  AND l.lifecycle_status = 'READY'
  AND l.retention_status = 'ACTIVE'
  AND (
      l.expires_at IS NULL
      OR l.expires_at > clock_timestamp()
  )
  ...
ORDER BY v.embedding <=> ?
LIMIT ?
```

Do not implement Dream-only TTL filtering after the ANN query. Expired rows must be excluded before top-K ranking; otherwise an expired neighbour can occupy one of the K slots and reduce recall of valid candidates.

## 29. `SemanticAssociationSeedRepository` hardening

Even though DREAM-4B does not mutate the graph, harden the existing apply boundary before declaring Dream lifecycle integration ready.

`lockPublishedGeneration` must include TTL:

```sql
SELECT 1
FROM knowledge_document_lifecycle
WHERE document_id = ?
  AND access_level = ?
  AND published_generation = ?
  AND lifecycle_status = 'READY'
  AND retention_status = 'ACTIVE'
  AND (
      expires_at IS NULL
      OR expires_at > clock_timestamp()
  )
FOR SHARE;
```

This also improves ingestion semantic seeding correctness.

DREAM-5 `SemanticGraphPriorWriter` must repeat the same predicate inside its own apply transaction; do not assume this repository call is enough.

## 30. Shared lifecycle predicate

Prefer a shared repository/helper abstraction only if it avoids SQL drift without hiding query planner-critical predicates.

Acceptable:

```java
PublishedLifecycleEligibilitySql.ACTIVE_PUBLISHED_UNEXPIRED
```

or dedicated repository method for row locking.

Avoid fetching lifecycle rows into Java and evaluating expiry there: DB `clock_timestamp()` must remain canonical to avoid pod clock skew.

## 31. DREAM-4A tests

Mandatory integration tests:

```text
semantic_neighbor_search_excludes_expired_target_before_top_k
semantic_neighbor_search_includes_non_expiring_target
semantic_neighbor_search_includes_future_expiry_target
semantic_seed_rejects_expired_generation
semantic_seed_accepts_unexpired_generation
candidate_discovered_then_expired_is_rejected_by_future_apply_boundary
```

The first test must prove ranking semantics, not merely post-filtering. Construct K where an expired high-similarity row would otherwise displace a valid lower-ranked row.

---

# Part E — DREAM-4B: shadow reciprocal ANN discovery

## 32. Required classes

Minimum implementation:

```text
DreamSourceRepository
DreamSource
DreamCandidateDiscovery
DreamReciprocalNeighborVerifier
DreamReverseNeighborCache
DreamConfidenceCalculator
DreamBudget
DreamAdmissionController
DreamCandidateRepository
DreamRunCounters / DreamRunReport
```

No `SemanticGraphPriorWriter` use in DREAM-4B.

## 33. Source repository

Fast lane source query must be keyset-paginated and lifecycle-safe.

Representative query shape:

```sql
SELECT
    v.access_level,
    v.document_id,
    v.generation,
    v.chunk_id,
    v.language,
    v.embedding,
    l.updated_at
FROM <active_vector_table> v
JOIN knowledge_document_lifecycle l
  ON l.document_id = v.document_id
 AND l.access_level = v.access_level
 AND l.published_generation = v.generation
WHERE l.lifecycle_status = 'READY'
  AND l.retention_status = 'ACTIVE'
  AND (l.expires_at IS NULL OR l.expires_at > clock_timestamp())
  AND (
      l.updated_at,
      v.access_level,
      v.document_id,
      v.generation,
      v.chunk_id
  ) > (?, ?, ?, ?, ?)
ORDER BY
      l.updated_at,
      v.access_level,
      v.document_id,
      v.generation,
      v.chunk_id
LIMIT ?;
```

### Critical ambiguity resolution: source `updated_at`

Use a timestamp that advances when **the published searchable projection relevant to Dream changes**. If `knowledge_document_lifecycle.updated_at` does not reliably move for every chunk/vector change inside the same published generation, it is not sufficient alone.

Implementation must verify current schema semantics before coding. If necessary use vector/projection update timestamp or publication timestamp plus identity. The chosen field must satisfy:

```text
newly published searchable chunk cannot be permanently skipped by watermark
```

This is a DREAM-4B review gate.

Record:

```java
public record DreamSource(
        ChunkGraphNode node,
        String language,
        float[] embedding,
        Instant sourceUpdatedAt,
        String embeddingProfileId
) {}
```

Do not persist raw text in Dream source state.

## 34. Rescan lane

Use deterministic keyset rotation independent of fast-lane timestamp.

Recommended ordering:

```text
(access_level, document_id, generation, chunk_id)
```

After end-of-keyspace, wrap to beginning on next run and increment a metric/cycle counter.

Rescan source must still satisfy full lifecycle eligibility.

## 35. Forward ANN

Reuse `SemanticNeighborSearchRepository` after DREAM-4A TTL hardening.

One source:

```java
List<SemanticNeighbor> forward = neighborRepository.search(
        source.embedding(),
        source.language(),
        source.node().accessLevel(),
        properties.dream().topK() + 1,
        properties.dream().candidateThreshold(),
        true
);
```

Do not assume self always consumes one slot. Requesting `topK+1` is acceptable up to repository maximum 256, therefore when Dream `topK=256`, cannot request 257. Required implementation:

```text
searchLimit = topK == 256 ? 256 : topK + 1
```

Then remove self and truncate to at most `topK` valid neighbours.

This edge case must be tested.

## 36. Reciprocal verifier

Interface:

```java
public interface DreamReciprocalNeighborVerifier {
    ReciprocalResult verify(
            DreamSource source,
            SemanticNeighbor forward,
            PolicyIdentity policy,
            DreamBudget budget
    );
}
```

Result:

```java
public record ReciprocalResult(
        boolean mutual,
        double reverseSimilarity,
        int reverseRank,
        boolean fromCache
) {}
```

Reverse lookup must use target node's **actual current embedding and language**, not source embedding.

Therefore verifier needs a lifecycle-safe vector lookup by full node identity:

```java
Optional<DreamSource> findEligibleSource(ChunkGraphNode node)
```

If target becomes ineligible between forward search and reverse lookup, classify as `INELIGIBLE`, not infrastructure failure.

Then:

```java
DreamSource target = sourceRepository.findEligibleSource(forward.node())
        .orElseThrow(() -> ReciprocalUnavailable.ineligible(...));

List<SemanticNeighbor> reverse = cache.getOrLoad(
        new ReverseCacheKey(target.node(), policy.fingerprint()),
        () -> neighborRepository.search(
                target.embedding(),
                target.language(),
                target.node().accessLevel(),
                searchLimit,
                properties.dream().candidateThreshold(),
                true
        )
);
```

Find source identity in reverse list. Rank is 1-based after removing reverse self and truncating to `topK`.

## 37. Reverse cache

Do not require a new dependency if the project does not already use Caffeine. A bounded access-order `LinkedHashMap` guarded by single-owner/single-run usage is sufficient for v1 if reciprocal verification is serialized around the cache.

If parallel reciprocal workers share cache, use a concurrency-safe bounded implementation.

Contract:

```java
public interface DreamReverseNeighborCache {
    Optional<List<SemanticNeighbor>> get(ReverseCacheKey key);
    void put(ReverseCacheKey key, List<SemanticNeighbor> value);
    int size();
    void clear();
}
```

Key includes:

```text
full ChunkGraphNode
semantic policy fingerprint
```

Embedding profile is already represented by fingerprint.

Cache hit must not consume reverse ANN query budget; cache miss that executes ANN must consume exactly one.

## 38. Budget model

Use one run-scoped mutable budget object with atomic counters/deadline if work is concurrent.

```java
public final class DreamBudget {
    private final Instant deadline;
    private final AtomicInteger sources = new AtomicInteger();
    private final AtomicInteger annQueries = new AtomicInteger();
    private final AtomicInteger reverseAnnQueries = new AtomicInteger();
    private final AtomicLong dbRowsTouched = new AtomicLong();

    public void acquireSource();
    public void acquireForwardAnn();
    public void acquireReverseAnn();
    public void addDbRows(long rows);
    public void checkDeadline();
}
```

Semantics: **reserve before executing work**. If the next unit would exceed the limit, throw/return `DreamBudgetExhausted` before issuing that query/write.

`maxAnnQueriesPerRun` is total ANN queries; `maxReverseAnnQueriesPerRun` is an additional subset cap. A reverse query increments both total and reverse counters.

Budget stop is `PARTIAL_BUDGET`, not `FAILED`.

## 39. Admission controller

DREAM-4B minimum implementation can be intentionally simple:

```java
public interface DreamAdmissionController {
    Decision beforeBatch();

    enum Decision { CONTINUE, YIELD, STOP }
}
```

At minimum:

- `STOP` when application shutting down/cancelled;
- `STOP` when authority lost;
- `YIELD` allowed when local pressure signal says interactive work is saturated;
- `CONTINUE` otherwise.

Do not design Kubernetes autoscaling feedback in v1.

## 40. Discovery service

Outline:

```java
public DiscoveryBatchResult discover(
        UUID runId,
        DreamAuthority authority,
        PolicyIdentity policy,
        List<DreamSource> sources,
        DreamBudget budget
) {
    Map<ChunkGraphNode, List<ScoredCandidate>> bySource = new LinkedHashMap<>();

    for (DreamSource source : sources) {
        cancellation.throwIfCancelled();
        budget.checkDeadline();
        budget.acquireSource();
        budget.acquireForwardAnn();

        List<SemanticNeighbor> forward = searchForward(source);
        for (SemanticNeighbor neighbor : normalizeForward(source, forward)) {
            DreamPair pair = DreamPair.of(source.node(), neighbor.node());
            if (!batchPairDedup.add(pair)) {
                continue;
            }

            ReciprocalResult reciprocal;
            try {
                reciprocal = reciprocalVerifier.verify(
                        source,
                        neighbor,
                        policy,
                        budget
                );
            } catch (InfrastructureUnavailable ex) {
                metrics.verificationUnknown(...);
                continue; // UNKNOWN, not negative
            }

            if (!reciprocal.mutual()) {
                metrics.mutual(false);
                continue;
            }

            double confidence = confidenceCalculator.calculate(
                    neighbor.similarity(),
                    reciprocal.reverseSimilarity(),
                    neighborRank,
                    reciprocal.reverseRank(),
                    dream.topK()
            );

            ScoredCandidate candidate = ...;
            bySource.computeIfAbsent(source.node(), ignored -> new ArrayList<>())
                    .add(candidate);
        }
    }

    return persistTopCandidates(runId, policy, bySource);
}
```

### Dedup semantics

Do not use one global pair dedup if that would make result depend on source processing order and accidentally discard better reciprocal evidence from the opposite direction.

For each canonical pair within one run, aggregate the best deterministic evidence:

```text
forward/reverse roles normalized by canonical node ordering
confidence = deterministic result under canonical orientation
```

Recommended: normalize evidence to `node_a -> node_b` and `node_b -> node_a` fields, then upsert once per pair/run.

## 41. Candidate activation in shadow

Dream candidate may become internal state `ACTIVE` in shadow if:

```text
mutual == true
confidence >= activationThreshold
both nodes remained eligible during verification
```

This only means semantic activation in Dream-owned table.

It MUST NOT call graph repository/writer.

Persist reason code:

```text
MUTUAL_KNN_CONFIDENCE
```

Candidates mutual but below activation remain `CANDIDATE`.

## 42. Confidence calculator

```java
@Component
public final class DreamConfidenceCalculator {
    public double calculate(
            double forwardSimilarity,
            double reverseSimilarity,
            int forwardRank,
            int reverseRank,
            int topK
    ) {
        requireUnit(forwardSimilarity);
        requireUnit(reverseSimilarity);
        requireRange(forwardRank, 1, topK);
        requireRange(reverseRank, 1, topK);

        double similarity = Math.min(
                forwardSimilarity,
                reverseSimilarity
        );
        int maxRank = Math.max(forwardRank, reverseRank);
        double rankFactor = 1.0
                - (((double) maxRank - 1.0) / (double) topK);
        return clamp01(
                similarity * (0.85 + 0.15 * rankFactor)
        );
    }
}
```

Unit tests must cover rank 1, rank K, symmetry, monotonicity and threshold edge cases.

## 43. Failure classification

DREAM-4B must distinguish:

```text
POSITIVE
NEGATIVE_SEMANTIC
INELIGIBLE
UNKNOWN_INFRASTRUCTURE
CANCELLED
LOST_OWNERSHIP
BUDGET_STOP
```

For DREAM-4B discovery, only positive mutual candidates need persistence. Future DREAM-6 negative streak logic must never treat `UNKNOWN_INFRASTRUCTURE`, cancellation, lost ownership or budget stop as semantic negative evidence.

Create enum now if useful:

```java
public enum DreamVerificationOutcome {
    MUTUAL,
    NOT_MUTUAL,
    INELIGIBLE,
    UNKNOWN,
    CANCELLED
}
```

## 44. Metrics — exact first milestone

Extend `AkmaiMetrics` using existing project conventions; avoid a parallel metrics framework.

Minimum:

```text
adaptive_graph_dream_runs_total{outcome}
adaptive_graph_dream_run_duration_seconds
adaptive_graph_dream_sources_total{lane}
adaptive_graph_dream_ann_queries_total{direction}
adaptive_graph_dream_reverse_cache_total{result}
adaptive_graph_dream_candidates_total{state}
adaptive_graph_dream_mutual_knn_total{result}
adaptive_graph_dream_budget_stops_total{reason}
adaptive_graph_dream_lease_events_total{event}
adaptive_graph_dream_fencing_rejections_total
adaptive_graph_dream_checkpoint_advance_total{result}
```

Avoid high-cardinality labels:

Forbidden labels include:

```text
document_id
chunk_id
run_id
owner_id
policy_fingerprint
```

Those belong in logs/audit rows, not metric labels.

## 45. Shadow immutability test

Mandatory end-to-end integration test:

1. seed eligible documents/vectors;
2. seed at least one existing graph association;
3. compute deterministic logical snapshot of `knowledge_chunk_association`;
4. run one complete Dream shadow cycle;
5. compute snapshot again;
6. assert exact equality;
7. assert Dream candidate/run/checkpoint state changed as expected.

Do not rely on PostgreSQL physical page/byte comparison. Compare logical ordered rows and all graph columns.

## 46. DREAM-4B test matrix

Unit:

```text
confidence_is_symmetric_for_swapped_directions
confidence_decreases_with_worse_reciprocal_rank
confidence_uses_minimum_similarity
canonical_pair_dedup_is_order_independent
budget_reserves_before_query
reverse_cache_hit_does_not_consume_ann_budget
reverse_cache_key_is_policy_isolated
```

Integration/Testcontainers:

```text
mutual_knn_pair_is_persisted
one_way_knn_is_not_activated
cross_acl_never_becomes_candidate
same_language_policy_is_enforced
expired_high_similarity_neighbor_does_not_displace_valid_top_k
reverse_target_expiring_between_forward_and_reverse_is_ineligible
source_keyset_resume_has_no_gap
rescan_cursor_wraps_deterministically
replayed_run_observation_is_idempotent
budget_stop_advances_only_completed_checkpoint
lease_loss_stops_checkpoint_progress
full_shadow_run_does_not_modify_graph
```

Multi-pod:

```text
six_pods_only_owner_runs_ann_work
standby_pods_do_not_issue_dream_ann_queries
owner_takeover_resumes_checkpoint
old_owner_ann_may_finish_but_cannot_checkpoint_after_takeover
```

Performance acceptance before merging DREAM-4B as production-safe shadow code:

```text
4–6 pods under synthetic RAG load
+ 1 active Dream shadow owner

no JDBC starvation
request error rate inside agreed budget
request p95/p99 regression inside agreed budget
Dream respects configured ANN/concurrency/run-duration caps
```

Exact latency budgets must be set from current baseline measurements, not invented in code.

---

# Part F — implementation order and commit gates

## 47. Recommended commit sequence

Do not implement all phases in one unreviewable commit.

```text
1. feat(dream): add configuration and policy fingerprint
2. test(dream): cover configuration and fingerprint invariants
3. feat(dream): add migration 026 and persistence repositories
4. test(dream): add persistence and concurrency integration tests
5. feat(dream): add lease, heartbeat and checkpoint fencing
6. test(dream): add six-owner takeover/resume tests
7. fix(graph): enforce TTL in semantic ANN and seed lifecycle checks
8. test(graph): cover TTL ranking and apply races
9. feat(dream): add source lanes and reciprocal shadow discovery
10. feat(dream): add budgets, cache and metrics
11. test(dream): add shadow graph-immutability and multipod load gates
```

Each numbered group should leave CI green.

## 48. Review gates

### Gate G1 — after DREAM-1

Required evidence:

```text
config binds
all defaults valid/off
invalid combinations fail fast
fingerprint deterministic
no runtime behaviour change while disabled
```

### Gate G2 — after DREAM-2

```text
migration applies cleanly on empty/current schema
candidate canonical uniqueness proven concurrently
lease/checkpoint repositories enforce constraints
no graph table change required yet
```

### Gate G3 — after DREAM-3

```text
6 contenders -> 1 owner
heartbeat protects healthy long run
expired owner takeover increments token
stale checkpoint authority rejected
replay idempotency proven
```

### Gate G4A

```text
semantic ANN TTL fence happens in SQL before LIMIT/top-K
seed/apply lifecycle lock includes TTL
expiry race tests green
```

### Gate G4B

```text
reciprocal ANN works
cache/budgets measurable
source/checkpoint resumability proven
shadow graph mutation = zero
4–6 pod serving performance acceptable
```

No DREAM-5 apply work should start until G4B review is accepted.

---

# Part G — defects and ambiguities found by this review

## 49. D1 — replay could falsely increase positive streak

Severity: **P0 for resumability**.

Problem: original checkpoint design correctly allows replay after crash, but a naive candidate upsert increments `positive_streak` again for the same run observation.

Resolution: positive observation must be idempotent per run/source observation; minimum v1 safeguard is `last_verified_run_id IS DISTINCT FROM :runId` before streak increment. If one run can validly verify the same pair from multiple distinct source lanes and those should count separately, introduce a separate observation identity table/key rather than silently double counting.

For DREAM-4B, count at most one positive streak increment per canonical pair per run.

## 50. D2 — source watermark timestamp is not yet proven correct

Severity: **P0 design verification before coding source query**.

Problem: using `knowledge_document_lifecycle.updated_at` is only correct if every newly searchable publication relevant to Dream advances it consistently.

Resolution: developer must verify schema/write paths. Select a source timestamp with the invariant that no newly published vector/chunk can be permanently skipped. Document the selected field in code and integration test publication-after-watermark.

## 51. D3 — `topK=256` plus self-padding can exceed repository maximum

Severity: **P1**.

Resolution: search limit is `min(256, topK + 1)`, remove self, truncate to `topK`. Test boundary 256.

## 52. D4 — pair dedup can become order-dependent

Severity: **P1**.

Problem: global first-seen canonical pair dedup may preserve weaker orientation/evidence depending on source iteration order.

Resolution: normalize directional evidence to canonical A/B and aggregate deterministically before one upsert per pair/run.

## 53. D5 — lease heartbeat must not share ANN executor

Severity: **P0 availability/HA**.

Resolution: dedicated scheduler/executor for heartbeat. ANN saturation must not expire healthy owner.

## 54. D6 — checkpoint fencing must validate live lease, not stored token alone

Severity: **P0 correctness**.

Resolution: checkpoint CAS includes `EXISTS` against live lease row with owner/token and `lease_until > clock_timestamp()`.

## 55. D7 — shadow graph immutability needs logical snapshot definition

Severity: **P1 test clarity**.

Resolution: ordered logical row comparison across all `knowledge_chunk_association` columns before/after shadow run.

## 56. D8 — metrics cardinality not explicitly bounded

Severity: **P1 operations**.

Resolution: no document/chunk/run/owner/policy IDs as metric labels.

## 57. D9 — reciprocal reverse lookup needs current target vector API

Severity: **P0 implementation dependency**.

Resolution: `DreamSourceRepository.findEligibleSource(ChunkGraphNode)` must fetch current lifecycle-eligible vector/language for target identity. Reverse ANN cannot reuse source vector.

## 58. D10 — infrastructure failures must be distinct from semantic negative

Severity: **P0 future forgetting safety**.

Resolution: explicit verification outcome classification. Timeout, DB error, cancellation, budget stop and lost lease are UNKNOWN/non-negative.

---

# Part H — Definition of Done for first milestone

DREAM-1..DREAM-4B are complete only when all are true:

1. Dream defaults fully OFF.
2. Config validation is fail-fast and covered.
3. Policy fingerprint is deterministic and semantic-only.
4. Migration 026 applies and rolls forward cleanly under project Liquibase flow.
5. Candidate pair uniqueness is enforced in Java and DB.
6. Candidate observation replay does not fabricate positive streak support.
7. Exactly one Dream owner exists for a policy/version across 4–6 pods.
8. Lease heartbeat is independent from ANN worker saturation.
9. Fencing token is monotonic.
10. Expired owner cannot renew, checkpoint or apply.
11. Checkpoint always trails durable observation work.
12. Fast-lane watermark source field has a proven no-gap invariant.
13. Rescan lane is deterministic and bounded.
14. Semantic ANN excludes TTL-expired rows before top-K LIMIT.
15. Semantic seed lifecycle lock rejects TTL-expired generation.
16. Reciprocal lookup uses current target embedding/language.
17. Reciprocal cache is bounded and policy-isolated.
18. ANN budgets reserve before issuing work.
19. Budget exhaustion produces `PARTIAL_BUDGET`, not failure.
20. Shadow mode changes Dream-owned tables only.
21. Full logical `knowledge_chunk_association` snapshot is unchanged by shadow run.
22. Standby pods issue no duplicate Dream ANN workload.
23. Multi-pod takeover resumes without source gap.
24. Metrics have bounded cardinality.
25. Testcontainers coverage includes lifecycle, concurrency, fencing and replay cases.
26. 4–6 pod synthetic serving test shows no unacceptable request-path regression.
27. CI is green.

At this point Dream is production-safe **for shadow observation only**. It is not yet authorized to mutate semantic priors or influence online retrieval.
