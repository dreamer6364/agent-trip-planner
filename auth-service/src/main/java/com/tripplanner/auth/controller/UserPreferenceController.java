package com.tripplanner.auth.controller;

import com.tripplanner.auth.dto.request.UpdatePreferenceRequest;
import com.tripplanner.auth.dto.response.UserPreferenceResponse;
import com.tripplanner.auth.service.UserPreferenceService;
import com.tripplanner.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 用户偏好控制器
 */
@Tag(name = "用户偏好", description = "获取/更新用户行程偏好配置")
@RestController
@RequestMapping("/api/auth/preferences")
@RequiredArgsConstructor
public class UserPreferenceController {

    private final UserPreferenceService preferenceService;

    @Operation(summary = "获取当前用户偏好")
    @GetMapping
    public ResponseEntity<ApiResponse<UserPreferenceResponse>> getPreference(
            @AuthenticationPrincipal com.tripplanner.auth.security.JwtAuthenticationToken auth
    ) {
        UserPreferenceResponse pref = preferenceService.getPreference(auth.getUserId());
        return ResponseEntity.ok(ApiResponse.success(pref));
    }

    @Operation(summary = "全量更新偏好")
    @PutMapping
    public ResponseEntity<ApiResponse<UserPreferenceResponse>> updatePreference(
            @AuthenticationPrincipal com.tripplanner.auth.security.JwtAuthenticationToken auth,
            @Valid @RequestBody UpdatePreferenceRequest request
    ) {
        UserPreferenceResponse pref = preferenceService.updatePreference(auth.getUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(pref));
    }

    @Operation(summary = "部分更新偏好")
    @PatchMapping
    public ResponseEntity<ApiResponse<UserPreferenceResponse>> patchPreference(
            @AuthenticationPrincipal com.tripplanner.auth.security.JwtAuthenticationToken auth,
            @Valid @RequestBody UpdatePreferenceRequest request
    ) {
        UserPreferenceResponse pref = preferenceService.patchPreference(auth.getUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(pref));
    }
}