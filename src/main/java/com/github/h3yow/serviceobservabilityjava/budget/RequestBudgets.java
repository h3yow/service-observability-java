package com.github.h3yow.serviceobservabilityjava.budget;

/**
 * 请求级总时间预算的归一化规则（骨架）。
 * 判定结果确定且四类可区分：provided / defaulted / invalid / clamped。
 */
public final class RequestBudgets {

    public static final String HEADER = "X-Request-Budget-Millis";
    public static final String SOURCE_HEADER = "X-Request-Budget-Source";
    public static final String SOURCE_PROVIDED = "provided";
    public static final String SOURCE_DEFAULTED = "defaulted";
    public static final String SOURCE_INVALID = "invalid";
    public static final String SOURCE_CLAMPED = "clamped";

    public static final long DEFAULT_BUDGET_MILLIS = 10_000L;
    public static final long MIN_BUDGET_MILLIS = 1L;
    public static final long MAX_BUDGET_MILLIS = 30_000L;

    private RequestBudgets() {
    }

    /**
     * 判定预算头，结果确定且四类可区分：
     * <ul>
     *   <li>头缺失或空白 -> defaulted，使用 {@link #DEFAULT_BUDGET_MILLIS}（兼容老调用方）；</li>
     *   <li>非数字、<=0 -> invalid，回落默认值（与缺省同值但来源可区分）；</li>
     *   <li>合法且在 [MIN, MAX] -> provided，原样生效；</li>
     *   <li>合法但超过 {@link #MAX_BUDGET_MILLIS} -> clamped，裁剪到上限。</li>
     * </ul>
     * 纯函数、不抛异常：预算头本身不参与业务错误分类。
     */
    public static BudgetDecision decide(String rawHeader) {
        if (rawHeader == null || rawHeader.isBlank()) {
            return new BudgetDecision(rawHeader, DEFAULT_BUDGET_MILLIS, SOURCE_DEFAULTED,
                    "header absent or blank; default budget " + DEFAULT_BUDGET_MILLIS + "ms applied");
        }
        String trimmed = rawHeader.trim();
        long value;
        try {
            value = Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return new BudgetDecision(rawHeader, DEFAULT_BUDGET_MILLIS, SOURCE_INVALID,
                    "not a number: '" + trimmed + "'; default budget " + DEFAULT_BUDGET_MILLIS
                            + "ms applied");
        }
        if (value < MIN_BUDGET_MILLIS) {
            return new BudgetDecision(rawHeader, DEFAULT_BUDGET_MILLIS, SOURCE_INVALID,
                    "value " + value + "ms below minimum " + MIN_BUDGET_MILLIS
                            + "ms; default budget " + DEFAULT_BUDGET_MILLIS + "ms applied");
        }
        if (value > MAX_BUDGET_MILLIS) {
            return new BudgetDecision(rawHeader, MAX_BUDGET_MILLIS, SOURCE_CLAMPED,
                    "value " + value + "ms above maximum " + MAX_BUDGET_MILLIS
                            + "ms; clamped to " + MAX_BUDGET_MILLIS + "ms");
        }
        return new BudgetDecision(rawHeader, value, SOURCE_PROVIDED,
                "value " + value + "ms within [" + MIN_BUDGET_MILLIS + "," + MAX_BUDGET_MILLIS
                        + "]; caller value applied");
    }
}
