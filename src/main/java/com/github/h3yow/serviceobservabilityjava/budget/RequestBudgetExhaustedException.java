package com.github.h3yow.serviceobservabilityjava.budget;

/**
 * 整条请求的总时间预算耗尽（骨架）。
 * 独立于下游超时/失败体系：不被重试捕获、不被层层包装，调用方可明确区分。
 */
public class RequestBudgetExhaustedException extends RuntimeException {

    public RequestBudgetExhaustedException(String message) {
        super(message);
    }

    public RequestBudgetExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
