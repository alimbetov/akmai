--liquibase formatted sql

--changeset akmai-greenfield:019-policy-shadow-lifecycle
ALTER TABLE rag_policy_registry
    DROP CONSTRAINT ck_rag_policy_status;

ALTER TABLE rag_policy_registry
    ADD CONSTRAINT ck_rag_policy_status
        CHECK (policy_status IN (
            'CANDIDATE',
            'SHADOW',
            'CANARY',
            'APPROVED',
            'REJECTED',
            'ROLLED_BACK'
        ));
