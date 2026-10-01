--liquibase formatted sql

--changeset akmai:004-lifecycle-ingestion-publication
ALTER TABLE knowledge_document_lifecycle
    DROP CONSTRAINT ck_knowledge_lifecycle_status;

ALTER TABLE knowledge_document_lifecycle
    ADD CONSTRAINT ck_knowledge_lifecycle_status
        CHECK (lifecycle_status IN (
            'INGESTING', 'READY', 'DELETE_PENDING', 'DELETING', 'DELETE_FAILED', 'DELETED'
        ));
