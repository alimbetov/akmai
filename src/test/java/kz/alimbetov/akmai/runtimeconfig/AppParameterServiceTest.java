package kz.alimbetov.akmai.runtimeconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import kz.alimbetov.akmai.config.AdaptiveGraphCompetitionProperties;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class AppParameterServiceTest {

    @Test
    void cachesRuntimeReads() {
        AppParameterRepository repository =
                mock(AppParameterRepository.class);
        AdaptiveGraphProperties graph =
                mock(AdaptiveGraphProperties.class);
        AdaptiveGraphCompetitionProperties competition =
                mock(AdaptiveGraphCompetitionProperties.class);
        AppParameter parameter = parameter(
                AppParameterKey.ADAPTIVE_GRAPH_LEARNING_ENABLED,
                true,
                3L
        );
        when(repository.find(parameter.key()))
                .thenReturn(Optional.of(parameter));

        AppParameterService service = service(
                repository,
                graph,
                competition
        );

        assertThat(service.isEnabled(
                AppParameterKey.ADAPTIVE_GRAPH_LEARNING_ENABLED
        )).isTrue();
        assertThat(service.isEnabled(
                AppParameterKey.ADAPTIVE_GRAPH_LEARNING_ENABLED
        )).isTrue();

        verify(repository).find(parameter.key());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void updateRefreshesLocalCacheImmediately() {
        AppParameterRepository repository =
                mock(AppParameterRepository.class);
        AdaptiveGraphProperties graph =
                mock(AdaptiveGraphProperties.class);
        AdaptiveGraphCompetitionProperties competition =
                mock(AdaptiveGraphCompetitionProperties.class);
        AppParameter updated = parameter(
                AppParameterKey.ADAPTIVE_GRAPH_SHADOW_EXPANSION_ENABLED,
                true,
                1L
        );
        when(repository.lockAll(anyList()))
                .thenReturn(allFalseParameters());
        when(repository.updateBoolean(
                updated.key(),
                true,
                0L,
                "operator"
        )).thenReturn(Optional.of(updated));

        AppParameterService service = service(
                repository,
                graph,
                competition
        );

        ResolvedAppParameter result = service.updateBoolean(
                AppParameterKey.ADAPTIVE_GRAPH_SHADOW_EXPANSION_ENABLED,
                true,
                0L,
                "operator"
        );

        assertThat(result.value()).isTrue();
        assertThat(result.version()).isEqualTo(1L);
        assertThat(service.isEnabled(
                AppParameterKey.ADAPTIVE_GRAPH_SHADOW_EXPANSION_ENABLED
        )).isTrue();
        verify(repository).lockAll(anyList());
        verify(repository).updateBoolean(
                updated.key(),
                true,
                0L,
                "operator"
        );
        verifyNoMoreInteractions(repository);
    }

    @Test
    void runtimeReadFallsBackToStaticConfigWhenDatabaseIsUnavailable() {
        AppParameterRepository repository =
                mock(AppParameterRepository.class);
        AdaptiveGraphProperties graph =
                mock(AdaptiveGraphProperties.class);
        AdaptiveGraphCompetitionProperties competition =
                mock(AdaptiveGraphCompetitionProperties.class);
        when(repository.find(
                AppParameterKey.ADAPTIVE_GRAPH_MAINTENANCE_ENABLED.key()
        )).thenThrow(new DataAccessResourceFailureException("down"));
        when(graph.maintenanceEnabled()).thenReturn(true);

        AppParameterService service = service(
                repository,
                graph,
                competition
        );

        ResolvedAppParameter value = service.get(
                AppParameterKey.ADAPTIVE_GRAPH_MAINTENANCE_ENABLED
        );

        assertThat(value.value()).isTrue();
        assertThat(value.source())
                .isEqualTo(AppParameterSource.STATIC_FALLBACK);
        assertThat(value.version()).isNull();
    }

    @Test
    void runtimeReadKeepsLastKnownGoodValueDuringDatabaseOutage() {
        AppParameterRepository repository =
                mock(AppParameterRepository.class);
        AppParameterKey key =
                AppParameterKey.ADAPTIVE_GRAPH_COMPETITION_ENABLED;
        AppParameter persisted = parameter(key, true, 4L);
        when(repository.find(key.key()))
                .thenReturn(Optional.of(persisted))
                .thenThrow(new DataAccessResourceFailureException("down"));

        AppParameterService service = service(
                repository,
                mock(AdaptiveGraphProperties.class),
                mock(AdaptiveGraphCompetitionProperties.class)
        );

        assertThat(service.get(key).value()).isTrue();
        service.invalidateAll();

        ResolvedAppParameter duringOutage = service.get(key);

        assertThat(duringOutage.value()).isTrue();
        assertThat(duringOutage.version()).isEqualTo(4L);
        assertThat(duringOutage.source())
                .isEqualTo(AppParameterSource.DATABASE);
    }

    @Test
    void authoritativeAdminReadDoesNotHideDatabaseFailure() {
        AppParameterRepository repository =
                mock(AppParameterRepository.class);
        when(repository.find(
                AppParameterKey.ADAPTIVE_GRAPH_COMPETITION_ENABLED.key()
        )).thenThrow(new DataAccessResourceFailureException("down"));

        AppParameterService service = service(
                repository,
                mock(AdaptiveGraphProperties.class),
                mock(AdaptiveGraphCompetitionProperties.class)
        );

        assertThatThrownBy(() -> service.getAuthoritative(
                AppParameterKey.ADAPTIVE_GRAPH_COMPETITION_ENABLED
        )).isInstanceOf(AppParameterUnavailableException.class);
    }

    @Test
    void rejectsCompetitionUntilOnlineExpansionIsEnabled() {
        AppParameterRepository repository =
                mock(AppParameterRepository.class);
        AdaptiveGraphProperties graph =
                mock(AdaptiveGraphProperties.class);
        AdaptiveGraphCompetitionProperties competition =
                mock(AdaptiveGraphCompetitionProperties.class);
        when(repository.lockAll(anyList()))
                .thenReturn(allFalseParameters());
        AppParameterService service = service(
                repository,
                graph,
                competition
        );

        assertThatThrownBy(() -> service.updateBoolean(
                AppParameterKey.ADAPTIVE_GRAPH_COMPETITION_ENABLED,
                true,
                0L,
                "operator"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires online expansion");
    }

    @Test
    void rejectsLostOptimisticUpdate() {
        AppParameterRepository repository =
                mock(AppParameterRepository.class);
        AppParameterService service = service(
                repository,
                mock(AdaptiveGraphProperties.class),
                mock(AdaptiveGraphCompetitionProperties.class)
        );
        AppParameterKey key =
                AppParameterKey.ADAPTIVE_GRAPH_SHADOW_EXPANSION_ENABLED;
        when(repository.lockAll(anyList()))
                .thenReturn(allFalseParameters());
        when(repository.updateBoolean(
                key.key(),
                true,
                7L,
                "operator"
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateBoolean(
                key,
                true,
                7L,
                "operator"
        ))
                .isInstanceOf(AppParameterConflictException.class)
                .hasMessageContaining("expected version 7");
    }

    private java.util.List<AppParameter> allFalseParameters() {
        return java.util.Arrays.stream(AppParameterKey.values())
                .map(key -> parameter(key, false, 0L))
                .toList();
    }

    private AppParameterService service(
            AppParameterRepository repository,
            AdaptiveGraphProperties graph,
            AdaptiveGraphCompetitionProperties competition
    ) {
        return new AppParameterService(
                repository,
                graph,
                competition,
                new AppParameterProperties(
                        Duration.ofSeconds(10),
                        64
                )
        );
    }

    private AppParameter parameter(
            AppParameterKey key,
            boolean value,
            long version
    ) {
        return new AppParameter(
                key.key(),
                AppParameterType.BOOLEAN,
                Boolean.toString(value),
                version,
                Instant.parse("2026-10-04T08:00:00Z"),
                "operator"
        );
    }
}
