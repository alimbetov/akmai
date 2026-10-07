package kz.alimbetov.akmai.rag.trace;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kz.alimbetov.akmai.config.SelfOptimizingRagProperties;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import org.springframework.stereotype.Component;

@Component
public class RagExecutionObservationStore {

    private final SelfOptimizingRagProperties properties;
    private final TokenEstimator tokenEstimator;
    private final Cache<String, Observation> observations = Caffeine.newBuilder()
            .maximumSize(4096)
            .expireAfterWrite(Duration.ofMinutes(15))
            .build();

    public RagExecutionObservationStore(
            SelfOptimizingRagProperties properties,
            TokenEstimator tokenEstimator
    ) {
        this.properties = properties;
        this.tokenEstimator = tokenEstimator;
    }

    public void record(
            RagExecutionTrace trace,
            List<RetrievalHit> finalContext,
            CitationValidator.CitationValidation validation,
            RagRuntimeAttribution.Snapshot attribution
    ) {
        if (!properties.executionObservationsEnabled()
                || trace == null
                || trace.requestId() == null
                || trace.requestId().isBlank()) {
            return;
        }
        List<RetrievalHit> selected = finalContext == null
                ? List.of()
                : finalContext;
        LinkedHashMap<String, Integer> tokenEstimates = new LinkedHashMap<>();
        for (RetrievalHit hit : selected) {
            if (hit == null || hit.chunkId() == null || hit.chunkId().isBlank()) {
                continue;
            }
            tokenEstimates.putIfAbsent(
                    hit.chunkId(),
                    tokenEstimator.estimate(hit.text())
            );
        }
        List<String> citedChunkIds = validation == null
                || validation.citedSources() == null
                ? List.of()
                : validation.citedSources().stream()
                        .map(CitationValidator.SourceRef::chunkId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();

        observations.put(
                trace.requestId(),
                new Observation(
                        trace,
                        selected.stream()
                                .filter(java.util.Objects::nonNull)
                                .map(RetrievalHit::chunkId)
                                .filter(java.util.Objects::nonNull)
                                .distinct()
                                .toList(),
                        citedChunkIds,
                        Map.copyOf(tokenEstimates),
                        attribution == null ? Map.of() : attribution.asMap()
                )
        );
    }

    public Optional<Observation> find(String requestId) {
        if (!properties.executionObservationsEnabled()
                || requestId == null
                || requestId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(observations.getIfPresent(requestId));
    }

    public record Observation(
            RagExecutionTrace trace,
            List<String> selectedChunkIds,
            List<String> citedChunkIds,
            Map<String, Integer> selectedTokenEstimates,
            Map<String, String> attribution
    ) {
        public Observation {
            selectedChunkIds = selectedChunkIds == null
                    ? List.of()
                    : List.copyOf(selectedChunkIds);
            citedChunkIds = citedChunkIds == null
                    ? List.of()
                    : List.copyOf(citedChunkIds);
            selectedTokenEstimates = selectedTokenEstimates == null
                    ? Map.of()
                    : Map.copyOf(selectedTokenEstimates);
            attribution = attribution == null ? Map.of() : Map.copyOf(attribution);
        }
    }
}
