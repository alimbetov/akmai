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

--changeset akmai-greenfield:006-access-level-1
SELECT akmai_admin.ensure_access_level(1);
