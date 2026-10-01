--liquibase formatted sql

--changeset akmai:001-canonical-retrieval-schema
CREATE EXTENSION IF NOT EXISTS pg_trgm;

DROP TABLE IF EXISTS document_identifier CASCADE;

CREATE TABLE document_identifier (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id      VARCHAR(100) NOT NULL,
    chunk_id         VARCHAR(100) NOT NULL,
    page_number      INTEGER NOT NULL,
    identifier_type  VARCHAR(50) NOT NULL,
    raw_value        VARCHAR(500) NOT NULL,
    normalized_value VARCHAR(500) NOT NULL,
    context_text     TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_document_identifier_occurrence
        UNIQUE (document_id, chunk_id, identifier_type, normalized_value)
);

CREATE INDEX idx_document_identifier_exact
    ON document_identifier (normalized_value);
CREATE INDEX idx_document_identifier_type_exact
    ON document_identifier (identifier_type, normalized_value);
CREATE INDEX idx_document_identifier_document
    ON document_identifier (document_id);
CREATE INDEX idx_document_identifier_trgm
    ON document_identifier USING GIN (normalized_value gin_trgm_ops);

CREATE TABLE IF NOT EXISTS knowledge_search_projection (
    chunk_id            VARCHAR(100) PRIMARY KEY,
    document_id         VARCHAR(100) NOT NULL,
    parent_chunk_id     VARCHAR(100),
    chunk_index         INTEGER NOT NULL,
    text_content        TEXT NOT NULL,
    embedding_text      TEXT,
    language            VARCHAR(32) NOT NULL,
    domain              VARCHAR(50),
    section_path        TEXT,
    identifiers_json    JSONB NOT NULL DEFAULT '[]'::jsonb,
    references_json     JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata_json       JSONB NOT NULL DEFAULT '{}'::jsonb,
    projection_version  INTEGER NOT NULL DEFAULT 1,
    search_vector       TSVECTOR GENERATED ALWAYS AS (
        to_tsvector(
            'simple',
            coalesce(section_path, '') || ' ' || coalesce(text_content, '')
        )
    ) STORED,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE knowledge_search_projection
    ADD COLUMN IF NOT EXISTS embedding_text TEXT,
    ADD COLUMN IF NOT EXISTS domain VARCHAR(50),
    ADD COLUMN IF NOT EXISTS identifiers_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS references_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS projection_version INTEGER NOT NULL DEFAULT 1;

UPDATE knowledge_search_projection
SET embedding_text = text_content
WHERE embedding_text IS NULL;

UPDATE knowledge_search_projection
SET domain = 'GENERAL'
WHERE domain IS NULL;

ALTER TABLE knowledge_search_projection
    ALTER COLUMN embedding_text SET NOT NULL,
    ALTER COLUMN domain SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_search_document_chunk_index
    ON knowledge_search_projection (document_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_knowledge_search_document
    ON knowledge_search_projection (document_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_knowledge_search_fts
    ON knowledge_search_projection USING GIN (search_vector);
