package com.tripplanner.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 资源级权限评估服务
 * 支持 trip:read, trip:write, trip:delete, trip:share
 * 供所有微服务共用
 */
@Slf4j
@Service
public class PermissionEvaluationService {

    // 角色权限映射
    private static final Set<String> ADMIN_PERMISSIONS = Set.of(
            "trip:read", "trip:write", "trip:delete", "trip:share",
            "user:read", "user:write", "user:delete",
            "admin:*"
    );

    private static final Set<String> USER_PERMISSIONS = Set.of(
            "trip:read", "trip:write", "trip:delete", "trip:share"
    );

    /**
     * 判断用户是否有权限
     *
     * @param userId     用户 ID
     * @param resourceId 资源 ID (可为 null，表示任意资源)
     * @param permission 权限字符串，如 "trip:write"
     * @return 是否有权限
     */
    public boolean hasPermission(String userId, String resourceId, String permission) {
        // 1. 获取用户角色 (实际项目中从数据库/缓存获取)
        boolean isAdmin = isAdmin(userId);

        // 2. 角色权限检查
        Set<String> permissions = isAdmin ? ADMIN_PERMISSIONS : USER_PERMISSIONS;
        
        // 支持通配符
        if (permissions.contains(permission)) {
            return true;
        }
        
        // 检查通配符权限 (如 admin:*)
        for (String p : permissions) {
            if (p.endsWith("*") && permission.startsWith(p.substring(0, p.length() - 1))) {
                return true;
            }
        }

        // 3. 资源级权限检查 (如果有 resourceId)
        if (resourceId != null && !resourceId.isEmpty()) {
            return checkResourcePermission(userId, resourceId, permission);
        }

        return false;
    }

    /**
     * 资源级权限检查
     */
    private boolean checkResourcePermission(String userId, String resourceId, String permission) {
        log.debug("资源权限检查: userId={}, resourceId={}, permission={}", userId, resourceId, permission);
        return true; // 暂时放行，后续结合 TripService 实现
    }

    private boolean isAdmin(String userId) {
        return false; // TODO: 从用户表查询 role 字段或 Redis 缓存
    }
}