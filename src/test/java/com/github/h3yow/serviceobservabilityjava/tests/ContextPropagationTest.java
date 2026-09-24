package com.github.h3yow.serviceobservabilityjava.tests;

import com.github.h3yow.serviceobservabilityjava.correlation.ContextPropagatingExecutor;
import com.github.h3yow.serviceobservabilityjava.correlation.CorrelationContext;
import com.github.h3yow.serviceobservabilityjava.correlation.CorrelationContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.MDC;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨线程关联标识传播与并发隔离单测。
 */
class ContextPropagationTest {

    private static final Logger log = LoggerFactory.getLogger(ContextPropagationTest.class);

    private final ExecutorService rawPool = Executors.newFixedThreadPool(2);
    private final ContextPropagatingExecutor propagating = new ContextPropagatingExecutor(rawPool);

    @AfterEach
    void tearDown() {
        rawPool.shutdownNow();
        CorrelationContextHolder.clear();
    }

    @Test
    void pooledThreadsReuseWithoutLeakingAcrossTasks() throws InterruptedException {
        int tasks = 400; // 远大于线程数 2，强制大量复用
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);
        ConcurrentMap<String, AtomicInteger> mismatches = new ConcurrentHashMap<>();
        ConcurrentMap<String, AtomicInteger> leftovers = new ConcurrentHashMap<>();

        for (int i = 0; i < tasks; i++) {
            String cid = "req-concurrent-" + String.format("%04d", i);
            CorrelationContextHolder.begin(new CorrelationContext(cid, "provided", cid));
            MDC.put("correlationId", cid);
            MDC.put("correlationSource", "provided");
            // 模拟交错：同时捕获提交线程当前标识
            propagating.execute(() -> {
                try {
                    start.await();
                    String observed = CorrelationContextHolder.currentCorrelationId();
                    if (!cid.equals(observed)) {
                        mismatches.computeIfAbsent(observed + "!=" + cid,
                                k -> new AtomicInteger()).incrementAndGet();
                    }
                    String observedMdc = MDC.get("correlationId");
                    if (!cid.equals(observedMdc)) {
                        mismatches.computeIfAbsent("mdc:" + observedMdc + "!=" + cid,
                                k -> new AtomicInteger()).incrementAndGet();
                    }
                    // 让出 CPU 制造交错，线程随后会被下一个任务复用。
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        // 主线程标识仍是最后一个提交任务的值，证明提交动作本身未被工作线程清掉。
        assertEquals("req-concurrent-0399", CorrelationContextHolder.currentCorrelationId());
        CorrelationContextHolder.clear();
        assertNull(CorrelationContextHolder.currentCorrelationId());

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "所有任务应在时限内完成");

        // 等任务全部结束后，用一个“干净提交”的探针：若工作线程残留了旧值，
        // 传播执行器在恢复前会先把旧值作为 previous 捕获；这里直接检查残留映射为空。
        // 通过让探针在恢复前读取工作线程当前值做验证：
        CountDownLatch probeDone = new CountDownLatch(1);
        rawPool.execute(() -> {
            // rawPool 不传播：此时若传播包装器清理不彻底，这里会看到上一个任务的标识/MDC。
            if (CorrelationContextHolder.currentCorrelationId() != null) {
                leftovers.computeIfAbsent("ctx:" + CorrelationContextHolder.currentCorrelationId(),
                        k -> new AtomicInteger()).incrementAndGet();
            }
            if (MDC.get("correlationId") != null) {
                leftovers.computeIfAbsent("mdc:" + MDC.get("correlationId"),
                        k -> new AtomicInteger()).incrementAndGet();
            }
            probeDone.countDown();
        });
        assertTrue(probeDone.await(10, TimeUnit.SECONDS));

        assertTrue(mismatches.isEmpty(), "工作线程观察到的标识必须与提交时一致，串号: " + mismatches);
        assertTrue(leftovers.isEmpty(), "任务结束后工作线程不得残留标识: " + leftovers);
        log.info("{} tasks across 2 pooled threads: no mismatch, no leak", tasks);
    }

    @Test
    void restoreReturnsPreviousAndClearIsSafe() {
        CorrelationContext a = new CorrelationContext("req-restore-A", "provided", "req-restore-A");
        CorrelationContext b = new CorrelationContext("req-restore-B", "provided", "req-restore-B");
        assertNull(CorrelationContextHolder.current());
        CorrelationContextHolder.begin(a);
        CorrelationContext previous = CorrelationContextHolder.restore(b);
        assertEquals(a.correlationId(), previous.correlationId());
        assertEquals("req-restore-B", CorrelationContextHolder.currentCorrelationId());
        CorrelationContextHolder.restore(previous);
        assertEquals("req-restore-A", CorrelationContextHolder.currentCorrelationId());
        CorrelationContextHolder.clear();
        assertNull(CorrelationContextHolder.currentCorrelationId());
    }
}
