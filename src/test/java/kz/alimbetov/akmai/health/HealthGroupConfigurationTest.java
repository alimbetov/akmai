package kz.alimbetov.akmai.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class HealthGroupConfigurationTest {

    @Test
    void aiDependenciesAffectReadinessButNotLiveness() throws Exception {
        List<PropertySource<?>> sources = applicationPropertySources();

        Object liveness = property(
                sources,
                "management.endpoint.health.group.liveness.include"
        );
        Object readiness = property(
                sources,
                "management.endpoint.health.group.readiness.include"
        );

        String livenessValue = String.valueOf(liveness);
        String readinessValue = String.valueOf(readiness);

        assertThat(livenessValue)
                .contains("livenessState")
                .contains("ping")
                .doesNotContain("ollama")
                .doesNotContain("embeddingProfile")
                .doesNotContain("pgvector");

        assertThat(readinessValue)
                .contains("readinessState")
                .contains("db")
                .contains("ollama")
                .contains("embeddingProfile")
                .contains("pgvector");
    }

    private List<PropertySource<?>> applicationPropertySources()
            throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:application.yml");
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = new ArrayList<>();
        for (int index = 0; index < resources.length; index++) {
            sources.addAll(loader.load("application-" + index, resources[index]));
        }
        return sources;
    }

    private Object property(
            List<PropertySource<?>> sources,
            String name
    ) {
        return sources.stream()
                .map(source -> source.getProperty(name))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing property: " + name
                ));
    }
}
