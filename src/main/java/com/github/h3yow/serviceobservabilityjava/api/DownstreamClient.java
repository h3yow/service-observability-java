package com.github.h3yow.serviceobservabilityjava.api;

import com.github.h3yow.serviceobservabilityjava.observability.correlation.CorrelationContext;
import com.github.h3yow.serviceobservabilityjava.observability.timeline.TimelineStore;
import org.springframework.stereotype.Component;

/**
 * 本地模拟下游调用，支持故障注入（慢调用 / 失败）。
 * 不发起任何真实网络请求；重试事件写入当前请求时间线，
 * 最终结果只由 ObservabilityFilter 计数一次。
 */
@Component
public class DownstreamClient {

    /** 单次尝试允许的最大延迟，超过即视为超时。 */
    public static final long TIMEOUT_MILLIS = 2000;

    /** 最大尝试次数（含首次调用），超过后不再重试。 */
    public static final int MAX_ATTEMPTS = 3;

    private final TimelineStore timelineStore;

    public DownstreamClient(TimelineStore timelineStore) {
        this.timelineStore = timelineStore;
    }

    /**
     * @param correlationId 当前请求关联标识（用于核对上下文传播）
     * @param failAttempts  前 N 次尝试注入失败（0 表示不注入失败）
     * @param latencyMillis 每次尝试注入的额外延迟毫秒数（超过 TIMEOUT_MILLIS 视为超时）
     * @return 下游响应内容
     */
    public String call(String correlationId, int failAttempts, long latencyMillis) {
        int maxAttempts = Math.min(failAttempts + 1, MAX_ATTEMPTS);
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            appendEvent("downstream-attempt",
                    "attempt=" + attempt + "/" + maxAttempts + " correlationId=" + correlationId);
            try {
                simulateLatency(latencyMillis);
            } catch (DownstreamTimeoutException e) {
                appendEvent("downstream-timeout", "attempt=" + attempt);
                throw e;
            }
            if (attempt <= failAttempts) {
                lastFailure = new IllegalStateException("injected downstream failure, attempt " + attempt);
                appendEvent("downstream-failure", lastFailure.getMessage());
                continue;
            }
            appendEvent("downstream-success", "attempt=" + attempt);
            return "downstream-ok after " + attempt + " attempt(s)";
        }
        // 保留完整异常链：外层语义异常包装注入的根因
        throw new DownstreamException("downstream call failed after " + maxAttempts + " attempt(s)", lastFailure);
    }

    private void simulateLatency(long latencyMillis) {
        if (latencyMillis <= 0) {
            return;
        }
        if (latencyMillis > TIMEOUT_MILLIS) {
            throw new DownstreamTimeoutException(
                    "downstream timeout: injected latency " + latencyMillis + "ms exceeds " + TIMEOUT_MILLIS + "ms");
        }
        try {
            Thread.sleep(latencyMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownstreamException("downstream call interrupted", e);
        }
    }

    private void appendEvent(String type, String detail) {
        String id = CorrelationContext.correlationId();
        if (id != null) {
            timelineStore.find(id).ifPresent(t -> t.append(type, detail));
        }
    }

    /** 下游调用失败（保留注入故障作为 cause）。 */
    public static class DownstreamException extends RuntimeException {
        public DownstreamException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 下游超时。 */
    public static class DownstreamTimeoutException extends RuntimeException {
        public DownstreamTimeoutException(String message) {
            super(message);
        }
    }
}
