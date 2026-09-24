package com.github.h3yow.serviceobservabilityjava.api;

import com.github.h3yow.serviceobservabilityjava.observability.correlation.CorrelationContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** 演示端点：成功 / 业务拒绝 / 系统异常 / 含下游调用（可注入故障）。 */
@RestController
public class DemoController {

    private final DownstreamClient downstreamClient;
    private final Executor observabilityExecutor;

    public DemoController(DownstreamClient downstreamClient,
                          @Qualifier("observabilityExecutor") Executor observabilityExecutor) {
        this.downstreamClient = downstreamClient;
        this.observabilityExecutor = observabilityExecutor;
    }

    @GetMapping("/api/demo/ok")
    public Map<String, Object> ok() {
        String asyncId = CompletableFuture
                .supplyAsync(CorrelationContext::correlationId, observabilityExecutor)
                .join();
        Map<String, Object> body = base();
        body.put("asyncThreadCorrelationId", asyncId);
        body.put("propagated", CorrelationContext.correlationId().equals(asyncId));
        return body;
    }

    @GetMapping("/api/demo/reject")
    public Map<String, Object> reject() {
        throw new BusinessRejectionException("business rule violated: demo rejection");
    }

    @GetMapping("/api/demo/error")
    public Map<String, Object> error() {
        // 构造三层异常链，验证根因不被覆盖
        IllegalStateException root = new IllegalStateException("root-cause: disk full (simulated)");
        RuntimeException middle = new RuntimeException("middle: failed to persist", root);
        throw new RuntimeException("outer: request processing failed", middle);
    }

    @GetMapping("/api/demo/downstream")
    public Map<String, Object> downstream(@RequestParam(defaultValue = "0") int failAttempts,
                                          @RequestParam(defaultValue = "0") long latencyMs) {
        String correlationId = CorrelationContext.correlationId();
        String result = downstreamClient.call(correlationId, failAttempts, latencyMs);
        Map<String, Object> body = base();
        body.put("downstreamResult", result);
        return body;
    }

    private Map<String, Object> base() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("correlationId", CorrelationContext.correlationId());
        body.put("providedByCaller", CorrelationContext.providedByCaller());
        return body;
    }
}
