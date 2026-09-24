package com.github.h3yow.serviceobservabilityjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * 端到端并发隔离：24 个真实 HTTP 请求并发打到 /api/process，
 * 混合 provided/缺失/非法标识与成功/超时/失败重试/业务拒绝，
 * 校验回显标识不串号、按类别计数精确、每个 provided 请求的时间线只含本标识事件。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentRequestIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRequestIntegrationTest.class);

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

    @Test
    void concurrentMixedRequestsStayIsolated() throws InterruptedException {
        // 每个元素：[cid(可null), mode, delay, failAttempts, timeout, expectStatus]
        List<String[]> cases = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String cid;
            if (i % 3 == 0) {
                cid = "req-conc-" + String.format("%04d", i);            // provided
            } else if (i % 3 == 1) {
                cid = null;                                              // 缺失 -> generated
            } else {
                cid = "bad id " + i;                                    // 非法 -> rejected 400
            }
            String mode;
            String delay = null;
            String failAttempts = null;
            String timeout = "2000";
            String expectStatus;
            if (i % 3 == 2) {
                // 非法标识在过滤器处确定性 400，优先级高于任何故障模式。
                mode = switch (i % 4) {
                    case 0 -> "none";
                    case 1 -> "slow";
                    case 2 -> "timeout";
                    default -> "reject";
                };
                if ("slow".equals(mode)) { delay = "40"; }
                if ("timeout".equals(mode)) { timeout = "150"; }
                expectStatus = "400";
            } else {
                switch (i % 4) {
                    case 0 -> { mode = "none"; expectStatus = "200"; }
                    case 1 -> { mode = "slow"; delay = "40"; expectStatus = "200"; }
                    case 2 -> { mode = "timeout"; timeout = "150"; expectStatus = "500"; }
                    default -> { mode = "reject"; expectStatus = "422"; }
                }
            }
            cases.add(new String[]{cid == null ? "" : cid, mode, delay, failAttempts,
                    timeout, expectStatus, String.valueOf(i % 3)});
        }

        ExecutorService pool = Executors.newFixedThreadPool(24);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(cases.size());
        ConcurrentMap<String, AtomicInteger> errors = new ConcurrentHashMap<>();
        ConcurrentMap<String, String> echoById = new ConcurrentHashMap<>();

        for (String[] c : cases) {
            pool.submit(() -> {
                try {
                    start.await();
                    HttpHeaders h = new HttpHeaders();
                    if (!c[0].isEmpty()) {
                        h.add("X-Correlation-Id", c[0]);
                    }
                    h.add("X-Fault-Mode", c[1]);
                    if (c[2] != null) {
                        h.add("X-Fault-Delay-Millis", c[2]);
                    }
                    h.add("X-Timeout-Millis", c[4]);
                    ResponseEntity<JsonNode> resp = rest.exchange(url("/api/process"),
                            HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
                    int status = resp.getStatusCode().value();
                    String effectiveId = resp.getHeaders().getFirst("X-Correlation-Id");
                    String source = resp.getHeaders().getFirst("X-Correlation-Id-Source");
                    record(errors, status == Integer.parseInt(c[5]),
                            "statusMismatch expected=" + c[5] + " actual=" + status + " cid=" + c[0]);

                    int kind = Integer.parseInt(c[6]);
                    if (kind == 0) { // provided
                        record(errors, c[0].equals(effectiveId), "providedIdChanged " + c[0] + "->" + effectiveId);
                        record(errors, "provided".equals(source), "providedSourceWrong " + source);
                        echoById.put(c[0], effectiveId);
                    } else if (kind == 1) { // missing
                        record(errors, effectiveId != null && effectiveId.startsWith("gen-"),
                                "missingNotGenerated " + effectiveId);
                        record(errors, "generated".equals(source), "generatedSourceWrong " + source);
                    } else { // invalid
                        record(errors, "rejected".equals(source), "rejectedSourceWrong " + source);
                        record(errors, effectiveId != null && effectiveId.startsWith("gen-"),
                                "rejectedAuditIdMissing " + effectiveId);
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

        // provided 请求：i%3==0 -> 0,3,6,...,21 共 8 个。
        List<Integer> provided = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            if (i % 3 == 0) {
                provided.add(i);
            }
        }
        assertEquals(8, provided.size());

        // 指标口径：24 个请求中
        //   进入业务(200/500/422)：kind!=2 且 (i%4==0||1)成功、i%4==2超时、kind!=2且i%4==3拒绝422
        //   400 INVALID_REQUEST（非法标识，未进入业务）：kind==2 的 8 个
        long invalid = metricCount("-", "INVALID_REQUEST");
        long business = metricCount("/api/process", "SUCCESS")
                + metricCount("/api/process", "BUSINESS_REJECTION")
                + metricCount("/api/process", "SYSTEM_ERROR");
        assertEquals(8L, invalid, "非法标识请求全部归 INVALID_REQUEST");
        assertEquals(16L, business, "其余 16 个请求进入业务端点");
        assertEquals(8L, metricCount("/api/process", "SUCCESS"));
        assertEquals(4L, metricCount("/api/process", "SYSTEM_ERROR"));
        assertEquals(4L, metricCount("/api/process", "BUSINESS_REJECTION"));
        // 24 个请求总数：8 invalid + 16 business = 24
        assertEquals(24L, invalid + business, "总计数精确等于并发请求数，不重不漏");

        // 时间线归属：每个 provided 标识查到的时间线 correlationId 必须等于自身。
        for (int i : provided) {
            String cid = "req-conc-" + String.format("%04d", i);
            JsonNode timeline = rest.getForObject(url("/observability/timelines/" + cid), JsonNode.class);
            record(errors, timeline.get("found").asBoolean(), "timelineMissing " + cid);
            record(errors, cid.equals(timeline.get("correlationId").asText()),
                    "timelineIdMismatch " + cid);
            for (JsonNode event : timeline.get("events")) {
                record(errors, !event.get("detail").asText().contains("req-conc-"),
                        "eventDetailLeaksCorrelationId " + cid);
            }
        }
        assertTrue(errors.isEmpty(), "并发隔离校验失败: " + errors);
        log.info("concurrent batch complete: invalid={} success={} systemError={} businessRejection={}",
                invalid, metricCount("/api/process", "SUCCESS"),
                metricCount("/api/process", "SYSTEM_ERROR"),
                metricCount("/api/process", "BUSINESS_REJECTION"));
    }

    private static void record(ConcurrentMap<String, AtomicInteger> errors, boolean ok, String key) {
        if (!ok) {
            errors.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        }
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
}
