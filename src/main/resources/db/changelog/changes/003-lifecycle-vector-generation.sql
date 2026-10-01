--liquibase formatted sql

--changeset akmai:003-lifecycle-vector-generation
CREATE TABLE knowledge_document_vector_generation (
    document_id   VARCHAR(100) NOT NULL,
    generation    BIGINT NOT NULL,
    vector_id     VARCHAR(300) NOT NULL,
    chunk_id      VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, generation, vector_id),
    CONSTRAINT fk_vector_generation_lifecycle
        FOREIGN KEY (document_id)
        REFERENCES knowledge_document_lifecycle(document_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_vector_generation_document_generation
    ON knowledge_document_vector_generation (document_id, generation);
