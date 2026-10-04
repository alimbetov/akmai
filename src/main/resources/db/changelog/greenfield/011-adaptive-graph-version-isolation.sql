--liquibase formatted sql

--changeset akmai-greenfield:011-adaptive-graph-version-isolation
ALTER TABLE knowledge_chunk_association
    DROP CONSTRAINT knowledge_chunk_association_pkey;

ALTER TABLE knowledge_chunk_association
    ADD CONSTRAINT knowledge_chunk_association_pkey
    PRIMARY KEY (
        access_level,
        source_document_id,
        source_generation,
        source_chunk_id,
        target_document_id,
        target_generation,
        target_chunk_id,
        graph_version
    );
