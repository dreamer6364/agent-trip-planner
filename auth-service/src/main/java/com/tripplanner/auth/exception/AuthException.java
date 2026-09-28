package com.tripplanner.auth.exception;

import com.tripplanner.common.exception.BizException;

/**
 * 认证相关业务异常
 */
public class AuthException extends BizException {

    public AuthException(String code, String message) {
        super(code, message, 401);
    }

    public AuthException(String code, String message, java.util.Map<String, Object> details) {
        super(code, message, details, 401);
    }

    public static AuthException invalidCredentials() {
        return new AuthException("INVALID_CREDENTIALS", "邮箱或密码错误");
    }

    public static AuthException accountDisabled() {
        return new AuthException("ACCOUNT_DISABLED", "账号已被禁用");
    }

    public static AuthException accountNotFound() {
        return new AuthException("ACCOUNT_NOT_FOUND", "账号不存在");
    }

    public static AuthException emailAlreadyExists() {
        return new AuthException("EMAIL_ALREADY_EXISTS", "邮箱已被注册");
    }

    public static AuthException passwordMismatch() {
        return new AuthException("PASSWORD_MISMATCH", "两次输入的密码不一致");
    }

    public static AuthException currentPasswordIncorrect() {
        return new AuthException("CURRENT_PASSWORD_INCORRECT", "当前密码错误");
    }
}