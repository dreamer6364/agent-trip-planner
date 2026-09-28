package com.tripplanner.auth.config;

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

/**
 * 强制请求/响应按 UTF-8 编码处理，防止中文变 ?。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Utf8EncodingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getCharacterEncoding() == null
                || "ISO-8859-1".equalsIgnoreCase(request.getCharacterEncoding())) {
            request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        }
        if (!response.isCommitted()) {
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        }
        filterChain.doFilter(request, response);
    }
}
