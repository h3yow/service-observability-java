package com.github.h3yow.serviceobservabilityjava.observability.metrics;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 进程内指标聚合：按 端点 × 结果类别 × 耗时区间 计数。
 * 标签全部来自有界枚举与受控端点集合，避免高基数。
 * 使用 LongAdder 保证并发写下计数一致；快照为弱一致读取，长时间无流量时值保持稳定。
 */
@Component
public class MetricsRegistry {

    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final LongAdder total = new LongAdder();

    public void record(String endpoint, ResultCategory category, long latencyMillis) {
        LatencyBucket bucket = LatencyBucket.ofMillis(latencyMillis);
        String key = endpoint + "|" + category.name() + "|" + bucket.name();
        counters.computeIfAbsent(key, k -> new LongAdder()).increment();
        total.increment();
    }

    /** 当前聚合快照：key 形如 endpoint|category|bucket，按 key 排序输出，稳定可读。 */
    public Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        counters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> result.put(e.getKey(), e.getValue().sum()));
        return result;
    }

    /** 已记录的请求总数。 */
    public long total() {
        return total.sum();
    }
}
