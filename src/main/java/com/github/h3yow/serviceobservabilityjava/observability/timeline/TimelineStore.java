package com.github.h3yow.serviceobservabilityjava.observability.timeline;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内时间线存储，按关联标识查询。
 * 容量有界（默认 1000 条），超出后淘汰最旧记录，避免内存无限增长。
 */
@Component
public class TimelineStore {

    private static final int MAX_CAPACITY = 1000;

    private final Map<String, RequestTimeline> byCorrelationId = new ConcurrentHashMap<>();
    private final Deque<String> insertionOrder = new ArrayDeque<>();

    public RequestTimeline begin(String correlationId, String endpoint) {
        RequestTimeline timeline = new RequestTimeline(correlationId, endpoint);
        byCorrelationId.put(correlationId, timeline);
        synchronized (insertionOrder) {
            insertionOrder.addLast(correlationId);
            while (insertionOrder.size() > MAX_CAPACITY) {
                String evicted = insertionOrder.pollFirst();
                if (evicted != null) {
                    byCorrelationId.remove(evicted);
                }
            }
        }
        return timeline;
    }

    public Optional<RequestTimeline> find(String correlationId) {
        return Optional.ofNullable(byCorrelationId.get(correlationId));
    }

    /** 最近开始的 limit 条时间线（新的在前）。 */
    public List<RequestTimeline> recent(int limit) {
        List<String> ids;
        synchronized (insertionOrder) {
            ids = new ArrayList<>(insertionOrder);
        }
        List<RequestTimeline> result = new ArrayList<>();
        for (int i = ids.size() - 1; i >= 0 && result.size() < limit; i--) {
            RequestTimeline t = byCorrelationId.get(ids.get(i));
            if (t != null) {
                result.add(t);
            }
        }
        return result;
    }
}
