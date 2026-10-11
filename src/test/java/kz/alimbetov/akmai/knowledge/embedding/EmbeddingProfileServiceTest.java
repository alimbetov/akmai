package kz.alimbetov.akmai.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.DefaultApplicationArguments;

class EmbeddingProfileServiceTest {

    @Test
    void startupEnsuresStorageBeforePersistingAndActivatingProfile() {
        EmbeddingProfileResolver resolver = mock(EmbeddingProfileResolver.class);
        EmbeddingProfileRepository repository = mock(EmbeddingProfileRepository.class);
        EmbeddingProfileStorageManager storage =
                mock(EmbeddingProfileStorageManager.class);
        EmbeddingProfile configured = profile("profile-a");
        when(resolver.configuredProfile()).thenReturn(configured);

        EmbeddingProfileService service = new EmbeddingProfileService(
                resolver,
                repository,
                storage
        );

        service.run(new DefaultApplicationArguments(new String[0]));

        InOrder order = inOrder(storage, repository);
        order.verify(storage).ensureStorage(configured);
        order.verify(repository).save(configured);
        order.verify(repository).activateIfEmpty("profile-a");
    }

    @Test
    void activeProfileBootstrapsConfiguredProfileWhenRuntimeIsEmpty() {
        EmbeddingProfileResolver resolver = mock(EmbeddingProfileResolver.class);
        EmbeddingProfileRepository repository = mock(EmbeddingProfileRepository.class);
        EmbeddingProfileStorageManager storage =
                mock(EmbeddingProfileStorageManager.class);
        EmbeddingProfile configured = profile("profile-a");
        when(resolver.configuredProfile()).thenReturn(configured);
        when(repository.runtime()).thenReturn(runtime(null));

        EmbeddingProfileService service = new EmbeddingProfileService(
                resolver,
                repository,
                storage
        );

        assertThat(service.activeProfile()).isEqualTo(configured);
        verify(storage).ensureStorage(configured);
        verify(repository).save(configured);
        verify(repository).activateIfEmpty("profile-a");
    }

    @Test
    void activeProfileFailsClosedWhenRuntimeReferencesMissingProfile() {
        EmbeddingProfileResolver resolver = mock(EmbeddingProfileResolver.class);
        EmbeddingProfileRepository repository = mock(EmbeddingProfileRepository.class);
        EmbeddingProfileStorageManager storage =
                mock(EmbeddingProfileStorageManager.class);
        when(repository.runtime()).thenReturn(runtime("missing-profile"));
        when(repository.findById("missing-profile")).thenReturn(Optional.empty());

        EmbeddingProfileService service = new EmbeddingProfileService(
                resolver,
                repository,
                storage
        );

        assertThatThrownBy(service::activeProfile)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing-profile");
    }

    @Test
    void configuredProfileMismatchFailsClosed() {
        EmbeddingProfileResolver resolver = mock(EmbeddingProfileResolver.class);
        EmbeddingProfileRepository repository = mock(EmbeddingProfileRepository.class);
        EmbeddingProfileStorageManager storage =
                mock(EmbeddingProfileStorageManager.class);
        EmbeddingProfile configured = profile("profile-a");
        EmbeddingProfile active = profile("profile-b");
        when(resolver.configuredProfile()).thenReturn(configured);
        when(repository.runtime()).thenReturn(runtime("profile-b"));
        when(repository.findById("profile-b")).thenReturn(Optional.of(active));

        EmbeddingProfileService service = new EmbeddingProfileService(
                resolver,
                repository,
                storage
        );

        assertThatThrownBy(service::assertConfiguredProfileIsActive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
    }

    private static EmbeddingRuntime runtime(String activeProfileId) {
        return new EmbeddingRuntime(
                activeProfileId,
                null,
                EmbeddingRuntime.MigrationStatus.IDLE,
                1,
                Instant.parse("2026-10-10T00:00:00Z")
        );
    }

    private static EmbeddingProfile profile(String id) {
        return new EmbeddingProfile(
                id,
                "local",
                "model-v1",
                3,
                "COSINE_DISTANCE",
                "tokenizer-v1",
                "fingerprint-" + id,
                "public",
                "vectors_" + id.replace('-', '_'),
                "HNSW",
                (short) 1,
                Instant.parse("2026-10-10T00:00:00Z")
        );
    }
}
