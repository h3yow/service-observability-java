package com.github.h3yow.serviceobservabilityjava.observability.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 入站请求过滤器：提取/生成关联标识并写入上下文与响应头。
 * 处理结果确定且可区分：
 *  - provided          调用方提供了合法标识，原样保留；
 *  - generated-missing 未提供标识，由本服务生成；
 *  - generated-invalid 提供了非法标识，丢弃并生成新标识。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String STATUS_HEADER = "X-Correlation-Id-Status";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String raw = request.getHeader(HEADER);
        String id;
        String status;
        if (raw == null || raw.isBlank()) {
            id = CorrelationIds.generate();
            status = "generated-missing";
        } else if (CorrelationIds.isValid(raw)) {
            id = raw;
            status = "provided";
        } else {
            id = CorrelationIds.generate();
            status = "generated-invalid";
        }

        CorrelationContext.set(id, "provided".equals(status));
        response.setHeader(HEADER, id);
        response.setHeader(STATUS_HEADER, status);
        try {
            filterChain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }
}
