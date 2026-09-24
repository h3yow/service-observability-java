package com.github.h3yow.serviceobservabilityjava.metrics;

/**
 * 有界耗时区间（上界含，单位毫秒）。最长一档 LT_2000 表示 [500, +inf)。
 */
public enum LatencyBucket {
    LT_50("<50ms", 0, 50),
    LT_100("50-100ms", 50, 100),
    LT_250("100-250ms", 100, 250),
    LT_500("250-500ms", 250, 500),
    LT_2000(">=500ms", 500, Long.MAX_VALUE);

    private final String label;
    private final long lowerInclusive;
    private final long upperExclusive;

    LatencyBucket(String label, long lowerInclusive, long upperExclusive) {
        this.label = label;
        this.lowerInclusive = lowerInclusive;
        this.upperExclusive = upperExclusive;
    }

    public String label() {
        return label;
    }

    public static LatencyBucket ofMillis(long durationMillis) {
        long d = Math.max(0L, durationMillis);
        for (LatencyBucket bucket : values()) {
            if (d >= bucket.lowerInclusive && d < bucket.upperExclusive) {
                return bucket;
            }
        }
        return LT_2000;
    }
}
