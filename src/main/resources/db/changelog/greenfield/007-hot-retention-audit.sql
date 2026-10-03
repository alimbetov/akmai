--liquibase formatted sql

--changeset akmai-greenfield:007-retired-generation
CREATE TABLE knowledge_retired_generation (
    document_id          VARCHAR(100) NOT NULL,
    generation           BIGINT NOT NULL,
    access_level         BIGINT NOT NULL,
    embedding_profile_id VARCHAR(128) NOT NULL,
    projection_count     INTEGER NOT NULL,
    vector_count         INTEGER NOT NULL,
    content_fingerprint  VARCHAR(64),
    physical_id_version  SMALLINT NOT NULL,
    retention_policy     VARCHAR(32) NOT NULL,
    purge_started_at     TIMESTAMPTZ NOT NULL,
    retired_at           TIMESTAMPTZ,
    purge_after          TIMESTAMPTZ,
    verified_at          TIMESTAMPTZ,
    cleanup_status       VARCHAR(32) NOT NULL DEFAULT 'PURGING',
    cleanup_attempts     INTEGER NOT NULL DEFAULT 0,
    last_error           VARCHAR(1000),

    PRIMARY KEY (document_id, generation, access_level),

    CONSTRAINT ck_retired_generation_access
        CHECK (access_level > 0),
    CONSTRAINT ck_retired_generation_counts
        CHECK (projection_count >= 0 AND vector_count >= 0),
    CONSTRAINT ck_retired_generation_policy
        CHECK (retention_policy IN ('PERMANENT', 'TTL')),
    CONSTRAINT ck_retired_generation_status
        CHECK (cleanup_status IN (
            'PURGING',
            'PURGED',
            'VERIFIED',
            'REPAIR_REQUIRED'
        )),
    CONSTRAINT ck_retired_generation_timestamps
        CHECK (
            (
                cleanup_status = 'PURGING'
                AND retired_at IS NULL
                AND purge_after IS NULL
            )
            OR
            (
                cleanup_status IN (
                    'PURGED',
                    'VERIFIED',
                    'REPAIR_REQUIRED'
                )
                AND retired_at IS NOT NULL
                AND purge_after IS NOT NULL
                AND purge_after > retired_at
            )
        ),
    CONSTRAINT ck_retired_generation_attempts
        CHECK (cleanup_attempts >= 0)
);

CREATE INDEX idx_retired_generation_verify
    ON knowledge_retired_generation(
        retired_at,
        document_id,
        generation,
        access_level
    )
    WHERE cleanup_status = 'PURGED';

CREATE INDEX idx_retired_generation_purge
    ON knowledge_retired_generation(
        purge_after,
        document_id,
        generation,
        access_level
    )
    WHERE cleanup_status = 'VERIFIED';


--changeset akmai-greenfield:007-audit-parent
CREATE TABLE knowledge_audit_event (
    event_at        TIMESTAMPTZ NOT NULL,
    event_id        UUID NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    document_id     VARCHAR(100),
    generation      BIGINT,
    access_level    BIGINT,
    correlation_id  UUID,
    source          VARCHAR(64) NOT NULL,
    details_json    JSONB NOT NULL DEFAULT '{}'::jsonb,

    PRIMARY KEY (event_at, event_id),

    CONSTRAINT ck_audit_access
        CHECK (access_level IS NULL OR access_level > 0),
    CONSTRAINT ck_audit_generation
        CHECK (generation IS NULL OR generation > 0)
)
PARTITION BY RANGE (event_at);


--changeset akmai-greenfield:007-audit-partitions splitStatements:false
CREATE OR REPLACE FUNCTION akmai_admin.ensure_audit_month_partition(
    p_month DATE
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, akmai_admin
AS $$
DECLARE
    v_from DATE;
    v_to DATE;
    v_name TEXT;
BEGIN
    v_from := date_trunc('month', p_month)::date;
    v_to := (v_from + interval '1 month')::date;
    v_name := 'knowledge_audit_event_'
        || to_char(v_from, 'YYYY_MM');

    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS public.%I
         PARTITION OF public.knowledge_audit_event
         FOR VALUES FROM (%L) TO (%L)',
        v_name,
        v_from,
        v_to
    );

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I
         ON public.%I (document_id, generation, event_at DESC)',
        v_name || '_generation',
        v_name
    );

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I
         ON public.%I (event_type, event_at DESC)',
        v_name || '_type',
        v_name
    );
END;
$$;

CREATE OR REPLACE FUNCTION akmai_admin.maintain_audit_partitions()
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, akmai_admin
AS $$
DECLARE
    v_offset INTEGER;
    v_cutoff DATE;
    v_partition RECORD;
BEGIN
    FOR v_offset IN 0..2 LOOP
        PERFORM akmai_admin.ensure_audit_month_partition(
            (date_trunc('month', clock_timestamp())
                + (v_offset * interval '1 month'))::date
        );
    END LOOP;

    v_cutoff := (
        date_trunc('month', clock_timestamp()) - interval '12 months'
    )::date;

    FOR v_partition IN
        SELECT child.relname AS relation_name
        FROM pg_inherits inheritance
        JOIN pg_class parent
          ON parent.oid = inheritance.inhparent
        JOIN pg_class child
          ON child.oid = inheritance.inhrelid
        JOIN pg_namespace namespace
          ON namespace.oid = child.relnamespace
        WHERE parent.oid = 'public.knowledge_audit_event'::regclass
          AND namespace.nspname = 'public'
          AND child.relname ~ '^knowledge_audit_event_[0-9]{4}_[0-9]{2}$'
          AND to_date(
                substring(child.relname from '([0-9]{4}_[0-9]{2})$'),
                'YYYY_MM'
              ) < v_cutoff
    LOOP
        EXECUTE format(
            'DROP TABLE public.%I',
            v_partition.relation_name
        );
    END LOOP;
END;
$$;

SELECT akmai_admin.maintain_audit_partitions();

