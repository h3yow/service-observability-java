package com.github.h3yow.serviceobservabilityjava.budget;

/**
 * 整条请求的总时间预算耗尽（骨架）。与单次下游调用超时严格区分，
 * 不得被重试，根因链不得被覆盖。
 */
public class BudgetExhaustedException extends RuntimeException {

    public BudgetExhaustedException(String message) {
        super(message);
    }

    public BudgetExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
