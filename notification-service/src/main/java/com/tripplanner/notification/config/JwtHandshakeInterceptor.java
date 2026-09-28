package com.tripplanner.notification.config;

import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.common.util.RequestIdUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * JWT 握手拦截器
 * 验证 WebSocket 连接时的 JWT Token
 * 支持两种方式：
 * 1. Query Parameter: /ws/notify?token=xxx
 * 2. STOMP CONNECT Header: Authorization: Bearer xxx
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor, ChannelInterceptor {

    private final JsonUtils jsonUtils;

    // ===== HandshakeInterceptor (HTTP 握手阶段) =====

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // 从 Query Parameter 获取 token
        String query = request.getURI().getQuery();
        String token = extractTokenFromQuery(query);
        
        if (!StringUtils.hasText(token)) {
            // 尝试从 Header 获取 (某些客户端支持)
            String authHeader = request.getHeaders().getFirst("Authorization");
            token = extractTokenFromHeader(authHeader);
        }

        if (!StringUtils.hasText(token)) {
            log.warn("WebSocket 握手失败: 缺少 JWT Token");
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }

        try {
            // 验证 JWT (这里简化，实际应调用 AuthService 或使用 JwtDecoder)
            String userId = validateAndExtractUserId(token);
            if (userId == null) {
                log.warn("WebSocket 握手失败: Token 无效");
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }

            // 将 userId 存入 attributes，后续 Principal 可获取
            attributes.put("userId", userId);
            attributes.put("token", token);
            
            log.debug("WebSocket 握手成功: userId={}", userId);
            return true;
        } catch (Exception e) {
            log.warn("WebSocket 握手失败: Token 解析异常: {}", e.getMessage());
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 握手后清理
    }

    // ===== ChannelInterceptor (STOMP 消息阶段) =====

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        
        if (accessor == null) {
            return message;
        }

        // CONNECT 帧：二次验证 (支持在 CONNECT 时传 token)
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = extractTokenFromConnectHeaders(accessor);
            if (StringUtils.hasText(token)) {
                String userId = validateAndExtractUserId(token);
                if (userId != null) {
                    accessor.setUser(new StompPrincipal(userId));
                    log.debug("STOMP CONNECT 认证成功: userId={}", userId);
                } else {
                    throw new IllegalArgumentException("无效的 JWT Token");
                }
            }
        }

        // SUBSCRIBE/UNSUBSCRIBE: 记录日志
        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            log.debug("用户订阅: userId={}, destination={}", 
                    accessor.getUser() != null ? accessor.getUser().getName() : "anonymous", destination);
        }

        return message;
    }

    @Override
    public void postSend(Message<?> message, MessageChannel channel, boolean sent) {
        // 发送后处理
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        // 完成回调
    }

    // ===== 辅助方法 =====

    private String extractTokenFromQuery(String query) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=");
            if (kv.length == 2 && "token".equals(kv[0])) {
                return kv[1];
            }
        }
        return null;
    }

    private String extractTokenFromHeader(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    private String extractTokenFromConnectHeaders(StompHeaderAccessor accessor) {
        // 优先从 Authorization Header 获取
        List<String> authHeaders = accessor.getNativeHeader("Authorization");
        if (authHeaders != null && !authHeaders.isEmpty()) {
            return extractTokenFromHeader(authHeaders.get(0));
        }
        // 兼容：从自定义 header 获取
        List<String> tokenHeaders = accessor.getNativeHeader("token");
        if (tokenHeaders != null && !tokenHeaders.isEmpty()) {
            return tokenHeaders.get(0);
        }
        return null;
    }

    /**
     * 验证 JWT 并提取 userId
     * 实际项目中应使用 JwtDecoder 或调用 AuthService
     */
    private String validateAndExtractUserId(String token) {
        try {
            // 简化实现：解析 JWT claims (不验证签名，仅作演示)
            // 生产环境必须验证签名！
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
            Map<String, Object> claims = jsonUtils.fromJson(payload, Map.class);
            
            // 检查过期时间
            Object exp = claims.get("exp");
            if (exp instanceof Number) {
                long expTime = ((Number) exp).longValue() * 1000;
                if (System.currentTimeMillis() > expTime) {
                    log.warn("Token 已过期");
                    return null;
                }
            }
            
            Object sub = claims.get("sub");
            return sub != null ? sub.toString() : null;
        } catch (Exception e) {
            log.debug("Token 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * STOMP Principal 适配
     */
    public static class StompPrincipal implements Principal {
        private final String name;

        public StompPrincipal(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }
    }
}