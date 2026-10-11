package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import kz.alimbetov.akmai.runtimeconfig.AppParameterKey;
import kz.alimbetov.akmai.runtimeconfig.AppParameterService;
import org.junit.jupiter.api.Test;

class AdaptiveGraphMaintenanceSchedulerSafetyTest {

    @Test
    void staticDisableCannotBeOverriddenByRuntimeTrue() {
        AdaptiveGraphMaintenanceService service =
                mock(AdaptiveGraphMaintenanceService.class);
        AdaptiveGraphProperties properties =
                mock(AdaptiveGraphProperties.class);
        AppParameterService appParameters =
                mock(AppParameterService.class);

        when(properties.maintenanceEnabled()).thenReturn(false);
        when(properties.maintenance()).thenReturn(
                new AdaptiveGraphProperties.Maintenance(
                        10,
                        3,
                        Duration.ofMinutes(5)
                )
        );
        when(appParameters.isEnabledAuthoritative(
                AppParameterKey.ADAPTIVE_GRAPH_MAINTENANCE_ENABLED
        )).thenReturn(true);

        AdaptiveGraphMaintenanceScheduler scheduler =
                new AdaptiveGraphMaintenanceScheduler(
                        service,
                        properties,
                        mock(AkmaiMetrics.class),
                        appParameters
                );

        scheduler.maintain();

        verify(service, never()).maintainBatch();
        verify(appParameters, never()).isEnabledAuthoritative(
                AppParameterKey.ADAPTIVE_GRAPH_MAINTENANCE_ENABLED
        );
    }

    @Test
    void runtimeDisableBetweenBatchesStopsNewMutationWork() {
        AdaptiveGraphMaintenanceService service =
                mock(AdaptiveGraphMaintenanceService.class);
        AdaptiveGraphProperties properties =
                mock(AdaptiveGraphProperties.class);
        AppParameterService appParameters =
                mock(AppParameterService.class);

        when(properties.maintenanceEnabled()).thenReturn(true);
        when(properties.maintenance()).thenReturn(
                new AdaptiveGraphProperties.Maintenance(
                        10,
                        3,
                        Duration.ofMinutes(5)
                )
        );
        when(appParameters.isEnabledAuthoritative(
                AppParameterKey.ADAPTIVE_GRAPH_MAINTENANCE_ENABLED
        )).thenReturn(true, false);
        when(service.maintainBatch()).thenReturn(
                new AdaptiveGraphMaintenanceService.MaintenanceBatch(
                        10,
                        0,
                        0,
                        Map.of(),
                        true
                )
        );

        AdaptiveGraphMaintenanceScheduler scheduler =
                new AdaptiveGraphMaintenanceScheduler(
                        service,
                        properties,
                        mock(AkmaiMetrics.class),
                        appParameters
                );

        scheduler.maintain();

        verify(service).maintainBatch();
        verify(appParameters, times(2)).isEnabledAuthoritative(
                AppParameterKey.ADAPTIVE_GRAPH_MAINTENANCE_ENABLED
        );
    }

    @Test
    void unsaturatedBatchStopsRunImmediately() {
        AdaptiveGraphMaintenanceService service =
                mock(AdaptiveGraphMaintenanceService.class);
        AdaptiveGraphProperties properties =
                mock(AdaptiveGraphProperties.class);
        AppParameterService appParameters =
                mock(AppParameterService.class);

        when(properties.maintenanceEnabled()).thenReturn(true);
        when(properties.maintenance()).thenReturn(
                new AdaptiveGraphProperties.Maintenance(
                        10,
                        5,
                        Duration.ofMinutes(5)
                )
        );
        when(appParameters.isEnabledAuthoritative(
                AppParameterKey.ADAPTIVE_GRAPH_MAINTENANCE_ENABLED
        )).thenReturn(true);
        when(service.maintainBatch()).thenReturn(
                new AdaptiveGraphMaintenanceService.MaintenanceBatch(
                        3,
                        1,
                        0,
                        Map.of(),
                        false
                )
        );

        AdaptiveGraphMaintenanceScheduler scheduler =
                new AdaptiveGraphMaintenanceScheduler(
                        service,
                        properties,
                        mock(AkmaiMetrics.class),
                        appParameters
                );

        scheduler.maintain();

        verify(service).maintainBatch();
    }

    @Test
    void saturatedBatchesCannotExceedConfiguredRunBound() {
        AdaptiveGraphMaintenanceService service =
                mock(AdaptiveGraphMaintenanceService.class);
        AdaptiveGraphProperties properties =
                mock(AdaptiveGraphProperties.class);
        AppParameterService appParameters =
                mock(AppParameterService.class);
        AkmaiMetrics metrics = mock(AkmaiMetrics.class);

        when(properties.maintenanceEnabled()).thenReturn(true);
        when(properties.maintenance()).thenReturn(
                new AdaptiveGraphProperties.Maintenance(
                        10,
                        3,
                        Duration.ofMinutes(5)
                )
        );
        when(appParameters.isEnabledAuthoritative(
                AppParameterKey.ADAPTIVE_GRAPH_MAINTENANCE_ENABLED
        )).thenReturn(true);
        when(service.maintainBatch()).thenReturn(
                new AdaptiveGraphMaintenanceService.MaintenanceBatch(
                        10,
                        1,
                        1,
                        Map.of(),
                        true
                )
        );

        AdaptiveGraphMaintenanceScheduler scheduler =
                new AdaptiveGraphMaintenanceScheduler(
                        service,
                        properties,
                        metrics,
                        appParameters
                );

        scheduler.maintain();

        verify(service, times(3)).maintainBatch();
        verify(metrics).adaptiveGraphMaintenance(
                eq("SUCCESS"),
                any(Duration.class),
                eq(30),
                eq(6)
        );
    }

    @Test
    void maintenanceFailurePropagatesAndIsRecordedAsFailed() {
        AdaptiveGraphMaintenanceService service =
                mock(AdaptiveGraphMaintenanceService.class);
        AdaptiveGraphProperties properties =
                mock(AdaptiveGraphProperties.class);
        AppParameterService appParameters =
                mock(AppParameterService.class);
        AkmaiMetrics metrics = mock(AkmaiMetrics.class);
        IllegalStateException failure = new IllegalStateException("database unavailable");

        when(properties.maintenanceEnabled()).thenReturn(true);
        when(properties.maintenance()).thenReturn(
                new AdaptiveGraphProperties.Maintenance(
                        10,
                        3,
                        Duration.ofMinutes(5)
                )
        );
        when(appParameters.isEnabledAuthoritative(
                AppParameterKey.ADAPTIVE_GRAPH_MAINTENANCE_ENABLED
        )).thenReturn(true);
        when(service.maintainBatch()).thenThrow(failure);

        AdaptiveGraphMaintenanceScheduler scheduler =
                new AdaptiveGraphMaintenanceScheduler(
                        service,
                        properties,
                        metrics,
                        appParameters
                );

        assertThatThrownBy(scheduler::maintain).isSameAs(failure);

        verify(metrics).adaptiveGraphMaintenance(
                eq("FAILED"),
                any(Duration.class),
                eq(0),
                eq(0)
        );
    }
}
