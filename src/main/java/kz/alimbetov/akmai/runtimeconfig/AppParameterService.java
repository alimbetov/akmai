package kz.alimbetov.akmai.runtimeconfig;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import kz.alimbetov.akmai.config.AdaptiveGraphCompetitionProperties;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class AppParameterService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AppParameterService.class);

    private final AppParameterRepository repository;
    private final AdaptiveGraphProperties graphProperties;
    private final AdaptiveGraphCompetitionProperties competitionProperties;
    private final Cache<AppParameterKey, ResolvedAppParameter> cache;

    public AppParameterService(
            AppParameterRepository repository,
            AdaptiveGraphProperties graphProperties,
            AdaptiveGraphCompetitionProperties competitionProperties,
            AppParameterProperties properties
    ) {
        this.repository = repository;
        this.graphProperties = graphProperties;
        this.competitionProperties = competitionProperties;
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.cacheMaximumSize())
                .expireAfterWrite(properties.cacheTtl())
                .recordStats()
                .build();
    }

    public boolean isEnabled(AppParameterKey key) {
        return get(key).value();
    }

    public ResolvedAppParameter get(AppParameterKey key) {
        if (key == null) {
            throw new IllegalArgumentException(
                    "app parameter key must not be null"
            );
        }
        ResolvedAppParameter cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        ResolvedAppParameter loaded = loadFailSafe(key);
        cache.put(key, loaded);
        return loaded;
    }

    public List<ResolvedAppParameter> list() {
        return Arrays.stream(AppParameterKey.values())
                .map(this::get)
                .sorted(Comparator.comparing(
                        value -> value.key().key()
                ))
                .toList();
    }

    public ResolvedAppParameter updateBoolean(
            AppParameterKey key,
            boolean value,
            long expectedVersion,
            String updatedBy
    ) {
        if (key == null) {
            throw new IllegalArgumentException(
                    "app parameter key must not be null"
            );
        }
        if (expectedVersion < 0) {
            throw new IllegalArgumentException(
                    "expectedVersion must not be negative"
            );
        }
        String actor = normalizeActor(updatedBy);
        AppParameter updated = repository.updateBoolean(
                        key.key(),
                        value,
                        expectedVersion,
                        actor
                )
                .orElseThrow(() ->
                        new AppParameterConflictException(
                                key.key(),
                                expectedVersion
                        )
                );
        ResolvedAppParameter resolved =
                ResolvedAppParameter.from(updated);
        cache.put(key, resolved);
        return resolved;
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    private ResolvedAppParameter loadFailSafe(
            AppParameterKey key
    ) {
        try {
            return repository.find(key.key())
                    .map(ResolvedAppParameter::from)
                    .orElseGet(() -> fallback(key));
        } catch (DataAccessException exception) {
            LOGGER.warn(
                    "app_parameter_read event=fallback key={} errorType={}",
                    key.key(),
                    exception.getClass().getSimpleName()
            );
            return fallback(key);
        }
    }

    private ResolvedAppParameter fallback(
            AppParameterKey key
    ) {
        boolean value = switch (key) {
            case ADAPTIVE_GRAPH_LEARNING_ENABLED ->
                    graphProperties.learningEnabled();
            case ADAPTIVE_GRAPH_MAINTENANCE_ENABLED ->
                    graphProperties.maintenanceEnabled();
            case ADAPTIVE_GRAPH_SHADOW_EXPANSION_ENABLED ->
                    graphProperties.shadowExpansionEnabled();
            case ADAPTIVE_GRAPH_EXPANSION_ENABLED ->
                    graphProperties.expansionEnabled();
            case ADAPTIVE_GRAPH_COMPETITION_ENABLED ->
                    competitionProperties.enabled();
        };
        return ResolvedAppParameter.fallback(key, value);
    }

    private String normalizeActor(String updatedBy) {
        String actor = updatedBy == null
                ? "unknown"
                : updatedBy.trim();
        if (actor.isBlank()) {
            actor = "unknown";
        }
        return actor.length() <= 128
                ? actor
                : actor.substring(0, 128);
    }
}
