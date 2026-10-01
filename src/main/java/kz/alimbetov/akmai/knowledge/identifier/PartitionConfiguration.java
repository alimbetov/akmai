package kz.alimbetov.akmai.knowledge.identifier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PartitionProperties.class)
public class PartitionConfiguration {
}
