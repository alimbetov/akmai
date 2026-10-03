--liquibase formatted sql

--changeset akmai-greenfield:006-runtime-seed
INSERT INTO knowledge_embedding_runtime (
    singleton_id,
    migration_status
)
VALUES (
    1,
    'IDLE'
);
