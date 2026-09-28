package com.tripplanner.auth.exception;

import com.tripplanner.common.exception.BizException;

/**
 * Token 过期/无效异常
 */
public class TokenException extends BizException {

    public TokenException(String code, String message) {
        super(code, message, 401);
    }

    public static TokenException accessTokenExpired() {
        return new TokenException("ACCESS_TOKEN_EXPIRED", "访问令牌已过期，请刷新");
    }

    public static TokenException refreshTokenExpired() {
        return new TokenException("REFRESH_TOKEN_EXPIRED", "刷新令牌已过期，请重新登录");
    }

    public static TokenException refreshTokenRevoked() {
        return new TokenException("REFRESH_TOKEN_REVOKED", "刷新令牌已失效，请重新登录");
    }

    public static TokenException invalidToken(String message) {
        return new TokenException("INVALID_TOKEN", message);
    }

    public static TokenException tokenMalformed() {
        return new TokenException("TOKEN_MALFORMED", "令牌格式错误");
    }

    public static TokenException tokenSignatureInvalid() {
        return new TokenException("TOKEN_SIGNATURE_INVALID", "令牌签名无效");
    }
}