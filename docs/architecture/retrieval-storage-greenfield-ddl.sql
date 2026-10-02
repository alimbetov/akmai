-- AKMAI greenfield retrieval storage DDL reference.
-- DESIGN ARTIFACT ONLY. Not wired into Liquibase yet.
--
-- Preconditions:
--   * PostgreSQL 17
--   * knowledge_document_generation exists with:
--       PRIMARY KEY (document_id, generation)
--       UNIQUE (document_id, generation, access_level)
--       access_level BIGINT NOT NULL CHECK (access_level > 0)
--   * knowledge_embedding_profile exists and vector_table names are application-controlled.
--
-- The active implementation must be generated from the final benchmarked design rather than
-- applying this file as an additive migration over the current unreleased schema.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE SCHEMA IF NOT EXISTS akmai_vector;
CREATE SCHEMA IF NOT EXISTS akmai_admin;

CREATE TABLE akmai_retrieval_partition_registry (
    access_level   BIGINT PRIMARY KEY,
    provisioned_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_retrieval_partition_access
        CHECK (access_level > 0)
);

CREATE TABLE knowledge_search_projection (
    access_level       BIGINT NOT NULL,
    document_id        VARCHAR(100) NOT NULL,
    generation         BIGINT NOT NULL,
    chunk_id           VARCHAR(100) NOT NULL,
    parent_chunk_id    VARCHAR(100),
    chunk_index        INTEGER NOT NULL,
    text_content       TEXT NOT NULL,
    embedding_text     TEXT NOT NULL,
    language           VARCHAR(32) NOT NULL,
    domain             VARCHAR(50) NOT NULL,
    section_path       TEXT,
    identifiers_json   JSONB NOT NULL DEFAULT '[]'::jsonb,
    references_json    JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    projection_version INTEGER NOT NULL DEFAULT 1,

    search_vector TSVECTOR GENERATED ALWAYS AS (
        to_tsvector(
            'simple',
            coalesce(section_path, '') || ' ' || coalesce(text_content, '')
        )
    ) STORED,

    search_vector_ru TSVECTOR GENERATED ALWAYS AS (
        to_tsvector(
            'russian',
            coalesce(section_path, '') || ' ' || coalesce(text_content, '')
        )
    ) STORED,

    search_vector_en TSVECTOR GENERATED ALWAYS AS (
        to_tsvector(
            'english',
            coalesce(section_path, '') || ' ' || coalesce(text_content, '')
        )
    ) STORED,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    PRIMARY KEY (
        access_level,
        document_id,
        generation,
        chunk_id
    ),

    UNIQUE (
        access_level,
        document_id,
        generation,
        chunk_index
    ),

    FOREIGN KEY (
        document_id,
        generation,
        access_level
    )
    REFERENCES knowledge_document_generation (
        document_id,
        generation,
        access_level
    ),

    CONSTRAINT ck_projection_access_level CHECK (access_level > 0),
    CONSTRAINT ck_projection_generation CHECK (generation > 0),
    CONSTRAINT ck_projection_chunk_index CHECK (chunk_index >= 0)
)
PARTITION BY LIST (access_level);

CREATE INDEX idx_projection_document_generation_chunk_index
    ON knowledge_search_projection (
        document_id,
        generation,
        chunk_index
    );

CREATE INDEX idx_projection_fts_ru
    ON knowledge_search_projection
    USING GIN (search_vector_ru)
    WHERE language = 'ru';

CREATE INDEX idx_projection_fts_en
    ON knowledge_search_projection
    USING GIN (search_vector_en)
    WHERE language = 'en';

CREATE INDEX idx_projection_fts_simple
    ON knowledge_search_projection
    USING GIN (search_vector);

CREATE INDEX idx_projection_text_trgm
    ON knowledge_search_projection
    USING GIN (lower(text_content) gin_trgm_ops);

CREATE INDEX idx_projection_section_trgm
    ON knowledge_search_projection
    USING GIN (lower(coalesce(section_path, '')) gin_trgm_ops);

CREATE TABLE document_identifier (
    access_level     BIGINT NOT NULL,
    id               BIGINT GENERATED ALWAYS AS IDENTITY,
    document_id      VARCHAR(100) NOT NULL,
    generation       BIGINT NOT NULL,
    chunk_id         VARCHAR(100) NOT NULL,
    page_number      INTEGER NOT NULL,
    identifier_type  VARCHAR(50) NOT NULL,
    raw_value        VARCHAR(500) NOT NULL,
    normalized_value VARCHAR(500) NOT NULL,
    context_text     TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    PRIMARY KEY (access_level, id),

    UNIQUE (
        access_level,
        document_id,
        generation,
        chunk_id,
        identifier_type,
        normalized_value
    ),

    FOREIGN KEY (
        document_id,
        generation,
        access_level
    )
    REFERENCES knowledge_document_generation (
        document_id,
        generation,
        access_level
    ),

    CONSTRAINT ck_identifier_access_level CHECK (access_level > 0),
    CONSTRAINT ck_identifier_generation CHECK (generation > 0),
    CONSTRAINT ck_identifier_page_number CHECK (page_number >= 0)
)
PARTITION BY LIST (access_level);

CREATE INDEX idx_identifier_type_exact
    ON document_identifier (
        identifier_type,
        normalized_value,
        created_at DESC
    );

CREATE INDEX idx_identifier_exact
    ON document_identifier (
        normalized_value,
        created_at DESC
    );

CREATE INDEX idx_identifier_type_prefix
    ON document_identifier (
        identifier_type,
        normalized_value text_pattern_ops
    );

CREATE INDEX idx_identifier_trgm
    ON document_identifier
    USING GIN (normalized_value gin_trgm_ops);

CREATE TABLE knowledge_reference_target (
    access_level    BIGINT NOT NULL,
    document_id     VARCHAR(100) NOT NULL,
    generation      BIGINT NOT NULL,
    chunk_id        VARCHAR(100) NOT NULL,
    reference_type  VARCHAR(32) NOT NULL,
    canonical_value VARCHAR(200) NOT NULL,
    raw_value       VARCHAR(500) NOT NULL,
    language        VARCHAR(8) NOT NULL,

    PRIMARY KEY (
        access_level,
        document_id,
        generation,
        reference_type,
        canonical_value
    ),

    FOREIGN KEY (
        document_id,
        generation,
        access_level
    )
    REFERENCES knowledge_document_generation (
        document_id,
        generation,
        access_level
    ),

    CONSTRAINT ck_reference_target_access_level CHECK (access_level > 0),
    CONSTRAINT ck_reference_target_generation CHECK (generation > 0)
)
PARTITION BY LIST (access_level);

CREATE TABLE knowledge_reference_edge (
    access_level       BIGINT NOT NULL,
    document_id        VARCHAR(100) NOT NULL,
    generation         BIGINT NOT NULL,
    source_chunk_id    VARCHAR(100) NOT NULL,
    reference_type     VARCHAR(32) NOT NULL,
    canonical_value    VARCHAR(200) NOT NULL,
    raw_value          VARCHAR(500) NOT NULL,
    language           VARCHAR(8) NOT NULL,
    target_scope       VARCHAR(32) NOT NULL,
    target_document_id VARCHAR(100),

    PRIMARY KEY (
        access_level,
        document_id,
        generation,
        source_chunk_id,
        target_scope,
        reference_type,
        canonical_value,
        raw_value
    ),

    FOREIGN KEY (
        document_id,
        generation,
        access_level
    )
    REFERENCES knowledge_document_generation (
        document_id,
        generation,
        access_level
    ),

    CONSTRAINT ck_reference_edge_access_level CHECK (access_level > 0),
    CONSTRAINT ck_reference_edge_generation CHECK (generation > 0),
    CONSTRAINT ck_reference_target_scope
        CHECK (target_scope IN ('SAME_DOCUMENT', 'EXPLICIT_DOCUMENT'))
)
PARTITION BY LIST (access_level);

-- Add only if the PK is not sufficient on representative corpus.
-- CREATE INDEX idx_reference_edge_same_document
--     ON knowledge_reference_edge (
--         document_id,
--         generation,
--         source_chunk_id,
--         reference_type,
--         canonical_value
--     )
--     WHERE target_scope = 'SAME_DOCUMENT';

CREATE TABLE knowledge_document_vector_generation (
    access_level         BIGINT NOT NULL,
    document_id          VARCHAR(100) NOT NULL,
    generation           BIGINT NOT NULL,
    vector_id            UUID NOT NULL,
    chunk_id             VARCHAR(100) NOT NULL,
    embedding_profile_id VARCHAR(128) NOT NULL,
    physical_id_version  SMALLINT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    PRIMARY KEY (
        access_level,
        document_id,
        generation,
        vector_id
    ),

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
    REFERENCES knowledge_document_generation (
        document_id,
        generation,
        access_level
    ),

    CONSTRAINT ck_vector_generation_access CHECK (access_level > 0),
    CONSTRAINT ck_vector_generation_number CHECK (generation > 0)
)
PARTITION BY LIST (access_level);

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
    v_parent_regclass REGCLASS;
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
      INTO v_parent_regclass;

    IF v_parent_regclass IS NULL THEN
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

    IF p_dimensions IS NULL OR p_dimensions <= 0 OR p_dimensions > 2000 THEN
        RAISE EXCEPTION 'HNSW vector dimensions must be between 1 and 2000';
    END IF;

    PERFORM pg_advisory_xact_lock(
        hashtextextended('akmai:vector-profile:' || p_vector_table, 0)
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

    v_child_name := 'knowledge_search_projection_al_' || p_access_level::TEXT;
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS public.%I
         PARTITION OF public.knowledge_search_projection
         FOR VALUES IN (%L)',
        v_child_name,
        p_access_level
    );

    v_child_name := 'document_identifier_al_' || p_access_level::TEXT;
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS public.%I
         PARTITION OF public.document_identifier
         FOR VALUES IN (%L)',
        v_child_name,
        p_access_level
    );

    v_child_name := 'knowledge_reference_target_al_' || p_access_level::TEXT;
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS public.%I
         PARTITION OF public.knowledge_reference_target
         FOR VALUES IN (%L)',
        v_child_name,
        p_access_level
    );

    v_child_name := 'knowledge_reference_edge_al_' || p_access_level::TEXT;
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS public.%I
         PARTITION OF public.knowledge_reference_edge
         FOR VALUES IN (%L)',
        v_child_name,
        p_access_level
    );

    v_child_name :=
        'knowledge_document_vector_generation_al_' || p_access_level::TEXT;
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

-- Development/bootstrap example only.
-- Production access levels should be provisioned explicitly by deployment configuration.
SELECT akmai_admin.ensure_access_level(1);

-- Recommended lifecycle retrieval helper index (defined after the lifecycle table in the final
-- clean baseline):
--
-- CREATE INDEX idx_lifecycle_active_acl_document
--     ON knowledge_document_lifecycle (access_level, document_id)
--     INCLUDE (published_generation)
--     WHERE retention_status = 'ACTIVE';
