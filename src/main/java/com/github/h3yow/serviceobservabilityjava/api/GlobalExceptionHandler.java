package com.github.h3yow.serviceobservabilityjava.api;

import com.github.h3yow.serviceobservabilityjava.observability.ObservabilityFilter;
import com.github.h3yow.serviceobservabilityjava.observability.correlation.CorrelationContext;
import com.github.h3yow.serviceobservabilityjava.observability.metrics.ResultCategory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常分类：业务拒绝 → 422，系统异常 → 500。
 * 原始根因（异常链最深处的 cause）写入请求属性与响应体，不被覆盖或丢弃。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessRejectionException.class)
    public ResponseEntity<Map<String, Object>> business(BusinessRejectionException ex, HttpServletRequest request) {
        return build(request, HttpStatus.UNPROCESSABLE_ENTITY, ResultCategory.BUSINESS_REJECTION, ex);
    }

    /** 未匹配路由 → 404，归为业务拒绝（4xx 客户端错误），不落入系统异常。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException ex, HttpServletRequest request) {
        return build(request, HttpStatus.NOT_FOUND, ResultCategory.BUSINESS_REJECTION, ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> system(Exception ex, HttpServletRequest request) {
        return build(request, HttpStatus.INTERNAL_SERVER_ERROR, ResultCategory.SYSTEM_ERROR, ex);
    }

    private ResponseEntity<Map<String, Object>> build(HttpServletRequest request, HttpStatus status,
                                                      ResultCategory category, Throwable ex) {
        Throwable root = rootCause(ex);
        String rootCauseText = root.getClass().getName() + ": " + root.getMessage();
        request.setAttribute(ObservabilityFilter.ATTR_CATEGORY, category);
        request.setAttribute(ObservabilityFilter.ATTR_ROOT_CAUSE, rootCauseText);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", category.name());
        body.put("message", ex.getMessage());
        body.put("rootCause", rootCauseText);
        body.put("correlationId", CorrelationContext.correlationId());
        return ResponseEntity.status(status).body(body);
    }

    /** 沿 cause 链找到最深处根因；链为空时返回自身。 */
    static Throwable rootCause(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
