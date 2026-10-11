package kz.alimbetov.akmai.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import kz.alimbetov.akmai.config.ReembeddingProperties;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.ApplicationArguments;

class ReembeddingStartupRunnerTest {

    @Test
    void recoveryAlwaysRunsEvenWhenAutomaticMigrationIsDisabled() {
        ReembeddingService service = mock(ReembeddingService.class);
        EmbeddingProfileService profiles = mock(EmbeddingProfileService.class);
        EmbeddingProfileResolver resolver = mock(EmbeddingProfileResolver.class);
        ReembeddingStartupRunner runner = new ReembeddingStartupRunner(
                service,
                profiles,
                resolver,
                properties(false)
        );

        runner.run(mock(ApplicationArguments.class));

        verify(service).recoverExpiredMigration();
        verify(service, never()).migrateToConfiguredProfile();
        verify(profiles, never()).activeProfile();
        verify(resolver, never()).configuredProfile();
    }

    @Test
    void automaticMigrationRunsOnlyWhenConfiguredProfileDiffers() {
        ReembeddingService service = mock(ReembeddingService.class);
        EmbeddingProfileService profiles = mock(EmbeddingProfileService.class);
        EmbeddingProfileResolver resolver = mock(EmbeddingProfileResolver.class);
        EmbeddingProfile active = mock(EmbeddingProfile.class);
        EmbeddingProfile configured = mock(EmbeddingProfile.class);
        when(active.profileId()).thenReturn("profile-a");
        when(configured.profileId()).thenReturn("profile-b");
        when(profiles.activeProfile()).thenReturn(active);
        when(resolver.configuredProfile()).thenReturn(configured);
        ReembeddingStartupRunner runner = new ReembeddingStartupRunner(
                service,
                profiles,
                resolver,
                properties(true)
        );

        runner.run(mock(ApplicationArguments.class));

        InOrder order = inOrder(service, profiles, resolver);
        order.verify(service).recoverExpiredMigration();
        order.verify(profiles).activeProfile();
        order.verify(resolver).configuredProfile();
        order.verify(service).migrateToConfiguredProfile();
    }

    @Test
    void matchingProfileDoesNotStartRedundantMigration() {
        ReembeddingService service = mock(ReembeddingService.class);
        EmbeddingProfileService profiles = mock(EmbeddingProfileService.class);
        EmbeddingProfileResolver resolver = mock(EmbeddingProfileResolver.class);
        EmbeddingProfile active = mock(EmbeddingProfile.class);
        EmbeddingProfile configured = mock(EmbeddingProfile.class);
        when(active.profileId()).thenReturn("profile-a");
        when(configured.profileId()).thenReturn("profile-a");
        when(profiles.activeProfile()).thenReturn(active);
        when(resolver.configuredProfile()).thenReturn(configured);
        ReembeddingStartupRunner runner = new ReembeddingStartupRunner(
                service,
                profiles,
                resolver,
                properties(true)
        );

        runner.run(mock(ApplicationArguments.class));

        verify(service).recoverExpiredMigration();
        verify(service, never()).migrateToConfiguredProfile();
    }

    @Test
    void recoveryFailureStopsStartupMigrationPath() {
        ReembeddingService service = mock(ReembeddingService.class);
        EmbeddingProfileService profiles = mock(EmbeddingProfileService.class);
        EmbeddingProfileResolver resolver = mock(EmbeddingProfileResolver.class);
        IllegalStateException failure = new IllegalStateException("recovery failed");
        doThrow(failure).when(service).recoverExpiredMigration();
        ReembeddingStartupRunner runner = new ReembeddingStartupRunner(
                service,
                profiles,
                resolver,
                properties(true)
        );

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isSameAs(failure);
        verify(profiles, never()).activeProfile();
        verify(resolver, never()).configuredProfile();
        verify(service, never()).migrateToConfiguredProfile();
    }

    private ReembeddingProperties properties(boolean autoMigrate) {
        return new ReembeddingProperties(
                autoMigrate,
                Duration.ofMinutes(2),
                Duration.ofMillis(250),
                Duration.ofMinutes(1),
                Duration.ofSeconds(15)
        );
    }
}
