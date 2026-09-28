package com.tripplanner.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * CORS + 静态资源 + SPA Fallback
 */
@Configuration
public class StaticResourceConfig implements WebFluxConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }

    @Bean
    public WebFilter spaFallbackFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            String path = exchange.getRequest().getURI().getPath();

            // API、WebSocket、Actuator、带扩展名的静态资源 → 放行到后端/静态文件
            if (path.startsWith("/api/") || path.startsWith("/ws/") || path.startsWith("/actuator") || path.contains(".")) {
                return chain.filter(exchange);
            }

            // SPA 路由 → 返回 index.html
            return exchange.getResponse().writeWith(
                    Mono.fromSupplier(() -> {
                        var response = exchange.getResponse();
                        response.getHeaders().setContentType(MediaType.TEXT_HTML);
                        response.setStatusCode(HttpStatus.OK);
                        try (var is = new ClassPathResource("static/index.html").getInputStream()) {
                            return response.bufferFactory().wrap(is.readAllBytes());
                        } catch (Exception e) {
                            return response.bufferFactory().wrap(new byte[0]);
                        }
                    })
            );
        };
    }

    @Bean
    public WebFilter corsWebFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            ServerHttpResponse response = exchange.getResponse();
            String origin = request.getHeaders().getOrigin();

            if (origin != null) {
                response.getHeaders().add(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            } else {
                response.getHeaders().add(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "null");
            }
            response.getHeaders().add(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, PATCH, DELETE, OPTIONS");
            response.getHeaders().add(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*");
            response.getHeaders().add(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            response.getHeaders().add(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");

            if (request.getMethod() == HttpMethod.OPTIONS) {
                response.setStatusCode(HttpStatus.OK);
                return Mono.empty();
            }
            return chain.filter(exchange);
        };
    }

    /**
     * 非 API / WebSocket 路径的匹配器 (用于 SecurityConfig)
     */
    @Bean("nonApiPathMatcher")
    public ServerWebExchangeMatcher nonApiPathMatcher() {
        return new ServerWebExchangeMatcher() {
            @Override
            public Mono<MatchResult> matches(ServerWebExchange exchange) {
                String path = exchange.getRequest().getURI().getPath();
                if (path.startsWith("/api/") || path.startsWith("/ws/")) {
                    return MatchResult.notMatch();
                }
                return MatchResult.match();
            }
        };
    }
}
