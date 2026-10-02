--liquibase formatted sql

--changeset akmai:008-published-lexical-indexes
ALTER TABLE knowledge_search_projection
    ADD COLUMN IF NOT EXISTS search_vector_ru TSVECTOR GENERATED ALWAYS AS (
        to_tsvector(
            'russian',
            coalesce(section_path, '') || ' ' || coalesce(text_content, '')
        )
    ) STORED,
    ADD COLUMN IF NOT EXISTS search_vector_en TSVECTOR GENERATED ALWAYS AS (
        to_tsvector(
            'english',
            coalesce(section_path, '') || ' ' || coalesce(text_content, '')
        )
    ) STORED;

CREATE INDEX IF NOT EXISTS idx_knowledge_search_fts_ru
    ON knowledge_search_projection USING GIN (search_vector_ru);

CREATE INDEX IF NOT EXISTS idx_knowledge_search_fts_en
    ON knowledge_search_projection USING GIN (search_vector_en);

CREATE INDEX IF NOT EXISTS idx_knowledge_lifecycle_published
    ON knowledge_document_lifecycle(document_id, published_generation, retention_status);

CREATE INDEX IF NOT EXISTS idx_knowledge_search_generation_language
    ON knowledge_search_projection(document_id, generation, language, chunk_index);
