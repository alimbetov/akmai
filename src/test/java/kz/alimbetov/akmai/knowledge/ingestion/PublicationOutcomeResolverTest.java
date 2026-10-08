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

class PublicationOutcomeResolverTest {

    @Test
    void successfulIdempotencyRecordWinsAsCommitted() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PublicationOutcomeResolver resolver = new PublicationOutcomeResolver(jdbc);
        IngestionIdempotencyContext context = context();

        when(jdbc.query(
                org.mockito.ArgumentMatchers.contains("knowledge_ingestion_request"),
                any(RowMapper.class),
                eq(context.key()),
                eq(context.claimId()),
                eq(context.fingerprint())
        )).thenReturn(List.of("SUCCEEDED"));

        assertThat(resolver.resolve("doc-1", 7L, context))
                .isEqualTo(PublicationOutcomeResolver.Outcome.COMMITTED);
    }

    @Test
    void publishedGenerationIsCommittedEvenWithoutIdempotencyContext() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PublicationOutcomeResolver resolver = new PublicationOutcomeResolver(jdbc);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("generation_status")).thenReturn("PUBLISHED");
        when(rs.getTimestamp("published_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-10-08T00:00:00Z")));

        when(jdbc.query(
                org.mockito.ArgumentMatchers.contains("knowledge_document_generation"),
                any(RowMapper.class),
                eq("doc-1"),
                eq(7L)
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<PublicationOutcomeResolver.Outcome> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });

        assertThat(resolver.resolve("doc-1", 7L, null))
                .isEqualTo(PublicationOutcomeResolver.Outcome.COMMITTED);
    }

    @Test
    void retiredGenerationWithPublishedTimestampRemainsCommitted() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PublicationOutcomeResolver resolver = new PublicationOutcomeResolver(jdbc);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("generation_status")).thenReturn("RETIRED");
        when(rs.getTimestamp("published_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-10-08T00:00:00Z")));

        stubGenerationRow(jdbc, rs);

        assertThat(resolver.resolve("doc-1", 7L, null))
                .isEqualTo(PublicationOutcomeResolver.Outcome.COMMITTED);
    }

    @Test
    void supersededFailureIsReportedAsSuperseded() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PublicationOutcomeResolver resolver = new PublicationOutcomeResolver(jdbc);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("generation_status")).thenReturn("FAILED");
        when(rs.getString("failure_code")).thenReturn("SUPERSEDED");

        stubGenerationRow(jdbc, rs);

        assertThat(resolver.resolve("doc-1", 7L, null))
                .isEqualTo(PublicationOutcomeResolver.Outcome.SUPERSEDED);
    }

    @Test
    void stagingOrMissingGenerationIsNotCommitted() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PublicationOutcomeResolver resolver = new PublicationOutcomeResolver(jdbc);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("generation_status")).thenReturn("STAGING");

        stubGenerationRow(jdbc, rs);
        assertThat(resolver.resolve("doc-1", 7L, null))
                .isEqualTo(PublicationOutcomeResolver.Outcome.NOT_COMMITTED);

        JdbcTemplate missingJdbc = mock(JdbcTemplate.class);
        when(missingJdbc.query(
                org.mockito.ArgumentMatchers.contains("knowledge_document_generation"),
                any(RowMapper.class),
                eq("doc-1"),
                eq(7L)
        )).thenReturn(List.of());
        assertThat(new PublicationOutcomeResolver(missingJdbc)
                .resolve("doc-1", 7L, null))
                .isEqualTo(PublicationOutcomeResolver.Outcome.NOT_COMMITTED);
    }

    private void stubGenerationRow(JdbcTemplate jdbc, ResultSet rs) {
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

    private IngestionIdempotencyContext context() {
        return new IngestionIdempotencyContext(
                "key",
                UUID.randomUUID(),
                "fingerprint"
        );
    }
}
