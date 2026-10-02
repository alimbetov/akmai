--liquibase formatted sql

--changeset akmai:009-restore-legacy-identifiers
INSERT INTO document_identifier (
    document_id,
    generation,
    chunk_id,
    page_number,
    identifier_type,
    raw_value,
    normalized_value,
    context_text,
    created_at
)
SELECT snapshot.row_data->>'document_id',
       COALESCE(l.published_generation, l.generation, 1),
       snapshot.row_data->>'chunk_id',
       COALESCE(NULLIF(snapshot.row_data->>'page_number', '')::integer, 0),
       snapshot.row_data->>'identifier_type',
       snapshot.row_data->>'raw_value',
       snapshot.row_data->>'normalized_value',
       snapshot.row_data->>'context_text',
       COALESCE(
           NULLIF(snapshot.row_data->>'created_at', '')::timestamptz,
           clock_timestamp()
       )
FROM legacy_document_identifier_snapshot snapshot
JOIN knowledge_document_lifecycle l
  ON l.document_id = snapshot.row_data->>'document_id'
WHERE jsonb_exists(snapshot.row_data, 'document_id')
  AND jsonb_exists(snapshot.row_data, 'chunk_id')
  AND jsonb_exists(snapshot.row_data, 'identifier_type')
  AND jsonb_exists(snapshot.row_data, 'raw_value')
  AND jsonb_exists(snapshot.row_data, 'normalized_value')
ON CONFLICT (
    document_id, generation, chunk_id, identifier_type, normalized_value
) DO NOTHING;

CREATE TABLE IF NOT EXISTS knowledge_legacy_reconciliation (
    reconciliation_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type VARCHAR(64) NOT NULL,
    entity_key VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    reason VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    resolved_at TIMESTAMPTZ
);

INSERT INTO knowledge_legacy_reconciliation (
    entity_type,
    entity_key,
    payload,
    reason
)
SELECT 'DOCUMENT_IDENTIFIER',
       COALESCE(snapshot.row_data->>'document_id', '<unknown>'),
       snapshot.row_data,
       'LEGACY_IDENTIFIER_WITHOUT_LIFECYCLE'
FROM legacy_document_identifier_snapshot snapshot
LEFT JOIN knowledge_document_lifecycle l
  ON l.document_id = snapshot.row_data->>'document_id'
WHERE l.document_id IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM knowledge_legacy_reconciliation existing
      WHERE existing.entity_type = 'DOCUMENT_IDENTIFIER'
        AND existing.payload = snapshot.row_data
  );
