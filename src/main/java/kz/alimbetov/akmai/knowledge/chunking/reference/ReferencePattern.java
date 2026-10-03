package kz.alimbetov.akmai.knowledge.chunking.reference;

import java.util.List;
import kz.alimbetov.akmai.knowledge.reference.CrossReferenceType;
import kz.alimbetov.akmai.knowledge.reference.ReferenceTargetScope;

public interface ReferencePattern {

    List<RawMatch> find(String text);

    record RawMatch(
            int start,
            int end,
            CrossReferenceType type,
            String canonicalValue,
            String rawText,
            String language,
            ReferenceTargetScope targetScope,
            String targetDocumentId
    ) {
    }
}
