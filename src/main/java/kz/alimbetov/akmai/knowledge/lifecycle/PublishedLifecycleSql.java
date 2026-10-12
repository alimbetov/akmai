package kz.alimbetov.akmai.knowledge.lifecycle;

/**
 * Canonical read-side lifecycle predicate for retrieval-visible knowledge.
 *
 * <p>Repository-specific SQL remains responsible for joining exact
 * access-level/document/generation identity. This helper owns only the shared
 * lifecycle state and TTL semantics so candidate-producing paths cannot drift
 * from the final synchronous fence.</p>
 */
public final class PublishedLifecycleSql {

    private PublishedLifecycleSql() {
    }

    public static String eligible(String alias) {
        if (alias == null || !alias.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("SQL alias must be a simple identifier");
        }
        return " " + alias + ".lifecycle_status = 'READY'"
                + " AND " + alias + ".retention_status = 'ACTIVE'"
                + " AND (" + alias + ".expires_at IS NULL"
                + " OR " + alias + ".expires_at > clock_timestamp()) ";
    }
}
