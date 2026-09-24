package com.github.h3yow.serviceobservabilityjava.config;

import com.github.h3yow.serviceobservabilityjava.observability.correlation.CorrelationPropagatingExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** 装配可观测性组件：带关联上下文传播的跨线程执行器。 */
@Configuration
public class ObservabilityConfig {

    /** 固定大小线程池 + 上下文传播包装，用于演示/验证跨线程传递。 */
    @Bean(name = "observabilityExecutor")
    public Executor observabilityExecutor() {
        return new CorrelationPropagatingExecutor(Executors.newFixedThreadPool(4));
    }
}
