package kz.alimbetov.akmai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PublicationOutcomeResolverFailureModelTest {

    @Test
    void publishedStatusWithoutPublishedTimestampIsNotCommitted() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PublicationOutcomeResolver resolver = new PublicationOutcomeResolver(jdbc);
        ResultSet rs = generationRow("PUBLISHED", null, null);
        stubGeneration(jdbc, rs);

        assertThat(resolver.resolve("doc-1", 7L, null))
                .isEqualTo(PublicationOutcomeResolver.Outcome.NOT_COMMITTED);
    }

    @Test
    void cleanedGenerationWithPublicationTimestampRemainsCommitted() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PublicationOutcomeResolver resolver = new PublicationOutcomeResolver(jdbc);
        ResultSet rs = generationRow(
                "CLEANED",
                Timestamp.from(Instant.parse("2026-10-08T00:00:00Z")),
                null
        );
        stubGeneration(jdbc, rs);

        assertThat(resolver.resolve("doc-1", 7L, null))
                .isEqualTo(PublicationOutcomeResolver.Outcome.COMMITTED);
    }

    @Test
    void ordinaryFailedGenerationIsNotMisclassifiedAsSuperseded() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PublicationOutcomeResolver resolver = new PublicationOutcomeResolver(jdbc);
        ResultSet rs = generationRow("FAILED", null, "VECTOR_WRITE_FAILED");
        stubGeneration(jdbc, rs);

        assertThat(resolver.resolve("doc-1", 7L, null))
                .isEqualTo(PublicationOutcomeResolver.Outcome.NOT_COMMITTED);
    }

    @Test
    void nonSucceededIdempotencyFallsBackToGenerationTruth() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PublicationOutcomeResolver resolver = new PublicationOutcomeResolver(jdbc);
        IngestionIdempotencyContext context = new IngestionIdempotencyContext(
                "key",
                UUID.randomUUID(),
                "fingerprint"
        );

        when(jdbc.query(
                org.mockito.ArgumentMatchers.contains("knowledge_ingestion_request"),
                any(RowMapper.class),
                eq(context.key()),
                eq(context.claimId()),
                eq(context.fingerprint())
        )).thenReturn(List.of("IN_PROGRESS"));

        ResultSet rs = generationRow(
                "RETIRED",
                Timestamp.from(Instant.parse("2026-10-08T00:00:00Z")),
                null
        );
        stubGeneration(jdbc, rs);

        assertThat(resolver.resolve("doc-1", 7L, context))
                .isEqualTo(PublicationOutcomeResolver.Outcome.COMMITTED);
    }

    private ResultSet generationRow(
            String status,
            Timestamp publishedAt,
            String failureCode
    ) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("generation_status")).thenReturn(status);
        when(rs.getTimestamp("published_at")).thenReturn(publishedAt);
        when(rs.getString("failure_code")).thenReturn(failureCode);
        return rs;
    }

    private void stubGeneration(JdbcTemplate jdbc, ResultSet rs) {
        when(jdbc.query(
                org.mockito.ArgumentMatchers.contains("knowledge_document_generation"),
                any(RowMapper.class),
                eq("doc-1"),
                eq(7L)
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<PublicationOutcomeResolver.Outcome> mapper = invocation.getArgument(1);
            try {
                return List.of(mapper.mapRow(rs, 0));
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }
}
