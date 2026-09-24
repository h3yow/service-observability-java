package com.github.h3yow.serviceobservabilityjava.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 关联标识规则：合法值为 8-64 位，字符集 [A-Za-z0-9._-]。
 * 判定结果分三类：provided(调用方提供且合法) / generated(缺失，由本服务生成) / rejected(非法)。
 */
public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-Id";
    public static final String SOURCE_HEADER = "X-Correlation-Id-Source";
    public static final String SOURCE_PROVIDED = "provided";
    public static final String SOURCE_GENERATED = "generated";
    public static final String SOURCE_REJECTED = "rejected";
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    private CorrelationIds() {
    }

    /**
     * 判定来源头，结果确定且三类可区分：
     * <ul>
     *   <li>头缺失或空白 -> generated，生成本服务标识；</li>
     *   <li>格式合法 -> provided，原样保留（大小写敏感、不做规范化改写）；</li>
     *   <li>非空但非法 -> rejected，另生成审计标识用于时间线/日志关联。</li>
     * </ul>
     */
    public static CorrelationDecision decide(String rawHeader) {
        if (rawHeader == null || rawHeader.isBlank()) {
            String id = generate();
            return new CorrelationDecision(rawHeader, id, SOURCE_GENERATED,
                    "header absent or blank; generated id=" + id);
        }
        String trimmed = rawHeader.trim();
        if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH) {
            String id = generate();
            return new CorrelationDecision(rawHeader, id, SOURCE_REJECTED,
                    "length " + trimmed.length() + " outside [" + MIN_LENGTH + "," + MAX_LENGTH
                            + "]; audit id=" + id);
        }
        if (!ALLOWED.matcher(trimmed).matches()) {
            String id = generate();
            return new CorrelationDecision(rawHeader, id, SOURCE_REJECTED,
                    "contains illegal characters; allowed=[A-Za-z0-9._-]; audit id=" + id);
        }
        return new CorrelationDecision(rawHeader, trimmed, SOURCE_PROVIDED,
                "length " + trimmed.length() + " and charset legal; caller value preserved");
    }

    /** 生成一个确定可区分的关联标识：gen- 前缀 + UUID（去横线后 32 位，总长 36）。 */
    public static String generate() {
        return "gen-" + UUID.randomUUID().toString().replace("-", "");
    }
}
