--liquibase formatted sql

--changeset akmai-greenfield:015-semantic-memory-runtime-flag
INSERT INTO app_parameter (
    parameter_key,
    parameter_type,
    parameter_value,
    updated_by
)
VALUES (
    'akmai.semantic-memory.ingestion-linking-enabled',
    'BOOLEAN',
    'false',
    'liquibase'
)
ON CONFLICT (parameter_key) DO NOTHING;
