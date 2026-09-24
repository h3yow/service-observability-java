package com.github.h3yow.serviceobservabilityjava.tests;

import com.github.h3yow.serviceobservabilityjava.metrics.LatencyBucket;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 耗时区间边界单测：下界含、上界不含（最后一档无上界）。
 */
class LatencyBucketTest {

    private static final Logger log = LoggerFactory.getLogger(LatencyBucketTest.class);

    @Test
    void bucketBoundariesAreInclusiveLowerExclusiveUpper() {
        check(0L, LatencyBucket.LT_50);
        check(49L, LatencyBucket.LT_50);
        check(50L, LatencyBucket.LT_100);
        check(100L, LatencyBucket.LT_250);
        check(250L, LatencyBucket.LT_500);
        check(499L, LatencyBucket.LT_500);
        check(500L, LatencyBucket.LT_2000);
        check(5_000_000L, LatencyBucket.LT_2000, "任意超长耗时都收敛到最长有界档");
        check(-7L, LatencyBucket.LT_50, "负耗时按 0 处理");
    }

    private static void check(long millis, LatencyBucket expected) {
        check(millis, expected, expected.label());
    }

    private static void check(long millis, LatencyBucket expected, String note) {
        LatencyBucket actual = LatencyBucket.ofMillis(millis);
        log.info("durationMillis={} -> bucket={} ({})", millis, actual, note);
        assertEquals(expected, actual);
    }
}
