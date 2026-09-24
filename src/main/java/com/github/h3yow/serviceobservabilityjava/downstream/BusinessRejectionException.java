package com.github.h3yow.serviceobservabilityjava.downstream;

/**
 * 业务拒绝（非系统异常，映射 422，不重试、只计一次）。
 */
public class BusinessRejectionException extends RuntimeException {

    public BusinessRejectionException(String reason) {
        super(reason);
    }
}
