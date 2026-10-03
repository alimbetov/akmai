--liquibase formatted sql

--changeset akmai-greenfield:010-adaptive-bounded-maintenance
ALTER TABLE knowledge_chunk_association
    ADD COLUMN last_scored_at TIMESTAMPTZ,
    ADD COLUMN band_changed_at TIMESTAMPTZ
        NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN decayed_at TIMESTAMPTZ,
    ADD COLUMN compaction_required BOOLEAN
        NOT NULL DEFAULT TRUE;

CREATE INDEX knowledge_chunk_association_score_due_idx
    ON knowledge_chunk_association (
        last_scored_at,
        last_reinforced_at
    )
    WHERE band <> 'DECAYED';

CREATE INDEX knowledge_chunk_association_decayed_due_idx
    ON knowledge_chunk_association (
        decayed_at
    )
    WHERE band = 'DECAYED';

CREATE INDEX knowledge_chunk_association_compaction_due_idx
    ON knowledge_chunk_association (
        updated_at,
        source_document_id,
        source_generation,
        source_chunk_id
    )
    WHERE compaction_required;
