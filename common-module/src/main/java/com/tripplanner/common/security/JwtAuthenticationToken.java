package com.tripplanner.common.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * 自定义 JWT Authentication Token
 * 携带用户 ID、角色、权限等信息
 * 供所有微服务共用
 */
public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final String userId;
    private final String email;
    private final String name;

    public JwtAuthenticationToken(String userId, String email, String name, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.userId = userId;
        this.email = email;
        this.name = name;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null; // JWT 无需凭证
    }

    @Override
    public Object getPrincipal() {
        return this; // Principal 为 Token 自身，供 @AuthenticationPrincipal 解析
    }

    public String getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }
}