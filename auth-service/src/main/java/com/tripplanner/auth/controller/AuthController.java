package com.tripplanner.auth.controller;

import com.tripplanner.auth.config.JwtConfig;
import com.tripplanner.auth.dto.request.LoginRequest;
import com.tripplanner.auth.dto.request.RefreshTokenRequest;
import com.tripplanner.auth.dto.request.RegisterRequest;
import com.tripplanner.auth.dto.request.UpdatePasswordRequest;
import com.tripplanner.auth.dto.request.UpdateProfileRequest;
import com.tripplanner.auth.dto.response.LoginResponse;
import com.tripplanner.auth.dto.response.UserProfileResponse;
import com.tripplanner.auth.service.JwtTokenService;
import com.tripplanner.auth.service.UserService;
import com.tripplanner.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 认证控制器
 */
@Tag(name = "认证授权", description = "注册、登录、Token 刷新、登出、用户信息")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtTokenService jwtTokenService;
    private final JwtConfig jwtConfig;

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<LoginResponse>> register(@Valid @RequestBody RegisterRequest request) {
        var user = userService.register(request);
        var tokens = jwtTokenService.generateTokens(
                new com.tripplanner.auth.security.UserPrincipal(
                        user.getId(), user.getEmail(), user.getPasswordHash(),
                        user.getName(), user.getStatus(), List.of("USER")
                ),
                null
        );
        return ResponseEntity.ok(ApiResponse.success(buildLoginResponse(user, tokens)));
    }

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        var user = userService.authenticate(request.getEmail(), request.getPassword());
        String deviceId = request.getDeviceId() != null ? request.getDeviceId() : httpRequest.getHeader("X-Device-Id");
        var tokens = jwtTokenService.generateTokens(
                new com.tripplanner.auth.security.UserPrincipal(
                        user.getId(), user.getEmail(), user.getPasswordHash(),
                        user.getName(), user.getStatus(), List.of("USER")
                ),
                deviceId
        );
        return ResponseEntity.ok(ApiResponse.success(buildLoginResponse(user, tokens)));
    }

    @Operation(summary = "刷新 Access Token")
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @Valid @RequestBody RefreshTokenRequest request,
            HttpServletRequest httpRequest
    ) {
        String deviceId = request.getDeviceId() != null ? request.getDeviceId() : httpRequest.getHeader("X-Device-Id");
        String accessToken = jwtTokenService.refreshAccessToken(request.getRefreshToken(), deviceId);

        // 这里简化：实际需要解析 refresh token 获取用户信息
        // 暂时返回基本结构
        return ResponseEntity.ok(ApiResponse.success(LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(request.getRefreshToken())
                .tokenType("Bearer")
                .expiresIn(jwtConfig.getAccessTokenExpiryMinutes() * 60)
                .build()));
    }

    @Operation(summary = "用户登出 (撤销 Refresh Token)")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal com.tripplanner.auth.security.JwtAuthenticationToken auth,
            HttpServletRequest request
    ) {
        String deviceId = request.getHeader("X-Device-Id");
        String refreshToken = request.getHeader("X-Refresh-Token");

        if (auth != null && refreshToken != null) {
            jwtTokenService.revokeRefreshToken(refreshToken, deviceId);
        }
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getCurrentUser(
            @AuthenticationPrincipal com.tripplanner.auth.security.JwtAuthenticationToken auth
    ) {
        if (auth == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("UNAUTHORIZED", "未登录"));
        }
        UserProfileResponse profile = userService.getProfile(auth.getUserId());
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    @Operation(summary = "更新个人信息")
    @PutMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateProfile(
            @AuthenticationPrincipal com.tripplanner.auth.security.JwtAuthenticationToken auth,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        UserProfileResponse profile = userService.updateProfile(auth.getUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    @Operation(summary = "修改密码")
    @PutMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> updatePassword(
            @AuthenticationPrincipal com.tripplanner.auth.security.JwtAuthenticationToken auth,
            @Valid @RequestBody UpdatePasswordRequest request
    ) {
        userService.updatePassword(auth.getUserId(), request);
        // 修改密码后撤销所有设备的 refresh token
        jwtTokenService.revokeRefreshToken("", null); // TODO: 实现撤销所有
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private LoginResponse buildLoginResponse(com.tripplanner.auth.entity.User user, JwtTokenService.TokenPair tokens) {
        return LoginResponse.builder()
                .accessToken(tokens.accessToken())
                .refreshToken(tokens.refreshToken())
                .tokenType("Bearer")
                .expiresIn(tokens.expiresIn())
                .user(UserProfileResponse.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .name(user.getName())
                        .avatarUrl(user.getAvatarUrl())
                        .status(user.getStatus())
                        .lastLoginAt(user.getLastLoginAt())
                        .createdAt(user.getCreatedAt())
                        .build())
                .build();
    }
}