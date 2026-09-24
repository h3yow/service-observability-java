package com.github.h3yow.serviceobservabilityjava.metrics;

import com.github.h3yow.serviceobservabilityjava.timeline.OutcomeCategory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 进程内指标聚合器：按 (端点, 结果类别, 耗时区间) 计数与累计耗时。
 * <p>标签有界：类别/桶为枚举，端点标签必须是路由模板或
 * {@link #UNMATCHED_ENDPOINT} 等有界常量，不接受原始 URL。
 * <p>并发安全：ConcurrentHashMap + LongAdder；snapshot 为一致性快照，
 * 无流量时返回空列表，长时间运行取值只增不乱序。
 */
public final class MetricsRegistry {

    /** 未匹配到路由（如 404、标识非法等在路由解析前结束的请求）使用的有界端点常量。 */
    public static final String UNMATCHED_ENDPOINT = "-";

    private static final class Counters {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalMillis = new LongAdder();
    }

    private final Map<MetricKey, Counters> points = new ConcurrentHashMap<>();

    public MetricsRegistry() {
    }

    /** 记录一次请求。端点为空或含可疑高基数特征时归一为 UNMATCHED_ENDPOINT。 */
    public void record(String endpoint, OutcomeCategory category, long durationMillis) {
        MetricKey key = new MetricKey(normalizeEndpoint(endpoint), category,
                LatencyBucket.ofMillis(durationMillis));
        Counters counters = points.computeIfAbsent(key, k -> new Counters());
        counters.count.add(1);
        counters.totalMillis.add(Math.max(0L, durationMillis));
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return UNMATCHED_ENDPOINT;
        }
        // 双保险：含数字段/查询串特征的原始路径不允许成为标签。
        String e = endpoint;
        if (e.chars().anyMatch(Character::isDigit) || e.indexOf('?') >= 0) {
            return UNMATCHED_ENDPOINT;
        }
        return e;
    }

    /** 按 端点 -> 类别 -> 耗时桶 稳定排序的快照。 */
    public List<MetricPoint> snapshot() {
        List<MetricPoint> result = new ArrayList<>();
        points.forEach((key, counters) -> result.add(new MetricPoint(
                key.endpoint(),
                key.category().name(),
                key.bucket().label(),
                counters.count.sum(),
                counters.totalMillis.sum())));
        result.sort(Comparator.comparing(MetricPoint::endpoint)
                .thenComparing(MetricPoint::category)
                .thenComparing(MetricPoint::bucket));
        return List.copyOf(result);
    }

    public void clear() {
        points.clear();
    }
}
