package com.github.h3yow.serviceobservabilityjava.correlation;

/**
 * 基于 ThreadLocal 的请求上下文持有者。
 * 请求边界必须成对调用 begin/clear；跨线程时使用 snapshot + 显式 restore。
 */
public final class CorrelationContextHolder {

    private static final ThreadLocal<CorrelationContext> CURRENT = new ThreadLocal<>();

    private CorrelationContextHolder() {
    }

    public static void begin(CorrelationContext context) {
        CURRENT.set(context);
    }

    public static CorrelationContext current() {
        return CURRENT.get();
    }

    public static String currentCorrelationId() {
        CorrelationContext context = CURRENT.get();
        return context == null ? null : context.correlationId();
    }

    /** 跨线程恢复上下文，返回旧上下文快照供还原（旧值可能为 null）。 */
    public static CorrelationContext restore(CorrelationContext context) {
        CorrelationContext previous = CURRENT.get();
        CURRENT.set(context);
        return previous;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
