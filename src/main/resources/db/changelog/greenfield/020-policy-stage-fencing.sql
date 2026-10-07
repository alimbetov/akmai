--liquibase formatted sql

--changeset akmai-greenfield:020-policy-stage-fencing
WITH ranked AS (
    SELECT policy_type,
           policy_version,
           row_number() OVER (
               PARTITION BY policy_type
               ORDER BY created_at DESC, policy_version DESC
           ) AS rn
    FROM rag_policy_registry
    WHERE policy_status = 'CANARY'
)
UPDATE rag_policy_registry policy
SET policy_status = 'REJECTED',
    decided_at = clock_timestamp()
FROM ranked
WHERE policy.policy_type = ranked.policy_type
  AND policy.policy_version = ranked.policy_version
  AND ranked.rn > 1;

CREATE UNIQUE INDEX uq_rag_policy_single_shadow
    ON rag_policy_registry(policy_type)
    WHERE policy_status = 'SHADOW';

CREATE UNIQUE INDEX uq_rag_policy_single_canary
    ON rag_policy_registry(policy_type)
    WHERE policy_status = 'CANARY';
