--liquibase formatted sql

--changeset akmai-greenfield:015-semantic-memory-prior
ALTER TABLE knowledge_chunk_association
    ADD COLUMN semantic_similarity DOUBLE PRECISION NULL,
    ADD COLUMN semantic_seeded_at TIMESTAMPTZ NULL,
    ADD COLUMN semantic_last_seen_at TIMESTAMPTZ NULL;

ALTER TABLE knowledge_chunk_association
    ADD CONSTRAINT ck_chunk_association_semantic_similarity
    CHECK (
        semantic_similarity IS NULL
        OR (semantic_similarity >= 0 AND semantic_similarity <= 1)
    );

COMMENT ON COLUMN knowledge_chunk_association.semantic_similarity IS
    'Ingestion-time semantic prior. Kept separate from adaptive learned weight.';
COMMENT ON COLUMN knowledge_chunk_association.semantic_seeded_at IS
    'First time this association was proposed by semantic ingestion linking.';
COMMENT ON COLUMN knowledge_chunk_association.semantic_last_seen_at IS
    'Most recent ingestion-time observation of the semantic relation.';

--changeset akmai-greenfield:015-semantic-memory-runtime-flag
INSERT INTO app_parameter (
    parameter_key,
    parameter_type,
    parameter_value,
    updated_by
)
VALUES (
    'akmai.semantic-memory.ingestion-linking-enabled',
    'BOOLEAN',
    'false',
    'liquibase'
)
ON CONFLICT (parameter_key) DO NOTHING;
