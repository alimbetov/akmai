--liquibase formatted sql

--changeset akmai-greenfield:008-adaptive-association-parent
CREATE TABLE knowledge_chunk_association (
    access_level            BIGINT NOT NULL,

    source_document_id      VARCHAR(100) NOT NULL,
    source_generation       BIGINT NOT NULL,
    source_chunk_id         VARCHAR(100) NOT NULL,

    target_document_id      VARCHAR(100) NOT NULL,
    target_generation       BIGINT NOT NULL,
    target_chunk_id         VARCHAR(100) NOT NULL,

    band                    VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE',
    weight                  DOUBLE PRECISION NOT NULL DEFAULT 0,

    support_count           BIGINT NOT NULL DEFAULT 0,
    context_count           BIGINT NOT NULL DEFAULT 0,
    citation_count          BIGINT NOT NULL DEFAULT 0,
    distinct_query_support  BIGINT NOT NULL DEFAULT 0,

    graph_version           INTEGER NOT NULL DEFAULT 1,

    first_seen_at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_seen_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_reinforced_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    PRIMARY KEY (
        access_level,
        source_document_id,
        source_generation,
        source_chunk_id,
        target_document_id,
        target_generation,
        target_chunk_id
    ),

    CONSTRAINT fk_chunk_association_source_generation
        FOREIGN KEY (
            source_document_id,
            source_generation,
            access_level
        )
        REFERENCES knowledge_document_generation (
            document_id,
            generation,
            access_level
        ),

    CONSTRAINT fk_chunk_association_target_generation
        FOREIGN KEY (
            target_document_id,
            target_generation,
            access_level
        )
        REFERENCES knowledge_document_generation (
            document_id,
            generation,
            access_level
        ),

    CONSTRAINT ck_chunk_association_access
        CHECK (access_level > 0),
    CONSTRAINT ck_chunk_association_source_generation
        CHECK (source_generation > 0),
    CONSTRAINT ck_chunk_association_target_generation
        CHECK (target_generation > 0),
    CONSTRAINT ck_chunk_association_not_self
        CHECK (
            source_document_id <> target_document_id
            OR source_generation <> target_generation
            OR source_chunk_id <> target_chunk_id
        ),
    CONSTRAINT ck_chunk_association_band
        CHECK (band IN ('CANDIDATE', 'WARM', 'HOT', 'DECAYED')),
    CONSTRAINT ck_chunk_association_weight
        CHECK (weight >= 0 AND weight <= 1),
    CONSTRAINT ck_chunk_association_counts
        CHECK (
            support_count >= 0
            AND context_count >= 0
            AND citation_count >= 0
            AND distinct_query_support >= 0
        ),
    CONSTRAINT ck_chunk_association_graph_version
        CHECK (graph_version > 0)
)
PARTITION BY LIST (access_level);


--changeset akmai-greenfield:008-adaptive-association-provisioning splitStatements:false
CREATE OR REPLACE FUNCTION akmai_admin.ensure_adaptive_graph_access_partition(
    p_access_level BIGINT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, akmai_admin
AS $$
DECLARE
    v_access_child TEXT;
    v_leaf TEXT;
    v_bucket INTEGER;
    v_index_prefix TEXT;
    v_bucket_count CONSTANT INTEGER := 32;
BEGIN
    IF p_access_level IS NULL OR p_access_level <= 0 THEN
        RAISE EXCEPTION 'access_level must be positive';
    END IF;

    v_access_child :=
        'knowledge_chunk_association_al_' || p_access_level::TEXT;

    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS public.%I
         PARTITION OF public.knowledge_chunk_association
         FOR VALUES IN (%L)
         PARTITION BY HASH (
             source_document_id,
             source_generation,
             source_chunk_id
         )',
        v_access_child,
        p_access_level
    );

    FOR v_bucket IN 0..(v_bucket_count - 1) LOOP
        v_leaf := v_access_child
            || '_h_'
            || lpad(v_bucket::TEXT, 2, '0');

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS public.%I
             PARTITION OF public.%I
             FOR VALUES WITH (MODULUS %s, REMAINDER %s)',
            v_leaf,
            v_access_child,
            v_bucket_count,
            v_bucket
        );
    END LOOP;

    v_index_prefix :=
        'kca_' || substr(
            md5('adaptive:' || p_access_level::TEXT),
            1,
            16
        );

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I
         ON public.%I (
             source_document_id,
             source_generation,
             source_chunk_id,
             band,
             weight DESC,
             last_reinforced_at DESC
         )
         INCLUDE (
             target_document_id,
             target_generation,
             target_chunk_id,
             support_count,
             context_count,
             citation_count,
             distinct_query_support,
             graph_version
         )',
        v_index_prefix || '_read',
        v_access_child
    );

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I
         ON public.%I (
             source_document_id,
             source_generation,
             source_chunk_id,
             band,
             weight,
             last_reinforced_at
         )',
        v_index_prefix || '_maint',
        v_access_child
    );

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I
         ON public.%I (
             target_document_id,
             target_generation,
             target_chunk_id
         )',
        v_index_prefix || '_target',
        v_access_child
    );
END;
$$;


--changeset akmai-greenfield:008-adaptive-association-trigger splitStatements:false
CREATE OR REPLACE FUNCTION akmai_admin.provision_adaptive_graph_acl_trigger()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, akmai_admin
AS $$
BEGIN
    PERFORM akmai_admin.ensure_adaptive_graph_access_partition(
        NEW.access_level
    );
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_provision_adaptive_graph_acl
    ON akmai_retrieval_partition_registry;

CREATE TRIGGER trg_provision_adaptive_graph_acl
AFTER INSERT ON akmai_retrieval_partition_registry
FOR EACH ROW
EXECUTE FUNCTION akmai_admin.provision_adaptive_graph_acl_trigger();


--changeset akmai-greenfield:008-adaptive-association-existing-acls splitStatements:false
DO $$
DECLARE
    v_row RECORD;
BEGIN
    FOR v_row IN
        SELECT access_level
        FROM akmai_retrieval_partition_registry
        ORDER BY access_level
    LOOP
        PERFORM akmai_admin.ensure_adaptive_graph_access_partition(
            v_row.access_level
        );
    END LOOP;
END;
$$;
