package kz.alimbetov.akmai.knowledge.chunking;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        ChunkingProperties.class,
        ParentChildProperties.class
})
public class ChunkingConfig {
}
