package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemporalAuthorityFilterTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-04T00:00:00Z"),
            ZoneOffset.UTC
    );

    private final TemporalAuthorityFilter filter =
            new TemporalAuthorityFilter(CLOCK);

    @Test
    void keepsUnversionedKnowledgeForBackwardCompatibility() {
        assertThat(filter.filter(List.of(hit(Map.of())))).hasSize(1);
    }

    @Test
    void keepsEffectiveCurrentDocument() {
        assertThat(filter.filter(List.of(hit(Map.of(
                "documentStatus", "ACTIVE",
                "effectiveFrom", "2026-01-01",
                "effectiveTo", "2026-12-31"
        ))))).hasSize(1);
    }

    @Test
    void rejectsSupersededDocument() {
        assertThat(filter.filter(List.of(hit(Map.of(
                "documentStatus", "SUPERSEDED"
        ))))).isEmpty();
    }

    @Test
    void rejectsFutureDocument() {
        assertThat(filter.filter(List.of(hit(Map.of(
                "documentStatus", "ACTIVE",
                "effectiveFrom", "2026-10-05"
        ))))).isEmpty();
    }

    @Test
    void rejectsExpiredDocument() {
        assertThat(filter.filter(List.of(hit(Map.of(
                "documentStatus", "ACTIVE",
                "effectiveTo", "2026-10-03"
        ))))).isEmpty();
    }

    @Test
    void rejectsMalformedExplicitAuthorityMetadata() {
        assertThat(filter.filter(List.of(hit(Map.of(
                "documentStatus", "ACTIVE",
                "effectiveFrom", "not-a-date"
        ))))).isEmpty();
        assertThat(filter.filter(List.of(hit(Map.of(
                "documentStatus", "mystery"
        ))))).isEmpty();
    }

    private RetrievalHit hit(Map<String, Object> metadata) {
        return new RetrievalHit(
                RetrievalType.LEXICAL,
                "doc",
                "chunk",
                "text",
                metadata
        );
    }
}
