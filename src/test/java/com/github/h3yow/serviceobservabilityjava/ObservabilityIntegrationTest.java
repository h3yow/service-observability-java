package com.github.h3yow.serviceobservabilityjava;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端到端：关联标识处理、并发隔离、异常链保留、指标一致性、本地故障注入。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityIntegrationTest.class);

    @Autowired
    private TestRestTemplate rest;

    private ResponseEntity<Map> get(String path, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        if (correlationId != null) {
            headers.set("X-Correlation-Id", correlationId);
        }
        ResponseEntity<Map> resp = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        log.info("GET {} rawCorrelationId={} -> status={} respId={} respStatus={}",
                path, correlationId, resp.getStatusCode(),
                resp.getHeaders().getFirst("X-Correlation-Id"),
                resp.getHeaders().getFirst("X-Correlation-Id-Status"));
        return resp;
    }

    @Test
    void validCorrelationIdIsPreserved() {
        ResponseEntity<Map> resp = get("/api/demo/ok", "valid-e2e-id-0001");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("valid-e2e-id-0001", resp.getHeaders().getFirst("X-Correlation-Id"));
        assertEquals("provided", resp.getHeaders().getFirst("X-Correlation-Id-Status"));
        assertEquals("valid-e2e-id-0001", resp.getBody().get("correlationId"));
        assertEquals(Boolean.TRUE, resp.getBody().get("providedByCaller"));
        // 跨线程传播：异步线程中读到同一标识
        assertEquals("valid-e2e-id-0001", resp.getBody().get("asyncThreadCorrelationId"));
        assertEquals(Boolean.TRUE, resp.getBody().get("propagated"));
    }

    @Test
    void missingCorrelationIdIsGenerated() {
        ResponseEntity<Map> resp = get("/api/demo/ok", null);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        String id = resp.getHeaders().getFirst("X-Correlation-Id");
        assertNotNull(id);
        assertEquals("generated-missing", resp.getHeaders().getFirst("X-Correlation-Id-Status"));
        assertEquals(id, resp.getBody().get("correlationId"));
        assertEquals(Boolean.FALSE, resp.getBody().get("providedByCaller"));
    }

    @Test
    void invalidCorrelationIdIsReplacedAndDistinguishable() {
        ResponseEntity<Map> resp = get("/api/demo/ok", "bad id!!");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        String id = resp.getHeaders().getFirst("X-Correlation-Id");
        assertNotNull(id);
        assertEquals("generated-invalid", resp.getHeaders().getFirst("X-Correlation-Id-Status"));
        assertTrue(!"bad id!!".equals(id), "非法标识必须被替换");
        assertEquals(id, resp.getBody().get("correlationId"));
    }

    @Test
    void concurrentRequestsDoNotCrossCorrelationIds() throws Exception {
        int n = 24;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = "conc-id-" + String.format("%08d", i);
            tasks.add(() -> {
                ResponseEntity<Map> resp = get("/api/demo/ok", id);
                Map body = resp.getBody();
                return id.equals(body.get("correlationId"))
                        && id.equals(body.get("asyncThreadCorrelationId"))
                        && id.equals(resp.getHeaders().getFirst("X-Correlation-Id"))
                        ? null : "mismatch for " + id + ": " + body;
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> t : tasks) {
            futures.add(pool.submit(t));
        }
        List<String> failures = new ArrayList<>();
        for (Future<String> f : futures) {
            String r = f.get();
            if (r != null) {
                failures.add(r);
            }
        }
        pool.shutdown();
        log.info("concurrency check: {} requests, failures={}", n, failures);
        assertTrue(failures.isEmpty(), failures.toString());

        // 每个请求的時間线独立且完整
        for (int i = 0; i < n; i++) {
            String id = "conc-id-" + String.format("%08d", i);
            ResponseEntity<Map> tl = rest.getForEntity("/observability/timeline/" + id, Map.class);
            assertEquals(HttpStatus.OK, tl.getStatusCode(), "timeline missing for " + id);
            assertEquals(id, tl.getBody().get("correlationId"));
            assertEquals("SUCCESS", tl.getBody().get("result"));
        }
    }

    @Test
    void exceptionChainRootCauseIsPreserved() {
        ResponseEntity<Map> resp = get("/api/demo/error", "err-chain-id-0001");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("SYSTEM_ERROR", resp.getBody().get("category"));
        String rootCause = (String) resp.getBody().get("rootCause");
        log.info("exception chain: message={} rootCause={}", resp.getBody().get("message"), rootCause);
        assertTrue(rootCause.contains("IllegalStateException"), rootCause);
        assertTrue(rootCause.contains("disk full"), rootCause);

        ResponseEntity<Map> tl = rest.getForEntity("/observability/timeline/err-chain-id-0001", Map.class);
        List<Map> events = (List<Map>) tl.getBody().get("events");
        boolean hasRootCauseEvent = events.stream()
                .anyMatch(e -> "root-cause".equals(e.get("type")) && e.get("detail").toString().contains("disk full"));
        assertTrue(hasRootCauseEvent, "时间线必须保留根因事件: " + events);
        assertEquals("SYSTEM_ERROR", tl.getBody().get("result"));
    }

    @Test
    void businessRejectionIsClassifiedSeparately() {
        ResponseEntity<Map> resp = get("/api/demo/reject", "biz-reject-00001");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        assertEquals("BUSINESS_REJECTION", resp.getBody().get("category"));
        ResponseEntity<Map> tl = rest.getForEntity("/observability/timeline/biz-reject-00001", Map.class);
        assertEquals("BUSINESS_REJECTION", tl.getBody().get("result"));
    }

    @Test
    void retriesDoNotDoubleCountAndTimelineShowsAttempts() {
        Map<String, Long> before = metrics();
        ResponseEntity<Map> resp = get("/api/demo/downstream?failAttempts=2&latencyMs=20", "retry-count-0001");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("downstream-ok after 3 attempt(s)", resp.getBody().get("downstreamResult"));

        Map<String, Long> after = metrics();
        long delta = delta(after, before, "GET /api/demo/downstream|SUCCESS|LE_100MS");
        log.info("retry request counted delta={} (must be exactly 1)", delta);
        assertEquals(1, delta, "重试 3 次尝试只能计 1 次请求");

        ResponseEntity<Map> tl = rest.getForEntity("/observability/timeline/retry-count-0001", Map.class);
        List<Map> events = (List<Map>) tl.getBody().get("events");
        long attempts = events.stream().filter(e -> "downstream-attempt".equals(e.get("type"))).count();
        long failures = events.stream().filter(e -> "downstream-failure".equals(e.get("type"))).count();
        log.info("timeline attempts={} failures={} events={}", attempts, failures, events);
        assertEquals(3, attempts);
        assertEquals(2, failures);
        // 序号严格递增
        List<Integer> seqs = events.stream().map(e -> ((Number) e.get("sequence")).intValue()).toList();
        assertEquals(seqs.stream().sorted().toList(), seqs, "事件序号必须有序");
    }

    @Test
    void faultInjectionSlowCallAndFailure() {
        Map<String, Long> before = metrics();

        // 慢调用：300ms 应落入 LE_500MS 桶
        ResponseEntity<Map> slow = get("/api/demo/downstream?latencyMs=300", "slow-call-000001");
        assertEquals(HttpStatus.OK, slow.getStatusCode());
        Map<String, Long> afterSlow = metrics();
        assertEquals(1, delta(afterSlow, before, "GET /api/demo/downstream|SUCCESS|LE_500MS"),
                "慢调用必须落入 LE_500MS 桶");

        // 失败注入：failAttempts=5 超过最大重试，最终失败且保留根因
        ResponseEntity<Map> fail = get("/api/demo/downstream?failAttempts=5", "fail-inject-0001");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, fail.getStatusCode());
        assertEquals("SYSTEM_ERROR", fail.getBody().get("category"));
        String rootCause = (String) fail.getBody().get("rootCause");
        log.info("injected failure rootCause={}", rootCause);
        assertTrue(rootCause.contains("injected downstream failure"), rootCause);

        // 超时注入：latencyMs 超过阈值
        ResponseEntity<Map> timeout = get("/api/demo/downstream?latencyMs=5000", "timeout-inj-00001");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, timeout.getStatusCode());
        assertTrue(((String) timeout.getBody().get("rootCause")).contains("DownstreamTimeoutException"));

        Map<String, Long> after = metrics();
        long systemErrors = delta(after, before, "GET /api/demo/downstream|SYSTEM_ERROR|LE_10MS");
        log.info("system error delta after fail+timeout={} (must be 2)", systemErrors);
        assertEquals(2, systemErrors, "失败与超时各计一次系统异常");
    }

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<Map> resp = rest.getForEntity("/observability/health", Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("UP", resp.getBody().get("status"));
        log.info("health={}", resp.getBody());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> metrics() {
        ResponseEntity<Map> resp = rest.getForEntity("/observability/metrics", Map.class);
        Map<String, Number> counters = (Map<String, Number>) resp.getBody().get("counters");
        Map<String, Long> result = new java.util.HashMap<>();
        counters.forEach((k, v) -> result.put(k, v.longValue()));
        return result;
    }

    private long delta(Map<String, Long> after, Map<String, Long> before, String key) {
        return after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L);
    }
}
