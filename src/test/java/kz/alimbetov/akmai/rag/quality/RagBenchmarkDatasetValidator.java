package kz.alimbetov.akmai.rag.quality;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;

public final class RagBenchmarkDatasetValidator {

    private static final int MIN_RELEASE_QUERIES = 300;
    private static final int MIN_CASES_PER_LANGUAGE = 10;
    private static final int MIN_CASES_PER_DOMAIN = 40;
    private static final int MIN_CASES_PER_QUERY_CLASS = 10;
    private static final double MIN_UNANSWERABLE_RATIO = 0.20;
    private static final double MAX_UNANSWERABLE_RATIO = 0.30;
    private static final Set<String> REQUIRED_LANGUAGES = Set.of(
            "kk", "ru", "en", "zh", "de", "fr", "es", "pt", "it", "tr", "el"
    );
    private static final Set<KnowledgeDomain> REQUIRED_DOMAINS = Set.of(
            KnowledgeDomain.LEGAL,
            KnowledgeDomain.MEDICAL,
            KnowledgeDomain.TECHNICAL
    );
    private static final Set<RagBenchmarkDataset.QueryClass> REQUIRED_QUERY_CLASSES =
            EnumSet.allOf(RagBenchmarkDataset.QueryClass.class);

    private RagBenchmarkDatasetValidator() {
    }

    public static Validation validateRelease(RagBenchmarkDataset dataset) {
        if (dataset == null) {
            return new Validation(false, List.of("dataset is required"));
        }
        List<String> failures = new ArrayList<>();

        if (!dataset.releaseQualified()) {
            failures.add("releaseQualified must be true");
        }
        if (dataset.queries().size() < MIN_RELEASE_QUERIES) {
            failures.add("release corpus requires at least 300 queries");
        }

        long unanswerable = dataset.queries().stream()
                .filter(query -> !query.answerable())
                .count();
        double ratio = dataset.queries().isEmpty()
                ? 0.0
                : (double) unanswerable / dataset.queries().size();
        if (ratio < MIN_UNANSWERABLE_RATIO || ratio > MAX_UNANSWERABLE_RATIO) {
            failures.add("unanswerable ratio must be between 0.20 and 0.30");
        }

        Map<String, Long> languageCounts = dataset.queries().stream()
                .map(RagBenchmarkDataset.Query::language)
                .filter(value -> value != null && !value.isBlank())
                .map(String::toLowerCase)
                .collect(Collectors.groupingBy(
                        value -> value,
                        Collectors.counting()
                ));
        Set<String> languages = languageCounts.keySet();
        if (!languages.containsAll(REQUIRED_LANGUAGES)) {
            Set<String> missing = new HashSet<>(REQUIRED_LANGUAGES);
            missing.removeAll(languages);
            failures.add("missing languages: " + missing);
        }
        REQUIRED_LANGUAGES.forEach(language -> {
            long count = languageCounts.getOrDefault(language, 0L);
            if (count < MIN_CASES_PER_LANGUAGE) {
                failures.add(
                        "language " + language + " requires at least "
                                + MIN_CASES_PER_LANGUAGE + " queries; found " + count
                );
            }
        });

        Map<KnowledgeDomain, Long> domainCounts = new EnumMap<>(
                KnowledgeDomain.class
        );
        dataset.queries().stream()
                .map(RagBenchmarkDataset.Query::domain)
                .filter(java.util.Objects::nonNull)
                .forEach(domain -> domainCounts.merge(domain, 1L, Long::sum));
        if (!domainCounts.keySet().containsAll(REQUIRED_DOMAINS)) {
            Set<KnowledgeDomain> missing = new HashSet<>(REQUIRED_DOMAINS);
            missing.removeAll(domainCounts.keySet());
            failures.add("missing domains: " + missing);
        }
        REQUIRED_DOMAINS.forEach(domain -> {
            long count = domainCounts.getOrDefault(domain, 0L);
            if (count < MIN_CASES_PER_DOMAIN) {
                failures.add(
                        "domain " + domain + " requires at least "
                                + MIN_CASES_PER_DOMAIN + " queries; found " + count
                );
            }
        });

        Map<RagBenchmarkDataset.QueryClass, Long> classCounts = new EnumMap<>(
                RagBenchmarkDataset.QueryClass.class
        );
        dataset.queries().stream()
                .map(RagBenchmarkDataset.Query::queryClass)
                .filter(java.util.Objects::nonNull)
                .forEach(queryClass -> classCounts.merge(queryClass, 1L, Long::sum));
        if (!classCounts.keySet().containsAll(REQUIRED_QUERY_CLASSES)) {
            Set<RagBenchmarkDataset.QueryClass> missing =
                    EnumSet.copyOf(REQUIRED_QUERY_CLASSES);
            missing.removeAll(classCounts.keySet());
            failures.add("missing query classes: " + missing);
        }
        REQUIRED_QUERY_CLASSES.forEach(queryClass -> {
            long count = classCounts.getOrDefault(queryClass, 0L);
            if (count < MIN_CASES_PER_QUERY_CLASS) {
                failures.add(
                        "query class " + queryClass + " requires at least "
                                + MIN_CASES_PER_QUERY_CLASS + " queries; found " + count
                );
            }
        });

        Set<RagBenchmarkDataset.Difficulty> difficulties = dataset.queries().stream()
                .map(RagBenchmarkDataset.Query::difficulty)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (!difficulties.containsAll(EnumSet.allOf(RagBenchmarkDataset.Difficulty.class))) {
            failures.add("release corpus must cover EASY, MEDIUM and HARD difficulty");
        }

        Set<String> documentIds = dataset.documents().stream()
                .map(RagBenchmarkDataset.Document::id)
                .collect(Collectors.toSet());
        if (documentIds.size() != dataset.documents().size()) {
            failures.add("document ids must be unique");
        }

        Set<String> queryIds = dataset.queries().stream()
                .map(RagBenchmarkDataset.Query::id)
                .collect(Collectors.toSet());
        if (queryIds.size() != dataset.queries().size()) {
            failures.add("query ids must be unique");
        }

        for (RagBenchmarkDataset.Query query : dataset.queries()) {
            if (query.id() == null || query.id().isBlank()) {
                failures.add("query id is required");
                continue;
            }
            if (query.question() == null || query.question().isBlank()) {
                failures.add(query.id() + ": question is required");
            }
            if (query.language() == null
                    || !REQUIRED_LANGUAGES.contains(query.language().toLowerCase())) {
                failures.add(query.id() + ": unsupported release language");
            }
            if (query.domain() == null || !REQUIRED_DOMAINS.contains(query.domain())) {
                failures.add(query.id() + ": release query requires a target domain");
            }
            if (query.queryClass() == null || query.difficulty() == null) {
                failures.add(query.id() + ": queryClass and difficulty are required");
            }

            if (query.answerable()) {
                if (query.queryClass() == RagBenchmarkDataset.QueryClass.UNANSWERABLE) {
                    failures.add(query.id() + ": answerable query cannot use UNANSWERABLE class");
                }
                if (query.relevantDocumentIds().isEmpty()) {
                    failures.add(query.id() + ": answerable query needs relevantDocumentIds");
                }
                if (query.relevantChunkIds().isEmpty()) {
                    failures.add(query.id() + ": answerable release query needs chunk-level truth");
                }
                for (String documentId : query.relevantDocumentIds()) {
                    if (!documentIds.contains(documentId)) {
                        failures.add(
                                query.id() + ": unknown relevant document " + documentId
                        );
                    }
                }
                Set<String> overlap = new HashSet<>(query.relevantChunkIds());
                overlap.retainAll(query.forbiddenChunkIds());
                if (!overlap.isEmpty()) {
                    failures.add(
                            query.id() + ": relevant and forbidden chunks overlap " + overlap
                    );
                }
            } else {
                if (query.queryClass() != RagBenchmarkDataset.QueryClass.UNANSWERABLE) {
                    failures.add(query.id() + ": unanswerable query must use UNANSWERABLE class");
                }
                if (!query.relevantChunkIds().isEmpty()
                        || !query.relevantDocumentIds().isEmpty()) {
                    failures.add(
                            query.id() + ": unanswerable query cannot declare relevant evidence"
                    );
                }
            }
        }

        return new Validation(failures.isEmpty(), List.copyOf(failures));
    }

    public record Validation(boolean valid, List<String> failures) {
        public Validation {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }
}
