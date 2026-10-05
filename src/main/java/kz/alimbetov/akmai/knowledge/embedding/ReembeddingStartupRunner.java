package kz.alimbetov.akmai.knowledge.embedding;

import kz.alimbetov.akmai.config.ReembeddingProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
public class ReembeddingStartupRunner implements ApplicationRunner {

    private final ReembeddingService service;
    private final EmbeddingProfileService profiles;
    private final EmbeddingProfileResolver resolver;
    private final ReembeddingProperties properties;

    public ReembeddingStartupRunner(
            ReembeddingService service,
            EmbeddingProfileService profiles,
            EmbeddingProfileResolver resolver,
            ReembeddingProperties properties
    ) {
        this.service = service;
        this.profiles = profiles;
        this.resolver = resolver;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        service.recoverExpiredMigration();
        if (!properties.autoMigrate()) {
            return;
        }

        EmbeddingProfile active = profiles.activeProfile();
        EmbeddingProfile configured = resolver.configuredProfile();
        if (!active.profileId().equals(configured.profileId())) {
            service.migrateToConfiguredProfile();
        }
    }
}
