package com.tripplanner.plan.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 强制请求/响应按 UTF-8 编码处理。
 * 防止 Feign / 网关 / 测试客户端未带 charset 时中文变 ?。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Utf8EncodingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getCharacterEncoding() == null
                || StandardCharsets.UTF_8.name().equalsIgnoreCase(request.getCharacterEncoding())
                || "ISO-8859-1".equalsIgnoreCase(request.getCharacterEncoding())) {
            // 显式按 UTF-8 重新解析 body（仅当客户端未声明正确 charset 或用了 latin1）
            String ct = request.getContentType();
            if (ct == null || ct.toLowerCase(Locale.ROOT).contains("application/json")
                    || ct.toLowerCase(Locale.ROOT).contains("text/plain")
                    || ct.toLowerCase(Locale.ROOT).contains("application/x-www-form-urlencoded")) {
                if (request.getCharacterEncoding() == null
                        || "ISO-8859-1".equalsIgnoreCase(request.getCharacterEncoding())) {
                    request.setCharacterEncoding(StandardCharsets.UTF_8.name());
                }
            }
        }
        if (!response.isCommitted()) {
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        }
        filterChain.doFilter(request, response);
    }
}
