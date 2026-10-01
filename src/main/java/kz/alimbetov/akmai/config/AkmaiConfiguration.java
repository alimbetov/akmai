package kz.alimbetov.akmai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(VectorStorageProperties.class)
public class AkmaiConfiguration {
}
