package com.tripplanner.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 资源权限注解
 * 用于方法级权限控制，配合 PermissionEvaluator 使用
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /**
     * 权限标识，格式：resource:action
     * 例如：trip:write, trip:read, trip:delete, trip:share
     */
    String value();

    /**
     * 资源 ID 表达式（SpEL）
     * 例如：#tripId, #request.tripId, #id
     * 为空时表示不检查资源级权限，仅检查角色权限
     */
    String resourceId() default "";
}