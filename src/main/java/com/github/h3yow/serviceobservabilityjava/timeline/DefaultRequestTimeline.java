package com.github.h3yow.serviceobservabilityjava.timeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认时间线：AtomicLong 分配全局（请求内）单调序号，synchronized 追加保证可见性与有序。
 * 序号在加锁前分配，保证“先发生的事件序号一定更小”，读取返回不可变快照。
 */
public final class DefaultRequestTimeline implements RequestTimeline {

    private final String correlationId;
    private final String method;
    private final String path;
    private final long startNanos;
    private final AtomicLong sequence = new AtomicLong(0);
    private final List<TimelineEvent> events = new ArrayList<>();

    public DefaultRequestTimeline(String correlationId, String method, String path, long startNanos) {
        this.correlationId = correlationId;
        this.method = method;
        this.path = path;
        this.startNanos = startNanos;
    }

    @Override
    public String correlationId() {
        return correlationId;
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public long startNanos() {
        return startNanos;
    }

    @Override
    public long addEvent(TimelineEventName name, int attempt, String detail) {
        long seq = sequence.incrementAndGet();
        TimelineEvent event = new TimelineEvent(seq, System.nanoTime(), name, attempt,
                detail == null ? "" : detail);
        synchronized (events) {
            events.add(event);
        }
        return seq;
    }

    @Override
    public List<TimelineEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }
}
