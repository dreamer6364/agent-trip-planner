package com.tripplanner.auth.security;

import com.tripplanner.common.service.PermissionEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * 自定义权限评估器
 * 支持 @PreAuthorize("hasPermission(#tripId, 'trip:write')")
 */
@Component
@RequiredArgsConstructor
public class CustomPermissionEvaluator implements PermissionEvaluator {

    private final PermissionEvaluationService permissionEvaluationService;

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            return false;
        }
        String userId = jwtAuth.getUserId();
        String perm = permission.toString();

        // targetDomainObject 可以是资源 ID (String) 或资源对象
        String resourceId = targetDomainObject != null ? targetDomainObject.toString() : null;

        return permissionEvaluationService.hasPermission(userId, resourceId, perm);
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            return false;
        }
        String userId = jwtAuth.getUserId();
        String resourceId = targetId != null ? targetId.toString() : null;
        String perm = permission.toString();

        return permissionEvaluationService.hasPermission(userId, resourceId, perm);
    }
}