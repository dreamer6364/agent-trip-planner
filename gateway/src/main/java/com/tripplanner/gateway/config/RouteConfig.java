package com.tripplanner.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关路由配置
 * 定义所有微服务的路由规则、谓词、过滤器
 *
 * 路由目标使用直接 URI (非 lb://)，适用于无服务发现的单机/少量节点部署
 * 生产环境如需服务发现，将 URI 改为 lb://service-name 即可
 * 响应超时通过 application.yml 中 spring.cloud.gateway.httpclient.response-timeout 配置
 */
@Configuration
public class RouteConfig {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("trip-service", r -> r
                        .path("/api/trips/**", "/api/internal/**")
                        .filters(f -> f.addRequestHeader("X-Gateway-Service", "trip"))
                        .uri("http://localhost:8082")
                )
                .route("auth-service", r -> r
                        .path("/api/auth/**")
                        .filters(f -> f.addRequestHeader("X-Gateway-Service", "auth"))
                        .uri("http://localhost:8081")
                )
                .route("plan-service", r -> r
                        .path("/api/plan/**", "/api/agent/**")
                        .filters(f -> f.addRequestHeader("X-Gateway-Service", "plan"))
                        .uri("http://localhost:8083")
                )
                .route("notification-service", r -> r
                        .path("/api/notifications/**", "/api/ws-test/**")
                        .filters(f -> f.addRequestHeader("X-Gateway-Service", "notification"))
                        .uri("http://localhost:8085")
                )
                .route("ws-planning", r -> r
                        .path("/ws/**")
                        .filters(f -> f.addRequestHeader("X-Gateway-Service", "notification"))
                        .uri("http://localhost:8085")
                )
                .build();
    }
}
