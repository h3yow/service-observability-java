package com.github.h3yow.serviceobservabilityjava.timeline;

import java.util.List;

/**
 * 单个请求的有序事件时间线（实现需保证并发追加安全）。
 */
public interface RequestTimeline {

    String correlationId();

    String method();

    String path();

    long startNanos();

    /** 追加事件，返回分配到的序号。 */
    long addEvent(TimelineEventName name, int attempt, String detail);

    /** 按序号返回不可变快照。 */
    List<TimelineEvent> events();
}
