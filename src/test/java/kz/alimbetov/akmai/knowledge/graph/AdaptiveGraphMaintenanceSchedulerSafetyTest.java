package kz.alimbetov.akmai.knowledge.graph;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        verify(appParameters, org.mockito.Mockito.times(2))
                .isEnabledAuthoritative(
                        AppParameterKey.ADAPTIVE_GRAPH_MAINTENANCE_ENABLED
                );
    }
}
