package com.github.h3yow.serviceobservabilityjava.budget;

/**
 * 单个请求共享的总时间预算。在请求边界创建一次后不可变：
 * 截止时刻在创建时一次性确定（startNanos + effectiveMillis），
 * 之后重试、嵌套调用、跨线程派工都读取同一个截止时刻，
 * 不存在“每换一次调用/线程就重新起算”的入口。
 * <p>线程安全：全部字段 final，剩余量由 System.nanoTime() 纯读取推导，
 * 并发请求各自持有独立实例，互不影响。
 */
public final class RequestBudget {

    private final BudgetDecision decision;
    private final long deadlineNanos;

    public RequestBudget(BudgetDecision decision, long startNanos) {
        this.decision = decision;
        this.deadlineNanos = startNanos + decision.effectiveMillis() * 1_000_000L;
    }

    public long effectiveMillis() {
        return decision.effectiveMillis();
    }

    public String source() {
        return decision.source();
    }

    public BudgetDecision decision() {
        return decision;
    }

    /** 剩余预算毫秒数（向下取整，耗尽后为 0，不出现负值）。 */
    public long remainingMillis() {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining <= 0L ? 0L : remaining / 1_000_000L;
    }

    /** 预算是否已耗尽：到达或越过截止时刻即耗尽。 */
    public boolean isExhausted() {
        return System.nanoTime() >= deadlineNanos;
    }
}
