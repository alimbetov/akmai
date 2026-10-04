package kz.alimbetov.akmai.runtimeconfig;

import java.util.List;
import java.util.Optional;

public interface AppParameterRepository {

    Optional<AppParameter> find(String key);

    List<AppParameter> lockAll(List<String> keys);

    Optional<AppParameter> updateBoolean(
            String key,
            boolean value,
            long expectedVersion,
            String updatedBy
    );
}
