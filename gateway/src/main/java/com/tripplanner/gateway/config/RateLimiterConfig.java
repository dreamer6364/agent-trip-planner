package com.tripplanner.gateway.config;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 限流配置
 * Redis Token Bucket + KeyResolver (IP/User/Endpoint)
 */
@Configuration
public class RateLimiterConfig {

    @Value("${rate-limit.default.replenish-rate:100}")
    private int defaultReplenishRate;

    @Value("${rate-limit.default.burst-capacity:200}")
    private int defaultBurstCapacity;

    @Value("${rate-limit.auth.replenish-rate:10}")
    private int authReplenishRate;

    @Value("${rate-limit.auth.burst-capacity:20}")
    private int authBurstCapacity;

    @Value("${rate-limit.api.replenish-rate:50}")
    private int apiReplenishRate;

    @Value("${rate-limit.api.burst-capacity:100}")
    private int apiBurstCapacity;

    /**
     * 默认限流器 (IP 维度)
     */
    @Bean
    @Primary
    public RedisRateLimiter defaultRateLimiter() {
        return new RedisRateLimiter(defaultReplenishRate, defaultBurstCapacity, 1);
    }

    /**
     * 认证接口限流器 (更严格)
     */
    @Bean
    public RedisRateLimiter authRateLimiter() {
        return new RedisRateLimiter(authReplenishRate, authBurstCapacity, 1);
    }

    /**
     * API 接口限流器 (用户维度)
     */
    @Bean
    public RedisRateLimiter apiRateLimiter() {
        return new RedisRateLimiter(apiReplenishRate, apiBurstCapacity, 1);
    }

    /**
     * KeyResolver: IP 维度
     * Key: ratelimit:ip:{ip}:{path}
     */
    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String ip = getClientIp(exchange.getRequest());
            String path = exchange.getRequest().getPath().value();
            return Mono.just("ip:" + ip + ":" + path);
        };
    }

    /**
     * KeyResolver: 用户维度
     * Key: ratelimit:user:{userId}:{path}
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId == null) {
                String ip = getClientIp(exchange.getRequest());
                String path = exchange.getRequest().getPath().value();
                return Mono.just("ip:" + ip + ":" + path);
            }
            String path = exchange.getRequest().getPath().value();
            return Mono.just("user:" + userId + ":" + path);
        };
    }

    /**
     * KeyResolver: 认证接口 (IP + 端点)
     */
    @Bean
    public KeyResolver authKeyResolver() {
        return exchange -> {
            String ip = getClientIp(exchange.getRequest());
            String path = exchange.getRequest().getPath().value();
            return Mono.just("auth:" + ip + ":" + path);
        };
    }

    /**
     * Resilience4j RateLimiter 注册表 (用于编程式限流)
     */
    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        io.github.resilience4j.ratelimiter.RateLimiterConfig defaultConfig = io.github.resilience4j.ratelimiter.RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .limitForPeriod(defaultReplenishRate)
                .timeoutDuration(Duration.ofMillis(500))
                .build();

        return RateLimiterRegistry.of(defaultConfig);
    }

    private String getClientIp(org.springframework.http.server.reactive.ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        return request.getRemoteAddress() != null ? request.getRemoteAddress().getAddress().getHostAddress() : "unknown";
    }
}
