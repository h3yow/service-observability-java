package com.github.h3yow.serviceobservabilityjava.timeline;

/**
 * 时间线上的一个有序事件。
 *
 * @param seq        请求内单调递增序号（并发安全）
 * @param eventNanos 事件发生时的 System.nanoTime()
 * @param name       有界事件名
 * @param attempt    下游尝试序号（从 1 开始；不适用为 0）
 * @param detail     有界、低基数的补充说明（不得放原始用户输入）
 */
public record TimelineEvent(long seq, long eventNanos, TimelineEventName name, int attempt, String detail) {
}
