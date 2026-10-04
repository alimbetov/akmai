--liquibase formatted sql

--changeset akmai-greenfield:012-runtime-app-parameters
CREATE TABLE app_parameter (
    parameter_key    VARCHAR(160) PRIMARY KEY,
    parameter_type   VARCHAR(32) NOT NULL,
    parameter_value  VARCHAR(1000) NOT NULL,
    row_version      BIGINT NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by       VARCHAR(128) NOT NULL DEFAULT 'system',
    CONSTRAINT ck_app_parameter_key
        CHECK (
            parameter_key = lower(parameter_key)
            AND parameter_key ~ '^[a-z0-9.-]+$'
        ),
    CONSTRAINT ck_app_parameter_type
        CHECK (parameter_type IN ('BOOLEAN')),
    CONSTRAINT ck_app_parameter_version
        CHECK (row_version >= 0),
    CONSTRAINT ck_app_parameter_boolean_value
        CHECK (
            parameter_type <> 'BOOLEAN'
            OR lower(parameter_value) IN ('true', 'false')
        )
);

--changeset akmai-greenfield:012-runtime-app-parameter-seed
INSERT INTO app_parameter (
    parameter_key,
    parameter_type,
    parameter_value,
    updated_by
)
VALUES
    ('akmai.adaptive-graph.learning-enabled', 'BOOLEAN', 'false', 'liquibase'),
    ('akmai.adaptive-graph.maintenance-enabled', 'BOOLEAN', 'false', 'liquibase'),
    ('akmai.adaptive-graph.shadow-expansion-enabled', 'BOOLEAN', 'false', 'liquibase'),
    ('akmai.adaptive-graph.expansion-enabled', 'BOOLEAN', 'false', 'liquibase'),
    ('akmai.adaptive-graph.competition.enabled', 'BOOLEAN', 'false', 'liquibase')
ON CONFLICT (parameter_key) DO NOTHING;
