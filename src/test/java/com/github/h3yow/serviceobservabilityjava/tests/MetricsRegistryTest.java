package com.github.h3yow.serviceobservabilityjava.tests;

import com.github.h3yow.serviceobservabilityjava.metrics.MetricPoint;
import com.github.h3yow.serviceobservabilityjava.metrics.MetricsRegistry;
import com.github.h3yow.serviceobservabilityjava.timeline.OutcomeCategory;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内指标聚合单测：并发写入计数一致、无流量/长时间无写入快照稳定、高基数标签归一。
 */
class MetricsRegistryTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsRegistryTest.class);

    private static Map<String, Long> countsByKey(List<MetricPoint> points) {
        return points.stream().collect(Collectors.toMap(
                p -> p.endpoint() + "|" + p.category() + "|" + p.bucket(), MetricPoint::count,
                (a, b) -> a + b));
    }

    @Test
    void emptyRegistryHasStableEmptySnapshot() {
        MetricsRegistry registry = new MetricsRegistry();
        assertTrue(registry.snapshot().isEmpty());
        // 模拟长时间无流量：重复快照仍为空，不出现幽灵数据。
        assertTrue(registry.snapshot().isEmpty());
        assertEquals(0L, registry.snapshot().stream().mapToLong(MetricPoint::count).sum());
    }

    @Test
    void concurrentWritesProduceExactCounts() throws InterruptedException {
        MetricsRegistry registry = new MetricsRegistry();
        int threads = 24;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        registry.record("/api/process", OutcomeCategory.SUCCESS, 10L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        long total = registry.snapshot().stream().mapToLong(MetricPoint::count).sum();
        assertEquals((long) threads * perThread, total, "并发写入总数必须精确无丢失");
        Map<String, Long> byKey = countsByKey(registry.snapshot());
        assertEquals(1L, byKey.size(), "同一键只应有一个聚合点");
        long count = byKey.get("/api/process|SUCCESS|<50ms");
        assertEquals((long) threads * perThread, count);
        log.info("concurrent aggregate: {}", registry.snapshot());
    }

    @Test
    void snapshotOrderingIsStable() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.record("/api/ping", OutcomeCategory.SUCCESS, 5L);
        registry.record("/api/process", OutcomeCategory.SYSTEM_ERROR, 600L);
        registry.record("/api/process", OutcomeCategory.SUCCESS, 120L);
        List<MetricPoint> first = registry.snapshot();
        List<MetricPoint> second = registry.snapshot();
        assertEquals(first, second, "相同数据下快照顺序必须稳定");
        // 按 端点 -> 类别 -> 桶 排序
        assertEquals("/api/ping", first.get(0).endpoint());
        log.info("ordered snapshot: {}", first);
    }

    @Test
    void highCardinalityRawPathsAreNormalizedToBoundedLabel() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.record("/items/1001", OutcomeCategory.SUCCESS, 5L);
        registry.record("/items/1002", OutcomeCategory.SUCCESS, 5L);
        registry.record("/items/9999?foo=bar", OutcomeCategory.SUCCESS, 5L);
        registry.record(null, OutcomeCategory.INVALID_REQUEST, 1L);
        List<MetricPoint> points = registry.snapshot();
        assertTrue(points.stream().noneMatch(p -> p.endpoint().contains("1001")
                || p.endpoint().contains("1002") || p.endpoint().contains("9999")),
                "原始路径/ID/查询串不得成为指标标签");
        long dashCount = points.stream().filter(p -> p.endpoint().equals("-"))
                .mapToLong(MetricPoint::count).sum();
        assertEquals(4L, dashCount, "所有高基数/空端点应归一到有界常量 '-'");
    }
}
