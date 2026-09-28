package com.tripplanner.common.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 错误详情
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorDetail implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务错误码
     * 例如：VALIDATION_ERROR, NOT_FOUND, PLANNING_FAILED, UNAUTHORIZED, FORBIDDEN
     */
    private String code;

    /**
     * 用户可读的错误消息
     */
    private String message;

    /**
     * 字段级错误详情（验证失败时）
     */
    private Map<String, Object> details;

    /**
     * 请求追踪 ID
     */
    private String requestId;
}