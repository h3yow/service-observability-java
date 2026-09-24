package com.github.h3yow.serviceobservabilityjava.observability.correlation;

import java.util.concurrent.Executor;

/**
 * 包装 Executor，在任务提交时捕获当前关联上下文并在执行线程中恢复，
 * 执行结束后清理，保证线程池复用时不串号、不丢失。
 */
public class CorrelationPropagatingExecutor implements Executor {

    private final Executor delegate;

    public CorrelationPropagatingExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    /** 捕获当前线程的上下文快照（提交线程调用）。 */
    public static Runnable wrap(Runnable task) {
        String id = CorrelationContext.correlationId();
        boolean provided = CorrelationContext.providedByCaller();
        return () -> {
            boolean hadPrevious = CorrelationContext.isPresent();
            String previousId = CorrelationContext.correlationId();
            boolean previousProvided = CorrelationContext.providedByCaller();
            if (id != null) {
                CorrelationContext.set(id, provided);
            }
            try {
                task.run();
            } finally {
                if (hadPrevious) {
                    CorrelationContext.set(previousId, previousProvided);
                } else {
                    CorrelationContext.clear();
                }
            }
        };
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(wrap(command));
    }
}
