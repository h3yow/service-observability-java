package com.github.h3yow.serviceobservabilityjava.observability;

import com.github.h3yow.serviceobservabilityjava.budget.RequestBudget;
import com.github.h3yow.serviceobservabilityjava.budget.RequestBudgets;
import com.github.h3yow.serviceobservabilityjava.correlation.CorrelationContext;
import com.github.h3yow.serviceobservabilityjava.correlation.CorrelationContextHolder;
import com.github.h3yow.serviceobservabilityjava.correlation.CorrelationIds;
import com.github.h3yow.serviceobservabilityjava.metrics.MetricsRegistry;
import com.github.h3yow.serviceobservabilityjava.timeline.DefaultRequestTimeline;
import com.github.h3yow.serviceobservabilityjava.timeline.OutcomeCategory;
import com.github.h3yow.serviceobservabilityjava.timeline.RequestTimeline;
import com.github.h3yow.serviceobservabilityjava.timeline.TimelineEventName;
import com.github.h3yow.serviceobservabilityjava.timeline.TimelineStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求边界过滤器：
 * <ol>
 *   <li>归一化关联标识（provided/generated/rejected），打印原始输入与判定依据；</li>
 *   <li>建立请求作用域与时间线，写 MDC，响应头回填标识与来源；</li>
 *   <li>非法标识直接返回确定性 400（不进入控制器）；</li>
 *   <li>请求结束时按最终类别“仅一次”记录聚合指标并保存时间线。</li>
 * </ol>
 */
public final class ObservabilityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityFilter.class);
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_SOURCE = "correlationSource";

    private final TimelineStore timelineStore;
    private final MetricsRegistry metricsRegistry;

    public ObservabilityFilter(TimelineStore timelineStore, MetricsRegistry metricsRegistry) {
        this.timelineStore = timelineStore;
        this.metricsRegistry = metricsRegistry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 不观测静态/错误基础路径；查询接口本身仍被观测。
        return path.startsWith("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        String rawHeader = request.getHeader(CorrelationIds.HEADER);
        var decision = CorrelationIds.decide(rawHeader);
        String rawBudget = request.getHeader(RequestBudgets.HEADER);
        var budgetDecision = RequestBudgets.decide(rawBudget);

        CorrelationContext context = new CorrelationContext(decision.id(), decision.source(),
                decision.raw());
        RequestTimeline timeline = new DefaultRequestTimeline(decision.id(), request.getMethod(),
                request.getRequestURI(), startNanos);
        // 预算时钟与请求同一时间原点起算，全请求（含重试与嵌套调用）共享这一份。
        RequestBudget budget = new RequestBudget(budgetDecision.effectiveMillis(), startNanos);
        RequestScope scope = new RequestScope(context, timeline, budget);

        CorrelationContextHolder.begin(context);
        RequestScopeHolder.begin(scope);
        MDC.put(MDC_CORRELATION_ID, decision.id());
        MDC.put(MDC_SOURCE, decision.source());
        // 单测要求可直接观察“原始输入 + 归一化后的判定依据”。
        log.info("correlation decision rawHeader={} source={} effectiveId={} basis={}",
                rawHeader, decision.source(), decision.id(), decision.reason());
        log.info("budget decision rawHeader={} source={} effectiveMillis={} basis={}",
                rawBudget, budgetDecision.source(), budgetDecision.effectiveMillis(),
                budgetDecision.reason());

        response.setHeader(CorrelationIds.HEADER, decision.id());
        response.setHeader(CorrelationIds.SOURCE_HEADER, decision.source());
        response.setHeader(RequestBudgets.HEADER, String.valueOf(budgetDecision.effectiveMillis()));
        response.setHeader(RequestBudgets.SOURCE_HEADER, budgetDecision.source());

        try {
            if (CorrelationIds.SOURCE_REJECTED.equals(decision.source())) {
                writeInvalidCorrelation(response, timeline, scope);
                return;
            }
            scope.addEvent(TimelineEventName.REQUEST_RECEIVED, 0, request.getMethod());
            filterChain.doFilter(request, response);
        } catch (Exception ex) {
            // 异常处理器未覆盖到的兜底（如过滤器链内抛出）：根因不丢、结果可分类。
            if (scope.markOutcome(OutcomeCategory.SYSTEM_ERROR)) {
                scope.addEvent(TimelineEventName.REQUEST_FAILED, 0,
                        "filter:" + ex.getClass().getSimpleName());
            }
            log.error("uncaught exception correlationId={} causeChain={}",
                    decision.id(), simpleChain(ex), ex);
            throw ex;
        } finally {
            long durationMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            int finalStatus = response.getStatus();
            String endpoint = resolveEndpoint(request, finalStatus);
            OutcomeCategory outcome = resolveOutcome(scope, finalStatus, endpoint);
            scope.markOutcome(outcome);

            // 完成事件：成功记 REQUEST_COMPLETED；此前已有失败事件则只补完成态，不改变类别。
            scope.addEvent(outcome == OutcomeCategory.SUCCESS
                            ? TimelineEventName.REQUEST_COMPLETED
                            : TimelineEventName.REQUEST_FAILED,
                    0, outcome.name());

            // 聚合只在这里发生：每个请求恰好一次，与重试/嵌套次数无关。
            metricsRegistry.record(endpoint, outcome, durationMillis);
            timelineStore.save(timeline);
            log.info("request finished correlationId={} endpoint={} outcome={} durationMillis={}",
                    decision.id(), endpoint, outcome, durationMillis);

            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_SOURCE);
            RequestScopeHolder.clear();
            CorrelationContextHolder.clear();
        }
    }

    private void writeInvalidCorrelation(HttpServletResponse response, RequestTimeline timeline,
                                         RequestScope scope) throws IOException {
        scope.addEvent(TimelineEventName.REQUEST_RECEIVED, 0, "rejected-correlation");
        scope.markOutcome(OutcomeCategory.INVALID_REQUEST);
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        String body = """
                {"category":"INVALID_REQUEST","status":400,"correlationId":"%s",\
                "message":"X-Correlation-Id must be 8-64 chars from [A-Za-z0-9._-]",\
                "source":"rejected"}\
                """.formatted(timeline.correlationId());
        response.getWriter().write(body);
    }

    /**
     * 路由模板在 HandlerMapping 之后才有；拿不到（404/拒绝等）时归一为有界常量，杜绝高基数。
     */
    private static String resolveEndpoint(HttpServletRequest request, int status) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String template && !template.isBlank()) {
            return template;
        }
        return status == 404 ? "/unmapped" : MetricsRegistry.UNMATCHED_ENDPOINT;
    }

    private static OutcomeCategory resolveOutcome(RequestScope scope, int status, String endpoint) {
        OutcomeCategory marked = scope.outcome();
        if (marked != null) {
            return marked;
        }
        if (status >= 200 && status < 300) {
            return OutcomeCategory.SUCCESS;
        }
        if (status == 422) {
            return OutcomeCategory.BUSINESS_REJECTION;
        }
        if (status == 504) {
            return OutcomeCategory.BUDGET_EXHAUSTED;
        }
        if (status >= 400 && status < 500) {
            return OutcomeCategory.INVALID_REQUEST;
        }
        return OutcomeCategory.SYSTEM_ERROR;
    }

    private static String simpleChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        while (c != null) {
            if (!sb.isEmpty()) {
                sb.append(" -> ");
            }
            sb.append(c.getClass().getSimpleName());
            c = c.getCause();
        }
        return sb.toString();
    }

    /** 供健康/测试使用的静态信息。 */
    public static Map<String, Object> rulesDescriptor() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("minLength", CorrelationIds.MIN_LENGTH);
        map.put("maxLength", CorrelationIds.MAX_LENGTH);
        map.put("charset", "A-Za-z0-9._-");
        return map;
    }
}
