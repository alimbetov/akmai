package kz.alimbetov.akmai.knowledge.chunking.reference;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kz.alimbetov.akmai.knowledge.reference.ReferenceTargetScope;

public final class ReferenceScopeResolver {

    private static final int CONTEXT_LIMIT = 96;
    private static final Pattern EXPLICIT_CODE = Pattern.compile(
            "(?iu)^\\s*[,;:]?\\s*"
                    + "(ГК|УК|УПК|ГПК|КоАП|НК|ТК|БК|ЗК)\\s+(РК|РФ)"
                    + "(?![\\p{L}\\p{N}])"
    );

    public ScopeResolution resolve(
            String text,
            ReferencePattern.RawMatch match
    ) {
        if (match.targetScope() == ReferenceTargetScope.EXPLICIT_DOCUMENT) {
            return new ScopeResolution(
                    match.targetScope(),
                    match.targetDocumentId()
            );
        }
        if (text == null || match.end() >= text.length()) {
            return ScopeResolution.sameDocument();
        }

        String context = text.substring(
                match.end(),
                Math.min(text.length(), match.end() + CONTEXT_LIMIT)
        );
        Matcher explicit = EXPLICIT_CODE.matcher(context);
        if (!explicit.find()) {
            return ScopeResolution.sameDocument();
        }

        String target = explicit.group(1).toUpperCase(Locale.ROOT)
                + " "
                + explicit.group(2).toUpperCase(Locale.ROOT);
        return new ScopeResolution(
                ReferenceTargetScope.EXPLICIT_DOCUMENT,
                target
        );
    }

    public record ScopeResolution(
            ReferenceTargetScope scope,
            String targetDocumentId
    ) {
        public static ScopeResolution sameDocument() {
            return new ScopeResolution(
                    ReferenceTargetScope.SAME_DOCUMENT,
                    null
            );
        }
    }
}
