package com.github.h3yow.serviceobservabilityjava.metrics;

import com.github.h3yow.serviceobservabilityjava.timeline.OutcomeCategory;

/**
 * 指标聚合键：端点必须使用路由模板或有界常量，类别与耗时桶均为有界枚举。
 */
public record MetricKey(String endpoint, OutcomeCategory category, LatencyBucket bucket) {
}
