package com.github.h3yow.serviceobservabilityjava.observability;

/**
 * 请求作用域的 ThreadLocal 持有者（与关联标识上下文生命周期一致）。
 * 跨线程派工时由业务代码显式捕获/恢复，保证事件写入同一请求时间线。
 */
public final class RequestScopeHolder {

    private static final ThreadLocal<RequestScope> CURRENT = new ThreadLocal<>();

    private RequestScopeHolder() {
    }

    public static void begin(RequestScope scope) {
        CURRENT.set(scope);
    }

    public static RequestScope current() {
        return CURRENT.get();
    }

    public static RequestScope restore(RequestScope scope) {
        RequestScope previous = CURRENT.get();
        CURRENT.set(scope);
        return previous;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
