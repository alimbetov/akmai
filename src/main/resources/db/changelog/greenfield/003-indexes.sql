--liquibase formatted sql

--changeset akmai-greenfield:003-indexes
CREATE UNIQUE INDEX uq_document_generation_published
    ON knowledge_document_generation(document_id)
    WHERE generation_status = 'PUBLISHED';

CREATE INDEX idx_document_generation_stale
    ON knowledge_document_generation(
        generation_kind,
        generation_status,
        started_at,
        document_id
    );

CREATE INDEX idx_knowledge_lifecycle_retention
    ON knowledge_document_lifecycle(
        lifecycle_status,
        expires_at,
        document_id
    )
    WHERE lifecycle_policy = 'TTL';

CREATE INDEX idx_knowledge_lifecycle_claim
    ON knowledge_document_lifecycle(
        lifecycle_status,
        lease_until,
        claim_generation
    )
    WHERE lifecycle_status IN (
        'DELETE_PENDING',
        'DELETING',
        'DELETE_FAILED'
    );

CREATE INDEX idx_knowledge_lifecycle_stale_ingestion
    ON knowledge_document_lifecycle(
        ingestion_started_at,
        document_id
    )
    WHERE lifecycle_status = 'INGESTING';

CREATE INDEX idx_lifecycle_active_acl_document
    ON knowledge_document_lifecycle(
        access_level,
        document_id
    )
    INCLUDE (published_generation)
    WHERE retention_status = 'ACTIVE';

CREATE INDEX idx_projection_document_generation_chunk_index
    ON knowledge_search_projection(
        document_id,
        generation,
        chunk_index
    );

CREATE INDEX idx_projection_fts_simple
    ON knowledge_search_projection
    USING GIN (search_vector);

CREATE INDEX idx_projection_text_trgm
    ON knowledge_search_projection
    USING GIN (lower(text_content) gin_trgm_ops);

CREATE INDEX idx_projection_section_trgm
    ON knowledge_search_projection
    USING GIN (lower(coalesce(section_path, '')) gin_trgm_ops);

CREATE INDEX idx_identifier_type_exact
    ON document_identifier(
        identifier_type,
        normalized_value,
        created_at DESC
    );

CREATE INDEX idx_identifier_exact
    ON document_identifier(
        normalized_value,
        created_at DESC
    );

CREATE INDEX idx_identifier_type_prefix
    ON document_identifier(
        identifier_type,
        normalized_value text_pattern_ops
    );

CREATE INDEX idx_identifier_trgm
    ON document_identifier
    USING GIN (normalized_value gin_trgm_ops);
