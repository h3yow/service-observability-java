package com.github.h3yow.serviceobservabilityjava.web;

import com.github.h3yow.serviceobservabilityjava.downstream.FaultSpec;
import com.github.h3yow.serviceobservabilityjava.service.DemoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 演示端点。
 */
@RestController
@RequestMapping("/api")
public final class DemoController {

    private final DemoService demoService;

    public DemoController(DemoService demoService) {
        this.demoService = demoService;
    }

    /** 稳定基线端点：始终成功，用于健康检查与无流量对照。 */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("status", "ok");
    }

    /**
     * 业务端点：通过故障注入头复现慢调用、失败、超时、业务拒绝。
     * X-Fault-Mode: none|slow|fail|timeout|reject
     * X-Fault-Delay-Millis: slow 模式延迟（0-5000）
     * X-Fault-Fail-Attempts: 前 N 次尝试失败（0=每次失败，1-3）
     * X-Timeout-Millis: 服务侧截止时间（最大 2000）
     */
    @GetMapping("/process")
    public Map<String, Object> process(@RequestHeader(name = "X-Fault-Mode", required = false) String mode,
                                       @RequestHeader(name = "X-Fault-Delay-Millis", required = false) String delay,
                                       @RequestHeader(name = "X-Fault-Fail-Attempts", required = false) String failAttempts,
                                       @RequestHeader(name = "X-Timeout-Millis", required = false) String timeout) {
        FaultSpec spec = FaultSpec.fromHeaders(mode, delay, failAttempts);
        long timeoutMillis = parseTimeout(timeout);
        return demoService.process(spec, timeoutMillis);
    }

    private static long parseTimeout(String raw) {
        if (raw == null || raw.isBlank()) {
            return DemoService.DEFAULT_TIMEOUT_MILLIS;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return DemoService.DEFAULT_TIMEOUT_MILLIS;
        }
    }
}
