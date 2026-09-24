package com.github.h3yow.serviceobservabilityjava.correlation;

/**
 * 关联标识归一化判定结果。
 *
 * @param raw     原始输入（null 表示头缺失）
 * @param id      生效的关联标识（非法时为审计用生成标识）
 * @param source  provided / generated / rejected
 * @param reason  判定依据的人类可读描述
 */
public record CorrelationDecision(String raw, String id, String source, String reason) {

    public boolean isValid() {
        return "provided".equals(source);
    }
}
