package com.github.h3yow.serviceobservabilityjava.tests;

import com.github.h3yow.serviceobservabilityjava.budget.BudgetDecision;
import com.github.h3yow.serviceobservabilityjava.budget.Budgets;
import com.github.h3yow.serviceobservabilityjava.budget.RequestBudget;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RequestBudget 语义单测：截止时刻创建时一次确定、不随读取/线程重新起算；
 * 多实例（模拟并发请求）各自独立扣减。
 */
class RequestBudgetTest {

    private static final Logger log = LoggerFactory.getLogger(RequestBudgetTest.class);

    private static RequestBudget budget(String raw, long startNanos) {
        BudgetDecision decision = Budgets.decide(raw);
        log.info("rawInput={} -> source={} effectiveMillis={} basis={}",
                raw, decision.source(), decision.effectiveMillis(), decision.reason());
        return new RequestBudget(decision, startNanos);
    }

    @Test
    void remainingDecreasesMonotonicallyAndNeverRestarts() throws InterruptedException {
        RequestBudget b = budget("500", System.nanoTime());
        long r1 = b.remainingMillis();
        Thread.sleep(60);
        long r2 = b.remainingMillis();
        Thread.sleep(60);
        long r3 = b.remainingMillis();
        log.info("remaining samples r1={} r2={} r3={}", r1, r2, r3);
        assertTrue(r1 <= 500 && r1 > 0, "初始剩余应接近预算值: " + r1);
        assertTrue(r2 < r1, "剩余量必须单调消耗，不得重新起算: r1=" + r1 + " r2=" + r2);
        assertTrue(r3 < r2, "剩余量必须单调消耗: r2=" + r2 + " r3=" + r3);
        assertFalse(b.isExhausted());
    }

    @Test
    void exhaustedAfterDeadlineAndRemainingClampsToZero() throws InterruptedException {
        RequestBudget b = budget("10", System.nanoTime());
        Thread.sleep(40);
        assertTrue(b.isExhausted(), "越过截止时刻必须判定为耗尽");
        assertEquals(0L, b.remainingMillis(), "耗尽后剩余量收敛为 0，不出现负值");
    }

    @Test
    void sharedInstanceIsVisibleConsistentlyAcrossThreads() throws InterruptedException {
        // 同一份预算被多个线程同时读取（模拟重试/嵌套/派工共享）：读到的剩余量都必须来自同一截止时刻。
        RequestBudget shared = budget("300", System.nanoTime());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<String> violations = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    Thread.sleep(20L * (idx + 1));
                    long remaining = shared.remainingMillis();
                    log.info("thread={} observed remaining={}", idx, remaining);
                    if (remaining < 0 || remaining > 300) {
                        violations.add("thread=" + idx + " remaining=" + remaining);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(violations.isEmpty(), "共享预算的并发读取越界: " + violations);
        assertTrue(shared.remainingMillis() < 300, "所有线程消耗的都是同一份预算");
    }

    @Test
    void concurrentBudgetsAreIsolated() throws InterruptedException {
        // 模拟并发请求：小预算先耗尽，大预算必须不受影响（不串预算）。
        RequestBudget small = budget("10", System.nanoTime());
        RequestBudget large = budget("2000", System.nanoTime());
        Thread.sleep(40);
        assertTrue(small.isExhausted());
        assertFalse(large.isExhausted(), "大预算不得被小预算的耗尽牵连");
        assertTrue(large.remainingMillis() > 1500,
                "大预算剩余量应接近自身额度: " + large.remainingMillis());
        log.info("isolation check small.exhausted={} large.remaining={}",
                small.isExhausted(), large.remainingMillis());
    }
}
