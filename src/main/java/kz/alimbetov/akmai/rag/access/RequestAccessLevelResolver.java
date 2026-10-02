package kz.alimbetov.akmai.rag.access;

import java.util.Set;
import java.util.TreeSet;
import kz.alimbetov.akmai.rag.api.QuestionRequest;
import org.springframework.stereotype.Component;

@Component
public class RequestAccessLevelResolver implements AccessLevelResolver {

    @Override
    public Set<Long> resolve(QuestionRequest request) {
        if (request == null
                || request.accessLevels() == null
                || request.accessLevels().isEmpty()) {
            return Set.of();
        }

        TreeSet<Long> normalized = new TreeSet<>();
        for (Long value : request.accessLevels()) {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException(
                        "accessLevels must contain only positive values"
                );
            }
            normalized.add(value);
        }
        return Set.copyOf(normalized);
    }
}
