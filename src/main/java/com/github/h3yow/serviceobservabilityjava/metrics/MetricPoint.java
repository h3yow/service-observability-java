package com.github.h3yow.serviceobservabilityjava.metrics;

/**
 * 单个聚合键的指标值快照。
 */
public record MetricPoint(String endpoint, String category, String bucket, long count, long totalMillis) {
}
