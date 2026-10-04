package kz.alimbetov.akmai.runtimeconfig;

import java.util.Optional;

public interface AppParameterRepository {

    Optional<AppParameter> find(String key);

    Optional<AppParameter> updateBoolean(
            String key,
            boolean value,
            long expectedVersion,
            String updatedBy
    );
}
