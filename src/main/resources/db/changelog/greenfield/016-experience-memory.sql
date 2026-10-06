--liquibase formatted sql

--changeset akmai-greenfield:016-experience-memory
CREATE TABLE rag_experience_memory (
    id                   UUID PRIMARY KEY,
    embedding_profile_id VARCHAR(128) NOT NULL,
    scope_key            VARCHAR(512) NOT NULL,
    normalized_question  TEXT NOT NULL,
    grounded_answer      TEXT NOT NULL,
    embedding            vector NOT NULL,
    observed_at          TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT fk_experience_memory_profile
        FOREIGN KEY (embedding_profile_id)
        REFERENCES knowledge_embedding_profile(profile_id),
    CONSTRAINT ck_experience_memory_scope
        CHECK (scope_key ~ '^[1-9][0-9]*(,[1-9][0-9]*)*$')
);

CREATE TABLE rag_experience_memory_source (
    memory_id    UUID NOT NULL,
    access_level BIGINT NOT NULL,
    document_id  VARCHAR(100) NOT NULL,
    generation   BIGINT NOT NULL,
    chunk_id     VARCHAR(100) NOT NULL,

    PRIMARY KEY (
        memory_id,
        access_level,
        document_id,
        generation,
        chunk_id
    ),

    CONSTRAINT fk_experience_memory_source_memory
        FOREIGN KEY (memory_id)
        REFERENCES rag_experience_memory(id)
        ON DELETE CASCADE,
    CONSTRAINT ck_experience_memory_source_access
        CHECK (access_level > 0),
    CONSTRAINT ck_experience_memory_source_generation
        CHECK (generation > 0)
);

-- Deliberately no FK from source identity to knowledge_document_generation.
-- Experience memory must not block retirement/purge of old corpus generations;
-- read-time generation fencing treats a missing/non-PUBLISHED generation as stale.
CREATE INDEX ix_experience_memory_scope_profile_time
    ON rag_experience_memory (
        embedding_profile_id,
        scope_key,
        observed_at DESC
    );

CREATE INDEX ix_experience_memory_source_generation
    ON rag_experience_memory_source (
        access_level,
        document_id,
        generation
    );
