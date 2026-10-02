package kz.alimbetov.akmai.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

class ReadinessIntegrationTest {

    @Test
    void pgvectorHealthIsDownWhenActiveProfileTableIsMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingProfileService profiles = mock(EmbeddingProfileService.class);
        EmbeddingProfile profile = new EmbeddingProfile(
                "ep-test",
                "ollama",
                "model",
                3,
                "COSINE_DISTANCE",
                "tokenizer",
                "fingerprint",
                "akmai_vector",
                "p_deadbeef",
                "NONE",
                (short) 1,
                Instant.parse("2026-10-02T00:00:00Z")
        );

        when(profiles.activeProfile()).thenReturn(profile);
        when(jdbc.queryForObject(
                org.mockito.ArgumentMatchers.contains("pg_extension"),
                org.mockito.ArgumentMatchers.eq(Boolean.class)
        )).thenReturn(true);
        when(jdbc.query(
                org.mockito.ArgumentMatchers.contains("pg_attribute"),
                org.mockito.ArgumentMatchers.any(
                        org.springframework.jdbc.core.RowMapper.class
                ),
                org.mockito.ArgumentMatchers.eq("akmai_vector.p_deadbeef")
        )).thenReturn(List.of());

        var health = new PgVectorHealthIndicator(jdbc, profiles).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("reason", "vector storage dimension mismatch")
                .containsEntry("actualType", "missing");
    }
}
