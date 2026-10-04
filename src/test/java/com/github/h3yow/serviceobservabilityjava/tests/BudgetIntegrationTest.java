package com.github.h3yow.serviceobservabilityjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求级总时间预算端到端验证：
 * 预算在重试中途耗尽、预算耗尽与单次下游超时可区分、非法/越界预算的兜底口径、
 * 预算充足时行为不变、并发请求预算互相隔离。
 * 每个用例打印原始预算输入与归一化后的判定依据（时间线 BUDGET_DECIDED 事件）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BudgetIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BudgetIntegrationTest.class);

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;

    @BeforeEach
    void reset() {
        rest.postForEntity(url("/observability/admin/reset"), null, JsonNode.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<JsonNode> process(String cid, String mode, String delay,
                                             String failAttempts, String timeout, String budget) {
        HttpHeaders h = new HttpHeaders();
        if (cid != null) {
            h.add("X-Correlation-Id", cid);
        }
        if (mode != null) {
            h.add("X-Fault-Mode", mode);
        }
        if (delay != null) {
            h.add("X-Fault-Delay-Millis", delay);
        }
        if (failAttempts != null) {
            h.add("X-Fault-Fail-Attempts", failAttempts);
        }
        if (timeout != null) {
            h.add("X-Timeout-Millis", timeout);
        }
        if (budget != null) {
            h.add("X-Budget-Millis", budget);
        }
        ResponseEntity<JsonNode> resp = rest.exchange(url("/api/process"), HttpMethod.GET,
                new HttpEntity<>(h), JsonNode.class);
        log.info("process cid={} mode={} rawBudget={} -> status={} body={}",
                cid, mode, budget, resp.getStatusCode().value(), resp.getBody());
        return resp;
    }

    private long metricCount(String endpoint, String category) {
        JsonNode metrics = rest.getForObject(url("/observability/metrics"), JsonNode.class);
        long count = 0;
        for (JsonNode point : metrics.get("points")) {
            if (endpoint.equals(point.get("endpoint").asText())
                    && category.equals(point.get("category").asText())) {
                count += point.get("count").asLong();
            }
        }
        return count;
    }

    private JsonNode timeline(String cid) {
        return rest.getForObject(url("/observability/timelines/" + cid), JsonNode.class);
    }

    private static int countEvents(JsonNode timeline, String name) {
        int n = 0;
        for (JsonNode event : timeline.get("events")) {
            if (name.equals(event.get("name").asText())) {
                n++;
            }
        }
        return n;
    }

    private static String budgetDecidedDetail(JsonNode timeline) {
        for (JsonNode event : timeline.get("events")) {
            if ("BUDGET_DECIDED".equals(event.get("name").asText())) {
                return event.get("detail").asText();
            }
        }
        return "";
    }

    @Test
    void budgetExhaustedMidRetryStopsChainAndIsDistinguishable() {
        // timeout 模式每次尝试停顿 20ms 后抛下游超时；预算 45ms 在第二次尝试期间被用光，
        // 第三次尝试（约 60ms 处）被预算闸门确定性地拦下。
        long t0 = System.currentTimeMillis();
        ResponseEntity<JsonNode> resp = process("req-budget-midretry", "timeout", null, null,
                "2000", "45");
        long elapsed = System.currentTimeMillis() - t0;
        assertEquals(504, resp.getStatusCode().value(),
                "预算耗尽必须是 504 而非单次超时的 500: " + resp.getBody());
        assertEquals("BUDGET_EXHAUSTED", resp.getBody().get("category").asText());
        assertTrue(resp.getBody().get("message").asText().contains("budget"),
                "错误信息必须指明是整条请求的预算耗尽: " + resp.getBody());
        assertTrue(elapsed < 1500, "预算耗尽后必须快速收尾，实际 " + elapsed + "ms");

        JsonNode timeline = timeline("req-budget-midretry");
        int started = countEvents(timeline, "DOWNSTREAM_CALL_STARTED");
        int retries = countEvents(timeline, "DOWNSTREAM_CALL_RETRY");
        int exhausted = countEvents(timeline, "BUDGET_EXHAUSTED");
        log.info("mid-retry timeline started={} retries={} exhausted={} decided={}",
                started, retries, exhausted, budgetDecidedDetail(timeline));
        assertTrue(started >= 1, "第一次尝试必须发生");
        assertTrue(retries >= 1, "预算必须是在重试进行中被用光的（已有重试事件）");
        assertTrue(exhausted >= 1, "时间线上必须能看到预算耗尽事件");
        assertTrue(budgetDecidedDetail(timeline).contains("source=provided"),
                "时间线必须记录预算判定依据: " + budgetDecidedDetail(timeline));

        assertEquals(1L, metricCount("/api/process", "BUDGET_EXHAUSTED"),
                "预算耗尽每请求只计一次");
        assertEquals(0L, metricCount("/api/process", "SYSTEM_ERROR"),
                "预算耗尽不得被计入单次下游超时/系统错误");
    }

    @Test
    void budgetExhaustedDuringSlowCallIsDistinctFromPerCallTimeout() {
        // 慢调用 1500ms，单次超时默认 2000ms 足够，但请求级预算只有 100ms。
        long t0 = System.currentTimeMillis();
        ResponseEntity<JsonNode> resp = process("req-budget-slow", "slow", "1500", null,
                null, "100");
        long elapsed = System.currentTimeMillis() - t0;
        assertEquals(504, resp.getStatusCode().value());
        assertEquals("BUDGET_EXHAUSTED", resp.getBody().get("category").asText());
        assertTrue(elapsed < 1000, "预算 100ms 到期必须快速停下，实际 " + elapsed + "ms");
        assertEquals(1L, metricCount("/api/process", "BUDGET_EXHAUSTED"));
        assertEquals(0L, metricCount("/api/process", "SYSTEM_ERROR"));
    }

    @Test
    void invalidOrOutOfRangeBudgetFallsBackDeterministically() {
        // 非数字 -> invalid，回落默认 2000，请求正常处理（老行为兼容）。
        ResponseEntity<JsonNode> invalid = process("req-budget-invalid", null, null, null,
                null, "abc");
        assertEquals(200, invalid.getStatusCode().value());
        assertEquals("invalid", invalid.getBody().get("budgetSource").asText());
        assertEquals(2000L, invalid.getBody().get("budgetMillis").asLong());
        log.info("rawBudget=abc -> source={} effectiveMillis={} decided={}",
                invalid.getBody().get("budgetSource").asText(),
                invalid.getBody().get("budgetMillis").asLong(),
                budgetDecidedDetail(timeline("req-budget-invalid")));

        // 越界（低于下限）-> clamped 到下限 10。
        ResponseEntity<JsonNode> belowMin = process("req-budget-belowmin", null, null, null,
                null, "5");
        assertEquals(200, belowMin.getStatusCode().value());
        assertEquals("clamped", belowMin.getBody().get("budgetSource").asText());
        assertEquals(10L, belowMin.getBody().get("budgetMillis").asLong());

        // 越界（高于上限）-> clamped 到上限 10000。
        ResponseEntity<JsonNode> aboveMax = process("req-budget-abovemax", null, null, null,
                null, "999999");
        assertEquals(200, aboveMax.getStatusCode().value());
        assertEquals("clamped", aboveMax.getBody().get("budgetSource").asText());
        assertEquals(10000L, aboveMax.getBody().get("budgetMillis").asLong());

        // 缺失 -> default，与 invalid 可区分。
        ResponseEntity<JsonNode> absent = process("req-budget-absent", null, null, null,
                null, null);
        assertEquals(200, absent.getStatusCode().value());
        assertEquals("default", absent.getBody().get("budgetSource").asText());
        assertEquals(2000L, absent.getBody().get("budgetMillis").asLong());

        // 时间线上每请求恰好一条 BUDGET_DECIDED，且 detail 体现归一化结果。
        JsonNode timeline = timeline("req-budget-invalid");
        assertEquals(1, countEvents(timeline, "BUDGET_DECIDED"), "预算判定每请求只记一次");
        assertTrue(budgetDecidedDetail(timeline).contains("source=invalid"));
        assertEquals(4L, metricCount("/api/process", "SUCCESS"),
                "非法/越界预算兜底后请求行为与未带预算一致");
    }

    @Test
    void sufficientBudgetKeepsRetryAndNestedCallBehavior() {
        // 预算充足：前 2 次失败第 3 次成功，与引入预算前的行为完全一致。
        ResponseEntity<JsonNode> resp = process("req-budget-enough", "fail", null, "2",
                null, "2000");
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(3, resp.getBody().get("attempts").asInt());
        assertEquals("primary-recovered", resp.getBody().get("primary").asText());
        assertEquals("provided", resp.getBody().get("budgetSource").asText());
        assertTrue(resp.getBody().get("budgetRemainingMillis").asLong() > 0);
        assertEquals(1L, metricCount("/api/process", "SUCCESS"));
        assertEquals(0L, metricCount("/api/process", "BUDGET_EXHAUSTED"));
        JsonNode timeline = timeline("req-budget-enough");
        assertEquals(0, countEvents(timeline, "BUDGET_EXHAUSTED"));
        assertEquals(2, countEvents(timeline, "DOWNSTREAM_CALL_RETRY"), "重试事件数量不变");
    }

    @Test
    void concurrentRequestsHaveIsolatedBudgets() throws InterruptedException {
        // 8 个小预算（30ms，timeout 模式必耗尽）+ 8 个充足预算（2000ms，slow 50ms 必成功）。
        int perKind = 8;
        ExecutorService pool = Executors.newFixedThreadPool(perKind * 2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(perKind * 2);
        ConcurrentMap<String, AtomicInteger> errors = new ConcurrentHashMap<>();
        List<String> exhaustedCids = new ArrayList<>();
        List<String> successCids = new ArrayList<>();

        for (int i = 0; i < perKind * 2; i++) {
            final boolean smallBudget = i % 2 == 0;
            final String cid = (smallBudget ? "req-cb-exh-" : "req-cb-ok-") + i;
            if (smallBudget) {
                exhaustedCids.add(cid);
            } else {
                successCids.add(cid);
            }
            pool.submit(() -> {
                try {
                    start.await();
                    ResponseEntity<JsonNode> resp = smallBudget
                            ? process(cid, "timeout", null, null, "2000", "30")
                            : process(cid, "slow", "50", null, null, "2000");
                    int expected = smallBudget ? 504 : 200;
                    record(errors, resp.getStatusCode().value() == expected,
                            "statusMismatch cid=" + cid + " expected=" + expected
                                    + " actual=" + resp.getStatusCode().value());
                    String expectedCategory = smallBudget ? "BUDGET_EXHAUSTED" : null;
                    if (expectedCategory != null) {
                        record(errors, expectedCategory.equals(
                                        resp.getBody().get("category").asText()),
                                "categoryMismatch cid=" + cid + " body=" + resp.getBody());
                    }
                } catch (Exception e) {
                    record(errors, false, "exception:" + e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals((long) perKind, metricCount("/api/process", "BUDGET_EXHAUSTED"),
                "小预算请求全部且仅这些归为预算耗尽");
        assertEquals((long) perKind, metricCount("/api/process", "SUCCESS"),
                "充足预算请求全部成功，不被并发的小预算请求牵连");
        assertEquals(0L, metricCount("/api/process", "SYSTEM_ERROR"));

        // 每个请求的时间线只反映自己的预算判定：不串号、不串预算。
        for (String cid : exhaustedCids) {
            JsonNode t = timeline(cid);
            record(errors, budgetDecidedDetail(t).contains("effectiveMillis=30"),
                    "exhaustedBudgetWrong cid=" + cid + " decided=" + budgetDecidedDetail(t));
            record(errors, countEvents(t, "BUDGET_EXHAUSTED") >= 1,
                    "exhaustedEventMissing cid=" + cid);
        }
        for (String cid : successCids) {
            JsonNode t = timeline(cid);
            record(errors, budgetDecidedDetail(t).contains("effectiveMillis=2000"),
                    "successBudgetWrong cid=" + cid + " decided=" + budgetDecidedDetail(t));
            record(errors, countEvents(t, "BUDGET_EXHAUSTED") == 0,
                    "successHasExhaustedEvent cid=" + cid);
        }
        assertTrue(errors.isEmpty(), "并发预算隔离校验失败: " + errors);
        log.info("concurrent budget isolation ok: exhausted={} success={}",
                metricCount("/api/process", "BUDGET_EXHAUSTED"),
                metricCount("/api/process", "SUCCESS"));
    }

    private static void record(ConcurrentMap<String, AtomicInteger> errors, boolean ok, String key) {
        if (!ok) {
            errors.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        }
    }
}
