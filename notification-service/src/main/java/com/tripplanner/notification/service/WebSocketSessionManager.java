package com.tripplanner.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 会话管理器
 * 管理用户连接、多设备、消息路由
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketSessionManager {

    private final SimpMessagingTemplate messagingTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String USER_SESSIONS_KEY = "ws:sessions:";
    private static final String SESSION_USER_KEY = "ws:session:";
    private static final long SESSION_TTL_HOURS = 24;

    // 本地缓存 (单实例时使用，集群需依赖 Redis)
    private final Map<String, Set<String>> userSessions = new ConcurrentHashMap<>();

    /**
     * 注册新连接
     */
    public void registerSession(String userId, String sessionId) {
        // 1. 本地缓存
        userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);

        // 2. Redis 持久化 (集群共享)
        String sessionKey = SESSION_USER_KEY + sessionId;
        redisTemplate.opsForValue().set(sessionKey, userId, SESSION_TTL_HOURS, TimeUnit.HOURS);

        String userSessionsKey = USER_SESSIONS_KEY + userId;
        redisTemplate.opsForSet().add(userSessionsKey, sessionId);
        redisTemplate.expire(userSessionsKey, SESSION_TTL_HOURS, TimeUnit.HOURS);

        log.debug("WebSocket 会话注册: userId={}, sessionId={}, 当前连接数={}", 
                userId, sessionId, getUserSessionCount(userId));
    }

    /**
     * 注销连接
     */
    public void unregisterSession(String userId, String sessionId) {
        // 1. 本地缓存
        Set<String> sessions = userSessions.get(userId);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                userSessions.remove(userId);
            }
        }

        // 2. Redis 清理
        redisTemplate.opsForSet().remove(USER_SESSIONS_KEY + userId, sessionId);
        redisTemplate.delete(SESSION_USER_KEY + sessionId);

        log.debug("WebSocket 会话注销: userId={}, sessionId={}, 剩余连接数={}", 
                userId, sessionId, getUserSessionCount(userId));
    }

    /**
     * 获取用户在线会话数
     */
    public int getUserSessionCount(String userId) {
        // 优先本地缓存
        Set<String> sessions = userSessions.get(userId);
        if (sessions != null && !sessions.isEmpty()) {
            return sessions.size();
        }
        // 回退 Redis
        Long count = redisTemplate.opsForSet().size(USER_SESSIONS_KEY + userId);
        return count != null ? count.intValue() : 0;
    }

    /**
     * 判断用户是否在线
     */
    public boolean isUserOnline(String userId) {
        return getUserSessionCount(userId) > 0;
    }

    /**
     * 向用户发送消息 (所有会话)
     * 使用 Spring 的 /user 前缀自动路由到用户的所有会话
     */
    public void sendToUser(String userId, String destination, Object payload) {
        messagingTemplate.convertAndSendToUser(userId, destination, payload);
        log.trace("发送消息到用户: userId={}, destination={}", userId, destination);
    }

    /**
     * 向用户特定会话发送消息
     */
    public void sendToSession(String sessionId, String destination, Object payload) {
        // Spring 不直接支持按 sessionId 发送，需通过用户路由
        // 这里简化：通过用户发送，前端根据 sessionId 过滤
        String userId = getUserIdBySession(sessionId);
        if (userId != null) {
            sendToUser(userId, destination, payload);
        }
    }

    /**
     * 广播消息到所有在线用户
     */
    public void broadcast(String destination, Object payload) {
        messagingTemplate.convertAndSend(destination, payload);
        log.debug("广播消息: destination={}", destination);
    }

    /**
     * 广播给指定用户列表
     */
    public void broadcastToUsers(List<String> userIds, String destination, Object payload) {
        for (String userId : userIds) {
            sendToUser(userId, destination, payload);
        }
    }

    /**
     * 获取用户的所有会话 ID
     */
    public Set<String> getUserSessions(String userId) {
        Set<String> sessions = userSessions.get(userId);
        if (sessions != null && !sessions.isEmpty()) {
            return sessions;
        }
        // 回退 Redis
        Set<Object> redisSessions = redisTemplate.opsForSet().members(USER_SESSIONS_KEY + userId);
        if (redisSessions != null) {
            return redisSessions.stream().map(Object::toString).collect(java.util.stream.Collectors.toSet());
        }
        return Set.of();
    }

    /**
     * 根据 sessionId 获取 userId
     */
    public String getUserIdBySession(String sessionId) {
        return (String) redisTemplate.opsForValue().get(SESSION_USER_KEY + sessionId);
    }

    /**
     * 刷新会话 TTL (心跳时调用)
     */
    public void refreshSession(String userId, String sessionId) {
        redisTemplate.expire(SESSION_USER_KEY + sessionId, SESSION_TTL_HOURS, TimeUnit.HOURS);
        redisTemplate.expire(USER_SESSIONS_KEY + userId, SESSION_TTL_HOURS, TimeUnit.HOURS);
    }

    /**
     * 踢出用户所有会话 (如修改密码、封号)
     */
    public void kickoutUser(String userId) {
        Set<String> sessions = getUserSessions(userId);
        for (String sessionId : sessions) {
            // 发送踢出通知
            sendToSession(sessionId, "/queue/kickout", Map.of("reason", "账号在其他设备登录"));
            unregisterSession(userId, sessionId);
        }
        log.info("用户被踢出: userId={}, 会话数={}", userId, sessions.size());
    }
}