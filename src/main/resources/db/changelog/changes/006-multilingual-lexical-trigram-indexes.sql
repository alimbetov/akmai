--liquibase formatted sql

--changeset akmai:006-multilingual-lexical-trigram-indexes
CREATE INDEX IF NOT EXISTS idx_knowledge_search_text_trgm
    ON knowledge_search_projection
    USING GIN (lower(text_content) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_knowledge_search_section_trgm
    ON knowledge_search_projection
    USING GIN (lower(coalesce(section_path, '')) gin_trgm_ops);
