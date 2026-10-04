package kz.alimbetov.akmai.rag.retrieval;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Fail-closed temporal/authority fence for explicitly versioned knowledge.
 *
 * <p>Rows without temporal metadata remain eligible for backward
 * compatibility. Once a document provides documentStatus/effectiveFrom/
 * effectiveTo metadata, malformed or inactive values are not allowed into the
 * final context.</p>
 */
@Component
public class TemporalAuthorityFilter {

    private final Clock clock;

    public TemporalAuthorityFilter() {
        this(Clock.systemUTC());
    }

    TemporalAuthorityFilter(Clock clock) {
        this.clock = clock;
    }

    public List<RetrievalHit> filter(List<RetrievalHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        LocalDate today = LocalDate.now(clock);
        return hits.stream()
                .filter(hit -> eligible(hit, today))
                .toList();
    }

    boolean eligible(RetrievalHit hit, LocalDate asOf) {
        if (hit == null) {
            return false;
        }
        Map<String, Object> metadata = hit.metadata();
        if (metadata == null || metadata.isEmpty()) {
            return true;
        }

        if (metadata.containsKey("documentStatus")
                && !activeStatus(metadata.get("documentStatus"))) {
            return false;
        }

        LocalDate effectiveFrom = date(metadata.get("effectiveFrom"));
        if (metadata.containsKey("effectiveFrom") && effectiveFrom == null) {
            return false;
        }
        if (effectiveFrom != null && effectiveFrom.isAfter(asOf)) {
            return false;
        }

        LocalDate effectiveTo = date(metadata.get("effectiveTo"));
        if (metadata.containsKey("effectiveTo") && effectiveTo == null) {
            return false;
        }
        return effectiveTo == null || !effectiveTo.isBefore(asOf);
    }

    private boolean activeStatus(Object raw) {
        if (!(raw instanceof String value) || value.isBlank()) {
            return false;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ACTIVE", "CURRENT", "EFFECTIVE" -> true;
            case "DRAFT", "REPEALED", "SUPERSEDED", "EXPIRED", "WITHDRAWN" ->
                    false;
            default -> false;
        };
    }

    private LocalDate date(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate value) {
            return value;
        }
        if (raw instanceof Instant value) {
            return value.atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (!(raw instanceof String value) || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.parse(value.trim())
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate();
            } catch (DateTimeParseException ignoredInstant) {
                return null;
            }
        }
    }
}
