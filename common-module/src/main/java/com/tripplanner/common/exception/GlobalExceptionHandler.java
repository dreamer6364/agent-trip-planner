package com.tripplanner.common.exception;

import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.common.response.ErrorDetail;
import com.tripplanner.common.util.RequestIdUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 全局异常处理器 (Servlet Web 应用)
 * Gateway (WebFlux) 不加载此类，使用自己的异常处理
 */
@Slf4j
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalExceptionHandler {

    /**
     * 业务异常
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException e, HttpServletRequest request) {
        log.warn("业务异常: code={}, message={}, path={}", e.getCode(), e.getMessage(), request.getRequestURI());
        return ResponseEntity.status(e.getHttpStatus())
                .body(ApiResponse.<Void>error(e.getCode(), e.getMessage(), e.getDetails())
                        .withRequestId(RequestIdUtils.getRequestId()));
    }

    /**
     * 参数校验异常 (@Valid)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, Object> details = new HashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            details.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        log.warn("参数校验失败: details={}, path={}", details, request.getRequestURI());
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>error("VALIDATION_ERROR", "请求参数校验失败", details)
                        .withRequestId(RequestIdUtils.getRequestId()));
    }

    /**
     * 表单绑定异常
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException e, HttpServletRequest request) {
        Map<String, Object> details = new HashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            details.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>error("VALIDATION_ERROR", "请求参数绑定失败", details)
                        .withRequestId(RequestIdUtils.getRequestId()));
    }

    /**
     * 方法参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        String message = String.format("参数 '%s' 类型错误，期望类型: %s", e.getName(), e.getRequiredType().getSimpleName());
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>error("PARAM_TYPE_MISMATCH", message)
                        .withRequestId(RequestIdUtils.getRequestId()));
    }

    /**
     * 缺少请求参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>error("MISSING_PARAMETER", "缺少必填参数: " + e.getParameterName())
                        .withRequestId(RequestIdUtils.getRequestId()));
    }

    /**
     * JSON 解析失败
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>error("MALFORMED_JSON", "请求体 JSON 格式错误: " + e.getMostSpecificCause().getMessage())
                        .withRequestId(RequestIdUtils.getRequestId()));
    }

    /**
     * Bean Validation 异常 (方法级别 @Validated)
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e, HttpServletRequest request) {
        Map<String, Object> details = new HashMap<>();
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            String path = violation.getPropertyPath().toString();
            details.put(path, violation.getMessage());
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>error("VALIDATION_ERROR", "参数校验失败", details)
                        .withRequestId(RequestIdUtils.getRequestId()));
    }

    /**
     * 数据完整性冲突 (唯一约束、外键等)
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e, HttpServletRequest request) {
        String message = "数据冲突";
        String cause = e.getMostSpecificCause().getMessage();
        if (cause != null) {
            if (cause.contains("Duplicate entry") || cause.contains("unique constraint")) {
                message = "数据已存在，请勿重复提交";
            } else if (cause.contains("foreign key")) {
                message = "关联数据不存在";
            }
        }
        log.warn("数据完整性冲突: {}", cause);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.<Void>error("DATA_CONFLICT", message)
                        .withRequestId(RequestIdUtils.getRequestId()));
    }

    /**
     * 认证失败
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.<Void>error("UNAUTHORIZED", "用户名或密码错误")
                        .withRequestId(RequestIdUtils.getRequestId()));
    }

    /**
     * 权限不足
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.<Void>error("FORBIDDEN", "权限不足: " + e.getMessage())
                        .withRequestId(RequestIdUtils.getRequestId()));
    }

    /**
     * 404 未找到处理器
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandlerFound(NoHandlerFoundException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.<Void>error("NOT_FOUND", "接口不存在: " + e.getHttpMethod() + " " + e.getRequestURL())
                        .withRequestId(RequestIdUtils.getRequestId()));
    }

    /**
     * 非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>error("ILLEGAL_ARGUMENT", e.getMessage())
                        .withRequestId(RequestIdUtils.getRequestId()));
    }

    /**
     * 非法状态异常
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.<Void>error("ILLEGAL_STATE", e.getMessage())
                        .withRequestId(RequestIdUtils.getRequestId()));
    }

    /**
     * 未捕获的异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e, HttpServletRequest request) {
        log.error("未处理异常: path={}, error={}", request.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.<Void>error("INTERNAL_ERROR", "服务器内部错误，请稍后重试")
                        .withRequestId(RequestIdUtils.getRequestId()));
    }
}