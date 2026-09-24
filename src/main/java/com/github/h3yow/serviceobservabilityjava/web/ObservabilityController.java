package com.github.h3yow.serviceobservabilityjava.web;

import com.github.h3yow.serviceobservabilityjava.metrics.MetricPoint;
import com.github.h3yow.serviceobservabilityjava.metrics.MetricsRegistry;
import com.github.h3yow.serviceobservabilityjava.timeline.RequestTimeline;
import com.github.h3yow.serviceobservabilityjava.timeline.TimelineEvent;
import com.github.h3yow.serviceobservabilityjava.timeline.TimelineStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可观测性查询端点：健康状态、聚合指标、按关联标识查询事件时间线；
 * POST /observability/admin/reset 仅供本地验证/测试重置内存态。
 */
@RestController
@RequestMapping("/observability")
public final class ObservabilityController {

    private final TimelineStore timelineStore;
    private final MetricsRegistry metricsRegistry;

    public ObservabilityController(TimelineStore timelineStore, MetricsRegistry metricsRegistry) {
        this.timelineStore = timelineStore;
        this.metricsRegistry = metricsRegistry;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("timelinesStored", timelineStore.size());
        body.put("components", Map.of(
                "timelineStore", "UP",
                "metricsRegistry", "UP",
                "downstreamSimulator", "UP (in-process fault injection)"));
        return body;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        List<MetricPoint> points = metricsRegistry.snapshot();
        long total = points.stream().mapToLong(MetricPoint::count).sum();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalRequests", total);
        body.put("points", points);
        return body;
    }

    @GetMapping("/timelines/{correlationId}")
    public ResponseEntity<Map<String, Object>> timeline(@PathVariable String correlationId) {
        return timelineStore.find(correlationId)
                .map(t -> ResponseEntity.ok(renderTimeline(t)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("found", false, "correlationId", correlationId)));
    }

    @PostMapping("/admin/reset")
    public Map<String, Object> reset() {
        timelineStore.clear();
        metricsRegistry.clear();
        return Map.of("reset", true);
    }

    private static Map<String, Object> renderTimeline(RequestTimeline timeline) {
        long origin = timeline.startNanos();
        List<Map<String, Object>> events = timeline.events().stream()
                .map(e -> renderEvent(e, origin))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("found", true);
        body.put("correlationId", timeline.correlationId());
        body.put("method", timeline.method());
        body.put("path", timeline.path());
        body.put("eventCount", events.size());
        body.put("events", events);
        return body;
    }

    private static Map<String, Object> renderEvent(TimelineEvent event, long originNanos) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("seq", event.seq());
        map.put("elapsedMicros", (event.eventNanos() - originNanos) / 1000L);
        map.put("name", event.name().name());
        map.put("attempt", event.attempt());
        map.put("detail", event.detail());
        return map;
    }
}
