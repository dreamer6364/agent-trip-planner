package com.tripplanner.gateway.handler;

import com.tripplanner.common.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 熔断/降级响应处理器
 * 当下游服务熔断或超时时返回友好错误
 */
@Component
public class FallbackHandler {

    /**
     * Trip Service 熔断降级
     */
    public Mono<ServerResponse> tripServiceFallback(ServerRequest request) {
        return ServerResponse.status(503)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ApiResponse.error("SERVICE_UNAVAILABLE", 
                        "行程服务暂时不可用，请稍后重试",
                        Map.of("service", "trip-service", "retryAfter", 30)));
    }

    /**
     * Plan Service 熔断降级
     */
    public Mono<ServerResponse> planServiceFallback(ServerRequest request) {
        return ServerResponse.status(503)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ApiResponse.error("SERVICE_UNAVAILABLE",
                        "规划服务暂时不可用，请稍后重试",
                        Map.of("service", "plan-service", "retryAfter", 30)));
    }

    /**
     * 通用 404 降级
     */
    public Mono<ServerResponse> notFoundFallback(ServerRequest request) {
        return ServerResponse.status(404)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ApiResponse.error("NOT_FOUND", "接口不存在"));
    }

    /**
     * 通用 500 降级
     */
    public Mono<ServerResponse> internalErrorFallback(ServerRequest request) {
        return ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ApiResponse.error("INTERNAL_ERROR", "网关内部错误，请稍后重试"));
    }

    /**
     * 限流降级 (429)
     */
    public Mono<ServerResponse> rateLimitFallback(ServerRequest request) {
        return ServerResponse.status(429)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Retry-After", "60")
                .bodyValue(ApiResponse.error("RATE_LIMITED", "请求过于频繁，请稍后重试",
                        Map.of("retryAfter", 60)));
    }
}