package com.github.h3yow.serviceobservabilityjava.observability.timeline;

import java.time.Instant;

/** 请求时间线中的单个事件（不可变）。 */
public record TimelineEvent(long sequence, Instant timestamp, String type, String detail) {
}
