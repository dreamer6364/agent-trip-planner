package com.tripplanner.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关强制 UTF-8：JSON 请求未声明 charset 时补 application/json;charset=UTF-8，
 * 防止中文 rawInput 经网关转发后被按 ISO-8859-1 解码成 ?。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Utf8EncodingFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest original = exchange.getRequest();
        String ct = original.getHeaders().getFirst("Content-Type");
        ServerHttpRequest request = original;
        if (ct != null && ct.startsWith(MediaType.APPLICATION_JSON_VALUE)
                && !ct.contains("charset")) {
            request = original.mutate()
                    .headers(h -> h.set("Content-Type", MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8"))
                    .build();
        } else if (ct == null) {
            // 无 Content-Type 的 JSON POST：常见于测试客户端
            if ("POST".equals(original.getMethodValue())) {
                request = original.mutate()
                        .headers(h -> h.set("Content-Type", MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8"))
                        .build();
            }
        }
        final ServerHttpRequest finalReq = request;
        return chain.filter(exchange.mutate().request(finalReq).build());
    }
}
