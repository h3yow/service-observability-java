package com.github.h3yow.serviceobservabilityjava.observability;

import com.github.h3yow.serviceobservabilityjava.budget.RequestBudget;
import com.github.h3yow.serviceobservabilityjava.correlation.CorrelationContext;
import com.github.h3yow.serviceobservabilityjava.timeline.OutcomeCategory;
import com.github.h3yow.serviceobservabilityjava.timeline.RequestTimeline;
import com.github.h3yow.serviceobservabilityjava.timeline.TimelineEventName;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 当前请求的可观测性作用域：关联上下文 + 时间线 + 最终类别 + 请求级总时间预算。
 * 由过滤器在请求边界建立，业务代码通过当前实例记录事件。
 */
public final class RequestScope {

    private final CorrelationContext context;
    private final RequestTimeline timeline;
    private final RequestBudget budget;
    private final AtomicReference<OutcomeCategory> outcome = new AtomicReference<>();

    public RequestScope(CorrelationContext context, RequestTimeline timeline, RequestBudget budget) {
        this.context = context;
        this.timeline = timeline;
        this.budget = budget;
    }

    public CorrelationContext context() {
        return context;
    }

    public RequestTimeline timeline() {
        return timeline;
    }

    /** 整个请求共享的唯一一份总时间预算（重试/嵌套/跨线程都读它）。 */
    public RequestBudget budget() {
        return budget;
    }

    public long addEvent(TimelineEventName name, int attempt, String detail) {
        return timeline.addEvent(name, attempt, detail);
    }

    /**
     * 标记最终结果类别；仅首次标记生效（compareAndSet），
     * 保证每个请求只归一类，重试/嵌套/异常处理之间不会互相覆盖。
     */
    public boolean markOutcome(OutcomeCategory category) {
        return outcome.compareAndSet(null, category);
    }

    public OutcomeCategory outcome() {
        return outcome.get();
    }
}
