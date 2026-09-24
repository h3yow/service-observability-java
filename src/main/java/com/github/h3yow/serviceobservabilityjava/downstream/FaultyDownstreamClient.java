package com.github.h3yow.serviceobservabilityjava.downstream;

import com.github.h3yow.serviceobservabilityjava.observability.RequestScopeHolder;
import com.github.h3yow.serviceobservabilityjava.timeline.TimelineEventName;

import java.io.IOException;

/**
 * 进程内模拟下游：无网络、无外部依赖，通过 {@link FaultSpec} 注入慢调用/失败/超时/业务拒绝。
 * 每次尝试向当前请求时间线追加有界事件；系统类异常始终包装底层根因，不静默丢弃。
 */
public final class FaultyDownstreamClient {

    /** 超时模式下的确定性短暂等待，避免测试长时间阻塞（真正的慢调用走 slow 模式）。 */
    private static final long TIMEOUT_MODE_PAUSE_MILLIS = 20L;

    public FaultyDownstreamClient() {
    }

    /**
     * 执行一次模拟调用（单次尝试，不含重试）。
     *
     * @param label   有界的调用标签（如 primary/audit）
     * @param attempt 尝试序号（从 1 开始）
     * @param spec    故障规约
     * @return 模拟结果文本
     */
    public String call(String label, int attempt, FaultSpec spec) {
        record(TimelineEventName.DOWNSTREAM_CALL_STARTED, attempt, label + ":mode=" + spec.mode());
        try {
            return switch (spec.mode()) {
                case FaultSpec.MODE_SLOW -> slow(label, attempt, spec);
                case FaultSpec.MODE_FAIL -> failing(label, attempt, spec);
                case FaultSpec.MODE_TIMEOUT -> downstreamTimeout(label, attempt);
                case FaultSpec.MODE_REJECT -> businessReject(label, attempt);
                default -> ok(label, attempt);
            };
        } catch (BusinessRejectionException e) {
            // 业务拒绝已记 BUSINESS_REJECTED，它不是系统故障，不得再记 DOWNSTREAM_CALL_FAILED。
            throw e;
        } catch (RuntimeException e) {
            // 事件只记录有界信息；原始异常对象继续向上抛，根因不被吞掉。
            record(TimelineEventName.DOWNSTREAM_CALL_FAILED, attempt,
                    label + ":" + rootType(e));
            throw e;
        }
    }

    private String ok(String label, int attempt) {
        record(TimelineEventName.DOWNSTREAM_CALL_SUCCEEDED, attempt, label);
        return label + "-ok";
    }

    private String slow(String label, int attempt, FaultSpec spec) {
        sleep(spec.delayMillis());
        record(TimelineEventName.DOWNSTREAM_CALL_SUCCEEDED, attempt, label);
        return label + "-slow-ok";
    }

    /**
     * fail 模式：failAttempts=0 表示每次尝试都失败；否则仅前 N 次失败，第 N+1 次成功
     * （用于验证“重试后成功”只计一次成功结果）。
     */
    private String failing(String label, int attempt, FaultSpec spec) {
        if (spec.failAttempts() == 0 || attempt <= spec.failAttempts()) {
            IOException root = new IOException("simulated downstream io failure: " + label
                    + " attempt=" + attempt);
            throw new DownstreamException("downstream call failed: " + label
                    + " attempt=" + attempt, root);
        }
        record(TimelineEventName.DOWNSTREAM_CALL_SUCCEEDED, attempt, label);
        return label + "-recovered";
    }

    private String downstreamTimeout(String label, int attempt) {
        sleep(TIMEOUT_MODE_PAUSE_MILLIS);
        // 下游自身报超时：根因即 DownstreamTimeoutException，异常链不得再被覆盖。
        throw new DownstreamTimeoutException("simulated downstream timeout: " + label
                + " attempt=" + attempt);
    }

    private String businessReject(String label, int attempt) {
        record(TimelineEventName.BUSINESS_REJECTED, attempt, label);
        throw new BusinessRejectionException("simulated business rejection: " + label);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // 被服务侧截止时间取消时到达这里：恢复中断标志并用根因异常表达，不静默吞掉。
            Thread.currentThread().interrupt();
            throw new DownstreamException("downstream wait interrupted after " + millis + "ms", e);
        }
    }

    private static void record(TimelineEventName name, int attempt, String detail) {
        var scope = RequestScopeHolder.current();
        if (scope != null) {
            scope.addEvent(name, attempt, detail);
        }
    }

    private static String rootType(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName();
    }
}
