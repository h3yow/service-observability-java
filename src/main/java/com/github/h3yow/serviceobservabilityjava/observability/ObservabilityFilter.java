package com.github.h3yow.serviceobservabilityjava.observability;

import com.github.h3yow.serviceobservabilityjava.observability.correlation.CorrelationContext;
import com.github.h3yow.serviceobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.h3yow.serviceobservabilityjava.observability.metrics.ResultCategory;
import com.github.h3yow.serviceobservabilityjava.observability.timeline.RequestTimeline;
import com.github.h3yow.serviceobservabilityjava.observability.timeline.TimelineStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * 请求级观测过滤器：开启时间线、按响应状态归类结果、记录指标。
 * 结果归类口径：2xx/3xx → SUCCESS；4xx → BUSINESS_REJECTION；5xx → SYSTEM_ERROR。
 * 异常处理器可通过请求属性 {@link #ATTR_CATEGORY} / {@link #ATTR_ROOT_CAUSE}
 * 写入更精确的类别与原始根因，过滤器只读取不覆盖。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ObservabilityFilter extends OncePerRequestFilter {

    public static final String ATTR_CATEGORY = "obs.resultCategory";
    public static final String ATTR_ROOT_CAUSE = "obs.rootCause";

    private final TimelineStore timelineStore;
    private final MetricsRegistry metricsRegistry;

    public ObservabilityFilter(TimelineStore timelineStore, MetricsRegistry metricsRegistry) {
        this.timelineStore = timelineStore;
        this.metricsRegistry = metricsRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = CorrelationContext.correlationId();
        if (correlationId == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String endpoint = request.getMethod() + " " + request.getRequestURI();
        RequestTimeline timeline = timelineStore.begin(correlationId, endpoint);
        timeline.append("request-start", endpoint);

        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
            ResultCategory category = resolveCategory(request, response);
            String rootCause = (String) request.getAttribute(ATTR_ROOT_CAUSE);
            if (rootCause != null) {
                timeline.append("root-cause", rootCause);
            }
            // finish 只允许生效一次，重试/嵌套/超时路径不会重复计数
            if (timeline.finish(category)) {
                metricsRegistry.record(metricEndpoint(request), category, elapsedMillis);
            }
        }
    }

    /**
     * 指标端点标签：优先使用 HandlerMapping 的路由模板（如 /observability/timeline/{correlationId}），
     * 未匹配路由统一归为 UNMATCHED，保证标签有界、不产生高基数。
     */
    private String metricEndpoint(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = pattern != null ? pattern.toString() : "UNMATCHED";
        return request.getMethod() + " " + path;
    }

    private ResultCategory resolveCategory(HttpServletRequest request, HttpServletResponse response) {
        Object attr = request.getAttribute(ATTR_CATEGORY);
        if (attr instanceof ResultCategory category) {
            return category;
        }
        int status = response.getStatus();
        if (status >= 500) {
            return ResultCategory.SYSTEM_ERROR;
        }
        if (status >= 400) {
            return ResultCategory.BUSINESS_REJECTION;
        }
        return ResultCategory.SUCCESS;
    }
}
