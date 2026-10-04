--liquibase formatted sql

--changeset akmai-greenfield:013-semantic-concept-partition-index-function splitStatements:false
CREATE OR REPLACE FUNCTION akmai_admin.ensure_projection_language_partition(
    p_access_level BIGINT,
    p_language TEXT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, akmai_admin
AS $$
DECLARE
    v_access_child TEXT;
    v_language_child TEXT;
    v_index_prefix TEXT;
BEGIN
    IF p_access_level IS NULL OR p_access_level <= 0 THEN
        RAISE EXCEPTION 'access_level must be positive';
    END IF;

    IF p_language IS NULL
       OR p_language !~ '^[a-z]{2,8}$' THEN
        RAISE EXCEPTION 'invalid language code: %', p_language;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.akmai_supported_language
        WHERE language_code = p_language
          AND enabled
    ) THEN
        RAISE EXCEPTION 'unsupported language code: %', p_language;
    END IF;

    v_access_child :=
        'knowledge_search_projection_al_' || p_access_level::TEXT;
    v_language_child :=
        v_access_child || '_lang_' || p_language;
    v_index_prefix :=
        'ksp_' || p_access_level::TEXT || '_' || p_language;

    IF to_regclass(format('public.%I', v_access_child)) IS NULL THEN
        RAISE EXCEPTION
            'projection access partition does not exist: %',
            v_access_child;
    END IF;

    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS public.%I
         PARTITION OF public.%I
         FOR VALUES IN (%L)',
        v_language_child,
        v_access_child,
        p_language
    );

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I
         ON public.%I (
             document_id,
             generation,
             chunk_index
         )',
        v_index_prefix || '_doc',
        v_language_child
    );

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I
         ON public.%I
         USING GIN (search_vector)',
        v_index_prefix || '_fts',
        v_language_child
    );

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I
         ON public.%I
         USING GIN (lower(text_content) gin_trgm_ops)',
        v_index_prefix || '_txt',
        v_language_child
    );

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I
         ON public.%I
         USING GIN (
             lower(coalesce(section_path, '''')) gin_trgm_ops
         )',
        v_index_prefix || '_sec',
        v_language_child
    );

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I
         ON public.%I
         USING GIN ((metadata_json -> ''semanticConcepts''))',
        v_index_prefix || '_semc',
        v_language_child
    );

    IF p_language = 'ru' THEN
        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS %I
             ON public.%I
             USING GIN (search_vector_ru)',
            v_index_prefix || '_ru',
            v_language_child
        );
    ELSIF p_language = 'en' THEN
        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS %I
             ON public.%I
             USING GIN (search_vector_en)',
            v_index_prefix || '_en',
            v_language_child
        );
    END IF;
END;
$$;

--changeset akmai-greenfield:013-semantic-concept-partition-index-backfill splitStatements:false
DO $$
DECLARE
    v_scope RECORD;
BEGIN
    FOR v_scope IN
        SELECT r.access_level, l.language_code
        FROM public.akmai_retrieval_partition_registry r
        CROSS JOIN public.akmai_supported_language l
        WHERE l.enabled
        ORDER BY r.access_level, l.language_code
    LOOP
        PERFORM akmai_admin.ensure_projection_language_partition(
            v_scope.access_level,
            v_scope.language_code
        );
    END LOOP;
END;
$$;
