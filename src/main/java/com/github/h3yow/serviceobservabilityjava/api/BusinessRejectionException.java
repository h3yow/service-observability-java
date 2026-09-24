package com.github.h3yow.serviceobservabilityjava.api;

/** 业务拒绝异常：与系统异常区分，不计入系统错误。 */
public class BusinessRejectionException extends RuntimeException {

    public BusinessRejectionException(String message) {
        super(message);
    }
}
