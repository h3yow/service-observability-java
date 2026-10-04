package com.github.h3yow.serviceobservabilityjava.budget;

/**
 * 请求级总时间预算的归一化判定结果。
 *
 * @param raw            原始输入（null 表示头缺失）
 * @param effectiveMillis 生效的总预算毫秒数（已按规则兜底/裁剪）
 * @param source         provided / default / invalid / clamped（有界）
 * @param reason         判定依据的人类可读描述
 */
public record BudgetDecision(String raw, long effectiveMillis, String source, String reason) {
}
