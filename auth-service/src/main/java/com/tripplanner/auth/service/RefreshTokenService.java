package com.tripplanner.auth.service;

import com.tripplanner.auth.config.JwtConfig;
import com.tripplanner.auth.exception.TokenException;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.common.util.RequestIdUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Refresh Token 管理服务
 * 存储于 Redis，支持多设备、可撤销
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtConfig jwtConfig;
    private final JsonUtils jsonUtils;

    private static final String TOKEN_PREFIX = "refresh_token:";
    private static final String DEVICE_PREFIX = "device:";

    /**
     * 创建 Refresh Token
     */
    public String createRefreshToken(String userId, String deviceId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = buildKey(userId, deviceId);
        
        RefreshTokenData data = new RefreshTokenData(
                token,
                userId,
                deviceId,
                RequestIdUtils.getRequestId(),
                System.currentTimeMillis()
        );
        
        long ttlDays = jwtConfig.getRefreshTokenExpiryDays();
        redisTemplate.opsForValue().set(key, jsonUtils.toJson(data), ttlDays, TimeUnit.DAYS);
        
        // 维护用户的设备列表
        addDeviceToUser(userId, deviceId);
        
        log.debug("创建 Refresh Token: userId={}, deviceId={}", userId, deviceId);
        return token;
    }

    /**
     * 校验并获取用户 ID
     */
    public String validateAndGetUserId(String token, String deviceId) {
        // 遍历用户的所有设备查找匹配的 token
        // 实际项目中建议维护 token -> key 的反向索引
        String userId = findUserIdByToken(token, deviceId);
        if (userId == null) {
            throw TokenException.refreshTokenRevoked();
        }
        return userId;
    }

    /**
     * 撤销 Refresh Token
     */
    public void revokeRefreshToken(String token, String deviceId) {
        String userId = findUserIdByToken(token, deviceId);
        if (userId != null) {
            String key = buildKey(userId, deviceId);
            redisTemplate.delete(key);
            removeDeviceFromUser(userId, deviceId);
            log.debug("撤销 Refresh Token: userId={}, deviceId={}", userId, deviceId);
        }
    }

    /**
     * 撤销用户所有设备的 Token (修改密码、安全登出时调用)
     */
    public void revokeAllUserTokens(String userId) {
        var deviceKeys = redisTemplate.keys(TOKEN_PREFIX + userId + ":*");
        if (deviceKeys != null && !deviceKeys.isEmpty()) {
            redisTemplate.delete(deviceKeys);
        }
        redisTemplate.delete(DEVICE_PREFIX + userId);
        log.info("撤销用户所有 Token: userId={}", userId);
    }

    /**
     * 延长 Token 有效期 (rememberMe)
     */
    public void extendTokenExpiry(String userId, String deviceId, int extraDays) {
        String key = buildKey(userId, deviceId);
        RefreshTokenData data = getRefreshTokenData(key);
        if (data != null) {
            long ttlDays = jwtConfig.getRefreshTokenExpiryDays() + extraDays;
            redisTemplate.expire(key, ttlDays, TimeUnit.DAYS);
        }
    }

    private String buildKey(String userId, String deviceId) {
        String safeDeviceId = deviceId != null ? deviceId : "default";
        return TOKEN_PREFIX + userId + ":" + safeDeviceId;
    }

    private String findUserIdByToken(String token, String deviceId) {
        // 简化实现：假设 deviceId 已知，直接查找
        // 生产环境建议维护 token -> userId 映射
        String pattern = TOKEN_PREFIX + "*:" + (deviceId != null ? deviceId : "default");
        var keys = redisTemplate.keys(pattern);
        if (keys != null) {
            for (String key : keys) {
                RefreshTokenData data = getRefreshTokenData(key);
                if (data != null && token.equals(data.token())) {
                    return data.userId();
                }
            }
        }
        return null;
    }

    private void addDeviceToUser(String userId, String deviceId) {
        String key = DEVICE_PREFIX + userId;
        String safeDeviceId = deviceId != null ? deviceId : "default";
        redisTemplate.opsForSet().add(key, safeDeviceId);
        redisTemplate.expire(key, jwtConfig.getRefreshTokenExpiryDays(), TimeUnit.DAYS);
    }

    private void removeDeviceFromUser(String userId, String deviceId) {
        String key = DEVICE_PREFIX + userId;
        String safeDeviceId = deviceId != null ? deviceId : "default";
        redisTemplate.opsForSet().remove(key, safeDeviceId);
    }

    private RefreshTokenData getRefreshTokenData(String key) {
        Object raw = redisTemplate.opsForValue().get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof RefreshTokenData rtd) {
            return rtd;
        }
        String json = raw instanceof String s ? s : jsonUtils.toJson(raw);
        return jsonUtils.fromJson(json, RefreshTokenData.class);
    }

    /**
     * Refresh Token 数据结构
     */
    public record RefreshTokenData(
            String token,
            String userId,
            String deviceId,
            String requestId,
            long createdAt
    ) {}
}