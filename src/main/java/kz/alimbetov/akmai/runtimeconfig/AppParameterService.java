package kz.alimbetov.akmai.runtimeconfig;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import kz.alimbetov.akmai.config.AdaptiveGraphCompetitionProperties;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AppParameterService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AppParameterService.class);

    private final AppParameterRepository repository;
    private final AdaptiveGraphProperties graphProperties;
    private final AdaptiveGraphCompetitionProperties competitionProperties;
    private final Cache<AppParameterKey, ResolvedAppParameter> cache;
    private final Map<AppParameterKey, ResolvedAppParameter> lastKnownGood =
            new ConcurrentHashMap<>();

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

    /**
     * Cached/fail-safe runtime read. This is suitable only for optional paths
     * where a bounded stale/LKG/static value is an explicitly accepted
     * availability trade-off.
     */
    public boolean isEnabled(AppParameterKey key) {
        return get(key).value();
    }

    /**
     * Authoritative database read for mutation-capable safety gates. Database
     * unavailability is surfaced to the caller and never converted into a
     * last-known-good or static fallback value.
     */
    public boolean isEnabledAuthoritative(AppParameterKey key) {
        return getAuthoritative(key).value();
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
                .sorted(Comparator.comparing(value -> value.key().key()))
                .toList();
    }

    public ResolvedAppParameter getAuthoritative(AppParameterKey key) {
        if (key == null) {
            throw new IllegalArgumentException(
                    "app parameter key must not be null"
            );
        }
        try {
            ResolvedAppParameter resolved = repository.find(key.key())
                    .map(ResolvedAppParameter::from)
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing persisted app parameter: " + key.key()
                    ));
            remember(key, resolved);
            return resolved;
        } catch (DataAccessException exception) {
            throw new AppParameterUnavailableException(exception);
        }
    }

    public List<ResolvedAppParameter> listAuthoritative() {
        return Arrays.stream(AppParameterKey.values())
                .map(this::getAuthoritative)
                .sorted(Comparator.comparing(value -> value.key().key()))
                .toList();
    }

    @Transactional
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
        Map<AppParameterKey, Boolean> locked = lockParameterSnapshot();
        validateTransition(key, value, locked);

        String actor = normalizeActor(updatedBy);
        AppParameter updated;
        try {
            updated = repository.updateBoolean(
                            key.key(), value, expectedVersion, actor
                    )
                    .orElseThrow(() -> {
                        invalidateLocalState(key);
                        return new AppParameterConflictException(
                                key.key(), expectedVersion
                        );
                    });
        } catch (DataAccessException exception) {
            throw new AppParameterUnavailableException(exception);
        }

        ResolvedAppParameter resolved = ResolvedAppParameter.from(updated);
        publishCacheAfterCommit(key, resolved);
        return resolved;
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    private Map<AppParameterKey, Boolean> lockParameterSnapshot() {
        List<String> keys = Arrays.stream(AppParameterKey.values())
                .map(AppParameterKey::key)
                .sorted()
                .toList();
        List<AppParameter> locked;
        try {
            locked = repository.lockAll(keys);
        } catch (DataAccessException exception) {
            throw new AppParameterUnavailableException(exception);
        }

        LinkedHashMap<AppParameterKey, Boolean> snapshot = new LinkedHashMap<>();
        for (AppParameter parameter : locked) {
            AppParameterKey parameterKey = AppParameterKey.parse(parameter.key());
            snapshot.put(parameterKey, parameter.booleanValue());
        }
        if (snapshot.size() != AppParameterKey.values().length) {
            throw new IllegalStateException(
                    "Runtime parameter registry is incomplete"
            );
        }
        return Map.copyOf(snapshot);
    }

    private void validateTransition(
            AppParameterKey key,
            boolean value,
            Map<AppParameterKey, Boolean> current
    ) {
        if (!value) {
            if (key == AppParameterKey.ADAPTIVE_GRAPH_MAINTENANCE_ENABLED
                    && enabled(current, AppParameterKey.ADAPTIVE_GRAPH_EXPANSION_ENABLED)) {
                throw new IllegalArgumentException(
                        "Cannot disable adaptive graph maintenance while online expansion is enabled"
                );
            }
            if (key == AppParameterKey.ADAPTIVE_GRAPH_EXPANSION_ENABLED
                    && enabled(current, AppParameterKey.ADAPTIVE_GRAPH_COMPETITION_ENABLED)) {
                throw new IllegalArgumentException(
                        "Cannot disable adaptive graph expansion while competition is enabled"
                );
            }
            if (key == AppParameterKey.ADAPTIVE_GRAPH_DREAM_ENABLED
                    && enabled(current, AppParameterKey.ADAPTIVE_GRAPH_DREAM_APPLY_ENABLED)) {
                throw new IllegalArgumentException(
                        "Cannot disable Dream while Dream apply is enabled"
                );
            }
            return;
        }

        switch (key) {
            case ADAPTIVE_GRAPH_LEARNING_ENABLED -> {
                String secret = graphProperties.learning().fingerprintSecret();
                if (secret == null || secret.length() < 32) {
                    throw new IllegalArgumentException(
                            "adaptive graph learning requires a fingerprint secret of at least 32 characters"
                    );
                }
            }
            case ADAPTIVE_GRAPH_EXPANSION_ENABLED -> {
                if (!enabled(current, AppParameterKey.ADAPTIVE_GRAPH_MAINTENANCE_ENABLED)) {
                    throw new IllegalArgumentException(
                            "adaptive graph online expansion requires maintenance to be enabled first"
                    );
                }
            }
            case ADAPTIVE_GRAPH_COMPETITION_ENABLED -> {
                if (!enabled(current, AppParameterKey.ADAPTIVE_GRAPH_EXPANSION_ENABLED)) {
                    throw new IllegalArgumentException(
                            "adaptive graph competition requires online expansion to be enabled first"
                    );
                }
            }
            case ADAPTIVE_GRAPH_DREAM_APPLY_ENABLED -> {
                if (!enabled(current, AppParameterKey.ADAPTIVE_GRAPH_DREAM_ENABLED)) {
                    throw new IllegalArgumentException(
                            "adaptive graph Dream apply requires Dream to be enabled first"
                    );
                }
            }
            case ADAPTIVE_GRAPH_MAINTENANCE_ENABLED,
                    ADAPTIVE_GRAPH_SHADOW_EXPANSION_ENABLED,
                    ADAPTIVE_GRAPH_DREAM_ENABLED,
                    SEMANTIC_MEMORY_INGESTION_LINKING_ENABLED -> {
                // no additional dependency
            }
        }
    }

    private boolean enabled(
            Map<AppParameterKey, Boolean> snapshot,
            AppParameterKey key
    ) {
        return Boolean.TRUE.equals(snapshot.get(key));
    }

    private void publishCacheAfterCommit(
            AppParameterKey key,
            ResolvedAppParameter value
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            remember(key, value);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        remember(key, value);
                    }
                }
        );
    }

    private ResolvedAppParameter loadFailSafe(AppParameterKey key) {
        try {
            ResolvedAppParameter resolved = repository.find(key.key())
                    .map(ResolvedAppParameter::from)
                    .orElse(null);
            if (resolved == null) {
                LOGGER.warn("app_parameter_read event=missing key={}", key.key());
                return lastKnownOrFallback(key);
            }
            remember(key, resolved);
            return resolved;
        } catch (DataAccessException exception) {
            LOGGER.warn(
                    "app_parameter_read event=fallback key={} errorType={}",
                    key.key(), exception.getClass().getSimpleName()
            );
            return lastKnownOrFallback(key);
        }
    }

    private ResolvedAppParameter lastKnownOrFallback(AppParameterKey key) {
        ResolvedAppParameter known = lastKnownGood.get(key);
        return known == null ? fallback(key) : known;
    }

    private void remember(
            AppParameterKey key,
            ResolvedAppParameter value
    ) {
        ResolvedAppParameter effective = lastKnownGood.compute(
                key,
                (ignored, current) -> newer(current, value) ? value : current
        );
        cache.put(key, effective);
    }

    private boolean newer(
            ResolvedAppParameter current,
            ResolvedAppParameter candidate
    ) {
        if (current == null) {
            return true;
        }
        Long currentVersion = current.version();
        Long candidateVersion = candidate.version();
        if (candidateVersion == null) {
            return currentVersion == null;
        }
        return currentVersion == null || candidateVersion >= currentVersion;
    }

    private void invalidateLocalState(AppParameterKey key) {
        cache.invalidate(key);
        lastKnownGood.remove(key);
    }

    private ResolvedAppParameter fallback(AppParameterKey key) {
        boolean value = switch (key) {
            case ADAPTIVE_GRAPH_LEARNING_ENABLED -> graphProperties.learningEnabled();
            case ADAPTIVE_GRAPH_MAINTENANCE_ENABLED -> graphProperties.maintenanceEnabled();
            case ADAPTIVE_GRAPH_SHADOW_EXPANSION_ENABLED -> graphProperties.shadowExpansionEnabled();
            case ADAPTIVE_GRAPH_EXPANSION_ENABLED -> graphProperties.expansionEnabled();
            case ADAPTIVE_GRAPH_COMPETITION_ENABLED -> competitionProperties.enabled();
            case ADAPTIVE_GRAPH_DREAM_ENABLED -> graphProperties.dreamEnabled();
            case ADAPTIVE_GRAPH_DREAM_APPLY_ENABLED -> graphProperties.dream().applyEnabled();
            case SEMANTIC_MEMORY_INGESTION_LINKING_ENABLED -> false;
        };
        return ResolvedAppParameter.fallback(key, value);
    }

    private String normalizeActor(String updatedBy) {
        String actor = updatedBy == null ? "unknown" : updatedBy.trim();
        if (actor.isBlank()) {
            actor = "unknown";
        }
        return actor.length() <= 128 ? actor : actor.substring(0, 128);
    }
}
