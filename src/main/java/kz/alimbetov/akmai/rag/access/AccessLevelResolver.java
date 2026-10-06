package kz.alimbetov.akmai.rag.access;

import java.util.Set;
import kz.alimbetov.akmai.rag.api.QuestionRequest;

public interface AccessLevelResolver {

    Set<Long> resolve(QuestionRequest request);

    default Set<Long> resolve(Set<Long> requestedAccessLevels) {
        return resolve(new QuestionRequest("access-scope", requestedAccessLevels));
    }
}
