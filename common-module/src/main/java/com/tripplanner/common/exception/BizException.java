package com.tripplanner.common.exception;

import lombok.Getter;

import java.util.Map;

/**
 * 业务异常基类
 */
@Getter
public class BizException extends RuntimeException {

    private final String code;
    private final Map<String, Object> details;
    private final int httpStatus;

    public BizException(String code, String message) {
        this(code, message, null, 400);
    }

    public BizException(String code, String message, Map<String, Object> details) {
        this(code, message, details, 400);
    }

    public BizException(String code, String message, int httpStatus) {
        this(code, message, null, httpStatus);
    }

    public BizException(String code, String message, Map<String, Object> details, int httpStatus) {
        super(message);
        this.code = code;
        this.details = details;
        this.httpStatus = httpStatus;
    }

    // 常用业务异常静态工厂方法

    public static BizException validationError(String message, Map<String, Object> details) {
        return new BizException("VALIDATION_ERROR", message, details, 400);
    }

    public static BizException notFound(String message) {
        return new BizException("NOT_FOUND", message, null, 404);
    }

    public static BizException notFound(String resource, String id) {
        return new BizException("NOT_FOUND", resource + " 不存在: " + id, null, 404);
    }

    public static BizException unauthorized(String message) {
        return new BizException("UNAUTHORIZED", message, null, 401);
    }

    public static BizException forbidden(String message) {
        return new BizException("FORBIDDEN", message, null, 403);
    }

    public static BizException conflict(String message) {
        return new BizException("CONFLICT", message, null, 409);
    }

    public static BizException planningFailed(String message) {
        return new BizException("PLANNING_FAILED", message, null, 500);
    }

    public static BizException planningTimeout() {
        return new BizException("PLANNING_TIMEOUT", "规划任务超时，请稍后查看结果或简化行程", null, 504);
    }

    public static BizException geocodeFailed(String placeName) {
        return new BizException("GEOCODE_FAILED", "无法识别地点: " + placeName + "，请补充地址或坐标", null, 422);
    }

    public static BizException timeWindowConflict(String message) {
        return new BizException("TIME_WINDOW_CONFLICT", message, null, 422);
    }

    public static BizException rateLimited(String message) {
        return new BizException("RATE_LIMITED", message, null, 429);
    }

    public static BizException internalError(String message) {
        return new BizException("INTERNAL_ERROR", message, null, 500);
    }
}