--liquibase formatted sql

--changeset akmai-greenfield:022-learning-feedback-dedup
-- Historical API semantics did not prevent multiple idempotency keys from
-- attaching multiple feedback rows to the same RAG request. Keep the earliest
-- signal and make request_id the independent-feedback boundary.
DELETE FROM rag_feedback duplicate
USING rag_feedback keeper
WHERE duplicate.request_id = keeper.request_id
  AND (
        duplicate.created_at > keeper.created_at
        OR (
            duplicate.created_at = keeper.created_at
            AND duplicate.feedback_id::text > keeper.feedback_id::text
        )
      );

CREATE UNIQUE INDEX uq_rag_feedback_request
    ON rag_feedback(request_id);

--changeset akmai-greenfield:022-learning-feedback-trust
ALTER TABLE rag_feedback
    ADD COLUMN trust_class VARCHAR(32) NOT NULL DEFAULT 'USER_UNVERIFIED';

ALTER TABLE rag_feedback
    ADD COLUMN source_fingerprint VARCHAR(64);

ALTER TABLE rag_feedback
    ADD CONSTRAINT ck_rag_feedback_trust_class
        CHECK (trust_class IN ('USER_UNVERIFIED', 'VERIFIED_OPERATOR'));

CREATE INDEX idx_rag_feedback_trust_created
    ON rag_feedback(trust_class, created_at DESC);
