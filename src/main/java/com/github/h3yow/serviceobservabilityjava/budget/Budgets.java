package com.github.h3yow.serviceobservabilityjava.budget;

/**
 * 请求级总时间预算的归一化规则：判定结果确定、四类来源可区分。
 * <ul>
 *   <li>头缺失或空白 -> default，回落默认预算（与历史默认超时一致，老调用方行为不变）；</li>
 *   <li>非数字 / 非正数 -> invalid，回落默认预算（来源与 default 可区分）；</li>
 *   <li>超出 [MIN, MAX] 合理范围 -> clamped，确定性地裁剪到边界值；</li>
 *   <li>范围内的合法值 -> provided，原样采用。</li>
 * </ul>
 * 解析是纯函数、不抛异常；预算头本身不参与业务错误分类。
 */
public final class Budgets {

    public static final String HEADER = "X-Budget-Millis";

    public static final String SOURCE_PROVIDED = "provided";
    public static final String SOURCE_DEFAULT = "default";
    public static final String SOURCE_INVALID = "invalid";
    public static final String SOURCE_CLAMPED = "clamped";

    /** 默认预算：与服务侧默认截止时间一致，保证未带预算头的老调用方行为不变。 */
    public static final long DEFAULT_BUDGET_MILLIS = 2000L;
    /** 合理范围下界：过小的预算没有工程意义，裁剪到下界。 */
    public static final long MIN_BUDGET_MILLIS = 10L;
    /** 合理范围上界：超过服务侧最大截止时间（2000ms）太多的预算同样裁剪，防止等待无界放大。 */
    public static final long MAX_BUDGET_MILLIS = 10_000L;

    private Budgets() {
    }

    /**
     * 判定预算头，结果确定且来源可区分；任何非法输入都有确定兜底，不抛异常。
     */
    public static BudgetDecision decide(String rawHeader) {
        if (rawHeader == null || rawHeader.isBlank()) {
            return new BudgetDecision(rawHeader, DEFAULT_BUDGET_MILLIS, SOURCE_DEFAULT,
                    "header absent or blank; fallback to default " + DEFAULT_BUDGET_MILLIS + "ms");
        }
        String trimmed = rawHeader.trim();
        long value;
        try {
            value = Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return new BudgetDecision(rawHeader, DEFAULT_BUDGET_MILLIS, SOURCE_INVALID,
                    "not a number: '" + abbreviate(trimmed) + "'; fallback to default "
                            + DEFAULT_BUDGET_MILLIS + "ms");
        }
        if (value <= 0L) {
            return new BudgetDecision(rawHeader, DEFAULT_BUDGET_MILLIS, SOURCE_INVALID,
                    "non-positive value " + value + "; fallback to default "
                            + DEFAULT_BUDGET_MILLIS + "ms");
        }
        if (value < MIN_BUDGET_MILLIS) {
            return new BudgetDecision(rawHeader, MIN_BUDGET_MILLIS, SOURCE_CLAMPED,
                    "value " + value + " below min " + MIN_BUDGET_MILLIS + "; clamped to min");
        }
        if (value > MAX_BUDGET_MILLIS) {
            return new BudgetDecision(rawHeader, MAX_BUDGET_MILLIS, SOURCE_CLAMPED,
                    "value " + value + " above max " + MAX_BUDGET_MILLIS + "; clamped to max");
        }
        return new BudgetDecision(rawHeader, value, SOURCE_PROVIDED,
                "value " + value + " within [" + MIN_BUDGET_MILLIS + "," + MAX_BUDGET_MILLIS
                        + "]; caller value used");
    }

    /** 判定依据里只保留有界长度的输入片段，防止日志被超长输入撑爆。 */
    private static String abbreviate(String trimmed) {
        return trimmed.length() <= 32 ? trimmed : trimmed.substring(0, 32) + "...";
    }
}
