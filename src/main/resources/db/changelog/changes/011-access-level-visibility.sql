--liquibase formatted sql

--changeset akmai:011-access-level-visibility
ALTER TABLE knowledge_document_lifecycle
    ADD COLUMN IF NOT EXISTS access_level BIGINT;

UPDATE knowledge_document_lifecycle
SET access_level = 0
WHERE access_level IS NULL;

ALTER TABLE knowledge_document_lifecycle
    ALTER COLUMN access_level SET NOT NULL;

ALTER TABLE knowledge_document_lifecycle
    ADD CONSTRAINT ck_knowledge_lifecycle_access_level
        CHECK (access_level >= 0);

ALTER TABLE knowledge_document_generation
    ADD COLUMN IF NOT EXISTS access_level BIGINT;

UPDATE knowledge_document_generation g
SET access_level = l.access_level
FROM knowledge_document_lifecycle l
WHERE l.document_id = g.document_id
  AND g.access_level IS NULL;

UPDATE knowledge_document_generation
SET access_level = 0
WHERE access_level IS NULL;

ALTER TABLE knowledge_document_generation
    ALTER COLUMN access_level SET NOT NULL;

ALTER TABLE knowledge_document_generation
    ADD CONSTRAINT ck_document_generation_access_level
        CHECK (access_level >= 0);

CREATE INDEX IF NOT EXISTS idx_knowledge_lifecycle_access_visibility
    ON knowledge_document_lifecycle (
        access_level, retention_status, document_id, published_generation
    )
    WHERE retention_status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_document_generation_access
    ON knowledge_document_generation (
        document_id, generation, access_level
    );
