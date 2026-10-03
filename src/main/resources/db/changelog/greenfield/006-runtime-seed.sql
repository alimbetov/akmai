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

--changeset akmai-greenfield:006-supported-languages
INSERT INTO akmai_supported_language (language_code)
VALUES
    ('kk'),
    ('ru'),
    ('en'),
    ('zh'),
    ('de'),
    ('fr'),
    ('es'),
    ('pt'),
    ('it'),
    ('tr'),
    ('el'),
    ('unknown');

--changeset akmai-greenfield:006-access-levels-1-10 splitStatements:false
DO $$
DECLARE
    v_access_level BIGINT;
BEGIN
    FOR v_access_level IN 1..10 LOOP
        PERFORM akmai_admin.ensure_access_level(v_access_level);
    END LOOP;
END;
$$;
