package com.tripplanner.trip.controller;

import com.tripplanner.common.security.JwtAuthenticationToken;
import com.tripplanner.common.annotation.RequirePermission;
import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.trip.dto.request.ShareTripRequest;
import com.tripplanner.trip.dto.response.ShareResponse;
import com.tripplanner.trip.service.TripShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 行程分享控制器
 */
@Tag(name = "行程分享", description = "生成/撤销分享链接、公开访问")
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripShareController {

    private final TripShareService shareService;

    @Operation(summary = "生成分享链接")
    @PostMapping("/{id}/share")
    @RequirePermission(value = "trip:share", resourceId = "#id")
    public ResponseEntity<ApiResponse<ShareResponse>> createShare(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id,
            @Valid @RequestBody ShareTripRequest request
    ) {
        ShareResponse response = shareService.createShare(auth.getUserId(), id, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "撤销分享链接")
    @DeleteMapping("/{id}/share")
    @RequirePermission(value = "trip:share", resourceId = "#id")
    public ResponseEntity<ApiResponse<Void>> revokeShare(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id
    ) {
        shareService.revokeShare(auth.getUserId(), id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "获取分享信息")
    @GetMapping("/{id}/share")
    @RequirePermission(value = "trip:read", resourceId = "#id")
    public ResponseEntity<ApiResponse<ShareResponse>> getShareInfo(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id
    ) {
        ShareResponse response = shareService.getShareInfo(auth.getUserId(), id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
