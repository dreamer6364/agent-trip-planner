package com.tripplanner.auth.service;

import com.tripplanner.auth.config.JwtConfig;
import com.tripplanner.auth.security.JwtAuthenticationToken;
import com.tripplanner.auth.security.UserPrincipal;
import com.tripplanner.common.util.JsonUtils;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JWT Token 生成/解析/校验服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtConfig jwtConfig;
    private final RefreshTokenService refreshTokenService;
    private final JsonUtils jsonUtils;

    /**
     * 生成 Access Token + Refresh Token
     */
    public TokenPair generateTokens(UserPrincipal user, String deviceId) {
        String accessToken = generateAccessToken(user);
        String refreshToken = refreshTokenService.createRefreshToken(user.userId(), deviceId);

        return new TokenPair(accessToken, refreshToken, jwtConfig.getAccessTokenExpiryMinutes() * 60);
    }

    /**
     * 生成 Access Token (JWT)
     */
    public String generateAccessToken(UserPrincipal user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtConfig.getAccessTokenExpiryMinutes(), ChronoUnit.MINUTES);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtConfig.getIssuer())
                .issuedAt(now)
                .expiresAt(expiry)
                .subject(user.userId())
                .claim("email", user.email() != null ? user.email() : "")
                .claim("name", user.name() != null ? user.name() : "")
                .claim("roles", user.roles() != null ? user.roles() : List.of())
                .claim("jti", UUID.randomUUID().toString())
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * 校验 Token
     */
    public boolean validateToken(String token) {
        try {
            Jwt jwt = decodeToken(token);
            // Spring Security Jwt 没有 isExpired()，手动检查过期时间
            return jwt.getExpiresAt() != null && jwt.getExpiresAt().isAfter(Instant.now());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Token 校验失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 解析 Token 获取 Claims
     */
    public java.util.Map<String, Object> parseClaims(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtException("Invalid JWT format");
        }
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = jsonUtils.fromJson(payload, java.util.Map.class);
        return map != null ? map : new java.util.HashMap<>();
    }

    public Jwt decodeToken(String token) {
        java.util.Map<String, Object> claims = parseClaims(token);
        Instant now = Instant.now();
        Instant expiresAt = null;
        Object expObj = claims.get("exp");
        if (expObj instanceof Number) {
            expiresAt = Instant.ofEpochSecond(((Number) expObj).longValue());
        }
        if (expiresAt == null) {
            expiresAt = now.plus(jwtConfig.getAccessTokenExpiryMinutes(), ChronoUnit.MINUTES);
        }
        Instant issuedAt = null;
        Object iatObj = claims.get("iat");
        if (iatObj instanceof Number) {
            issuedAt = Instant.ofEpochSecond(((Number) iatObj).longValue());
        }
        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(issuedAt != null ? issuedAt : now)
                .expiresAt(expiresAt)
                .build();
    }

    public JwtAuthenticationToken getAuthentication(String token) {
        java.util.Map<String, Object> claims = parseClaims(token);
        String userId = (String) claims.get("sub");
        String email = (String) claims.get("email");
        String name = (String) claims.get("name");
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) claims.get("roles");
        UserPrincipal principal = new UserPrincipal(userId, email, null, name, "active", roles != null ? roles : List.of());
        return new JwtAuthenticationToken(userId, email, name, principal.getAuthorities());
    }

    /**
     * 刷新 Access Token
     */
    public String refreshAccessToken(String refreshToken, String deviceId) {
        String userId = refreshTokenService.validateAndGetUserId(refreshToken, deviceId);
        UserPrincipal principal = new UserPrincipal(
                userId,
                null,
                null,
                null,
                "active",
                List.of("USER")
        );
        return generateAccessToken(principal);
    }

    /**
     * 撤销 Refresh Token (登出)
     */
    public void revokeRefreshToken(String refreshToken, String deviceId) {
        refreshTokenService.revokeRefreshToken(refreshToken, deviceId);
    }

    /**
     * Token 对
     */
    public record TokenPair(
            String accessToken,
            String refreshToken,
            int expiresIn
    ) {}
}