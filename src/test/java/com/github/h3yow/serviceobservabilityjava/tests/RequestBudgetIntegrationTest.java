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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求级总时间预算端到端验证：
 * 重试中途耗尽、慢调用被预算叫停、非法/越界预算兜底、预算充足时行为不变、并发请求预算互不影响。
 * 每条用例打印原始预算头与归一化后的生效值/来源，日志可直接核对判定依据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestBudgetIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RequestBudgetIntegrationTest.class);

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
            h.add("X-Request-Budget-Millis", budget);
        }
        ResponseEntity<JsonNode> resp = rest.exchange(url("/api/process"), HttpMethod.GET,
                new HttpEntity<>(h), JsonNode.class);
        log.info("process cid={} mode={} rawBudget={} -> status={} effectiveBudget={} budgetSource={} body={}",
                cid, mode, budget, resp.getStatusCode().value(),
                resp.getHeaders().getFirst("X-Request-Budget-Millis"),
                resp.getHeaders().getFirst("X-Request-Budget-Source"), resp.getBody());
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

    private List<String> eventNames(String cid) {
        JsonNode timeline = rest.getForObject(url("/observability/timelines/" + cid), JsonNode.class);
        assertTrue(timeline.get("found").asBoolean(), "时间线必须可查: " + cid);
        List<String> names = new ArrayList<>();
        long lastSeq = 0;
        for (JsonNode event : timeline.get("events")) {
            long seq = event.get("seq").asLong();
            assertTrue(seq > lastSeq, "事件序号必须严格递增");
            lastSeq = seq;
            names.add(event.get("name").asText());
        }
        log.info("timeline cid={} events={}", cid, names);
        return names;
    }

    @Test
    void budgetExhaustedMidRetryStopsRetryingAndIsDistinguishable() {
        // timeout 模式每次尝试耗 20ms 后抛下游超时（3 次尝试 + 2 次退避约 80ms）；
        // 预算 60ms 保证在第 3 次尝试前被用光：重试中途耗尽。
        ResponseEntity<JsonNode> resp = process("req-budget-retry", "timeout", null, null,
                "2000", "60");
        assertEquals(504, resp.getStatusCode().value());
        assertEquals("BUDGET_EXHAUSTED", resp.getBody().get("category").asText());
        assertTrue(resp.getBody().get("causeChain").asText()
                        .startsWith("RequestBudgetExhaustedException"),
                "异常链顶端必须是预算耗尽而非下游超时: " + resp.getBody());
        assertEquals("provided", resp.getHeaders().getFirst("X-Request-Budget-Source"));
        assertEquals("60", resp.getHeaders().getFirst("X-Request-Budget-Millis"));

        List<String> events = eventNames("req-budget-retry");
        assertTrue(events.contains("REQUEST_BUDGET_EXHAUSTED"), "时间线必须有预算耗尽事件: " + events);
        long started = events.stream().filter("DOWNSTREAM_CALL_STARTED"::equals).count();
        assertTrue(started < 3, "预算耗尽必须截断重试（正常重试应发起 3 次尝试）: " + events);
        // 指标口径：每请求只计一次，且归入有界新类别 BUDGET_EXHAUSTED 而非 SYSTEM_ERROR。
        assertEquals(1L, metricCount("/api/process", "BUDGET_EXHAUSTED"));
        assertEquals(0L, metricCount("/api/process", "SYSTEM_ERROR"));
    }

    @Test
    void slowCallIsStoppedBySharedBudgetInsteadOfPerCallTimeout() {
        // 单次上限 2000ms、下游慢 1500ms，但整条请求预算只有 200ms：必须在预算附近停下。
        long t0 = System.currentTimeMillis();
        ResponseEntity<JsonNode> resp = process("req-budget-slow", "slow", "1500", null,
                "2000", "200");
        long elapsed = System.currentTimeMillis() - t0;
        assertEquals(504, resp.getStatusCode().value());
        assertEquals("BUDGET_EXHAUSTED", resp.getBody().get("category").asText());
        assertTrue(resp.getBody().get("causeChain").asText()
                .startsWith("RequestBudgetExhaustedException"));
        assertTrue(elapsed < 1500, "预算耗尽后不得继续干等慢调用，实际 " + elapsed + "ms");
        List<String> events = eventNames("req-budget-slow");
        assertTrue(events.contains("REQUEST_BUDGET_EXHAUSTED"), events.toString());
        assertEquals(1L, metricCount("/api/process", "BUDGET_EXHAUSTED"));
    }

    @Test
    void invalidBudgetFallsBackToDefaultAndBehavesLikeLegacyCaller() {
        ResponseEntity<JsonNode> resp = process("req-budget-invalid", null, null, null, null, "abc");
        assertEquals(200, resp.getStatusCode().value(), "非法预算不得影响正常处理");
        assertEquals("invalid", resp.getHeaders().getFirst("X-Request-Budget-Source"));
        assertEquals("10000", resp.getHeaders().getFirst("X-Request-Budget-Millis"));

        ResponseEntity<JsonNode> zero = process("req-budget-zero", null, null, null, null, "0");
        assertEquals(200, zero.getStatusCode().value());
        assertEquals("invalid", zero.getHeaders().getFirst("X-Request-Budget-Source"));

        ResponseEntity<JsonNode> negative = process("req-budget-neg", null, null, null, null, "-5");
        assertEquals(200, negative.getStatusCode().value());
        assertEquals("invalid", negative.getHeaders().getFirst("X-Request-Budget-Source"));
    }

    @Test
    void outOfRangeBudgetIsClampedAndMissingBudgetDefaults() {
        ResponseEntity<JsonNode> clamped = process("req-budget-clamp", null, null, null, null,
                "99999999");
        assertEquals(200, clamped.getStatusCode().value());
        assertEquals("clamped", clamped.getHeaders().getFirst("X-Request-Budget-Source"));
        assertEquals("30000", clamped.getHeaders().getFirst("X-Request-Budget-Millis"));

        // 老调用方：不带预算头，行为与上线前完全一致。
        ResponseEntity<JsonNode> legacy = process("req-budget-legacy", null, null, null, null, null);
        assertEquals(200, legacy.getStatusCode().value());
        assertEquals("defaulted", legacy.getHeaders().getFirst("X-Request-Budget-Source"));
        assertEquals("10000", legacy.getHeaders().getFirst("X-Request-Budget-Millis"));
    }

    @Test
    void sufficientBudgetKeepsRetryAndNestedCallSemanticsUnchanged() {
        // 预算充足：前 2 次失败第 3 次成功，与既有行为一致（重试 + 嵌套审计调用都发生）。
        ResponseEntity<JsonNode> resp = process("req-budget-ample", "fail", null, "2", null,
                "5000");
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(3, resp.getBody().get("attempts").asInt());
        assertEquals("primary-recovered", resp.getBody().get("primary").asText());
        assertEquals("audit-ok", resp.getBody().get("audit").asText());
        List<String> events = eventNames("req-budget-ample");
        assertTrue(!events.contains("REQUEST_BUDGET_EXHAUSTED"),
                "预算充足时不得出现预算耗尽事件: " + events);
        assertEquals(2, events.stream().filter("DOWNSTREAM_CALL_RETRY"::equals).count());
        assertEquals(1L, metricCount("/api/process", "SUCCESS"));
    }

    @Test
    void concurrentRequestsHaveIsolatedBudgets() throws InterruptedException {
        // 6 个充足预算（成功）+ 6 个紧张预算（重试中途耗尽）并发混跑，线程池复用下不得串预算。
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        List<String> ampleIds = new ArrayList<>();
        List<String> tightIds = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String cid = "req-bconc-" + String.format("%04d", i);
            if (i % 2 == 0) {
                ampleIds.add(cid);
            } else {
                tightIds.add(cid);
            }
        }
        for (int i = 0; i < threads; i++) {
            final int index = i;
            final String cid = "req-bconc-" + String.format("%04d", index);
            pool.submit(() -> {
                try {
                    start.await();
                    if (index % 2 == 0) {
                        ResponseEntity<JsonNode> resp = process(cid, null, null, null, null,
                                "10000");
                        if (resp.getStatusCode().value() != 200
                                || !"10000".equals(resp.getHeaders()
                                        .getFirst("X-Request-Budget-Millis"))) {
                            failures.incrementAndGet();
                        }
                    } else {
                        ResponseEntity<JsonNode> resp = process(cid, "timeout", null, null,
                                "2000", "60");
                        if (resp.getStatusCode().value() != 504
                                || !"BUDGET_EXHAUSTED".equals(
                                        resp.getBody().get("category").asText())
                                || !"60".equals(resp.getHeaders()
                                        .getFirst("X-Request-Budget-Millis"))) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    log.error("concurrent budget request failed cid={}", cid, e);
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发请求必须在限定时间内完成");
        pool.shutdown();
        assertEquals(0, failures.get(), "并发请求结果必须符合各自预算预期");

        // 聚合口径：每请求恰好一次，紧张预算全部归 BUDGET_EXHAUSTED，充足预算全部 SUCCESS。
        assertEquals(6L, metricCount("/api/process", "SUCCESS"));
        assertEquals(6L, metricCount("/api/process", "BUDGET_EXHAUSTED"));
        assertEquals(0L, metricCount("/api/process", "SYSTEM_ERROR"));

        // 时间线隔离：紧张预算请求各自有耗尽事件；充足预算请求各自完整完成且无耗尽事件。
        for (String cid : tightIds) {
            List<String> events = eventNames(cid);
            assertTrue(events.contains("REQUEST_BUDGET_EXHAUSTED"),
                    "紧张预算请求必须有自己的耗尽事件: " + cid + " " + events);
        }
        for (String cid : ampleIds) {
            List<String> events = eventNames(cid);
            assertTrue(events.contains("REQUEST_COMPLETED"), cid + " " + events);
            assertTrue(!events.contains("REQUEST_BUDGET_EXHAUSTED"),
                    "充足预算请求不得被别人的预算影响: " + cid + " " + events);
        }
    }
}
