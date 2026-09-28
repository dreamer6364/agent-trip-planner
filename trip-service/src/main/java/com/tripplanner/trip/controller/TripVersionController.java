package com.tripplanner.trip.controller;

import com.tripplanner.common.security.JwtAuthenticationToken;
import com.tripplanner.common.annotation.RequirePermission;
import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.common.response.Meta;
import com.tripplanner.trip.dto.request.CreateVersionRequest;
import com.tripplanner.trip.dto.request.RollbackVersionRequest;
import com.tripplanner.trip.dto.response.TripVersionResponse;
import com.tripplanner.trip.service.TripVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 行程版本控制器
 */
@Tag(name = "行程版本", description = "版本历史、回滚、分支")
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripVersionController {

    private final TripVersionService versionService;

    @Operation(summary = "获取版本历史列表")
    @GetMapping("/{id}/versions")
    @RequirePermission(value = "trip:read", resourceId = "#id")
    public ResponseEntity<ApiResponse<List<TripVersionResponse>>> listVersions(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        List<TripVersionResponse> versions = versionService.getVersionHistory(id, page, size)
                .stream()
                .map(versionService::toResponse)
                .toList();

        long total = versionService.countVersions(id);
        return ResponseEntity.ok(ApiResponse.success(versions, Meta.builder()
                .page(page)
                .size(size)
                .total(total)
                .build()));
    }

    @Operation(summary = "获取指定版本详情")
    @GetMapping("/{id}/versions/{versionNum}")
    @RequirePermission(value = "trip:read", resourceId = "#id")
    public ResponseEntity<ApiResponse<TripVersionResponse>> getVersion(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id,
            @PathVariable int versionNum
    ) {
        TripVersionResponse version = versionService.toResponse(versionService.getVersionByNum(id, versionNum));
        return ResponseEntity.ok(ApiResponse.success(version));
    }

    @Operation(summary = "基于反馈创建新版本 (触发重新规划)")
    @PostMapping("/{id}/versions")
    @RequirePermission(value = "trip:write", resourceId = "#id")
    public ResponseEntity<ApiResponse<TripVersionResponse>> createVersion(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id,
            @Valid @RequestBody CreateVersionRequest request
    ) {
        TripVersionResponse version = versionService.toResponse(
                versionService.createVersionFromFeedback(auth.getUserId(), id, request)
        );
        return ResponseEntity.accepted().body(ApiResponse.success(version));
    }

    @Operation(summary = "回滚到指定版本")
    @PostMapping("/{id}/versions/{versionNum}/rollback")
    @RequirePermission(value = "trip:write", resourceId = "#id")
    public ResponseEntity<ApiResponse<TripVersionResponse>> rollbackVersion(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id,
            @PathVariable int versionNum,
            @RequestBody RollbackVersionRequest request
    ) {
        String versionId = versionService.getVersionByNum(id, versionNum).getId();
        TripVersionResponse version = versionService.toResponse(
                versionService.rollbackToVersion(auth.getUserId(), id, versionId, request)
        );
        return ResponseEntity.ok(ApiResponse.success(version));
    }

    @Operation(summary = "基于历史版本分支新建行程")
    @PostMapping("/{id}/versions/{versionNum}/fork")
    @RequirePermission(value = "trip:write", resourceId = "#id")
    public ResponseEntity<ApiResponse<Map<String, String>>> forkVersion(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id,
            @PathVariable int versionNum,
            @RequestParam(required = false) String title
    ) {
        String versionId = versionService.getVersionByNum(id, versionNum).getId();
        var newTrip = versionService.forkFromVersion(auth.getUserId(), id, versionId, title);
        return ResponseEntity.status(201).body(ApiResponse.success(Map.of(
                "newTripId", newTrip.getId(),
                "message", "分支行程创建成功"
        )));
    }
}
