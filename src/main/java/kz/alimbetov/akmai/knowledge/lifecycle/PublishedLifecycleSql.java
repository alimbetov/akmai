package kz.alimbetov.akmai.knowledge.lifecycle;

/**
 * Canonical SQL fragments for online lifecycle eligibility.
 *
 * <p>{@link #eligible(String)} is the backward-compatible boundary for callers
 * that expose only a lifecycle alias; it verifies the referenced published
 * generation with an indexed EXISTS lookup. Callers that already join the exact
 * generation should use {@link #publishedGenerationEligible(String, String)} to
 * express the same contract without a redundant subquery. Locking paths that
 * separately acquire the exact generation row may use
 * {@link #lifecycleEligible(String)} before that lock.</p>
 */
public final class PublishedLifecycleSql {

    private PublishedLifecycleSql() {
    }

    public static String eligible(String lifecycleAlias) {
        String lifecycle = requireAlias(lifecycleAlias);
        return lifecycleEligible(lifecycle)
                + " AND EXISTS ("
                + "SELECT 1 FROM knowledge_document_generation __akmai_generation "
                + "WHERE __akmai_generation.document_id = "
                + lifecycle + ".document_id"
                + " AND __akmai_generation.generation = "
                + lifecycle + ".published_generation"
                + " AND __akmai_generation.access_level = "
                + lifecycle + ".access_level"
                + " AND __akmai_generation.generation_status = 'PUBLISHED'"
                + ") ";
    }

    public static String publishedGenerationEligible(
            String lifecycleAlias,
            String generationAlias
    ) {
        String lifecycle = requireAlias(lifecycleAlias);
        String generation = requireAlias(generationAlias);
        return lifecycleEligible(lifecycle)
                + " AND " + lifecycle + ".published_generation = "
                + generation + ".generation"
                + " AND " + lifecycle + ".document_id = "
                + generation + ".document_id"
                + " AND " + lifecycle + ".access_level = "
                + generation + ".access_level"
                + " AND " + generation + ".generation_status = 'PUBLISHED' ";
    }

    /**
     * Lifecycle half of the canonical predicate. This intentionally does not
     * prove generation status and is therefore appropriate only when the caller
     * immediately proves/locks the exact published generation separately.
     */
    public static String lifecycleEligible(String lifecycleAlias) {
        String lifecycle = requireAlias(lifecycleAlias);
        return " " + lifecycle + ".lifecycle_status = 'READY'"
                + " AND " + lifecycle + ".retention_status = 'ACTIVE'"
                + " AND " + lifecycle + ".published_generation IS NOT NULL"
                + " AND (" + lifecycle + ".expires_at IS NULL"
                + " OR " + lifecycle + ".expires_at > clock_timestamp()) ";
    }

    private static String requireAlias(String alias) {
        if (alias == null || !alias.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "SQL alias must be a simple identifier"
            );
        }
        return alias;
    }
}
