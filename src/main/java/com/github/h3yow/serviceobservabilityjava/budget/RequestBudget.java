package com.github.h3yow.serviceobservabilityjava.budget;

/**
 * 单个请求共享的一份总时间预算时钟（骨架）。
 * 挂在请求作用域上随作用域跨线程传播；重试与嵌套调用都从这一份里扣。
 * 只读快照语义：内部无可变状态，天然线程安全、请求间隔离。
 */
public final class RequestBudget {

    private final long budgetMillis;
    private final long startNanos;

    public RequestBudget(long budgetMillis, long startNanos) {
        this.budgetMillis = budgetMillis;
        this.startNanos = startNanos;
    }

    public long budgetMillis() {
        return budgetMillis;
    }

    public long startNanos() {
        return startNanos;
    }

    /** 剩余预算（毫秒，可为负：表示已耗尽）。 */
    public long remainingMillis() {
        return budgetMillis - elapsedMillis();
    }

    /** 预算是否已耗尽。 */
    public boolean exhausted() {
        return remainingMillis() <= 0L;
    }

    private long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
