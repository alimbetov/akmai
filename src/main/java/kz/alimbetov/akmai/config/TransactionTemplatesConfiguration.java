package kz.alimbetov.akmai.config;

import java.time.Duration;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class TransactionTemplatesConfiguration {

    @Bean
    @Primary
    public TransactionTemplate transactionTemplate(
            PlatformTransactionManager manager
    ) {
        return new TransactionTemplate(manager);
    }

    @Bean(name = "retrievalTransactionTemplate")
    public TransactionTemplate retrievalTransactionTemplate(
            PlatformTransactionManager manager,
            kz.alimbetov.akmai.rag.retrieval.RetrievalProperties properties
    ) {
        TransactionTemplate template = bounded(
                manager,
                properties.strategyTimeout()
        );
        template.setReadOnly(true);
        return template;
    }

    @Bean(name = "publicationTransactionTemplate")
    public TransactionTemplate publicationTransactionTemplate(
            PlatformTransactionManager manager,
            VectorStorageProperties vectorProperties
    ) {
        return bounded(manager, vectorProperties.dbTransactionTimeout());
    }

    @Bean(name = "graphMutationTransactionTemplate")
    public TransactionTemplate graphMutationTransactionTemplate(
            PlatformTransactionManager manager,
            AdaptiveGraphProperties graphProperties
    ) {
        return bounded(manager, graphProperties.dream().transactionTimeout());
    }

    @Bean(name = "cleanupTransactionTemplate")
    public TransactionTemplate cleanupTransactionTemplate(
            PlatformTransactionManager manager,
            RetentionProperties retentionProperties,
            RetentionCleanupProperties cleanupProperties
    ) {
        Duration timeout = cleanupProperties.cleanupTransactionTimeout();
        Duration halfLease = retentionProperties.leaseDuration().dividedBy(2);
        if (timeout.compareTo(halfLease) >= 0) {
            throw new IllegalStateException(
                    "retention cleanup transaction timeout must be less than half the lease duration"
            );
        }
        return bounded(manager, timeout);
    }

    @Bean(name = "repairTransactionTemplate")
    public TransactionTemplate repairTransactionTemplate(
            PlatformTransactionManager manager,
            RetentionCleanupProperties cleanupProperties
    ) {
        TransactionTemplate template = bounded(
                manager,
                cleanupProperties.cleanupTransactionTimeout()
        );
        template.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        return template;
    }

    @Bean(name = "reembeddingTransactionTemplate")
    public TransactionTemplate reembeddingTransactionTemplate(
            PlatformTransactionManager manager,
            VectorStorageProperties vectorProperties
    ) {
        return bounded(manager, vectorProperties.dbTransactionTimeout());
    }

    private TransactionTemplate bounded(
            PlatformTransactionManager manager,
            Duration timeout
    ) {
        TransactionTemplate template = new TransactionTemplate(manager);
        long seconds = Math.max(1L, (timeout.toMillis() + 999L) / 1000L);
        template.setTimeout(Math.toIntExact(
                Math.min(seconds, Integer.MAX_VALUE)
        ));
        return template;
    }
}
