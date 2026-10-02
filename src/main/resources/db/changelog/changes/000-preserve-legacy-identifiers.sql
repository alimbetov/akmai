--liquibase formatted sql

--changeset akmai:000-preserve-legacy-identifiers-table
CREATE TABLE IF NOT EXISTS legacy_document_identifier_snapshot (
    snapshot_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    row_data JSONB NOT NULL
);

--changeset akmai:000-preserve-legacy-identifiers-copy splitStatements:false
DO $$
BEGIN
    IF to_regclass('public.document_identifier') IS NOT NULL THEN
        INSERT INTO legacy_document_identifier_snapshot (row_data)
        SELECT to_jsonb(source_row)
        FROM document_identifier source_row
        WHERE NOT EXISTS (
            SELECT 1
            FROM legacy_document_identifier_snapshot snapshot
            WHERE snapshot.row_data = to_jsonb(source_row)
        );
    END IF;
END
$$;
