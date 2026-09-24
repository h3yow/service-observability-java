package com.github.h3yow.serviceobservabilityjava.api;

import com.github.h3yow.serviceobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.h3yow.serviceobservabilityjava.observability.timeline.RequestTimeline;
import com.github.h3yow.serviceobservabilityjava.observability.timeline.TimelineStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 可观测性查询端点：时间线 / 指标 / 健康状态。 */
@RestController
public class ObservabilityController {

    private final TimelineStore timelineStore;
    private final MetricsRegistry metricsRegistry;

    public ObservabilityController(TimelineStore timelineStore, MetricsRegistry metricsRegistry) {
        this.timelineStore = timelineStore;
        this.metricsRegistry = metricsRegistry;
    }

    @GetMapping("/observability/timeline/{correlationId}")
    public ResponseEntity<Map<String, Object>> timeline(@PathVariable String correlationId) {
        return timelineStore.find(correlationId)
                .map(t -> ResponseEntity.ok(toMap(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/observability/timelines")
    public Map<String, Object> recent(@RequestParam(defaultValue = "20") int limit) {
        int capped = Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> timelines = timelineStore.recent(capped).stream()
                .map(this::toMap)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", timelines.size());
        body.put("timelines", timelines);
        return body;
    }

    @GetMapping("/observability/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", metricsRegistry.total());
        body.put("counters", metricsRegistry.snapshot());
        return body;
    }

    @GetMapping("/observability/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("uptimeMillis", ManagementFactory.getRuntimeMXBean().getUptime());
        body.put("totalRequests", metricsRegistry.total());
        body.put("freeMemoryBytes", Runtime.getRuntime().freeMemory());
        return body;
    }

    private Map<String, Object> toMap(RequestTimeline t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("correlationId", t.correlationId());
        m.put("endpoint", t.endpoint());
        m.put("result", t.result() == null ? "IN_PROGRESS" : t.result().name());
        m.put("events", t.events());
        return m;
    }
}
