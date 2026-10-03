--liquibase formatted sql

--changeset akmai-greenfield:009-adaptive-query-support-sketch
ALTER TABLE knowledge_chunk_association
    ADD COLUMN query_support_sketch BIT(256)
        NOT NULL DEFAULT 0::bit(256);

ALTER TABLE knowledge_chunk_association
    ADD CONSTRAINT ck_chunk_association_distinct_query_support
        CHECK (
            distinct_query_support >= 0
            AND distinct_query_support <= 256
        );
