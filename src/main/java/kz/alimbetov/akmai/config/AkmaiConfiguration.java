package kz.alimbetov.akmai.config;

import kz.alimbetov.akmai.knowledge.lifecycle.RetentionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({VectorStorageProperties.class, RetentionProperties.class})
public class AkmaiConfiguration {
}
