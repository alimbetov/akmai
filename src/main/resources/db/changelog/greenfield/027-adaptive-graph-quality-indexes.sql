--liquibase formatted sql

--changeset akmai-greenfield:027-adaptive-graph-quality-indexes
CREATE INDEX idx_kca_active_semantic_degree
    ON knowledge_chunk_association (
        access_level,
        source_document_id,
        source_generation,
        source_chunk_id,
        graph_version
    )
    WHERE semantic_similarity IS NOT NULL
      AND band <> 'DECAYED';
