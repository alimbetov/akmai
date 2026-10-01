CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS document_identifier (
    id               BIGINT GENERATED ALWAYS AS IDENTITY,
    document_id      VARCHAR(100) NOT NULL,
    chunk_id         VARCHAR(100),
    page_number      INTEGER NOT NULL,
    identifier_type  VARCHAR(50) NOT NULL,
    raw_value        VARCHAR(500) NOT NULL,
    normalized_value VARCHAR(500) NOT NULL,
    context_text     TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE IF NOT EXISTS document_identifier_default
    PARTITION OF document_identifier DEFAULT;

CREATE INDEX IF NOT EXISTS idx_document_identifier_exact
    ON document_identifier (normalized_value);

CREATE INDEX IF NOT EXISTS idx_document_identifier_type_exact
    ON document_identifier (identifier_type, normalized_value);

CREATE INDEX IF NOT EXISTS idx_document_identifier_document
    ON document_identifier (document_id);

CREATE INDEX IF NOT EXISTS idx_document_identifier_trgm
    ON document_identifier USING GIN (normalized_value gin_trgm_ops);


CREATE TABLE IF NOT EXISTS knowledge_search_projection (
    chunk_id        VARCHAR(100) PRIMARY KEY,
    document_id     VARCHAR(100) NOT NULL,
    parent_chunk_id VARCHAR(100),
    chunk_index     INTEGER NOT NULL,
    text_content    TEXT NOT NULL,
    language        VARCHAR(32) NOT NULL,
    section_path    TEXT,
    search_vector   TSVECTOR GENERATED ALWAYS AS (
        to_tsvector(
            'simple',
            coalesce(section_path, '') || ' ' || coalesce(text_content, '')
        )
    ) STORED,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_search_document
    ON knowledge_search_projection (document_id, chunk_index);

CREATE INDEX IF NOT EXISTS idx_knowledge_search_fts
    ON knowledge_search_projection USING GIN (search_vector);
