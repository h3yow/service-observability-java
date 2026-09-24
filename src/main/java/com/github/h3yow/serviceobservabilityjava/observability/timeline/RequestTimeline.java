package com.github.h3yow.serviceobservabilityjava.observability.timeline;

import com.github.h3yow.serviceobservabilityjava.observability.metrics.ResultCategory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个请求的有序事件时间线。
 * 结果类别只允许设置一次：重试、嵌套调用或超时后的重复 finish 会被拒绝，
 * 保证一个请求只被计数一次。
 */
public class RequestTimeline {

    private final String correlationId;
    private final String endpoint;
    private final List<TimelineEvent> events = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile ResultCategory result;

    public RequestTimeline(String correlationId, String endpoint) {
        this.correlationId = correlationId;
        this.endpoint = endpoint;
    }

    /** 追加一个事件，返回其单调递增序号（从 1 开始）。 */
    public synchronized long append(String type, String detail) {
        long seq = sequence.incrementAndGet();
        events.add(new TimelineEvent(seq, Instant.now(), type, detail));
        return seq;
    }

    /**
     * 标记请求最终结果类别，只允许设置一次。
     *
     * @return true 表示本次设置生效；false 表示已有结果，本次被忽略（不重复计数）
     */
    public synchronized boolean finish(ResultCategory category) {
        if (result != null) {
            return false;
        }
        result = category;
        append("result", category.name());
        return true;
    }

    public String correlationId() {
        return correlationId;
    }

    public String endpoint() {
        return endpoint;
    }

    public ResultCategory result() {
        return result;
    }

    public synchronized List<TimelineEvent> events() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }
}
