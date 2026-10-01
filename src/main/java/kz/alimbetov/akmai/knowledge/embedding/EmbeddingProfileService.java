package kz.alimbetov.akmai.knowledge.embedding;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingProfileService implements ApplicationRunner {

    private final EmbeddingProfileResolver resolver;
    private final EmbeddingProfileRepository repository;
    private final EmbeddingProfileStorageManager storageManager;

    public EmbeddingProfileService(
            EmbeddingProfileResolver resolver,
            EmbeddingProfileRepository repository,
            EmbeddingProfileStorageManager storageManager
    ) {
        this.resolver = resolver;
        this.repository = repository;
        this.storageManager = storageManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureConfiguredProfile();
    }

    public EmbeddingProfile ensureConfiguredProfile() {
        EmbeddingProfile configured = resolver.configuredProfile();
        storageManager.ensureStorage(configured);
        repository.save(configured);
        repository.activateIfEmpty(configured.profileId());
        return configured;
    }

    public EmbeddingProfile activeProfile() {
        EmbeddingRuntime runtime = repository.runtime();
        if (runtime.activeProfileId() == null) {
            return ensureConfiguredProfile();
        }
        return repository.findById(runtime.activeProfileId())
                .orElseThrow(() -> new IllegalStateException(
                        "Active embedding profile is missing: "
                                + runtime.activeProfileId()
                ));
    }

    public void assertConfiguredProfileIsActive() {
        EmbeddingProfile configured = resolver.configuredProfile();
        EmbeddingProfile active = activeProfile();
        if (!configured.profileId().equals(active.profileId())) {
            throw new IllegalStateException(
                    "Configured embedding profile does not match corpus active profile"
            );
        }
    }
}
