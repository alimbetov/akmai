--liquibase formatted sql

--changeset akmai-greenfield:016-self-optimizing-query-memory
CREATE TABLE rag_query_memory_cluster (
    cluster_id               UUID PRIMARY KEY,
    embedding_profile_id     VARCHAR(128) NOT NULL,
    required_access_levels   JSONB NOT NULL,
    centroid                 JSONB NOT NULL,
    observation_count        INTEGER NOT NULL CHECK (observation_count > 0),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT ck_rag_query_memory_scope_array
        CHECK (jsonb_typeof(required_access_levels) = 'array'),
    CONSTRAINT ck_rag_query_memory_centroid_array
        CHECK (jsonb_typeof(centroid) = 'array')
);

CREATE INDEX idx_rag_query_memory_profile_updated
    ON rag_query_memory_cluster(embedding_profile_id, updated_at DESC);

CREATE TABLE rag_query_memory_observation (
    observation_id       UUID PRIMARY KEY,
    cluster_id           UUID NOT NULL,
    query_fingerprint    VARCHAR(64) NOT NULL,
    grounded_answer      VARCHAR(8000) NOT NULL,
    source_refs          JSONB NOT NULL,
    observed_at          TIMESTAMPTZ NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT fk_rag_query_memory_observation_cluster
        FOREIGN KEY (cluster_id)
        REFERENCES rag_query_memory_cluster(cluster_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_rag_query_memory_source_refs_array
        CHECK (jsonb_typeof(source_refs) = 'array')
);

CREATE INDEX idx_rag_query_memory_observation_cluster
    ON rag_query_memory_observation(cluster_id, observed_at DESC);

--changeset akmai-greenfield:016-self-optimizing-learning-event
CREATE TABLE rag_learning_event (
    event_id                    UUID PRIMARY KEY,
    request_id                  UUID NOT NULL UNIQUE,
    query_fingerprint           VARCHAR(64) NOT NULL,
    access_levels               JSONB NOT NULL,
    language                    VARCHAR(32) NOT NULL DEFAULT 'unknown',
    query_class                 VARCHAR(64) NOT NULL DEFAULT 'UNKNOWN',
    corpus_version              VARCHAR(128) NOT NULL,
    embedding_profile_id        VARCHAR(128),
    retrieval_policy_version    VARCHAR(128) NOT NULL,
    learning_policy_version     VARCHAR(128) NOT NULL,
    grounding_policy_version    VARCHAR(128) NOT NULL,
    answer_status               VARCHAR(32) NOT NULL,
    grounding_status            VARCHAR(32) NOT NULL,
    retrieved_count             INTEGER NOT NULL CHECK (retrieved_count >= 0),
    selected_count              INTEGER NOT NULL CHECK (selected_count >= 0),
    cited_count                 INTEGER NOT NULL CHECK (cited_count >= 0),
    total_latency_ms            BIGINT NOT NULL CHECK (total_latency_ms >= 0),
    trace_json                  JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT ck_rag_learning_event_scope_array
        CHECK (jsonb_typeof(access_levels) = 'array'),
    CONSTRAINT ck_rag_learning_event_answer_status
        CHECK (answer_status IN (
            'GROUNDED',
            'INSUFFICIENT',
            'UNGROUNDED',
            'UNAVAILABLE'
        )),
    CONSTRAINT ck_rag_learning_event_grounding_status
        CHECK (grounding_status IN (
            'SUPPORTED',
            'CONTRADICTED',
            'INSUFFICIENT',
            'DETERMINISTIC_REJECTED',
            'NOT_EVALUATED'
        ))
);

CREATE INDEX idx_rag_learning_event_created
    ON rag_learning_event(created_at DESC);
CREATE INDEX idx_rag_learning_event_policy
    ON rag_learning_event(
        retrieval_policy_version,
        learning_policy_version,
        grounding_policy_version,
        created_at DESC
    );

--changeset akmai-greenfield:016-self-optimizing-feedback
CREATE TABLE rag_feedback (
    feedback_id          UUID PRIMARY KEY,
    idempotency_key      VARCHAR(200) NOT NULL UNIQUE,
    request_id           UUID NOT NULL,
    reason               VARCHAR(64) NOT NULL,
    details              VARCHAR(2000),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT fk_rag_feedback_request
        FOREIGN KEY (request_id)
        REFERENCES rag_learning_event(request_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_rag_feedback_reason
        CHECK (reason IN (
            'GOOD',
            'WRONG_ANSWER',
            'WRONG_EVIDENCE',
            'MISSING_EVIDENCE',
            'OUTDATED_EVIDENCE',
            'BAD_CITATION',
            'INCOMPLETE'
        ))
);

CREATE INDEX idx_rag_feedback_request
    ON rag_feedback(request_id, created_at DESC);

--changeset akmai-greenfield:016-self-optimizing-policy-registry
CREATE TABLE rag_policy_registry (
    policy_type          VARCHAR(32) NOT NULL,
    policy_version       VARCHAR(128) NOT NULL,
    policy_status        VARCHAR(32) NOT NULL,
    configuration_json   JSONB NOT NULL DEFAULT '{}'::jsonb,
    quality_report_json  JSONB,
    performance_report_json JSONB,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    decided_at           TIMESTAMPTZ,

    PRIMARY KEY (policy_type, policy_version),
    CONSTRAINT ck_rag_policy_type
        CHECK (policy_type IN ('RETRIEVAL', 'LEARNING', 'GROUNDING')),
    CONSTRAINT ck_rag_policy_status
        CHECK (policy_status IN (
            'CANDIDATE',
            'CANARY',
            'APPROVED',
            'REJECTED',
            'ROLLED_BACK'
        ))
);

CREATE UNIQUE INDEX uq_rag_policy_single_approved
    ON rag_policy_registry(policy_type)
    WHERE policy_status = 'APPROVED';
