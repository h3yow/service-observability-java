package com.github.h3yow.serviceobservabilityjava.downstream;

/**
 * 模拟下游调用的系统类异常（骨架）。cause 必须保留底层根因（超时/IO）。
 */
public class DownstreamException extends RuntimeException {

    public DownstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
