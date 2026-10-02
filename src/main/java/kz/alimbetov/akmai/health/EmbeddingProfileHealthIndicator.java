package kz.alimbetov.akmai.health;

import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileResolver;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("embeddingProfile")
public class EmbeddingProfileHealthIndicator implements HealthIndicator {

    private final EmbeddingProfileResolver resolver;
    private final EmbeddingProfileService service;

    public EmbeddingProfileHealthIndicator(
            EmbeddingProfileResolver resolver,
            EmbeddingProfileService service
    ) {
        this.resolver = resolver;
        this.service = service;
    }

    @Override
    public Health health() {
        try {
            EmbeddingProfile configured = resolver.configuredProfile();
            EmbeddingProfile active = service.activeProfile();
            if (!configured.profileId().equals(active.profileId())) {
                return Health.down()
                        .withDetail("reason", "embedding profile mismatch")
                        .withDetail("activeProfile", active.profileId())
                        .withDetail("configuredProfile", configured.profileId())
                        .build();
            }
            return Health.up()
                    .withDetail("profileId", active.profileId())
                    .withDetail("dimensions", active.dimensions())
                    .build();
        } catch (RuntimeException exception) {
            return Health.down()
                    .withDetail("reason", exception.getClass().getSimpleName())
                    .build();
        }
    }
}
