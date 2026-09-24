package com.github.h3yow.serviceobservabilityjava.service;

import com.github.h3yow.serviceobservabilityjava.correlation.CorrelationContextHolder;
import com.github.h3yow.serviceobservabilityjava.downstream.BusinessRejectionException;
import com.github.h3yow.serviceobservabilityjava.downstream.DownstreamException;
import com.github.h3yow.serviceobservabilityjava.downstream.DownstreamTimeoutException;
import com.github.h3yow.serviceobservabilityjava.downstream.FaultSpec;
import com.github.h3yow.serviceobservabilityjava.downstream.FaultyDownstreamClient;
import com.github.h3yow.serviceobservabilityjava.observability.RequestScope;
import com.github.h3yow.serviceobservabilityjava.observability.RequestScopeHolder;
import com.github.h3yow.serviceobservabilityjava.timeline.TimelineEventName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 演示业务服务：
 * <ul>
 *   <li>通过上下文传播执行器把工作派发到线程池，验证跨线程标识传递；</li>
 *   <li>对系统类失败进行最多 3 次尝试（业务拒绝不重试），每次尝试都有独立事件；</li>
 *   <li>主调用成功后产生一次嵌套审计调用，验证嵌套不影响“每请求一次”的聚合计数；</li>
 *   <li>带截止时间，慢调用在工作线程被中断取消，超时根因 TimeoutException 完整保留。</li>
 * </ul>
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    static final int MAX_ATTEMPTS = 3;
    static final long RETRY_BACKOFF_MILLIS = 10L;
    public static final long DEFAULT_TIMEOUT_MILLIS = 2000L;
    public static final long MAX_TIMEOUT_MILLIS = 2000L;

    private final Executor workerExecutor;
    private final FaultyDownstreamClient client;

    public DemoService(Executor workerExecutor, FaultyDownstreamClient client) {
        this.workerExecutor = workerExecutor;
        this.client = client;
    }

    public Map<String, Object> process(FaultSpec spec, long timeoutMillis) {
        long deadline = Math.max(1L, Math.min(timeoutMillis, MAX_TIMEOUT_MILLIS));
        RequestScope scope = RequestScopeHolder.current();
        scope.addEvent(TimelineEventName.WORK_DISPATCHED, 0, "deadlineMillis=" + deadline);

        FutureTask<Map<String, Object>> task = new FutureTask<>(() -> runInWorker(scope, spec));
        // workerExecutor 已负责关联标识传播；请求作用域在此显式恢复（含还原，防止线程复用串号）。
        workerExecutor.execute(task);
        try {
            return task.get(deadline, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            scope.addEvent(TimelineEventName.DOWNSTREAM_CALL_TIMEOUT, 0,
                    "deadlineMillis=" + deadline);
            throw new DownstreamException("downstream work did not finish within " + deadline + "ms", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new DownstreamException("downstream work failed: " + rootName(cause), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownstreamException("request thread interrupted while awaiting downstream", e);
        }
    }

    private Map<String, Object> runInWorker(RequestScope scope, FaultSpec spec) {
        RequestScope previousScope = RequestScopeHolder.restore(scope);
        try {
            log.info("worker start on thread={}", Thread.currentThread().getName());
            int[] attempts = {0};
            String primary = invokePrimaryWithRetry(spec, attempts);
            // 嵌套调用：独立标签、不重试、不产生额外聚合（聚合只在请求边界计一次）。
            String audit = client.call("audit", 1, new FaultSpec(FaultSpec.MODE_NONE, 0L, 0));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("correlationId", CorrelationContextHolder.currentCorrelationId());
            result.put("source", scope.context().source());
            result.put("primary", primary);
            result.put("audit", audit);
            result.put("attempts", attempts[0]);
            result.put("worker", Thread.currentThread().getName());
            return result;
        } finally {
            if (previousScope == null) {
                RequestScopeHolder.clear();
            } else {
                RequestScopeHolder.restore(previousScope);
            }
        }
    }

    private String invokePrimaryWithRetry(FaultSpec spec, int[] attempts) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            attempts[0] = attempt;
            try {
                return client.call("primary", attempt, spec);
            } catch (BusinessRejectionException e) {
                // 业务拒绝不是系统故障：立即上抛，不重试。
                throw e;
            } catch (DownstreamException | DownstreamTimeoutException e) {
                lastFailure = e;
                if (attempt < MAX_ATTEMPTS) {
                    RequestScopeHolder.current().addEvent(TimelineEventName.DOWNSTREAM_CALL_RETRY,
                            attempt, "next=" + (attempt + 1));
                    backoff();
                }
            }
        }
        // 重试用尽：抛出的仍是最后一次的原始异常，根因链未被覆盖。
        throw lastFailure;
    }

    private static void backoff() {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String rootName(Throwable t) {
        Throwable c = t;
        while (c != null && c.getCause() != null) {
            c = c.getCause();
        }
        return c == null ? "unknown" : c.getClass().getSimpleName();
    }
}
