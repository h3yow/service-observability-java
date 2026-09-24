package com.github.h3yow.serviceobservabilityjava.timeline;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 LinkedHashMap 的有界时间线存储：
 * 访问/保存更新最近顺序，超出容量淘汰最旧条目；所有读写加同一把锁保证并发一致。
 */
public final class InMemoryTimelineStore implements TimelineStore {

    private final int capacity;
    private final Map<String, RequestTimeline> byId = new LinkedHashMap<>();

    public InMemoryTimelineStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    @Override
    public synchronized void save(RequestTimeline timeline) {
        // 重存时先移除旧条目，使其移动到最近端。
        byId.remove(timeline.correlationId());
        byId.put(timeline.correlationId(), timeline);
        while (byId.size() > capacity) {
            Iterator<Map.Entry<String, RequestTimeline>> it = byId.entrySet().iterator();
            it.next();
            it.remove();
        }
    }

    @Override
    public synchronized Optional<RequestTimeline> find(String correlationId) {
        return Optional.ofNullable(byId.get(correlationId));
    }

    @Override
    public synchronized List<RequestTimeline> recent(int limit) {
        int max = Math.min(Math.max(limit, 0), byId.size());
        List<RequestTimeline> all = new ArrayList<>(byId.values());
        // LinkedHashMap 为最旧 -> 最新，反转得到最近优先。
        List<RequestTimeline> result = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            result.add(all.get(all.size() - 1 - i));
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized void clear() {
        byId.clear();
    }

    @Override
    public synchronized int size() {
        return byId.size();
    }
}
