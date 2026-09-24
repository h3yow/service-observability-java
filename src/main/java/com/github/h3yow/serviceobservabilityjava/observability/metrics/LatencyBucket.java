package com.github.h3yow.serviceobservabilityjava.observability.metrics;

/** 固定的耗时区间（标签有界，避免高基数）。 */
public enum LatencyBucket {
    LE_10MS,
    LE_100MS,
    LE_500MS,
    LE_1S,
    LE_5S,
    GT_5S;

    public static LatencyBucket ofMillis(long millis) {
        if (millis <= 10) {
            return LE_10MS;
        }
        if (millis <= 100) {
            return LE_100MS;
        }
        if (millis <= 500) {
            return LE_500MS;
        }
        if (millis <= 1000) {
            return LE_1S;
        }
        if (millis <= 5000) {
            return LE_5S;
        }
        return GT_5S;
    }
}
