--liquibase formatted sql

--changeset akmai-greenfield:021-policy-rollback-semantics
ALTER TABLE rag_policy_registry
    DROP CONSTRAINT ck_rag_policy_status;

-- Before v1.1 there was no rollback operation: ROLLED_BACK meant only that a
-- newer policy had replaced the approved one. Reclassify that historical state.
UPDATE rag_policy_registry
SET policy_status = 'SUPERSEDED'
WHERE policy_status = 'ROLLED_BACK';

ALTER TABLE rag_policy_registry
    ADD CONSTRAINT ck_rag_policy_status
        CHECK (policy_status IN (
            'CANDIDATE',
            'SHADOW',
            'CANARY',
            'APPROVED',
            'SUPERSEDED',
            'REJECTED',
            'ROLLED_BACK'
        ));
