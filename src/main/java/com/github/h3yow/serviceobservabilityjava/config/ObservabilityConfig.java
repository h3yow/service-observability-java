package com.github.h3yow.serviceobservabilityjava.config;

import com.github.h3yow.serviceobservabilityjava.correlation.ContextPropagatingExecutor;
import com.github.h3yow.serviceobservabilityjava.downstream.FaultyDownstreamClient;
import com.github.h3yow.serviceobservabilityjava.metrics.MetricsRegistry;
import com.github.h3yow.serviceobservabilityjava.observability.ObservabilityFilter;
import com.github.h3yow.serviceobservabilityjava.timeline.InMemoryTimelineStore;
import com.github.h3yow.serviceobservabilityjava.timeline.TimelineStore;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可观测性组件与有界工作线程池装配。
 */
@Configuration
public class ObservabilityConfig {

    /** 时间线存储容量上限（最旧淘汰）。 */
    public static final int TIMELINE_CAPACITY = 1000;
    /** 工作线程池大小：固定有界，复用线程以验证“不串号”。 */
    public static final int WORKER_POOL_SIZE = 8;

    @Bean
    public TimelineStore timelineStore() {
        return new InMemoryTimelineStore(TIMELINE_CAPACITY);
    }

    @Bean
    public MetricsRegistry metricsRegistry() {
        return new MetricsRegistry();
    }

    @Bean
    public FaultyDownstreamClient faultyDownstreamClient() {
        return new FaultyDownstreamClient();
    }

    /** 业务工作线程池（固定有界，守护线程，命名便于日志核对）。 */
    @Bean(name = "rawWorkerExecutor", destroyMethod = "shutdownNow")
    public ExecutorService rawWorkerExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "obs-worker-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        return Executors.newFixedThreadPool(WORKER_POOL_SIZE, factory);
    }

    /** 包装后的工作线程池：跨线程传播关联标识上下文。 */
    @Bean(name = "workerExecutor")
    public Executor workerExecutor(ExecutorService rawWorkerExecutor) {
        return new ContextPropagatingExecutor(rawWorkerExecutor);
    }

    /** 请求边界过滤器：最高优先级，保证标识先于业务逻辑归一化。 */
    @Bean
    public FilterRegistrationBean<ObservabilityFilter> observabilityFilter(
            TimelineStore timelineStore, MetricsRegistry metricsRegistry) {
        FilterRegistrationBean<ObservabilityFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ObservabilityFilter(timelineStore, metricsRegistry));
        registration.setOrder(1);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
