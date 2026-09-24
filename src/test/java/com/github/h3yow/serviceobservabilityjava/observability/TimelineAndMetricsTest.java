package com.github.h3yow.serviceobservabilityjava.observability;

import com.github.h3yow.serviceobservabilityjava.observability.metrics.LatencyBucket;
import com.github.h3yow.serviceobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.h3yow.serviceobservabilityjava.observability.metrics.ResultCategory;
import com.github.h3yow.serviceobservabilityjava.observability.timeline.RequestTimeline;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 时间线有序性与一次性结果、指标并发一致性与空闲稳定性。 */
class TimelineAndMetricsTest {

    private static final Logger log = LoggerFactory.getLogger(TimelineAndMetricsTest.class);

    @Test
    void eventsAreOrderedByMonotonicSequence() {
        RequestTimeline timeline = new RequestTimeline("corr-timeline-01", "GET /x");
        long s1 = timeline.append("a", "1");
        long s2 = timeline.append("b", "2");
        long s3 = timeline.append("c", "3");
        log.info("sequences: {} {} {}", s1, s2, s3);
        assertTrue(s1 < s2 && s2 < s3);
        List<String> types = timeline.events().stream().map(e -> e.type()).toList();
        assertEquals(List.of("a", "b", "c"), types);
    }

    @Test
    void finishOnlyCountsOnce() {
        RequestTimeline timeline = new RequestTimeline("corr-finish-once", "GET /x");
        assertTrue(timeline.finish(ResultCategory.SUCCESS));
        // 模拟重试/嵌套/超时路径的重复 finish：全部被拒绝
        assertFalse(timeline.finish(ResultCategory.SYSTEM_ERROR));
        assertFalse(timeline.finish(ResultCategory.BUSINESS_REJECTION));
        assertEquals(ResultCategory.SUCCESS, timeline.result());
        log.info("result stays {} after duplicate finish attempts", timeline.result());
    }

    @Test
    void latencyBucketBoundaries() {
        record Case(long millis, LatencyBucket expected) {
        }
        List<Case> cases = List.of(
                new Case(0, LatencyBucket.LE_10MS),
                new Case(10, LatencyBucket.LE_10MS),
                new Case(11, LatencyBucket.LE_100MS),
                new Case(100, LatencyBucket.LE_100MS),
                new Case(101, LatencyBucket.LE_500MS),
                new Case(500, LatencyBucket.LE_500MS),
                new Case(501, LatencyBucket.LE_1S),
                new Case(1000, LatencyBucket.LE_1S),
                new Case(1001, LatencyBucket.LE_5S),
                new Case(5000, LatencyBucket.LE_5S),
                new Case(5001, LatencyBucket.GT_5S));
        for (Case c : cases) {
            LatencyBucket actual = LatencyBucket.ofMillis(c.millis());
            log.info("latency={}ms -> bucket={} (expected={})", c.millis(), actual, c.expected());
            assertEquals(c.expected(), actual);
        }
    }

    @Test
    void concurrentWritesAreCountedExactly() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < perThread; i++) {
                    registry.record("GET /api/demo/ok", ResultCategory.SUCCESS, 5);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long expected = (long) threads * perThread;
        Map<String, Long> snapshot = registry.snapshot();
        log.info("expected total={} actual total={} snapshot={}", expected, registry.total(), snapshot);
        assertEquals(expected, registry.total());
        assertEquals(expected, snapshot.get("GET /api/demo/ok|SUCCESS|LE_10MS"));
        long sum = snapshot.values().stream().mapToLong(Long::longValue).sum();
        assertEquals(registry.total(), sum, "各桶之和必须等于总数");
    }

    @Test
    void snapshotIsStableWhenIdle() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        registry.record("GET /a", ResultCategory.SUCCESS, 5);
        registry.record("GET /a", ResultCategory.SYSTEM_ERROR, 6000);
        Map<String, Long> first = registry.snapshot();
        Thread.sleep(50); // 长时间无流量的缩小模拟
        Map<String, Long> second = registry.snapshot();
        log.info("idle stability: first={} second={}", first, second);
        assertEquals(first, second, "无流量时快照必须保持稳定");
        assertEquals(2, registry.total());
    }
}
