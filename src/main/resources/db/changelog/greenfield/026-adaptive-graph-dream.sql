--liquibase formatted sql

--changeset akmai-greenfield:026-adaptive-graph-dream
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
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
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
    CONSTRAINT ck_dream_candidate_positive_identity CHECK (
        access_level > 0
        AND node_a_generation > 0
        AND node_b_generation > 0
        AND graph_version > 0
    ),
    CONSTRAINT ck_dream_candidate_canonical_pair CHECK (
        ROW(node_a_document_id, node_a_generation, node_a_chunk_id)
        < ROW(node_b_document_id, node_b_generation, node_b_chunk_id)
    ),
    CONSTRAINT ck_dream_candidate_state CHECK (
        state IN ('CANDIDATE', 'ACTIVE', 'STALE', 'REJECTED')
    ),
    CONSTRAINT ck_dream_candidate_forward_similarity CHECK (
        forward_similarity IS NULL OR forward_similarity BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_dream_candidate_reverse_similarity CHECK (
        reverse_similarity IS NULL OR reverse_similarity BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_dream_candidate_confidence CHECK (
        confidence BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_dream_candidate_ranks CHECK (
        (forward_rank IS NULL OR forward_rank > 0)
        AND (reverse_rank IS NULL OR reverse_rank > 0)
    ),
    CONSTRAINT ck_dream_candidate_streaks CHECK (
        positive_streak >= 0 AND negative_streak >= 0
    ),
    CONSTRAINT ck_dream_candidate_policy_fingerprint CHECK (
        semantic_policy_fingerprint ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX idx_dream_candidate_policy_state_verified
    ON knowledge_chunk_dream_candidate(
        semantic_policy_fingerprint,
        state,
        last_verified_at
    );
CREATE INDEX idx_dream_candidate_acl_state_updated
    ON knowledge_chunk_dream_candidate(access_level, state, updated_at);
CREATE INDEX idx_dream_candidate_policy_updated
    ON knowledge_chunk_dream_candidate(semantic_policy_fingerprint, updated_at);

CREATE TABLE adaptive_graph_dream_lease (
    graph_version INTEGER NOT NULL,
    semantic_policy_fingerprint VARCHAR(64) NOT NULL,
    owner_id VARCHAR(200),
    lease_until TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (graph_version, semantic_policy_fingerprint),
    CONSTRAINT ck_dream_lease_graph_version CHECK (graph_version > 0),
    CONSTRAINT ck_dream_lease_fencing_token CHECK (fencing_token >= 0),
    CONSTRAINT ck_dream_lease_policy_fingerprint CHECK (
        semantic_policy_fingerprint ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE adaptive_graph_dream_run (
    run_id UUID PRIMARY KEY,
    owner_id VARCHAR(200) NOT NULL,
    fencing_token BIGINT NOT NULL,
    graph_version INTEGER NOT NULL,
    semantic_policy_version VARCHAR(100) NOT NULL,
    semantic_policy_fingerprint VARCHAR(64) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL,
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
    CONSTRAINT ck_dream_run_status CHECK (
        status IN (
            'RUNNING', 'SUCCEEDED', 'PARTIAL_BUDGET',
            'FAILED', 'LOST_OWNERSHIP', 'CANCELLED'
        )
    ),
    CONSTRAINT ck_dream_run_counts CHECK (
        fencing_token >= 0
        AND graph_version > 0
        AND sources_fast >= 0
        AND sources_rescan >= 0
        AND forward_ann_queries >= 0
        AND reverse_ann_queries >= 0
        AND candidates_seen >= 0
        AND candidates_mutual >= 0
        AND candidates_activated >= 0
        AND candidates_applied >= 0
        AND candidates_retired >= 0
        AND db_rows_touched >= 0
    ),
    CONSTRAINT ck_dream_run_policy_fingerprint CHECK (
        semantic_policy_fingerprint ~ '^[0-9a-f]{64}$'
    )
);
CREATE INDEX idx_dream_run_policy_started
    ON adaptive_graph_dream_run(semantic_policy_fingerprint, started_at DESC);

CREATE TABLE adaptive_graph_dream_checkpoint (
    graph_version INTEGER NOT NULL,
    semantic_policy_fingerprint VARCHAR(64) NOT NULL,
    fast_watermark_updated_at TIMESTAMPTZ,
    fast_watermark_access_level BIGINT,
    fast_watermark_document_id VARCHAR(100),
    fast_watermark_generation BIGINT,
    fast_watermark_chunk_id VARCHAR(100),
    rescan_cursor TEXT,
    last_successful_run_id UUID,
    last_completed_at TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (graph_version, semantic_policy_fingerprint),
    CONSTRAINT ck_dream_checkpoint_graph_version CHECK (graph_version > 0),
    CONSTRAINT ck_dream_checkpoint_fencing CHECK (fencing_token >= 0),
    CONSTRAINT ck_dream_checkpoint_policy_fingerprint CHECK (
        semantic_policy_fingerprint ~ '^[0-9a-f]{64}$'
    )
);

--changeset akmai-greenfield:026-adaptive-graph-dream-runtime-seed
INSERT INTO app_parameter (
    parameter_key,
    parameter_type,
    parameter_value,
    updated_by
)
VALUES
    ('akmai.adaptive-graph.dream-enabled', 'BOOLEAN', 'false', 'liquibase'),
    ('akmai.adaptive-graph.dream.apply-enabled', 'BOOLEAN', 'false', 'liquibase')
ON CONFLICT (parameter_key) DO NOTHING;
