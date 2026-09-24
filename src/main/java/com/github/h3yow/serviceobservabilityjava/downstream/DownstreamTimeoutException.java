package com.github.h3yow.serviceobservabilityjava.downstream;

/**
 * 模拟下游超时（根因类型，须在异常链与错误体中保留）。
 */
public class DownstreamTimeoutException extends RuntimeException {

    public DownstreamTimeoutException(String message) {
        super(message);
    }
}
