package com.tripplanner.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * 请求 ID 工具类
 * 用于链路追踪，从 Header 获取或生成 X-Request-Id
 */
public final class RequestIdUtils {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String REQUEST_ATTRIBUTE_NAME = "REQUEST_ID";

    private RequestIdUtils() {}

    /**
     * 获取当前请求的 Request ID
     * 优先从 Header 获取，没有则生成新的
     */
    public static String getRequestId() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return generateRequestId();
        }
        
        HttpServletRequest request = attrs.getRequest();
        String requestId = (String) request.getAttribute(REQUEST_ATTRIBUTE_NAME);
        if (requestId == null) {
            requestId = request.getHeader(HEADER_NAME);
            if (requestId == null || requestId.isBlank()) {
                requestId = generateRequestId();
            }
            request.setAttribute(REQUEST_ATTRIBUTE_NAME, requestId);
        }
        return requestId;
    }

    /**
     * 生成新的 Request ID
     */
    public static String generateRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 设置当前请求的 Request ID
     */
    public static void setRequestId(String requestId) {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.getRequest().setAttribute(REQUEST_ATTRIBUTE_NAME, requestId);
        }
    }
}