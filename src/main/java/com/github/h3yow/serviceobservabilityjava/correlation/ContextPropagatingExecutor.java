package com.github.h3yow.serviceobservabilityjava.correlation;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 包装 Executor：提交任务时捕获当前关联标识快照与 MDC，
 * 在工作线程执行前恢复、执行后还原，保证线程池复用下不串号、不泄漏日志字段。
 */
public final class ContextPropagatingExecutor implements Executor {

    private final Executor delegate;

    public ContextPropagatingExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        // 在提交线程捕获快照，而非在工作线程读取：线程池复用下这是不串号的关键。
        CorrelationContext snapshot = CorrelationContextHolder.current();
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        delegate.execute(() -> {
            CorrelationContext previous = CorrelationContextHolder.restore(snapshot);
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            applyMdc(mdcSnapshot);
            try {
                command.run();
            } finally {
                // 还原工作线程原有值并清理，避免把本请求标识/MDC 泄漏给下一个复用任务。
                if (previous == null) {
                    CorrelationContextHolder.clear();
                } else {
                    CorrelationContextHolder.restore(previous);
                }
                applyMdc(previousMdc);
            }
        });
    }

    private static void applyMdc(Map<String, String> mdc) {
        MDC.clear();
        if (mdc != null) {
            MDC.setContextMap(mdc);
        }
    }
}
