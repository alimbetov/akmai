--liquibase formatted sql

--changeset akmai:024-policy-canary-evidence
CREATE TABLE IF NOT EXISTS rag_policy_canary_observation (
    id                  BIGSERIAL PRIMARY KEY,
    policy_version      VARCHAR(128) NOT NULL,
    request_id          UUID NOT NULL,
    cohort              VARCHAR(16) NOT NULL,
    source_fingerprint  VARCHAR(128) NOT NULL DEFAULT '',
    query_class         VARCHAR(64) NOT NULL,
    answer_status       VARCHAR(32) NOT NULL,
    grounding_status    VARCHAR(32) NOT NULL,
    degraded            BOOLEAN NOT NULL DEFAULT FALSE,
    critical_failure    BOOLEAN NOT NULL DEFAULT FALSE,
    total_latency_ms    BIGINT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_rag_policy_canary_observation
        UNIQUE (policy_version, request_id),
    CONSTRAINT ck_rag_policy_canary_cohort
        CHECK (cohort IN ('CANARY', 'CONTROL')),
    CONSTRAINT ck_rag_policy_canary_latency
        CHECK (total_latency_ms >= 0)
);

CREATE INDEX IF NOT EXISTS idx_rag_policy_canary_policy_created
    ON rag_policy_canary_observation (policy_version, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_rag_policy_canary_policy_cohort
    ON rag_policy_canary_observation (policy_version, cohort, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_rag_policy_canary_source
    ON rag_policy_canary_observation (policy_version, source_fingerprint)
    WHERE source_fingerprint <> '';
