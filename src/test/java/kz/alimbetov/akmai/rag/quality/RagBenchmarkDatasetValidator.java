package kz.alimbetov.akmai.rag.quality;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;

public final class RagBenchmarkDatasetValidator {

    private static final int MIN_RELEASE_QUERIES = 300;
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

        Set<String> languages = dataset.queries().stream()
                .map(RagBenchmarkDataset.Query::language)
                .filter(value -> value != null && !value.isBlank())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        if (!languages.containsAll(REQUIRED_LANGUAGES)) {
            Set<String> missing = new HashSet<>(REQUIRED_LANGUAGES);
            missing.removeAll(languages);
            failures.add("missing languages: " + missing);
        }

        Set<KnowledgeDomain> domains = dataset.queries().stream()
                .map(RagBenchmarkDataset.Query::domain)
                .filter(value -> value != null)
                .collect(Collectors.toSet());
        if (!domains.containsAll(REQUIRED_DOMAINS)) {
            Set<KnowledgeDomain> missing = new HashSet<>(REQUIRED_DOMAINS);
            missing.removeAll(domains);
            failures.add("missing domains: " + missing);
        }

        Set<RagBenchmarkDataset.QueryClass> queryClasses = dataset.queries().stream()
                .map(RagBenchmarkDataset.Query::queryClass)
                .filter(value -> value != null)
                .collect(Collectors.toSet());
        if (!queryClasses.containsAll(REQUIRED_QUERY_CLASSES)) {
            Set<RagBenchmarkDataset.QueryClass> missing =
                    EnumSet.copyOf(REQUIRED_QUERY_CLASSES);
            missing.removeAll(queryClasses);
            failures.add("missing query classes: " + missing);
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
            if (query.answerable()) {
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
            } else if (!query.relevantChunkIds().isEmpty()
                    || !query.relevantDocumentIds().isEmpty()) {
                failures.add(
                        query.id() + ": unanswerable query cannot declare relevant evidence"
                );
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
