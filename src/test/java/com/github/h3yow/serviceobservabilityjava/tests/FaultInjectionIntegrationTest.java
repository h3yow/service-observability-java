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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端故障注入验证：标识三类处理、慢调用、失败重试、超时、业务拒绝、
 * 异常链根因保留、时间线有序、聚合指标每请求仅一次。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FaultInjectionIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(FaultInjectionIntegrationTest.class);

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

    private HttpHeaders headers(String correlationId, String mode, String delay,
                                String failAttempts, String timeout) {
        HttpHeaders h = new HttpHeaders();
        if (correlationId != null) {
            h.add("X-Correlation-Id", correlationId);
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
        return h;
    }

    private ResponseEntity<JsonNode> process(String cid, String mode, String delay,
                                             String failAttempts, String timeout) {
        HttpHeaders h = headers(cid, mode, delay, failAttempts, timeout);
        ResponseEntity<JsonNode> resp = rest.exchange(url("/api/process"), HttpMethod.GET,
                new HttpEntity<>(h), JsonNode.class);
        log.info("process cid={} mode={} -> status={} body={}", cid, mode,
                resp.getStatusCode().value(), resp.getBody());
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

    @Test
    void providedCorrelationIdIsStableAndEchoed() {
        ResponseEntity<JsonNode> resp = process("req-provided-0001", null, null, null, null);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("req-provided-0001", resp.getBody().get("correlationId").asText());
        assertEquals("provided", resp.getBody().get("source").asText());
        assertEquals("req-provided-0001", resp.getHeaders().getFirst("X-Correlation-Id"));
        assertEquals("provided", resp.getHeaders().getFirst("X-Correlation-Id-Source"));
        assertEquals(1L, metricCount("/api/process", "SUCCESS"));
    }

    @Test
    void missingCorrelationIdGeneratesOne() {
        ResponseEntity<JsonNode> resp = process(null, null, null, null, null);
        assertEquals(200, resp.getStatusCode().value());
        String cid = resp.getBody().get("correlationId").asText();
        assertTrue(cid.startsWith("gen-"));
        assertEquals("generated", resp.getBody().get("source").asText());
        assertEquals("generated", resp.getHeaders().getFirst("X-Correlation-Id-Source"));
    }

    @Test
    void invalidCorrelationIdGetsDeterministic400WithAuditId() {
        ResponseEntity<JsonNode> resp = process("bad id!!", null, null, null, null);
        assertEquals(400, resp.getStatusCode().value());
        assertEquals("INVALID_REQUEST", resp.getBody().get("category").asText());
        String auditId = resp.getBody().get("correlationId").asText();
        assertTrue(auditId.startsWith("gen-"));
        assertEquals("rejected", resp.getHeaders().getFirst("X-Correlation-Id-Source"));
        // 非法标识请求本身也要进入 INVALID_REQUEST 指标，且端点为有界常量。
        assertEquals(1L, metricCount("-", "INVALID_REQUEST"));
    }

    @Test
    void slowCallWithinDeadlineSucceedsAndLandsInLatencyBucket() {
        long t0 = System.currentTimeMillis();
        ResponseEntity<JsonNode> resp = process("req-slow-0001", "slow", "300", null, "2000");
        long elapsed = System.currentTimeMillis() - t0;
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(1, resp.getBody().get("attempts").asInt(), "慢调用不应触发重试");
        assertTrue(elapsed >= 280, "应真实等待约 300ms，实际 " + elapsed + "ms");
        JsonNode metrics = rest.getForObject(url("/observability/metrics"), JsonNode.class);
        boolean in250to500 = false;
        for (JsonNode point : metrics.get("points")) {
            if ("/api/process".equals(point.get("endpoint").asText())
                    && "SUCCESS".equals(point.get("category").asText())
                    && "250-500ms".equals(point.get("bucket").asText())) {
                in250to500 = true;
            }
        }
        assertTrue(in250to500, "300ms 请求应落入 250-500ms 桶: " + metrics);
    }

    @Test
    void slowCallBeyondDeadlineTimesOutWithTimeoutRootCause() {
        long t0 = System.currentTimeMillis();
        ResponseEntity<JsonNode> resp = process("req-slow-timeout", "slow", "1500", null, "200");
        long elapsed = System.currentTimeMillis() - t0;
        assertEquals(500, resp.getStatusCode().value());
        assertEquals("SYSTEM_ERROR", resp.getBody().get("category").asText());
        assertEquals("java.util.concurrent.TimeoutException",
                resp.getBody().get("rootCauseType").asText(),
                "服务侧截止时间的原始超时根因不得被覆盖: " + resp.getBody());
        assertTrue(resp.getBody().get("causeChain").asText().contains("TimeoutException"));
        assertTrue(elapsed < 1000, "超时后应快速失败而不是等完 1500ms，实际 " + elapsed + "ms");
        assertEquals(1L, metricCount("/api/process", "SYSTEM_ERROR"));
    }

    @Test
    void downstreamTimeoutModeReportsDownstreamTimeoutRootCause() {
        ResponseEntity<JsonNode> resp = process("req-timeout-mode", "timeout", null, null, "2000");
        assertEquals(500, resp.getStatusCode().value());
        assertEquals("com.github.h3yow.serviceobservabilityjava.downstream.DownstreamTimeoutException",
                resp.getBody().get("rootCauseType").asText());
        assertTrue(resp.getBody().get("causeChain").asText().contains("DownstreamTimeoutException"));
        // 三次尝试都超时，但指标只计一次。
        assertEquals(1L, metricCount("/api/process", "SYSTEM_ERROR"));
    }

    @Test
    void failureRecoversAfterRetryButCountsOnce() {
        // 前 2 次尝试 IO 失败，第 3 次成功。
        ResponseEntity<JsonNode> resp = process("req-retry-recover", "fail", null, "2", null);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(3, resp.getBody().get("attempts").asInt());
        assertEquals("primary-recovered", resp.getBody().get("primary").asText());
        assertEquals(1L, metricCount("/api/process", "SUCCESS"),
                "重试后成功也只能产生 1 条成功计数");
    }

    @Test
    void exhaustedRetriesKeepIOExceptionRootCauseAndCountOnce() {
        ResponseEntity<JsonNode> resp = process("req-retry-exhaust", "fail", null, "0", null);
        assertEquals(500, resp.getStatusCode().value());
        assertEquals("java.io.IOException",
                resp.getBody().get("rootCauseType").asText(),
                "重试用尽后根因仍须是底层 IOException: " + resp.getBody());
        String chain = resp.getBody().get("causeChain").asText();
        assertTrue(chain.startsWith("DownstreamException"),
                "异常链应包含包装层: " + chain);
        assertTrue(chain.endsWith("IOException"), "异常链末端应是 IOException: " + chain);
        assertEquals(1L, metricCount("/api/process", "SYSTEM_ERROR"));
        // 时间线上 3 次尝试各自失败，且有 2 次重试事件。
        JsonNode timeline = rest.getForObject(
                url("/observability/timelines/req-retry-exhaust"), JsonNode.class);
        int failed = 0;
        int retries = 0;
        long lastSeq = 0;
        for (JsonNode event : timeline.get("events")) {
            long seq = event.get("seq").asLong();
            assertTrue(seq > lastSeq, "事件序号必须严格递增");
            lastSeq = seq;
            if ("DOWNSTREAM_CALL_FAILED".equals(event.get("name").asText())) {
                failed++;
            }
            if ("DOWNSTREAM_CALL_RETRY".equals(event.get("name").asText())) {
                retries++;
            }
        }
        assertEquals(3, failed, "三次尝试都要有失败事件");
        assertEquals(2, retries, "应有两次重试事件");
    }

    @Test
    void businessRejectionIsNotRetriedAndClassified422() {
        ResponseEntity<JsonNode> resp = process("req-reject-0001", "reject", null, null, null);
        assertEquals(422, resp.getStatusCode().value());
        assertEquals("BUSINESS_REJECTION", resp.getBody().get("category").asText());
        assertEquals(1L, metricCount("/api/process", "BUSINESS_REJECTION"));
        assertEquals(0L, metricCount("/api/process", "SYSTEM_ERROR"));
        JsonNode timeline = rest.getForObject(
                url("/observability/timelines/req-reject-0001"), JsonNode.class);
        int rejectedEvents = 0;
        int startedEvents = 0;
        for (JsonNode event : timeline.get("events")) {
            if ("BUSINESS_REJECTED".equals(event.get("name").asText())) {
                rejectedEvents++;
            }
            if ("DOWNSTREAM_CALL_STARTED".equals(event.get("name").asText())) {
                startedEvents++;
            }
        }
        assertEquals(1, startedEvents, "业务拒绝不得重试，只发起一次调用");
        assertEquals(1, rejectedEvents);
    }

    @Test
    void timelineIsQueryableOrderedAndContainsNestedAuditCall() {
        ResponseEntity<JsonNode> resp = process("req-timeline-01", null, null, null, null);
        assertEquals(200, resp.getStatusCode().value());
        JsonNode timeline = rest.getForObject(
                url("/observability/timelines/req-timeline-01"), JsonNode.class);
        assertTrue(timeline.get("found").asBoolean());
        JsonNode events = timeline.get("events");
        assertNotNull(events);
        long lastSeq = 0;
        long lastMicros = -1;
        String joined = "";
        for (JsonNode event : events) {
            long seq = event.get("seq").asLong();
            long micros = event.get("elapsedMicros").asLong();
            assertTrue(seq > lastSeq);
            assertTrue(micros >= lastMicros, "相对耗时必须非递减");
            lastSeq = seq;
            lastMicros = micros;
            joined += event.get("name").asText() + " " + event.get("detail").asText() + "\n";
        }
        assertTrue(joined.contains("WORK_DISPATCHED"), joined);
        assertTrue(joined.contains("primary:mode=none"), joined);
        assertTrue(joined.contains("audit"), "嵌套审计调用必须出现在同一时间线: " + joined);
        assertTrue(joined.contains("REQUEST_COMPLETED"), joined);
    }

    @Test
    void unknownTimelineReturns404() {
        ResponseEntity<JsonNode> resp = rest.getForEntity(
                url("/observability/timelines/does-not-exist"), JsonNode.class);
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void mixedOutcomesKeepIndependentCounters() {
        process("req-mix-ok", null, null, null, null);
        process("req-mix-reject", "reject", null, null, null);
        process("req-mix-fail", "fail", null, "0", null);
        assertEquals(1L, metricCount("/api/process", "SUCCESS"));
        assertEquals(1L, metricCount("/api/process", "BUSINESS_REJECTION"));
        assertEquals(1L, metricCount("/api/process", "SYSTEM_ERROR"));
        // 观测端点本身也会被计数，业务口径只统计 /api/process：三类各一，不重不漏。
        long businessTotal = metricCount("/api/process", "SUCCESS")
                + metricCount("/api/process", "BUSINESS_REJECTION")
                + metricCount("/api/process", "SYSTEM_ERROR");
        assertEquals(3L, businessTotal, "三个业务请求总计数为 3，不重不漏");
    }
}
