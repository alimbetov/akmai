--liquibase formatted sql

--changeset akmai-greenfield:030-async-ingestion-source-version-identity
DROP INDEX IF EXISTS ix_knowledge_ingestion_job_source;

CREATE UNIQUE INDEX uq_knowledge_ingestion_job_source_version
    ON knowledge_ingestion_job (file_id, source_version)
    WHERE file_id IS NOT NULL;
