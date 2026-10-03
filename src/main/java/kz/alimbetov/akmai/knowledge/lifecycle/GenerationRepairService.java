package kz.alimbetov.akmai.knowledge.lifecycle;

import kz.alimbetov.akmai.config.ReconciliationProperties;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GenerationRepairService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProfileStorageManager storageManager;
    private final ReconciliationProperties properties;
    private final TransactionTemplate transactionTemplate;

    public GenerationRepairService(
            JdbcTemplate jdbcTemplate,
            EmbeddingProfileStorageManager storageManager,
            ReconciliationProperties properties,
            @Qualifier("repairTransactionTemplate")
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageManager = storageManager;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    public RepairOutcome repair(
            EmbeddingProfile profile,
            GenerationIdentity identity
    ) {
        long deletedRows = 0L;
        int batches = 0;

        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            Integer deleted = transactionTemplate.execute(status ->
                    deletePass(profile, identity)
            );
            int removed = deleted == null ? 0 : deleted;
            batches++;
            deletedRows += removed;

            if (removed == 0) {
                return new RepairOutcome(
                        deletedRows,
                        batches,
                        false
                );
            }
        }

        return new RepairOutcome(
                deletedRows,
                batches,
                true
        );
    }

    private int deletePass(
            EmbeddingProfile profile,
            GenerationIdentity identity
    ) {
        int batchSize = properties.batchSize();
        int deleted = 0;

        deleted += deleteBatch(
                storageManager.qualified(profile),
                identity,
                batchSize
        );
        deleted += deleteAssociationBatch(
                identity,
                batchSize
        );
        deleted += deleteBatch(
                "knowledge_reference_edge",
                identity,
                batchSize
        );
        deleted += deleteBatch(
                "knowledge_reference_target",
                identity,
                batchSize
        );
        deleted += deleteBatch(
                "document_identifier",
                identity,
                batchSize
        );
        deleted += deleteBatch(
                "knowledge_search_projection",
                identity,
                batchSize
        );
        deleted += deleteBatch(
                "knowledge_document_vector_generation",
                identity,
                batchSize
        );

        return deleted;
    }

    private int deleteAssociationBatch(
            GenerationIdentity identity,
            int batchSize
    ) {
        return jdbcTemplate.update(
                """
                WITH batch AS (
                    SELECT access_level,
                           source_document_id,
                           source_generation,
                           source_chunk_id,
                           target_document_id,
                           target_generation,
                           target_chunk_id
                    FROM knowledge_chunk_association
                    WHERE access_level = ?
                      AND (
                          (
                              source_document_id = ?
                              AND source_generation = ?
                          )
                          OR
                          (
                              target_document_id = ?
                              AND target_generation = ?
                          )
                      )
                    ORDER BY source_document_id,
                             source_generation,
                             source_chunk_id,
                             target_document_id,
                             target_generation,
                             target_chunk_id
                    LIMIT ?
                )
                DELETE FROM knowledge_chunk_association target
                USING batch
                WHERE target.access_level = batch.access_level
                  AND (
                      (
                          target.source_document_id =
                              batch.source_document_id
                          AND target.source_generation =
                              batch.source_generation
                          AND target.source_chunk_id =
                              batch.source_chunk_id
                          AND target.target_document_id =
                              batch.target_document_id
                          AND target.target_generation =
                              batch.target_generation
                          AND target.target_chunk_id =
                              batch.target_chunk_id
                      )
                      OR
                      (
                          target.source_document_id =
                              batch.target_document_id
                          AND target.source_generation =
                              batch.target_generation
                          AND target.source_chunk_id =
                              batch.target_chunk_id
                          AND target.target_document_id =
                              batch.source_document_id
                          AND target.target_generation =
                              batch.source_generation
                          AND target.target_chunk_id =
                              batch.source_chunk_id
                      )
                  )
                """,
                identity.accessLevel(),
                identity.documentId(),
                identity.generation(),
                identity.documentId(),
                identity.generation(),
                batchSize
        );
    }

    private int deleteBatch(
            String table,
            GenerationIdentity identity,
            int batchSize
    ) {
        return jdbcTemplate.update(
                """
                WITH batch AS (
                    SELECT tableoid, ctid
                    FROM %s
                    WHERE access_level = ?
                      AND document_id = ?
                      AND generation = ?
                    LIMIT ?
                )
                DELETE FROM %s target
                USING batch
                WHERE target.tableoid = batch.tableoid
                  AND target.ctid = batch.ctid
                """.formatted(table, table),
                identity.accessLevel(),
                identity.documentId(),
                identity.generation(),
                batchSize
        );
    }

    public record RepairOutcome(
            long deletedRows,
            int batches,
            boolean batchLimitReached
    ) {
    }
}
