package com.github.h3yow.serviceobservabilityjava.correlation;

/**
 * 单次请求的关联标识上下文（不可变）。
 */
public record CorrelationContext(String correlationId, String source, String raw) {

    /** 用于跨线程传递的快照。 */
    public CorrelationContext snapshot() {
        return this;
    }
}
