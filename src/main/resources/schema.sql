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
