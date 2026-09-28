package com.tripplanner.gateway.filter;

import com.tripplanner.common.util.RequestIdUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 请求/响应日志过滤器
 * 记录请求行、参数、响应状态、耗时、用户ID
 */
@Slf4j
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private static final List<String> SENSITIVE_HEADERS = List.of(
            "authorization", "cookie", "set-cookie", "x-api-key"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Instant startTime = Instant.now();
        ServerHttpRequest request = exchange.getRequest();
        String requestId = request.getHeaders().getFirst(RequestIdUtils.HEADER_NAME);
        String userId = request.getHeaders().getFirst("X-User-Id");

        // 记录请求
        logRequest(request, requestId, userId);

        // 包装响应以记录响应体 (仅错误时)
        return chain.filter(exchange).doFinally(signalType -> {
            Instant endTime = Instant.now();
            long durationMs = Duration.between(startTime, endTime).toMillis();

            int statusCode = exchange.getResponse().getStatusCode() != null 
                    ? exchange.getResponse().getStatusCode().value() : 0;

            // 记录响应
            logResponse(exchange, requestId, userId, statusCode, durationMs);
        });
    }

    private void logRequest(ServerHttpRequest request, String requestId, String userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("REQUEST ")
                .append("requestId=").append(requestId)
                .append(" userId=").append(userId != null ? userId : "anonymous")
                .append(" method=").append(request.getMethod())
                .append(" uri=").append(request.getURI())
                .append(" query=").append(request.getURI().getQuery())
                .append(" headers=").append(maskSensitiveHeaders(request.getHeaders()))
                .append(" remoteAddr=").append(getClientIp(request));

        log.info(sb.toString());
    }

    private void logResponse(ServerWebExchange exchange, String requestId, String userId, int statusCode, long durationMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("RESPONSE ")
                .append("requestId=").append(requestId)
                .append(" userId=").append(userId != null ? userId : "anonymous")
                .append(" status=").append(statusCode)
                .append(" durationMs=").append(durationMs)
                .append(" responseHeaders=").append(maskSensitiveHeaders(exchange.getResponse().getHeaders()));

        if (statusCode >= 400) {
            log.warn(sb.toString());
        } else {
            log.info(sb.toString());
        }
    }

    private String maskSensitiveHeaders(HttpHeaders headers) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String key : headers.keySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(key).append("=");
            if (SENSITIVE_HEADERS.contains(key.toLowerCase())) {
                sb.append("***MASKED***");
            } else {
                sb.append(headers.getFirst(key));
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String getClientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddress() != null ? request.getRemoteAddress().getAddress().getHostAddress() : "unknown";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}