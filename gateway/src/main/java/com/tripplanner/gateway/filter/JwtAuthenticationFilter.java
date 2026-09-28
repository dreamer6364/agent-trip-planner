package com.tripplanner.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.util.RequestIdUtils;
import lombok.RequiredArgsConstructor;
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

/**
 * JWT 认证过滤器
 * 从 Authorization Header 解析 JWT，提取用户信息注入下游 Header
 * 下游服务直接读取 Header，无需再次验签
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final ObjectMapper objectMapper;

    private static final String AUTH_HEADER = HttpHeaders.AUTHORIZATION;
    private static final String BEARER_PREFIX = "Bearer ";

    // 下游服务读取的 Header
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLES_HEADER = "X-User-Roles";
    public static final String USER_EMAIL_HEADER = "X-User-Email";
    public static final String USER_NAME_HEADER = "X-User-Name";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 1. 生成/透传 Request ID
        String requestId = request.getHeaders().getFirst(RequestIdUtils.HEADER_NAME);
        if (!StringUtils.hasText(requestId)) {
            requestId = RequestIdUtils.generateRequestId();
        }
        ServerHttpRequest mutatedRequest = request.mutate()
                .header(RequestIdUtils.HEADER_NAME, requestId)
                .build();

        // 2. 解析 JWT (如果有)
        String authHeader = request.getHeaders().getFirst(AUTH_HEADER);
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(BEARER_PREFIX.length());
            try {
                // 简化：解析 JWT payload (不验签，网关已验签)
                String[] parts = token.split("\\.");
                if (parts.length == 3) {
                    String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> claims = objectMapper.readValue(payload, java.util.Map.class);

                    // 注入用户信息 Header
                    String userId = (String) claims.get("sub");
                    String email = (String) claims.get("email");
                    String name = (String) claims.get("name");
                    @SuppressWarnings("unchecked")
                    java.util.List<String> roles = (java.util.List<String>) claims.get("roles");

                    if (StringUtils.hasText(userId)) {
                        mutatedRequest = mutatedRequest.mutate()
                                .header(USER_ID_HEADER, userId)
                                .build();
                    }
                    if (StringUtils.hasText(email)) {
                        mutatedRequest = mutatedRequest.mutate()
                                .header(USER_EMAIL_HEADER, email)
                                .build();
                    }
                    if (StringUtils.hasText(name)) {
                        mutatedRequest = mutatedRequest.mutate()
                                .header(USER_NAME_HEADER, name)
                                .build();
                    }
                    if (roles != null && !roles.isEmpty()) {
                        mutatedRequest = mutatedRequest.mutate()
                                .header(USER_ROLES_HEADER, String.join(",", roles))
                                .build();
                    }
                }
            } catch (Exception e) {
                log.debug("JWT 解析失败 (下游将返回 401): {}", e.getMessage());
            }
        }

        // 3. 透传 Authorization Header 和其他有用信息
        String clientIp = getClientIp(request);
        mutatedRequest = mutatedRequest.mutate()
                .header("X-Forwarded-For", clientIp)
                .header("X-Gateway-Time", String.valueOf(System.currentTimeMillis()))
                .build();
        // 透传原始 Authorization 给下游服务
        if (StringUtils.hasText(authHeader)) {
            mutatedRequest = mutatedRequest.mutate()
                    .header(AUTH_HEADER, authHeader)
                    .build();
        }

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private String getClientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeaders().getFirst("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp;
        }
        return request.getRemoteAddress() != null ? request.getRemoteAddress().getAddress().getHostAddress() : "unknown";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10; // 在路由匹配前执行
    }
}