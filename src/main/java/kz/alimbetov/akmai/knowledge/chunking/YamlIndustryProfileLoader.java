package kz.alimbetov.akmai.knowledge.chunking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class YamlIndustryProfileLoader {

    private static final String PROFILE_PATTERN =
            "classpath*:industries/**/*.yaml";

    private final ObjectMapper yamlMapper =
            new ObjectMapper(new YAMLFactory());
    private final PathMatchingResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver();

    public List<IndustryProfile> loadAll() {
        try {
            Resource[] resources = resolver.getResources(PROFILE_PATTERN);
            Arrays.sort(
                    resources,
                    java.util.Comparator.comparing(Resource::getDescription)
            );
            return Arrays.stream(resources)
                    .map(this::load)
                    .map(GenericIndustryProfile::new)
                    .map(IndustryProfile.class::cast)
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to load industry profiles",
                    exception
            );
        }
    }

    private IndustryDefinition load(Resource resource) {
        try (var input = resource.getInputStream()) {
            return yamlMapper.readValue(
                    input,
                    IndustryDefinition.class
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to load " + resource.getDescription(),
                    exception
            );
        }
    }
}
