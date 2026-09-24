package com.github.h3yow.serviceobservabilityjava.downstream;

import java.util.Set;

/**
 * 单次调用的故障注入规约（由请求头解析，确定性、有界）。
 *
 * @param mode         none/slow/fail/timeout/reject 之一（有界）
 * @param delayMillis  slow 模式的延迟（裁剪到 [0, 5000]）
 * @param failAttempts 前 N 次尝试失败（裁剪到 [0, 3]，用于验证重试不重复计数）
 */
public record FaultSpec(String mode, long delayMillis, int failAttempts) {

    public static final String MODE_NONE = "none";
    public static final String MODE_SLOW = "slow";
    public static final String MODE_FAIL = "fail";
    public static final String MODE_TIMEOUT = "timeout";
    public static final String MODE_REJECT = "reject";

    private static final Set<String> MODES = Set.of(
            MODE_NONE, MODE_SLOW, MODE_FAIL, MODE_TIMEOUT, MODE_REJECT);
    public static final long MAX_DELAY_MILLIS = 5000L;
    public static final int MAX_FAIL_ATTEMPTS = 3;

    /**
     * 从请求头解析：mode 非法/缺失回落 none；数值头非法回落 0；超长延迟有界裁剪。
     * 结果完全确定，不抛异常（故障注入头本身不参与业务错误分类）。
     */
    public static FaultSpec fromHeaders(String mode, String delayMillis, String failAttempts) {
        String normalizedMode = mode == null ? MODE_NONE : mode.trim().toLowerCase();
        if (!MODES.contains(normalizedMode)) {
            normalizedMode = MODE_NONE;
        }
        long delay = parseLong(delayMillis, 0L);
        delay = Math.max(0L, Math.min(delay, MAX_DELAY_MILLIS));
        int fails = (int) Math.max(0L, Math.min(parseLong(failAttempts, 0L), MAX_FAIL_ATTEMPTS));
        return new FaultSpec(normalizedMode, delay, fails);
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
