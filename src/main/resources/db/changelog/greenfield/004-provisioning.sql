--liquibase formatted sql

--changeset akmai-greenfield:004-vector-access-partition splitStatements:false
CREATE OR REPLACE FUNCTION akmai_admin.ensure_vector_access_partition(
    p_vector_table TEXT,
    p_access_level BIGINT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, akmai_vector, akmai_admin
AS $$
DECLARE
    v_child_name TEXT;
    v_parent REGCLASS;
BEGIN
    IF p_access_level IS NULL OR p_access_level <= 0 THEN
        RAISE EXCEPTION 'access_level must be positive';
    END IF;

    IF p_vector_table IS NULL
       OR p_vector_table !~ '^[a-z_][a-z0-9_]*$'
       OR length(p_vector_table) > 32 THEN
        RAISE EXCEPTION 'invalid vector table name: %', p_vector_table;
    END IF;

    SELECT to_regclass(format('akmai_vector.%I', p_vector_table))
      INTO v_parent;

    IF v_parent IS NULL THEN
        RAISE EXCEPTION 'vector parent does not exist: %', p_vector_table;
    END IF;

    v_child_name := p_vector_table || '_al_' || p_access_level::TEXT;

    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS akmai_vector.%I
         PARTITION OF akmai_vector.%I
         FOR VALUES IN (%L)',
        v_child_name,
        p_vector_table,
        p_access_level
    );
END;
$$;

--changeset akmai-greenfield:004-vector-profile-storage splitStatements:false
CREATE OR REPLACE FUNCTION akmai_admin.ensure_vector_profile_storage(
    p_vector_table TEXT,
    p_dimensions INTEGER
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, akmai_vector, akmai_admin
AS $$
DECLARE
    v_hnsw_index TEXT;
    v_row RECORD;
BEGIN
    IF p_vector_table IS NULL
       OR p_vector_table !~ '^[a-z_][a-z0-9_]*$'
       OR length(p_vector_table) > 32 THEN
        RAISE EXCEPTION 'invalid vector table name: %', p_vector_table;
    END IF;

    IF p_dimensions IS NULL
       OR p_dimensions <= 0
       OR p_dimensions > 2000 THEN
        RAISE EXCEPTION
            'HNSW vector dimensions must be between 1 and 2000';
    END IF;

    PERFORM pg_advisory_xact_lock(
        hashtextextended(
            'akmai:vector-profile:' || p_vector_table,
            0
        )
    );

    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS akmai_vector.%I (
            access_level BIGINT NOT NULL,
            document_id VARCHAR(100) NOT NULL,
            generation BIGINT NOT NULL,
            chunk_id VARCHAR(100) NOT NULL,
            id UUID NOT NULL,
            content TEXT NOT NULL,
            metadata JSONB NOT NULL DEFAULT ''{}''::jsonb,
            embedding VECTOR(%s) NOT NULL,

            PRIMARY KEY (access_level, id),

            UNIQUE (
                access_level,
                document_id,
                generation,
                chunk_id
            ),

            FOREIGN KEY (
                document_id,
                generation,
                access_level
            )
            REFERENCES public.knowledge_document_generation (
                document_id,
                generation,
                access_level
            ),

            CHECK (access_level > 0),
            CHECK (generation > 0)
        )
        PARTITION BY LIST (access_level)',
        p_vector_table,
        p_dimensions
    );

    v_hnsw_index := p_vector_table || '_embedding_hnsw';

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I
         ON akmai_vector.%I
         USING HNSW (embedding vector_cosine_ops)',
        v_hnsw_index,
        p_vector_table
    );

    FOR v_row IN
        SELECT access_level
        FROM public.akmai_retrieval_partition_registry
        ORDER BY access_level
    LOOP
        PERFORM akmai_admin.ensure_vector_access_partition(
            p_vector_table,
            v_row.access_level
        );
    END LOOP;
END;
$$;

--changeset akmai-greenfield:004-access-level splitStatements:false
CREATE OR REPLACE FUNCTION akmai_admin.ensure_access_level(
    p_access_level BIGINT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, akmai_vector, akmai_admin
AS $$
DECLARE
    v_child_name TEXT;
    v_profile RECORD;
BEGIN
    IF p_access_level IS NULL OR p_access_level <= 0 THEN
        RAISE EXCEPTION 'access_level must be positive';
    END IF;

    PERFORM pg_advisory_xact_lock(
        hashtextextended(
            'akmai:retrieval-access:' || p_access_level::TEXT,
            0
        )
    );

    INSERT INTO public.akmai_retrieval_partition_registry (
        access_level
    )
    VALUES (p_access_level)
    ON CONFLICT (access_level) DO NOTHING;

    v_child_name :=
        'knowledge_search_projection_al_' || p_access_level::TEXT;
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS public.%I
         PARTITION OF public.knowledge_search_projection
         FOR VALUES IN (%L)',
        v_child_name,
        p_access_level
    );

    v_child_name :=
        'document_identifier_al_' || p_access_level::TEXT;
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS public.%I
         PARTITION OF public.document_identifier
         FOR VALUES IN (%L)',
        v_child_name,
        p_access_level
    );

    v_child_name :=
        'knowledge_reference_target_al_' || p_access_level::TEXT;
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS public.%I
         PARTITION OF public.knowledge_reference_target
         FOR VALUES IN (%L)',
        v_child_name,
        p_access_level
    );

    v_child_name :=
        'knowledge_reference_edge_al_' || p_access_level::TEXT;
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS public.%I
         PARTITION OF public.knowledge_reference_edge
         FOR VALUES IN (%L)',
        v_child_name,
        p_access_level
    );

    v_child_name :=
        'knowledge_document_vector_generation_al_'
        || p_access_level::TEXT;
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS public.%I
         PARTITION OF public.knowledge_document_vector_generation
         FOR VALUES IN (%L)',
        v_child_name,
        p_access_level
    );

    FOR v_profile IN
        SELECT vector_table
        FROM public.knowledge_embedding_profile
        WHERE vector_schema = 'akmai_vector'
        ORDER BY vector_table
    LOOP
        IF to_regclass(
            format('akmai_vector.%I', v_profile.vector_table)
        ) IS NOT NULL THEN
            PERFORM akmai_admin.ensure_vector_access_partition(
                v_profile.vector_table,
                p_access_level
            );
        END IF;
    END LOOP;
END;
$$;
