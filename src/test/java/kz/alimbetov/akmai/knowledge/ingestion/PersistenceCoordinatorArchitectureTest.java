package kz.alimbetov.akmai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PersistenceCoordinatorArchitectureTest {

    @Test
    void ingestionCoordinatorDoesNotOwnLongLivedJdbcOrAdvisoryLockDependency() {
        var constructor = PersistenceCoordinator.class.getConstructors()[0];

        assertThat(constructor.getParameterTypes())
                .doesNotContain(DataSource.class, Connection.class);
        assertThat(java.util.Arrays.stream(constructor.getParameterTypes())
                .map(Class::getSimpleName))
                .doesNotContain("DocumentOperationLock");
    }

    @Test
    void legacyDocumentOperationLockIsAbsentFromRuntimeClasspath() {
        assertThatThrownBy(() -> Class.forName(
                "kz.alimbetov.akmai.knowledge.lifecycle.DocumentOperationLock"
        )).isInstanceOf(ClassNotFoundException.class);
    }
}
