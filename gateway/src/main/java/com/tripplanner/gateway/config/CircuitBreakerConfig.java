package com.tripplanner.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 熔断器配置
 * Resilience4j CircuitBreaker + TimeLimiter
 */
@Configuration
public class CircuitBreakerConfig {

    @Value("${circuit-breaker.trip-service.failure-rate-threshold:50}")
    private int tripFailureRateThreshold;

    @Value("${circuit-breaker.trip-service.wait-duration:30s}")
    private Duration tripWaitDuration;

    @Value("${circuit-breaker.trip-service.sliding-window-size:10}")
    private int tripSlidingWindowSize;

    @Value("${circuit-breaker.plan-service.failure-rate-threshold:50}")
    private int planFailureRateThreshold;

    @Value("${circuit-breaker.plan-service.wait-duration:30s}")
    private Duration planWaitDuration;

    @Value("${circuit-breaker.plan-service.sliding-window-size:10}")
    private int planSlidingWindowSize;

    @Value("${circuit-breaker.default.timeout:10s}")
    private Duration defaultTimeout;

    /**
     * Trip Service 熔断器配置
     */
    @Bean
    public io.github.resilience4j.circuitbreaker.CircuitBreakerConfig tripServiceCircuitBreakerConfig() {
        return io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .failureRateThreshold(tripFailureRateThreshold)
                .waitDurationInOpenState(tripWaitDuration)
                .slidingWindowSize(tripSlidingWindowSize)
                .minimumNumberOfCalls(5)
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(
                        java.io.IOException.class,
                        java.util.concurrent.TimeoutException.class,
                        org.springframework.web.reactive.function.client.WebClientResponseException.class
                )
                .build();
    }

    /**
     * Plan Service 熔断器配置
     */
    @Bean
    public io.github.resilience4j.circuitbreaker.CircuitBreakerConfig planServiceCircuitBreakerConfig() {
        return io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .failureRateThreshold(planFailureRateThreshold)
                .waitDurationInOpenState(planWaitDuration)
                .slidingWindowSize(planSlidingWindowSize)
                .minimumNumberOfCalls(5)
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(
                        java.io.IOException.class,
                        java.util.concurrent.TimeoutException.class,
                        org.springframework.web.reactive.function.client.WebClientResponseException.class
                )
                .build();
    }

    /**
     * 通用熔断器配置
     */
    @Bean
    public io.github.resilience4j.circuitbreaker.CircuitBreakerConfig defaultCircuitBreakerConfig() {
        return io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
    }

    /**
     * TimeLimiter 配置 (超时控制)
     */
    @Bean
    public TimeLimiterConfig defaultTimeLimiterConfig() {
        return TimeLimiterConfig.custom()
                .timeoutDuration(defaultTimeout)
                .cancelRunningFuture(true)
                .build();
    }

    /**
     * 熔断器注册表
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig tripServiceCircuitBreakerConfig,
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig planServiceCircuitBreakerConfig,
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig defaultCircuitBreakerConfig) {

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(defaultCircuitBreakerConfig);
        registry.circuitBreaker("trip-service", tripServiceCircuitBreakerConfig);
        registry.circuitBreaker("plan-service", planServiceCircuitBreakerConfig);
        registry.circuitBreaker("auth-service", defaultCircuitBreakerConfig);
        registry.circuitBreaker("notification-service", defaultCircuitBreakerConfig);
        return registry;
    }

    /**
     * TimeLimiter 注册表
     */
    @Bean
    public TimeLimiterRegistry timeLimiterRegistry(TimeLimiterConfig defaultTimeLimiterConfig) {
        return TimeLimiterRegistry.of(defaultTimeLimiterConfig);
    }
}
