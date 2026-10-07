package kz.alimbetov.akmai.rag.policy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.rag.retrieval.plan.AdaptiveRetrievalPlanner;
import org.springframework.stereotype.Component;

@Component
public class CanaryRetrievalPolicyProvider {

    private static final String CACHE_KEY = "canary";

    private final RagPolicyRegistryRepository repository;
    private final Cache<String, Optional<RagPolicyRegistryRepository.PolicyRecord>> cache =
            Caffeine.newBuilder()
                    .maximumSize(1)
                    .expireAfterWrite(Duration.ofSeconds(2))
                    .build();

    public CanaryRetrievalPolicyProvider(RagPolicyRegistryRepository repository) {
        this.repository = repository;
    }

    public Optional<Set<RetrievalType>> lanes(
            AdaptiveRetrievalPlanner.QueryClass queryClass
    ) {
        if (queryClass == null) {
            return Optional.empty();
        }
        Optional<RagPolicyRegistryRepository.PolicyRecord> policy = current();
        if (policy.isEmpty()) {
            return Optional.empty();
        }
        return route(policy.get().configuration(), queryClass);
    }

    public Optional<String> canaryVersion() {
        return current().map(RagPolicyRegistryRepository.PolicyRecord::version);
    }

    public void invalidate() {
        cache.invalidateAll();
    }

    private Optional<RagPolicyRegistryRepository.PolicyRecord> current() {
        try {
            Optional<RagPolicyRegistryRepository.PolicyRecord> policy = cache.get(
                    CACHE_KEY,
                    ignored -> repository.canary(RagPolicyType.RETRIEVAL)
            );
            return policy == null ? Optional.empty() : policy;
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<Set<RetrievalType>> route(
            Map<String, Object> configuration,
            AdaptiveRetrievalPlanner.QueryClass queryClass
    ) {
        if (configuration == null || configuration.isEmpty()) {
            return Optional.empty();
        }
        Object routesValue = configuration.get("routes");
        if (!(routesValue instanceof Map<?, ?> routes)) {
            return Optional.empty();
        }
        Object laneValue = routes.get(queryClass.name());
        if (!(laneValue instanceof List<?> rawLanes) || rawLanes.isEmpty()) {
            return Optional.empty();
        }
        EnumSet<RetrievalType> parsed = EnumSet.noneOf(RetrievalType.class);
        for (Object raw : rawLanes) {
            if (!(raw instanceof String text)) {
                return Optional.empty();
            }
            try {
                parsed.add(RetrievalType.valueOf(
                        text.trim().toUpperCase(java.util.Locale.ROOT)
                ));
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }
        return parsed.isEmpty()
                ? Optional.empty()
                : Optional.of(Set.copyOf(parsed));
    }
}
