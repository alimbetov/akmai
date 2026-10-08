package kz.alimbetov.akmai.util;

import java.time.Duration;

/**
 * Safe conversion of application durations to Spring/JDBC whole-second
 * timeout values.
 */
public final class TransactionTimeouts {

    private TransactionTimeouts() {
    }

    public static int seconds(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long seconds = timeout.toSeconds();
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, seconds));
    }
}
