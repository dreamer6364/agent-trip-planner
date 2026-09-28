package com.tripplanner.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 网关 Header 认证过滤器
 * 从网关注入的 X-User-* Header 中提取用户信息，
 * 构建 JwtAuthenticationToken 放入 SecurityContext
 */
@Slf4j
public class GatewayHeaderAuthFilter extends OncePerRequestFilter {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_EMAIL_HEADER = "X-User-Email";
    private static final String USER_NAME_HEADER = "X-User-Name";
    private static final String USER_ROLES_HEADER = "X-User-Roles";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 已有认证则跳过
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = request.getHeader(USER_ID_HEADER);
        if (StringUtils.hasText(userId)) {
            String email = request.getHeader(USER_EMAIL_HEADER);
            String name = request.getHeader(USER_NAME_HEADER);
            String rolesStr = request.getHeader(USER_ROLES_HEADER);

            List<SimpleGrantedAuthority> authorities = List.of();
            if (StringUtils.hasText(rolesStr)) {
                authorities = Arrays.stream(rolesStr.split(","))
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r.trim()))
                        .collect(Collectors.toList());
            }

            JwtAuthenticationToken authToken = new JwtAuthenticationToken(
                    userId,
                    email != null ? email : "",
                    name != null ? name : "",
                    authorities
            );

            SecurityContextHolder.getContext().setAuthentication(authToken);
            log.debug("GatewayHeaderAuth: 设置用户认证 userId={}", userId);
        }

        filterChain.doFilter(request, response);
    }
}
