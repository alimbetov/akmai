package kz.alimbetov.akmai.knowledge.lifecycle;

/**
 * Canonical SQL fragments for online lifecycle eligibility.
 *
 * <p>The lifecycle-only predicate is retained for callers that already bind an
 * exact published generation through another trusted relation. New online paths
 * should prefer {@link #publishedGenerationEligible(String, String)} so the
 * lifecycle row and the physical generation state are proven together.</p>
 */
public final class PublishedLifecycleSql {

    private PublishedLifecycleSql() {
    }

    public static String eligible(String lifecycleAlias) {
        String alias = requireAlias(lifecycleAlias);
        return " " + alias + ".lifecycle_status = 'READY'"
                + " AND " + alias + ".retention_status = 'ACTIVE'"
                + " AND (" + alias + ".expires_at IS NULL"
                + " OR " + alias + ".expires_at > clock_timestamp()) ";
    }

    public static String publishedGenerationEligible(
            String lifecycleAlias,
            String generationAlias
    ) {
        String lifecycle = requireAlias(lifecycleAlias);
        String generation = requireAlias(generationAlias);
        return eligible(lifecycle)
                + " AND " + lifecycle + ".published_generation = "
                + generation + ".generation"
                + " AND " + lifecycle + ".document_id = "
                + generation + ".document_id"
                + " AND " + lifecycle + ".access_level = "
                + generation + ".access_level"
                + " AND " + generation + ".generation_status = 'PUBLISHED' ";
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
