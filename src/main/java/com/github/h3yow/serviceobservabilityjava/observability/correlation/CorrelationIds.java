package com.github.h3yow.serviceobservabilityjava.observability.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 关联标识的校验与归一化规则。
 * 合法标识：8~64 字符，仅含字母、数字、'-'、'_'。
 */
public final class CorrelationIds {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private CorrelationIds() {
    }

    /** 判断原始输入是否为合法关联标识（null/空白/超长/含非法字符均为非法）。 */
    public static boolean isValid(String raw) {
        return raw != null && VALID.matcher(raw).matches();
    }

    /** 返回归一化判定依据（用于日志与测试断言），确定且可解释。 */
    public static String explain(String raw) {
        if (raw == null) {
            return "INVALID: input is null";
        }
        if (raw.isBlank()) {
            return "INVALID: input is blank";
        }
        if (raw.length() < 8) {
            return "INVALID: length " + raw.length() + " < 8";
        }
        if (raw.length() > 64) {
            return "INVALID: length " + raw.length() + " > 64";
        }
        if (!VALID.matcher(raw).matches()) {
            return "INVALID: contains characters outside [A-Za-z0-9_-]";
        }
        return "VALID: matches ^[A-Za-z0-9_-]{8,64}$";
    }

    /** 生成一个新的关联标识（32 位十六进制，无前缀连字符，天然合法）。 */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
