--liquibase formatted sql

--changeset akmai-greenfield:002-retrieval-parents
CREATE TABLE knowledge_search_projection (
    access_level       BIGINT NOT NULL,
    document_id        VARCHAR(100) NOT NULL,
    generation         BIGINT NOT NULL,
    chunk_id           VARCHAR(100) NOT NULL,
    parent_chunk_id    VARCHAR(100),
    chunk_index        INTEGER NOT NULL,
    text_content       TEXT NOT NULL,
    embedding_text     TEXT NOT NULL,
    language           VARCHAR(16) NOT NULL,
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
        language,
        document_id,
        generation,
        chunk_id
    ),

    UNIQUE (
        access_level,
        language,
        document_id,
        generation,
        chunk_index
    ),

    CONSTRAINT fk_projection_generation_acl
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

    CONSTRAINT ck_projection_access_level
        CHECK (access_level > 0),
    CONSTRAINT ck_projection_generation
        CHECK (generation > 0),
    CONSTRAINT ck_projection_language
        CHECK (
            language = lower(language)
            AND language ~ '^[a-z]{2,8}
        CHECK (chunk_index >= 0)
)
PARTITION BY LIST (access_level);

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

    CONSTRAINT fk_identifier_generation_acl
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

    CONSTRAINT ck_identifier_access_level
        CHECK (access_level > 0),
    CONSTRAINT ck_identifier_generation
        CHECK (generation > 0),
    CONSTRAINT ck_identifier_page_number
        CHECK (page_number >= 0)
)
PARTITION BY LIST (access_level);

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

    CONSTRAINT fk_reference_target_generation_acl
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

    CONSTRAINT ck_reference_target_access_level
        CHECK (access_level > 0),
    CONSTRAINT ck_reference_target_generation
        CHECK (generation > 0)
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

    CONSTRAINT fk_reference_edge_generation_acl
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

    CONSTRAINT ck_reference_edge_access_level
        CHECK (access_level > 0),
    CONSTRAINT ck_reference_edge_generation
        CHECK (generation > 0),
    CONSTRAINT ck_reference_target_scope
        CHECK (target_scope IN ('SAME_DOCUMENT', 'EXPLICIT_DOCUMENT'))
)
PARTITION BY LIST (access_level);

CREATE TABLE knowledge_document_vector_generation (
    access_level         BIGINT NOT NULL,
    document_id          VARCHAR(100) NOT NULL,
    generation           BIGINT NOT NULL,
    vector_id            VARCHAR(300) NOT NULL,
    chunk_id             VARCHAR(200) NOT NULL,
    embedding_profile_id VARCHAR(128),
    physical_id_version  SMALLINT NOT NULL DEFAULT 2,
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

    CONSTRAINT fk_vector_generation_acl
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

    CONSTRAINT fk_vector_generation_profile
        FOREIGN KEY (embedding_profile_id)
        REFERENCES knowledge_embedding_profile(profile_id),

    CONSTRAINT ck_vector_generation_access
        CHECK (access_level > 0),
    CONSTRAINT ck_vector_generation_number
        CHECK (generation > 0)
)
PARTITION BY LIST (access_level);

        ),
    CONSTRAINT ck_projection_chunk_index
        CHECK (chunk_index >= 0)
)
PARTITION BY LIST (access_level);

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

    CONSTRAINT fk_identifier_generation_acl
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

    CONSTRAINT ck_identifier_access_level
        CHECK (access_level > 0),
    CONSTRAINT ck_identifier_generation
        CHECK (generation > 0),
    CONSTRAINT ck_identifier_page_number
        CHECK (page_number >= 0)
)
PARTITION BY LIST (access_level);

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

    CONSTRAINT fk_reference_target_generation_acl
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

    CONSTRAINT ck_reference_target_access_level
        CHECK (access_level > 0),
    CONSTRAINT ck_reference_target_generation
        CHECK (generation > 0)
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

    CONSTRAINT fk_reference_edge_generation_acl
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

    CONSTRAINT ck_reference_edge_access_level
        CHECK (access_level > 0),
    CONSTRAINT ck_reference_edge_generation
        CHECK (generation > 0),
    CONSTRAINT ck_reference_target_scope
        CHECK (target_scope IN ('SAME_DOCUMENT', 'EXPLICIT_DOCUMENT'))
)
PARTITION BY LIST (access_level);

CREATE TABLE knowledge_document_vector_generation (
    access_level         BIGINT NOT NULL,
    document_id          VARCHAR(100) NOT NULL,
    generation           BIGINT NOT NULL,
    vector_id            VARCHAR(300) NOT NULL,
    chunk_id             VARCHAR(200) NOT NULL,
    embedding_profile_id VARCHAR(128),
    physical_id_version  SMALLINT NOT NULL DEFAULT 2,
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

    CONSTRAINT fk_vector_generation_acl
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

    CONSTRAINT fk_vector_generation_profile
        FOREIGN KEY (embedding_profile_id)
        REFERENCES knowledge_embedding_profile(profile_id),

    CONSTRAINT ck_vector_generation_access
        CHECK (access_level > 0),
    CONSTRAINT ck_vector_generation_number
        CHECK (generation > 0)
)
PARTITION BY LIST (access_level);
