package com.tripplanner.notification.config;

import com.tripplanner.notification.config.JwtHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket STOMP 配置
 * 启用消息代理、配置端点、拦截器
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 启用简单消息代理 (内存)
        // 生产环境建议使用 RabbitMQ/Redis 作为外部代理
        registry.enableSimpleBroker("/queue", "/topic", "/user");
        
        // 应用目标前缀
        registry.setApplicationDestinationPrefixes("/app");
        
        // 用户目标前缀 (私有队列)
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 主端点 (WebSocket)
        registry.addEndpoint("/ws/notify")
                .setAllowedOriginPatterns("*")
                .addInterceptors(jwtHandshakeInterceptor)
                .withSockJS(); // SockJS 降级支持
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 可添加入站拦截器 (如权限检查、限流)
        registration.interceptors();
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        // 出站拦截器 (如日志、监控)
        registration.interceptors();
    }
}