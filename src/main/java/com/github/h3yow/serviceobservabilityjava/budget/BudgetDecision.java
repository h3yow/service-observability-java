package com.github.h3yow.serviceobservabilityjava.budget;

/**
 * 请求级总时间预算的判定结果（骨架，规则见 {@link RequestBudgets}）。
 *
 * @param raw            原始请求头值（可空，仅用于日志/审计，不作标签）
 * @param effectiveMillis 归一化后生效的预算毫秒数（有界）
 * @param source         有界来源标签：provided/defaulted/invalid/clamped
 * @param reason         判定依据（人类可读，进日志不进指标）
 */
public record BudgetDecision(String raw, long effectiveMillis, String source, String reason) {
}
