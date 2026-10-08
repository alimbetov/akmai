package kz.alimbetov.akmai.runtimeconfig;

import java.util.Arrays;

public enum AppParameterKey {

    ADAPTIVE_GRAPH_LEARNING_ENABLED(
            "akmai.adaptive-graph.learning-enabled",
            "Enable citation-anchored adaptive graph learning"
    ),
    ADAPTIVE_GRAPH_MAINTENANCE_ENABLED(
            "akmai.adaptive-graph.maintenance-enabled",
            "Enable adaptive graph scoring, lifecycle maintenance and bounded cleanup"
    ),
    ADAPTIVE_GRAPH_SHADOW_EXPANSION_ENABLED(
            "akmai.adaptive-graph.shadow-expansion-enabled",
            "Enable shadow graph expansion and candidate telemetry"
    ),
    ADAPTIVE_GRAPH_EXPANSION_ENABLED(
            "akmai.adaptive-graph.expansion-enabled",
            "Allow validated graph candidates to enter online retrieval"
    ),
    ADAPTIVE_GRAPH_COMPETITION_ENABLED(
            "akmai.adaptive-graph.competition.enabled",
            "Allow eligible HOT graph candidates to compete with base retrieval"
    ),
    ADAPTIVE_GRAPH_DREAM_ENABLED(
            "akmai.adaptive-graph.dream-enabled",
            "Enable bounded Adaptive Graph Dream discovery and verification"
    ),
    ADAPTIVE_GRAPH_DREAM_APPLY_ENABLED(
            "akmai.adaptive-graph.dream.apply-enabled",
            "Allow Dream to apply or retire semantic priors in the adaptive graph"
    ),
    SEMANTIC_MEMORY_INGESTION_LINKING_ENABLED(
            "akmai.semantic-memory.ingestion-linking-enabled",
            "Seed bounded semantic graph links after generation publication"
    );

    private final String key;
    private final String description;

    AppParameterKey(String key, String description) {
        this.key = key;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String description() {
        return description;
    }

    public static AppParameterKey parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "app parameter key must not be blank"
            );
        }
        return Arrays.stream(values())
                .filter(candidate -> candidate.key.equals(raw))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported app parameter: " + raw
                ));
    }
}
