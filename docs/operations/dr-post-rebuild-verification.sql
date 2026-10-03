-- Post-rebuild verification for a restored or regenerated AkmAI database.
-- Every query must return zero rows (or zero count) unless stated otherwise.

-- 1. READY/ACTIVE lifecycle rows must point at a PUBLISHED generation.
SELECT
    lifecycle.document_id,
    lifecycle.published_generation,
    lifecycle.access_level
FROM knowledge_document_lifecycle lifecycle
LEFT JOIN knowledge_document_generation generation
  ON generation.document_id = lifecycle.document_id
 AND generation.generation = lifecycle.published_generation
 AND generation.access_level = lifecycle.access_level
 AND generation.generation_status = 'PUBLISHED'
WHERE lifecycle.lifecycle_status = 'READY'
  AND lifecycle.retention_status = 'ACTIVE'
  AND (
      lifecycle.published_generation IS NULL
      OR generation.document_id IS NULL
  );

-- 2. Published chunk_count must match the searchable projection count.
SELECT
    generation.document_id,
    generation.generation,
    generation.access_level,
    generation.chunk_count,
    count(projection.chunk_id) AS projection_count
FROM knowledge_document_generation generation
LEFT JOIN knowledge_search_projection projection
  ON projection.document_id = generation.document_id
 AND projection.generation = generation.generation
 AND projection.access_level = generation.access_level
WHERE generation.generation_status = 'PUBLISHED'
GROUP BY
    generation.document_id,
    generation.generation,
    generation.access_level,
    generation.chunk_count
HAVING generation.chunk_count IS NULL
    OR generation.chunk_count <> count(projection.chunk_id);

-- 3. Published chunk_count must match the vector manifest count.
SELECT
    generation.document_id,
    generation.generation,
    generation.access_level,
    generation.chunk_count,
    count(manifest.vector_id) AS vector_manifest_count
FROM knowledge_document_generation generation
LEFT JOIN knowledge_document_vector_generation manifest
  ON manifest.document_id = generation.document_id
 AND manifest.generation = generation.generation
 AND manifest.access_level = generation.access_level
WHERE generation.generation_status = 'PUBLISHED'
GROUP BY
    generation.document_id,
    generation.generation,
    generation.access_level,
    generation.chunk_count
HAVING generation.chunk_count IS NULL
    OR generation.chunk_count <> count(manifest.vector_id);

-- 4. Published generations must use the active embedding profile.
SELECT
    generation.document_id,
    generation.generation,
    generation.embedding_profile_id,
    runtime.active_profile_id
FROM knowledge_document_generation generation
CROSS JOIN knowledge_embedding_runtime runtime
WHERE generation.generation_status = 'PUBLISHED'
  AND runtime.singleton_id = 1
  AND generation.embedding_profile_id IS DISTINCT FROM runtime.active_profile_id;

-- 5. No payload may remain for generations proven PURGED/VERIFIED.
SELECT
    tombstone.document_id,
    tombstone.generation,
    tombstone.access_level,
    tombstone.cleanup_status,
    count(DISTINCT projection.chunk_id) AS projection_rows,
    count(DISTINCT manifest.vector_id) AS vector_manifest_rows
FROM knowledge_retired_generation tombstone
LEFT JOIN knowledge_search_projection projection
  ON projection.document_id = tombstone.document_id
 AND projection.generation = tombstone.generation
 AND projection.access_level = tombstone.access_level
LEFT JOIN knowledge_document_vector_generation manifest
  ON manifest.document_id = tombstone.document_id
 AND manifest.generation = tombstone.generation
 AND manifest.access_level = tombstone.access_level
WHERE tombstone.cleanup_status IN ('PURGED', 'VERIFIED')
GROUP BY
    tombstone.document_id,
    tombstone.generation,
    tombstone.access_level,
    tombstone.cleanup_status
HAVING count(DISTINCT projection.chunk_id) > 0
    OR count(DISTINCT manifest.vector_id) > 0;

-- 6. Informational counts to record in the rehearsal report.
SELECT
    count(*) FILTER (
        WHERE lifecycle_status = 'READY'
          AND retention_status = 'ACTIVE'
    ) AS active_documents,
    count(*) FILTER (
        WHERE lifecycle_status = 'DELETED'
    ) AS deleted_documents
FROM knowledge_document_lifecycle;

SELECT
    generation_status,
    count(*) AS generations
FROM knowledge_document_generation
GROUP BY generation_status
ORDER BY generation_status;
