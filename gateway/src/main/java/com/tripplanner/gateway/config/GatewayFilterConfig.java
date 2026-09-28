package com.tripplanner.gateway.config;

import com.tripplanner.gateway.filter.JwtAuthenticationFilter;
import com.tripplanner.gateway.filter.RequestLoggingFilter;
import com.tripplanner.gateway.handler.FallbackHandler;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * 网关全局过滤器配置 & 降级路由
 */
@Configuration
public class GatewayFilterConfig {

    /**
     * 全局过滤器链顺序：
     * 1. TraceIdFilter (最高优先级) - 生成/透传 Request ID
     * 2. JwtAuthenticationFilter - JWT 解析、用户信息注入
     * 3. RateLimitFilter (通过 RequestRateLimiter GatewayFilter) - 限流
     * 3. RequestLoggingFilter - 请求/响应日志
     * 4. CircuitBreakerGatewayFilter (通过配置) - 熔断
     * 5. RetryGatewayFilter (通过配置) - 重试
     */

    @Bean
    public GlobalFilter traceIdFilter() {
        return (exchange, chain) -> {
            String requestId = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
            if (requestId == null || requestId.isBlank()) {
                requestId = java.util.UUID.randomUUID().toString().replace("-", "");
            }
            String finalRequestId = requestId;
            return chain.filter(exchange.mutate().request(
                    exchange.getRequest().mutate()
                            .header("X-Request-Id", finalRequestId)
                            .build()
            ).build());
        };
    }

    /**
     * 降级路由 (熔断/超时/404 时转发)
     */
    @Bean
    public RouterFunction<ServerResponse> fallbackRouter(FallbackHandler fallbackHandler) {
        return RouterFunctions.route()
                .GET("/fallback/trip", fallbackHandler::tripServiceFallback)
                .GET("/fallback/plan", fallbackHandler::planServiceFallback)
                .GET("/fallback/404", fallbackHandler::notFoundFallback)
                .GET("/fallback/500", fallbackHandler::internalErrorFallback)
                .GET("/fallback/rate-limit", fallbackHandler::rateLimitFallback)
                .build();
    }

    /**
     * 响应头安全增强
     */
    @Bean
    public GlobalFilter securityHeadersFilter() {
        return (exchange, chain) -> chain.filter(exchange).then(Mono.fromRunnable(() -> {
            exchange.getResponse().getHeaders().add("X-Content-Type-Options", "nosniff");
            exchange.getResponse().getHeaders().add("X-Frame-Options", "DENY");
            exchange.getResponse().getHeaders().add("X-XSS-Protection", "1; mode=block");
            exchange.getResponse().getHeaders().add("Referrer-Policy", "strict-origin-when-cross-origin");
            exchange.getResponse().getHeaders().add("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline';");
        }));
    }
}