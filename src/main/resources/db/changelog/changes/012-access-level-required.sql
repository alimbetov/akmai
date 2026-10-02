--liquibase formatted sql

--changeset akmai:012-access-level-required
ALTER TABLE knowledge_document_lifecycle
    ALTER COLUMN access_level DROP DEFAULT;

ALTER TABLE knowledge_document_generation
    ALTER COLUMN access_level DROP DEFAULT;
