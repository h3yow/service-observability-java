package com.github.h3yow.serviceobservabilityjava.timeline;

/**
 * 有界的请求结果类别（指标标签与事件分类共用，禁止随意新增以避免高基数）。
 */
public enum OutcomeCategory {
    SUCCESS,
    BUSINESS_REJECTION,
    BUDGET_EXHAUSTED,
    INVALID_REQUEST,
    SYSTEM_ERROR,
    UNMATCHED
}
