package com.github.h3yow.serviceobservabilityjava.observability;

import com.github.h3yow.serviceobservabilityjava.correlation.CorrelationContext;
import com.github.h3yow.serviceobservabilityjava.correlation.CorrelationContextHolder;
import com.github.h3yow.serviceobservabilityjava.downstream.BusinessRejectionException;
import com.github.h3yow.serviceobservabilityjava.downstream.DownstreamException;
import com.github.h3yow.serviceobservabilityjava.downstream.DownstreamTimeoutException;
import com.github.h3yow.serviceobservabilityjava.timeline.OutcomeCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常映射：
 * <ul>
 *   <li>业务拒绝 {@link BusinessRejectionException} -> 422 BUSINESS_REJECTION；</li>
 *   <li>下游超时/失败等系统异常 -> 500 SYSTEM_ERROR；</li>
 *   <li>错误体携带根因类型链；日志保留完整堆栈，不覆盖、不静默丢弃根因。</li>
 * </ul>
 */
@RestControllerAdvice
public final class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public GlobalExceptionHandler() {
    }

    @ExceptionHandler(BusinessRejectionException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessRejection(BusinessRejectionException ex) {
        // 仅标记最终类别；终态事件由过滤器统一追加，避免同一拒绝被记两次。
        markOutcome(OutcomeCategory.BUSINESS_REJECTION);
        // 业务拒绝是预期结果，不打印堆栈。
        log.warn("business rejection correlationId={} reason={}", currentId(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(errorBody("BUSINESS_REJECTION", 422, ex));
    }

    @ExceptionHandler({DownstreamException.class, DownstreamTimeoutException.class})
    public ResponseEntity<Map<String, Object>> handleDownstream(RuntimeException ex) {
        markOutcome(OutcomeCategory.SYSTEM_ERROR);
        log.error("downstream system error correlationId={} rootCauseChain={}",
                currentId(), causeChain(ex), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("SYSTEM_ERROR", 500, ex));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        markOutcome(OutcomeCategory.SYSTEM_ERROR);
        log.error("unexpected system error correlationId={} rootCauseChain={}",
                currentId(), causeChain(ex), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("SYSTEM_ERROR", 500, ex));
    }

    private static void markOutcome(OutcomeCategory category) {
        RequestScope scope = RequestScopeHolder.current();
        if (scope != null) {
            scope.markOutcome(category);
        }
    }

    private static Map<String, Object> errorBody(String category, int status, Throwable ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("correlationId", currentId());
        body.put("category", category);
        body.put("status", status);
        body.put("message", ex.getMessage());
        body.put("rootCauseType", rootName(ex));
        body.put("causeChain", causeChain(ex));
        return body;
    }

    private static String currentId() {
        CorrelationContext context = CorrelationContextHolder.current();
        return context == null ? null : context.correlationId();
    }

    private static String rootName(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getClass().getName();
    }

    private static String causeChain(Throwable t) {
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
}
