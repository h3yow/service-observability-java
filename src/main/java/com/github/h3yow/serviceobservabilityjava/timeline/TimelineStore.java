package com.github.h3yow.serviceobservabilityjava.timeline;

import java.util.List;
import java.util.Optional;

/**
 * 进程内时间线存储。容量有界，超出时淘汰最旧条目，避免内存无界增长。
 */
public interface TimelineStore {

    void save(RequestTimeline timeline);

    Optional<RequestTimeline> find(String correlationId);

    /** 最近优先的摘要列表（有界）。 */
    List<RequestTimeline> recent(int limit);

    void clear();

    int size();
}
