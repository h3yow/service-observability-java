package com.github.h3yow.serviceobservabilityjava.observability.correlation;

/**
 * 基于 ThreadLocal 的关联上下文，支持跨线程显式传递。
 * 每个请求处理结束必须调用 {@link #clear()}，避免线程复用串号。
 */
public final class CorrelationContext {

    private record Holder(String correlationId, boolean providedByCaller) {
    }

    private static final ThreadLocal<Holder> CURRENT = new ThreadLocal<>();

    private CorrelationContext() {
    }

    public static void set(String correlationId, boolean providedByCaller) {
        CURRENT.set(new Holder(correlationId, providedByCaller));
    }

    public static String correlationId() {
        Holder h = CURRENT.get();
        return h == null ? null : h.correlationId();
    }

    /** 调用方是否提供了合法标识（false 表示由本服务生成）。 */
    public static boolean providedByCaller() {
        Holder h = CURRENT.get();
        return h != null && h.providedByCaller();
    }

    /** 当前线程是否已绑定上下文。 */
    public static boolean isPresent() {
        return CURRENT.get() != null;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
