package com.github.h3yow.serviceobservabilityjava.observability;

import com.github.h3yow.serviceobservabilityjava.observability.correlation.CorrelationContext;
import com.github.h3yow.serviceobservabilityjava.observability.correlation.CorrelationPropagatingExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 跨线程传播与并发隔离：线程复用下不串号、不丢失。 */
class CorrelationPropagationTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationPropagationTest.class);

    @AfterEach
    void cleanup() {
        CorrelationContext.clear();
    }

    @Test
    void contextIsPropagatedToWorkerThreads() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CorrelationPropagatingExecutor executor = new CorrelationPropagatingExecutor(pool);
        try {
            int tasks = 50;
            CountDownLatch latch = new CountDownLatch(tasks);
            Map<String, String> mismatches = new ConcurrentHashMap<>();
            for (int i = 0; i < tasks; i++) {
                String expected = "corr-id-" + String.format("%08d", i);
                // 每个任务在不同的提交线程中设置自己的上下文，模拟并发请求线程
                new Thread(() -> {
                    CorrelationContext.set(expected, true);
                    executor.execute(() -> {
                        String actual = CorrelationContext.correlationId();
                        log.info("task expected={} actual={} thread={}", expected, actual,
                                Thread.currentThread().getName());
                        if (!expected.equals(actual)) {
                            mismatches.put(expected, String.valueOf(actual));
                        }
                        latch.countDown();
                    });
                    CorrelationContext.clear();
                }).start();
            }
            assertTrue(latch.await(30, TimeUnit.SECONDS));
            log.info("propagation mismatches={}", mismatches);
            assertTrue(mismatches.isEmpty(), "串号: " + mismatches);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void workerThreadIsCleanedAfterTask() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CorrelationPropagatingExecutor executor = new CorrelationPropagatingExecutor(pool);
        try {
            CorrelationContext.set("corr-id-cleanup01", true);
            CountDownLatch first = new CountDownLatch(1);
            executor.execute(first::countDown);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            CorrelationContext.clear();

            // 同一个工作线程再次执行未包装的任务，不应看到残留上下文
            CountDownLatch second = new CountDownLatch(1);
            AtomicInteger residuals = new AtomicInteger();
            pool.execute(() -> {
                String residual = CorrelationContext.correlationId();
                log.info("residual context on reused thread={}", residual);
                if (residual != null) {
                    residuals.incrementAndGet();
                }
                second.countDown();
            });
            assertTrue(second.await(5, TimeUnit.SECONDS));
            assertEquals(0, residuals.get(), "线程复用后上下文未清理");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void submitterContextIsNotDisturbedByWrap() {
        CorrelationContext.set("corr-id-outer-001", true);
        Runnable wrapped = CorrelationPropagatingExecutor.wrap(() -> {
        });
        CorrelationContext.clear();
        wrapped.run();
        assertNull(CorrelationContext.correlationId(), "wrap 捕获的是提交时快照，执行后不应污染当前线程");
        log.info("submitter thread remains clean after running wrapped task");
    }
}
